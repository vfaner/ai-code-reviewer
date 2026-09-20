package com.aicodereview.config;

import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import lombok.extern.slf4j.Slf4j;

/**
 * 请求语言解析，优先级从高到低：
 * <ol>
 *   <li>用户显式选择（语言 Cookie，由 {@link LocaleChangeInterceptor} 在 ?lang= 时写入）</li>
 *   <li>浏览器 IANA 时区（首访时由内联小脚本上报）—— 故意优先于 Accept-Language：
 *       海外华人常用英文界面浏览器，但人在 Asia/Shanghai，时区更能反映真实语言偏好</li>
 *   <li>Accept-Language 头（时区还没上报时兜底）</li>
 *   <li>简体中文（最终默认）</li>
 * </ol>
 */
@Slf4j
public class TimezoneAwareLocaleResolver extends CookieLocaleResolver {

    /** 客户端上报检测时区所用的 Cookie */
    public static final String TZ_COOKIE = "AICR_TZ";

    /** 视为中文环境的时区（含港澳台） */
    private static final Set<String> ZH_TIMEZONES = Set.of(
            "Asia/Shanghai", "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin",
            "Asia/Urumqi", "Asia/Kashgar", "Asia/Hong_Kong", "Asia/Macau",
            "Asia/Macao", "Asia/Taipei", "Asia/Beijing", "PRC", "ROC", "Hongkong");

    /**
     * 覆写这里而不是 resolveLocale：父类把 Cookie 解析结果缓存在请求属性里，
     * ?lang= 切换时 setLocale 同时更新该属性，保证一次点击当次响应就生效。
     */
    @Override
    protected Locale determineDefaultLocale(HttpServletRequest request) {
        String timezone = readTimezone(request);
        if (timezone != null) {
            return ZH_TIMEZONES.contains(timezone) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        }
        Locale fromHeader = request.getLocale();
        if (fromHeader != null && !fromHeader.getLanguage().isBlank()) {
            return fromHeader.getLanguage().toLowerCase().startsWith("zh")
                    ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        }
        Locale configured = getDefaultLocale();
        return configured != null ? configured : Locale.SIMPLIFIED_CHINESE;
    }

    /** 只认中文/英文，乱码 Cookie 值按未设置处理，避免所有消息静默退化成 key */
    @Override
    protected Locale parseLocaleValue(String localeValue) {
        Locale parsed = super.parseLocaleValue(localeValue);
        if (parsed == null) {
            return null;
        }
        String language = parsed.getLanguage().toLowerCase();
        if (language.startsWith("zh")) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        if (language.equals("en")) {
            return Locale.ENGLISH;
        }
        log.debug("Ignoring unsupported locale cookie value '{}'", localeValue);
        return null;
    }

    private String readTimezone(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (TZ_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }
}
