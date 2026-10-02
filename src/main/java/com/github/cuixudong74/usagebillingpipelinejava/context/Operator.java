package com.github.cuixudong74.usagebillingpipelinejava.context;

import java.util.Set;

/**
 * 操作人：名称 + 角色集合。角色：BILLER（出账/预览）、ADMIN（更正/封账/方案管理）。
 */
public record Operator(String name, Set<String> roles) {

    public static final String ROLE_BILLER = "BILLER";
    public static final String ROLE_ADMIN = "ADMIN";

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public static Operator system() {
        return new Operator("system", Set.of(ROLE_BILLER, ROLE_ADMIN));
    }

    public static Operator anonymous() {
        return new Operator("anonymous", Set.of());
    }
}
