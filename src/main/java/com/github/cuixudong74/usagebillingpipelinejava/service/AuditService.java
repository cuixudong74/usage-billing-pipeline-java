package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.authorization.Action;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.Actor;
import com.github.cuixudong74.usagebillingpipelinejava.authorization.AuthorizationService;
import com.github.cuixudong74.usagebillingpipelinejava.model.AuditEntry;
import com.github.cuixudong74.usagebillingpipelinejava.repository.AuditRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final AuditRepository auditRepository;
    private final AuthorizationService authorizationService;

    public AuditService(AuditRepository auditRepository, AuthorizationService authorizationService) {
        this.auditRepository = auditRepository;
        this.authorizationService = authorizationService;
    }

    public AuditEntry record(Actor actor, Action action, String accountId, String entityId, Map<String, String> details) {
        AuditEntry entry = new AuditEntry(
                UUID.randomUUID().toString(),
                accountId,
                actor == null ? "system" : actor.subject(),
                action.name(),
                entityId,
                Map.copyOf(details),
                Instant.now());
        return auditRepository.save(entry);
    }

    public List<AuditEntry> list(String accountId, Actor actor) {
        authorizationService.authorize(actor, Action.READ_AUDIT, accountId);
        return auditRepository.findByAccount(accountId);
    }
}
