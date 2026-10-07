package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.model.RecoveryState;
import com.github.cuixudong74.usagebillingpipelinejava.storage.RecoveryStateStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RecoveryService {
    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);
    private static final AtomicInteger RECOVERY_THREADS = new AtomicInteger();
    private static final ExecutorService RECOVERY_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "billing-recovery-" + RECOVERY_THREADS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final RecoveryStateStore recoveryStateStore;
    private final BillingService billingService;

    public RecoveryService(RecoveryStateStore recoveryStateStore,
                           BillingService billingService) {
        this.recoveryStateStore = recoveryStateStore;
        this.billingService = billingService;
    }

    public Map<String, String> recover() {
        Map<String, List<RecoveryState>> statesByAccount = recoveryStateStore.load().values().stream()
                .filter(state -> "IN_PROGRESS".equals(state.status()) || "INTERRUPTED".equals(state.status()))
                .collect(java.util.stream.Collectors.groupingBy(RecoveryState::accountId, LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));

        List<CompletableFuture<Void>> recoveries = statesByAccount.entrySet().stream()
                .map(entry -> CompletableFuture.runAsync(() -> recoverAccount(entry.getKey(), entry.getValue()),
                        RECOVERY_EXECUTOR))
                .toList();

        CompletableFuture.allOf(recoveries.toArray(CompletableFuture[]::new)).join();
        return recoveryStateStore.load().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().status(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private void recoverAccount(String accountId, List<RecoveryState> states) {
        for (RecoveryState state : states) {
            try {
                billingService.resumeInterrupted(state);
            } catch (RuntimeException e) {
                log.error("Automatic recovery failed for account {} window {} to {}",
                        accountId, state.windowStart(), state.windowEnd(), e);
            }
        }
    }
}
