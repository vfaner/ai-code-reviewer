package com.qqmu.jargus.callgraph;

import lombok.Builder;
import lombok.Data;

/**
 * 调用边 - 表示一次方法调用
 */
@Data
@Builder
public class CallEdge {

    /** 调用者（方法信息） */
    private MethodInfo caller;

    /** 被调用者的类名 */
    private String calleeClassName;

    /** 被调用者的方法名 */
    private String calleeMethodName;

    /** 被调用者的描述符 */
    private String calleeDescriptor;

    /** 调用位置（行号） */
    private int lineNumber;

    /** 调用类型 */
    private CallType callType;

    /**
     * 调用类型
     */
    public enum CallType {
        /** 静态调用 invokestatic */
        STATIC,
        /** 特殊调用 invokespecial（构造方法、私有方法、super） */
        SPECIAL,
        /** 虚方法调用 invokevirtual */
        VIRTUAL,
        /** 接口调用 invokeinterface */
        INTERFACE,
        /** 动态调用 invokedynamic（Lambda 等） */
        DYNAMIC
    }
}
