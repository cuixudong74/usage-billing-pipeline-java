package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.domain.UsageEvent;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.BadRequestException;
import com.github.cuixudong74.usagebillingpipelinejava.repository.UsageEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用量事件摄入。
 *
 * 幂等：eventId 全局唯一，重复上报（同 eventId）直接返回已存在事件，不重复入账。
 * 事件只追加、不修改、不删除；窗口归属完全由 eventTime 决定。
 */
@Service
public class EventIngestionService {

    private final UsageEventRepository eventRepository;
    private final AuditService auditService;

    public EventIngestionService(UsageEventRepository eventRepository, AuditService auditService) {
        this.eventRepository = eventRepository;
        this.auditService = auditService;
    }

    /** 摄入结果：created=false 表示 eventId 已存在（重复上报）。 */
    public record IngestResult(boolean created, UsageEvent event) {
    }

    @Transactional
    public IngestResult ingest(String eventId, String accountId, String dimension,
                               BigDecimal quantity, Instant eventTime, String source) {
        validate(eventId, accountId, dimension, quantity, eventTime);

        Optional<UsageEvent> existing = eventRepository.findByAccountIdAndEventId(accountId, eventId);
        if (existing.isPresent()) {
            return new IngestResult(false, existing.get());
        }

        UsageEvent event = new UsageEvent();
        event.setEventId(eventId);
        event.setAccountId(accountId);
        event.setDimension(dimension);
        event.setQuantity(quantity);
        event.setEventTime(eventTime);
        event.setIngestedAt(Instant.now());
        event.setSource(source);
        try {
            event = eventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            // 并发下唯一约束兜底：另一线程已插入同 eventId
            return new IngestResult(false, eventRepository.findByAccountIdAndEventId(accountId, eventId).orElseThrow());
        }
        auditService.recordSuccess(AuditService.ACTION_INGEST_EVENT, "USAGE_EVENT", eventId,
                accountId, null, Map.of("dimension", dimension, "quantity", quantity.toPlainString()));
        return new IngestResult(true, event);
    }

    private void validate(String eventId, String accountId, String dimension,
                          BigDecimal quantity, Instant eventTime) {
        if (eventId == null || eventId.isBlank()) {
            throw new BadRequestException("eventId 不能为空");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new BadRequestException("accountId 不能为空");
        }
        if (dimension == null || dimension.isBlank()) {
            throw new BadRequestException("dimension 不能为空");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new BadRequestException("quantity 必须为正数");
        }
        if (eventTime == null) {
            throw new BadRequestException("eventTime 不能为空");
        }
    }
}
