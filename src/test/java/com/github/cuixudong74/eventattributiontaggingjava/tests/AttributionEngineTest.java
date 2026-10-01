package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.attribution.AttributionEngine;
import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfig;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionEvent;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionModelType;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.model.EventType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AttributionEngineTest {
    private final AttributionEngine engine = new AttributionEngine();
    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    private AttributionEvent ev(String id, EventType type, String channel, Instant time) {
        return new AttributionEvent(id, type, "u1", "d1", channel, "c1", time, 0, true);
    }

    private AttributionConfig config(AttributionModelType model) {
        return new AttributionConfig(model, Duration.ofDays(30), Duration.ofDays(7), 1);
    }

    @Test
    void firstTouchPicksEarliest() {
        AttributionEvent conv = ev("cv1", EventType.CONVERSION, null, T0.plus(Duration.ofDays(3)));
        List<AttributionEvent> touches = List.of(
                ev("t1", EventType.IMPRESSION, "chA", T0),
                ev("t2", EventType.CLICK, "chB", T0.plus(Duration.ofDays(1))));
        AttributionResult r = engine.attribute(conv, touches, config(AttributionModelType.FIRST_TOUCH), "k", 1);
        assertEquals(1, r.getContributions().size());
        assertEquals("t1", r.getContributions().get(0).touchEventId());
        assertEquals(1.0, r.getContributions().get(0).weight(), 1e-9);
    }

    @Test
    void lastTouchPicksLatest() {
        AttributionEvent conv = ev("cv1", EventType.CONVERSION, null, T0.plus(Duration.ofDays(3)));
        List<AttributionEvent> touches = List.of(
                ev("t1", EventType.IMPRESSION, "chA", T0),
                ev("t2", EventType.CLICK, "chB", T0.plus(Duration.ofDays(1))));
        AttributionResult r = engine.attribute(conv, touches, config(AttributionModelType.LAST_TOUCH), "k", 1);
        assertEquals("t2", r.getContributions().get(0).touchEventId());
    }

    @Test
    void timeDecayWeightsSumToOneAndFavorRecent() {
        AttributionEvent conv = ev("cv1", EventType.CONVERSION, null, T0.plus(Duration.ofDays(7)));
        List<AttributionEvent> touches = List.of(
                ev("t1", EventType.CLICK, "chA", T0),                          // 7 天前 = 1 个半衰期
                ev("t2", EventType.CLICK, "chB", T0.plus(Duration.ofDays(7)))); // 0 天前
        AttributionResult r = engine.attribute(conv, touches, config(AttributionModelType.TIME_DECAY), "k", 1);
        double sum = r.getContributions().stream().mapToDouble(AttributionResult.Contribution::weight).sum();
        assertEquals(1.0, sum, 1e-9);
        double w1 = r.getContributions().get(0).weight();
        double w2 = r.getContributions().get(1).weight();
        assertTrue(w2 > w1, "越近的触点权重应越大");
        assertEquals(w2, 2 * w1, 1e-9); // 相差一个半衰期，权重比 2:1
    }

    @Test
    void touchesOutsideWindowOrAfterConversionExcluded() {
        AttributionEvent conv = ev("cv1", EventType.CONVERSION, null, T0.plus(Duration.ofDays(3)));
        List<AttributionEvent> touches = List.of(
                ev("old", EventType.CLICK, "chA", T0.minus(Duration.ofDays(40))), // 超窗
                ev("future", EventType.CLICK, "chB", T0.plus(Duration.ofDays(5)))); // 转化后
        AttributionResult r = engine.attribute(conv, touches, config(AttributionModelType.LAST_TOUCH), "k", 1);
        assertFalse(r.isAttributed());
    }

    @Test
    void deterministicAcrossRuns() {
        AttributionEvent conv = ev("cv1", EventType.CONVERSION, null, T0.plus(Duration.ofDays(7)));
        List<AttributionEvent> touches = List.of(
                ev("t1", EventType.CLICK, "chA", T0),
                ev("t2", EventType.IMPRESSION, "chB", T0.plus(Duration.ofDays(2))),
                ev("t3", EventType.CLICK, "chC", T0.plus(Duration.ofDays(5))));
        AttributionConfig cfg = config(AttributionModelType.TIME_DECAY);
        AttributionResult r1 = engine.attribute(conv, touches, cfg, "k", 1);
        AttributionResult r2 = engine.attribute(conv, touches, cfg, "k", 1);
        assertEquals(r1.getContributions(), r2.getContributions());
    }
}
