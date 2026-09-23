package com.qqmu.jargus.service;

import com.qqmu.jargus.entity.AiProviderConfig;
import com.qqmu.jargus.mapper.AiProviderConfigMapper;
import com.qqmu.jargus.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI 厂商配置服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderConfigService {

    private final AiProviderConfigMapper providerConfigMapper;

    /**
     * 获取所有厂商配置
     */
    public List<AiProviderConfig> listAll() {
        List<AiProviderConfig> list = providerConfigMapper.selectList(
                new QueryWrapper<AiProviderConfig>().orderByAsc("sort_order", "id")
        );
        // 不返回敏感信息明文
        list.forEach(cfg -> {
            cfg.setApiKey(null);
            cfg.setSecretKey(null);
        });
        return list;
    }

    /**
     * 根据ID获取配置
     */
    public AiProviderConfig getById(Long id) {
        AiProviderConfig cfg = providerConfigMapper.selectById(id);
        if (cfg != null) {
            cfg.setApiKey(null);
            cfg.setSecretKey(null);
        }
        return cfg;
    }

    /**
     * 获取激活的厂商配置
     */
    public AiProviderConfig getActive() {
        AiProviderConfig active = providerConfigMapper.selectOne(
                new QueryWrapper<AiProviderConfig>().eq("is_active", true)
        );
        if (active != null) {
            // 返回解密后的配置，供内部调用
            active.setApiKey(CryptoUtil.decrypt(active.getApiKey()));
            if (active.getSecretKey() != null) {
                active.setSecretKey(CryptoUtil.decrypt(active.getSecretKey()));
            }
        }
        return active;
    }

    /**
     * 新增厂商配置
     */
    @Transactional
    public AiProviderConfig create(AiProviderConfig config) {
        // 加密敏感信息
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            config.setApiKey(CryptoUtil.encrypt(config.getApiKey()));
        }
        if (config.getSecretKey() != null && !config.getSecretKey().isEmpty()) {
            config.setSecretKey(CryptoUtil.encrypt(config.getSecretKey()));
        }

        // 设置默认值：鉴权方式由协议决定（前端精简后不再暴露鉴权字段）
        applyProtocolDefaults(config);
        if (config.getAuthType() == null) config.setAuthType("BEARER");
        if (config.getAuthHeaderName() == null) config.setAuthHeaderName("Authorization");
        if (config.getRequestMethod() == null) config.setRequestMethod("POST");
        if (config.getTimeoutSeconds() == null) config.setTimeoutSeconds(60);
        if (config.getIsCustom() == null) config.setIsCustom(false);
        if (config.getIsActive() == null) config.setIsActive(false);
        if (config.getIsEnabled() == null) config.setIsEnabled(true);
        if (config.getSortOrder() == null) config.setSortOrder(0);

        providerConfigMapper.insert(config);
        log.info("新增 AI 厂商配置: id={}, name={}", config.getId(), config.getProviderName());

        config.setApiKey(null);
        config.setSecretKey(null);
        return config;
    }

    /**
     * 更新厂商配置
     */
    @Transactional
    public AiProviderConfig update(Long id, AiProviderConfig config) {
        AiProviderConfig existing = providerConfigMapper.selectById(id);
        if (existing == null) {
            throw new RuntimeException("厂商配置不存在");
        }

        config.setId(id);

        // 鉴权方式跟随协议（避免把已废弃的表单字段写回库里）
        applyProtocolDefaults(config);

        // 如果传了 API Key，加密；否则保留原有
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            config.setApiKey(CryptoUtil.encrypt(config.getApiKey()));
        } else {
            config.setApiKey(existing.getApiKey());
        }

        // 如果传了 Secret Key，加密；否则保留原有
        if (config.getSecretKey() != null && !config.getSecretKey().isEmpty()) {
            config.setSecretKey(CryptoUtil.encrypt(config.getSecretKey()));
        } else {
            config.setSecretKey(existing.getSecretKey());
        }

        providerConfigMapper.updateById(config);
        log.info("更新 AI 厂商配置: id={}", id);

        AiProviderConfig updated = providerConfigMapper.selectById(id);
        updated.setApiKey(null);
        updated.setSecretKey(null);
        return updated;
    }

    /**
     * 表单测试用：补齐协议默认值；编辑态 API Key 留空时沿用库中已保存的密文，
     * 客户端识别 {cipher} 前缀会自行解密。不落库。
     */
    public AiProviderConfig prepareForFormTest(AiProviderConfig config) {
        if (config == null) return null;
        applyProtocolDefaults(config);
        if (config.getAuthType() == null) config.setAuthType("BEARER");
        if (config.getAuthHeaderName() == null) config.setAuthHeaderName("Authorization");
        if (config.getRequestMethod() == null) config.setRequestMethod("POST");
        if (config.getTimeoutSeconds() == null) config.setTimeoutSeconds(60);

        boolean keyBlank = config.getApiKey() == null || config.getApiKey().isEmpty();
        boolean secretBlank = config.getSecretKey() == null || config.getSecretKey().isEmpty();
        if (config.getId() != null && (keyBlank || secretBlank)) {
            AiProviderConfig saved = providerConfigMapper.selectById(config.getId());
            if (saved != null) {
                // 库里是 AES 密文，测试前必须显式解密（客户端不会自动解密存储值）
                if (keyBlank) config.setApiKey(CryptoUtil.decrypt(saved.getApiKey()));
                if (secretBlank) config.setSecretKey(CryptoUtil.decrypt(saved.getSecretKey()));
            }
        }
        return config;
    }

    /**
     * 协议决定鉴权方式：Anthropic 固定 x-api-key，其余走 Bearer Token
     */
    public void applyProtocolDefaults(AiProviderConfig config) {
        if (config == null || config.getProtocolType() == null) return;
        if ("ANTHROPIC".equalsIgnoreCase(config.getProtocolType())) {
            config.setAuthType("API_KEY_HEADER");
            config.setAuthHeaderName("x-api-key");
        } else if ("OPENAI_COMPATIBLE".equalsIgnoreCase(config.getProtocolType())) {
            config.setAuthType("BEARER");
            config.setAuthHeaderName("Authorization");
        }
    }

    /**
     * 删除厂商配置
     */
    @Transactional
    public boolean delete(Long id) {
        AiProviderConfig config = providerConfigMapper.selectById(id);
        if (config == null) {
            return false;
        }
        if (Boolean.TRUE.equals(config.getIsActive())) {
            throw new RuntimeException("不能删除当前激活的厂商配置");
        }
        providerConfigMapper.deleteById(id);
        log.info("删除 AI 厂商配置: id={}", id);
        return true;
    }

    /**
     * 激活指定厂商（同时禁用其他所有厂商）
     */
    @Transactional
    public boolean activate(Long id) {
        AiProviderConfig config = providerConfigMapper.selectById(id);
        if (config == null) {
            throw new RuntimeException("厂商配置不存在");
        }
        if (!Boolean.TRUE.equals(config.getIsEnabled())) {
            throw new RuntimeException("该厂商未启用");
        }

        // 禁用所有
        providerConfigMapper.update(null,
                new UpdateWrapper<AiProviderConfig>().set("is_active", false)
        );

        // 启用目标
        config.setIsActive(true);
        providerConfigMapper.updateById(config);

        log.info("激活 AI 厂商: id={}, name={}", id, config.getProviderName());
        return true;
    }

    /**
     * 测试连接（使用解密后的完整配置）
     */
    public AiProviderConfig getConfigForTest(Long id) {
        AiProviderConfig config = providerConfigMapper.selectById(id);
        if (config != null) {
            // 解密敏感信息
            config.setApiKey(CryptoUtil.decrypt(config.getApiKey()));
            if (config.getSecretKey() != null) {
                config.setSecretKey(CryptoUtil.decrypt(config.getSecretKey()));
            }
        }
        return config;
    }
}
