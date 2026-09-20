package com.aicodereview.exception;

/**
 * 权限不足异常（HTTP 403）
 */
public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String message) {
        super(message);
    }
}
