package com.github.cuixudong74.eventattributiontaggingjava.result;

/**
 * 单个触点对某次转化的贡献。
 */
public final class TouchCredit {

    private final String eventId;
    private final String eventType;
    private final String channel;
    private final long timestamp;
    private final double weight;

    public TouchCredit(String eventId, String eventType, String channel, long timestamp, double weight) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.channel = channel;
        this.timestamp = timestamp;
        this.weight = weight;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getChannel() {
        return channel;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getWeight() {
        return weight;
    }
}
