package com.github.cuixudong74.eventattributiontaggingjava.config;

import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionModelType;

import java.time.Duration;

/**
 * 归因配置。不可变；配置变更通过整体替换生效，
 * 在途事件按“到达时生效的配置”处理，行为确定。
 */
public final class AttributionConfig {
    private final AttributionModelType modelType;
    private final Duration attributionWindow;   // 归因窗口：触点须早于转化且不超出窗口
    private final Duration timeDecayHalfLife;   // 时间衰减半衰期（仅 TIME_DECAY 使用）
    private final long version;

    public AttributionConfig(AttributionModelType modelType, Duration attributionWindow,
                             Duration timeDecayHalfLife, long version) {
        this.modelType = modelType;
        this.attributionWindow = attributionWindow;
        this.timeDecayHalfLife = timeDecayHalfLife;
        this.version = version;
    }

    /** 校验配置合法性，非法时抛 ConfigException 并说明原因 */
    public void validate() {
        if (modelType == null) {
            throw new ConfigException("归因模型 modelType 不能为空");
        }
        if (attributionWindow == null || attributionWindow.isZero() || attributionWindow.isNegative()) {
            throw new ConfigException("归因窗口 attributionWindow 必须为正时长，当前=" + attributionWindow);
        }
        if (attributionWindow.toDays() > 365) {
            throw new ConfigException("归因窗口不得超过 365 天，当前=" + attributionWindow);
        }
        if (modelType == AttributionModelType.TIME_DECAY) {
            if (timeDecayHalfLife == null || timeDecayHalfLife.isZero() || timeDecayHalfLife.isNegative()) {
                throw new ConfigException("TIME_DECAY 模型要求 timeDecayHalfLife 为正时长，当前=" + timeDecayHalfLife);
            }
            if (timeDecayHalfLife.compareTo(attributionWindow) > 0) {
                throw new ConfigException("配置冲突：timeDecayHalfLife(" + timeDecayHalfLife
                        + ") 不应大于归因窗口(" + attributionWindow + ")");
            }
        }
        if (version < 0) {
            throw new ConfigException("配置版本 version 不能为负，当前=" + version);
        }
    }

    public AttributionModelType getModelType() { return modelType; }
    public Duration getAttributionWindow() { return attributionWindow; }
    public Duration getTimeDecayHalfLife() { return timeDecayHalfLife; }
    public long getVersion() { return version; }
}
