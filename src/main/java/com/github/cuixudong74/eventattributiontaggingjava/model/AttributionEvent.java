package com.github.cuixudong74.eventattributiontaggingjava.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 投放事件。eventId 全局唯一，用于去重；
 * eventTime 为事件发生时间（可能乱序/迟到），ingestSeq 为到达顺序。
 */
public final class AttributionEvent {
    private final String eventId;
    private final EventType type;
    private final String userId;      // 登录态用户 id，可空
    private final String deviceId;    // 设备 id，可空
    private final String channel;     // 渠道
    private final String campaignId;  // 转化所属活动，可空
    private final Instant eventTime;
    private final long ingestSeq;     // 到达序号，由系统分配，保证确定性
    private final boolean consented;  // 隐私授权

    public AttributionEvent(String eventId, EventType type, String userId, String deviceId,
                            String channel, String campaignId, Instant eventTime,
                            long ingestSeq, boolean consented) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.type = Objects.requireNonNull(type, "type");
        this.userId = userId;
        this.deviceId = deviceId;
        this.channel = channel;
        this.campaignId = campaignId;
        this.eventTime = Objects.requireNonNull(eventTime, "eventTime");
        this.ingestSeq = ingestSeq;
        this.consented = consented;
    }

    public String getEventId() { return eventId; }
    public EventType getType() { return type; }
    public String getUserId() { return userId; }
    public String getDeviceId() { return deviceId; }
    public String getChannel() { return channel; }
    public String getCampaignId() { return campaignId; }
    public Instant getEventTime() { return eventTime; }
    public long getIngestSeq() { return ingestSeq; }
    public boolean isConsented() { return consented; }
}
