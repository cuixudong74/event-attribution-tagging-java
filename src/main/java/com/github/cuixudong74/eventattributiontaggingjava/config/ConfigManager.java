package com.github.cuixudong74.eventattributiontaggingjava.config;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置管理：加载时校验，非法配置拒绝并保持旧配置；
 * 读取返回当前不可变配置快照。
 */
@Component
public class ConfigManager {
    private final AtomicReference<AttributionConfig> current = new AtomicReference<>(defaultConfig());

    /** 默认配置：末次触点，30 天窗口，7 天半衰期 */
    private static AttributionConfig defaultConfig() {
        return new AttributionConfig(
                com.github.cuixudong74.eventattributiontaggingjava.model.AttributionModelType.LAST_TOUCH,
                java.time.Duration.ofDays(30), java.time.Duration.ofDays(7), 0);
    }

    public AttributionConfig current() {
        return current.get();
    }

    /** 原子替换；校验失败抛 ConfigException，旧配置保持不变 */
    public void update(AttributionConfig config) {
        if (config == null) {
            throw new ConfigException("配置不能为空");
        }
        config.validate(); // 先校验后替换，失败时旧配置不受影响
        current.set(config);
    }
}
