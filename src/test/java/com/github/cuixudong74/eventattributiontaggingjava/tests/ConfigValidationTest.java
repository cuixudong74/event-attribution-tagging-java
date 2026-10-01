package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfig;
import com.github.cuixudong74.eventattributiontaggingjava.config.ConfigException;
import com.github.cuixudong74.eventattributiontaggingjava.config.ConfigManager;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionModelType;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidationTest {

    @Test
    void validConfigsPass() {
        new AttributionConfig(AttributionModelType.LAST_TOUCH, Duration.ofDays(30), null, 1).validate();
        new AttributionConfig(AttributionModelType.TIME_DECAY, Duration.ofDays(30), Duration.ofDays(7), 1).validate();
    }

    @Test
    void rejectsNullModel() {
        ConfigException e = assertThrows(ConfigException.class, () ->
                new AttributionConfig(null, Duration.ofDays(30), null, 1).validate());
        assertTrue(e.getMessage().contains("modelType"));
    }

    @Test
    void rejectsNonPositiveWindow() {
        assertThrows(ConfigException.class, () ->
                new AttributionConfig(AttributionModelType.FIRST_TOUCH, Duration.ZERO, null, 1).validate());
        assertThrows(ConfigException.class, () ->
                new AttributionConfig(AttributionModelType.FIRST_TOUCH, Duration.ofDays(-1), null, 1).validate());
    }

    @Test
    void rejectsExcessiveWindow() {
        ConfigException e = assertThrows(ConfigException.class, () ->
                new AttributionConfig(AttributionModelType.LAST_TOUCH, Duration.ofDays(400), null, 1).validate());
        assertTrue(e.getMessage().contains("365"));
    }

    @Test
    void timeDecayRequiresHalfLife() {
        assertThrows(ConfigException.class, () ->
                new AttributionConfig(AttributionModelType.TIME_DECAY, Duration.ofDays(30), null, 1).validate());
        assertThrows(ConfigException.class, () ->
                new AttributionConfig(AttributionModelType.TIME_DECAY, Duration.ofDays(30), Duration.ZERO, 1).validate());
    }

    @Test
    void rejectsConflictingHalfLifeLargerThanWindow() {
        ConfigException e = assertThrows(ConfigException.class, () ->
                new AttributionConfig(AttributionModelType.TIME_DECAY, Duration.ofDays(7),
                        Duration.ofDays(30), 1).validate());
        assertTrue(e.getMessage().contains("冲突"));
    }

    @Test
    void invalidUpdateKeepsOldConfig() {
        ConfigManager manager = new ConfigManager();
        AttributionConfig before = manager.current();
        assertThrows(ConfigException.class, () -> manager.update(
                new AttributionConfig(AttributionModelType.TIME_DECAY, Duration.ofDays(30), null, 2)));
        assertSame(before, manager.current()); // 旧配置保持不变
    }

    @Test
    void validUpdateTakesEffectAtomically() {
        ConfigManager manager = new ConfigManager();
        AttributionConfig cfg = new AttributionConfig(AttributionModelType.FIRST_TOUCH,
                Duration.ofDays(14), null, 2);
        manager.update(cfg);
        assertSame(cfg, manager.current());
    }
}
