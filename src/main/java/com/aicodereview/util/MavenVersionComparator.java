package com.aicodereview.util;

/**
 * Maven 版本号比较器（轻量实现）
 *
 * 按 . - _ 分段比较：数字段按数值比较；数字段 > 限定符段（1.0 > 1.0-beta）；
 * 限定符之间按字典序（忽略大小写）；缺失段：对数字补 0（1.0 == 1.0.0），
 * 对限定符视为正式版更高（1.0 > 1.0-rc1）。
 *
 * 覆盖常见形如 1.2.3 / 2.13.4.2 / 5.1.49 / 20231013 / 30.0-jre 的版本号，
 * 满足漏洞库「低于修复版本」判定即可，不追求完整 Maven Comparable 语义。
 */
public final class MavenVersionComparator {

    private MavenVersionComparator() {
    }

    /**
     * 比较两个版本号
     *
     * @return 负数表示 v1 &lt; v2，0 表示相等，正数表示 v1 &gt; v2
     */
    public static int compare(String v1, String v2) {
        String[] a = normalize(v1).split("[.\\-_]");
        String[] b = normalize(v2).split("[.\\-_]");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            String sa = i < a.length ? a[i] : "";
            String sb = i < b.length ? b[i] : "";
            int cmp = compareSegment(sa, sb);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int compareSegment(String sa, String sb) {
        if (sa.equals(sb)) {
            return 0;
        }
        Long na = tryParseLong(sa);
        Long nb = tryParseLong(sb);
        if (na != null && nb != null) {
            return Long.compare(na, nb);
        }
        if (na != null) {
            // 数字 vs 限定符/缺失：数字更大（1.0.1 > 1.0.beta，1.0.0 == 1.0）
            return sb.isEmpty() ? 0 : 1;
        }
        if (nb != null) {
            return sa.isEmpty() ? 0 : -1;
        }
        // 双方都是限定符或空：正式版（空）> 限定符；其余字典序
        if (sa.isEmpty()) {
            return 1;
        }
        if (sb.isEmpty()) {
            return -1;
        }
        return sa.compareToIgnoreCase(sb);
    }

    private static Long tryParseLong(String s) {
        if (s.isEmpty()) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return null;
            }
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalize(String v) {
        if (v == null) {
            return "";
        }
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        // 去掉构建元数据（+xxx）与首尾空白
        int plus = s.indexOf('+');
        if (plus > 0) {
            s = s.substring(0, plus);
        }
        return s;
    }
}
