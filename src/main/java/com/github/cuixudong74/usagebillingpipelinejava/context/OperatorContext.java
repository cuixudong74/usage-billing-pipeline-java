package com.github.cuixudong74.usagebillingpipelinejava.context;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 ThreadLocal 的操作人上下文。
 * Web 层由 {@code OperatorFilter} 从请求头写入；异步/测试场景可手动设置。
 */
public final class OperatorContext {

    private static final ThreadLocal<Operator> CURRENT = new ThreadLocal<>();

    private OperatorContext() {
    }

    public static void set(Operator operator) {
        CURRENT.set(operator);
    }

    public static Operator get() {
        Operator op = CURRENT.get();
        return op == null ? Operator.anonymous() : op;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 供 Filter 解析请求头。 */
    public static Operator fromHeaders(String nameHeader, String rolesHeader) {
        String name = (nameHeader == null || nameHeader.isBlank()) ? "anonymous" : nameHeader.trim();
        Set<String> roles = rolesHeader == null || rolesHeader.isBlank()
                ? Set.of()
                : Arrays.stream(rolesHeader.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        return new Operator(name, roles);
    }
}
