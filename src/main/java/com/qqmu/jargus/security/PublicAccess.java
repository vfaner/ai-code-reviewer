package com.qqmu.jargus.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记公开访问的接口（不需要登录态）。
 *
 * 用于登录、登出、CI Webhook 等自带鉴权或无需鉴权的接口，
 * RoleAspect 遇到该注解（方法或类上）时跳过角色校验。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicAccess {
}
