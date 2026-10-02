package com.github.cuixudong74.usagebillingpipelinejava.pricing;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.OrderBy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 计价规则集合。作为方案版本的不可变组成部分，不允许跨版本混合。
 *
 * 计价公式（应用顺序，详见 README）：
 *   1. 各维度按阶梯（ slab，左闭右开）计算阶梯小计；
 *   2. 小计之和 × discount（折扣，1 表示无折扣）；
 *   3. 与 minCharge（最低消费）取大；
 *   4. 与 cap（封顶）取小；
 *   5. 最终金额保留 2 位小数（HALF_UP）。
 */
@Embeddable
public class PricingRules {

    /** 币种。 */
    @Column(name = "currency", length = 8)
    private String currency = "CNY";

    /** 阶梯段，按 upTo 升序；最后一段 upTo 可为 null（正无穷）。 */
    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(name = "pricing_rule_tiers")
    @OrderBy("upTo ASC")
    private List<Tier> tiers = new ArrayList<>();

    /** 折扣率：1 = 无折扣，0.9 = 九折。null 视为 1。 */
    @Column(name = "discount")
    private BigDecimal discount;

    /** 最低消费（含税前保底金额），null 表示无。 */
    @Column(name = "min_charge")
    private BigDecimal minCharge;

    /** 封顶金额，null 表示无。 */
    @Column(name = "cap")
    private BigDecimal cap;

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public List<Tier> getTiers() {
        return tiers;
    }

    public void setTiers(List<Tier> tiers) {
        this.tiers = tiers;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public void setDiscount(BigDecimal discount) {
        this.discount = discount;
    }

    public BigDecimal getMinCharge() {
        return minCharge;
    }

    public void setMinCharge(BigDecimal minCharge) {
        this.minCharge = minCharge;
    }

    public BigDecimal getCap() {
        return cap;
    }

    public void setCap(BigDecimal cap) {
        this.cap = cap;
    }
}
