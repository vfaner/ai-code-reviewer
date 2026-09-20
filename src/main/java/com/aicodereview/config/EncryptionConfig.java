package com.aicodereview.config;

import com.aicodereview.util.CryptoUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 加密配置初始化
 */
@Configuration
public class EncryptionConfig {

    @Value("${app.crypto-key:a1b2c3d4e5f6g7h8}")
    private String cryptoKey;

    @PostConstruct
    public void init() {
        CryptoUtil.setSecretKey(cryptoKey);
    }
}
