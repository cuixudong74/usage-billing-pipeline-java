package com.github.cuixudong74.usagebillingpipelinejava.exception;

/** 出账业务异常基类。 */
public class BillingExceptions {

    private BillingExceptions() {
    }

    /** 越权操作。 */
    public static class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    /** 方案不存在或未提交。 */
    public static class PlanNotCommittedException extends RuntimeException {
        public PlanNotCommittedException(String message) {
            super(message);
        }
    }

    /** 窗口已封账，操作不允许（需走调整）。 */
    public static class WindowSealedException extends RuntimeException {
        public WindowSealedException(String message) {
            super(message);
        }
    }

    /** 非法请求。 */
    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    /** 资源不存在。 */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
