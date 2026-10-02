package com.github.cuixudong74.usagebillingpipelinejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 出账任务。
 *
 * 故障恢复：任务状态持久化。RUNNING 表示可能中断；重启后由 RecoveryService
 * 核对：若已存在已提交账单则置 COMMITTED，否则重置为 PENDING 重跑（幂等）。
 * 检查点 cursor 记录已处理的最大事件时间戳，作为「最近已提交位置」。
 */
@Entity
@Table(name = "billing_task",
        uniqueConstraints = @UniqueConstraint(name = "uk_task_key", columnNames = "idempotencyKey"),
        indexes = @Index(name = "idx_task_status", columnList = "status"))
public class BillingTask {

    public enum TaskType { COMMIT, ADJUST, SEAL }

    public enum Status { PENDING, RUNNING, COMMITTED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 幂等键，如 COMMIT:acc:2026-09。 */
    @Column(nullable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, length = 16)
    private String windowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false, length = 64)
    private String operator;

    private Instant startedAt;

    private Instant finishedAt;

    /** 检查点：已处理事件的最大 eventTime。 */
    private Instant cursorEventTime;

    /** 检查点：已处理事件数。 */
    private Long cursorEventCount;

    @Column(length = 512)
    private String errorMessage;

    @Version
    private Long version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
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

    public TaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(TaskType taskType) {
        this.taskType = taskType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getCursorEventTime() {
        return cursorEventTime;
    }

    public void setCursorEventTime(Instant cursorEventTime) {
        this.cursorEventTime = cursorEventTime;
    }

    public Long getCursorEventCount() {
        return cursorEventCount;
    }

    public void setCursorEventCount(Long cursorEventCount) {
        this.cursorEventCount = cursorEventCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
