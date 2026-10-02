package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.context.OperatorContext;
import com.github.cuixudong74.usagebillingpipelinejava.domain.AuditLog;
import com.github.cuixudong74.usagebillingpipelinejava.repository.AuditLogRepository;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计服务：所有金额变动与权限拒绝均留痕。
 * 审计日志独立事务（REQUIRES_NEW），即使业务回滚，拒绝/失败审计也保留。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public static final String ACTION_COMMIT_BILL = "COMMIT_BILL";
    public static final String ACTION_ADJUST_BILL = "ADJUST_BILL";
    public static final String ACTION_SEAL_WINDOW = "SEAL_WINDOW";
    public static final String ACTION_CREATE_PLAN = "CREATE_PLAN";
    public static final String ACTION_COMMIT_PLAN = "COMMIT_PLAN";
    public static final String ACTION_ASSIGN_PLAN = "ASSIGN_PLAN";
    public static final String ACTION_INGEST_EVENT = "INGEST_EVENT";
    public static final String ACTION_ACCESS_DENIED = "ACCESS_DENIED";
    public static final String ACTION_RECOVERY = "RECOVERY";

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entityType, String entityId,
                       String accountId, String windowId, String result, Map<String, ?> details) {
        AuditLog entry = new AuditLog();
        entry.setTimestamp(Instant.now());
        entry.setOperator(OperatorContext.get().name());
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setAccountId(accountId);
        entry.setWindowId(windowId);
        entry.setResult(result);
        if (details != null) {
            try {
                entry.setDetails(objectMapper.writeValueAsString(details));
            } catch (JsonProcessingException e) {
                entry.setDetails(String.valueOf(details));
            }
        }
        auditLogRepository.save(entry);
        log.info("audit action={} entity={}/{} account={} window={} result={} operator={}",
                action, entityType, entityId, accountId, windowId, result, entry.getOperator());
    }

    public void recordSuccess(String action, String entityType, String entityId,
                              String accountId, String windowId, Map<String, ?> details) {
        record(action, entityType, entityId, accountId, windowId, "SUCCESS", details);
    }

    public void recordFailure(String action, String entityType, String entityId,
                              String accountId, String windowId, Map<String, ?> details) {
        record(action, entityType, entityId, accountId, windowId, "FAILURE", details);
    }
}
