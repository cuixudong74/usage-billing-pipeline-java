package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.model.AuditEntry;
import java.util.List;

public interface AuditRepository {
    AuditEntry save(AuditEntry entry);
    List<AuditEntry> findByAccount(String accountId);
}
