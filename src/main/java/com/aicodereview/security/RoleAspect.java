package com.aicodereview.security;

import com.aicodereview.exception.AccessDeniedException;
import com.aicodereview.exception.NotLoggedInException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

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

    @Around("execution(* com.aicodereview.controller..*.*(..))")
    public Object checkRole(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = pjp.getTarget().getClass();

        // 公开接口（登录、webhook 等）不做登录/角色校验
        // 注意：CGLIB 代理类的父类才是原始类，注解在父类上；同时检查方法和类两个层级
        boolean methodPublic = method.isAnnotationPresent(PublicAccess.class);
        boolean classPublic = targetClass.isAnnotationPresent(PublicAccess.class);
        if (!classPublic && targetClass.getSuperclass() != null) {
            classPublic = targetClass.getSuperclass().isAnnotationPresent(PublicAccess.class);
        }
        if (methodPublic || classPublic) {
            return pjp.proceed();
        }

        RequiresRole methodAnnotation = method.getAnnotation(RequiresRole.class);
        RequiresRole classAnnotation = targetClass.getAnnotation(RequiresRole.class);
        if (classAnnotation == null && targetClass.getSuperclass() != null) {
            classAnnotation = targetClass.getSuperclass().getAnnotation(RequiresRole.class);
        }

        String requiredRole = null;
        if (methodAnnotation != null) {
            requiredRole = methodAnnotation.value();
        } else if (classAnnotation != null) {
            requiredRole = classAnnotation.value();
        }

        // 没有注解的话，按方法名推断：GET/查询类 viewer 可访问，其他需要 admin
        if (requiredRole == null) {
            String lowerMethodName = method.getName().toLowerCase();
            // 只对 RestController 方法做启发式判断；Controller 页面方法默认 viewer 可访问，
            // 管理员专属页面在 PageController 内显式重定向（服务端二次校验）。
            boolean isPageController = org.springframework.stereotype.Controller.class.isAssignableFrom(targetClass)
                    || (targetClass.getSuperclass() != null
                        && org.springframework.stereotype.Controller.class.isAssignableFrom(targetClass.getSuperclass()));
            if (isPageController) {
                // 页面 GET 请求全放行；写操作走 /api REST 接口，由 RestController 启发式判断
                requiredRole = "VIEWER";
            }
            if (lowerMethodName.startsWith("get") || lowerMethodName.startsWith("list")
                    || lowerMethodName.startsWith("find") || lowerMethodName.startsWith("query")
                    || lowerMethodName.startsWith("detail") || lowerMethodName.startsWith("preview")
                    || lowerMethodName.startsWith("download") || lowerMethodName.startsWith("context")
                    || lowerMethodName.startsWith("stats") || lowerMethodName.startsWith("quality")) {
                requiredRole = "VIEWER";
            } else {
                requiredRole = "ADMIN";
            }
        }

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
}
