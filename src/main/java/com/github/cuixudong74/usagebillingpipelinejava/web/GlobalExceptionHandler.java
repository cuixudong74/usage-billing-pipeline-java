package com.github.cuixudong74.usagebillingpipelinejava.web;

import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.AccessDeniedException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.BadRequestException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.NotFoundException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.PlanNotCommittedException;
import com.github.cuixudong74.usagebillingpipelinejava.exception.BillingExceptions.WindowSealedException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        return build(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler({WindowSealedException.class, PlanNotCommittedException.class})
    public ResponseEntity<Map<String, Object>> handleConflict(RuntimeException e) {
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({BadRequestException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
        String msg = e instanceof MethodArgumentNotValidException ex
                ? ex.getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + ": " + f.getDefaultMessage())
                        .reduce((a, b) -> a + "; " + b).orElse("参数校验失败")
                : e.getMessage();
        return build(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message));
    }
}
