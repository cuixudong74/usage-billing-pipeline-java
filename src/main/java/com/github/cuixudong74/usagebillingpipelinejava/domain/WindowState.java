package com.github.cuixudong74.usagebillingpipelinejava.domain;

import jakarta.persistence.Column;
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
 * 窗口状态：OPEN（出账中）/ SEALED（已封账）。
 * 封账后账单不得被悄悄改写，更正必须走显式调整。
 */
@Entity
@Table(name = "window_state",
        uniqueConstraints = @UniqueConstraint(name = "uk_window_account_window", columnNames = {"accountId", "windowId"}))
public class WindowState {

    public enum Status { OPEN, SEALED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String accountId;

    @Column(nullable = false, length = 16)
    private String windowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    private Instant sealedAt;

    @Column(length = 64)
    private String sealedBy;

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

    public String getWindowId() {
        return windowId;
    }

    public void setWindowId(String windowId) {
        this.windowId = windowId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getSealedAt() {
        return sealedAt;
    }

    public void setSealedAt(Instant sealedAt) {
        this.sealedAt = sealedAt;
    }

    public String getSealedBy() {
        return sealedBy;
    }

    public void setSealedBy(String sealedBy) {
        this.sealedBy = sealedBy;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
