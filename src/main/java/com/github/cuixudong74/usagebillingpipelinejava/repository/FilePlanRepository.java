package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion;
import com.github.cuixudong74.usagebillingpipelinejava.storage.JsonFileStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class FilePlanRepository implements PlanRepository {
    private static final String TYPE = "plans";
    private final JsonFileStore store;
    private final Map<String, PricingPlanVersion> plans;

    public FilePlanRepository(JsonFileStore store) {
        this.store = store;
        this.plans = store.readAll(TYPE, new TypeReference<>() {
        });
    }

    @Override
    public synchronized PricingPlanVersion save(PricingPlanVersion plan) {
        String id = plan.planId() + "#" + plan.version();
        store.put(TYPE, id, plan);
        plans.put(id, plan);
        return plan;
    }

    @Override
    public synchronized Optional<PricingPlanVersion> findEffective(String accountId, Instant at) {
        return plans.values().stream()
                .filter(plan -> plan.accountId().equals(accountId))
                .filter(plan -> !plan.effectiveFrom().isAfter(at) && (plan.effectiveTo() == null || at.isBefore(plan.effectiveTo())))
                .max(Comparator.comparing(PricingPlanVersion::effectiveFrom)
                        .thenComparing(PricingPlanVersion::version));
    }


    @Override
    public synchronized Optional<PricingPlanVersion> findVersion(String accountId, int version) {
        return plans.values().stream()
                .filter(plan -> plan.accountId().equals(accountId) && plan.version() == version)
                .findFirst();
    }

    @Override
    public synchronized List<PricingPlanVersion> findByAccount(String accountId) {
        return plans.values().stream()
                .filter(plan -> plan.accountId().equals(accountId))
                .sorted(Comparator.comparing(PricingPlanVersion::effectiveFrom).thenComparing(PricingPlanVersion::version))
                .toList();
    }
}
