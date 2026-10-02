package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.domain.PlanAssignment;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanAssignmentRepository extends JpaRepository<PlanAssignment, Long> {

    List<PlanAssignment> findByAccountIdOrderByEffectiveFromDesc(String accountId);

    /** 查找覆盖窗口起点的分配记录（effectiveFrom <= windowStart 且 effectiveTo 为空或晚于 windowStart）。 */
    @Query("SELECT a FROM PlanAssignment a WHERE a.accountId = :accountId "
            + "AND a.effectiveFrom <= :at AND (a.effectiveTo IS NULL OR a.effectiveTo > :at) "
            + "ORDER BY a.effectiveFrom DESC")
    List<PlanAssignment> findCovering(@Param("accountId") String accountId, @Param("at") Instant at);
}
