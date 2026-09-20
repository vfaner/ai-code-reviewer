package com.aicodereview.env;

import lombok.Builder;
import lombok.Data;

/**
 * 依赖信息
 */
@Data
@Builder
public class DependencyInfo {

    /** groupId */
    private String groupId;

    /** artifactId */
    private String artifactId;

    /** 版本 */
    private String version;

    /** 作用域: compile / test / provided / runtime */
    private String scope;

    /** 类型 */
    private String type;

    /**
     * 获取完整标识
     */
    public String getFullName() {
        return groupId + ":" + artifactId + ":" + (version != null ? version : "unknown");
    }
}
