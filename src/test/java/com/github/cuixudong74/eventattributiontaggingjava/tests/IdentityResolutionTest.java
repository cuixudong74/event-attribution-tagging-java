package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.event.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.result.Reconciliation;
import com.github.cuixudong74.eventattributiontaggingjava.snapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨设备/渠道身份关联测试：关联不上的事件必须可区分标记，不得静默丢弃。
 */
class IdentityResolutionTest {

    @Test
    void anonymousEventIsMarkedUnlinkedAndLaterLinksToUser() {
        AttributionService service = AttributionTestSupport.service();
        service.grantConsent("u1");

        // 匿名曝光：只有设备标识，无法关联用户
        service.ingest(List.of(
                AttributionTestSupport.event("i-anon", EventType.IMPRESSION, null, "d1", "web", 1_000L)
        ));
        Snapshot afterAnonymous = service.snapshot();
        assertEquals(com.github.cuixudong74.eventattributiontaggingjava.event.EventStatus.UNLINKED,
                afterAnonymous.getEventStatuses().get("i-anon"));
        assertEquals(1, afterAnonymous.getReconciliation().getUnlinked());
        assertTrue(afterAnonymous.getResults().isEmpty());

        // 登录事件建立 u1 与 d1 的关联
        service.ingest(List.of(
                AttributionTestSupport.event("login", EventType.CLICK, "u1", "d1", "web", 1_500L)
        ));
        // 此后同一设备上的匿名点击应关联到 u1
        service.ingest(List.of(
                AttributionTestSupport.event("c-anon", EventType.CLICK, null, "d1", "mobile", 2_000L),
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", 3_000L)
        ));

        Snapshot snapshot = service.snapshot();
        assertEquals(com.github.cuixudong74.eventattributiontaggingjava.event.EventStatus.LINKED,
                snapshot.getEventStatuses().get("c-anon"));
        AttributionResult result = snapshot.getResults().get("cv1");
        assertNotNull(result);
        // 末次触点为匿名点击 c-anon（跨渠道 mobile 行为被关联）
        assertEquals("c-anon", result.getCredits().get(0).getEventId());
    }

    @Test
    void crossDeviceIdentityMergesTouchesIntoSingleJourney() {
        // 时间加权模型下，跨设备关联的两个触点都应进入同一次归因
        AttributionService service = AttributionTestSupport.service(
                com.github.cuixudong74.eventattributiontaggingjava.config.AttributionModel.TIME_WEIGHTED,
                java.time.Duration.ofDays(7), java.time.Duration.ofHours(24));
        service.grantConsent("u1");
        service.grantConsent("u2");

        // u1 在设备 d1 曝光；u2 在设备 d2 点击；u2 在 d1 登录，把两个身份关联到一起
        service.ingest(List.of(
                AttributionTestSupport.event("i1", EventType.IMPRESSION, "u1", "d1", "web", 1_000L),
                AttributionTestSupport.event("c2", EventType.CLICK, "u2", "d2", "mobile", 2_000L),
                AttributionTestSupport.event("login2", EventType.CLICK, "u2", "d1", "web", 2_500L),
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u2", "d2", "web", 3_000L)
        ));

        AttributionResult result = service.snapshot().getResults().get("cv1");
        assertNotNull(result);
        // 规范用户取字典序较小者 u1；跨设备关联后，i1、c2 与登录点击 login2 都归入同一旅程
        assertEquals("u1", result.getUserId());
        assertEquals(3, result.getCredits().size());
        assertTrue(result.getCredits().stream().anyMatch(c -> c.getEventId().equals("i1")));
        assertTrue(result.getCredits().stream().anyMatch(c -> c.getEventId().equals("c2")));
        assertTrue(result.getCredits().stream().anyMatch(c -> c.getEventId().equals("login2")));
    }

    @Test
    void unlinkedEventsRemainInReconciliationButNotAttribution() {
        AttributionService service = AttributionTestSupport.service();
        service.ingest(List.of(
                AttributionTestSupport.event("i-anon", EventType.IMPRESSION, null, "unknown-device", "web", 1_000L),
                AttributionTestSupport.event("cv-anon", EventType.CONVERSION, null, "unknown-device", "web", 2_000L)
        ));

        Snapshot snapshot = service.snapshot();
        Reconciliation reconciliation = snapshot.getReconciliation();
        assertEquals(2, reconciliation.getTotalReceived());
        assertEquals(2, reconciliation.getUnlinked());
        assertEquals(0, reconciliation.getLinked());
        // 关联不上的转化不产生归因结果，但在总量中可对账
        assertTrue(snapshot.getResults().isEmpty());
        assertTrue(reconciliation.isBalanced());
    }
}
