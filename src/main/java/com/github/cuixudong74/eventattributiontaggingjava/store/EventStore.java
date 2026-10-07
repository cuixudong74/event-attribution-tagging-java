package com.github.cuixudong74.eventattributiontaggingjava.store;

import com.github.cuixudong74.eventattributiontaggingjava.event.Event;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件追加日志：只追加、不修改；以 eventId 去重，重复上报返回 false 且不计入归因。
 */
public class EventStore {

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();

    public EventStore() {
    }

    /**
     * 追加事件；若 eventId 已存在则视为重复上报，返回 false。
     */
    public synchronized boolean append(Event event) {
        if (event == null || event.getEventId() == null) {
            return false;
        }
        if (!seenEventIds.add(event.getEventId())) {
            return false;
        }
        events.add(event);
        return true;
    }

    /**
     * @return 调用时刻已追加事件的不可变快照（按到达顺序）
     */
    public List<Event> all() {
        return List.copyOf(events);
    }

    public int size() {
        return events.size();
    }
}
