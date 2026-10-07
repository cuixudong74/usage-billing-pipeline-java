package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.model.RecoveryState;
import com.github.cuixudong74.usagebillingpipelinejava.storage.RecoveryStateStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Startup recovery: every task left in {@code IN_PROGRESS} by a crash is driven to completion
 * instead of being parked for manual resubmission.
 *
 * <p>Tasks for different accounts run concurrently, so a slow recovery for one account never
 * blocks other accounts. Each task is idempotent (see
 * {@link BillingService#resumeInterrupted(RecoveryState)}): an already finished bill is detected
 * and merely re-marked COMMITTED, a truly unfinished commit/correction is re-executed, and sealed
 * windows are never recomputed. A window that still fails keeps its IN_PROGRESS marker with the
 * failure recorded as the owner note, allowing the next restart to retry it without touching any
 * other account.</p>
 */
@Service
public class RecoveryService {
    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final RecoveryStateStore recoveryStateStore;
    private final BillingService billingService;

    public RecoveryService(RecoveryStateStore recoveryStateStore, BillingService billingService) {
        this.recoveryStateStore = recoveryStateStore;
        this.billingService = billingService;
    }

    public Map<String, String> recover() {
        Map<String, RecoveryState> states = recoveryStateStore.load();
        List<RecoveryState> interrupted = states.values().stream()
                .filter(state -> "IN_PROGRESS".equals(state.status()))
                .toList();
        if (interrupted.isEmpty()) {
            Map<String, String> statuses = new ConcurrentHashMap<>();
            states.forEach((key, state) -> statuses.put(key, state.status()));
            return statuses;
        }

        ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "billing-recovery");
            thread.setDaemon(true);
            return thread;
        });
        Map<String, String> statuses = new ConcurrentHashMap<>();
        states.forEach((key, state) -> statuses.put(key, state.status()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (RecoveryState state : interrupted) {
                futures.add(pool.submit(() -> resumeOne(state, statuses)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    log.error("Recovery task failed", e);
                }
            }
        } finally {
            pool.shutdown();
        }
        return statuses;
    }

    private void resumeOne(RecoveryState state, Map<String, String> statuses) {
        String key = RecoveryStateStore.key(state.accountId(), state.windowStart(), state.windowEnd());
        try {
            billingService.resumeInterrupted(state);
            statuses.put(key, "COMMITTED");
        } catch (RuntimeException e) {
            log.warn("Could not resume billing task {} for account {}: {}",
                    key, state.accountId(), e.getMessage());
            // Keep the task retryable on the next restart; record why it was not resumed yet.
            recoveryStateStore.put(new RecoveryState(
                    state.accountId(), state.windowStart(), state.windowEnd(),
                    "IN_PROGRESS", state.billId(), Instant.now(),
                    "recovery-failed: " + e.getMessage(),
                    state.operation(), state.idempotencyKey(), state.reason()));
            statuses.put(key, "IN_PROGRESS");
        }
    }
}
