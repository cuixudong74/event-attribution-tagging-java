package com.github.cuixudong74.eventattributiontaggingjava.result;

import java.util.List;

/**
 * 某次转化的归因结果：窗口内各触点按所选模型分配贡献。
 */
public final class AttributionResult {

    private final String conversionEventId;
    private final String userId;
    private final String channel;
    private final long conversionTime;
    private final List<TouchCredit> credits;
    private final String model;

    public AttributionResult(String conversionEventId, String userId, String channel,
                             long conversionTime, List<TouchCredit> credits, String model) {
        this.conversionEventId = conversionEventId;
        this.userId = userId;
        this.channel = channel;
        this.conversionTime = conversionTime;
        this.credits = List.copyOf(credits);
        this.model = model;
    }

    public String getConversionEventId() {
        return conversionEventId;
    }

    public String getUserId() {
        return userId;
    }

    public String getChannel() {
        return channel;
    }

    public long getConversionTime() {
        return conversionTime;
    }

    public List<TouchCredit> getCredits() {
        return credits;
    }

    public String getModel() {
        return model;
    }
}
