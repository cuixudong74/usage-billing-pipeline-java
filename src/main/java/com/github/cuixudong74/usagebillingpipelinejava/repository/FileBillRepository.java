package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.model.Bill;
import com.github.cuixudong74.usagebillingpipelinejava.model.BillStatus;
import com.github.cuixudong74.usagebillingpipelinejava.storage.BillingState;
import com.github.cuixudong74.usagebillingpipelinejava.storage.BillingStateStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class FileBillRepository implements BillRepository {
    private final BillingStateStore stateStore;

    public FileBillRepository(BillingStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public Bill save(Bill bill) {
        return stateStore.commit(state -> {
            state.bills().put(bill.billId(), bill);
            return state;
        }).bills().get(bill.billId());
    }

    @Override
    public Optional<Bill> findById(String billId) {
        return Optional.ofNullable(stateStore.load().bills().get(billId));
    }

    @Override
    public Optional<Bill> findCommitted(String accountId, Instant start, Instant end) {
        return stateStore.load().bills().values().stream()
                .filter(bill -> bill.accountId().equals(accountId))
                .filter(bill -> bill.windowStart().equals(start) && bill.windowEnd().equals(end))
                .filter(bill -> bill.status() == BillStatus.COMMITTED || bill.status() == BillStatus.ADJUSTED)
                .max(Comparator.comparing(Bill::billVersion));
    }

    @Override
    public List<Bill> findByAccount(String accountId) {
        return stateStore.load().bills().values().stream()
                .filter(bill -> bill.accountId().equals(accountId))
                .sorted(Comparator.comparing(Bill::windowStart).thenComparing(Bill::billVersion))
                .toList();
    }
}
