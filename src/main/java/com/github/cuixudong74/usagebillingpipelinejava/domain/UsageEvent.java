package com.github.cuixudong74.usagebillingpipelinejava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 用量事件。
 *
 * 幂等：{@code eventId} 全局唯一，重复上报同 eventId 不会重复入账。
 * 归属：按事件发生时间 {@code eventTime} 归属到窗口，与上报/摄入时间无关；
 *       乱序、迟到、超期补报都不改变其归属窗口。
 */
@Entity
@Table(name = "usage_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_account", columnNames = {"accountId", "eventId"}),
        indexes = {
                @Index(name = "idx_event_account_time", columnList = "accountId,eventTime"),
                @Index(name = "idx_event_account_dim_time", columnList = "accountId,dimension,eventTime")
        })
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务侧幂等键，全局唯一。 */
    @Column(nullable = false, length = 128)
    private String eventId;

    @Column(nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, length = 64)
    private String dimension;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    /** 用量实际发生时间：窗口归属依据。 */
    @Column(nullable = false)
    private Instant eventTime;

    /** 摄入时间（仅用于审计，不参与窗口归属）。 */
    @Column(nullable = false)
    private Instant ingestedAt;

    @Column(length = 64)
    private String source;

    @Version
    private Long version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public void setEventTime(Instant eventTime) {
        this.eventTime = eventTime;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    public void setIngestedAt(Instant ingestedAt) {
        this.ingestedAt = ingestedAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
