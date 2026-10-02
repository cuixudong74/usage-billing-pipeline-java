package com.github.cuixudong74.usagebillingpipelinejava.web;

import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.service.AdjustmentService;
import com.github.cuixudong74.usagebillingpipelinejava.service.AuditService;
import com.github.cuixudong74.usagebillingpipelinejava.service.BillingService;
import com.github.cuixudong74.usagebillingpipelinejava.service.EventService;
import com.github.cuixudong74.usagebillingpipelinejava.service.PlanService;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BillingController {
    private final EventService eventService;
    private final PlanService planService;
    private final BillingService billingService;
    private final AdjustmentService adjustmentService;
    private final AuditService auditService;

    public BillingController(EventService eventService,
                             PlanService planService,
                             BillingService billingService,
                             AdjustmentService adjustmentService,
                             AuditService auditService) {
        this.eventService = eventService;
        this.planService = planService;
        this.billingService = billingService;
        this.adjustmentService = adjustmentService;
        this.auditService = auditService;
    }

    @PostMapping("/events")
    public Object ingestEvent(@RequestBody UsageEvent event,
                              @RequestHeader("X-Actor") String actor,
                              @RequestHeader(value = "X-Roles", defaultValue = "EVENT_WRITER") String roles) {
        return eventService.ingest(event, actor(actor, roles));
    }

    @PostMapping("/plans")
    public Object commitPlan(@RequestBody PricingPlanVersion plan,
                             @RequestHeader("X-Actor") String actor,
                             @RequestHeader(value = "X-Roles", defaultValue = "ADMIN") String roles) {
        return planService.commit(plan, actor(actor, roles));
    }

    @PostMapping("/bills/preview")
    public Object preview(@RequestBody BillWindowRequest request,
                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                          @RequestHeader("X-Actor") String actor,
                          @RequestHeader(value = "X-Roles", defaultValue = "BILLER") String roles) {
        return billingService.preview(
                request.accountId(), request.start(), request.end(), idempotencyKey, actor(actor, roles));
    }

    @PostMapping("/bills/commit")
    public Object commitBill(@RequestBody BillWindowRequest request,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                             @RequestHeader("X-Actor") String actor,
                             @RequestHeader(value = "X-Roles", defaultValue = "BILLER") String roles) {
        return billingService.commit(
                request.accountId(), request.start(), request.end(), idempotencyKey, actor(actor, roles));
    }

    @PostMapping("/bills/{billId}/adjustments")
    public Object adjust(@PathVariable String billId,
                         @RequestBody AdjustmentRequest request,
                         @RequestHeader("X-Actor") String actor,
                         @RequestHeader(value = "X-Roles", defaultValue = "BILLER") String roles) {
        return adjustmentService.correct(billId, request.reason(), actor(actor, roles));
    }

    @GetMapping("/bills/current")
    public Object current(@RequestParam String accountId,
                          @RequestParam Instant start,
                          @RequestParam Instant end,
                          @RequestHeader("X-Actor") String actor,
                          @RequestHeader(value = "X-Roles", defaultValue = "BILLER") String roles) {
        return billingService.current(accountId, start, end, actor(actor, roles));
    }

    @GetMapping("/audit")
    public Object audit(@RequestParam String accountId,
                        @RequestHeader("X-Actor") String actor,
                        @RequestHeader(value = "X-Roles", defaultValue = "AUDITOR") String roles) {
        return auditService.list(accountId, actor(actor, roles));
    }

    private Actor actor(String subject, String rolesHeader) {
        Set<String> roles = Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toSet());
        return new Actor(subject, roles);
    }

    public record BillWindowRequest(String accountId, Instant start, Instant end) {
    }

    public record AdjustmentRequest(String reason) {
    }
}
