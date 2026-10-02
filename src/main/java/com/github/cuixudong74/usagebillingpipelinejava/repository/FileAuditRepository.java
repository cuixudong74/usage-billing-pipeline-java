package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.model.AuditEntry;
import com.github.cuixudong74.usagebillingpipelinejava.storage.BillingStateStore;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class FileAuditRepository implements AuditRepository {
    private final BillingStateStore stateStore;

    public FileAuditRepository(BillingStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public AuditEntry save(AuditEntry entry) {
        return stateStore.commit(state -> {
            state.audits().put(entry.auditId(), entry);
            return state;
        }).audits().get(entry.auditId());
    }

    @Override
    public List<AuditEntry> findByAccount(String accountId) {
        return stateStore.load().audits().values().stream()
                .filter(entry -> entry.accountId().equals(accountId))
                .sorted(Comparator.comparing(AuditEntry::occurredAt).thenComparing(AuditEntry::auditId))
                .toList();
    }
}
