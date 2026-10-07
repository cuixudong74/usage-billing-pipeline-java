package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion;
import com.github.cuixudong74.usagebillingpipelinejava.storage.JsonFileStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
        normalizeLegacyKeys();
    }

    /**
     * Older builds keyed plans by {@code planId#version} without the account, so two accounts
     * sharing the same plan id/version collided. Rewrite keys transparently on startup so existing
     * plan data keeps working without a manual migration.
     */
    private synchronized void normalizeLegacyKeys() {
        boolean needsRewrite = plans.entrySet().stream()
                .anyMatch(entry -> !entry.getKey().equals(compositeKey(entry.getValue())));
        if (!needsRewrite) {
            return;
        }
        Map<String, PricingPlanVersion> normalized = new LinkedHashMap<>();
        plans.values().forEach(plan -> normalized.putIfAbsent(compositeKey(plan), plan));
        store.replaceAll(TYPE, normalized);
        plans.clear();
        plans.putAll(normalized);
    }

    @Override
    public synchronized PricingPlanVersion save(PricingPlanVersion plan) {
        String id = compositeKey(plan);
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

    private static String compositeKey(PricingPlanVersion plan) {
        return plan.accountId() + "|" + plan.planId() + "#" + plan.version();
    }
}
