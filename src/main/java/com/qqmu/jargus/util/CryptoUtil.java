package com.qqmu.jargus.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 加密工具类
 * 使用 AES 对称加密存储敏感配置
 */
@Slf4j
public class CryptoUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    /**
     * 源码内置默认密钥：仅在未配置 app.crypto-key 时兜底，随源码公开可知。
     * 自扫对本行报 SEC_HARDCODED_SECRET 属有意保留的真命中，提醒生产部署务必覆盖。
     */
    private static final String DEFAULT_SECRET_KEY = "a1b2c3d4e5f6g7h8";

    private static String secretKey = DEFAULT_SECRET_KEY;

    /**
     * 设置加密密钥
     */
    public static void setSecretKey(String key) {
        if (key != null && key.length() >= 16) {
            secretKey = key.substring(0, 16);
        }
    }

    /** 当前是否仍在使用内置默认密钥（EncryptionConfig 启动时据此打警告日志） */
    public static boolean isDefaultKeyInUse() {
        return DEFAULT_SECRET_KEY.equals(secretKey);
    }

    /**
     * 加密
     */
    public static String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("加密失败", e);
            throw new RuntimeException("加密失败", e);
        }
    }

    /**
     * 解密
     */
    public static String decrypt(String encryptedText) {
        if (encryptedText == null) {
            return null;
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decoded = Base64.getDecoder().decode(encryptedText);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("解密失败", e);
            return encryptedText; // 解密失败时返回原文（可能是未加密的数据）
        }
    }
}
