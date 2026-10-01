package com.github.cuixudong74.eventattributiontaggingjava.api;

import com.github.cuixudong74.eventattributiontaggingjava.event.Event;
import com.github.cuixudong74.eventattributiontaggingjava.event.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件摄入接口。
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final AttributionService service;

    public EventController(AttributionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody List<EventRequest> requests) {
        List<Event> events = new ArrayList<>();
        for (EventRequest r : requests) {
            events.add(new Event(r.getEventId(), EventType.valueOf(r.getType()), r.getUserId(),
                    r.getDeviceId(), r.getChannel(), r.getTimestamp(), Instant.now().toEpochMilli(),
                    r.getProperties()));
        }
        AttributionService.IngestResult result = service.ingest(events);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", result.accepted());
        body.put("duplicates", result.duplicates());
        body.put("snapshotVersion", result.snapshotVersion());
        return ResponseEntity.ok(body);
    }
}
