package com.aicodereview.exception;

/**
 * CI Webhook 鉴权失败（签名/令牌缺失或不匹配），对应 HTTP 401
 */
public class CiWebhookUnauthorizedException extends RuntimeException {

    public CiWebhookUnauthorizedException(String message) {
        super(message);
    }
}
