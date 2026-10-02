package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PlanRepository {
    PricingPlanVersion save(PricingPlanVersion plan);
    Optional<PricingPlanVersion> findEffective(String accountId, Instant at);
    Optional<PricingPlanVersion> findVersion(String accountId, int version);
    List<PricingPlanVersion> findByAccount(String accountId);
}
