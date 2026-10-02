package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.model.Adjustment;
import com.github.cuixudong74.usagebillingpipelinejava.storage.BillingStateStore;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class FileAdjustmentRepository implements AdjustmentRepository {
    private final BillingStateStore stateStore;

    public FileAdjustmentRepository(BillingStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public Adjustment save(Adjustment adjustment) {
        return stateStore.commit(state -> {
            state.adjustments().put(adjustment.adjustmentId(), adjustment);
            return state;
        }).adjustments().get(adjustment.adjustmentId());
    }

    @Override
    public List<Adjustment> findByAccount(String accountId) {
        return stateStore.load().adjustments().values().stream()
                .filter(adjustment -> adjustment.accountId().equals(accountId))
                .sorted(Comparator.comparing(Adjustment::createdAt))
                .toList();
    }
}
