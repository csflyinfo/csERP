package com.erp.system.perm;

import com.erp.common.security.RequirePerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * dev 环境权限盘点工具（PRD-28 §18.1）。
 *
 * <p>每次 dev 启动后反射全部 @*Mapping，按「URL 片段 = 菜单层级」规则给出建议功能点编码，
 * 输出到仓库 docs/perm-inventory.md，作为卡片 5~7 补 @RequirePerm 的作业清单与评审依据。
 * 生产 profile 不加载本组件。
 */
@Component
@Profile("dev")
public class PermInventoryDumper {

    private static final Logger log = LoggerFactory.getLogger(PermInventoryDumper.class);

    /** 基础设施路径不纳入权限盘点（登录/健康检查/清库冒烟/流程跑批/错误页）。 */
    private static final Set<String> SKIP_PREFIX = Set.of(
            "/auth/", "/actuator/", "/testing/", "/flow/", "/error");

    private final RequestMappingHandlerMapping handlerMapping;
    private final MenuCatalog catalog;

    public PermInventoryDumper(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            MenuCatalog catalog) {
        this.handlerMapping = handlerMapping;
        this.catalog = catalog;
    }

    private record Row(String module, String method, String path, String handler,
                       String suggestedCode, String menuCode, String action, String anno) {}

    @EventListener(ApplicationReadyEvent.class)
    public void dump() {
        List<Row> rows = collect();
        String md = render(rows);
        Path target = resolveDocsDir().resolve("perm-inventory.md");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, md);
            long guarded = rows.stream().filter(r -> !r.anno().isBlank()).count();
            log.info("权限盘点已生成：{}（端点 {}，已挂注解 {}）", target.toAbsolutePath().normalize(),
                    rows.size(), guarded);
        } catch (IOException e) {
            // 盘点文件写不出不阻断启动
            log.warn("权限盘点文件写入失败：{}", target.toAbsolutePath().normalize(), e);
        }
    }

    private List<Row> collect() {
        List<Row> rows = new ArrayList<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod hm = entry.getValue();
            List<String> patterns = patternsOf(info);
            if (patterns.isEmpty()) continue;
            String path = patterns.get(0);
            if (SKIP_PREFIX.stream().anyMatch(path::startsWith) || "/error".equals(path)) continue;

            Set<String> methods = new LinkedHashSet<>();
            info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            String httpMethod = methods.isEmpty() ? "ANY" : String.join(",", methods);

            RequirePerm methodAnno = hm.getMethodAnnotation(RequirePerm.class);
            RequirePerm typeAnno = hm.getBeanType().getAnnotation(RequirePerm.class);
            RequirePerm anno = methodAnno != null ? methodAnno : typeAnno;

            String module = path.startsWith("/") ? path.split("/")[1] : "";
            String handler = hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();

            String annoCell;
            String suggestedCode;
            String menuCode;
            String action;
            if (anno != null) {
                annoCell = (methodAnno != null ? "方法" : "类") + "：" + anno.value()
                        + (anno.global() ? "（GLOBAL）" : "");
                int dot = anno.value().lastIndexOf('.');
                suggestedCode = anno.value();
                menuCode = dot > 0 ? anno.value().substring(0, dot) : "";
                action = dot >= 0 ? anno.value().substring(dot + 1) : "";
            } else {
                annoCell = "";
                MenuNode page = matchPage(path);
                if (page == null) {
                    suggestedCode = "—";
                    menuCode = "—";
                    action = "—";
                } else {
                    menuCode = page.getCode();
                    String tail = path.startsWith(page.getRoutePath() + "/")
                            ? path.substring(page.getRoutePath().length() + 1) : "";
                    action = suggestAction(tail, httpMethod);
                    suggestedCode = menuCode + "." + action;
                }
            }
            rows.add(new Row(module, httpMethod, path, handler, suggestedCode, menuCode, action, annoCell));
        }
        rows.sort(Comparator.comparing(Row::path).thenComparing(Row::method));
        return rows;
    }

    /** 路由最长前缀匹配：path 等于 page 路由或以其为前缀。 */
    private MenuNode matchPage(String path) {
        MenuNode best = null;
        for (MenuNode node : catalog.all()) {
            if (node.getType() != MenuNode.Type.PAGE || node.getRoutePath() == null) continue;
            String route = node.getRoutePath();
            if (path.equals(route) || path.startsWith(route + "/")) {
                if (best == null || route.length() > best.getRoutePath().length()) best = node;
            }
        }
        return best;
    }

    /** §18.1 URL 末段 → 标准动作映射；识别不了的业务动作统一 biz_ 前缀。 */
    private String suggestAction(String tail, String httpMethod) {
        if (tail.isEmpty()) return "view";
        String seg = tail.split("/")[0].toLowerCase();
        String bare = seg.replaceAll("\\{.*}", "").replaceAll("^-|-$", "");
        if (bare.isEmpty()) return "view";
        if (bare.contains("unaudit") || bare.contains("reverse-audit") || bare.equals("reverse")) return "unaudit";
        if (bare.contains("audit") || bare.equals("approve")) return "audit";
        if (bare.contains("import") || bare.contains("upload")) return "import";
        if (bare.contains("export") || bare.contains("download")) return "export";
        if (bare.contains("print")) return "print";
        if (bare.startsWith("del") || bare.startsWith("remove")) return "delete";
        if (bare.contains("close") || bare.contains("cancel") || bare.contains("void")) return "close";
        if (bare.contains("log")) return "log";
        if (bare.startsWith("add") || bare.startsWith("create")) return "add";
        if (bare.startsWith("edit") || bare.startsWith("update") || bare.equals("save")) return "edit";
        if (bare.equals("page") || bare.equals("list") || bare.equals("detail")
                || bare.equals("info") || bare.equals("get")) return "view";
        // 兜底：GET 视为查看，其它写动作保留原名加 biz_ 前缀（人工评审是否需要新动作）
        if ("GET".equals(httpMethod)) return "view";
        return "biz_" + bare.replace('-', '_');
    }

    private String render(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 权限盘点清单（自动生成，请勿手改）\n\n");
        sb.append("> 由 `PermInventoryDumper` 在 dev 启动时依据 RequestMapping 反射生成（PRD-28 §18.1）。\n");
        sb.append("> 生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        long guarded = rows.stream().filter(r -> !r.anno().isBlank()).count();
        long writes = rows.stream()
                .filter(r -> r.method().contains("POST") || r.method().contains("PUT") || r.method().contains("DELETE"))
                .filter(r -> r.anno().isBlank()).count();
        sb.append("- 端点总数：").append(rows.size()).append("\n");
        sb.append("- 已挂 @RequirePerm：").append(guarded).append("\n");
        sb.append("- 未纳管写端点（需在卡片5~7 补齐或加入豁免）：").append(writes).append("\n\n");

        var byModule = new TreeMap<String, List<Row>>();
        for (Row r : rows) byModule.computeIfAbsent(r.module(), k -> new ArrayList<>()).add(r);
        for (var e : byModule.entrySet()) {
            sb.append("## ").append(e.getKey()).append("\n\n");
            sb.append("| HTTP | 路径 | Handler | 建议功能点编码 | 归属菜单 | 动作 | @RequirePerm |\n");
            sb.append("| --- | --- | --- | --- | --- | --- | --- |\n");
            for (Row r : e.getValue()) {
                sb.append("| ").append(r.method())
                        .append(" | `").append(r.path()).append("`")
                        .append(" | ").append(r.handler())
                        .append(" | ").append(r.suggestedCode())
                        .append(" | ").append(r.menuCode())
                        .append(" | ").append(r.action())
                        .append(" | ").append(r.anno().isBlank() ? "❌" : r.anno())
                        .append(" |\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private Path resolveDocsDir() {
        Path sibling = Path.of("../docs");
        if (Files.isDirectory(sibling)) return sibling;
        return Path.of("docs");
    }

    private List<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues().stream().sorted().toList();
        }
        return List.of();
    }
}
