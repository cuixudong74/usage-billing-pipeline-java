package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.domain.PlanAssignment;
import com.github.cuixudong74.usagebillingpipelinejava.domain.PricingPlan;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.BadRequestException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.NotFoundException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.PlanNotCommittedException;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.PricingRules;
import com.github.cuixudong74.usagebillingpipelinejava.repository.PlanAssignmentRepository;
import com.github.cuixudong74.usagebillingpipelinejava.repository.PricingPlanRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计价方案版本管理。
 *
 * 规则：
 * - 方案先以 DRAFT 创建，commit 后版本递增且不可变；
 * - 只有 COMMITTED 方案可被账户分配、可用于出账；
 * - 出账只引用 planCode+version，不做跨版本字段级混合。
 */
@Service
public class PlanService {

    private final PricingPlanRepository planRepository;
    private final PlanAssignmentRepository assignmentRepository;
    private final PermissionChecker permissionChecker;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PlanService(PricingPlanRepository planRepository,
                       PlanAssignmentRepository assignmentRepository,
                       PermissionChecker permissionChecker,
                       AuditService auditService,
                       ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.assignmentRepository = assignmentRepository;
        this.permissionChecker = permissionChecker;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** 创建方案草稿（DRAFT）。 */
    @Transactional
    public PricingPlan createDraft(String planCode, Instant effectiveFrom, Instant effectiveTo,
                                  PricingRules rules, String operator) {
        permissionChecker.requireRole("ADMIN", AuditService.ACTION_CREATE_PLAN, null, null);
        if (planCode == null || planCode.isBlank()) {
            throw new BadRequestException("planCode 不能为空");
        }
        validateRules(rules);
        int nextVersion = planRepository.findByPlanCodeOrderByVersionDesc(planCode).stream()
                .mapToInt(PricingPlan::getVersion).max().orElse(0) + 1;

        PricingPlan plan = new PricingPlan();
        plan.setPlanCode(planCode);
        plan.setVersion(nextVersion);
        plan.setStatus(PricingPlan.Status.DRAFT);
        plan.setEffectiveFrom(effectiveFrom != null ? effectiveFrom : Instant.now());
        plan.setEffectiveTo(effectiveTo);
        plan.setRules(rules);
        plan.setCreatedAt(Instant.now());
        plan.setCreatedBy(operator);
        plan = planRepository.save(plan);
        auditService.recordSuccess(AuditService.ACTION_CREATE_PLAN, "PRICING_PLAN",
                planCode + ":v" + nextVersion, null, null,
                Map.of("planCode", planCode, "version", nextVersion, "status", "DRAFT"));
        return plan;
    }

    /** 提交方案版本：DRAFT -> COMMITTED，提交后规则不可变。 */
    @Transactional
    public PricingPlan commitPlan(String planCode, Integer version) {
        permissionChecker.requireRole("ADMIN", AuditService.ACTION_COMMIT_PLAN, null, null);
        PricingPlan plan = planRepository.findByPlanCodeAndVersion(planCode, version)
                .orElseThrow(() -> new NotFoundException("方案不存在: " + planCode + ":v" + version));
        if (plan.getStatus() == PricingPlan.Status.COMMITTED) {
            return plan;
        }
        validateRules(plan.getRules());
        plan.setStatus(PricingPlan.Status.COMMITTED);
        plan = planRepository.save(plan);
        auditService.recordSuccess(AuditService.ACTION_COMMIT_PLAN, "PRICING_PLAN",
                planCode + ":v" + version, null, null,
                Map.of("planCode", planCode, "version", version, "status", "COMMITTED"));
        return plan;
    }

    /** 分配方案版本给账户 [effectiveFrom, effectiveTo)。 */
    @Transactional
    public PlanAssignment assignPlan(String accountId, String planCode, Integer planVersion,
                                     Instant effectiveFrom, Instant effectiveTo, String operator) {
        permissionChecker.requireRole("ADMIN", AuditService.ACTION_ASSIGN_PLAN, accountId, null);
        PricingPlan plan = planRepository.findByPlanCodeAndVersion(planCode, planVersion)
                .orElseThrow(() -> new NotFoundException("方案不存在: " + planCode + ":v" + planVersion));
        if (plan.getStatus() != PricingPlan.Status.COMMITTED) {
            throw new PlanNotCommittedException("方案 " + planCode + ":v" + planVersion
                    + " 尚未提交，不能分配");
        }
        PlanAssignment assignment = new PlanAssignment();
        assignment.setAccountId(accountId);
        assignment.setPlanCode(planCode);
        assignment.setPlanVersion(planVersion);
        assignment.setEffectiveFrom(effectiveFrom != null ? effectiveFrom : Instant.now());
        assignment.setEffectiveTo(effectiveTo);
        assignment.setCreatedAt(Instant.now());
        assignment.setCreatedBy(operator);
        assignment = assignmentRepository.save(assignment);
        auditService.recordSuccess(AuditService.ACTION_ASSIGN_PLAN, "PLAN_ASSIGNMENT",
                String.valueOf(assignment.getId()), accountId, null,
                Map.of("planCode", planCode, "planVersion", planVersion,
                        "effectiveFrom", assignment.getEffectiveFrom().toString()));
        return assignment;
    }

    /** 查找覆盖某时刻的已提交方案；不存在则抛异常。 */
    public PricingPlan resolvePlan(String accountId, Instant at) {
        List<PlanAssignment> covering = assignmentRepository.findCovering(accountId, at);
        if (covering.isEmpty()) {
            throw new PlanNotCommittedException("账户 " + accountId + " 在 " + at + " 没有可用的方案分配");
        }
        PlanAssignment assignment = covering.get(0);
        PricingPlan plan = planRepository
                .findByPlanCodeAndVersion(assignment.getPlanCode(), assignment.getPlanVersion())
                .orElseThrow(() -> new PlanNotCommittedException(
                        "方案 " + assignment.getPlanCode() + ":v" + assignment.getPlanVersion() + " 不存在"));
        if (plan.getStatus() != PricingPlan.Status.COMMITTED) {
            throw new PlanNotCommittedException(
                    "方案 " + plan.getPlanCode() + ":v" + plan.getVersion() + " 尚未提交");
        }
        return plan;
    }

    public String writeRulesSnapshot(PricingRules rules) {
        return writeSnapshot(rules);
    }

    public String writeSnapshot(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照序列化失败", e);
        }
    }

    private void validateRules(PricingRules rules) {
        if (rules == null) {
            throw new BadRequestException("计价规则不能为空");
        }
        if (rules.getTiers() == null || rules.getTiers().isEmpty()) {
            throw new BadRequestException("阶梯价格不能为空");
        }
        for (var tier : rules.getTiers()) {
            if (tier.getPricePerUnit() == null || tier.getPricePerUnit().signum() < 0) {
                throw new BadRequestException("阶梯单价必须为非负数");
            }
        }
    }
}
