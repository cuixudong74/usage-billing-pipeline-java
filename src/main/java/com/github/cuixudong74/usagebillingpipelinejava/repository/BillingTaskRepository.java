package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.domain.BillingTask;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingTaskRepository extends JpaRepository<BillingTask, Long> {

    Optional<BillingTask> findByIdempotencyKey(String idempotencyKey);

    List<BillingTask> findByStatusIn(List<BillingTask.Status> statuses);

    List<BillingTask> findByAccountIdAndWindowIdOrderByIdDesc(String accountId, String windowId);
}
