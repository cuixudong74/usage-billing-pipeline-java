package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.model.Checkpoint;
import com.github.cuixudong74.usagebillingpipelinejava.model.RecoveryState;
import com.github.cuixudong74.usagebillingpipelinejava.repository.CheckpointRepository;
import com.github.cuixudong74.usagebillingpipelinejava.storage.RecoveryStateStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RecoveryService {
    private final RecoveryStateStore recoveryStateStore;
    private final CheckpointRepository checkpointRepository;

    public RecoveryService(RecoveryStateStore recoveryStateStore,
                           CheckpointRepository checkpointRepository) {
        this.recoveryStateStore = recoveryStateStore;
        this.checkpointRepository = checkpointRepository;
    }

    public Map<String, String> recover() {
        Map<String, String> result = new LinkedHashMap<>();
        recoveryStateStore.commit(states -> {
            states.replaceAll((key, state) -> {
                if (!"IN_PROGRESS".equals(state.status())) {
                    return state;
                }
                return checkpointRepository.find(state.accountId(), state.windowStart(), state.windowEnd())
                        .map(this::committed)
                        .orElseGet(() -> interrupted(state));
            });
            return states;
        }).forEach((key, state) -> result.put(key, state.status()));
        return result;
    }

    private RecoveryState committed(Checkpoint checkpoint) {
        return new RecoveryState(
                checkpoint.accountId(),
                checkpoint.windowStart(),
                checkpoint.windowEnd(),
                "COMMITTED",
                checkpoint.billId(),
                Instant.now(),
                "recovery");
    }

    private RecoveryState interrupted(RecoveryState state) {
        return new RecoveryState(
                state.accountId(),
                state.windowStart(),
                state.windowEnd(),
                "INTERRUPTED",
                null,
                Instant.now(),
                "recovery");
    }
}
