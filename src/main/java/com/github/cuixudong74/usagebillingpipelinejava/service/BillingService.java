package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.authorization.Action;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.AuthorizationService;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ConflictException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.NotFoundException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ValidationException;
import com.github.cuixudong74.usagebillingpipelinejava.model.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.model.BillLine;
import com.github.cuixudong74.usagebillingpipelinejava.model.BillStatus;
import com.github.cuixudong74.usagebillingpipelinejava.model.Adjustment;
import com.github.cuixudong74.usagebillingpipelinejava.model.Checkpoint;
import com.github.cuixudong74.usagebillingpipelinejava.model.MeterKey;
import com.github.cuixudong74.usagebillingpipelinejava.model.RecoveryState;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageAggregate;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.model.AuditEntry;
import com.github.cuixudong74.usagebillingpipelinejava.repository.BillRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.EventRepository;
import com.github.cuixudong74.usagebillingpipelinejava.storage.BillingStateStore;
import com.github.cuixudong74.usagebillingpipelinejava.storage.RecoveryStateStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

@Service
public class BillingService {
    private final EventRepository eventRepository;
    private final BillRepository billRepository;
    private final BillingStateStore stateStore;
    private final RecoveryStateStore recoveryStateStore;
    private final PlanService planService;
    private final UsageAggregationService aggregationService;
    private final PricingService pricingService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final AccountLocks accountLocks;

    public BillingService(EventRepository eventRepository,
                          BillRepository billRepository,
                          BillingStateStore stateStore,
                          RecoveryStateStore recoveryStateStore,
                          PlanService planService,
                          UsageAggregationService aggregationService,
                          PricingService pricingService,
                          AuthorizationService authorizationService,
                          AuditService auditService,
                          AccountLocks accountLocks) {
        this.eventRepository = eventRepository;
        this.billRepository = billRepository;
        this.stateStore = stateStore;
        this.recoveryStateStore = recoveryStateStore;
        this.planService = planService;
        this.aggregationService = aggregationService;
        this.pricingService = pricingService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.accountLocks = accountLocks;
    }

    public Bill preview(String accountId, Instant start, Instant end, String idempotencyKey, Actor actor) {
        validateWindow(accountId, start, end);
        authorizationService.authorize(actor, Action.PREVIEW_BILL, accountId);
        ReentrantLock lock = accountLocks.forAccount(accountId);
        lock.lock();
        try {
            Bill bill = calculate(accountId, start, end, 1, BillStatus.PREVIEW, idempotencyKey, null, actor);
            auditService.record(actor, Action.PREVIEW_BILL, accountId, bill.billId(), Map.of(
                    "windowStart", start.toString(),
                    "windowEnd", end.toString(),
                    "idempotencyKey", safeKey(idempotencyKey)));
            return bill;
        } finally {
            lock.unlock();
        }
    }

    public Bill commit(String accountId, Instant start, Instant end, String idempotencyKey, Actor actor) {
        validateWindow(accountId, start, end);
        authorizationService.authorize(actor, Action.COMMIT_BILL, accountId);
        ReentrantLock lock = accountLocks.forAccount(accountId);
        lock.lock();
        try {
            markInProgress(accountId, start, end, null, RecoveryState.OP_COMMIT, idempotencyKey, null, actor);
            return commitWithinLock(accountId, start, end, idempotencyKey, actor);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Commit pipeline that assumes the account lock is held and an IN_PROGRESS marker exists.
     * Shared by the normal commit endpoint and by startup recovery of an interrupted commit, so
     * both paths are guaranteed to be idempotent and never produce a second bill.
     */
    private Bill commitWithinLock(String accountId, Instant start, Instant end, String idempotencyKey, Actor actor) {
        Bill existing = billRepository.findCommitted(accountId, start, end).orElse(null);
        if (existing != null) {
            markCommitted(accountId, start, end, existing.billId(), actor);
            return existing;
        }
        Bill bill = calculate(accountId, start, end, 1, BillStatus.COMMITTED, idempotencyKey, null, actor);
        Checkpoint checkpoint = new Checkpoint(
                accountId, start, end, bill.billId(), sourceEventIds(bill), Instant.now());
        AuditEntry audit = new AuditEntry(
                UUID.randomUUID().toString(),
                accountId,
                actor == null ? "recovery" : actor.subject(),
                Action.COMMIT_BILL.name(),
                bill.billId(),
                Map.of(
                        "planVersion", String.valueOf(bill.planVersion()),
                        "planId", bill.planId(),
                        "totalAmount", bill.totalAmount().toPlainString(),
                        "sourceEventIds", String.join(",", sourceEventIds(bill)),
                        "idempotencyKey", safeKey(idempotencyKey)),
                Instant.now());
        stateStore.commit(state -> {
            state.bills().put(bill.billId(), bill);
            state.checkpoints().put(accountId + "|" + start + "|" + end, checkpoint);
            state.audits().put(audit.auditId(), audit);
            return state;
        });
        markCommitted(accountId, start, end, bill.billId(), actor);
        return bill;
    }

    /**
     * Resume an interrupted billing task after a restart. Idempotent:
     * <ul>
     *   <li>if a bill/checkpoint already exists, the window is simply marked COMMITTED;</li>
     *   <li>otherwise the original COMMIT or ADJUST task is re-executed to completion;</li>
     *   <li>already sealed windows are never recomputed or duplicated.</li>
     * </ul>
     */
    public Bill resumeInterrupted(RecoveryState state) {
        ReentrantLock lock = accountLocks.forAccount(state.accountId());
        lock.lock();
        try {
            Actor actor = systemActor();
            if (RecoveryState.OP_ADJUST.equals(state.operation())) {
                // A correction must always be (re)evaluated: the late events are present but the
                // replacement bill may never have been written. correctWithinLock is idempotent
                // once the replacement already exists because findCommitted returns it and a
                // no-difference recompute short-circuits.
                Bill base = state.billId() == null ? null
                        : billRepository.findById(state.billId()).orElse(null);
                markInProgress(state.accountId(), state.windowStart(), state.windowEnd(), state.billId(),
                        state.operation(), state.idempotencyKey(), state.reason(), actor);
                return correctWithinLock(base, state.reason(), actor);
            }
            // COMMIT task: if the bill/checkpoint already landed, just finalize the marker.
            Bill committed = billRepository.findCommitted(
                    state.accountId(), state.windowStart(), state.windowEnd()).orElse(null);
            if (committed != null) {
                markCommitted(state.accountId(), state.windowStart(), state.windowEnd(),
                        committed.billId(), actor);
                return committed;
            }
            markInProgress(state.accountId(), state.windowStart(), state.windowEnd(), state.billId(),
                    state.operation(), state.idempotencyKey(), state.reason(), actor);
            return commitWithinLock(state.accountId(), state.windowStart(), state.windowEnd(),
                    state.idempotencyKey(), actor);
        } finally {
            lock.unlock();
        }
    }

    private void markInProgress(String accountId, Instant start, Instant end, String billId,
                                String operation, String idempotencyKey, String reason, Actor actor) {
        recoveryStateStore.put(new RecoveryState(
                accountId, start, end, "IN_PROGRESS", billId, Instant.now(),
                actor == null ? "system" : actor.subject(),
                operation, idempotencyKey, reason));
    }

    private void markCommitted(String accountId, Instant start, Instant end, String billId, Actor actor) {
        recoveryStateStore.put(new RecoveryState(
                accountId, start, end, "COMMITTED", billId, Instant.now(),
                actor == null ? "system" : actor.subject(),
                RecoveryState.OP_COMMIT, null, null));
    }

    private Actor systemActor() {
        return new Actor("recovery", java.util.Set.of("ADMIN"));
    }

    public Bill current(String accountId, Instant start, Instant end, Actor actor) {
        validateWindow(accountId, start, end);
        authorizationService.authorize(actor, Action.READ_BILL, accountId);
        return billRepository.findCommitted(accountId, start, end)
                .orElseThrow(() -> new NotFoundException("No committed bill for requested window"));
    }


    public Bill correct(Bill current, String reason, Actor actor) {
        if (current == null) {
            throw new ValidationException("Current bill is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("Correction reason is required");
        }
        authorizationService.authorize(actor, Action.ADJUST_BILL, current.accountId());
        ReentrantLock lock = accountLocks.forAccount(current.accountId());
        lock.lock();
        try {
            markInProgress(current.accountId(), current.windowStart(), current.windowEnd(),
                    current.billId(), RecoveryState.OP_ADJUST, current.idempotencyKey(), reason, actor);
            return correctWithinLock(current, reason, actor);
        } finally {
            lock.unlock();
        }
    }

    private Bill correctWithinLock(Bill current, String reason, Actor actor) {
        Bill latest = billRepository.findCommitted(
                current.accountId(), current.windowStart(), current.windowEnd()).orElse(current);
        Bill replacement = calculate(
                latest.accountId(),
                latest.windowStart(),
                latest.windowEnd(),
                latest.billVersion() + 1,
                BillStatus.ADJUSTED,
                latest.idempotencyKey(),
                latest.planVersion(),
                actor);
        if (sameMoney(latest, replacement)) {
            markCommitted(latest.accountId(), latest.windowStart(), latest.windowEnd(),
                    latest.billId(), actor);
            return latest;
        }
        BigDecimal delta = replacement.totalAmount().subtract(latest.totalAmount());
        String checkpointKey = latest.accountId() + "|" + latest.windowStart() + "|" + latest.windowEnd();
        Checkpoint checkpoint = new Checkpoint(
                latest.accountId(),
                latest.windowStart(),
                latest.windowEnd(),
                replacement.billId(),
                sourceEventIds(replacement),
                Instant.now());
        Adjustment adjustment = new Adjustment(
                "adj-" + UUID.randomUUID(),
                latest.accountId(),
                latest.billId(),
                replacement.billId(),
                reason,
                delta,
                sourceEventIds(replacement),
                Instant.now(),
                actor == null ? "recovery" : actor.subject());
        AuditEntry audit = new AuditEntry(
                UUID.randomUUID().toString(),
                latest.accountId(),
                actor == null ? "recovery" : actor.subject(),
                Action.ADJUST_BILL.name(),
                replacement.billId(),
                java.util.Map.of(
                        "previousBillId", latest.billId(),
                        "adjustmentId", adjustment.adjustmentId(),
                        "previousAmount", latest.totalAmount().toPlainString(),
                        "newAmount", replacement.totalAmount().toPlainString(),
                        "amountDelta", delta.toPlainString(),
                        "planId", replacement.planId(),
                        "planVersion", String.valueOf(replacement.planVersion()),
                        "sourceEventIds", String.join(",", sourceEventIds(replacement)),
                        "reason", reason),
                Instant.now());
        Bill superseded = new Bill(
                latest.billId(),
                latest.accountId(),
                latest.windowStart(),
                latest.windowEnd(),
                latest.billVersion(),
                BillStatus.SUPERSEDED,
                latest.planId(),
                latest.planVersion(),
                latest.lines(),
                latest.totalAmount(),
                latest.createdAt(),
                latest.createdBy(),
                latest.idempotencyKey());
        stateStore.commit(state -> {
            state.bills().put(superseded.billId(), superseded);
            state.bills().put(replacement.billId(), replacement);
            state.adjustments().put(adjustment.adjustmentId(), adjustment);
            state.checkpoints().put(checkpointKey, checkpoint);
            state.audits().put(audit.auditId(), audit);
            return state;
        });
        markCommitted(latest.accountId(), latest.windowStart(), latest.windowEnd(),
                replacement.billId(), actor);
        return replacement;
    }

    private boolean sameMoney(Bill left, Bill right) {
        return left.totalAmount().compareTo(right.totalAmount()) == 0
                && left.lines().size() == right.lines().size()
                && left.lines().stream().allMatch(line -> right.lines().stream().anyMatch(other ->
                        line.key().equals(other.key()) && line.netAmount().compareTo(other.netAmount()) == 0
                                && line.quantity().compareTo(other.quantity()) == 0));
    }

    private Bill calculate(String accountId,
                           Instant start,
                           Instant end,
                           int version,
                           BillStatus status,
                           String idempotencyKey,
                           Integer fixedPlanVersion,
                           Actor actor) {
        List<UsageEvent> events = eventRepository.findByAccount(accountId).stream()
                .filter(event -> event.eventTime().compareTo(start) >= 0 && event.eventTime().isBefore(end))
                .toList();
        Map<MeterKey, UsageAggregate> usage = aggregationService.aggregate(events, start, end);
        PricingPlanVersion plan = fixedPlanVersion == null
                ? planService.effective(accountId, start)
                : planService.version(accountId, fixedPlanVersion);
        if (plan.effectiveTo() != null && plan.effectiveTo().isBefore(end)) {
            throw new ConflictException("Billing window crosses pricing version boundary at " + plan.effectiveTo());
        }
        // Line order is derived only from business keys (metric, dimensions), never from event
        // arrival order or map iteration, so preview/commit/restart always produce the same lines.
        List<BillLine> lines = usage.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(UsageAggregationService.METER_KEY_ORDER))
                .map(Map.Entry::getValue)
                .map(item -> pricingService.price(item, plan))
                .toList();
        BigDecimal total = lines.stream().map(BillLine::netAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String billId = "bill-" + accountId + "-" + start.toEpochMilli() + "-" + end.toEpochMilli() + "-v" + version;
        return new Bill(
                billId,
                accountId,
                start,
                end,
                version,
                status,
                plan.planId(),
                plan.version(),
                List.copyOf(lines),
                total,
                Instant.now(),
                actor == null ? "system" : actor.subject(),
                idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey);
    }

    private List<String> sourceEventIds(Bill bill) {
        return bill.lines().stream().flatMap(line -> line.eventIds().stream()).distinct().sorted().toList();
    }

    private String safeKey(String key) {
        return key == null || key.isBlank() ? "" : key;
    }

    private void validateWindow(String accountId, Instant start, Instant end) {
        if (accountId == null || accountId.isBlank()) {
            throw new ValidationException("accountId is required");
        }
        if (start == null || end == null || !start.isBefore(end)) {
            throw new ValidationException("Window must be a non-empty half-open interval [start,end)");
        }
    }
}
