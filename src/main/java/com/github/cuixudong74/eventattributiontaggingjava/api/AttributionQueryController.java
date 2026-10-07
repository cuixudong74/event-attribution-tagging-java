package com.github.cuixudong74.eventattributiontaggingjava.api;

import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionResult;
import com.github.cuixudong74.eventattributiontaggingjava.result.AttributionRevision;
import com.github.cuixudong74.eventattributiontaggingjava.result.Reconciliation;
import com.github.cuixudong74.eventattributiontaggingjava.snapshot.Snapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 归因查询接口：快照、对账、修订历史为聚合口径；用户级明细需鉴权。
 */
@RestController
@RequestMapping("/api")
public class AttributionQueryController {

    private final AttributionService service;

    public AttributionQueryController(AttributionService service) {
        this.service = service;
    }

    @GetMapping("/snapshot")
    public Snapshot snapshot(@RequestParam(required = false) Long version) {
        return service.snapshot(version);
    }

    @GetMapping("/reconciliation")
    public Reconciliation reconciliation(@RequestParam(required = false) Long version) {
        return service.reconciliation(version);
    }

    @GetMapping("/revisions")
    public List<AttributionRevision> revisions(@RequestParam(required = false) Long version) {
        return service.revisions(version);
    }

    @GetMapping("/users/{userId}")
    public List<AttributionResult> userResults(@RequestParam String actor, @PathVariable String userId) {
        return service.userResults(actor, userId);
    }
}
