package com.aicodereview.config;

import com.aicodereview.util.CryptoUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * 加密配置初始化
 */
@Slf4j
@Configuration
public class EncryptionConfig {

    @Value("${app.crypto-key:a1b2c3d4e5f6g7h8}")
    private String cryptoKey;

    @PostConstruct
    public void init() {
        CryptoUtil.setSecretKey(cryptoKey);
        if (CryptoUtil.isDefaultKeyInUse()) {
            log.warn("未配置 app.crypto-key，正在使用源码内置默认加密密钥（随源码公开可知）："
                    + "数据库密码/API Key/CI Token 等敏感数据的静态加密将形同虚设，"
                    + "生产环境务必通过环境变量 APP_CRYPTO_KEY 或启动参数 --app.crypto-key 覆盖");
        }
    }
}
