package com.github.cuixudong74.usagebillingpipelinejava.authorization;

import com.github.cuixudong74.usagebillingpipelinejava.exception.ForbiddenException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.ValidationException;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {
    private static final String ADMIN = "ADMIN";
    private static final String EVENT_WRITER = "EVENT_WRITER";
    private static final String BILLER = "BILLER";
    private static final String AUDITOR = "AUDITOR";
    private static final String ACCOUNT_OWNER = "ACCOUNT_OWNER";

    public void authorize(Actor actor, Action action, String accountId) {
        if (actor == null || actor.subject() == null || actor.subject().isBlank()) {
            throw new ValidationException("Authenticated actor is required");
        }
        Set<String> roles = actor.roles() == null ? Set.of() : actor.roles();
        if (roles.contains(ADMIN)) {
            return;
        }
        boolean allowed = switch (action) {
            case INGEST_EVENT -> roles.contains(EVENT_WRITER);
            case COMMIT_PLAN -> false;
            case PREVIEW_BILL, COMMIT_BILL, ADJUST_BILL ->
                    roles.contains(BILLER) || (roles.contains(ACCOUNT_OWNER) && actor.subject().equals(accountId));
            case READ_BILL ->
                    roles.contains(BILLER) || roles.contains(AUDITOR) || (roles.contains(ACCOUNT_OWNER) && actor.subject().equals(accountId));
            case READ_AUDIT -> roles.contains(AUDITOR);
        };
        if (!allowed) {
            throw new ForbiddenException("Actor " + actor.subject() + " is not allowed to " + action);
        }
    }

    public boolean canPerformAny(Actor actor, EnumSet<Action> actions, String accountId) {
        return actions.stream().anyMatch(action -> {
            try {
                authorize(actor, action, accountId);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });
    }
}
