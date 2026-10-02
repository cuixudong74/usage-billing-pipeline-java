package com.github.cuixudong74.usagebillingpipelinejava.pricing;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 计价引擎：纯函数，无状态，相同输入永远得到相同输出（可复现）。
 *
 * 阶梯语义（slab / 分段计价，左闭右开）：
 *   数量落在 (上一档上限, 本档上限] 的部分按本档单价计费；
 *   最后一档 upTo 为 null，表示超过上一档上限的全部余量。
 *   例：tiers=[(100,1),(500,0.8),(null,0.6)]，数量 600：
 *       100*1 + 400*0.8 + 100*0.6 = 480。
 */
@Component
public class PricingEngine {

    /** 单个维度的计价结果。 */
    public record LineCharge(String dimension, BigDecimal quantity, BigDecimal amount, String description) {
    }

    /** 整张账单的计价结果。 */
    public record PricingResult(String currency, List<LineCharge> lines, BigDecimal totalAmount) {
    }

    public PricingResult calculate(PricingRules rules, Map<String, BigDecimal> quantitiesByDimension) {
        // TreeMap 保证维度顺序确定，便于逐项比对
        Map<String, BigDecimal> sorted = new TreeMap<>(quantitiesByDimension);
        List<LineCharge> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO.setScale(Money.INTERMEDIATE_SCALE, Money.ROUNDING);

        for (Map.Entry<String, BigDecimal> entry : sorted.entrySet()) {
            String dimension = entry.getKey();
            BigDecimal quantity = entry.getValue();
            BigDecimal lineAmount = priceTiered(rules.getTiers(), quantity);
            String desc = describeTiers(rules.getTiers(), quantity);
            lines.add(new LineCharge(dimension, quantity, lineAmount, desc));
            subtotal = subtotal.add(lineAmount);
        }

        BigDecimal discount = rules.getDiscount() == null ? BigDecimal.ONE : rules.getDiscount();
        BigDecimal discounted = Money.intermediateRound(subtotal.multiply(discount));

        BigDecimal total = discounted;
        if (rules.getMinCharge() != null) {
            total = total.max(rules.getMinCharge());
        }
        if (rules.getCap() != null) {
            total = total.min(rules.getCap());
        }
        total = Money.finalRound(total);

        return new PricingResult(rules.getCurrency(), lines, total);
    }

    /** 分段计价：返回该数量的阶梯小计（中间精度）。 */
    static BigDecimal priceTiered(List<Tier> tiers, BigDecimal quantity) {
        if (quantity.signum() <= 0 || tiers == null || tiers.isEmpty()) {
            return BigDecimal.ZERO.setScale(Money.INTERMEDIATE_SCALE, Money.ROUNDING);
        }
        List<Tier> sorted = tiers.stream()
                .sorted(Comparator.comparing(t -> t.getUpTo() == null
                        ? new BigDecimal("999999999999999999")
                        : t.getUpTo()))
                .toList();

        BigDecimal amount = BigDecimal.ZERO.setScale(Money.INTERMEDIATE_SCALE, Money.ROUNDING);
        BigDecimal prev = BigDecimal.ZERO;
        for (Tier tier : sorted) {
            if (prev.compareTo(quantity) >= 0) {
                break;
            }
            BigDecimal upper = tier.getUpTo() == null ? quantity : tier.getUpTo().min(quantity);
            BigDecimal slabQty = upper.subtract(prev);
            BigDecimal slabAmount = Money.intermediateRound(slabQty.multiply(tier.getPricePerUnit()));
            amount = amount.add(slabAmount);
            prev = upper;
        }
        return amount;
    }

    private String describeTiers(List<Tier> tiers, BigDecimal quantity) {
        List<String> parts = new ArrayList<>();
        BigDecimal prev = BigDecimal.ZERO;
        for (Tier tier : tiers) {
            if (prev.compareTo(quantity) >= 0) {
                break;
            }
            BigDecimal upper = tier.getUpTo() == null ? quantity : tier.getUpTo().min(quantity);
            BigDecimal slabQty = upper.subtract(prev);
            if (slabQty.signum() > 0) {
                parts.add(prev.toPlainString() + "-" + (tier.getUpTo() == null ? "∞" : tier.getUpTo().toPlainString())
                        + " @ " + tier.getPricePerUnit().toPlainString() + " x " + slabQty.toPlainString());
            }
            prev = upper;
        }
        return String.join("; ", parts);
    }
}
