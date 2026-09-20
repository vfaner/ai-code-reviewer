package com.aicodereview.datasource;

/**
 * 数据源上下文持有者
 * 使用 ThreadLocal 保存当前线程使用的数据源 key
 */
public class DataSourceContextHolder {

    private static final ThreadLocal<String> CONTEXT_HOLDER = new ThreadLocal<>();

    /**
     * 默认数据源 key
     */
    public static final String DEFAULT_DATASOURCE = "default";

    /**
     * 设置当前线程的数据源 key
     */
    public static void setDataSourceKey(String key) {
        CONTEXT_HOLDER.set(key);
    }

    /**
     * 获取当前线程的数据源 key
     */
    public static String getDataSourceKey() {
        String key = CONTEXT_HOLDER.get();
        return key != null ? key : DEFAULT_DATASOURCE;
    }

    /**
     * 清除当前线程的数据源 key
     */
    public static void clearDataSourceKey() {
        CONTEXT_HOLDER.remove();
    }
}
