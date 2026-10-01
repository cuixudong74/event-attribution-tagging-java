package com.github.cuixudong74.eventattributiontaggingjava.model;

import java.time.Instant;

/**
 * 迟到/乱序事件触发的归因修正记录：修正前后结论 + 触发原因，可追溯。
 */
public record AttributionCorrection(
        String conversionEventId,
        String triggerEventId,     // 触发修正的迟到事件
        AttributionResult before,  // 修正前（可能为 null 表示此前无结论）
        AttributionResult after,   // 修正后
        String reason,
        Instant correctedAt) {}
