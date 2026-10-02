package com.github.cuixudong74.usagebillingpipelinejava.pricing;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 阶梯价格段（区间左闭右开：(upToPrevious, upTo]）。
 * upTo 为空表示正无穷（最后一档）。
 */
@Embeddable
public class Tier {

    /** 该档上限（含），null 表示无上限。 */
    private BigDecimal upTo;

    /** 该档单价（每计量单位）。 */
    private BigDecimal pricePerUnit;

    public Tier() {
    }

    public Tier(BigDecimal upTo, BigDecimal pricePerUnit) {
        this.upTo = upTo;
        this.pricePerUnit = pricePerUnit;
    }

    public BigDecimal getUpTo() {
        return upTo;
    }

    public void setUpTo(BigDecimal upTo) {
        this.upTo = upTo;
    }

    public BigDecimal getPricePerUnit() {
        return pricePerUnit;
    }

    public void setPricePerUnit(BigDecimal pricePerUnit) {
        this.pricePerUnit = pricePerUnit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tier tier)) return false;
        return Objects.equals(upTo, tier.upTo) && Objects.equals(pricePerUnit, tier.pricePerUnit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(upTo, pricePerUnit);
    }
}
