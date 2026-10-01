package com.github.cuixudong74.eventattributiontaggingjava.result;

/**
 * 总量对账口径：接收总数 = 已关联 + 未关联 + 未授权 + 重复。
 */
public final class Reconciliation {

    private final long totalReceived;
    private final long duplicates;
    private final long linked;
    private final long unlinked;
    private final long unauthorized;

    public Reconciliation(long totalReceived, long duplicates, long linked, long unlinked, long unauthorized) {
        this.totalReceived = totalReceived;
        this.duplicates = duplicates;
        this.linked = linked;
        this.unlinked = unlinked;
        this.unauthorized = unauthorized;
    }

    public long getTotalReceived() {
        return totalReceived;
    }

    public long getDuplicates() {
        return duplicates;
    }

    public long getLinked() {
        return linked;
    }

    public long getUnlinked() {
        return unlinked;
    }

    public long getUnauthorized() {
        return unauthorized;
    }

    /**
     * 对账恒等式：各分项之和必须等于接收总数。
     */
    public boolean isBalanced() {
        return totalReceived == duplicates + linked + unlinked + unauthorized;
    }
}
