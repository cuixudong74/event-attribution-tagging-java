package com.github.cuixudong74.eventattributiontaggingjava.snapshot;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 快照持有者：写入在锁内完成并整体发布，查询通过 volatile 引用读取不可变快照。
 */
@Component
public class SnapshotHolder {

    private volatile Snapshot current = Snapshot.empty();
    private final Map<Long, Snapshot> versions = new ConcurrentHashMap<>();

    public SnapshotHolder() {
        versions.put(current.getVersion(), current);
    }

    public Snapshot get() {
        return current;
    }

    public Snapshot get(long version) {
        return versions.get(version);
    }

    public synchronized void publish(Snapshot snapshot) {
        versions.put(snapshot.getVersion(), snapshot);
        this.current = snapshot;
    }
}
