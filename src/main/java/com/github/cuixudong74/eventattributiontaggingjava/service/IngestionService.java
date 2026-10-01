package com.github.cuixudong74.eventattributiontaggingjava.service;

import com.github.cuixudong74.eventattributiontaggingjava.attribution.AttributionEngine;
import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfig;
import com.github.cuixudong74.eventattributiontaggingjava.config.ConfigManager;
import com.github.cuixudong74.eventattributiontaggingjava.identity.IdentityGraph;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionCorrection;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionEvent;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.model.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.privacy.ConsentService;
import com.github.cuixudong74.eventattributiontaggingjava.store.EventStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 采集编排：
 *  1) 分配到达序号（保证乱序下仍有确定次序）；
 *  2) 隐私总量计数（未授权也计，用于对账），未授权事件到此为止，不进归因；
 *  3) eventId 去重，重复上报不重复计入；
 *  4) 身份归一后落库；
 *  5) 对受影响身份的转化重算归因：迟到/乱序事件会修正既有结论，
 *     修正前后差异记录为 AttributionCorrection，可追溯。
 * 并发语义：摄入持写锁、查询持读锁；查询读到的是某一时刻自洽的快照，
 * 查询期间到达的事件在锁释放后生效，不丢失。
 */
@Service
public class IngestionService {
    private final EventStore store;
    private final IdentityGraph identityGraph;
    private final AttributionEngine engine;
    private final ConfigManager configManager;
    private final ConsentService consentService;

    private final AtomicLong ingestSeq = new AtomicLong();
    private final Map<String, AttributionResult> results = new ConcurrentHashMap<>();
    private final Map<String, List<AttributionCorrection>> corrections = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public IngestionService(EventStore store, IdentityGraph identityGraph, AttributionEngine engine,
                            ConfigManager configManager, ConsentService consentService) {
        this.store = store;
        this.identityGraph = identityGraph;
        this.engine = engine;
        this.configManager = configManager;
        this.consentService = consentService;
    }

    /** 摄入一个事件；返回 true 表示进入归因管线，false 表示重复或未授权（均已计总量） */
    public boolean ingest(AttributionEvent raw) {
        AttributionEvent event = new AttributionEvent(raw.getEventId(), raw.getType(), raw.getUserId(),
                raw.getDeviceId(), raw.getChannel(), raw.getCampaignId(), raw.getEventTime(),
                ingestSeq.incrementAndGet(), raw.isConsented());
        consentService.recordTotal(event.isConsented());
        if (!event.isConsented()) {
            return false; // 未授权：只计总量，不进归因
        }
        lock.writeLock().lock();
        try {
            if (!store.addIfAbsent(event)) {
                return false; // 重复上报：不重复计入
            }
            recomputeAffected(event);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 只重算受影响身份（新事件归并后的身份键）下的转化，复杂度可控 */
    private void recomputeAffected(AttributionEvent newEvent) {
        AttributionConfig config = configManager.current(); // 到达时生效的配置，行为确定
        List<AttributionEvent> all = store.snapshot();
        List<AttributionEvent> touches = new ArrayList<>();
        List<AttributionEvent> conversions = new ArrayList<>();
        for (AttributionEvent e : all) {
            String key = identityOf(e);
            if (!key.equals(identityOf(newEvent))) {
                continue;
            }
            if (e.getType() == EventType.CONVERSION) {
                conversions.add(e);
            } else {
                touches.add(e);
            }
        }
        for (AttributionEvent conversion : conversions) {
            String identityKey = identityOf(conversion);
            AttributionResult before = results.get(conversion.getEventId());
            long version = before == null ? 1 : before.getVersion() + 1;
            AttributionResult after = engine.attribute(conversion, touches, config, identityKey, version);
            if (before == null || !before.getContributions().equals(after.getContributions())) {
                results.put(conversion.getEventId(), after);
                if (before != null) {
                    corrections.computeIfAbsent(conversion.getEventId(),
                            k -> new CopyOnWriteArrayList<>()).add(new AttributionCorrection(
                            conversion.getEventId(), newEvent.getEventId(), before, after,
                            "迟到/乱序事件 " + newEvent.getEventId() + " 到达，按事件时间归位后重算",
                            Instant.now()));
                }
            }
        }
    }

    private String identityOf(AttributionEvent e) {
        String key = identityGraph.resolve(e.getUserId(), e.getDeviceId());
        return key != null ? key : identityGraph.anonymousKey(e.getEventId());
    }

    /** 查询单个转化的归因结论（一致性快照） */
    public AttributionResult resultOf(String conversionEventId) {
        lock.readLock().lock();
        try {
            return results.get(conversionEventId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 全部归因结论的快照 */
    public Map<String, AttributionResult> allResults() {
        lock.readLock().lock();
        try {
            return Map.copyOf(results);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 某转化的修正历史（可追溯） */
    public List<AttributionCorrection> correctionsOf(String conversionEventId) {
        lock.readLock().lock();
        try {
            List<AttributionCorrection> list = corrections.get(conversionEventId);
            return list == null ? List.of() : List.copyOf(list);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 用户级明细：需授权令牌，越权拒绝 */
    public List<AttributionEvent> userDetail(String userId, String token) {
        consentService.requireUserDetailAccess(token);
        lock.readLock().lock();
        try {
            List<AttributionEvent> out = new ArrayList<>();
            for (AttributionEvent e : store.snapshot()) {
                if (userId.equals(e.getUserId())) {
                    out.add(e);
                }
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }
}
