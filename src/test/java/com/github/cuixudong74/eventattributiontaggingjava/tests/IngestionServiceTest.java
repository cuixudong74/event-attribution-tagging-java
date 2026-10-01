package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.attribution.AttributionEngine;
import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfig;
import com.github.cuixudong74.eventattributiontaggingjava.config.ConfigManager;
import com.github.cuixudong74.eventattributiontaggingjava.identity.IdentityGraph;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionCorrection;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionEvent;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionModelType;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.model.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.privacy.ConsentService;
import com.github.cuixudong74.eventattributiontaggingjava.service.IngestionService;
import com.github.cuixudong74.eventattributiontaggingjava.store.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IngestionServiceTest {
    private IngestionService service;
    private ConsentService consentService;
    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        consentService = new ConsentService();
        service = new IngestionService(new EventStore(), new IdentityGraph(), new AttributionEngine(),
                new ConfigManager(), consentService);
    }

    private AttributionEvent ev(String id, EventType type, String userId, String deviceId,
                                String channel, Instant time) {
        return new AttributionEvent(id, type, userId, deviceId, channel, "c1", time, 0, true);
    }

    @Test
    void duplicateEventNotCountedTwice() {
        AttributionEvent click = ev("e1", EventType.CLICK, "u1", "d1", "chA", T0);
        assertTrue(service.ingest(click));
        assertFalse(service.ingest(click)); // 重复上报
        assertFalse(service.ingest(click));
        service.ingest(ev("cv", EventType.CONVERSION, "u1", "d1", null, T0.plus(Duration.ofDays(1))));
        AttributionResult r = service.resultOf("cv");
        assertEquals(1, r.getContributions().size()); // 只归因一次
        assertEquals("e1", r.getContributions().get(0).touchEventId());
    }

    @Test
    void outOfOrderEventsAttributedByEventTime() {
        // 转化先到达，触点后到达（乱序）
        service.ingest(ev("cv", EventType.CONVERSION, "u1", "d1", null, T0.plus(Duration.ofDays(2))));
        AttributionResult before = service.resultOf("cv");
        assertFalse(before.isAttributed()); // 暂无触点

        service.ingest(ev("e1", EventType.CLICK, "u1", "d1", "chA", T0.plus(Duration.ofDays(1))));
        AttributionResult after = service.resultOf("cv");
        assertTrue(after.isAttributed());
        assertEquals("e1", after.getContributions().get(0).touchEventId());

        List<AttributionCorrection> corrections = service.correctionsOf("cv");
        assertEquals(1, corrections.size());
        assertFalse(corrections.get(0).before().isAttributed());
        assertTrue(corrections.get(0).after().isAttributed());
        assertEquals("e1", corrections.get(0).triggerEventId());
    }

    @Test
    void lateTouchCorrectsLastTouchResult() {
        service.ingest(ev("e1", EventType.CLICK, "u1", "d1", "chA", T0));
        service.ingest(ev("cv", EventType.CONVERSION, "u1", "d1", null, T0.plus(Duration.ofDays(3))));
        assertEquals("e1", service.resultOf("cv").getContributions().get(0).touchEventId());

        // 更晚的触点迟到到达（仍在转化前），末次触点应被修正
        service.ingest(ev("e2", EventType.CLICK, "u1", "d1", "chB", T0.plus(Duration.ofDays(2))));
        AttributionResult corrected = service.resultOf("cv");
        assertEquals("e2", corrected.getContributions().get(0).touchEventId());
        assertEquals(2, corrected.getVersion());
        assertEquals(1, service.correctionsOf("cv").size());
    }

    @Test
    void crossDeviceSameUserLinked() {
        // 同一 userId 跨设备
        service.ingest(ev("e1", EventType.IMPRESSION, "u1", "d1", "chA", T0));
        service.ingest(ev("e2", EventType.CLICK, "u1", "d2", "chB", T0.plus(Duration.ofDays(1))));
        service.ingest(ev("cv", EventType.CONVERSION, "u1", "d2", null, T0.plus(Duration.ofDays(2))));
        AttributionResult r = service.resultOf("cv");
        assertTrue(r.isAttributed());
        assertEquals("u:u1", r.getIdentityKey());
    }

    @Test
    void deviceOnlyEventsLinkedAfterIdentityBridge() {
        // 先只有设备行为，之后桥接事件把设备归并到用户
        service.ingest(ev("e1", EventType.CLICK, null, "d1", "chA", T0));
        service.ingest(ev("cv", EventType.CONVERSION, "u1", null, null, T0.plus(Duration.ofDays(2))));
        assertFalse(service.resultOf("cv").isAttributed()); // 尚未关联

        service.ingest(ev("bridge", EventType.IMPRESSION, "u1", "d1", "chB", T0.minus(Duration.ofDays(1))));
        AttributionResult r = service.resultOf("cv");
        assertTrue(r.isAttributed(), "桥接后 d1 的点击应归入 u1");
        assertEquals("e1", r.getContributions().get(0).touchEventId());
    }

    @Test
    void anonymousEventGetsDistinguishableMarker() {
        service.ingest(ev("cv", EventType.CONVERSION, null, null, null, T0));
        AttributionResult r = service.resultOf("cv");
        assertNotNull(r);
        assertTrue(r.getIdentityKey().startsWith(IdentityGraph.NO_IDENTITY_PREFIX));
        assertFalse(r.isAttributed()); // 有标记但不静默丢弃
    }

    @Test
    void nonConsentedExcludedButReconciled() {
        AttributionEvent bad = new AttributionEvent("e1", EventType.CLICK, "u1", "d1", "chA", "c1",
                T0, 0, false);
        assertFalse(service.ingest(bad)); // 不进归因
        service.ingest(ev("cv", EventType.CONVERSION, "u1", "d1", null, T0.plus(Duration.ofDays(1))));
        assertFalse(service.resultOf("cv").isAttributed());

        // 总量对账仍可见
        assertEquals(2, consentService.totalEvents());
        assertEquals(1, consentService.consentedEvents());
        assertEquals(1, consentService.nonConsentedEvents());
    }

    @Test
    void userDetailRequiresToken() {
        service.ingest(ev("e1", EventType.CLICK, "u1", "d1", "chA", T0));
        assertThrows(SecurityException.class, () -> service.userDetail("u1", "wrong-token"));
        assertThrows(SecurityException.class, () -> service.userDetail("u1", null));
        assertEquals(1, service.userDetail("u1", ConsentService.ADMIN_TOKEN).size());
    }

    @Test
    void reproducibleRegardlessOfArrivalOrder() {
        // 同一批事件两种到达顺序，最终结论必须一致
        IngestionService s1 = newService();
        IngestionService s2 = newService();
        AttributionEvent[] events = {
                ev("t1", EventType.IMPRESSION, "u1", "d1", "chA", T0),
                ev("t2", EventType.CLICK, "u1", "d1", "chB", T0.plus(Duration.ofDays(1))),
                ev("t3", EventType.CLICK, "u1", "d1", "chC", T0.plus(Duration.ofDays(2))),
                ev("cv", EventType.CONVERSION, "u1", "d1", null, T0.plus(Duration.ofDays(3)))};
        for (AttributionEvent e : events) s1.ingest(e);
        for (int i = events.length - 1; i >= 0; i--) s2.ingest(events[i]); // 逆序到达
        assertEquals(s1.resultOf("cv").getContributions(), s2.resultOf("cv").getContributions());
    }

    private IngestionService newService() {
        return new IngestionService(new EventStore(), new IdentityGraph(), new AttributionEngine(),
                new ConfigManager(), new ConsentService());
    }
}
