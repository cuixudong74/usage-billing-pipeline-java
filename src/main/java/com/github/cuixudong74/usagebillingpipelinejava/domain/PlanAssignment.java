package com.github.cuixudong74.usagebillingpipelinejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 账户的方案分配记录：某账户在 [effectiveFrom, effectiveTo) 区间采用某方案版本。
 *
 * 出账时按窗口起点匹配覆盖该时刻的分配记录；一个窗口只采用一个方案版本，
 * 不做跨版本字段级混合（见 README「方案版本化」）。
 */
@Entity
@Table(name = "plan_assignment",
        indexes = @Index(name = "idx_assignment_account", columnList = "accountId,effectiveFrom"))
public class PlanAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, length = 64)
    private String planCode;

    @Column(nullable = false)
    private Integer planVersion;

    @Column(nullable = false)
    private Instant effectiveFrom;

    /** null 表示长期有效。 */
    private Instant effectiveTo;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 64)
    private String createdBy;

    @Version
    private Long version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
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

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
