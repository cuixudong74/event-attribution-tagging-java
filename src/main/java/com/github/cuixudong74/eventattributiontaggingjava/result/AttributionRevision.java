package com.github.cuixudong74.eventattributiontaggingjava.result;

import java.time.Instant;
import java.util.List;

/**
 * 一次归因修订：记录修订序号、发生时间、原因（迟到事件/配置变更等）与逐项差异，保证可追溯。
 */
public final class AttributionRevision {

    private final long revisionId;
    private final Instant occurredAt;
    private final String reason;
    private final List<AttributionDiff> diffs;

    public AttributionRevision(long revisionId, Instant occurredAt, String reason, List<AttributionDiff> diffs) {
        this.revisionId = revisionId;
        this.occurredAt = occurredAt;
        this.reason = reason;
        this.diffs = List.copyOf(diffs);
    }

    public long getRevisionId() {
        return revisionId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getReason() {
        return reason;
    }

    public List<AttributionDiff> getDiffs() {
        return diffs;
    }
}
