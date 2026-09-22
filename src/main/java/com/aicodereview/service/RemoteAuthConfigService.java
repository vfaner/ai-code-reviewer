package com.aicodereview.service;

import com.aicodereview.entity.RemoteAuthConfig;
import com.aicodereview.mapper.RemoteAuthConfigMapper;
import com.aicodereview.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 远端登录配置服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteAuthConfigService {

    private final RemoteAuthConfigMapper configMapper;

    public List<RemoteAuthConfig> listAll() {
        List<RemoteAuthConfig> list = configMapper.selectList(
                new QueryWrapper<RemoteAuthConfig>().orderByDesc("created_at")
        );
        // 脱敏密钥
        list.forEach(c -> {
            if (c.getClientSecret() != null) {
                c.setClientSecret(maskSecret(c.getClientSecret()));
            }
        });
        return list;
    }

    public List<RemoteAuthConfig> listEnabled() {
        return configMapper.selectList(
                new QueryWrapper<RemoteAuthConfig>()
                        .eq("is_enabled", true)
                        .orderByAsc("id")
        );
    }

    public RemoteAuthConfig getById(Long id) {
        RemoteAuthConfig config = configMapper.selectById(id);
        if (config != null && config.getClientSecret() != null) {
            config.setClientSecret(maskSecret(config.getClientSecret()));
        }
        return config;
    }

    public RemoteAuthConfig create(RemoteAuthConfig config) {
        // 加密密钥
        if (config.getClientSecret() != null && !config.getClientSecret().isEmpty()
                && !config.getClientSecret().contains("****")) {
            config.setClientSecret(CryptoUtil.encrypt(config.getClientSecret()));
        }
        config.setIsEnabled(config.getIsEnabled() != null ? config.getIsEnabled() : false);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        configMapper.insert(config);
        return config;
    }

    public boolean update(Long id, RemoteAuthConfig config) {
        config.setId(id);
        // 如果密钥是掩码格式（****），说明没修改，不更新
        if (config.getClientSecret() != null && config.getClientSecret().contains("****")) {
            config.setClientSecret(null);
        } else if (config.getClientSecret() != null && !config.getClientSecret().isEmpty()) {
            config.setClientSecret(CryptoUtil.encrypt(config.getClientSecret()));
        }
        config.setUpdatedAt(LocalDateTime.now());
        return configMapper.updateById(config) > 0;
    }

    public boolean delete(Long id) {
        return configMapper.deleteById(id) > 0;
    }

    public boolean toggle(Long id) {
        RemoteAuthConfig config = configMapper.selectById(id);
        if (config == null) return false;
        config.setIsEnabled(!Boolean.TRUE.equals(config.getIsEnabled()));
        config.setUpdatedAt(LocalDateTime.now());
        return configMapper.updateById(config) > 0;
    }

    /**
     * 获取原始配置（密钥已解密，仅供内部调用，不可返回给前端）
     */
    public RemoteAuthConfig getRawById(Long id) {
        RemoteAuthConfig config = configMapper.selectById(id);
        if (config != null && config.getClientSecret() != null) {
            try {
                config.setClientSecret(CryptoUtil.decrypt(config.getClientSecret()));
            } catch (Exception e) {
                log.warn("密钥解密失败: {}", e.getMessage());
            }
        }
        return config;
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 调用远端登录接口。
     * 请求体：{ "username": 用户名, "password": 密码, client_id/client_secret（如已配置）}
     * （usernameField 等字段映射仅用于解析响应，不影响请求体）
     * 若登录响应包含 access_token 且配置了 userInfoUrl，则再拉取用户信息并合并。
     *
     * @return 远端返回的用户信息 Map（解析后的 JSON）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> invokeLogin(RemoteAuthConfig config, String username, String password) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("username", username);
            body.put("password", password);
            if (config.getClientId() != null && !config.getClientId().isBlank()) {
                body.put("client_id", config.getClientId());
            }
            if (config.getClientSecret() != null && !config.getClientSecret().isBlank()) {
                body.put("client_secret", config.getClientSecret());
            }

            HttpResponse<String> resp = postJson(config.getLoginUrl(), body);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("远端登录接口返回状态码: {}", resp.statusCode());
                throw new RuntimeException("远端登录失败（HTTP " + resp.statusCode() + "）");
            }
            Map<String, Object> result = OBJECT_MAPPER.readValue(resp.body(), Map.class);

            // 两步式：先拿 token，再拉用户信息
            if (config.getUserInfoUrl() != null && !config.getUserInfoUrl().isBlank()) {
                Object token = extractField(result, "access_token");
                if (token == null) token = extractField(result, "token");
                if (token != null) {
                    HttpRequest infoReq = HttpRequest.newBuilder(URI.create(config.getUserInfoUrl()))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + token)
                            .GET()
                            .build();
                    HttpResponse<String> infoResp = HTTP_CLIENT.send(infoReq, HttpResponse.BodyHandlers.ofString());
                    if (infoResp.statusCode() >= 200 && infoResp.statusCode() < 300) {
                        Map<String, Object> userInfo = OBJECT_MAPPER.readValue(infoResp.body(), Map.class);
                        Map<String, Object> merged = new HashMap<>(result);
                        merged.putAll(userInfo);
                        return merged;
                    }
                }
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用远端登录接口失败: {}", e.getMessage(), e);
            throw new RuntimeException("远端登录调用失败: " + e.getMessage());
        }
    }

    /**
     * 连通性测试：用测试账号向登录接口发一次请求，判断地址/网络/格式是否可用。
     * 401/403 也算"接口可达"（测试凭据本来就是假的，被拒是正常的）。
     *
     * @return success + message
     */
    public Map<String, Object> testConnection(RemoteAuthConfig form) {
        Map<String, Object> result = new HashMap<>();
        String url = form.getLoginUrl();
        if (url == null || url.isBlank()) {
            result.put("success", false);
            result.put("message", "登录接口 URL 不能为空");
            return result;
        }
        try {
            URI.create(url.trim()).toURL();
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "URL 格式不正确：" + url);
            return result;
        }

        // 编辑态密钥留空/掩码时使用库里已保存的密钥
        String secret = form.getClientSecret();
        if (form.getId() != null
                && (secret == null || secret.isBlank() || secret.contains("****"))) {
            RemoteAuthConfig saved = getRawById(form.getId());
            if (saved != null && saved.getClientSecret() != null) {
                secret = saved.getClientSecret();
            }
        }
        if (secret != null && secret.contains("****")) secret = null;

        Map<String, Object> body = new HashMap<>();
        body.put("username", "__connectivity_test__");
        body.put("password", "__connectivity_test__");
        if (form.getClientId() != null && !form.getClientId().isBlank()) {
            body.put("client_id", form.getClientId());
        }
        if (secret != null && !secret.isBlank()) {
            body.put("client_secret", secret);
        }

        HttpResponse<String> resp;
        try {
            resp = postJson(url.trim(), body);
        } catch (java.net.http.HttpTimeoutException e) {
            result.put("success", false);
            result.put("message", "请求超时（15 秒），请检查网络或地址是否可达");
            return result;
        } catch (java.net.UnknownHostException e) {
            result.put("success", false);
            result.put("message", "域名无法解析：" + e.getMessage());
            return result;
        } catch (java.net.ConnectException e) {
            result.put("success", false);
            result.put("message", "连接被拒绝（端口未开放或服务未启动）"
                    + (e.getMessage() != null ? "：" + e.getMessage() : ""));
            return result;
        } catch (javax.net.ssl.SSLException e) {
            result.put("success", false);
            result.put("message", "HTTPS 握手失败，请确认地址协议（http/https）与证书是否有效：" + e.getMessage());
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "请求失败：" + e.getMessage());
            return result;
        }

        int sc = resp.statusCode();
        if (sc >= 200 && sc < 300) {
            result.put("success", true);
            result.put("message", "接口连通正常（HTTP " + sc + "，未校验账号密码），建议在登录页用真实账号验证一次");
        } else if (sc == 401 || sc == 403) {
            result.put("success", true);
            result.put("message", "接口可达（HTTP " + sc + "）：测试账号被拒绝属正常现象，地址与网络没有问题");
        } else if (sc == 404) {
            result.put("success", false);
            result.put("message", "接口返回 404，请检查登录接口 URL 是否正确");
        } else if (sc >= 300 && sc < 400) {
            result.put("success", false);
            result.put("message", "接口返回重定向（HTTP " + sc
                    + "），地址可能填成了页面地址，请确认填写的是登录 API 而非网页链接");
        } else if (sc >= 400 && sc < 500) {
            result.put("success", true);
            result.put("message", "接口可达（HTTP " + sc + "），建议用真实账号验证字段映射是否正确");
        } else {
            result.put("success", false);
            result.put("message", "对端服务异常（HTTP " + sc + "）");
        }
        return result;
    }

    private HttpResponse<String> postJson(String url, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 按点分路径从 JSON Map 中提取字段，例如 "data.user.name"。
     * 数字段按数组下标处理（data.0.name）。
     */
    @SuppressWarnings("unchecked")
    public static Object extractField(Object root, String path) {
        if (path == null || path.isBlank() || root == null) return null;
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (current == null) return null;
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list) {
                try {
                    current = list.get(Integer.parseInt(segment));
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    private String maskSecret(String encrypted) {
        // 返回 前4 + **** + 后4
        try {
            String plain = CryptoUtil.decrypt(encrypted);
            if (plain.length() <= 8) return "********";
            return plain.substring(0, 4) + "****" + plain.substring(plain.length() - 4);
        } catch (Exception e) {
            return "********";
        }
    }
}
