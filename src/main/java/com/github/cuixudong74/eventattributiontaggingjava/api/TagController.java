package com.github.cuixudong74.eventattributiontaggingjava.api;

import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import com.github.cuixudong74.eventattributiontaggingjava.tag.Tag;
import com.github.cuixudong74.eventattributiontaggingjava.tag.TagKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 标签读写接口。
 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final AttributionService service;

    public TagController(AttributionService service) {
        this.service = service;
    }

    @PostMapping
    public Tag write(@RequestParam(required = false) String actor, @RequestBody TagRequest request) {
        TagKey key = new TagKey(request.getUserId(), request.getChannel());
        Tag tag = new Tag(request.getKey(), request.getValue(), request.getPriority(),
                request.getSource(), Instant.now().toEpochMilli());
        return service.writeTag(key, tag);
    }

    @GetMapping
    public Tag read(@RequestParam(required = false) String actor,
                    @RequestParam String userId,
                    @RequestParam(required = false) String channel,
                    @RequestParam String key) {
        return service.readTag(actor, new TagKey(userId, channel));
    }
}
