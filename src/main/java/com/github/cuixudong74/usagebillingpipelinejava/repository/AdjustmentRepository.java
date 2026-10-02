package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.domain.Adjustment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdjustmentRepository extends JpaRepository<Adjustment, Long> {

    List<Adjustment> findByAccountIdAndWindowIdOrderByIdAsc(String accountId, String windowId);
}
