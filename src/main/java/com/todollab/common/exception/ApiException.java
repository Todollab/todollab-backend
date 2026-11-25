package com.todollab.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Domain/API exception mapped by {@link GlobalExceptionHandler} into RFC 7807 ProblemDetail responses.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;
    private final String detail;

    public ApiException(HttpStatus status, ErrorCode code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }
}
