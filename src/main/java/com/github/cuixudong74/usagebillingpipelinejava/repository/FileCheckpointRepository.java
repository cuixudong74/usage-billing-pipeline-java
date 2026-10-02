package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.model.Checkpoint;
import com.github.cuixudong74.usagebillingpipelinejava.storage.BillingStateStore;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class FileCheckpointRepository implements CheckpointRepository {
    private final BillingStateStore stateStore;

    public FileCheckpointRepository(BillingStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public Checkpoint save(Checkpoint checkpoint) {
        return stateStore.commit(state -> {
            state.checkpoints().put(key(checkpoint), checkpoint);
            return state;
        }).checkpoints().get(key(checkpoint));
    }

    @Override
    public Optional<Checkpoint> find(String accountId, Instant start, Instant end) {
        return Optional.ofNullable(stateStore.load().checkpoints().get(accountId + "|" + start + "|" + end));
    }

    private String key(Checkpoint checkpoint) {
        return checkpoint.accountId() + "|" + checkpoint.windowStart() + "|" + checkpoint.windowEnd();
    }
}
