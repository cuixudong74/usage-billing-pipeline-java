package com.github.cuixudong74.usagebillingpipelinejava.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 账单（已提交版本）。
 *
 * 只读语义：对外暴露的已提交账单永远是某个完整版本；PREVIEW 不入库。
 * 幂等：同一 (accountId, windowId, billVersion) 唯一；重复出账返回同一账单。
 * 可追溯：记录所用方案版本、规则快照、来源事件 ID 集合。
 */
@Entity
@Table(name = "bill",
        uniqueConstraints = @UniqueConstraint(name = "uk_bill_account_window_version",
                columnNames = {"accountId", "windowId", "billVersion"}))
public class Bill {

    public enum Status { COMMITTED, ADJUSTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 账单业务号。 */
    @Column(nullable = false, length = 64, unique = true)
    private String billNo;

    @Column(nullable = false, length = 64)
    private String accountId;

    /** 窗口标识，如 2026-09（UTC 月窗口）。 */
    @Column(nullable = false, length = 16)
    private String windowId;

    @Column(nullable = false)
    private Instant windowStart;

    @Column(nullable = false)
    private Instant windowEnd;

    @Column(nullable = false, length = 64)
    private String planCode;

    @Column(nullable = false)
    private Integer planVersion;

    @Enumerated
    @Column(nullable = false, length = 16)
    private Status status;

    /** 账单版本：1=首次出账，2+=调整。 */
    @Column(nullable = false)
    private Integer billVersion;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(nullable = false)
    private Long eventCount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalQuantity;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "bill_id", nullable = false)
    private List<BillLineItem> lineItems = new ArrayList<>();

    /** 本账单覆盖的来源事件 ID（可追溯到事件）。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "bill_event_ids", joinColumns = @JoinColumn(name = "bill_id"))
    @Column(name = "event_id", length = 128)
    private List<String> eventIds = new ArrayList<>();

    /** 计价规则快照（JSON）：方案更新不影响历史账单。 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String rulesSnapshot;

    @Column(nullable = false)
    private Instant committedAt;

    @Column(nullable = false, length = 64)
    private String operator;

    /** 调整链：上一版本账单 ID。 */
    private Long previousBillId;

    @Column(length = 512)
    private String adjustmentReason;

    @Version
    private Long version;

    /** 是否为预览（不入库）。 */
    @jakarta.persistence.Transient
    private boolean preview;

    /** 窗口是否已封账（预览时提示）。 */
    @jakarta.persistence.Transient
    private boolean sealed;

    public boolean isPreview() {
        return preview;
    }

    public void setPreview(boolean preview) {
        this.preview = preview;
    }

    public boolean isSealed() {
        return sealed;
    }

    public void setSealed(boolean sealed) {
        this.sealed = sealed;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBillNo() {
        return billNo;
    }

    public void setBillNo(String billNo) {
        this.billNo = billNo;
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

    public Instant getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Instant windowStart) {
        this.windowStart = windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(Instant windowEnd) {
        this.windowEnd = windowEnd;
    }

    public String getPlanCode() {
        return planCode;
    }

    public void setPlanCode(String planCode) {
        this.planCode = planCode;
    }

    public Integer getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(Integer planVersion) {
        this.planVersion = planVersion;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Integer getBillVersion() {
        return billVersion;
    }

    public void setBillVersion(Integer billVersion) {
        this.billVersion = billVersion;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Long getEventCount() {
        return eventCount;
    }

    public void setEventCount(Long eventCount) {
        this.eventCount = eventCount;
    }

    public BigDecimal getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(BigDecimal totalQuantity) {
        this.totalQuantity = totalQuantity;
    }

    public List<BillLineItem> getLineItems() {
        return lineItems;
    }

    public void setLineItems(List<BillLineItem> lineItems) {
        this.lineItems = lineItems;
    }

    public List<String> getEventIds() {
        return eventIds;
    }

    public void setEventIds(List<String> eventIds) {
        this.eventIds = eventIds;
    }

    public String getRulesSnapshot() {
        return rulesSnapshot;
    }

    public void setRulesSnapshot(String rulesSnapshot) {
        this.rulesSnapshot = rulesSnapshot;
    }

    public Instant getCommittedAt() {
        return committedAt;
    }

    public void setCommittedAt(Instant committedAt) {
        this.committedAt = committedAt;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Long getPreviousBillId() {
        return previousBillId;
    }

    public void setPreviousBillId(Long previousBillId) {
        this.previousBillId = previousBillId;
    }

    public String getAdjustmentReason() {
        return adjustmentReason;
    }

    public void setAdjustmentReason(String adjustmentReason) {
        this.adjustmentReason = adjustmentReason;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
