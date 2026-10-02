package com.github.cuixudong74.usagebillingpipelinejava.service;

import com.github.cuixudong74.usagebillingpipelinejava.config.BillingProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

/**
 * 窗口服务：定义窗口边界与封账规则。
 *
 * 窗口语义（详见 README「计量语义」）：
 * - 窗口为自然月，按配置时区对齐（默认 UTC），windowId 形如 2026-09；
 * - 窗口区间左闭右开：[当月1日 00:00, 下月1日 00:00)；
 * - 事件按 eventTime 归属窗口，与摄入时间无关；
 * - 宽限期 gracePeriodDays（默认 3）：窗口结束后该期限内为 OPEN，
 *   超期自动/手动封账（SEALED）；封账后账单不被悄悄改写。
 */
@Service
public class WindowService {

    private static final DateTimeFormatter WINDOW_ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final BillingProperties properties;

    public WindowService(BillingProperties properties) {
        this.properties = properties;
    }

    public ZoneId zoneId() {
        return properties.zoneId();
    }

    /** eventTime 所属窗口 ID。 */
    public String windowIdFor(Instant eventTime) {
        ZonedDateTime zdt = eventTime.atZone(zoneId());
        return YearMonth.from(zdt).format(WINDOW_ID_FORMAT);
    }

    /** 窗口起点（含）。 */
    public Instant windowStart(String windowId) {
        YearMonth ym = YearMonth.parse(windowId, WINDOW_ID_FORMAT);
        return ym.atDay(1).atStartOfDay(zoneId()).toInstant();
    }

    /** 窗口终点（不含）。 */
    public Instant windowEnd(String windowId) {
        YearMonth ym = YearMonth.parse(windowId, WINDOW_ID_FORMAT);
        return ym.plusMonths(1).atDay(1).atStartOfDay(zoneId()).toInstant();
    }

    /** 窗口宽限期截止时刻：windowEnd + gracePeriodDays。 */
    public Instant sealDeadline(String windowId) {
        return windowEnd(windowId).plusMillis(
                java.util.concurrent.TimeUnit.DAYS.toMillis(properties.getGracePeriodDays()));
    }

    /** 当前是否已超过宽限期（可封账）。 */
    public boolean isPastGrace(String windowId, Instant now) {
        return now.isAfter(sealDeadline(windowId));
    }

    /** 窗口是否已结束（now >= windowEnd）。 */
    public boolean isWindowEnded(String windowId, Instant now) {
        return !now.isBefore(windowEnd(windowId));
    }

    /** 解析 windowId 为 YearMonth。 */
    public YearMonth parse(String windowId) {
        return YearMonth.parse(windowId, WINDOW_ID_FORMAT);
    }

    /** 本地日期工具（测试用）。 */
    public Instant instantAt(int year, int month, int dayOfMonth, int hour, int minute) {
        LocalDate date = LocalDate.of(year, month, dayOfMonth);
        return ZonedDateTime.of(date.atTime(hour, minute), zoneId()).toInstant();
    }
}
