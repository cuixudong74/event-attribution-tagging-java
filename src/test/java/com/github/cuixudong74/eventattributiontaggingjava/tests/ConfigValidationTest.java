package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfig;
import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfigException;
import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfigLoader;
import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionModel;
import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionProperties;
import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import com.github.cuixudong74.eventattributiontaggingjava.privacy.AuthorizationService;
import com.github.cuixudong74.eventattributiontaggingjava.privacy.ConsentService;
import com.github.cuixudong74.eventattributiontaggingjava.snapshot.SnapshotHolder;
import com.github.cuixudong74.eventattributiontaggingjava.tag.TagStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非法与冲突配置在加载时被拒绝（逐项原因）测试。
 */
class ConfigValidationTest {

    @Test
    void validConfigPassesValidation() {
        AttributionConfig config = new AttributionConfig(AttributionModel.LAST_TOUCH,
                Duration.ofDays(7), Duration.ofHours(24), List.of("IMPRESSION", "CLICK"));
        assertTrue(config.validate().isEmpty());
    }

    @Test
    void invalidConfigsAreRejectedWithReasons() {
        // 窗口为 0
        AttributionConfig zeroWindow = new AttributionConfig(AttributionModel.LAST_TOUCH,
                Duration.ZERO, Duration.ofHours(24), List.of("IMPRESSION"));
        assertFalse(zeroWindow.validate().isEmpty());

        // 时间加权缺少半衰期
        AttributionConfig noHalfLife = new AttributionConfig(AttributionModel.TIME_WEIGHTED,
                Duration.ofDays(7), null, List.of("IMPRESSION"));
        assertTrue(noHalfLife.validate().stream().anyMatch(r -> r.contains("half-life")));

        // 触点类型为空
        AttributionConfig emptyTouches = new AttributionConfig(AttributionModel.LAST_TOUCH,
                Duration.ofDays(7), Duration.ofHours(24), List.of());
        assertTrue(emptyTouches.validate().stream().anyMatch(r -> r.contains("touch-types")));

        // 触点类型包含 CONVERSION（转化不能作为触点）
        AttributionConfig conversionTouch = new AttributionConfig(AttributionModel.LAST_TOUCH,
                Duration.ofDays(7), Duration.ofHours(24), List.of("IMPRESSION", "CONVERSION"));
        assertTrue(conversionTouch.validate().stream().anyMatch(r -> r.contains("CONVERSION")));

        // 非法触点类型
        AttributionConfig badTouch = new AttributionConfig(AttributionModel.LAST_TOUCH,
                Duration.ofDays(7), Duration.ofHours(24), List.of("BOGUS"));
        assertTrue(badTouch.validate().stream().anyMatch(r -> r.contains("非法的触点类型")));
    }

    @Test
    void loaderRejectsInvalidConfigAtStartup() {
        AttributionProperties properties = new AttributionProperties();
        properties.setWindow(Duration.ofDays(-1));
        AttributionConfigLoader loader = new AttributionConfigLoader(properties);

        AttributionConfigException ex = assertThrows(AttributionConfigException.class, loader::load);
        assertFalse(ex.getReasons().isEmpty());
    }

    @Test
    void failedReloadKeepsCurrentConfig() {
        AttributionProperties properties = new AttributionProperties();
        AttributionConfigLoader loader = new AttributionConfigLoader(properties);
        AttributionConfig original = loader.load();
        assertEquals(AttributionModel.LAST_TOUCH, original.getModel());

        // 运行期改成非法配置
        properties.setWindow(Duration.ZERO);
        assertThrows(AttributionConfigException.class, loader::reload);

        // 当前配置保持不变
        assertEquals(original, loader.getCurrent());
    }

    @Test
    void configChangeDeterministicallyRecomputesResults() {
        AttributionProperties properties = new AttributionProperties();
        properties.setModel(AttributionModel.LAST_TOUCH);
        properties.setWindow(Duration.ofDays(7));
        AttributionConfigLoader loader = new AttributionConfigLoader(properties);
        AttributionService service = new AttributionService(new SnapshotHolder(), loader,
                new ConsentService(), new AuthorizationService(), new TagStore());
        service.grantConsent("u1");
        service.ingest(List.of(
                AttributionTestSupport.event("i1", com.github.cuixudong74.eventattributiontaggingjava.event.EventType.IMPRESSION,
                        "u1", null, "web", 1_000L),
                AttributionTestSupport.event("c1", com.github.cuixudong74.eventattributiontaggingjava.event.EventType.CLICK,
                        "u1", null, "web", 2_000L),
                AttributionTestSupport.event("cv1", com.github.cuixudong74.eventattributiontaggingjava.event.EventType.CONVERSION,
                        "u1", null, "web", 3_000L)
        ));
        // 末次触点 -> c1
        assertEquals("c1", service.snapshot().getResults().get("cv1").getCredits().get(0).getEventId());

        // 热加载切换为首次触点：在途事件按新配置确定性重算
        properties.setModel(AttributionModel.FIRST_TOUCH);
        AttributionConfig reloaded = service.reloadConfig();
        assertEquals(AttributionModel.FIRST_TOUCH, reloaded.getModel());
        assertEquals("i1", service.snapshot().getResults().get("cv1").getCredits().get(0).getEventId());
        // 修订原因 CONFIG_CHANGED 可追溯
        assertTrue(service.revisions().stream()
                .anyMatch(r -> AttributionService.REASON_CONFIG_CHANGED.equals(r.getReason())));

        // 随后提交非法配置：拒绝加载，当前配置与既有结论均不变
        properties.setWindow(Duration.ZERO);
        assertThrows(AttributionConfigException.class, service::reloadConfig);
        assertEquals(AttributionModel.FIRST_TOUCH, service.config().getModel());
        assertEquals("i1", service.snapshot().getResults().get("cv1").getCredits().get(0).getEventId());
    }
}
