package com.eia.notifications.expert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Emits SPEC 6.11 style `application/problem+json` responses with a stable
 * `code`, `category`, `retryable` flag and optional field violations.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_BASE = "https://errors.eia-open-insurance.example/notifications/";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        return problem(ex.errorCode().status(), ex.errorCode().code(), ex.errorCode().title(),
                ex.getMessage(), ex.errorCode().category(), ex.errorCode().retryable(), ex.errors(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), "INVALID_FIELD",
                        error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return problem(code.status(), code.code(), code.title(),
                "One or more request fields are invalid.", code.category(), code.retryable(), errors, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParameter(MissingServletRequestParameterException ex,
                                                                HttpServletRequest request) {
        ErrorCode code = ErrorCode.TENANT_REQUIRED;
        return problem(code.status(), code.code(), code.title(),
                "Required parameter '" + ex.getParameterName() + "' is missing.", code.category(),
                code.retryable(), List.of(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        ErrorCode code = ErrorCode.INVALID_ARGUMENT;
        return problem(code.status(), code.code(), code.title(),
                "Parameter '" + ex.getName() + "' is not a valid " + expectedType(ex) + ".", code.category(),
                code.retryable(), List.of(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        ErrorCode code = ErrorCode.MALFORMED_REQUEST;
        return problem(code.status(), code.code(), code.title(),
                "The request body is missing or malformed.", code.category(), code.retryable(),
                List.of(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        log.warn("Notification data integrity violation", ex);
        ErrorCode code = ErrorCode.DATA_INTEGRITY_VIOLATION;
        return problem(code.status(), code.code(), code.title(),
                "The notification violates a persisted invariant.", code.category(), code.retryable(),
                List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled notifications expert error", ex);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return problem(code.status(), code.code(), code.title(),
                "An unexpected error occurred.", code.category(), code.retryable(), List.of(), request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title, String detail,
                                                  String category, boolean retryable,
                                                  List<FieldViolation> errors, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT)));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("category", category);
        problem.setProperty("retryable", retryable);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private String expectedType(MethodArgumentTypeMismatchException ex) {
        Class<?> required = ex.getRequiredType();
        return required == null ? "value" : required.getSimpleName();
    }
}
