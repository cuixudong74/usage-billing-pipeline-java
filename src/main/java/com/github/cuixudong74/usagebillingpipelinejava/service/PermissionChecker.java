package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.context.Operator;
import com.github.cuixudong74.usagebillingpipelinejava.context.OperatorContext;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.AccessDeniedException;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 权限校验：出账/预览需 BILLER，更正/封账/方案管理需 ADMIN。
 * 越权操作抛 AccessDeniedException 并写审计（由调用方记录）。
 */
@Component
public class PermissionChecker {

    private final AuditService auditService;

    public PermissionChecker(AuditService auditService) {
        this.auditService = auditService;
    }

    public void requireRole(String role, String action, String accountId, String windowId) {
        Operator operator = OperatorContext.get();
        if (!operator.hasRole(role)) {
            auditService.recordFailure(AuditService.ACTION_ACCESS_DENIED, "OPERATION", action,
                    accountId, windowId,
                    Map.of("requiredRole", role, "operator", operator.name()));
            throw new AccessDeniedException(
                    "操作 " + action + " 需要角色 " + role + "，当前操作人 " + operator.name() + " 无权限");
        }
    }
}
