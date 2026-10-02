package com.github.cuixudong74.usagebillingpipelinejava.web;

import com.github.cuixudong74.usagebillingpipelinejava.context.Operator;
import com.github.cuixudong74.usagebillingpipelinejava.context.OperatorContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从请求头解析操作人与角色：
 *   X-Operator: 操作人名
 *   X-Operator-Roles: 逗号分隔角色（BILLER,ADMIN）
 * 未提供时为 anonymous（无任何权限）。
 */
@Component
@Order(1)
public class OperatorFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Operator operator = OperatorContext.fromHeaders(
                    request.getHeader("X-Operator"), request.getHeader("X-Operator-Roles"));
            OperatorContext.set(operator);
            filterChain.doFilter(request, response);
        } finally {
            OperatorContext.clear();
        }
    }
}
