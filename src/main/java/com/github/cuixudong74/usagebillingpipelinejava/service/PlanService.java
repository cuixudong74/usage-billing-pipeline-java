package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.authorization.Action;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.AuthorizationService;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ConflictException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.NotFoundException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ValidationException;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingRule;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

@Service
public class PlanService {
    private final com.github.cuixudong74.usagebillingpipelinejava.repository.PlanRepository planRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final AccountLocks accountLocks;

    public PlanService(com.github.cuixudong74.usagebillingpipelinejava.repository.PlanRepository planRepository,
                       AuthorizationService authorizationService,
                       AuditService auditService,
                       AccountLocks accountLocks) {
        this.planRepository = planRepository;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.accountLocks = accountLocks;
    }

    public PricingPlanVersion commit(PricingPlanVersion plan, Actor actor) {
        if (plan == null) {
            throw new ValidationException("Plan is required");
        }
        authorizationService.authorize(actor, Action.COMMIT_PLAN, plan.accountId());
        validate(plan);
        ReentrantLock lock = accountLocks.forAccount(plan.accountId());
        lock.lock();
        try {
            var existing = planRepository.findByAccount(plan.accountId());
            existing.stream()
                    .filter(item -> item.version() == plan.version())
                    .findAny()
                    .ifPresent(item -> {
                        throw new ConflictException("Plan version already exists");
                    });
            existing.stream()
                    .filter(item -> overlaps(item, plan))
                    .findAny()
                    .ifPresent(item -> {
                        throw new ConflictException("Plan effective period overlaps version " + item.version());
                    });

            PricingPlanVersion committed = new PricingPlanVersion(
                    plan.planId(),
                    plan.accountId(),
                    plan.version(),
                    plan.effectiveFrom(),
                    plan.effectiveTo(),
                    Instant.now(),
                    normalizeRules(plan.rules()));
            planRepository.save(committed);
            auditService.record(actor, Action.COMMIT_PLAN, committed.accountId(),
                    committed.planId() + "#" + committed.version(), Map.of(
                            "effectiveFrom", committed.effectiveFrom().toString(),
                            "ruleCount", String.valueOf(committed.rules().size())));
            return committed;
        } finally {
            lock.unlock();
        }
    }


    public PricingPlanVersion version(String accountId, int version) {
        return planRepository.findVersion(accountId, version)
                .orElseThrow(() -> new NotFoundException("Committed plan version not found: " + version));
    }

    public PricingPlanVersion effective(String accountId, Instant at) {
        return planRepository.findEffective(accountId, at)
                .orElseThrow(() -> new NotFoundException("No committed pricing plan for account at " + at));
    }

    private void validate(PricingPlanVersion plan) {
        require(!plan.accountId().isBlank(), "accountId is required");
        require(!plan.planId().isBlank(), "planId is required");
        require(plan.version() > 0, "version must be positive");
        require(plan.effectiveFrom() != null, "effectiveFrom is required");
        require(plan.effectiveTo() == null || plan.effectiveFrom().isBefore(plan.effectiveTo()),
                "effectiveFrom must precede effectiveTo");
        require(plan.rules() != null && !plan.rules().isEmpty(), "plan must contain pricing rules");
        plan.rules().forEach(this::validateRule);
        Set<String> ruleKeys = new HashSet<>();
        plan.rules().forEach(rule -> {
            String key = rule.metric() + "|" + new TreeMap<>(rule.dimensions());
            if (!ruleKeys.add(key)) {
                throw new ConflictException("Duplicate pricing rule for " + key);
            }
        });
    }

    private void validateRule(PricingRule rule) {
        require(!rule.metric().isBlank(), "rule metric is required");
        require(rule.tiers() != null && !rule.tiers().isEmpty(), "at least one tier is required");
        requireNonNegative(rule.minimumAmount(), "minimumAmount");
        requireNonNegative(rule.maximumAmount(), "maximumAmount");
        if (rule.minimumAmount() != null && rule.maximumAmount() != null) {
            require(rule.minimumAmount().compareTo(rule.maximumAmount()) <= 0,
                    "minimumAmount must not exceed maximumAmount");
        }
        if (rule.discountRate() != null) {
            require(rule.discountRate().signum() >= 0 && rule.discountRate().compareTo(BigDecimal.ONE) <= 0,
                    "discountRate must be between 0 and 1");
        }
        var tiers = new ArrayList<>(rule.tiers());
        BigDecimal expected = BigDecimal.ZERO;
        for (int i = 0; i < tiers.size(); i++) {
            PricingTier tier = tiers.get(i);
            require(tier.unitPrice() != null && tier.unitPrice().signum() >= 0, "tier price must be non-negative");
            if (i < tiers.size() - 1) {
                require(tier.upperBoundExclusive() != null, "all tiers except the last need an upper bound");
                require(tier.upperBoundExclusive().compareTo(expected) > 0, "tier bounds must be strictly increasing");
                expected = tier.upperBoundExclusive();
            }
        }
    }

    private List<PricingRule> normalizeRules(List<PricingRule> rules) {
        return rules.stream()
                .map(rule -> new PricingRule(
                        rule.metric(),
                        new TreeMap<>(rule.dimensions()),
                        rule.tiers().stream()
                                .sorted(Comparator.comparing(PricingTier::upperBoundExclusive,
                                        Comparator.nullsLast(Comparator.naturalOrder())))
                                .toList(),
                        rule.minimumAmount(),
                        rule.maximumAmount(),
                        rule.discountRate()))
                .sorted(Comparator.comparing(PricingRule::metric)
                        .thenComparing(rule -> rule.dimensions().toString()))
                .toList();
    }

    private boolean overlaps(PricingPlanVersion left, PricingPlanVersion right) {
        return left.effectiveFrom().isBefore(orMax(right.effectiveTo()))
                && right.effectiveFrom().isBefore(orMax(left.effectiveTo()));
    }

    private Instant orMax(Instant instant) {
        return instant == null ? Instant.MAX : instant;
    }

    private void requireNonNegative(BigDecimal value, String field) {
        require(value == null || value.signum() >= 0, field + " must be non-negative");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ValidationException(message);
        }
    }
}
