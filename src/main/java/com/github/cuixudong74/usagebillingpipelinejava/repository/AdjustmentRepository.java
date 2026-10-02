package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.model.Adjustment;
import java.util.List;

public interface AdjustmentRepository {
    Adjustment save(Adjustment adjustment);
    List<Adjustment> findByAccount(String accountId);
}
