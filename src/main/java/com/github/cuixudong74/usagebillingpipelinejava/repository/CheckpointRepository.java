package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.model.Checkpoint;
import java.util.Optional;

public interface CheckpointRepository {
    Checkpoint save(Checkpoint checkpoint);
    Optional<Checkpoint> find(String accountId, java.time.Instant start, java.time.Instant end);
}
