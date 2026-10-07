package com.github.cuixudong74.usagebillingpipelinejava.exception;

/**
 * Raised when an authenticated actor is not permitted to perform an action. Distinct from
 * {@link ValidationException} so callers and security tooling can tell "access denied" (HTTP 403)
 * apart from a malformed request (HTTP 400). A forbidden request never mutates any state.
 */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
        super(message);
    }
}
