package com.aicodereview.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户上下文
 */
public class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static boolean isAuthenticated() {
        return HOLDER.get() != null;
    }

    public static boolean isAdmin() {
        CurrentUser u = HOLDER.get();
        return u != null && "ADMIN".equalsIgnoreCase(u.getRole());
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentUser {
        private Long id;
        private String username;
        private String nickname;
        private String role;
        private String source; // LOCAL / REMOTE
        private boolean passwordDefault;
    }
}
