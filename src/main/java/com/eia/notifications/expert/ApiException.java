package com.eia.notifications.expert;

import java.util.List;

/**
 * Business or validation failure that maps to an RFC 9457 problem response.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<FieldViolation> errors;

    public ApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, List.of());
    }

    public ApiException(ErrorCode errorCode, String detail, List<FieldViolation> errors) {
        super(detail);
        this.errorCode = errorCode;
        this.errors = List.copyOf(errors);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldViolation> errors() {
        return errors;
    }
}
