package com.github.cuixudong74.usagebillingpipelinejava.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额工具：全系统金额运算只允许使用 BigDecimal，禁止 double/float。
 *
 * 取整约定（文档见 README「计价规则」）：
 * 1. 阶梯内单价 × 数量等中间运算保留 {@code intermediateScale} 位（默认 4）；
 * 2. 折扣、最低消费、封顶比较后，最终金额统一保留 {@code finalScale} 位（默认 2）；
 * 3. 取整方式统一为 {@link RoundingMode#HALF_UP}（四舍五入）。
 */
public final class Money {

    public static final int FINAL_SCALE = 2;
    public static final int INTERMEDIATE_SCALE = 4;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Money() {
    }

    /** 最终金额取整：2 位小数，HALF_UP。 */
    public static BigDecimal finalRound(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(FINAL_SCALE, ROUNDING);
    }

    /** 中间运算取整：4 位小数，HALF_UP。 */
    public static BigDecimal intermediateRound(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(INTERMEDIATE_SCALE, ROUNDING);
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(FINAL_SCALE, ROUNDING);
    }
}
