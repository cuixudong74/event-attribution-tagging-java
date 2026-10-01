package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.event.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import com.github.cuixudong74.eventattributiontaggingjava.privacy.UnauthorizedAccessException;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionRevision;
import com.github.cuixudong74.eventattributiontaggingjava.result.Reconciliation;
import com.github.cuixudong74.eventattributiontaggingjava.snapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隐私授权与总量对账测试。
 */
class PrivacyAndReconciliationTest {

    @Test
    void unauthorizedEventsDoNotEnterAttributionButAreCounted() {
        AttributionService service = AttributionTestSupport.service();
        // u1 未授权
        service.ingest(List.of(
                AttributionTestSupport.event("i1", EventType.IMPRESSION, "u1", null, "web", 1_000L),
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", 2_000L)
        ));

        Snapshot snapshot = service.snapshot();
        assertEquals(com.github.cuixudong74.eventattributiontaggingjava.event.EventStatus.UNAUTHORIZED,
                snapshot.getEventStatuses().get("i1"));
        assertEquals(com.github.cuixudong74.eventattributiontaggingjava.event.EventStatus.UNAUTHORIZED,
                snapshot.getEventStatuses().get("cv1"));
        // 未授权事件不进入归因
        assertTrue(snapshot.getResults().isEmpty());
        // 总量口径仍可对账
        Reconciliation reconciliation = snapshot.getReconciliation();
        assertEquals(2, reconciliation.getTotalReceived());
        assertEquals(2, reconciliation.getUnauthorized());
        assertEquals(0, reconciliation.getLinked());
        assertTrue(reconciliation.isBalanced());
    }

    @Test
    void grantingConsentLetsEventsEnterAttributionAndIsTraceable() {
        AttributionService service = AttributionTestSupport.service();
        service.ingest(List.of(
                AttributionTestSupport.event("i1", EventType.IMPRESSION, "u1", null, "web", 1_000L),
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", 2_000L)
        ));
        assertTrue(service.snapshot().getResults().isEmpty());

        service.grantConsent("u1");

        Snapshot snapshot = service.snapshot();
        assertEquals(com.github.cuixudong74.eventattributiontaggingjava.event.EventStatus.LINKED,
                snapshot.getEventStatuses().get("i1"));
        AttributionResult result = snapshot.getResults().get("cv1");
        assertEquals("i1", result.getCredits().get(0).getEventId());

        // 授权变更触发重算，修订原因可追溯
        List<AttributionRevision> revisions = service.revisions();
        assertTrue(revisions.stream()
                .anyMatch(r -> AttributionService.REASON_CONSENT_CHANGED.equals(r.getReason())));
    }

    @Test
    void readingUserDetailsWithoutAuthorizationIsRejected() {
        AttributionService service = AttributionTestSupport.service();
        service.grantConsent("u1");
        service.ingest(List.of(
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", 2_000L)
        ));

        // 其他用户读取 -> 拒绝
        assertThrows(UnauthorizedAccessException.class,
                () -> service.userResults("u2", "u1"));
        // 本人读取 -> 允许
        assertEquals(1, service.userResults("u1", "u1").size());
        // 管理员读取 -> 允许
        assertEquals(1, service.userResults("admin", "u1").size());
        // 匿名读取 -> 拒绝
        assertThrows(UnauthorizedAccessException.class,
                () -> service.userResults(null, "u1"));
    }

    @Test
    void aggregateReconciliationIsAccessibleWithoutUserAuthorization() {
        AttributionService service = AttributionTestSupport.service();
        service.ingest(List.of(
                AttributionTestSupport.event("i1", EventType.IMPRESSION, "u1", null, "web", 1_000L)
        ));
        // 聚合口径不暴露用户明细，任何调用方可读且恒等式成立
        Reconciliation reconciliation = service.reconciliation();
        assertTrue(reconciliation.isBalanced());
        assertEquals(1, reconciliation.getTotalReceived());
    }

    @Test
    void mixedStatusesReconcileExactly() {
        AttributionService service = AttributionTestSupport.service();
        service.grantConsent("u1");
        service.ingest(List.of(
                // u1：已授权 -> linked
                AttributionTestSupport.event("i1", EventType.IMPRESSION, "u1", null, "web", 1_000L),
                // u2：未授权 -> unauthorized
                AttributionTestSupport.event("i2", EventType.IMPRESSION, "u2", null, "web", 1_000L),
                // 匿名 -> unlinked
                AttributionTestSupport.event("i3", EventType.IMPRESSION, null, "d9", "web", 1_000L),
                // 重复上报 -> duplicates
                AttributionTestSupport.event("i1", EventType.IMPRESSION, "u1", null, "web", 1_000L)
        ));

        Reconciliation r = service.reconciliation();
        assertEquals(4, r.getTotalReceived());
        assertEquals(1, r.getLinked());
        assertEquals(1, r.getUnauthorized());
        assertEquals(1, r.getUnlinked());
        assertEquals(1, r.getDuplicates());
        assertTrue(r.isBalanced());
    }
}
