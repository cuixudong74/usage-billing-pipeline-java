package com.github.cuixudong74.usagebillingpipelinejava.web;

import com.github.cuixudong74.usagebillingpipelinejava.domain.PlanAssignment;
import com.github.cuixudong74.usagebillingpipelinejava.domain.PricingPlan;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.PricingRules;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.Tier;
import com.github.cuixudong74.usagebillingpipelinejava.service.PlanService;
import com.github.cuixudong74.usagebillingpipelinejava.web.dto.PlanAssignRequest;
import com.github.cuixudong74.usagebillingpipelinejava.web.dto.PlanCreateRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @PostMapping
    public ResponseEntity<?> createDraft(@Valid @RequestBody PlanCreateRequest request) {
        PricingRules rules = toRules(request.rules());
        PricingPlan plan = planService.createDraft(request.planCode(), request.effectiveFrom(),
                request.effectiveTo(), rules, "web");
        return ResponseEntity.ok(new PlanResponse(plan.getPlanCode(), plan.getVersion(),
                plan.getStatus().name()));
    }

    @PostMapping("/{planCode}/versions/{version}/commit")
    public ResponseEntity<?> commit(@PathVariable String planCode, @PathVariable Integer version) {
        PricingPlan plan = planService.commitPlan(planCode, version);
        return ResponseEntity.ok(new PlanResponse(plan.getPlanCode(), plan.getVersion(),
                plan.getStatus().name()));
    }

    @PostMapping("/assignments")
    public ResponseEntity<?> assign(@Valid @RequestBody PlanAssignRequest request) {
        PlanAssignment assignment = planService.assignPlan(request.accountId(), request.planCode(),
                request.planVersion(), request.effectiveFrom(), request.effectiveTo(), "web");
        return ResponseEntity.ok(new AssignmentResponse(assignment.getId(), assignment.getAccountId(),
                assignment.getPlanCode(), assignment.getPlanVersion(),
                assignment.getEffectiveFrom(), assignment.getEffectiveTo()));
    }

    private PricingRules toRules(PlanCreateRequest.RulesDto dto) {
        PricingRules rules = new PricingRules();
        rules.setCurrency(dto.currency() == null ? "CNY" : dto.currency());
        if (dto.discount() != null) {
            rules.setDiscount(dto.discount());
        }
        rules.setMinCharge(dto.minCharge());
        rules.setCap(dto.cap());
        List<Tier> tiers = dto.tiers().stream()
                .map(t -> new Tier(t.upTo(), t.pricePerUnit()))
                .toList();
        rules.setTiers(tiers);
        return rules;
    }

    public record PlanResponse(String planCode, Integer version, String status) {
    }

    public record AssignmentResponse(Long id, String accountId, String planCode, Integer planVersion,
                                     java.time.Instant effectiveFrom, java.time.Instant effectiveTo) {
    }
}
