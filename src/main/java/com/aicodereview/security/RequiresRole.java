package com.aicodereview.security;

import java.lang.annotation.*;

/**
 * 角色权限注解
 *
 * 加在 Controller 方法或类上，需要指定角色才能访问。
 * 默认所有 GET 请求 viewer 可访问，写操作需要 admin。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresRole {
    /** 需要的角色: ADMIN / VIEWER */
    String value() default "VIEWER";
}
