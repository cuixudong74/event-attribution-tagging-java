package com.github.cuixudong74.eventattributiontaggingjava.attribution;

import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfig;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionEvent;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 归因引擎：纯函数式计算，同一输入 + 同一配置必然得到同一结果（可复现）。
 * 触点须满足：时间早于（或等于）转化时间，且落在归因窗口内。
 */
@Component
public class AttributionEngine {

    public AttributionResult attribute(AttributionEvent conversion, List<AttributionEvent> touches,
                                       AttributionConfig config, String identityKey, long version) {
        List<AttributionEvent> eligible = new ArrayList<>();
        for (AttributionEvent t : touches) {
            if (t.getEventTime().isAfter(conversion.getEventTime())) {
                continue; // 转化之后的触点不参与
            }
            Duration age = Duration.between(t.getEventTime(), conversion.getEventTime());
            if (age.compareTo(config.getAttributionWindow()) > 0) {
                continue; // 超出归因窗口
            }
            eligible.add(t);
        }

        List<AttributionResult.Contribution> contributions = switch (config.getModelType()) {
            case FIRST_TOUCH -> firstTouch(eligible);
            case LAST_TOUCH -> lastTouch(eligible);
            case TIME_DECAY -> timeDecay(eligible, conversion, config.getTimeDecayHalfLife());
        };
        return new AttributionResult(conversion.getEventId(), identityKey, contributions, version);
    }

    private List<AttributionResult.Contribution> firstTouch(List<AttributionEvent> eligible) {
        if (eligible.isEmpty()) {
            return List.of();
        }
        return List.of(toContribution(eligible.get(0), 1.0)); // 输入已按时间确定性排序
    }

    private List<AttributionResult.Contribution> lastTouch(List<AttributionEvent> eligible) {
        if (eligible.isEmpty()) {
            return List.of();
        }
        return List.of(toContribution(eligible.get(eligible.size() - 1), 1.0));
    }

    /** 时间衰减：weight ∝ 0.5^(距转化时长 / 半衰期)，归一化使权重和为 1 */
    private List<AttributionResult.Contribution> timeDecay(List<AttributionEvent> eligible,
                                                           AttributionEvent conversion,
                                                           Duration halfLife) {
        if (eligible.isEmpty()) {
            return List.of();
        }
        double halfLifeMillis = halfLife.toMillis();
        double[] raw = new double[eligible.size()];
        double sum = 0;
        for (int i = 0; i < eligible.size(); i++) {
            long ageMillis = Duration.between(eligible.get(i).getEventTime(),
                    conversion.getEventTime()).toMillis();
            raw[i] = Math.pow(0.5, ageMillis / halfLifeMillis);
            sum += raw[i];
        }
        List<AttributionResult.Contribution> out = new ArrayList<>(eligible.size());
        for (int i = 0; i < eligible.size(); i++) {
            out.add(toContribution(eligible.get(i), raw[i] / sum));
        }
        return out;
    }

    private AttributionResult.Contribution toContribution(AttributionEvent touch, double weight) {
        return new AttributionResult.Contribution(touch.getEventId(), touch.getChannel(),
                touch.getEventTime(), weight);
    }
}
