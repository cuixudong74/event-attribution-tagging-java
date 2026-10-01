package com.github.cuixudong74.eventattributiontaggingjava.snapshot;

import org.springframework.stereotype.Component;

/**
 * 快照持有者：写入在锁内完成并整体发布，查询通过 volatile 引用读取不可变快照。
 */
@Component
public class SnapshotHolder {

    private volatile Snapshot current = Snapshot.empty();

    public SnapshotHolder() {
    }

    public Snapshot get() {
        return current;
    }

    public synchronized void publish(Snapshot snapshot) {
        this.current = snapshot;
    }
}
