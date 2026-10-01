package com.github.cuixudong74.eventattributiontaggingjava.event;

import java.util.Map;

/**
 * 投放事件。eventId 为去重键；userId 与 deviceId 均可能缺失；timestamp 为事件发生时间，
 * ingestTime 为到达时间（用于判定迟到/乱序）。
 */
public final class Event {

    private final String eventId;
    private final EventType type;
    private final String userId;
    private final String deviceId;
    private final String channel;
    private final long timestamp;
    private final long ingestTime;
    private final Map<String, String> properties;

    public Event(String eventId, EventType type, String userId, String deviceId,
                 String channel, long timestamp, long ingestTime, Map<String, String> properties) {
        this.eventId = eventId;
        this.type = type;
        this.userId = userId;
        this.deviceId = deviceId;
        this.channel = channel;
        this.timestamp = timestamp;
        this.ingestTime = ingestTime;
        this.properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public String getEventId() {
        return eventId;
    }

    public EventType getType() {
        return type;
    }

    public String getUserId() {
        return userId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getChannel() {
        return channel;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getIngestTime() {
        return ingestTime;
    }

    public Map<String, String> getProperties() {
        return properties;
    }
}
