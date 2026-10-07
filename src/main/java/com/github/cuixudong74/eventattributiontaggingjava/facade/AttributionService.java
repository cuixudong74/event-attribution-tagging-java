package com.github.cuixudong74.eventattributiontaggingjava.facade;

import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfig;
import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfigLoader;
import com.github.cuixudong74.eventattributiontaggingjava.engine.AttributionEngine;
import com.github.cuixudong74.eventattributiontaggingjava.event.Event;
import com.github.cuixudong74.eventattributiontaggingjava.event.EventStatus;
import com.github.cuixudong74.eventattributiontaggingjava.identity.IdentityGraph;
import com.github.cuixudong74.eventattributiontaggingjava.privacy.AuthorizationService;
import com.github.cuixudong74.eventattributiontaggingjava.privacy.ConsentService;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionDiff;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionRevision;
import com.github.cuixudong74.eventattributiontaggingjava.result.Reconciliation;
import com.github.cuixudong74.eventattributiontaggingjava.result.TouchCredit;
import com.github.cuixudong74.eventattributiontaggingjava.snapshot.Snapshot;
import com.github.cuixudong74.eventattributiontaggingjava.snapshot.SnapshotHolder;
import com.github.cuixudong74.eventattributiontaggingjava.store.EventStore;
import com.github.cuixudong74.eventattributiontaggingjava.tag.Tag;
import com.github.cuixudong74.eventattributiontaggingjava.tag.TagKey;
import com.github.cuixudong74.eventattributiontaggingjava.tag.TagStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 归因能力门面：串行化摄入、整体发布快照，保证采集与查询并发时不丢事件、不读中间状态。
 */
@Service
public class AttributionService {

    /** 修订原因：首次建账 */
    public static final String REASON_INITIAL = "INITIAL";
    /** 修订原因：正常到达 */
    public static final String REASON_INCREMENTAL = "INCREMENTAL";
    /** 修订原因：迟到/乱序事件到达导致修正 */
    public static final String REASON_LATE_EVENT = "LATE_EVENT";
    /** 修订原因：配置变更导致重算 */
    public static final String REASON_CONFIG_CHANGED = "CONFIG_CHANGED";
    /** 修订原因：授权状态变更导致重算 */
    public static final String REASON_CONSENT_CHANGED = "CONSENT_CHANGED";

    private final EventStore eventStore = new EventStore();
    private final IdentityGraph identityGraph = new IdentityGraph();
    private final AttributionEngine engine = new AttributionEngine();
    private final SnapshotHolder snapshots;
    private final AttributionConfigLoader configLoader;
    private final ConsentService consentService;
    private final AuthorizationService authorizationService;
    private final TagStore tagStore;
    private final Object ingestLock = new Object();

    private final List<AttributionRevision> revisions = new ArrayList<>();
    private long revisionCounter = 0;
    private long cumulativeDuplicates = 0;
    private long maxObservedTimestamp = Long.MIN_VALUE;

    public AttributionService(SnapshotHolder snapshots,
                              AttributionConfigLoader configLoader,
                              ConsentService consentService,
                              AuthorizationService authorizationService,
                              TagStore tagStore) {
        this.snapshots = snapshots;
        this.configLoader = configLoader;
        this.consentService = consentService;
        this.authorizationService = authorizationService;
        this.tagStore = tagStore;
    }

    /**
     * @return 当前生效的归因配置
     */
    public AttributionConfig config() {
        ensureConfigLoaded();
        return configLoader.getCurrent();
    }

    /**
     * 批量摄入事件：去重、身份解析、状态判定、重算归因、发布新快照。
     */
    public IngestResult ingest(List<Event> events) {
        synchronized (ingestLock) {
            ensureConfigLoaded();
            long previousMaxTimestamp = maxObservedTimestamp;
            int accepted = 0;
            int duplicates = 0;
            long batchMinTimestamp = Long.MAX_VALUE;
            for (Event event : events) {
                if (event == null) {
                    continue;
                }
                if (eventStore.append(event)) {
                    accepted++;
                    batchMinTimestamp = Math.min(batchMinTimestamp, event.getTimestamp());
                    maxObservedTimestamp = Math.max(maxObservedTimestamp, event.getTimestamp());
                } else {
                    duplicates++;
                }
            }
            cumulativeDuplicates += duplicates;

            boolean lateArrival = accepted > 0 && previousMaxTimestamp != Long.MIN_VALUE
                    && batchMinTimestamp < previousMaxTimestamp;
            String reason = lateArrival ? REASON_LATE_EVENT : REASON_INCREMENTAL;
            publishWithReason(reason);
            return new IngestResult(accepted, duplicates, snapshots.get().getVersion());
        }
    }

    /**
     * @return 当前不可变快照
     */
    public Snapshot snapshot() {
        return snapshots.get();
    }

    /**
     * 读取某用户的归因明细（用户级数据，需鉴权）。
     */
    public List<AttributionResult> userResults(String actor, String userId) {
        authorizationService.authorizeUserDetail(actor, userId);
        return snapshots.get().getResults().values().stream()
                .filter(result -> result.getUserId().equals(userId))
                .toList();
    }

    /**
     * @return 总量对账口径（聚合数据，无需用户级鉴权）
     */
    public Reconciliation reconciliation() {
        return snapshots.get().getReconciliation();
    }

    /**
     * @return 归因修订历史（修正前后差异可追溯）。
     * 读取自当前不可变快照：与同一时刻的归因结果、对账口径同属一个版本，
     * 采集并发进行时也不会读到中间状态或抛异常；历史只增不减，已返回的条目不会回退。
     */
    public List<AttributionRevision> revisions() {
        return snapshots.get().getRevisions();
    }

    /**
     * 重新加载配置并按新配置确定性重算全量结果。
     */
    public AttributionConfig reloadConfig() {
        synchronized (ingestLock) {
            AttributionConfig config = configLoader.reload();
            publishWithReason(REASON_CONFIG_CHANGED);
            return config;
        }
    }

    /**
     * 写入标签（合并规则见 TagStore）。
     */
    public Tag writeTag(TagKey key, Tag tag) {
        return tagStore.put(key, tag);
    }

    /**
     * 读取标签（用户级数据，需鉴权）。
     */
    public Tag readTag(String actor, TagKey key) {
        authorizationService.authorizeUserDetail(actor, key.getUserId());
        return tagStore.get(key).orElse(null);
    }

    /**
     * 授权某用户：此前未授权的事件将在重算后进入归因。
     */
    public void grantConsent(String userId) {
        synchronized (ingestLock) {
            consentService.grant(userId);
            publishWithReason(REASON_CONSENT_CHANGED);
        }
    }

    /**
     * 撤销授权：该用户事件退出归因，但总量口径仍保留记录。
     */
    public void revokeConsent(String userId) {
        synchronized (ingestLock) {
            consentService.revoke(userId);
            publishWithReason(REASON_CONSENT_CHANGED);
        }
    }

    /**
     * 基于当前全量事件重建身份图、判定状态、重算结果、与上一快照做差异并整体发布。
     */
    private void publishWithReason(String reason) {
        ensureConfigLoaded();
        identityGraph.clear();
        for (Event event : eventStore.all()) {
            identityGraph.link(event.getUserId(), event.getDeviceId());
        }

        Map<String, EventStatus> statuses = new HashMap<>();
        long linked = 0;
        long unlinked = 0;
        long unauthorized = 0;
        for (Event event : eventStore.all()) {
            String uid = identityGraph.resolveCanonical(event.getUserId(), event.getDeviceId());
            EventStatus status;
            if (uid == null) {
                status = EventStatus.UNLINKED;
                unlinked++;
            } else if (!consentService.isConsented(uid)) {
                status = EventStatus.UNAUTHORIZED;
                unauthorized++;
            } else {
                status = EventStatus.LINKED;
                linked++;
            }
            statuses.put(event.getEventId(), status);
        }

        AttributionConfig config = configLoader.getCurrent();
        Map<String, AttributionResult> newResults =
                engine.compute(eventStore.all(), statuses, identityGraph, config);

        Snapshot previous = snapshots.get();
        List<AttributionDiff> diffs = diff(previous.getResults(), newResults);
        if (!diffs.isEmpty()) {
            revisionCounter++;
            revisions.add(new AttributionRevision(revisionCounter, Instant.now(), reason, diffs));
        }

        long totalReceived = eventStore.size() + cumulativeDuplicates;
        Reconciliation reconciliation =
                new Reconciliation(totalReceived, cumulativeDuplicates, linked, unlinked, unauthorized);

        Snapshot snapshot = new Snapshot(previous.getVersion() + 1, Instant.now(),
                newResults, statuses, reconciliation, List.copyOf(revisions));
        snapshots.publish(snapshot);
    }

    private List<AttributionDiff> diff(Map<String, AttributionResult> oldResults,
                                       Map<String, AttributionResult> newResults) {
        List<AttributionDiff> diffs = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(oldResults.keySet());
        keys.addAll(newResults.keySet());
        for (String conversionId : keys) {
            AttributionResult oldResult = oldResults.get(conversionId);
            AttributionResult newResult = newResults.get(conversionId);
            List<TouchCredit> added = new ArrayList<>();
            List<TouchCredit> removed = new ArrayList<>();
            List<TouchCredit> changed = new ArrayList<>();
            String diffReason;
            if (oldResult == null) {
                added.addAll(newResult.getCredits());
                diffReason = "NEW_CONVERSION";
            } else if (newResult == null) {
                removed.addAll(oldResult.getCredits());
                diffReason = "CONVERSION_LOST";
            } else {
                Map<String, TouchCredit> oldById = creditsById(oldResult);
                Map<String, TouchCredit> newById = creditsById(newResult);
                for (TouchCredit credit : newById.values()) {
                    if (!oldById.containsKey(credit.getEventId())) {
                        added.add(credit);
                    }
                }
                for (TouchCredit credit : oldById.values()) {
                    if (!newById.containsKey(credit.getEventId())) {
                        removed.add(credit);
                    }
                }
                for (TouchCredit credit : newById.values()) {
                    TouchCredit oldCredit = oldById.get(credit.getEventId());
                    if (oldCredit != null && Math.abs(oldCredit.getWeight() - credit.getWeight()) > 1e-9) {
                        changed.add(credit);
                    }
                }
                diffReason = "CREDIT_CHANGED";
            }
            if (!added.isEmpty() || !removed.isEmpty() || !changed.isEmpty()) {
                diffs.add(new AttributionDiff(conversionId, added, removed, changed, diffReason));
            }
        }
        return diffs;
    }

    private Map<String, TouchCredit> creditsById(AttributionResult result) {
        Map<String, TouchCredit> map = new LinkedHashMap<>();
        for (TouchCredit credit : result.getCredits()) {
            map.put(credit.getEventId(), credit);
        }
        return map;
    }

    private void ensureConfigLoaded() {
        if (configLoader.getCurrent() == null) {
            configLoader.load();
        }
    }

    /**
     * 批量摄入结果摘要。
     */
    public record IngestResult(int accepted, int duplicates, long snapshotVersion) {
    }
}
