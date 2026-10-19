package com.eia.notifications.expert;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes for the Notifications vertical. Codes follow the SPEC
 * Appendix C scheme (`EIA-NTF-9xxx`) and are never reused for another meaning.
 */
public enum ErrorCode {
    NOT_FOUND("EIA-NTF-9001", HttpStatus.NOT_FOUND, "Resource not found", "NOT_FOUND", false),
    TEMPLATE_REQUIRED("EIA-NTF-9201", HttpStatus.BAD_REQUEST, "Approved template required", "BUSINESS_RULE", false),
    TEMPLATE_NOT_APPROVED("EIA-NTF-9202", HttpStatus.BAD_REQUEST, "Template is not approved", "BUSINESS_RULE", false),
    TEMPLATE_NOT_FOUND("EIA-NTF-9203", HttpStatus.BAD_REQUEST, "Template not found", "BUSINESS_RULE", false),
    TEMPLATE_CHANNEL_MISMATCH("EIA-NTF-9204", HttpStatus.BAD_REQUEST, "Template channel mismatch", "BUSINESS_RULE", false),
    SEND_BEFORE_SCHEDULED("EIA-NTF-9301", HttpStatus.BAD_REQUEST, "Notification is not due", "BUSINESS_RULE", false),
    INVALID_STATUS_TRANSITION("EIA-NTF-9302", HttpStatus.BAD_REQUEST, "Invalid status transition", "CONFLICT", false),
    STALE_VERSION("EIA-NTF-9303", HttpStatus.BAD_REQUEST, "Notification was modified concurrently", "CONFLICT", false),
    INVALID_CHANNEL("EIA-NTF-9401", HttpStatus.BAD_REQUEST, "Invalid notification channel", "VALIDATION", false),
    INVALID_STATUS("EIA-NTF-9402", HttpStatus.BAD_REQUEST, "Invalid notification status", "VALIDATION", false),
    INVALID_NOTIFICATION_TYPE("EIA-NTF-9403", HttpStatus.BAD_REQUEST, "Invalid notification type", "VALIDATION", false),
    VALIDATION_FAILED("EIA-NTF-9404", HttpStatus.BAD_REQUEST, "Request validation failed", "VALIDATION", false),
    INVALID_TEMPLATE_VERSION("EIA-NTF-9405", HttpStatus.BAD_REQUEST, "Invalid template version", "VALIDATION", false),
    MALFORMED_REQUEST("EIA-NTF-9406", HttpStatus.BAD_REQUEST, "Malformed request", "VALIDATION", false),
    DATA_INTEGRITY_VIOLATION("EIA-NTF-9407", HttpStatus.BAD_REQUEST, "Data integrity violation", "VALIDATION", false),
    TENANT_REQUIRED("EIA-NTF-9501", HttpStatus.BAD_REQUEST, "Tenant is required", "VALIDATION", false),
    INVALID_ARGUMENT("EIA-NTF-9502", HttpStatus.BAD_REQUEST, "Invalid argument", "VALIDATION", false),
    INTERNAL_ERROR("EIA-NTF-9999", HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "INTERNAL", false);

    private final String code;
    private final HttpStatus status;
    private final String title;
    private final String category;
    private final boolean retryable;

    ErrorCode(String code, HttpStatus status, String title, String category, boolean retryable) {
        this.code = code;
        this.status = status;
        this.title = title;
        this.category = category;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String category() {
        return category;
    }

    public boolean retryable() {
        return retryable;
    }
}
