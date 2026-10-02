package com.github.cuixudong74.usagebillingpipelinejava.domain;

import com.github.cuixudong74.usagebillingpipelinejava.pricing.PricingRules;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 计价方案版本。
 *
 * 只有 status=COMMITTED 的方案版本可被出账使用；DRAFT 版本不可出账。
 * 出账时把规则快照进账单，历史账单不受后续方案更新影响。
 */
@Entity
@Table(name = "pricing_plan",
        uniqueConstraints = @UniqueConstraint(name = "uk_plan_code_version", columnNames = {"planCode", "version"}))
public class PricingPlan {

    public enum Status { DRAFT, COMMITTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 方案编码，如 STD-2026。 */
    @Column(nullable = false, length = 64)
    private String planCode;

    /** 版本号，从 1 递增。 */
    @Column(nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    /** 方案生效时间（方案维度的生效区间；账户实际采用以分配记录为准）。 */
    @Column(nullable = false)
    private Instant effectiveFrom;

    private Instant effectiveTo;

    @Embedded
    private PricingRules rules;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 64)
    private String createdBy;

    @Version
    private Long rowVersion;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlanCode() {
        return planCode;
    }

    public void setPlanCode(String planCode) {
        this.planCode = planCode;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
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

    public PricingRules getRules() {
        return rules;
    }

    public void setRules(PricingRules rules) {
        this.rules = rules;
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

    public Long getRowVersion() {
        return rowVersion;
    }

    public void setRowVersion(Long rowVersion) {
        this.rowVersion = rowVersion;
    }
}
