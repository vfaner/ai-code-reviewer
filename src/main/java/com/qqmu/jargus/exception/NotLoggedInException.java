package com.qqmu.jargus.exception;

/**
 * 未登录异常（HTTP 401）
 */
public class NotLoggedInException extends RuntimeException {
    public NotLoggedInException(String message) {
        super(message);
    }
}
