package com.github.cuixudong74.eventattributiontaggingjava.tag;

import java.util.Objects;

/**
 * 标签键：用户 + 渠道。
 */
public final class TagKey {

    private final String userId;
    private final String channel;

    public TagKey(String userId, String channel) {
        this.userId = userId;
        this.channel = channel;
    }

    public String getUserId() {
        return userId;
    }

    public String getChannel() {
        return channel;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TagKey tagKey)) {
            return false;
        }
        return Objects.equals(userId, tagKey.userId) && Objects.equals(channel, tagKey.channel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, channel);
    }
}
