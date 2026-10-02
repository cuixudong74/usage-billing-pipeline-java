package com.github.cuixudong74.usagebillingpipelinejava.web;

import com.github.cuixudong74.usagebillingpipelinejava.domain.AuditLog;
import com.github.cuixudong74.usagebillingpipelinejava.repository.AuditLogRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    public AuditController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping
    public List<AuditLog> list(@RequestParam(required = false) String accountId,
                               @RequestParam(required = false) String windowId,
                               @RequestParam(required = false) String operator) {
        if (accountId != null && windowId != null) {
            return auditLogRepository.findByAccountIdAndWindowIdOrderByIdAsc(accountId, windowId);
        }
        if (operator != null) {
            return auditLogRepository.findByOperatorOrderByIdDesc(operator);
        }
        return auditLogRepository.findAll();
    }

    public record AuditEntry(Long id, Instant timestamp, String operator, String action,
                             String accountId, String windowId, String result, String details) {
    }
}
