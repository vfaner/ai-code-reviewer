package com.qqmu.jargus.security;

import com.qqmu.jargus.exception.AccessDeniedException;
import com.qqmu.jargus.exception.NotLoggedInException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;

import java.lang.reflect.Method;

/**
 * 角色权限切面
 *
 * 基于 @RequiresRole 注解进行权限校验。
 * 也可以通过 HTTP 方法自动判断：GET = VIEWER，其他 = ADMIN。
 */
@Slf4j
@Aspect
@Component
public class RoleAspect {

    @Around("execution(* com.qqmu.jargus.controller..*.*(..))")
    public Object checkRole(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = pjp.getTarget().getClass();

        // 公开接口（登录、webhook 等）不做登录/角色校验
        if (isPublicAccess(method, targetClass)) {
            return pjp.proceed();
        }

        String requiredRole = resolveRequiredRole(method, targetClass);

        // 检查权限
        UserContext.CurrentUser user = UserContext.get();
        if (user == null) {
            throw new NotLoggedInException("未登录或登录已过期");
        }

        if ("ADMIN".equalsIgnoreCase(requiredRole) && !"ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new AccessDeniedException("权限不足：需要管理员权限");
        }

        return pjp.proceed();
    }

    /**
     * 公开接口判定：方法或类（含 CGLIB 代理的父类）带 PublicAccess 即公开。
     * 注意：CGLIB 代理类的父类才是原始类，注解在父类上；同时检查方法和类两个层级
     */
    private boolean isPublicAccess(@NonNull Method method, @NonNull Class<?> targetClass) {
        if (method.isAnnotationPresent(PublicAccess.class)) {
            return true;
        }
        if (targetClass.isAnnotationPresent(PublicAccess.class)) {
            return true;
        }
        return targetClass.getSuperclass() != null
                && targetClass.getSuperclass().isAnnotationPresent(PublicAccess.class);
    }

    /** 所需角色：方法注解 &gt; 类注解（含父类）&gt; 方法名启发式 */
    private String resolveRequiredRole(@NonNull Method method, @NonNull Class<?> targetClass) {
        RequiresRole methodAnnotation = method.getAnnotation(RequiresRole.class);
        RequiresRole classAnnotation = targetClass.getAnnotation(RequiresRole.class);
        if (classAnnotation == null && targetClass.getSuperclass() != null) {
            classAnnotation = targetClass.getSuperclass().getAnnotation(RequiresRole.class);
        }
        if (methodAnnotation != null) {
            return methodAnnotation.value();
        }
        if (classAnnotation != null) {
            return classAnnotation.value();
        }
        // 没有注解的话，按方法名推断：GET/查询类 viewer 可访问，其他需要 admin
        return roleByNameHeuristic(method, targetClass);
    }

    /** 方法名启发式定角色（保持原有覆盖顺序：方法名启发式最终生效） */
    private String roleByNameHeuristic(@NonNull Method method, @NonNull Class<?> targetClass) {
        String requiredRole = null;
        // 只对 RestController 方法做启发式判断；Controller 页面方法默认 viewer 可访问，
        // 管理员专属页面在 PageController 内显式重定向（服务端二次校验）。
        if (isPageController(targetClass)) {
            // 页面 GET 请求全放行；写操作走 /api REST 接口，由 RestController 启发式判断
            requiredRole = "VIEWER";
        }
        if (isReadLikeName(method.getName().toLowerCase())) {
            requiredRole = "VIEWER";
        } else {
            requiredRole = "ADMIN";
        }
        return requiredRole;
    }

    /** Controller 页面类（含 CGLIB 父类）判定 */
    private boolean isPageController(@NonNull Class<?> targetClass) {
        return Controller.class.isAssignableFrom(targetClass)
                || (targetClass.getSuperclass() != null
                    && Controller.class.isAssignableFrom(targetClass.getSuperclass()));
    }

    /** 读操作类方法名前缀（GET/查询类 viewer 可访问） */
    private boolean isReadLikeName(String lowerMethodName) {
        return lowerMethodName.startsWith("get") || lowerMethodName.startsWith("list")
                || lowerMethodName.startsWith("find") || lowerMethodName.startsWith("query")
                || lowerMethodName.startsWith("detail") || lowerMethodName.startsWith("preview")
                || lowerMethodName.startsWith("download") || lowerMethodName.startsWith("context")
                || lowerMethodName.startsWith("stats") || lowerMethodName.startsWith("quality");
    }
}
