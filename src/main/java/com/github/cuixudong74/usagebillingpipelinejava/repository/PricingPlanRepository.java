package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.domain.PricingPlan;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PricingPlanRepository extends JpaRepository<PricingPlan, Long> {

    Optional<PricingPlan> findByPlanCodeAndVersion(String planCode, Integer version);

    List<PricingPlan> findByPlanCodeOrderByVersionDesc(String planCode);

    List<PricingPlan> findByStatusOrderByPlanCodeAscVersionDesc(PricingPlan.Status status);
}
