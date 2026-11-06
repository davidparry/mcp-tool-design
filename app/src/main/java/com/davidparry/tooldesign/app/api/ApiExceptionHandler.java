package com.davidparry.tooldesign.app.api;

import java.util.List;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.davidparry.tooldesign.app.service.BusinessException;

/**
 * Every refusal is a ProblemDetail with a stable {@code code}, such as {@code not_eligible} or {@code quote_expired},
 * and the facts behind it as extra properties. Ordered ahead of Boot's ProblemDetail handler, which would answer
 * validation failures with a 400 and no code.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ProblemDetail> business(BusinessException e) {
        return problem(e.status(), e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage()))).toList();
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed", "The request body is invalid",
                Map.of("errors", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed",
                "The request body isn't valid JSON for this operation, or a field has the wrong type or value", Map.of());
    }

    @ExceptionHandler({ MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class })
    ResponseEntity<ProblemDetail> invalidParameter(Exception e) {
        String detail = switch (e) {
            case MissingServletRequestParameterException missing -> "Missing the required parameter " + missing.getParameterName();
            case MethodArgumentTypeMismatchException mismatch -> "Parameter " + mismatch.getName() + " can't be '"
                    + mismatch.getValue() + "'";
            default -> "A parameter is invalid";
        };
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed", detail, Map.of());
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
            Map<String, Object> details) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        details.forEach(problem::setProperty);
        return ResponseEntity.status(status).body(problem);
    }
}
