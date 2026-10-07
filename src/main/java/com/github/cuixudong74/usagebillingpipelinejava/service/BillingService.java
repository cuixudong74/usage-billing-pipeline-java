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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            recoveryStateStore.put(new RecoveryState(
                    accountId, start, end, "IN_PROGRESS", null, Instant.now(), actor.subject()));
            Bill existing = billRepository.findCommitted(accountId, start, end).orElse(null);
            if (existing != null) {
                if (existing.status() == BillStatus.SUPERSEDED) {
                    throw new ConflictException("Current bill is superseded; use correction");
                }
                recoveryStateStore.put(new RecoveryState(
                        accountId, start, end, "COMMITTED", existing.billId(), Instant.now(), actor.subject()));
                return existing;
            }
            Bill bill = calculate(accountId, start, end, 1, BillStatus.COMMITTED, idempotencyKey, null, actor);
            Checkpoint checkpoint = new Checkpoint(
                    accountId, start, end, bill.billId(), sourceEventIds(bill), Instant.now());
            AuditEntry audit = new AuditEntry(
                    UUID.randomUUID().toString(),
                    accountId,
                    actor.subject(),
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
            recoveryStateStore.put(new RecoveryState(
                    accountId, start, end, "COMMITTED", bill.billId(), Instant.now(), actor.subject()));
            return bill;
        } finally {
            lock.unlock();
        }
    }

    public void resumeInterrupted(RecoveryState state) {
        if (!Set.of("IN_PROGRESS", "INTERRUPTED").contains(state.status())) {
            return;
        }

        ReentrantLock lock = accountLocks.forAccount(state.accountId());
        lock.lock();
        try {
            RecoveryState current = recoveryStateStore.load()
                    .getOrDefault(RecoveryStateStore.key(state.accountId(), state.windowStart(), state.windowEnd()), state);
            if (!Set.of("IN_PROGRESS", "INTERRUPTED").contains(current.status())) {
                return;
            }

            Optional<Bill> committed = billRepository.findCommitted(
                    current.accountId(), current.windowStart(), current.windowEnd());
            if (current.billId() == null) {
                if (committed.isPresent()) {
                    markRecovered(current, committed.get());
                    return;
                }
                resumeCommit(current);
            } else {
                resumeAdjustment(current, committed.orElse(null));
            }
        } finally {
            lock.unlock();
        }
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
            recoveryStateStore.put(new RecoveryState(
                    current.accountId(),
                    current.windowStart(),
                    current.windowEnd(),
                    "IN_PROGRESS",
                    current.billId(),
                    Instant.now(),
                    actor.subject()));
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
                recoveryStateStore.put(new RecoveryState(
                        latest.accountId(),
                        latest.windowStart(),
                        latest.windowEnd(),
                        "COMMITTED",
                        latest.billId(),
                        Instant.now(),
                        actor.subject()));
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
                    actor.subject());
            AuditEntry audit = new AuditEntry(
                    UUID.randomUUID().toString(),
                    latest.accountId(),
                    actor.subject(),
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
            recoveryStateStore.put(new RecoveryState(
                    latest.accountId(),
                    latest.windowStart(),
                    latest.windowEnd(),
                    "COMMITTED",
                    replacement.billId(),
                    Instant.now(),
                    actor.subject()));
            return replacement;
        } finally {
            lock.unlock();
        }
    }

    private void resumeCommit(RecoveryState state) {
        commit(state.accountId(), state.windowStart(), state.windowEnd(), null, systemActor());
    }

    private void resumeAdjustment(RecoveryState state, Bill committed) {
        Bill original = stateStore.load().bills().get(state.billId());
        if (original == null) {
            if (committed != null) {
                markRecovered(state, committed);
                return;
            }
            throw new IllegalStateException("Cannot recover adjustment: original bill is missing " + state.billId());
        }

        if (original.status() == BillStatus.SUPERSEDED) {
            Bill replacement = committed == null
                    ? billRepository.findById(findReplacementBillId(original)).orElseThrow(() ->
                            new IllegalStateException("Cannot find replacement bill for " + original.billId()))
                    : committed;
            markRecovered(state, replacement);
            return;
        }

        if (committed != null && !committed.billId().equals(original.billId())) {
            markRecovered(state, committed);
            return;
        }

        correct(original, "Automatic recovery after interrupted correction", systemActor());
    }

    private String findReplacementBillId(Bill original) {
        return stateStore.load().adjustments().values().stream()
                .filter(adjustment -> adjustment.originalBillId().equals(original.billId()))
                .map(adjustment -> adjustment.replacementBillId())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Cannot find replacement bill for " + original.billId()));
    }

    private void markRecovered(RecoveryState state, Bill bill) {
        recoveryStateStore.put(new RecoveryState(
                state.accountId(),
                state.windowStart(),
                state.windowEnd(),
                "COMMITTED",
                bill.billId(),
                Instant.now(),
                "recovery"));
    }

    private Actor systemActor() {
        return new Actor("system", Set.of("ADMIN"));
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
        List<BillLine> lines = usage.values().stream()
                .sorted(Comparator.comparing(UsageAggregate::key, MeterKey.businessOrder()))
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
