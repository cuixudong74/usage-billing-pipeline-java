package com.github.cuixudong74.usagebillingpipelinejava.web;

import com.github.cuixudong74.usagebillingpipelinejava.domain.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.PricingRules;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.Tier;
import com.github.cuixudong74.usagebillingpipelinejava.service.EventIngestionService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PlanService;
import com.github.cuixudong74.usagebillingpipelinejava.web.dto.EventIngestRequest;
import com.github.cuixudong74.usagebillingpipelinejava.web.dto.PlanAssignRequest;
import com.github.cuixudong74.usagebillingpipelinejava.web.dto.PlanCreateRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class EventController {

    private final EventIngestionService eventIngestionService;

    public EventController(EventIngestionService eventIngestionService) {
        this.eventIngestionService = eventIngestionService;
    }

    @PostMapping("/events")
    public ResponseEntity<?> ingest(@Valid @RequestBody EventIngestRequest request) {
        EventIngestionService.IngestResult result = eventIngestionService.ingest(
                request.eventId(), request.accountId(), request.dimension(),
                request.quantity(), request.eventTime(), request.source());
        return ResponseEntity.ok(new IngestResponse(result.created(), result.event().getEventId()));
    }

    public record IngestResponse(boolean created, String eventId) {
    }
}
