package com.github.cuixudong74.eventattributiontaggingjava.result;

import java.util.List;

/**
 * 归因结果修正前后的差异：新增、移除与权重变化的触点，并给出修正原因。
 */
public final class AttributionDiff {

    private final String conversionEventId;
    private final List<TouchCredit> added;
    private final List<TouchCredit> removed;
    private final List<TouchCredit> changed;
    private final String reason;

    public AttributionDiff(String conversionEventId, List<TouchCredit> added,
                           List<TouchCredit> removed, List<TouchCredit> changed, String reason) {
        this.conversionEventId = conversionEventId;
        this.added = List.copyOf(added);
        this.removed = List.copyOf(removed);
        this.changed = List.copyOf(changed);
        this.reason = reason;
    }

    public String getConversionEventId() {
        return conversionEventId;
    }

    public List<TouchCredit> getAdded() {
        return added;
    }

    public List<TouchCredit> getRemoved() {
        return removed;
    }

    public List<TouchCredit> getChanged() {
        return changed;
    }

    public String getReason() {
        return reason;
    }
}
