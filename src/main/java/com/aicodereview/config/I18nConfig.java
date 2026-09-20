package com.aicodereview.config;

import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * 国际化配置。{@code ?lang=zh_CN|en} 切换语言，选择写入 Cookie 长期保留。
 *
 * <p>默认语言按浏览器时区推断（中国大陆/港澳台 → 中文，其他 → 英文），
 * Accept-Language 作为兜底，见 {@link TimezoneAwareLocaleResolver}。
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer {

    public static final String LANG_PARAM = "lang";

    @Bean
    public LocaleResolver localeResolver() {
        TimezoneAwareLocaleResolver resolver = new TimezoneAwareLocaleResolver();
        resolver.setCookieName("AICR_LANG");
        resolver.setCookieMaxAge(60 * 60 * 24 * 365);
        resolver.setCookiePath("/");
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        // 手工写坏的 Cookie 退回自动探测，而不是让页面 500
        resolver.setRejectInvalidCookies(false);
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName(LANG_PARAM);
        interceptor.setIgnoreInvalidLocale(true);
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
