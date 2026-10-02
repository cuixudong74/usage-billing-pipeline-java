package com.github.cuixudong74.usagebillingpipelinejava.tests;

import com.github.cuixudong74.usagebillingpipelinejava.pricing.PricingEngine;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.PricingRules;
import com.github.cuixudong74.usagebillingpipelinejava.pricing.Tier;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 计价引擎单测：阶梯价、最低消费、封顶、折扣、取整与可复现性。
 */
class PricingEngineTest {

    private final PricingEngine engine = new PricingEngine();

    private PricingRules rules(List<Tier> tiers, String discount, String minCharge, String cap) {
        PricingRules r = new PricingRules();
        r.setCurrency("CNY");
        r.setTiers(tiers);
        if (discount != null) r.setDiscount(new BigDecimal(discount));
        if (minCharge != null) r.setMinCharge(new BigDecimal(minCharge));
        if (cap != null) r.setCap(new BigDecimal(cap));
        return r;
    }

    private List<Tier> standardTiers() {
        return List.of(
                new Tier(new BigDecimal("100"), new BigDecimal("1.00")),
                new Tier(new BigDecimal("500"), new BigDecimal("0.80")),
                new Tier(null, new BigDecimal("0.60")));
    }

    @Test
    void tiered_slabPricing_boundaries() {
        PricingRules r = rules(standardTiers(), null, null, null);
        // 边界点：100 -> 100*1 = 100
        assertEquals(0, new BigDecimal("100.00").compareTo(
                engine.calculate(r, Map.of("api", new BigDecimal("100"))).totalAmount()));
        // 500 -> 100*1 + 400*0.8 = 420
        assertEquals(0, new BigDecimal("420.00").compareTo(
                engine.calculate(r, Map.of("api", new BigDecimal("500"))).totalAmount()));
        // 600 -> 100 + 320 + 60 = 480
        assertEquals(0, new BigDecimal("480.00").compareTo(
                engine.calculate(r, Map.of("api", new BigDecimal("600"))).totalAmount()));
        // 0 -> 0
        assertEquals(0, new BigDecimal("0.00").compareTo(
                engine.calculate(r, Map.of("api", BigDecimal.ZERO)).totalAmount()));
    }

    @Test
    void tiered_multipleDimensions_summed() {
        PricingRules r = rules(standardTiers(), null, null, null);
        Map<String, BigDecimal> q = new LinkedHashMap<>();
        q.put("api", new BigDecimal("600"));
        q.put("sms", new BigDecimal("100"));
        PricingEngine.PricingResult result = engine.calculate(r, q);
        // api: 480, sms: 100 -> 580
        assertEquals(0, new BigDecimal("580.00").compareTo(result.totalAmount()));
        assertEquals(2, result.lines().size());
    }

    @Test
    void discount_appliedAfterTiered() {
        PricingRules r = rules(standardTiers(), "0.9", null, null);
        // 600 * 0.9 = 432
        assertEquals(0, new BigDecimal("432.00").compareTo(
                engine.calculate(r, Map.of("api", new BigDecimal("600"))).totalAmount()));
    }

    @Test
    void minCharge_floor() {
        PricingRules r = rules(standardTiers(), null, "50.00", null);
        // 数量 10 -> 10 元，低于最低消费 50 -> 收 50
        assertEquals(0, new BigDecimal("50.00").compareTo(
                engine.calculate(r, Map.of("api", new BigDecimal("10"))).totalAmount()));
    }

    @Test
    void cap_ceiling() {
        PricingRules r = rules(standardTiers(), null, null, "400.00");
        // 600 -> 480，封顶 400 -> 收 400
        assertEquals(0, new BigDecimal("400.00").compareTo(
                engine.calculate(r, Map.of("api", new BigDecimal("600"))).totalAmount()));
    }

    @Test
    void discountThenMinThenCap_order() {
        // 小计 100（数量100），折扣 0.5 -> 50，最低消费 80 -> 80，封顶 100 -> 80
        PricingRules r = rules(
                List.of(new Tier(null, new BigDecimal("1.00"))),
                "0.5", "80.00", "100.00");
        assertEquals(0, new BigDecimal("80.00").compareTo(
                engine.calculate(r, Map.of("api", new BigDecimal("100"))).totalAmount()));
    }

    @Test
    void rounding_halfUp_noPennyDrift() {
        // 单价 0.005，数量 1 -> 0.005，中间精度 4 位为 0.0050，最终 HALF_UP 到 2 位 = 0.01
        PricingRules r = rules(
                List.of(new Tier(null, new BigDecimal("0.005"))),
                null, null, null);
        assertEquals(0, new BigDecimal("0.01").compareTo(
                engine.calculate(r, Map.of("api", new BigDecimal("1"))).totalAmount()));

        // 可复现：任意次数计算结果一致
        PricingEngine.PricingResult r1 = engine.calculate(r, Map.of("api", new BigDecimal("12345")));
        PricingEngine.PricingResult r2 = engine.calculate(r, Map.of("api", new BigDecimal("12345")));
        assertEquals(0, r1.totalAmount().compareTo(r2.totalAmount()));
    }

    @Test
    void result_isDeterministic_regardlessOfMapOrder() {
        PricingRules r = rules(standardTiers(), "0.85", "10.00", "1000.00");
        Map<String, BigDecimal> q1 = new LinkedHashMap<>();
        q1.put("api", new BigDecimal("600"));
        q1.put("sms", new BigDecimal("300"));
        Map<String, BigDecimal> q2 = new LinkedHashMap<>();
        q2.put("sms", new BigDecimal("300"));
        q2.put("api", new BigDecimal("600"));
        assertEquals(0, engine.calculate(r, q1).totalAmount()
                .compareTo(engine.calculate(r, q2).totalAmount()));
    }
}
