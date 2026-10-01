package com.github.cuixudong74.eventattributiontaggingjava.tag;

/**
 * 标签：键值对 + 优先级 + 来源 + 写入时间。
 * 覆盖规则：高优先级覆盖低优先级；同优先级以较新写入为准。
 */
public final class Tag {

    private final String key;
    private final String value;
    private final int priority;
    private final String source;
    private final long updatedAt;

    public Tag(String key, String value, int priority, String source, long updatedAt) {
        this.key = key;
        this.value = value;
        this.priority = priority;
        this.source = source;
        this.updatedAt = updatedAt;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public int getPriority() {
        return priority;
    }

    public String getSource() {
        return source;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }
}
