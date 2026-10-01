package com.github.cuixudong74.eventattributiontaggingjava.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 加载并校验归因配置；配置变更后通过 reload 重新加载，在途事件按新配置确定性重算。
 */
@Component
public class AttributionConfigLoader {

    private final AttributionProperties properties;

    private volatile AttributionConfig current;

    public AttributionConfigLoader(AttributionProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动时加载：非法配置直接拒绝（抛出 AttributionConfigException）。
     */
    @PostConstruct
    public AttributionConfig load() {
        AttributionConfig config = properties.toConfig();
        List<String> reasons = config.validate();
        if (!reasons.isEmpty()) {
            throw new AttributionConfigException(reasons);
        }
        this.current = config;
        return config;
    }

    public AttributionConfig getCurrent() {
        return current;
    }

    /**
     * 运行期重新加载配置，非法/冲突配置同样拒绝，且不影响在途事件的既有结论之外的确定性重算。
     */
    public AttributionConfig reload() {
        AttributionConfig config = properties.toConfig();
        List<String> reasons = config.validate();
        if (!reasons.isEmpty()) {
            throw new AttributionConfigException(reasons);
        }
        this.current = config;
        return config;
    }
}
