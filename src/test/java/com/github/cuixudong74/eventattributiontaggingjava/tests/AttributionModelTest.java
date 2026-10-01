package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionModel;
import com.github.cuixudong74.eventattributiontaggingjava.event.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.result.TouchCredit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归因模型语义测试：首次触点、末次触点、时间加权、窗口边界与可复现性。
 */
class AttributionModelTest {

    @Test
    void firstTouchGivesAllCreditToEarliestTouch() {
        AttributionService service = AttributionTestSupport.service(AttributionModel.FIRST_TOUCH, Duration.ofDays(7));
        service.grantConsent("u1");
        service.ingest(List.of(
                AttributionTestSupport.event("i1", EventType.IMPRESSION, "u1", null, "web", 1_000L),
                AttributionTestSupport.event("c1", EventType.CLICK, "u1", null, "mobile", 2_000L),
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", 10_000L)
        ));

        AttributionResult result = service.snapshot().getResults().get("cv1");
        assertEquals(1, result.getCredits().size());
        assertEquals("i1", result.getCredits().get(0).getEventId());
        assertEquals(1.0, result.getCredits().get(0).getWeight(), 1e-9);
    }

    @Test
    void lastTouchGivesAllCreditToLatestTouch() {
        AttributionService service = AttributionTestSupport.service(AttributionModel.LAST_TOUCH, Duration.ofDays(7));
        service.grantConsent("u1");
        service.ingest(List.of(
                AttributionTestSupport.event("i1", EventType.IMPRESSION, "u1", null, "web", 1_000L),
                AttributionTestSupport.event("c1", EventType.CLICK, "u1", null, "mobile", 2_000L),
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", 10_000L)
        ));

        AttributionResult result = service.snapshot().getResults().get("cv1");
        assertEquals(1, result.getCredits().size());
        assertEquals("c1", result.getCredits().get(0).getEventId());
    }

    @Test
    void timeWeightedGivesMoreCreditToNearerTouchAndWeightsSumToOne() {
        AttributionService service = AttributionTestSupport.service(AttributionModel.TIME_WEIGHTED,
                Duration.ofDays(7), Duration.ofHours(24));
        service.grantConsent("u1");
        long conversion = 100_000_000L;
        service.ingest(List.of(
                AttributionTestSupport.event("i-far", EventType.IMPRESSION, "u1", null, "web",
                        conversion - Duration.ofHours(48).toMillis()),
                AttributionTestSupport.event("i-near", EventType.IMPRESSION, "u1", null, "web",
                        conversion - Duration.ofHours(1).toMillis()),
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", conversion)
        ));

        AttributionResult result = service.snapshot().getResults().get("cv1");
        assertEquals(2, result.getCredits().size());
        TouchCredit far = result.getCredits().stream().filter(c -> c.getEventId().equals("i-far")).findFirst().orElseThrow();
        TouchCredit near = result.getCredits().stream().filter(c -> c.getEventId().equals("i-near")).findFirst().orElseThrow();
        assertTrue(near.getWeight() > far.getWeight());
        assertEquals(1.0, far.getWeight() + near.getWeight(), 1e-9);
    }

    @Test
    void windowBoundaryIncludesExactBoundaryAndExcludesOutside() {
        AttributionService service = AttributionTestSupport.service(AttributionModel.LAST_TOUCH, Duration.ofDays(7));
        service.grantConsent("u1");
        long conversion = 10_000_000L;
        long windowStart = conversion - Duration.ofDays(7).toMillis();
        service.ingest(List.of(
                // 恰好在窗口起点：计入
                AttributionTestSupport.event("i-boundary", EventType.IMPRESSION, "u1", null, "web", windowStart),
                // 窗口起点之前 1 毫秒：不计入
                AttributionTestSupport.event("i-outside", EventType.IMPRESSION, "u1", null, "web", windowStart - 1),
                AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", conversion)
        ));

        AttributionResult result = service.snapshot().getResults().get("cv1");
        assertEquals(1, result.getCredits().size());
        assertEquals("i-boundary", result.getCredits().get(0).getEventId());
    }

    @Test
    void sameInputsProduceSameResultsAcrossIndependentServices() {
        AttributionService s1 = AttributionTestSupport.service(AttributionModel.TIME_WEIGHTED,
                Duration.ofDays(3), Duration.ofHours(12));
        AttributionService s2 = AttributionTestSupport.service(AttributionModel.TIME_WEIGHTED,
                Duration.ofDays(3), Duration.ofHours(12));
        for (AttributionService s : List.of(s1, s2)) {
            s.grantConsent("u1");
            s.ingest(List.of(
                    AttributionTestSupport.event("i1", EventType.IMPRESSION, "u1", null, "web", 1_000L),
                    AttributionTestSupport.event("c1", EventType.CLICK, "u1", null, "mobile", 4_000L),
                    AttributionTestSupport.event("cv1", EventType.CONVERSION, "u1", null, "web", 9_000L)
            ));
        }

        AttributionResult r1 = s1.snapshot().getResults().get("cv1");
        AttributionResult r2 = s2.snapshot().getResults().get("cv1");
        assertEquals(r1.getCredits().size(), r2.getCredits().size());
        for (int i = 0; i < r1.getCredits().size(); i++) {
            assertEquals(r1.getCredits().get(i).getEventId(), r2.getCredits().get(i).getEventId());
            assertEquals(r1.getCredits().get(i).getWeight(), r2.getCredits().get(i).getWeight(), 1e-12);
        }
    }
}
