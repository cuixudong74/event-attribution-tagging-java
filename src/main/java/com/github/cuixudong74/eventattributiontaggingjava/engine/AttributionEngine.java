package com.github.cuixudong74.eventattributiontaggingjava.engine;

import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfig;
import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionModel;
import com.github.cuixudong74.eventattributiontaggingjava.event.Event;
import com.github.cuixudong74.eventattributiontaggingjava.event.EventStatus;
import com.github.cuixudong74.eventattributiontaggingjava.event.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.identity.IdentityGraph;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.result.TouchCredit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归因引擎：基于全量事件日志、身份图与配置纯函数式重算所有转化的归因结果。
 * 相同输入与相同配置下，任意时刻重算结果一致且可复现。
 */
@Component
public class AttributionEngine {

    public AttributionEngine() {
    }

    /**
     * @param events        全部已去重事件（按到达顺序）
     * @param statuses      每个事件的归因状态
     * @param identityGraph 跨设备/渠道身份图（引擎内部会基于全量事件重建，保证确定性）
     * @param config        归因配置
     * @return conversionEventId -> 归因结果
     */
    public Map<String, AttributionResult> compute(
            List<Event> events,
            Map<String, EventStatus> statuses,
            IdentityGraph identityGraph,
            AttributionConfig config) {

        IdentityGraph graph = new IdentityGraph();
        for (Event event : events) {
            graph.link(event.getUserId(), event.getDeviceId());
        }

        Map<String, List<Event>> touchesByUser = new HashMap<>();
        List<Event> conversions = new ArrayList<>();
        for (Event event : events) {
            if (statuses.get(event.getEventId()) != EventStatus.LINKED) {
                continue;
            }
            String uid = graph.resolveCanonical(event.getUserId(), event.getDeviceId());
            if (uid == null) {
                continue;
            }
            if (event.getType() == EventType.CONVERSION) {
                conversions.add(event);
            } else if (config.getTouchTypes().contains(event.getType().name())) {
                touchesByUser.computeIfAbsent(uid, k -> new ArrayList<>()).add(event);
            }
        }

        long windowMillis = config.getWindow().toMillis();
        Map<String, AttributionResult> results = new LinkedHashMap<>();
        for (Event conversion : conversions) {
            String uid = graph.resolveCanonical(conversion.getUserId(), conversion.getDeviceId());
            long conversionTime = conversion.getTimestamp();
            long windowStart = conversionTime - windowMillis;
            List<Event> windowTouches = touchesByUser.getOrDefault(uid, List.of()).stream()
                    .filter(touch -> touch.getTimestamp() <= conversionTime)
                    .filter(touch -> touch.getTimestamp() >= windowStart)
                    .sorted(Comparator.comparingLong(Event::getTimestamp))
                    .toList();
            List<TouchCredit> credits = allocate(windowTouches, conversionTime, config);
            results.put(conversion.getEventId(),
                    new AttributionResult(conversion.getEventId(), uid, conversion.getChannel(),
                            conversionTime, credits, config.getModel().name()));
        }
        return results;
    }

    private List<TouchCredit> allocate(List<Event> touches, long conversionTime, AttributionConfig config) {
        if (touches.isEmpty()) {
            return List.of();
        }
        AttributionModel model = config.getModel();
        List<TouchCredit> credits = new ArrayList<>();
        if (model == AttributionModel.FIRST_TOUCH) {
            Event first = touches.get(0);
            credits.add(toCredit(first, 1.0));
            return credits;
        }
        if (model == AttributionModel.LAST_TOUCH) {
            Event last = touches.get(touches.size() - 1);
            credits.add(toCredit(last, 1.0));
            return credits;
        }
        // TIME_WEIGHTED：指数衰减，半衰期 half-life，权重和归一化为 1
        double lambda = Math.log(2) / config.getHalfLife().toMillis();
        double[] weights = new double[touches.size()];
        double sum = 0;
        for (int i = 0; i < touches.size(); i++) {
            double w = Math.exp(-lambda * (conversionTime - touches.get(i).getTimestamp()));
            weights[i] = w;
            sum += w;
        }
        for (int i = 0; i < touches.size(); i++) {
            credits.add(toCredit(touches.get(i), weights[i] / sum));
        }
        return credits;
    }

    private TouchCredit toCredit(Event event, double weight) {
        return new TouchCredit(event.getEventId(), event.getType().name(), event.getChannel(),
                event.getTimestamp(), weight);
    }
}
