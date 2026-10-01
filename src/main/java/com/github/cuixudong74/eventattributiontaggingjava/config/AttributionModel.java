package com.github.cuixudong74.eventattributiontaggingjava.config;

/**
 * 归因模型。
 */
public enum AttributionModel {
    /** 首次触点：窗口内最早的触点获得 100% 贡献 */
    FIRST_TOUCH,
    /** 末次触点：窗口内最晚的触点获得 100% 贡献 */
    LAST_TOUCH,
    /** 按时间加权：距转化越近的触点权重越高，权重和归一化为 1 */
    TIME_WEIGHTED
}
