package com.github.cuixudong74.eventattributiontaggingjava.store;

import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 事件存储：全局事件日志。
 *  - 按 eventId 去重，重复上报返回 false，不重复计入；
 *  - 读操作在读锁内拷贝出排序好的不可变快照：查询期间到达的写入不可见，
 *    查询读到自洽快照，写入也不会丢失；
 *  - 身份分组不在存储层固化（身份会随后续事件合并），由上层在重算时动态分组。
 */
@Component
public class EventStore {
    /** 确定性排序：先事件时间，再到达序号，再 eventId（迟到事件按时间归位，次序可复现） */
    public static final Comparator<AttributionEvent> ORDER =
            Comparator.comparing(AttributionEvent::getEventTime)
                    .thenComparingLong(AttributionEvent::getIngestSeq)
                    .thenComparing(AttributionEvent::getEventId);

    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();
    private final List<AttributionEvent> events = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 返回 true 表示新事件已落库，false 表示重复（不计入） */
    public boolean addIfAbsent(AttributionEvent event) {
        lock.writeLock().lock();
        try {
            if (!seenEventIds.add(event.getEventId())) {
                return false;
            }
            events.add(event);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 全部已去重事件的自洽快照，按确定性顺序排序 */
    public List<AttributionEvent> snapshot() {
        lock.readLock().lock();
        try {
            List<AttributionEvent> copy = new ArrayList<>(events);
            copy.sort(ORDER);
            return List.copyOf(copy);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean contains(String eventId) {
        return seenEventIds.contains(eventId);
    }

    public long totalDistinctEvents() {
        return seenEventIds.size();
    }
}
