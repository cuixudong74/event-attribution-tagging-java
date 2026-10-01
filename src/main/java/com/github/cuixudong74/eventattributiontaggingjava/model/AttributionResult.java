package com.github.cuixudong74.eventattributiontaggingjava.model;

import java.time.Instant;
import java.util.List;

/**
 * 一次转化的归因结论。不可变，可安全跨线程共享。
 * contributions 按触点事件时间升序，weight 之和为 1（UNATTRIBUTED 时为空）。
 */
public final class AttributionResult {
    /** 未关联到任何触点时的标记渠道 */
    public static final String UNATTRIBUTED = "__UNATTRIBUTED__";

    public record Contribution(String touchEventId, String channel, Instant touchTime, double weight) {}

    private final String conversionEventId;
    private final String identityKey;      // 归一化后的身份键；无法关联时带 NO_IDENTITY 前缀
    private final List<Contribution> contributions;
    private final long version;            // 结果版本，迟到修正时递增

    public AttributionResult(String conversionEventId, String identityKey,
                             List<Contribution> contributions, long version) {
        this.conversionEventId = conversionEventId;
        this.identityKey = identityKey;
        this.contributions = List.copyOf(contributions);
        this.version = version;
    }

    public String getConversionEventId() { return conversionEventId; }
    public String getIdentityKey() { return identityKey; }
    public List<Contribution> getContributions() { return contributions; }
    public long getVersion() { return version; }
    public boolean isAttributed() { return !contributions.isEmpty(); }
}
