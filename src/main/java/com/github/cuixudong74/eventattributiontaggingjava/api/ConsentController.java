package com.github.cuixudong74.eventattributiontaggingjava.api;

import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 隐私授权接口：授权后用户事件才进入归因。
 */
@RestController
@RequestMapping("/api/consent")
public class ConsentController {

    private final AttributionService service;

    public ConsentController(AttributionService service) {
        this.service = service;
    }

    @PostMapping("/{userId}")
    public Map<String, Object> grant(@PathVariable String userId) {
        service.grantConsent(userId);
        return Map.of("userId", userId, "consented", true);
    }
}
