package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.authorization.Action;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.exception.NotFoundException;
import com.github.cuixudong74.usagebillingpipelinejava.model.Adjustment;
import com.github.cuixudong74.usagebillingpipelinejava.model.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.repository.AdjustmentRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.BillRepository;
import java.util.Comparator;
import org.springframework.stereotype.Service;

@Service
public class AdjustmentService {
    private final BillRepository billRepository;
    private final AdjustmentRepository adjustmentRepository;
    private final BillingService billingService;

    public AdjustmentService(BillRepository billRepository,
                             AdjustmentRepository adjustmentRepository,
                             BillingService billingService) {
        this.billRepository = billRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.billingService = billingService;
    }

    public Adjustment correct(String billId, String reason, Actor actor) {
        Bill requested = billRepository.findById(billId)
                .orElseThrow(() -> new NotFoundException("Bill not found: " + billId));
        Bill current = billRepository.findCommitted(
                        requested.accountId(), requested.windowStart(), requested.windowEnd())
                .orElse(requested);
        Bill replacement = billingService.correct(current, reason, actor);
        return adjustmentRepository.findByAccount(current.accountId()).stream()
                .filter(adjustment -> adjustment.replacementBillId().equals(replacement.billId()))
                .max(Comparator.comparing(Adjustment::createdAt))
                .orElseThrow(() -> new NotFoundException("No amount difference was produced; bill remains unchanged"));
    }
}
