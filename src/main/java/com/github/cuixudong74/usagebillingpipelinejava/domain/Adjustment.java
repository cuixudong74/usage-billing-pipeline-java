package com.github.cuixudong74.usagebillingpipelinejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 调整记录：每次出账更正都保留调整前后差异。
 */
@Entity
@Table(name = "adjustment",
        indexes = @Index(name = "idx_adjustment_account_window", columnList = "accountId,windowId"))
public class Adjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long billId;

    @Column(nullable = false)
    private Long previousBillId;

    @Column(nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, length = 16)
    private String windowId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal beforeAmount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal afterAmount;

    /** 差异 = afterAmount - beforeAmount。 */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal diffAmount;

    @Column(nullable = false, length = 512)
    private String reason;

    @Column(nullable = false, length = 64)
    private String operator;

    @Column(nullable = false)
    private Instant createdAt;

    /** 调整前账单快照（JSON），便于审计追溯。 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String beforeSnapshot;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBillId() {
        return billId;
    }

    public void setBillId(Long billId) {
        this.billId = billId;
    }

    public Long getPreviousBillId() {
        return previousBillId;
    }

    public void setPreviousBillId(Long previousBillId) {
        this.previousBillId = previousBillId;
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

    public BigDecimal getBeforeAmount() {
        return beforeAmount;
    }

    public void setBeforeAmount(BigDecimal beforeAmount) {
        this.beforeAmount = beforeAmount;
    }

    public BigDecimal getAfterAmount() {
        return afterAmount;
    }

    public void setAfterAmount(BigDecimal afterAmount) {
        this.afterAmount = afterAmount;
    }

    public BigDecimal getDiffAmount() {
        return diffAmount;
    }

    public void setDiffAmount(BigDecimal diffAmount) {
        this.diffAmount = diffAmount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getBeforeSnapshot() {
        return beforeSnapshot;
    }

    public void setBeforeSnapshot(String beforeSnapshot) {
        this.beforeSnapshot = beforeSnapshot;
    }
}
