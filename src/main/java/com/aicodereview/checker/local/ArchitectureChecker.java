package com.aicodereview.checker.local;

import com.aicodereview.checker.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 架构约束检查器（内置分层规则，零配置）
 *
 * 分层模型：controller → service → dao(mapper/repository) → entity，
 * dto/vo/util 等中立层不参与约束。层归属由包名段与类名后缀共同判定。
 *
 * 规则：
 * - ARCH_LAYER_SKIP：controller 越过 service 直接依赖 dao 层
 * - ARCH_LAYER_REVERSE：service/dao 反向依赖 controller
 * - ARCH_ENTITY_LEAK：entity 依赖 controller/service/dao（领域对象泄漏基础设施）
 *
 * 仅检查项目内部 import（与本文件包共享 ≥2 段根包），第三方类库不受分层约束。
 */
@Component
public class ArchitectureChecker extends AbstractLocalChecker {

    private enum Layer { CONTROLLER, SERVICE, DAO, ENTITY, OTHER }

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.ARCHITECTURE;
    }

    @Override
    public int getPriority() {
        return 45;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString()).orElse("");
        Layer ownLayer = layerOfPackage(pkg);
        String ownClass = cu.getPrimaryTypeName().orElse("");
        if (ownLayer == Layer.OTHER) {
            // 包名无层信息时按类名后缀兜底判定自身层
            ownLayer = layerOfClassSuffix(ownClass);
        }
        if (ownLayer == Layer.OTHER) {
            return;
        }

        String ownRoot = packageRoot(pkg, 2);

        for (ImportDeclaration imp : cu.getImports()) {
            String fqcn = imp.getNameAsString();
            int lastDot = fqcn.lastIndexOf('.');
            if (lastDot <= 0) {
                continue;
            }
            String impPkg = fqcn.substring(0, lastDot);
            String impClass = fqcn.substring(lastDot + 1);

            // 仅约束项目内部依赖：共享根包，或类名带明确分层后缀
            Layer targetLayer = layerOfPackage(impPkg);
            boolean sameRoot = !ownRoot.isEmpty() && impPkg.startsWith(ownRoot + ".");
            if (targetLayer == Layer.OTHER) {
                targetLayer = layerOfClassSuffix(impClass);
            }
            if (targetLayer == Layer.OTHER || (!sameRoot && layerOfClassSuffix(impClass) == Layer.OTHER)) {
                continue;
            }
            // 同层互引不在本轮规则内（controller→controller 常见于继承基类）
            if (targetLayer == ownLayer) {
                continue;
            }

            int line = imp.getBegin().map(p -> p.line).orElse(1);
            String ruleCode = null;
            String title = null;
            String description = null;
            String suggestion = null;

            if (ownLayer == Layer.CONTROLLER && targetLayer == Layer.DAO) {
                ruleCode = "ARCH_LAYER_SKIP";
                title = "架构越层依赖";
                description = "Controller 越过 Service 层直接依赖 DAO/Mapper（" + fqcn + "），破坏分层架构";
                suggestion = "在 Service 层封装数据访问逻辑，Controller 只依赖 Service 接口";
            } else if ((ownLayer == Layer.SERVICE || ownLayer == Layer.DAO)
                    && targetLayer == Layer.CONTROLLER) {
                ruleCode = "ARCH_LAYER_REVERSE";
                title = "架构反向依赖";
                description = lowerName(ownLayer) + " 层反向依赖 Controller（" + fqcn + "），依赖方向应为 controller → service → dao";
                suggestion = "将 Controller 中被下层引用的逻辑下沉到 Service，或通过事件/接口回调解耦";
            } else if (ownLayer == Layer.ENTITY
                    && (targetLayer == Layer.CONTROLLER || targetLayer == Layer.SERVICE
                        || targetLayer == Layer.DAO)) {
                ruleCode = "ARCH_ENTITY_LEAK";
                title = "实体层依赖上层";
                description = "Entity/领域对象依赖 " + lowerName(targetLayer) + " 层（" + fqcn + "），实体应保持纯净、无基础设施依赖";
                suggestion = "移除实体对上层/基础设施的引用，业务逻辑放入 Service，展示转换使用 DTO/VO";
            }

            if (ruleCode != null) {
                CheckIssue issue = createIssue(
                        IssueLevel.MAJOR, ruleCode, title, description,
                        context.getCurrentFilePath(), line, line);
                issue.setSuggestion(suggestion);
                issues.add(issue);
            }
        }
    }

    /**
     * 由包名段判定层归属
     */
    private Layer layerOfPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return Layer.OTHER;
        }
        for (String seg : pkg.split("\\.")) {
            String s = seg.toLowerCase();
            switch (s) {
                case "controller":
                case "controllers":
                case "web":
                case "rest":
                case "endpoint":
                case "endpoints":
                    return Layer.CONTROLLER;
                case "service":
                case "services":
                    return Layer.SERVICE;
                case "mapper":
                case "mappers":
                case "dao":
                case "repository":
                case "repositories":
                    return Layer.DAO;
                case "entity":
                case "entities":
                case "domain":
                case "po":
                case "model":
                case "models":
                    return Layer.ENTITY;
                default:
                    break;
            }
        }
        return Layer.OTHER;
    }

    /**
     * 由类名后缀判定层归属（包名无层信息时兜底）
     */
    private Layer layerOfClassSuffix(String className) {
        if (className == null || className.isEmpty()) {
            return Layer.OTHER;
        }
        if (className.endsWith("Controller") || className.endsWith("Resource")
                || className.endsWith("Endpoint")) {
            return Layer.CONTROLLER;
        }
        if (className.endsWith("ServiceImpl") || className.endsWith("Service")) {
            return Layer.SERVICE;
        }
        if (className.endsWith("Mapper") || className.endsWith("Dao")
                || className.endsWith("Repository")) {
            return Layer.DAO;
        }
        return Layer.OTHER;
    }

    /**
     * 取包名前 n 段作为根包
     */
    private String packageRoot(String pkg, int segments) {
        if (pkg == null || pkg.isEmpty()) {
            return "";
        }
        String[] parts = pkg.split("\\.");
        int n = Math.min(segments, parts.length);
        return String.join(".", java.util.Arrays.copyOfRange(parts, 0, n));
    }

    private String lowerName(Layer layer) {
        return switch (layer) {
            case CONTROLLER -> "Controller";
            case SERVICE -> "Service";
            case DAO -> "DAO";
            case ENTITY -> "Entity";
            default -> "Other";
        };
    }
}
