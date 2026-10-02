package com.github.cuixudong74.usagebillingpipelinejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 全流程审计日志：出账、更正、封账、方案变更、权限拒绝等均留痕。
 * 金额变动可通过 accountId/windowId + details 追溯到事件与方案版本。
 */
@Entity
@Table(name = "audit_log",
        indexes = {
                @Index(name = "idx_audit_account_window", columnList = "accountId,windowId"),
                @Index(name = "idx_audit_timestamp", columnList = "timestamp")
        })
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false, length = 64)
    private String operator;

    /** 操作类型：COMMIT_BILL / ADJUST_BILL / SEAL_WINDOW / CREATE_PLAN / COMMIT_PLAN / ASSIGN_PLAN / INGEST_EVENT / ACCESS_DENIED。 */
    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 64)
    private String entityType;

    @Column(length = 128)
    private String entityId;

    @Column(length = 64)
    private String accountId;

    @Column(length = 16)
    private String windowId;

    /** 结果：SUCCESS / FAILURE。 */
    @Column(nullable = false, length = 16)
    private String result;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String details;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getWindowId() {
        return windowId;
    }

    public void setWindowId(String windowId) {
        this.windowId = windowId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
