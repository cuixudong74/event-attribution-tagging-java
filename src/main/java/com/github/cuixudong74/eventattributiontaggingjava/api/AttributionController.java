package com.github.cuixudong74.eventattributiontaggingjava.api;

import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfig;
import com.github.cuixudong74.eventattributiontaggingjava.config.ConfigException;
import com.github.cuixudong74.eventattributiontaggingjava.config.ConfigManager;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionCorrection;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionEvent;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionModelType;
import com.github.cuixudong74.eventattributiontaggingjava.model.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.model.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.model.TagEntry;
import com.github.cuixudong74.eventattributiontaggingjava.privacy.ConsentService;
import com.github.cuixudong74.eventattributiontaggingjava.service.IngestionService;
import com.github.cuixudong74.eventattributiontaggingjava.tagging.TagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 采集 / 查询 / 配置 / 标签 / 对账 API */
@RestController
@RequestMapping("/api")
public class AttributionController {
    private final IngestionService ingestionService;
    private final ConfigManager configManager;
    private final TagService tagService;
    private final ConsentService consentService;

    public AttributionController(IngestionService ingestionService, ConfigManager configManager,
                                 TagService tagService, ConsentService consentService) {
        this.ingestionService = ingestionService;
        this.configManager = configManager;
        this.tagService = tagService;
        this.consentService = consentService;
    }

    public record EventRequest(String eventId, EventType type, String userId, String deviceId,
                               String channel, String campaignId, Instant eventTime, Boolean consented) {}

    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody EventRequest req) {
        AttributionEvent event = new AttributionEvent(req.eventId(), req.type(), req.userId(),
                req.deviceId(), req.channel(), req.campaignId(), req.eventTime(), 0,
                req.consented() == null || req.consented());
        boolean accepted = ingestionService.ingest(event);
        return ResponseEntity.ok(Map.of("accepted", accepted, "eventId", event.getEventId()));
    }

    @GetMapping("/attributions/{conversionEventId}")
    public ResponseEntity<AttributionResult> resultOf(@PathVariable String conversionEventId) {
        AttributionResult result = ingestionService.resultOf(conversionEventId);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @GetMapping("/attributions")
    public Map<String, AttributionResult> allResults() {
        return ingestionService.allResults();
    }

    @GetMapping("/attributions/{conversionEventId}/corrections")
    public List<AttributionCorrection> corrections(@PathVariable String conversionEventId) {
        return ingestionService.correctionsOf(conversionEventId);
    }

    public record ConfigRequest(AttributionModelType modelType, Duration attributionWindow,
                                Duration timeDecayHalfLife, Long version) {}

    @PostMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody ConfigRequest req) {
        try {
            configManager.update(new AttributionConfig(req.modelType(), req.attributionWindow(),
                    req.timeDecayHalfLife(), req.version() == null ? 0 : req.version()));
            return ResponseEntity.ok(configManager.current());
        } catch (ConfigException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/config")
    public AttributionConfig currentConfig() {
        return configManager.current();
    }

    public record TagRequest(String identityKey, String channel, String key, String value,
                             Integer priority) {}

    @PostMapping("/tags")
    public Map<String, Object> putTag(@RequestBody TagRequest req) {
        boolean applied = tagService.putTag(req.identityKey(), req.channel(), req.key(), req.value(),
                req.priority() == null ? 0 : req.priority());
        return Map.of("applied", applied);
    }

    @GetMapping("/tags")
    public Map<String, TagEntry> tagsOf(@RequestParam String identityKey, @RequestParam String channel) {
        return tagService.tagsOf(identityKey, channel);
    }

    /** 总量对账：含未授权事件 */
    @GetMapping("/reconciliation")
    public Map<String, Long> reconciliation() {
        return Map.of(
                "totalEvents", consentService.totalEvents(),
                "consentedEvents", consentService.consentedEvents(),
                "nonConsentedEvents", consentService.nonConsentedEvents());
    }

    /** 用户级明细：需授权令牌 */
    @GetMapping("/users/{userId}/events")
    public ResponseEntity<?> userDetail(@PathVariable String userId,
                                        @RequestHeader(value = "X-Access-Token", required = false) String token) {
        try {
            return ResponseEntity.ok(ingestionService.userDetail(userId, token));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }
}
