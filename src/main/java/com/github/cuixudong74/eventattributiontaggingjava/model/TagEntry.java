package com.github.cuixudong74.eventattributiontaggingjava.model;

import java.time.Instant;

/**
 * 标签条目。priority 越大优先级越高；同优先级后写覆盖先写（以写入序号定序，保证确定性）。
 */
public record TagEntry(String key, String value, int priority, long writeSeq, Instant updatedAt) {}
