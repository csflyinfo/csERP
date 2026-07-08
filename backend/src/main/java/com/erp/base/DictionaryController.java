package com.erp.base;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 用户数据字典模块：
 * <ul>
 *   <li>字典类型 sys_dictionary_type（左侧网格）— CRUD</li>
 *   <li>字典值 sys_dictionary（右侧网格）— 按 dict_type 增删改停</li>
 *   <li>公共取值：{@code GET /base/dictionary?type=xxx} 已在 {@code BaseController} 里提供，仅返回 status=NORMAL</li>
 * </ul>
 */
@RestController
@RequestMapping("/base/dictionary")
public class DictionaryController {

    private final JdbcTemplate jdbcTemplate;

    public DictionaryController(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    // ================ 字典类型 ================

    @PostMapping("/type/page")
    public ApiResponse<PageResult<Map<String, Object>>> typePage(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, dict_type, dict_type_name, description, is_system, status, created_at "
                + "FROM sys_dictionary_type ORDER BY dict_type ASC");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(camelize(r));
        return ApiResponse.ok(PageResult.of(out, request));
    }

    @PostMapping("/type/save")
    public ApiResponse<Map<String, Object>> typeSave(@RequestBody Map<String, Object> req) {
        String id = trim(req.get("id"));
        String type = trim(req.get("dictType"));
        String name = trim(req.get("dictTypeName"));
        String desc = trim(req.get("description"));
        if (type.isEmpty() || name.isEmpty()) return ApiResponse.fail("400", "字典编码和名称必填");
        if (id.isEmpty()) {
            // 新建：编码冲突拒
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_dictionary_type WHERE dict_type = ?", Integer.class, type);
            if (c != null && c > 0) return ApiResponse.fail("400", "字典编码已存在：" + type);
            id = "DT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
            jdbcTemplate.update(
                    "INSERT INTO sys_dictionary_type(id, dict_type, dict_type_name, description, is_system, status) "
                    + "VALUES (?, ?, ?, ?, 0, 'NORMAL')",
                    id, type, name, desc);
        } else {
            // 编辑：is_system 记录只能改 name/description（不能改 dict_type）
            Map<String, Object> old = jdbcTemplate.queryForMap(
                    "SELECT * FROM sys_dictionary_type WHERE id = ?", id);
            Object isSys = old.get("is_system"); if (isSys == null) isSys = old.get("IS_SYSTEM");
            boolean system = isSys instanceof Boolean b ? b : (isSys instanceof Number n && n.intValue() > 0);
            if (system) {
                jdbcTemplate.update(
                        "UPDATE sys_dictionary_type SET dict_type_name = ?, description = ? WHERE id = ?",
                        name, desc, id);
            } else {
                jdbcTemplate.update(
                        "UPDATE sys_dictionary_type SET dict_type = ?, dict_type_name = ?, description = ? WHERE id = ?",
                        type, name, desc, id);
            }
        }
        return ApiResponse.ok(GenericResult.row("id", id, "success", true));
    }

    @PostMapping("/type/delete")
    public ApiResponse<Map<String, Object>> typeDelete(@RequestBody Map<String, Object> req) {
        String id = trim(req.get("id"));
        if (id.isEmpty()) return ApiResponse.fail("400", "缺少 id");
        // 系统内置类型不允许删
        Integer sysCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_dictionary_type WHERE id = ? AND is_system = TRUE", Integer.class, id);
        if (sysCnt != null && sysCnt > 0) return ApiResponse.fail("400", "系统预置字典类型不允许删除");
        // 先查 dict_type，再删除所有值
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT dict_type FROM sys_dictionary_type WHERE id = ?", id);
        if (rows.isEmpty()) return ApiResponse.fail("404", "字典类型不存在");
        Object dt = rows.get(0).get("dict_type"); if (dt == null) dt = rows.get(0).get("DICT_TYPE");
        String dictType = String.valueOf(dt);
        jdbcTemplate.update("DELETE FROM sys_dictionary WHERE dict_type = ?", dictType);
        jdbcTemplate.update("DELETE FROM sys_dictionary_type WHERE id = ?", id);
        return ApiResponse.ok(GenericResult.row("id", id, "success", true));
    }

    // ================ 字典值 ================

    @PostMapping("/value/page")
    public ApiResponse<PageResult<Map<String, Object>>> valuePage(@RequestBody Map<String, Object> req) {
        String dictType = trim(req.get("dictType"));
        if (dictType.isEmpty()) return ApiResponse.ok(new PageResult<>(new ArrayList<>(), 1, 100, 0, Map.of()));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, dict_type, dict_code, dict_name, sort_order, is_system, remark, status "
                + "FROM sys_dictionary WHERE dict_type = ? ORDER BY sort_order ASC, dict_code ASC",
                dictType);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(camelize(r));
        // 字典值一般不多，返回全量
        return ApiResponse.ok(new PageResult<>(out, 1, Math.max(out.size(), 100), out.size(), Map.of()));
    }

    @PostMapping("/value/save")
    public ApiResponse<Map<String, Object>> valueSave(@RequestBody Map<String, Object> req) {
        String id = trim(req.get("id"));
        String dictType = trim(req.get("dictType"));
        String code = trim(req.get("dictCode"));
        String name = trim(req.get("dictName"));
        String remark = trim(req.get("remark"));
        int sort = parseInt(req.get("sortOrder"));
        String status = trim(req.get("status")); if (status.isEmpty()) status = "NORMAL";
        if (dictType.isEmpty() || code.isEmpty() || name.isEmpty())
            return ApiResponse.fail("400", "字典类型/编码/名称必填");
        if (id.isEmpty()) {
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_dictionary WHERE dict_type = ? AND dict_code = ?",
                    Integer.class, dictType, code);
            if (c != null && c > 0) return ApiResponse.fail("400", "字典编码已存在：" + code);
            id = "D_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update(
                    "INSERT INTO sys_dictionary(id, dict_type, dict_code, dict_name, sort_order, is_system, remark, status) "
                    + "VALUES (?, ?, ?, ?, ?, 0, ?, ?)",
                    id, dictType, code, name, sort, remark, status);
        } else {
            // 编辑：is_system 记录不允许改 dict_code
            Map<String, Object> old = jdbcTemplate.queryForMap(
                    "SELECT * FROM sys_dictionary WHERE id = ?", id);
            Object isSys = old.get("is_system"); if (isSys == null) isSys = old.get("IS_SYSTEM");
            boolean system = isSys instanceof Boolean b ? b : (isSys instanceof Number n && n.intValue() > 0);
            if (system) {
                jdbcTemplate.update(
                        "UPDATE sys_dictionary SET dict_name = ?, sort_order = ?, remark = ?, status = ? WHERE id = ?",
                        name, sort, remark, status, id);
            } else {
                jdbcTemplate.update(
                        "UPDATE sys_dictionary SET dict_code = ?, dict_name = ?, sort_order = ?, remark = ?, status = ? WHERE id = ?",
                        code, name, sort, remark, status, id);
            }
        }
        return ApiResponse.ok(GenericResult.row("id", id, "success", true));
    }

    @PostMapping("/value/delete")
    public ApiResponse<Map<String, Object>> valueDelete(@RequestBody Map<String, Object> req) {
        String id = trim(req.get("id"));
        if (id.isEmpty()) return ApiResponse.fail("400", "缺少 id");
        Integer sysCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_dictionary WHERE id = ? AND is_system = TRUE", Integer.class, id);
        if (sysCnt != null && sysCnt > 0) return ApiResponse.fail("400", "系统预置字典值不允许删除，请改为停用");
        // 拒绝删除仍有引用的字典值（用户可先改为停用）
        long usage = countDictValueUsage(id);
        if (usage > 0) return ApiResponse.fail("400", "该字典值已被 " + usage + " 条业务记录引用，无法删除；请改为停用");
        jdbcTemplate.update("DELETE FROM sys_dictionary WHERE id = ?", id);
        return ApiResponse.ok(GenericResult.row("id", id, "success", true));
    }

    @PostMapping("/value/stop")
    public ApiResponse<Map<String, Object>> valueStop(@RequestBody Map<String, Object> req) {
        String id = trim(req.get("id"));
        String status = trim(req.get("status")); // NORMAL / STOPPED
        if (status.isEmpty()) status = "STOPPED";
        jdbcTemplate.update("UPDATE sys_dictionary SET status = ? WHERE id = ?", status, id);
        long usage = "STOPPED".equalsIgnoreCase(status) ? countDictValueUsage(id) : 0L;
        return ApiResponse.ok(GenericResult.row("id", id, "status", status, "usageCount", usage, "success", true));
    }

    /** 查询单个字典值被业务记录引用的数量。 */
    @PostMapping("/value/usage")
    public ApiResponse<Map<String, Object>> valueUsage(@RequestBody Map<String, Object> req) {
        String id = trim(req.get("id"));
        if (id.isEmpty()) return ApiResponse.fail("400", "缺少 id");
        long usage = countDictValueUsage(id);
        return ApiResponse.ok(GenericResult.row("id", id, "usageCount", usage));
    }

    /**
     * 字典值 → 业务表引用统计。
     * 存值场景：绝大多数业务表存的是「字典值名称」（如 base_supplier.delivery_method 存「送货上门」），
     * 而非字典编码；这里按名称匹配以保证兼容。
     */
    private long countDictValueUsage(String dictValueId) {
        java.util.List<Map<String, Object>> vs = jdbcTemplate.queryForList(
                "SELECT dict_type, dict_name FROM sys_dictionary WHERE id = ?", dictValueId);
        if (vs.isEmpty()) return 0;
        Map<String, Object> v = vs.get(0);
        String type = String.valueOf(v.getOrDefault("dict_type", v.getOrDefault("DICT_TYPE", "")));
        String name = String.valueOf(v.getOrDefault("dict_name", v.getOrDefault("DICT_NAME", "")));
        if (type.isEmpty() || name.isEmpty()) return 0;
        // 字典类型 → 引用表/列
        String[][] refs = switch (type) {
            case "delivery_method" -> new String[][] { { "base_supplier", "delivery_method" } };
            case "logistics_company" -> new String[][] { { "base_supplier", "default_logistics_company" } };
            case "customer_channel" -> new String[][] { { "base_customer", "channel_type" } };
            case "supplier_type" -> new String[][] { { "base_supplier", "supplier_type" } };
            case "settlement_method" -> new String[][] { { "base_customer", "settlement_method" }, { "base_supplier", "settlement_method" } };
            default -> null;
        };
        if (refs == null) return 0;
        long total = 0;
        for (String[] ref : refs) {
            try {
                Integer c = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + ref[0] + " WHERE " + ref[1] + " = ?",
                        Integer.class, name);
                if (c != null) total += c;
            } catch (Exception e) { /* 列或表不存在，跳过 */ }
        }
        return total;
    }

    @PostMapping("/import")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> importValues(@RequestBody Map<String, Object> req) {
        String dictType = trim(req.get("dictType"));
        Object rowsRaw = req.get("rows");
        if (dictType.isEmpty() || !(rowsRaw instanceof List<?> list)) {
            return ApiResponse.fail("400", "缺少 dictType 或 rows");
        }
        int inserted = 0, skipped = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) { skipped++; continue; }
            Map<String, Object> r = (Map<String, Object>) raw;
            String code = trim(r.get("dictCode"));
            String name = trim(r.get("dictName"));
            if (code.isEmpty() || name.isEmpty()) { skipped++; continue; }
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_dictionary WHERE dict_type = ? AND dict_code = ?",
                    Integer.class, dictType, code);
            if (c != null && c > 0) { skipped++; continue; }
            String id = "D_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update(
                    "INSERT INTO sys_dictionary(id, dict_type, dict_code, dict_name, sort_order, is_system, remark, status) "
                    + "VALUES (?, ?, ?, ?, ?, 0, ?, 'NORMAL')",
                    id, dictType, code, name, parseInt(r.get("sortOrder")), trim(r.get("remark")));
            inserted++;
        }
        return ApiResponse.ok(GenericResult.row("inserted", inserted, "skipped", skipped, "success", true));
    }

    // ================ 工具 ================

    private static String trim(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static int parseInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    /** 下划线 key 转驼峰；H2 大写场景也一并处理。 */
    private static Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char c : k.toCharArray()) {
                if (c == '_') { upper = true; continue; }
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            out.put(sb.toString(), e.getValue());
        }
        return out;
    }
}
