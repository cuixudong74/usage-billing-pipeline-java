package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.domain.Bill;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillRepository extends JpaRepository<Bill, Long> {

    Optional<Bill> findByBillNo(String billNo);

    /** 当前有效版本 = 该账户该窗口的最大 billVersion。 */
    Optional<Bill> findTopByAccountIdAndWindowIdOrderByBillVersionDesc(String accountId, String windowId);

    List<Bill> findByAccountIdAndWindowIdOrderByBillVersionAsc(String accountId, String windowId);

    List<Bill> findByAccountIdOrderByWindowIdDescBillVersionDesc(String accountId);

    boolean existsByAccountIdAndWindowIdAndBillVersion(String accountId, String windowId, Integer billVersion);
}
