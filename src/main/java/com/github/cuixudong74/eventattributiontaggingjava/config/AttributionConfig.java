package com.github.cuixudong74.eventattributiontaggingjava.config;

import com.github.cuixudong74.eventattributiontaggingjava.event.EventType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 不可变归因配置。窗口长度、归因模型、时间加权半衰期与参与归因的触点类型。
 */
public final class AttributionConfig {

    private final AttributionModel model;
    private final Duration window;
    private final Duration halfLife;
    private final List<String> touchTypes;

    public AttributionConfig(AttributionModel model, Duration window, Duration halfLife, List<String> touchTypes) {
        this.model = model;
        this.window = window;
        this.halfLife = halfLife;
        this.touchTypes = List.copyOf(touchTypes);
    }

    public AttributionModel getModel() {
        return model;
    }

    public Duration getWindow() {
        return window;
    }

    public Duration getHalfLife() {
        return halfLife;
    }

    public List<String> getTouchTypes() {
        return touchTypes;
    }

    /**
     * 校验配置的合法性与一致性，返回拒绝原因列表；空列表表示通过。
     */
    public List<String> validate() {
        List<String> reasons = new ArrayList<>();
        if (model == null) {
            reasons.add("attribution.model 不能为空");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            reasons.add("attribution.window 必须为正数，当前为: " + window);
        }
        if (model == AttributionModel.TIME_WEIGHTED
                && (halfLife == null || halfLife.isZero() || halfLife.isNegative())) {
            reasons.add("时间加权模型(TIME_WEIGHTED)必须配置正的 attribution.half-life，当前为: " + halfLife);
        }
        if (touchTypes == null || touchTypes.isEmpty()) {
            reasons.add("attribution.touch-types 不能为空，至少包含 IMPRESSION 或 CLICK");
        } else {
            for (String touchType : touchTypes) {
                if (touchType == null || touchType.isBlank()) {
                    reasons.add("attribution.touch-types 包含空值");
                    continue;
                }
                try {
                    EventType type = EventType.valueOf(touchType);
                    if (type == EventType.CONVERSION) {
                        reasons.add("触点类型不能包含 CONVERSION：转化是归因目标，不能作为触点 -> " + touchType);
                    }
                } catch (IllegalArgumentException ex) {
                    reasons.add("非法的触点类型: " + touchType + "，可选值: IMPRESSION, CLICK");
                }
            }
        }
        return reasons;
    }
}
