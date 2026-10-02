package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.exception.ValidationException;
import com.github.cuixudong74.usagebillingpipelinejava.model.BillLine;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingPlanVersion;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingRule;
import com.github.cuixudong74.usagebillingpipelinejava.model.PricingTier;
import com.github.cuixudong74.usagebillingpipelinejava.model.UsageAggregate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PricingService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MONEY_SCALE = 2;

    public BillLine price(UsageAggregate usage, PricingPlanVersion plan) {
        PricingRule rule = findRule(plan, usage);
        BigDecimal rawList = calculateTierAmount(usage.quantity(), rule);
        BigDecimal listAmount = money(rawList);

        BigDecimal rate = rule.discountRate() == null ? BigDecimal.ZERO : rule.discountRate();
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new ValidationException("Discount rate must be between 0 and 1");
        }
        BigDecimal multiplier = BigDecimal.ONE.subtract(rate);
        BigDecimal afterDiscount = money(rawList.multiply(multiplier));
        BigDecimal discountAmount = listAmount.subtract(afterDiscount);

        BigDecimal minimum = rule.minimumAmount() == null ? BigDecimal.ZERO : money(rule.minimumAmount());
        BigDecimal minimumAdjustment = maximum(BigDecimal.ZERO, minimum.subtract(afterDiscount));
        BigDecimal beforeCap = afterDiscount.add(minimumAdjustment);

        BigDecimal capAdjustment = BigDecimal.ZERO.setScale(MONEY_SCALE);
        BigDecimal netAmount = beforeCap;
        if (rule.maximumAmount() != null) {
            BigDecimal cap = money(rule.maximumAmount());
            if (cap.compareTo(beforeCap) < 0) {
                capAdjustment = cap.subtract(beforeCap);
                netAmount = cap;
            }
        }

        return new BillLine(
                usage.key(),
                usage.quantity(),
                usage.eventIds(),
                listAmount,
                discountAmount,
                minimumAdjustment,
                capAdjustment,
                netAmount);
    }

    private PricingRule findRule(PricingPlanVersion plan, UsageAggregate usage) {
        return plan.rules().stream()
                .filter(rule -> rule.metric().equals(usage.key().metric()))
                .filter(rule -> rule.dimensions().equals(usage.key().dimensions()))
                .findFirst()
                .orElseThrow(() -> new ValidationException(
                        "Plan " + plan.planId() + "#" + plan.version() + " does not contain rule for " + usage.key()));
    }

    private BigDecimal calculateTierAmount(BigDecimal quantity, PricingRule rule) {
        if (rule.tiers() == null || rule.tiers().isEmpty()) {
            return BigDecimal.ZERO;
        }
        var tiers = rule.tiers().stream()
                .sorted(Comparator.comparing(PricingTier::upperBoundExclusive,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal lower = BigDecimal.ZERO;
        for (PricingTier tier : tiers) {
            BigDecimal upper = tier.upperBoundExclusive();
            BigDecimal remaining = upper == null ? quantity : quantity.min(upper);
            BigDecimal chargeable = remaining.subtract(lower).max(BigDecimal.ZERO);
            amount = amount.add(chargeable.multiply(tier.unitPrice()));
            if (upper != null && upper.compareTo(quantity) >= 0) {
                break;
            }
            if (upper != null) {
                lower = upper;
            }
        }
        return amount;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal maximum(BigDecimal first, BigDecimal second) {
        return first.compareTo(second) >= 0 ? first : second;
    }
}
