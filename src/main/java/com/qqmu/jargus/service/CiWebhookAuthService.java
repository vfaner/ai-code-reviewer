package com.qqmu.jargus.service;

import com.qqmu.jargus.entity.CiTriggerConfig;
import com.qqmu.jargus.exception.CiWebhookUnauthorizedException;
import com.qqmu.jargus.mapper.CiTriggerConfigMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * CI Webhook 鉴权
 *
 * 各平台约定：
 * - GitHub：X-Hub-Signature-256 = "sha256=" + HMAC_SHA256(secret, rawBody)
 * - GitLab：X-Gitlab-Token 直接等于 Webhook 配置的 Secret token
 * - Gitee：X-Gitee-Token 直接等于 Webhook 配置的密码
 * - 通用 / ZIP 上传 / CI 脚本：Authorization: Bearer（触发器密钥或系统访问令牌），
 *   也兼容 X-Ci-Token 头
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CiWebhookAuthService {

    private final CiTriggerConfigMapper triggerConfigMapper;
    private final CiTokenService ciTokenService;

    /**
     * 校验 Webhook 请求，失败抛 {@link CiWebhookUnauthorizedException}。
     *
     * @param triggerId 触发器 ID
     * @param request   HTTP 请求
     * @param rawBody   原始请求体（JSON 事件用于 HMAC；ZIP 上传传 null）
     */
    public void verify(Long triggerId, HttpServletRequest request, byte[] rawBody) {
        CiTriggerConfig config = triggerConfigMapper.selectById(triggerId);
        if (config == null) {
            throw new CiWebhookUnauthorizedException("触发配置不存在");
        }
        if (!Boolean.TRUE.equals(config.getIsEnabled())) {
            throw new CiWebhookUnauthorizedException("触发配置未启用");
        }

        String secret = CiTriggerService.decryptStored(config.getSecretToken());
        if (secret == null || secret.isEmpty()) {
            throw new CiWebhookUnauthorizedException(
                    "触发器未配置签名密钥，请在 CI/CD 页面重置密钥后再配置 Webhook");
        }

        // 1. GitHub HMAC 签名
        String hubSig = request.getHeader("X-Hub-Signature-256");
        if (hubSig != null) {
            verifyGitHubSignature(secret, rawBody, hubSig);
            return;
        }

        // 2. GitLab Secret token
        String gitlabToken = request.getHeader("X-Gitlab-Token");
        if (gitlabToken != null) {
            if (!constantTimeEq(gitlabToken, secret)) {
                throw new CiWebhookUnauthorizedException("X-Gitlab-Token 与触发器密钥不匹配");
            }
            return;
        }

        // 3. Gitee Webhook 密码
        String giteeToken = request.getHeader("X-Gitee-Token");
        if (giteeToken != null) {
            if (!constantTimeEq(giteeToken, secret)) {
                throw new CiWebhookUnauthorizedException("X-Gitee-Token 与触发器密钥不匹配");
            }
            return;
        }

        // 4. Bearer / X-Ci-Token：触发器密钥 或 系统访问令牌
        String bearer = extractBearer(request);
        if (bearer != null && !bearer.isEmpty()) {
            if (constantTimeEq(bearer, secret) || ciTokenService.validateToken(bearer)) {
                return;
            }
            throw new CiWebhookUnauthorizedException("访问令牌无效");
        }

        log.warn("Webhook 缺少鉴权信息: triggerId={}, 远端={}", triggerId, request.getRemoteAddr());
        throw new CiWebhookUnauthorizedException(
                "缺少 Webhook 鉴权信息：请配置平台签名密钥，或在请求头携带 Authorization: Bearer <触发器密钥/访问令牌>");
    }

    private void verifyGitHubSignature(String secret, byte[] rawBody, String signature) {
        if (rawBody == null) {
            throw new CiWebhookUnauthorizedException("无法校验 GitHub 签名：请求体为空");
        }
        String expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(rawBody);
            StringBuilder sb = new StringBuilder("sha256=");
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            expected = sb.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
        if (!constantTimeEq(expected, signature.trim())) {
            throw new CiWebhookUnauthorizedException(
                    "GitHub 签名校验失败（X-Hub-Signature-256 与触发器密钥不匹配）");
        }
    }

    private String extractBearer(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        String ciToken = request.getHeader("X-Ci-Token");
        return ciToken != null ? ciToken.trim() : null;
    }

    /** 常量时间比较，避免计时侧信道 */
    private static boolean constantTimeEq(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
