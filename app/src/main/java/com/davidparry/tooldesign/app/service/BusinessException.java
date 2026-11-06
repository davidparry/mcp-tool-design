package com.davidparry.tooldesign.app.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A request the business rules refuse. {@code code} is the stable machine-readable reason, such as
 * {@code quote_expired}, and {@code details} carries the facts behind it. The API turns it into a ProblemDetail.
 */
public class BusinessException extends RuntimeException {

    private final String code;

    private final HttpStatus status;

    private final Map<String, Object> details;

    public BusinessException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public BusinessException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = new LinkedHashMap<>(details);
    }

    public static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    public static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed", message);
    }

    public String code() {
        return this.code;
    }

    public HttpStatus status() {
        return this.status;
    }

    public Map<String, Object> details() {
        return this.details;
    }
}
