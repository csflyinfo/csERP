package com.erp.system;

import com.erp.common.util.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 操作日志统一写入入口（PRD-31）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>审计独立事务</b>：所有写方法 {@code REQUIRES_NEW} 且整体 try/catch 不抛异常，
 *       日志写入失败、业务回滚都不影响——业务回滚后审计日志仍保留（审计语义要求）。</li>
 *   <li><b>真实操作人</b>：账号取自 {@link RequestContext}（JWT subject），姓名优先 JWT claim，
 *       回落 sys_user_runtime 查询（带缓存）；无请求上下文（定时任务）记 SYSTEM。</li>
 *   <li><b>改前改后</b>：{@link #logUpdate} 按 {@link KeyFields} 关键字段做字段级 diff，
 *       生成 operation_content 摘要与 before/after JSON；明细按行级 diff（新增/修改/删除）。
 *       备注等非关键字段变化只标注「非关键字段有修改」，不留值。</li>
 *   <li><b>脱敏</b>：key 命中 password/token/secret/pwd 的值一律写 ***。</li>
 * </ul>
 */
@Service
public class OperationLogService {

    private final JdbcTemplate jdbc;
    private final SysParamService sysParam;
    private final ObjectMapper objectMapper;
    /** 自身代理：让 {@link #saveInner} 的 REQUIRES_NEW 经过事务代理（自调用 this 会绕过代理）。 */
    private final OperationLogService self;

    /** account -> display_name 缓存（用户量级小，命中 JWT 时根本不查库）。 */
    private final Map<String, String> nameCache = new ConcurrentHashMap<>();

    /** 需要脱敏的字段名（小写包含匹配）。 */
    private static final Pattern MASK_PATTERN = Pattern.compile("password|passwd|token|secret|pwd");

    /** 非关键字段变化时的统一提示。 */
    private static final String NON_KEY_HINT = "非关键字段有修改";

    public OperationLogService(JdbcTemplate jdbc, SysParamService sysParam, ObjectMapper objectMapper,
                               @Lazy OperationLogService self) {
        this.jdbc = jdbc;
        this.sysParam = sysParam;
        this.objectMapper = objectMapper;
        this.self = self;
    }

    // ============================ 便捷写方法 ============================

    /** 最简写入（兼容旧调用）：成功、非敏感。 */
    public void log(String moduleCode, String action, String bizNo, String detail) {
        save(builder(moduleCode, action).bizNo(bizNo).detail(detail).resultSuccess().build());
    }

    /** 带业务对象定位的写入（用于按单据聚时间线）。 */
    public void log(String moduleCode, String action, String bizType, String bizId, String bizNo, String detail) {
        save(builder(moduleCode, action).bizType(bizType).bizId(bizId).bizNo(bizNo).detail(detail).resultSuccess().build());
    }

    /** 敏感操作（如成本调整、客户价格查看）：整条日志 sensitive=Y。 */
    public void logSensitive(String moduleCode, String action, String bizType, String bizId, String bizNo, String detail) {
        RecB b = builder(moduleCode, action).bizType(bizType).bizId(bizId).bizNo(bizNo).detail(detail).resultSuccess();
        b.sensitive = "Y";
        save(b.build());
    }

    /** 业务失败：result=FAIL，记录失败原因（用于登录失败、审核被拒等）。 */
    public void logFail(String moduleCode, String action, String bizNo, String failReason) {
        RecB b = builder(moduleCode, action).bizNo(bizNo);
        b.result = "FAIL";
        b.failReason = truncate(failReason, 500);
        save(b.build());
    }

    /**
     * 单据详情查看。受参数 OP_LOG_ENABLE_DETAIL_VIEW（'1' 记 / '0' 不记）控制；
     * 敏感模块（成本/价格等）无论开关都记录且标 sensitive。
     */
    public void logView(String moduleCode, String bizType, String bizId, String bizNo, boolean sensitiveModule) {
        boolean enabled = "1".equals(sysParam.get("OP_LOG_ENABLE_DETAIL_VIEW", "0"));
        if (!enabled && !sensitiveModule) {
            return;
        }
        RecB b = builder(moduleCode, OperationAction.VIEW).bizType(bizType).bizId(bizId).bizNo(bizNo)
                .detail("查看单据详情").resultSuccess();
        if (sensitiveModule) {
            b.sensitive = "Y";
        }
        save(b.build());
    }

    /**
     * 修改留痕（主表，无明细行）——商品/客户/供应商等基础资料用。
     *
     * @param before 改前字段快照（key 与 {@link KeyFields} 字段一致）
     * @param after  改后字段快照
     */
    public void logUpdate(String moduleCode, String bizType, String bizId, String bizNo,
                          Map<String, Object> before, Map<String, Object> after) {
        logUpdate(moduleCode, OperationAction.UPDATE, bizType, bizId, bizNo, before, after, null, null);
    }

    /**
     * 修改留痕（主表 + 明细行）——采购/销售订单编辑用，动作固定为 UPDATE。
     *
     * @param beforeLines 改前明细（Map 列表，含 detail_id/goods_code 等），无明细传 null
     * @param afterLines  改后明细，无明细传 null
     */
    public void logUpdate(String moduleCode, String bizType, String bizId, String bizNo,
                          Map<String, Object> before, Map<String, Object> after,
                          List<Map<String, Object>> beforeLines, List<Map<String, Object>> afterLines) {
        logUpdate(moduleCode, OperationAction.UPDATE, bizType, bizId, bizNo, before, after, beforeLines, afterLines);
    }

    /**
     * 带指定动作、无明细行的改前改后留痕——基础资料停用/删除/冻结等状态流转用。
     */
    public void logUpdate(String moduleCode, String action, String bizType, String bizId, String bizNo,
                          Map<String, Object> before, Map<String, Object> after) {
        logUpdate(moduleCode, action, bizType, bizId, bizNo, before, after, null, null);
    }

    /**
     * 带指定动作的改前改后留痕——审核/反审核/关闭等状态流转也想留下「状态：待审核→已审核」这类 diff 时用。
     * before/after 只放需要对比的字段（如 status）即可。
     */
    public void logUpdate(String moduleCode, String action, String bizType, String bizId, String bizNo,
                          Map<String, Object> before, Map<String, Object> after,
                          List<Map<String, Object>> beforeLines, List<Map<String, Object>> afterLines) {
        Map<String, String> mainLabels = KeyFields.mainFields(bizType);
        Map<String, String> lineLabels = KeyFields.lineFields(bizType);

        // ---- 主表字段 diff ----
        List<Map<String, Object>> mainChanges = new ArrayList<>();
        boolean sensitive = false;
        for (Map.Entry<String, String> e : mainLabels.entrySet()) {
            String field = e.getKey();
            String oldV = norm(before == null ? null : before.get(field));
            String newV = norm(after == null ? null : after.get(field));
            if (!oldV.equals(newV)) {
                Map<String, Object> ch = new LinkedHashMap<>();
                ch.put("field", field);
                ch.put("label", e.getValue());
                ch.put("old", maskIfNeeded(field, oldV));
                ch.put("new", maskIfNeeded(field, newV));
                mainChanges.add(ch);
                if (KeyFields.isSensitiveField(field)) {
                    sensitive = true;
                }
            }
        }
        // 非关键字段是否变化（只标注，不留值）
        boolean nonKeyChanged = hasNonKeyChange(before, after, mainLabels.keySet());

        // ---- 明细行 diff ----
        List<Map<String, Object>> lineChanges = new ArrayList<>();
        if (lineLabels != null && !lineLabels.isEmpty()) {
            diffLines(beforeLines, afterLines, lineLabels, lineChanges);
            for (Map<String, Object> lc : lineChanges) {
                if (Boolean.TRUE.equals(lc.get("_sensitive"))) {
                    sensitive = true;
                }
                lc.remove("_sensitive");
            }
        }

        // 无任何变化不写日志
        if (mainChanges.isEmpty() && lineChanges.isEmpty() && !nonKeyChanged) {
            return;
        }

        // ---- 组装 content / before / after ----
        StringBuilder content = new StringBuilder();
        for (Map<String, Object> ch : mainChanges) {
            if (content.length() > 0) content.append("; ");
            content.append(ch.get("label")).append(": ").append(ch.get("old")).append("→").append(ch.get("new"));
        }
        int add = 0, mod = 0, rem = 0;
        for (Map<String, Object> lc : lineChanges) {
            String op = String.valueOf(lc.get("op"));
            if ("ADD".equals(op)) add++;
            else if ("REMOVE".equals(op)) rem++;
            else mod++;
        }
        if (add + mod + rem > 0) {
            if (content.length() > 0) content.append("; ");
            content.append("明细：新增").append(add).append("行/修改").append(mod).append("行/删除").append(rem).append("行");
        }
        if (nonKeyChanged) {
            if (content.length() > 0) content.append("; ");
            content.append(NON_KEY_HINT);
        }

        Map<String, Object> diffStruct = new LinkedHashMap<>();
        diffStruct.put("main", mainChanges);
        diffStruct.put("lines", lineChanges);

        RecB b = builder(moduleCode, action).bizType(bizType).bizId(bizId).bizNo(bizNo)
                .resultSuccess();
        b.content = truncate(content.toString(), 2000);
        b.beforeJson = toJson(maskSnapshot(before));
        b.afterJson = toJson(diffStruct);
        b.detail = truncate(content.toString(), 1000);
        if (sensitive) {
            b.sensitive = "Y";
        }
        save(b.build());
    }

    // ============================ 内部实现 ============================

    /** 明细行 diff：按 detail_id（优先）或 goods_code 配对。 */
    private void diffLines(List<Map<String, Object>> beforeLines, List<Map<String, Object>> afterLines,
                           Map<String, String> lineLabels, List<Map<String, Object>> out) {
        Map<String, Map<String, Object>> beforeMap = indexLines(beforeLines);
        Map<String, Map<String, Object>> afterMap = indexLines(afterLines);

        // 修改/新增：遍历改后
        for (Map.Entry<String, Map<String, Object>> e : afterMap.entrySet()) {
            String key = e.getKey();
            Map<String, Object> afterRow = e.getValue();
            Map<String, Object> beforeRow = beforeMap.get(key);
            if (beforeRow == null) {
                // 新增行：全部关键字段作为 new
                Map<String, Object> lc = new LinkedHashMap<>();
                lc.put("op", "ADD");
                lc.put("lineKey", key);
                lc.put("lineLabel", lineLabel(afterRow));
                List<Map<String, Object>> changes = new ArrayList<>();
                boolean sens = false;
                for (Map.Entry<String, String> f : lineLabels.entrySet()) {
                    String nv = norm(afterRow.get(f.getKey()));
                    if (!nv.isEmpty()) {
                        changes.add(fieldChange(f.getKey(), f.getValue(), "", nv));
                        if (KeyFields.isSensitiveField(f.getKey())) sens = true;
                    }
                }
                lc.put("changes", changes);
                if (sens) lc.put("_sensitive", true);
                out.add(lc);
            } else {
                // 修改行：逐字段 diff
                List<Map<String, Object>> changes = new ArrayList<>();
                boolean sens = false;
                for (Map.Entry<String, String> f : lineLabels.entrySet()) {
                    String oldV = norm(beforeRow.get(f.getKey()));
                    String newV = norm(afterRow.get(f.getKey()));
                    if (!oldV.equals(newV)) {
                        changes.add(fieldChange(f.getKey(), f.getValue(), oldV, newV));
                        if (KeyFields.isSensitiveField(f.getKey())) sens = true;
                    }
                }
                if (!changes.isEmpty()) {
                    Map<String, Object> lc = new LinkedHashMap<>();
                    lc.put("op", "MODIFY");
                    lc.put("lineKey", key);
                    lc.put("lineLabel", lineLabel(afterRow));
                    lc.put("changes", changes);
                    if (sens) lc.put("_sensitive", true);
                    out.add(lc);
                }
            }
        }
        // 删除：改前有、改后无
        for (Map.Entry<String, Map<String, Object>> e : beforeMap.entrySet()) {
            if (!afterMap.containsKey(e.getKey())) {
                Map<String, Object> beforeRow = e.getValue();
                Map<String, Object> lc = new LinkedHashMap<>();
                lc.put("op", "REMOVE");
                lc.put("lineKey", e.getKey());
                lc.put("lineLabel", lineLabel(beforeRow));
                List<Map<String, Object>> changes = new ArrayList<>();
                boolean sens = false;
                for (Map.Entry<String, String> f : lineLabels.entrySet()) {
                    String ov = norm(beforeRow.get(f.getKey()));
                    if (!ov.isEmpty()) {
                        changes.add(fieldChange(f.getKey(), f.getValue(), ov, ""));
                        if (KeyFields.isSensitiveField(f.getKey())) sens = true;
                    }
                }
                lc.put("changes", changes);
                if (sens) lc.put("_sensitive", true);
                out.add(lc);
            }
        }
    }

    private Map<String, Object> fieldChange(String field, String label, String oldV, String newV) {
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("field", field);
        ch.put("label", label);
        ch.put("old", maskIfNeeded(field, oldV));
        ch.put("new", maskIfNeeded(field, newV));
        return ch;
    }

    private Map<String, Map<String, Object>> indexLines(List<Map<String, Object>> lines) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        if (lines == null) return map;
        int idx = 0;
        for (Map<String, Object> row : lines) {
            String key = lineKey(row, idx++);
            map.put(key, row);
        }
        return map;
    }

    /** 行配对键：优先 detail_id，其次 goods_code，都没有用行序号。 */
    private String lineKey(Map<String, Object> row, int idx) {
        String id = norm(row.get("detail_id"));
        if (!id.isEmpty()) return "id:" + id;
        String code = norm(row.get("goods_code"));
        if (!code.isEmpty()) return "code:" + code;
        return "row:" + idx;
    }

    private String lineLabel(Map<String, Object> row) {
        String name = norm(row.get("goods_name"));
        String code = norm(row.get("goods_code"));
        if (!name.isEmpty() && !code.isEmpty()) return name + "（" + code + "）";
        return name.isEmpty() ? code : name;
    }

    /** 非关键字段是否有变化（只判断，不留值）。 */
    private boolean hasNonKeyChange(Map<String, Object> before, Map<String, Object> after,
                                    java.util.Set<String> keyFields) {
        if (before == null || after == null) return false;
        java.util.Set<String> all = new java.util.HashSet<>();
        all.addAll(before.keySet());
        all.addAll(after.keySet());
        for (String k : all) {
            if (keyFields.contains(k)) continue;
            if (MASK_PATTERN.matcher(k.toLowerCase()).find()) continue;
            String o = norm(before.get(k));
            String n = norm(after.get(k));
            if (!o.equals(n)) return true;
        }
        return false;
    }

    /** 快照脱敏：返回一个新 Map，敏感 key 的值替换为 ***。 */
    private Map<String, Object> maskSnapshot(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (src == null) return out;
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String k = e.getKey();
            if (MASK_PATTERN.matcher(k.toLowerCase()).find()) {
                out.put(k, "***");
            } else {
                out.put(k, e.getValue());
            }
        }
        return out;
    }

    private String maskIfNeeded(String field, String value) {
        if (field != null && MASK_PATTERN.matcher(field.toLowerCase()).find()) {
            return value.isEmpty() ? value : "***";
        }
        return value;
    }

    // ============================ 落库 ============================

    private void save(Rec r) {
        try {
            // 经自身代理调用，确保 REQUIRES_NEW 独立事务生效（业务回滚不影响审计日志）
            self.saveInner(r);
        } catch (Exception ignored) {
            // 审计日志永不影响主业务
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveInner(Rec r) {
        jdbc.update(
            "INSERT INTO sys_operation_log_runtime(" +
            "log_id, operate_at, operator_name, module_code, action, biz_no, result, detail, " +
            "operator_id, operator_account, module_name, action_name, biz_type, biz_id, " +
            "request_ip, request_url, request_method, cost_time_ms, fail_reason, sensitive, " +
            "before_value, after_value, operation_content) " +
            "VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            r.logId, r.operatorName, r.moduleCode, r.action, r.bizNo, r.result, r.detail,
            r.operatorId, r.operatorAccount, r.moduleName, r.actionName, r.bizType, r.bizId,
            r.ip, r.url, r.method, r.costMs, r.failReason, r.sensitive,
            r.beforeJson, r.afterJson, r.content
        );
    }

    private RecB builder(String moduleCode, String action) {
        RecB b = new RecB();
        b.moduleCode = moduleCode;
        b.action = action;
        b.moduleName = OperationModule.name(moduleCode);
        b.actionName = OperationAction.name(action);
        b.result = "SUCCESS";
        b.sensitive = "N";

        RequestContext.Ctx ctx = RequestContext.current();
        if (ctx != null) {
            b.operatorAccount = ctx.account();
            b.ip = ctx.ip();
            b.url = truncate(ctx.uri(), 500);
            b.method = ctx.method();
            b.costMs = (int) Math.min(System.currentTimeMillis() - ctx.startMillis(), Integer.MAX_VALUE);
            b.operatorId = ctx.userId();
            b.operatorName = resolveName(ctx.account(), ctx.displayName());
        } else {
            b.operatorAccount = "SYSTEM";
            b.operatorName = "系统";
        }
        return b;
    }

    /** 姓名：优先 JWT 携带的 displayName，否则按账号查库（带缓存）。 */
    private String resolveName(String account, String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        if (account == null || account.isBlank()) {
            return "系统";
        }
        String cached = nameCache.get(account);
        if (cached != null) {
            return cached;
        }
        try {
            List<String> names = jdbc.queryForList(
                    "SELECT display_name FROM sys_user_runtime WHERE username = ?", String.class, account);
            String name = names.isEmpty() ? account : names.get(0);
            nameCache.put(account, name);
            return name;
        } catch (Exception e) {
            return account;
        }
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 归一化取值：null→""，去首尾空白；数字/对象统一 toString。 */
    private String norm(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v).trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ---- 内部承载结构 ----

    private static class Rec {
        String logId, operatorName, moduleCode, action, bizNo, result, detail;
        String operatorId, operatorAccount, moduleName, actionName, bizType, bizId;
        String ip, url, method, failReason, sensitive, beforeJson, afterJson, content;
        int costMs;
    }

    /** 构建器：链式填充后 build() 生成 logId。 */
    private static class RecB {
        String moduleCode, action, bizType, bizId, bizNo, detail, content, beforeJson, afterJson, failReason;
        String result = "SUCCESS", sensitive = "N";
        String operatorId, operatorAccount, operatorName, ip, url, method, moduleName, actionName;
        int costMs;

        RecB bizType(String v) { this.bizType = v; return this; }
        RecB bizId(String v) { this.bizId = v; return this; }
        RecB bizNo(String v) { this.bizNo = v; return this; }
        RecB detail(String v) { this.detail = v; return this; }
        RecB resultSuccess() { this.result = "SUCCESS"; return this; }

        Rec build() {
            Rec r = new Rec();
            r.logId = "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            r.moduleCode = moduleCode; r.action = action; r.bizType = bizType; r.bizId = bizId;
            r.bizNo = bizNo; r.result = result; r.detail = detail; r.content = content;
            r.beforeJson = beforeJson; r.afterJson = afterJson; r.failReason = failReason;
            r.sensitive = sensitive;
            r.operatorId = operatorId; r.operatorAccount = operatorAccount; r.operatorName = operatorName;
            r.ip = ip; r.url = url; r.method = method;
            r.moduleName = moduleName; r.actionName = actionName; r.costMs = costMs;
            return r;
        }
    }
}
