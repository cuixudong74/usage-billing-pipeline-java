package com.github.cuixudong74.usagebillingpipelinejava.config;

import java.math.RoundingMode;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 出账配置项（application.properties 中 billing.*）。
 */
@ConfigurationProperties(prefix = "billing")
public class BillingProperties {

    /** 窗口宽限期（天）：窗口结束后该天数内可直接出账，超期自动封账。 */
    private long gracePeriodDays = 3;

    /** 窗口时区：月窗口按该时区对齐。 */
    private String windowZone = "UTC";

    /** 金额中间运算精度。 */
    private int amountScale = 4;

    /** 最终金额精度。 */
    private int finalScale = 2;

    /** 取整方式。 */
    private RoundingMode roundingMode = RoundingMode.HALF_UP;

    /** 是否启用自动封账巡检。 */
    private boolean autoSealEnabled = true;

    private String autoSealCron = "0 0 * * * *";

    public long getGracePeriodDays() {
        return gracePeriodDays;
    }

    public void setGracePeriodDays(long gracePeriodDays) {
        this.gracePeriodDays = gracePeriodDays;
    }

    public ZoneId zoneId() {
        return ZoneId.of(windowZone);
    }

    public String getWindowZone() {
        return windowZone;
    }

    public void setWindowZone(String windowZone) {
        this.windowZone = windowZone;
    }

    public int getAmountScale() {
        return amountScale;
    }

    public void setAmountScale(int amountScale) {
        this.amountScale = amountScale;
    }

    public int getFinalScale() {
        return finalScale;
    }

    public void setFinalScale(int finalScale) {
        this.finalScale = finalScale;
    }

    public RoundingMode getRoundingMode() {
        return roundingMode;
    }

    public void setRoundingMode(RoundingMode roundingMode) {
        this.roundingMode = roundingMode;
    }

    public boolean isAutoSealEnabled() {
        return autoSealEnabled;
    }

    public void setAutoSealEnabled(boolean autoSealEnabled) {
        this.autoSealEnabled = autoSealEnabled;
    }

    public String getAutoSealCron() {
        return autoSealCron;
    }

    public void setAutoSealCron(String autoSealCron) {
        this.autoSealCron = autoSealCron;
    }
}
