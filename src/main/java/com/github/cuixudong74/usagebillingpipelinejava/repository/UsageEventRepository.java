package com.github.cuixudong74.usagebillingpipelinejava.repository;

import com.github.cuixudong74.usagebillingpipelinejava.domain.UsageEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    Optional<UsageEvent> findByAccountIdAndEventId(String accountId, String eventId);

    boolean existsByAccountIdAndEventId(String accountId, String eventId);

    /** 按事件时间归属窗口：[windowStart, windowEnd)，与摄入顺序无关。 */
    @Query("SELECT e FROM UsageEvent e WHERE e.accountId = :accountId "
            + "AND e.eventTime >= :start AND e.eventTime < :end ORDER BY e.eventTime ASC, e.id ASC")
    List<UsageEvent> findInWindow(@Param("accountId") String accountId,
                                  @Param("start") Instant start,
                                  @Param("end") Instant end);

    @Query("SELECT e.dimension AS dimension, SUM(e.quantity) AS quantity, COUNT(e) AS eventCount "
            + "FROM UsageEvent e WHERE e.accountId = :accountId AND e.eventTime >= :start AND e.eventTime < :end "
            + "GROUP BY e.dimension ORDER BY e.dimension ASC")
    List<Object[]> aggregateByDimension(@Param("accountId") String accountId,
                                        @Param("start") Instant start,
                                        @Param("end") Instant end);
}
