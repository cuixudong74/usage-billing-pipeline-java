package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.model.Bill;
import java.util.List;
import java.util.Optional;

public interface BillRepository {
    Bill save(Bill bill);
    Optional<Bill> findById(String billId);
    Optional<Bill> findCommitted(String accountId, java.time.Instant start, java.time.Instant end);
    List<Bill> findByAccount(String accountId);
}
