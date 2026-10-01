package com.github.cuixudong74.eventattributiontaggingjava.config;

/** 非法或冲突配置在加载时抛出，消息须说明原因 */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) { super(message); }
}
