package com.github.cuixudong74.eventattributiontaggingjava.snapshot;

import com.github.cuixudong74.eventattributiontaggingjava.event.EventStatus;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionRevision;
import com.github.cuixudong74.eventattributiontaggingjava.result.Reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 不可变快照：查询永远读到某个自洽的完整版本，不会读到中间状态。
 */
public final class Snapshot {

    private final long version;
    private final Instant publishedAt;
    private final Map<String, AttributionResult> results;
    private final Map<String, EventStatus> eventStatuses;
    private final Reconciliation reconciliation;
    private final List<AttributionRevision> revisions;

    public Snapshot(long version, Instant publishedAt,
                    Map<String, AttributionResult> results,
                    Map<String, EventStatus> eventStatuses,
                    Reconciliation reconciliation,
                    List<AttributionRevision> revisions) {
        this.version = version;
        this.publishedAt = publishedAt;
        this.results = Map.copyOf(results);
        this.eventStatuses = Map.copyOf(eventStatuses);
        this.reconciliation = reconciliation;
        this.revisions = List.copyOf(revisions);
    }

    public static Snapshot empty() {
        return new Snapshot(0L, java.time.Instant.EPOCH, java.util.Map.of(), java.util.Map.of(),
                new Reconciliation(0L, 0L, 0L, 0L, 0L), java.util.List.of());
    }

    public long getVersion() {
        return version;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Map<String, AttributionResult> getResults() {
        return results;
    }

    public Map<String, EventStatus> getEventStatuses() {
        return eventStatuses;
    }

    public Reconciliation getReconciliation() {
        return reconciliation;
    }

    public List<AttributionRevision> getRevisions() {
        return revisions;
    }
}
