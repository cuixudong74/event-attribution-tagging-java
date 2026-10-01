package com.github.cuixudong74.eventattributiontaggingjava.config;

import java.util.List;

/**
 * 配置非法或互相冲突时抛出，reasons 给出逐项拒绝原因。
 */
public class AttributionConfigException extends RuntimeException {

    private final transient List<String> reasons;

    public AttributionConfigException(List<String> reasons) {
        super("非法归因配置: " + String.join("; ", reasons));
        this.reasons = List.copyOf(reasons);
    }

    public List<String> getReasons() {
        return reasons;
    }
}
