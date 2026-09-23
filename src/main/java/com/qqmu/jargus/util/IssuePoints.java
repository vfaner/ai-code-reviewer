package com.qqmu.jargus.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 问题行位置工具：同一文件同一规则的多个命中点（[起始行, 结束行] 闭区间）
 * 需要在存储前合并去重、在列表/报告里紧凑展示（53-56、59-60）。
 */
public final class IssuePoints {

    private IssuePoints() {
    }

    /**
     * 合并重叠或首尾相邻的闭区间，按起始行升序返回；非法区间被剔除。
     */
    public static List<int[]> merge(List<int[]> raw) {
        List<int[]> sorted = new ArrayList<>();
        if (raw != null) {
            for (int[] p : raw) {
                if (p != null && p.length >= 2 && p[0] > 0 && p[1] >= p[0]) {
                    sorted.add(new int[]{p[0], p[1]});
                }
            }
        }
        if (sorted.isEmpty()) {
            return sorted;
        }
        sorted.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0])
                : Integer.compare(b[1], a[1]));
        List<int[]> merged = new ArrayList<>();
        int[] cur = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            int[] p = sorted.get(i);
            if (p[0] <= cur[1] + 1) {
                cur[1] = Math.max(cur[1], p[1]);
            } else {
                merged.add(cur);
                cur = p;
            }
        }
        merged.add(cur);
        return merged;
    }

    /**
     * 紧凑行号标签：单行给 "53"，连续区间给 "53-56"，多区间用顿号连接。
     */
    public static String format(List<int[]> points) {
        List<int[]> m = merge(points);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < m.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            int[] p = m.get(i);
            sb.append(p[0]);
            if (p[1] > p[0]) {
                sb.append('-').append(p[1]);
            }
        }
        return sb.toString();
    }

    /**
     * 把 JSON 反序列化得到的混合结构（List&lt;List&lt;Number&gt;&gt;，理论上也兼容 int[]）
     * 统一转成 int[] 区间列表。
     */
    @SuppressWarnings("unchecked")
    public static List<int[]> toRanges(Object raw) {
        List<int[]> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return result;
        }
        for (Object o : list) {
            if (o instanceof int[] a && a.length >= 2) {
                result.add(new int[]{a[0], a[1]});
            } else if (o instanceof List<?> pair && pair.size() >= 2
                    && pair.get(0) instanceof Number n1 && pair.get(1) instanceof Number n2) {
                result.add(new int[]{n1.intValue(), n2.intValue()});
            }
        }
        return result;
    }

    /** int[] 区间列表转 JSON 友好的 List&lt;List&lt;Integer&gt;&gt;（实体持久化用） */
    public static List<List<Integer>> toLists(List<int[]> ranges) {
        List<List<Integer>> out = new ArrayList<>();
        for (int[] r : ranges) {
            out.add(List.of(r[0], r[1]));
        }
        return out;
    }

    /**
     * 带旧数据兜底的行号标签：无 linePoints 时退化为 lineStart-lineEnd。
     */
    public static String label(List<int[]> points, Integer lineStart, Integer lineEnd) {
        if (points != null && !points.isEmpty()) {
            String s = format(points);
            if (!s.isEmpty()) {
                return s;
            }
        }
        int s = lineStart != null ? lineStart : 1;
        int e = lineEnd != null ? lineEnd : s;
        return e > s ? s + "-" + e : String.valueOf(s);
    }
}
