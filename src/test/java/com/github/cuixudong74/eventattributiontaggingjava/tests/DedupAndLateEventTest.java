package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionModel;
import com.github.cuixudong74.eventattributiontaggingjava.event.Event;
import com.github.cuixudong74.eventattributiontaggingjava.event.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionDiff;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionRevision;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复上报、乱序到达与迟到事件修正测试。
 */
class DedupAndLateEventTest {

    @Test
    void duplicateEventIdIsNotDoubleCounted() {
        AttributionService service = AttributionTestSupport.service();
        service.grantConsent("u1");
        Event click = AttributionTestSupport.event("c1", EventType.CLICK, "u1", null, "web", 2_000L);
        Event conversion = AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", 3_000L);

        AttributionService.IngestResult first = service.ingest(List.of(click, conversion));
        AttributionService.IngestResult second = service.ingest(List.of(click));

        assertEquals(2, first.accepted());
        assertEquals(1, second.duplicates());
        assertEquals(0, second.accepted());
        // 末次触点仍是 c1，重复点击不得改变归因
        AttributionResult result = service.snapshot().getResults().get("cv1");
        assertEquals(1, result.getCredits().size());
        assertEquals("c1", result.getCredits().get(0).getEventId());
        // 总量口径：接收 3 条 = 去重后 2 条 + 重复 1 条
        assertEquals(3, service.reconciliation().getTotalReceived());
        assertEquals(2, service.reconciliation().getLinked());
        assertEquals(1, service.reconciliation().getDuplicates());
        assertTrue(service.reconciliation().isBalanced());
    }

    @Test
    void lateEventCorrectsAttributionAndRevisionIsTraceable() {
        // 首次触点模型下，迟到的更早曝光会替换原有触点
        AttributionService service = AttributionTestSupport.service(AttributionModel.FIRST_TOUCH, Duration.ofDays(7));
        service.grantConsent("u1");
        service.ingest(List.of(
                AttributionTestSupport.event("c1", EventType.CLICK, "u1", null, "web", 2_000L),
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", 3_000L)
        ));
        assertEquals("c1", service.snapshot().getResults().get("cv1").getCredits().get(0).getEventId());

        // 迟到事件：发生时间早于已观察到的最大时间戳
        AttributionService.IngestResult late = service.ingest(List.of(
                AttributionTestSupport.event("i0", EventType.IMPRESSION, "u1", null, "web", 500L)
        ));
        assertEquals(1, late.accepted());

        AttributionResult corrected = service.snapshot().getResults().get("cv1");
        assertEquals("i0", corrected.getCredits().get(0).getEventId());

        // 修订历史可追溯：原因 LATE_EVENT，差异含新增 i0 与移除 c1
        List<AttributionRevision> revisions = service.revisions();
        AttributionRevision last = revisions.get(revisions.size() - 1);
        assertEquals(AttributionService.REASON_LATE_EVENT, last.getReason());
        AttributionDiff diff = last.getDiffs().get(0);
        assertEquals("cv1", diff.getConversionEventId());
        assertTrue(diff.getAdded().stream().anyMatch(c -> c.getEventId().equals("i0")));
        assertTrue(diff.getRemoved().stream().anyMatch(c -> c.getEventId().equals("c1")));
    }

    @Test
    void outOfOrderArrivalIsAttributedByEventTime() {
        AttributionService service = AttributionTestSupport.service(AttributionModel.LAST_TOUCH, Duration.ofDays(7));
        service.grantConsent("u1");
        // 乱序到达：转化先到，点击次之，曝光最后
        service.ingest(List.of(
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", 3_000L),
                AttributionTestSupport.event("c1", EventType.CLICK, "u1", null, "web", 2_000L),
                AttributionTestSupport.event("i1", EventType.IMPRESSION, "u1", null, "web", 1_000L)
        ));

        AttributionResult result = service.snapshot().getResults().get("cv1");
        assertEquals(1, result.getCredits().size());
        // 末次触点按事件发生时间判定为 c1，与到达顺序无关
        assertEquals("c1", result.getCredits().get(0).getEventId());
    }

    @Test
    void lateEventOutsideWindowDoesNotChangeAttribution() {
        AttributionService service = AttributionTestSupport.service(AttributionModel.FIRST_TOUCH, Duration.ofDays(1));
        service.grantConsent("u1");
        long conversion = Duration.ofDays(10).toMillis();
        service.ingest(List.of(
                AttributionTestSupport.event("c1", EventType.CLICK, "u1", null, "web", conversion - 1_000L),
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", conversion)
        ));
        // 迟到曝光发生在转化前 2 天，已超出 1 天窗口，不应进入归因
        service.ingest(List.of(
                AttributionTestSupport.event("i-old", EventType.IMPRESSION, "u1", null, "web",
                        conversion - Duration.ofDays(2).toMillis())
        ));

        AttributionResult result = service.snapshot().getResults().get("cv1");
        assertEquals(1, result.getCredits().size());
        assertEquals("c1", result.getCredits().get(0).getEventId());
    }
}
