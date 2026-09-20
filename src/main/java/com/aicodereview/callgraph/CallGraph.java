package com.aicodereview.callgraph;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用图
 *
 * 维护方法定义和调用关系
 */
@Slf4j
public class CallGraph {

    /** 所有已定义的方法（按签名索引） */
    private final Map<String, MethodInfo> definedMethods = new ConcurrentHashMap<>();

    /** 所有调用边 */
    private final List<CallEdge> callEdges = new ArrayList<>();

    /** 方法被哪些方法调用（反向索引） key=被调用者签名, value=调用者列表 */
    private final Map<String, Set<String>> callersMap = new ConcurrentHashMap<>();

    /** 方法调用了哪些方法（正向索引） key=调用者签名, value=被调用者列表 */
    private final Map<String, Set<String>> calleesMap = new ConcurrentHashMap<>();

    /**
     * 添加方法定义
     */
    public void addMethod(MethodInfo method) {
        String signature = method.getSignature();
        definedMethods.put(signature, method);
    }

    /**
     * 添加调用边
     */
    public void addCall(CallEdge edge) {
        callEdges.add(edge);

        String callerSig = edge.getCaller() != null ? edge.getCaller().getSignature() : "<unknown>";
        String calleeSig = edge.getCalleeClassName() + "." + edge.getCalleeMethodName() + edge.getCalleeDescriptor();

        // 正向索引
        calleesMap.computeIfAbsent(callerSig, k -> ConcurrentHashMap.newKeySet()).add(calleeSig);

        // 反向索引
        callersMap.computeIfAbsent(calleeSig, k -> ConcurrentHashMap.newKeySet()).add(callerSig);
    }

    /**
     * 获取所有定义的方法
     */
    public Collection<MethodInfo> getAllMethods() {
        return definedMethods.values();
    }

    /**
     * 获取方法的调用者
     */
    public Set<String> getCallers(String methodSignature) {
        return callersMap.getOrDefault(methodSignature, Collections.emptySet());
    }

    /**
     * 获取方法调用的其他方法
     */
    public Set<String> getCallees(String methodSignature) {
        return calleesMap.getOrDefault(methodSignature, Collections.emptySet());
    }

    /**
     * 检查方法是否被调用
     */
    public boolean isCalled(String methodSignature) {
        return !getCallers(methodSignature).isEmpty();
    }

    /**
     * 检查方法是否被调用（模糊匹配，忽略描述符）
     */
    public boolean isCalledByName(String className, String methodName) {
        String prefix = className + "." + methodName + "(";
        for (String key : callersMap.keySet()) {
            if (key.startsWith(prefix) && !callersMap.get(key).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取所有未被调用的方法
     * 排除：入口方法（main）、构造方法、public 方法（可能是 API）、override 方法
     */
    public List<MethodInfo> getUnusedMethods() {
        List<MethodInfo> unused = new ArrayList<>();

        for (MethodInfo method : definedMethods.values()) {
            // 排除标准入口
            if (isEntryMethod(method)) {
                continue;
            }

            // 排除构造方法（一般都会被调用，除非类完全未被使用）
            if (method.isConstructor()) {
                continue;
            }

            // 检查是否被调用（精确匹配）
            String sig = method.getSignature();
            if (isCalled(sig)) {
                continue;
            }

            // 再按方法名模糊检查（处理多态情况）
            if (isCalledByName(method.getClassName(), method.getMethodName())) {
                continue;
            }

            unused.add(method);
        }

        return unused;
    }

    /**
     * 判断是否是入口方法（不可能被项目内代码调用的方法）
     */
    private boolean isEntryMethod(MethodInfo method) {
        // main 方法
        if ("main".equals(method.getMethodName()) && method.isStatic() && method.isPublic()) {
            return true;
        }

        // public 方法可能被外部调用，保守起见也先排除
        // （如果要更严格的检测，可以关闭此排除）
        if (method.isPublic()) {
            return true;
        }

        // 抽象方法没有实现体
        if (method.isAbstract()) {
            return true;
        }

        // native 方法
        if (method.isNative()) {
            return true;
        }

        // override 方法可能通过父类/接口被调用
        if (method.isOverride()) {
            return true;
        }

        return false;
    }

    /**
     * 获取方法定义数量
     */
    public int getMethodCount() {
        return definedMethods.size();
    }

    /**
     * 获取调用边数量
     */
    public int getCallCount() {
        return callEdges.size();
    }

    /**
     * 打印统计信息
     */
    public void printStats() {
        log.info("调用图统计: 方法={}, 调用边={}, 未被调用={}",
                getMethodCount(), getCallCount(), getUnusedMethods().size());
    }
}
