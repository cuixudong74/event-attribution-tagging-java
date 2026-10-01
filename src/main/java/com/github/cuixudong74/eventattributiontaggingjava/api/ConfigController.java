package com.github.cuixudong74.eventattributiontaggingjava.api;

import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfig;
import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配置查看与热加载接口。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final AttributionService service;

    public ConfigController(AttributionService service) {
        this.service = service;
    }

    @GetMapping
    public AttributionConfig current() {
        return service.config();
    }

    @PostMapping("/reload")
    public AttributionConfig reload() {
        return service.reloadConfig();
    }
}
