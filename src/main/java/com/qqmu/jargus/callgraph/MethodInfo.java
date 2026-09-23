package com.qqmu.jargus.callgraph;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 方法信息
 */
@Data
@Builder
@EqualsAndHashCode(of = {"className", "methodName", "descriptor"})
public class MethodInfo {

    /** 类名（全限定名） */
    private String className;

    /** 方法名 */
    private String methodName;

    /** 方法描述符（参数+返回值），如 (Ljava/lang/String;)I */
    private String descriptor;

    /** 源文件路径 */
    private String filePath;

    /** 起始行号 */
    private int lineStart;

    /** 结束行号 */
    private int lineEnd;

    /** 是否是 public 方法 */
    private boolean isPublic;

    /** 是否是 static 方法 */
    private boolean isStatic;

    /** 是否是构造方法 */
    private boolean isConstructor;

    /** 是否是抽象方法 */
    private boolean isAbstract;

    /** 是否是 native 方法 */
    private boolean isNative;

    /** 是否是 override 方法 */
    private boolean isOverride;

    /**
     * 获取方法的唯一签名
     * 格式：className.methodName(descriptor)
     */
    public String getSignature() {
        return className + "." + methodName + descriptor;
    }
}
