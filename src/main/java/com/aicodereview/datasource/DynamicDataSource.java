package com.aicodereview.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 动态数据源
 * 根据 ThreadLocal 中的 key 路由到对应的数据源
 */
public class DynamicDataSource extends AbstractRoutingDataSource {

    private Map<Object, Object> resolvedDataSources;

    public DynamicDataSource(DataSource defaultTargetDataSource, Map<Object, Object> targetDataSources) {
        super.setDefaultTargetDataSource(defaultTargetDataSource);
        super.setTargetDataSources(targetDataSources);
        super.afterPropertiesSet();
        this.resolvedDataSources = targetDataSources;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContextHolder.getDataSourceKey();
    }

    /**
     * 动态添加数据源
     */
    public void addDataSource(String key, DataSource dataSource) {
        this.resolvedDataSources.put(key, dataSource);
        super.setTargetDataSources(this.resolvedDataSources);
        super.afterPropertiesSet();
    }

    /**
     * 移除数据源
     */
    public void removeDataSource(String key) {
        if (this.resolvedDataSources.containsKey(key) && !"default".equals(key)) {
            this.resolvedDataSources.remove(key);
            super.setTargetDataSources(this.resolvedDataSources);
            super.afterPropertiesSet();
        }
    }
}
