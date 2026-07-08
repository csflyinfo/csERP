package com.erp.base;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.erp.base.entity.BaseCustomer;
import com.erp.base.entity.BaseSupplier;
import com.erp.base.service.BaseCustomerService;
import com.erp.base.service.BaseSupplierService;
import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 通用主档接口（片区/线路/部门/费用类型/人员/货主/往来单位/资金账户/价格组）
 * 每个主档一张实体表 base_*，通过 moduleCode 分派到对应表。
 * 客户 (customer) / 供应商 (supplier) 走独立 service 保持既有实现。
 */
@RestController
@RequestMapping("/base/master")
public class BaseMasterController {

    private final JdbcTemplate jdbcTemplate;
    private final BaseCustomerService customerService;
    private final BaseSupplierService supplierService;

    public BaseMasterController(JdbcTemplate jdbcTemplate, BaseCustomerService customerService, BaseSupplierService supplierService) {
        this.jdbcTemplate = jdbcTemplate;
        this.customerService = customerService;
        this.supplierService = supplierService;
    }

    // ============================================================
    // moduleCode → 表/列 元信息
    // ============================================================
    private record MasterSpec(String table, String idCol, String codeCol,
                              List<String> allColumns, List<String> nonKeyColumns) {}

    private static final Map<String, MasterSpec> SPECS = Map.ofEntries(
        Map.entry("territory", new MasterSpec("base_territory", "territory_id", "territory_code",
                List.of("territory_id", "territory_code", "territory_name", "parent_code", "city", "coverage", "remark", "status"),
                List.of("territory_name", "parent_code", "city", "coverage", "remark", "status"))),
        Map.entry("routeLine", new MasterSpec("base_route_line", "route_line_id", "route_line_code",
                List.of("route_line_id", "route_line_code", "route_line_name", "driver", "coverage", "remark", "status", "created_at", "creator_name"),
                List.of("route_line_name", "driver", "coverage", "remark", "status"))),
        Map.entry("department", new MasterSpec("base_department", "department_id", "department_code",
                List.of("department_id", "department_code", "department_name", "parent_code", "head_count", "remark", "status"),
                List.of("department_name", "parent_code", "head_count", "remark", "status"))),
        Map.entry("expenseType", new MasterSpec("base_expense_type", "expense_type_id", "expense_type_code",
                List.of("expense_type_id", "expense_type_code", "expense_type_name", "parent_code", "direction", "cost_participation", "remark", "status"),
                List.of("expense_type_name", "parent_code", "direction", "cost_participation", "remark", "status"))),
        Map.entry("employee", new MasterSpec("base_employee", "employee_id", "employee_code",
                List.of("employee_id", "employee_code", "employee_name", "gender", "owner_name", "mobile", "id_card",
                        "education", "address", "department", "is_salesman", "is_salesman_admin", "parent_salesman",
                        "is_buyer", "is_warehouse_keeper", "is_deliveryman", "position", "remark", "status", "created_at"),
                List.of("employee_name", "gender", "owner_name", "mobile", "id_card", "education", "address", "department",
                        "is_salesman", "is_salesman_admin", "parent_salesman", "is_buyer", "is_warehouse_keeper",
                        "is_deliveryman", "position", "remark", "status"))),
        Map.entry("owner", new MasterSpec("base_owner", "owner_id", "owner_code",
                List.of("owner_id", "owner_code", "owner_name", "owner_type", "platform", "remark", "status"),
                List.of("owner_name", "owner_type", "platform", "remark", "status"))),
    Map.entry("counterparty", new MasterSpec("base_counterparty", "counterparty_id", "counterparty_code",
            List.of("counterparty_id", "counterparty_code", "counterparty_name", "counterparty_type", "type_code", "contact_name", "phone", "remark", "status"),
            List.of("counterparty_name", "counterparty_type", "type_code", "contact_name", "phone", "remark", "status"))),
    Map.entry("counterpartyType", new MasterSpec("base_counterparty_type", "type_id", "type_code",
            List.of("type_id", "type_code", "type_name", "sort_order", "remark", "status"),
            List.of("type_name", "sort_order", "remark", "status"))),
        Map.entry("fundAccount", new MasterSpec("base_fund_account", "fund_account_id", "fund_account_code",
                List.of("fund_account_id", "fund_account_code", "fund_account_name", "parent_code", "account_type", "balance", "remark", "is_system", "status"),
                List.of("fund_account_name", "parent_code", "account_type", "balance", "remark", "status"))),
        Map.entry("priceGroup", new MasterSpec("base_price_group", "price_group_id", "price_group_code",
                List.of("price_group_id", "price_group_code", "price_group_name", "enabled", "sort_order", "remark", "is_system", "status"),
                List.of("price_group_name", "sort_order", "remark")))
    );

    // ============================================================
    // 分页
    // ============================================================
    @PostMapping("/price-group/page")
    public ApiResponse<PageResult<Map<String, Object>>> priceGroupPage(@RequestBody PageRequest request) {
        MasterSpec spec = SPECS.get("priceGroup");
        String cols = String.join(", ", spec.allColumns());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT " + cols + " FROM " + spec.table() + " ORDER BY sort_order ASC, " + spec.codeCol() + " ASC");
        // 一次拿到 code -> 客户数
        Map<String, Integer> customerCountByCode = new java.util.HashMap<>();
        try {
            jdbcTemplate.queryForList(
                    "SELECT price_group_code, COUNT(*) AS cnt FROM base_customer WHERE price_group_code IS NOT NULL AND price_group_code <> '' GROUP BY price_group_code")
                    .forEach(r -> {
                        Object c = r.get("price_group_code"); if (c == null) c = r.get("PRICE_GROUP_CODE");
                        Object cnt = r.get("cnt"); if (cnt == null) cnt = r.get("CNT");
                        if (c != null && cnt != null) customerCountByCode.put(String.valueOf(c), ((Number) cnt).intValue());
                    });
        } catch (Exception e) {
            // base_customer 无 price_group_code 时（新库尚未迁移）静默跳过
        }
        // 价格组商品数：从 base_price_group_item 聚合（V5 起真实统计）
        Map<String, Integer> goodsCountByCode = new java.util.HashMap<>();
        try {
            jdbcTemplate.queryForList(
                    "SELECT price_group_code, COUNT(DISTINCT goods_code) AS cnt FROM base_price_group_item WHERE is_active = TRUE GROUP BY price_group_code")
                    .forEach(r -> {
                        Object c = r.get("price_group_code"); if (c == null) c = r.get("PRICE_GROUP_CODE");
                        Object cnt = r.get("cnt"); if (cnt == null) cnt = r.get("CNT");
                        if (c != null && cnt != null) goodsCountByCode.put(String.valueOf(c), ((Number) cnt).intValue());
                    });
        } catch (Exception e) { /* 新库尚未迁移到 V5 时静默 */ }
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = toCamel(r);
            String code = String.valueOf(row.getOrDefault("priceGroupCode", ""));
            row.put("customerCount", customerCountByCode.getOrDefault(code, 0));
            row.put("goodsCount", goodsCountByCode.getOrDefault(code, 0));
            mapped.add(row);
        }
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    /** 启用/停用价格组：仅切换 enabled，不改其它字段；停用时清空关联客户 price_group_code。 */
    @PostMapping("/price-group/enable")
    public ApiResponse<Map<String, Object>> priceGroupEnable(@RequestBody Map<String, Object> request) {
        String bizId = trimOrEmpty(request.get("bizId"));
        if (bizId.isEmpty()) bizId = trimOrEmpty(request.get("priceGroupCode"));
        if (bizId.isEmpty()) return ApiResponse.fail("400", "缺少 bizId");
        Object enabledRaw = request.get("enabled");
        boolean enabled = enabledRaw == null || Boolean.parseBoolean(String.valueOf(enabledRaw));
        int rows = jdbcTemplate.update(
                "UPDATE base_price_group SET enabled = ? WHERE price_group_code = ? OR price_group_id = ?",
                enabled, bizId, bizId);
        if (rows == 0) return ApiResponse.fail("404", "价格组不存在");
        // 停用时清空所有关联客户的 price_group_code
        int cleared = 0;
        if (!enabled) {
            try {
                cleared = jdbcTemplate.update(
                        "UPDATE base_customer SET price_group_code = NULL WHERE price_group_code = ?", bizId);
            } catch (Exception e) { cleared = 0; }
        }
        log("base.priceGroup", enabled ? "ENABLE" : "DISABLE", bizId, enabled ? "启用价格组" : "停用价格组");
        return ApiResponse.ok(GenericResult.row("priceGroupCode", bizId, "enabled", enabled, "clearedCustomers", cleared, "success", true));
    }

    /** 关联客户列表：门店编号、名称、业务员、渠道、片区 */
    @PostMapping("/price-group/customers")
    public ApiResponse<List<Map<String, Object>>> priceGroupCustomers(@RequestBody Map<String, Object> request) {
        String code = trimOrEmpty(request.get("priceGroupCode"));
        if (code.isEmpty()) code = trimOrEmpty(request.get("bizId"));
        if (code.isEmpty()) return ApiResponse.ok(new ArrayList<>());
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT customer_code, customer_name, salesman, channel_type, territory FROM base_customer WHERE price_group_code = ? ORDER BY customer_code ASC",
                    code);
        } catch (Exception e) {
            return ApiResponse.ok(new ArrayList<>());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(toCamel(r));
        return ApiResponse.ok(out);
    }

    @PostMapping("/counterparty/page")
    public ApiResponse<PageResult<Map<String, Object>>> counterpartyPage(@RequestBody PageRequest request) {
        return counterpartyPaged("counterparty", request);
    }

    @PostMapping("/counterparty-type/page")
    public ApiResponse<PageResult<Map<String, Object>>> counterpartyTypePage(@RequestBody PageRequest request) {
        return pageOf("counterpartyType", request);
    }

    /**
     * 往来单位树形数据：单位类型 → 往来单位
     */
    @PostMapping("/counterparty/tree")
    public ApiResponse<List<Map<String, Object>>> counterpartyTree() {
        List<Map<String, Object>> tree = new ArrayList<>();
        // 获取所有启用的单位类型
        List<Map<String, Object>> types = jdbcTemplate.queryForList(
                "SELECT type_code, type_name FROM base_counterparty_type WHERE status = 'NORMAL' ORDER BY sort_order ASC, type_code ASC");
        // 获取所有往来单位
        List<Map<String, Object>> parties = jdbcTemplate.queryForList(
                "SELECT counterparty_code, counterparty_name, counterparty_type, type_code, status FROM base_counterparty ORDER BY counterparty_code ASC");
        // 按 type_code 分组
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> p : parties) {
            String typeCode = p.get("TYPE_CODE") != null ? String.valueOf(p.get("TYPE_CODE"))
                    : p.get("type_code") != null ? String.valueOf(p.get("type_code"))
                    : String.valueOf(p.getOrDefault("COUNTERPARTY_TYPE", ""));
            grouped.computeIfAbsent(typeCode, k -> new ArrayList<>()).add(toCamel(p));
        }
        // 构建树
        for (Map<String, Object> t : types) {
            String typeCode = String.valueOf(t.get("TYPE_CODE"));
            String typeName = String.valueOf(t.get("TYPE_NAME"));
            Map<String, Object> typeNode = new LinkedHashMap<>();
            typeNode.put("code", typeCode);
            typeNode.put("name", typeName);
            typeNode.put("level", 1);
            typeNode.put("type", "type");
            typeNode.put("children", grouped.getOrDefault(typeCode, new ArrayList<>()).stream()
                    .map(p -> {
                        Map<String, Object> child = new LinkedHashMap<>(p);
                        child.put("level", 2);
                        child.put("type", "counterparty");
                        return child;
                    }).toList());
            tree.add(typeNode);
        }
        // 未分类的往来单位归入"其他"
        List<Map<String, Object>> other = new ArrayList<>();
        for (Map<String, Object> p : parties) {
            String typeCode = p.get("TYPE_CODE") != null ? String.valueOf(p.get("TYPE_CODE"))
                    : p.get("type_code") != null ? String.valueOf(p.get("type_code"))
                    : String.valueOf(p.getOrDefault("COUNTERPARTY_TYPE", ""));
            boolean exists = types.stream().anyMatch(t -> typeCode.equals(String.valueOf(t.get("TYPE_CODE"))));
            if (!exists && !typeCode.isEmpty()) {
                other.add(toCamel(p));
            }
        }
        if (!other.isEmpty()) {
            Map<String, Object> otherNode = new LinkedHashMap<>();
            otherNode.put("code", "__OTHER__");
            otherNode.put("name", "其他");
            otherNode.put("level", 1);
            otherNode.put("type", "type");
            otherNode.put("children", other.stream().map(p -> {
                Map<String, Object> child = new LinkedHashMap<>(p);
                child.put("level", 2);
                child.put("type", "counterparty");
                return child;
            }).toList());
            tree.add(otherNode);
        }
        return ApiResponse.ok(tree);
    }

    /**
     * 往来单位分页（支持按 type_code / treeNode 过滤）
     */
    private ApiResponse<PageResult<Map<String, Object>>> counterpartyPaged(String moduleCode, PageRequest request) {
        MasterSpec spec = SPECS.get(moduleCode);
        if (spec == null) return ApiResponse.ok(new PageResult<>(List.of(), 1, 20, 0, Map.of()));

        String treeNode = request.filters() != null
                ? String.valueOf(request.filters().getOrDefault("treeNode", "")).trim()
                : "";

        StringBuilder sql = new StringBuilder("SELECT * FROM " + spec.table());
        List<Object> params = new ArrayList<>();

        // 点击类型节点：筛选该类型下的往来单位
        if (!treeNode.isEmpty() && !"全部".equals(treeNode) && !"__OTHER__".equals(treeNode)) {
            sql.append(" WHERE (counterparty_type = ? OR type_code = ?)");
            params.add(treeNode);
            params.add(treeNode);
        } else if ("__OTHER__".equals(treeNode)) {
            sql.append(" WHERE counterparty_type NOT IN (SELECT type_code FROM base_counterparty_type WHERE status = 'NORMAL')");
        }
        sql.append(" ORDER BY ").append(spec.codeCol()).append(" ASC");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) mapped.add(toCamel(r));

        // 补齐类型名称
        Map<String, String> typeNames = new LinkedHashMap<>();
        jdbcTemplate.queryForList("SELECT type_code, type_name FROM base_counterparty_type")
                .forEach(t -> typeNames.put(String.valueOf(t.get("TYPE_CODE")), String.valueOf(t.get("TYPE_NAME"))));
        for (Map<String, Object> r : mapped) {
            String tc = String.valueOf(r.getOrDefault("typeCode", r.getOrDefault("counterpartyType", "")));
            if (tc.isEmpty()) tc = String.valueOf(r.getOrDefault("type_code", ""));
            if (typeNames.containsKey(tc)) {
                r.put("counterpartyType", typeNames.get(tc));
                r.put("typeCode", tc);
            }
        }

        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    @PostMapping("/fund-account/page")
    public ApiResponse<PageResult<Map<String, Object>>> fundAccountPage(@RequestBody PageRequest request) { return pageOf("fundAccount", request); }

    @PostMapping("/expense-type/page")
    public ApiResponse<PageResult<Map<String, Object>>> expenseTypePage(@RequestBody PageRequest request) { return pageOf("expenseType", request); }

    @PostMapping("/territory/page")
    public ApiResponse<PageResult<Map<String, Object>>> territoryPage(@RequestBody PageRequest request) { return pageOf("territory", request); }

    @PostMapping("/route-line/page")
    public ApiResponse<PageResult<Map<String, Object>>> routeLinePage(@RequestBody PageRequest request) { return pageOf("routeLine", request); }

    @PostMapping("/employee/page")
    public ApiResponse<PageResult<Map<String, Object>>> employeePage(@RequestBody PageRequest request) { return pageOf("employee", request); }

    @PostMapping("/department/page")
    public ApiResponse<PageResult<Map<String, Object>>> departmentPage(@RequestBody PageRequest request) { return pageOf("department", request); }

    @PostMapping("/owner/page")
    public ApiResponse<PageResult<Map<String, Object>>> ownerPage(@RequestBody PageRequest request) { return pageOf("owner", request); }

    private ApiResponse<PageResult<Map<String, Object>>> pageOf(String moduleCode, PageRequest request) {
        MasterSpec spec = SPECS.get(moduleCode);
        if (spec == null) return ApiResponse.ok(new PageResult<>(List.of(), 1, 20, 0, Map.of()));
        // 用 SPECS 里声明的显式列名，避免 SELECT * 因表加列不同步遗漏字段
        String cols = String.join(", ", spec.allColumns());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT " + cols + " FROM " + spec.table() + " ORDER BY " + spec.codeCol() + " ASC");
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) mapped.add(toCamel(r));
        // 前端通用过滤/分页由 PageResult.of 兜底
        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    // ============================================================
    // 保存（新增/更新）
    // ============================================================
    @PostMapping("/save")
    public ApiResponse<Map<String, Object>> save(@RequestBody Map<String, Object> request) {
        String moduleCode = String.valueOf(request.getOrDefault("moduleCode", "base.master"));
        if ("customer".equals(moduleCode)) return saveCustomer(request);
        if ("supplier".equals(moduleCode)) return saveSupplier(request);
        MasterSpec spec = SPECS.get(moduleCode);
        if (spec == null) return ApiResponse.ok(GenericResult.operation(moduleCode, "SAVE"));

        String codeCamel = toCamelCase(spec.codeCol());
        String idCamel = toCamelCase(spec.idCol());
        String code = trimOrEmpty(request.get(codeCamel));
        String id = trimOrEmpty(request.get(idCamel));
        // 前端 mode（'add' / 'edit'）显式传入时优先按此判定；未传则退化为按 id 存在与否判断
        String mode = trimOrEmpty(request.get("mode"));
        boolean isEditIntent = "edit".equalsIgnoreCase(mode) || !id.isEmpty();
        // 线路：新建时必须显式指定编码，不再自动生成（按业务要求编码由用户维护）
        if ("routeLine".equals(moduleCode) && !isEditIntent && code.isEmpty()) {
            return ApiResponse.fail("400", "线路编码不能为空");
        }
        // 无 code：用 id 或自动生成
        if (code.isEmpty()) {
            code = id.isEmpty() ? autoCode(moduleCode) : id;
        }

        // 查是否存在（按 code，兼容 id 命中）
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + spec.table() + " WHERE " + spec.codeCol() + " = ?",
                Integer.class, code);
        boolean exists = count != null && count > 0;

        // 新建模式下如果编码已存在，视为冲突，防止「新增」意外覆盖同码记录
        if (exists && !isEditIntent) {
            return ApiResponse.fail("400", "编码 " + code + " 已存在");
        }

        // 价格组：不允许新建（系统预置 10 条配送价，只可编辑）
        if ("priceGroup".equals(moduleCode) && !isEditIntent) {
            return ApiResponse.fail("400", "价格组不允许新建，请使用系统预置的 10 条配送价");
        }

        // 资金账户：新建时上级账户必填（一级账户为系统内置，不允许通过页面新建同级）
        if ("fundAccount".equals(moduleCode) && !isEditIntent) {
            String parent = trimOrEmpty(request.get("parentCode"));
            if (parent.isEmpty()) {
                return ApiResponse.fail("400", "上级账户不能为空");
            }
            // 校验 parent 存在
            Integer pc = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + spec.table() + " WHERE " + spec.codeCol() + " = ?",
                    Integer.class, parent);
            if (pc == null || pc == 0) {
                return ApiResponse.fail("400", "上级账户 " + parent + " 不存在");
            }
        }

        if (exists) {
            // 系统默认记录不允许通过 save 修改；priceGroup 例外（允许改名/排序/备注，enabled 走独立端点）
            if (!"priceGroup".equals(moduleCode)) {
                rejectIfSystem(moduleCode, code, "编辑");
            }
            // 更新
            List<String> setCols = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            for (String col : spec.nonKeyColumns()) {
                String key = toCamelCase(col);
                if (request.containsKey(key)) {
                    setCols.add(col + " = ?");
                    args.add(coerce(col, request.get(key)));
                }
            }
            if (!setCols.isEmpty()) {
                args.add(code);
                jdbcTemplate.update("UPDATE " + spec.table() + " SET " + String.join(", ", setCols)
                        + " WHERE " + spec.codeCol() + " = ?", args.toArray());
            }
            log("base." + moduleCode, "UPDATE", code, "更新" + moduleCode);
        } else {
            // 插入
            if (id.isEmpty()) id = genId(moduleCode);
            List<String> cols = new ArrayList<>(spec.allColumns());
            List<Object> vals = new ArrayList<>();
            for (String col : cols) {
                if (col.equals(spec.idCol())) { vals.add(id); continue; }
                if (col.equals(spec.codeCol())) { vals.add(code); continue; }
                // created_at 交给 DB 默认（CURRENT_TIMESTAMP），当前时间戳显式写入避免依赖数据库设置漂移
                if (col.equals("created_at")) { vals.add(new java.sql.Timestamp(System.currentTimeMillis())); continue; }
                // creator_name：优先取 request，否则用当前登录用户名（暂用"系统管理员"占位，与 log() 保持一致）
                if (col.equals("creator_name")) {
                    String creator = trimOrEmpty(request.get("creatorName"));
                    if (creator.isEmpty()) creator = "系统管理员";
                    vals.add(creator);
                    continue;
                }
                String key = toCamelCase(col);
                Object raw = request.get(key);
                if (raw == null && col.equals("status")) raw = "NORMAL";
                vals.add(coerce(col, raw));
            }
            String placeholders = String.join(", ", java.util.Collections.nCopies(cols.size(), "?"));
            jdbcTemplate.update("INSERT INTO " + spec.table() + " (" + String.join(", ", cols) + ") VALUES (" + placeholders + ")",
                    vals.toArray());
            log("base." + moduleCode, "CREATE", code, "新建" + moduleCode);
        }
        // 往来单位子表同步（银行账户 / 开票信息），有则覆盖式重写
        if ("counterparty".equals(moduleCode)) {
            syncCounterpartyChildren(code, request);
        }
        return ApiResponse.ok(GenericResult.row(codeCamel, code, "success", true));
    }

    /** 覆盖式同步 counterparty 的银行账户与开票信息子表 */
    @SuppressWarnings("unchecked")
    private void syncCounterpartyChildren(String code, Map<String, Object> request) {
        // bank_accounts
        Object banks = request.get("bankAccounts");
        if (banks instanceof List<?> bankList) {
            jdbcTemplate.update("DELETE FROM base_counterparty_bank_account WHERE counterparty_code = ?", code);
            for (Object item : bankList) {
                if (!(item instanceof Map<?, ?> m)) continue;
                Map<String, Object> row = (Map<String, Object>) m;
                if (trimOrEmpty(row.get("bankAccountNo")).isEmpty() && trimOrEmpty(row.get("accountName")).isEmpty()) continue;
                jdbcTemplate.update(
                        "INSERT INTO base_counterparty_bank_account " +
                        "(bank_account_id, counterparty_code, account_name, bank_name, bank_account_no, branch_name, is_default, remark, status) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)",
                        "BA" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                        code,
                        trimOrEmpty(row.get("accountName")),
                        trimOrEmpty(row.get("bankName")),
                        trimOrEmpty(row.get("bankAccountNo")),
                        trimOrEmpty(row.get("branchName")),
                        parseBool(row.get("isDefault")) ? 1 : 0,
                        trimOrEmpty(row.get("remark")),
                        "NORMAL");
            }
        }
        // invoice_infos
        Object invs = request.get("invoiceInfos");
        if (invs instanceof List<?> invList) {
            jdbcTemplate.update("DELETE FROM base_counterparty_invoice_info WHERE counterparty_code = ?", code);
            for (Object item : invList) {
                if (!(item instanceof Map<?, ?> m)) continue;
                Map<String, Object> row = (Map<String, Object>) m;
                if (trimOrEmpty(row.get("invoiceTitle")).isEmpty() && trimOrEmpty(row.get("taxNo")).isEmpty()) continue;
                jdbcTemplate.update(
                        "INSERT INTO base_counterparty_invoice_info " +
                        "(invoice_info_id, counterparty_code, invoice_title, tax_no, bank_name, bank_account_no, address, phone, is_default, status) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)",
                        "II" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                        code,
                        trimOrEmpty(row.get("invoiceTitle")),
                        trimOrEmpty(row.get("taxNo")),
                        trimOrEmpty(row.get("bankName")),
                        trimOrEmpty(row.get("bankAccountNo")),
                        trimOrEmpty(row.get("address")),
                        trimOrEmpty(row.get("phone")),
                        parseBool(row.get("isDefault")) ? 1 : 0,
                        "NORMAL");
            }
        }
    }

    private static boolean parseBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return !(s.isEmpty() || "false".equals(s) || "0".equals(s) || "no".equals(s) || "否".equals(s));
    }

    /** 详情：主档 + 子表 */
    @PostMapping("/counterparty/detail")
    public ApiResponse<Map<String, Object>> counterpartyDetail(@RequestBody Map<String, Object> request) {
        String code = trimOrEmpty(request.get("counterpartyCode"));
        if (code.isEmpty()) code = trimOrEmpty(request.get("bizId"));
        if (code.isEmpty()) throw new IllegalArgumentException("缺少 counterpartyCode");
        List<Map<String, Object>> main = jdbcTemplate.queryForList(
                "SELECT counterparty_id, counterparty_code, counterparty_name, counterparty_type, type_code, contact_name, phone, remark, status " +
                "FROM base_counterparty WHERE counterparty_code = ?", code);
        if (main.isEmpty()) return ApiResponse.fail("404", "往来单位不存在");
        Map<String, Object> out = toCamel(main.get(0));
        List<Map<String, Object>> banks = jdbcTemplate.queryForList(
                "SELECT bank_account_id, account_name, bank_name, bank_account_no, branch_name, is_default, remark, status " +
                "FROM base_counterparty_bank_account WHERE counterparty_code = ? ORDER BY is_default DESC, bank_account_id ASC", code);
        List<Map<String, Object>> invs = jdbcTemplate.queryForList(
                "SELECT invoice_info_id, invoice_title, tax_no, bank_name, bank_account_no, address, phone, is_default, status " +
                "FROM base_counterparty_invoice_info WHERE counterparty_code = ? ORDER BY is_default DESC, invoice_info_id ASC", code);
        out.put("bankAccounts", banks.stream().map(BaseMasterController::toCamel).toList());
        out.put("invoiceInfos", invs.stream().map(BaseMasterController::toCamel).toList());
        return ApiResponse.ok(out);
    }

    /**
     * 通用主档导入
     * body: { moduleCode, rows: [ {...camelCase 字段...} ] }
     * 按 SPECS 元数据识别列，编码冲突则跳过（不覆盖），返回 inserted / skipped。
     * 仅覆盖 SPECS 声明的模块（territory / routeLine / department / expenseType /
     *   employee / owner / counterparty / counterpartyType / fundAccount / priceGroup）
     * 客户/供应商由各自 controller 自行导入（不在本端点范围内）。
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> masterImport(@RequestBody Map<String, Object> request) {
        String moduleCode = trimOrEmpty(request.get("moduleCode"));
        Object rowsObj = request.get("rows");
        if (!(rowsObj instanceof List<?> rows)) return ApiResponse.fail("400", "缺少 rows");
        MasterSpec spec = SPECS.get(moduleCode);
        if (spec == null) return ApiResponse.fail("400", "不支持的模块：" + moduleCode);
        String codeCamel = toCamelCase(spec.codeCol());
        String nameCamel = toCamelCase(spec.allColumns().stream()
                .filter(c -> c.endsWith("_name")).findFirst().orElse(spec.codeCol()));
        int inserted = 0, skipped = 0;
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> m)) { skipped++; continue; }
            Map<String, Object> r = (Map<String, Object>) m;
            String name = trimOrEmpty(r.get(nameCamel));
            if (name.isEmpty()) { skipped++; continue; }
            String code = trimOrEmpty(r.get(codeCamel));
            if (code.isEmpty()) code = autoCode(moduleCode);
            // 冲突跳过
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + spec.table() + " WHERE " + spec.codeCol() + " = ?",
                    Integer.class, code);
            if (exists != null && exists > 0) { skipped++; continue; }
            // 组装 insert
            List<String> cols = new ArrayList<>(spec.allColumns());
            List<Object> vals = new ArrayList<>();
            for (String col : cols) {
                if (col.equals(spec.idCol())) { vals.add(genId(moduleCode)); continue; }
                if (col.equals(spec.codeCol())) { vals.add(code); continue; }
                if (col.equals("created_at")) { vals.add(new java.sql.Timestamp(System.currentTimeMillis())); continue; }
                String key = toCamelCase(col);
                Object raw = r.get(key);
                if (raw == null && col.equals("status")) raw = "NORMAL";
                vals.add(coerce(col, raw));
            }
            String placeholders = String.join(", ", java.util.Collections.nCopies(cols.size(), "?"));
            jdbcTemplate.update("INSERT INTO " + spec.table() + " (" + String.join(", ", cols) + ") VALUES (" + placeholders + ")",
                    vals.toArray());
            inserted++;
        }
        log("base." + moduleCode, "IMPORT", moduleCode, "导入 " + moduleCode + " 共 " + inserted + " 条");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("moduleCode", moduleCode);
        resp.put("inserted", inserted);
        resp.put("skipped", skipped);
        return ApiResponse.ok(resp);
    }

    /**
     * 往来单位导入：kind=counterparty|bank|invoice
     * body: { kind, rows: [ {...}, ... ] }
     * 返回: { inserted, skipped }
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/counterparty/import")
    public ApiResponse<Map<String, Object>> counterpartyImport(@RequestBody Map<String, Object> request) {
        String kind = trimOrEmpty(request.get("kind"));
        Object rowsObj = request.get("rows");
        if (!(rowsObj instanceof List<?> rows)) return ApiResponse.fail("400", "缺少 rows");
        int inserted = 0, skipped = 0;
        if ("counterparty".equals(kind)) {
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> m)) { skipped++; continue; }
                Map<String, Object> r = (Map<String, Object>) m;
                String name = trimOrEmpty(r.get("counterpartyName"));
                if (name.isEmpty()) { skipped++; continue; }
                String code = trimOrEmpty(r.get("counterpartyCode"));
                if (code.isEmpty()) code = autoCode("counterparty");
                // 存在则跳过
                Integer exists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM base_counterparty WHERE counterparty_code = ?", Integer.class, code);
                if (exists != null && exists > 0) { skipped++; continue; }
                jdbcTemplate.update(
                        "INSERT INTO base_counterparty (counterparty_id, counterparty_code, counterparty_name, type_code, contact_name, phone, remark, status) " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                        "COU" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                        code, name,
                        trimOrEmpty(r.get("typeCode")),
                        trimOrEmpty(r.get("contactName")),
                        trimOrEmpty(r.get("phone")),
                        trimOrEmpty(r.get("remark")),
                        "NORMAL");
                inserted++;
            }
        } else if ("bank".equals(kind)) {
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> m)) { skipped++; continue; }
                Map<String, Object> r = (Map<String, Object>) m;
                String cp = trimOrEmpty(r.get("counterpartyCode"));
                String acc = trimOrEmpty(r.get("bankAccountNo"));
                if (cp.isEmpty() || acc.isEmpty()) { skipped++; continue; }
                Integer cpExists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM base_counterparty WHERE counterparty_code = ?", Integer.class, cp);
                if (cpExists == null || cpExists == 0) { skipped++; continue; }
                jdbcTemplate.update(
                        "INSERT INTO base_counterparty_bank_account " +
                        "(bank_account_id, counterparty_code, account_name, bank_name, bank_account_no, branch_name, is_default, remark, status) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)",
                        "BA" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                        cp,
                        trimOrEmpty(r.get("accountName")),
                        trimOrEmpty(r.get("bankName")),
                        acc,
                        trimOrEmpty(r.get("branchName")),
                        parseBool(r.get("isDefault")) ? 1 : 0,
                        trimOrEmpty(r.get("remark")),
                        "NORMAL");
                inserted++;
            }
        } else if ("invoice".equals(kind)) {
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> m)) { skipped++; continue; }
                Map<String, Object> r = (Map<String, Object>) m;
                String cp = trimOrEmpty(r.get("counterpartyCode"));
                String title = trimOrEmpty(r.get("invoiceTitle"));
                if (cp.isEmpty() || title.isEmpty()) { skipped++; continue; }
                Integer cpExists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM base_counterparty WHERE counterparty_code = ?", Integer.class, cp);
                if (cpExists == null || cpExists == 0) { skipped++; continue; }
                jdbcTemplate.update(
                        "INSERT INTO base_counterparty_invoice_info " +
                        "(invoice_info_id, counterparty_code, invoice_title, tax_no, bank_name, bank_account_no, address, phone, is_default, status) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)",
                        "II" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                        cp, title,
                        trimOrEmpty(r.get("taxNo")),
                        trimOrEmpty(r.get("bankName")),
                        trimOrEmpty(r.get("bankAccountNo")),
                        trimOrEmpty(r.get("address")),
                        trimOrEmpty(r.get("phone")),
                        parseBool(r.get("isDefault")) ? 1 : 0,
                        "NORMAL");
                inserted++;
            }
        } else {
            return ApiResponse.fail("400", "未知 kind：" + kind);
        }
        log("base.counterparty", "IMPORT", kind, "导入 " + kind + " 共 " + inserted + " 条");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("kind", kind);
        resp.put("inserted", inserted);
        resp.put("skipped", skipped);
        return ApiResponse.ok(resp);
    }

    // ============================================================
    // 停用 / 冻结 / 解冻 / 删除
    // ============================================================
    @PostMapping("/stop")
    public ApiResponse<Map<String, Object>> stop(@RequestBody Map<String, Object> request) {
        return updateStatus(request, "STOPPED", "STOP", "停用");
    }

    @PostMapping("/freeze")
    public ApiResponse<Map<String, Object>> freeze(@RequestBody Map<String, Object> request) {
        return updateStatus(request, "FROZEN", "FREEZE", "冻结");
    }

    @PostMapping("/unfreeze")
    public ApiResponse<Map<String, Object>> unfreeze(@RequestBody Map<String, Object> request) {
        return updateStatus(request, "NORMAL", "UNFREEZE", "解冻");
    }

    @PostMapping("/delete")
    public ApiResponse<Map<String, Object>> delete(@RequestBody Map<String, Object> request) {
        String moduleCode = String.valueOf(request.getOrDefault("moduleCode", "base.master"));
        String bizId = String.valueOf(request.getOrDefault("bizId", "")).trim();
        if (bizId.isEmpty()) throw new IllegalArgumentException("缺少 bizId");

        // 系统默认记录不允许删除
        rejectIfSystem(moduleCode, bizId, "删除");

        // customer / supplier 走原 service
        if ("customer".equals(moduleCode)) {
            boolean removed = customerService.remove(new QueryWrapper<BaseCustomer>().eq("customer_id", bizId).or().eq("customer_code", bizId));
            if (!removed) throw new IllegalArgumentException("客户不存在");
            log("base.customer", "DELETE", bizId, "删除客户");
            return ApiResponse.ok(GenericResult.operation(moduleCode, "DELETE"));
        }
        if ("supplier".equals(moduleCode)) {
            boolean removed = supplierService.remove(new QueryWrapper<BaseSupplier>().eq("supplier_id", bizId).or().eq("supplier_code", bizId));
            if (!removed) throw new IllegalArgumentException("供应商不存在");
            log("base.supplier", "DELETE", bizId, "删除供应商");
            return ApiResponse.ok(GenericResult.operation(moduleCode, "DELETE"));
        }

        MasterSpec spec = SPECS.get(moduleCode);
        if (spec == null) throw new IllegalArgumentException("未知模块: " + moduleCode);
        // 树形主档：有子级则拒绝删除
        if (spec.allColumns().contains("parent_code")) {
            Integer childCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + spec.table() + " WHERE parent_code = ?",
                    Integer.class, bizId);
            if (childCount != null && childCount > 0) {
                throw new IllegalArgumentException("存在下级记录，无法删除，请先删除下级");
            }
        }
        int rows = jdbcTemplate.update(
                "DELETE FROM " + spec.table() + " WHERE " + spec.codeCol() + " = ? OR " + spec.idCol() + " = ?",
                bizId, bizId);
        if (rows == 0) throw new IllegalArgumentException("基础资料不存在或删除失败");
        log("base." + moduleCode, "DELETE", bizId, "删除" + moduleCode);
        return ApiResponse.ok(GenericResult.operation(moduleCode, "DELETE"));
    }

    private ApiResponse<Map<String, Object>> updateStatus(Map<String, Object> request, String status, String action, String detail) {
        String moduleCode = String.valueOf(request.getOrDefault("moduleCode", "base.master"));
        String bizId = String.valueOf(request.getOrDefault("bizId", "")).trim();
        // 系统默认记录不允许停用/冻结/解冻
        rejectIfSystem(moduleCode, bizId, detail);
        boolean updated = false;
        if ("customer".equals(moduleCode)) {
            updated = customerService.update(new UpdateWrapper<BaseCustomer>()
                    .eq("customer_id", bizId).or().eq("customer_code", bizId)
                    .set("status", status));
        } else if ("supplier".equals(moduleCode)) {
            updated = supplierService.update(new UpdateWrapper<BaseSupplier>()
                    .eq("supplier_id", bizId).or().eq("supplier_code", bizId)
                    .set("status", status));
        } else {
            MasterSpec spec = SPECS.get(moduleCode);
            if (spec != null) {
                int rows = jdbcTemplate.update(
                        "UPDATE " + spec.table() + " SET status = ? WHERE " + spec.codeCol() + " = ? OR " + spec.idCol() + " = ?",
                        status, bizId, bizId);
                updated = rows > 0;
            }
        }
        if (!updated) throw new IllegalArgumentException("基础资料不存在，无法" + detail);
        log("base." + moduleCode, action, bizId, detail + "基础资料");
        return ApiResponse.ok(GenericResult.operation(moduleCode, action));
    }

    // ============================================================
    // 客户 / 供应商（保留旧实现）
    // ============================================================
    private ApiResponse<Map<String, Object>> saveCustomer(Map<String, Object> request) {
        String id = String.valueOf(request.getOrDefault("customerId", "CUS" + System.currentTimeMillis()));
        String code = String.valueOf(request.getOrDefault("customerCode", request.getOrDefault("code", id)));
        BaseCustomer existing = customerService.getOne(new QueryWrapper<BaseCustomer>().eq("customer_id", id).or().eq("customer_code", code));
        if (existing != null) {
            existing.setCustomerName((String) request.getOrDefault("customerName", request.getOrDefault("name", existing.getCustomerName())));
            existing.setChannelType((String) request.getOrDefault("channelType", existing.getChannelType()));
            existing.setContactName((String) request.getOrDefault("contactName", existing.getContactName()));
            existing.setMobile((String) request.getOrDefault("mobile", existing.getMobile()));
            existing.setTerritory((String) request.getOrDefault("territory", existing.getTerritory()));
            existing.setRouteLine((String) request.getOrDefault("routeLine", existing.getRouteLine()));
            existing.setSalesman((String) request.getOrDefault("salesman", existing.getSalesman()));
            existing.setCustomerLevel((String) request.getOrDefault("customerLevel", existing.getCustomerLevel()));
            existing.setAccountPeriodType((String) request.getOrDefault("accountPeriodType", existing.getAccountPeriodType()));
            existing.setCutoffDay((String) request.getOrDefault("cutoffDay", existing.getCutoffDay()));
            existing.setPaymentDay((String) request.getOrDefault("paymentDay", existing.getPaymentDay()));
            customerService.updateById(existing);
        } else {
            BaseCustomer entity = new BaseCustomer();
            entity.setCustomerId(id);
            entity.setCustomerCode(code);
            entity.setCustomerName((String) request.getOrDefault("customerName", request.getOrDefault("name", "新客户")));
            entity.setChannelType((String) request.getOrDefault("channelType", "零售商超"));
            entity.setContactName((String) request.getOrDefault("contactName", ""));
            entity.setMobile((String) request.getOrDefault("mobile", ""));
            entity.setTerritory((String) request.getOrDefault("territory", ""));
            entity.setRouteLine((String) request.getOrDefault("routeLine", ""));
            entity.setSalesman((String) request.getOrDefault("salesman", ""));
            entity.setCustomerLevel((String) request.getOrDefault("customerLevel", "普通"));
            entity.setAccountPeriodType((String) request.getOrDefault("accountPeriodType", "现结"));
            entity.setStatus("NORMAL");
            customerService.save(entity);
        }
        log("base.customer", "SAVE", code, "保存客户资料");
        return ApiResponse.ok(GenericResult.row("customerId", id, "customerCode", code, "success", true));
    }

    private ApiResponse<Map<String, Object>> saveSupplier(Map<String, Object> request) {
        String id = String.valueOf(request.getOrDefault("supplierId", "SUP" + System.currentTimeMillis()));
        String code = String.valueOf(request.getOrDefault("supplierCode", request.getOrDefault("code", id)));
        BaseSupplier existing = supplierService.getOne(new QueryWrapper<BaseSupplier>().eq("supplier_id", id).or().eq("supplier_code", code));
        if (existing != null) {
            existing.setSupplierName((String) request.getOrDefault("supplierName", request.getOrDefault("name", existing.getSupplierName())));
            existing.setShortName((String) request.getOrDefault("shortName", existing.getShortName()));
            existing.setSupplierType((String) request.getOrDefault("supplierType", existing.getSupplierType()));
            existing.setContactName((String) request.getOrDefault("contactName", existing.getContactName()));
            existing.setPhone((String) request.getOrDefault("phone", existing.getPhone()));
            existing.setSettlementMethod((String) request.getOrDefault("settlementMethod", existing.getSettlementMethod()));
            existing.setDefaultBuyer((String) request.getOrDefault("defaultBuyer", existing.getDefaultBuyer()));
            supplierService.updateById(existing);
        } else {
            BaseSupplier entity = new BaseSupplier();
            entity.setSupplierId(id);
            entity.setSupplierCode(code);
            entity.setSupplierName((String) request.getOrDefault("supplierName", request.getOrDefault("name", "新供应商")));
            entity.setShortName((String) request.getOrDefault("shortName", ""));
            entity.setSupplierType((String) request.getOrDefault("supplierType", "普通供应商"));
            entity.setContactName((String) request.getOrDefault("contactName", ""));
            entity.setPhone((String) request.getOrDefault("phone", ""));
            entity.setSettlementMethod((String) request.getOrDefault("settlementMethod", "现结"));
            entity.setStatus("NORMAL");
            supplierService.save(entity);
        }
        log("base.supplier", "SAVE", code, "保存供应商资料");
        return ApiResponse.ok(GenericResult.row("supplierId", id, "supplierCode", code, "success", true));
    }

    // ============================================================
    // 工具方法
    // ============================================================
    private void log(String moduleCode, String action, String bizNo, String detail) {
        jdbcTemplate.update("""
                INSERT INTO sys_operation_log_runtime(log_id, operate_at, operator_name, module_code, action, biz_no, result, detail)
                VALUES (?, CURRENT_TIMESTAMP, '系统管理员', ?, ?, ?, 'SUCCESS', ?)
                """, "LOG" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(), moduleCode, action, bizNo, detail);
    }

    /** 下划线列名 → 驼峰 key */
    private static String toCamelCase(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    /** 若目标记录被标记为系统默认（is_system=true），拒绝对应操作。 */
    private void rejectIfSystem(String moduleCode, String bizId, String action) {
        if (bizId == null || bizId.isBlank()) return;
        MasterSpec spec = SPECS.get(moduleCode);
        if (spec == null || !spec.allColumns().contains("is_system")) return;
        try {
            java.util.List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT is_system FROM " + spec.table() + " WHERE "
                            + spec.codeCol() + " = ? OR " + spec.idCol() + " = ?",
                    bizId, bizId);
            for (Map<String, Object> r : rows) {
                Object v = r.get("IS_SYSTEM");
                if (v == null) v = r.get("is_system");
                if (v instanceof Boolean b && b) {
                    throw new IllegalArgumentException("系统默认记录不允许" + action);
                }
                if (v != null && "true".equalsIgnoreCase(String.valueOf(v))) {
                    throw new IllegalArgumentException("系统默认记录不允许" + action);
                }
            }
        } catch (org.springframework.dao.DataAccessException e) {
            // 表可能还没升级 is_system 列，忽略
        }
    }

    /** JDBC 返回的 Map（下划线列名）→ 前端可读的驼峰 Map。保留原下划线键做兜底。 */
    private static Map<String, Object> toCamel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String key = e.getKey();
            String lower = key.toLowerCase(Locale.ROOT);
            out.put(toCamelCase(lower), e.getValue());
        }
        return out;
    }

    private static String trimOrEmpty(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    /** 数值/布尔列的类型转换。 */
    private static Object coerce(String col, Object raw) {
        if (raw == null) return null;
        if (col.equals("head_count") || col.equals("sort_order")) {
            if (raw instanceof Number n) return n.intValue();
            String s = String.valueOf(raw).trim();
            try { return s.isEmpty() ? 0 : Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
        }
        if (col.equals("balance")) {
            if (raw instanceof Number n) return n.doubleValue();
            String s = String.valueOf(raw).trim();
            try { return s.isEmpty() ? 0.0 : Double.parseDouble(s); } catch (NumberFormatException e) { return 0.0; }
        }
        if (col.equals("enabled") || col.startsWith("is_")) {
            if (raw instanceof Boolean b) return b;
            String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) return false;
            return !("false".equals(s) || "0".equals(s) || "no".equals(s) || "n".equals(s) || "否".equals(s) || "停用".equals(s));
        }
        return String.valueOf(raw);
    }

    private static String genId(String moduleCode) {
        return moduleCode.toUpperCase(Locale.ROOT).substring(0, Math.min(3, moduleCode.length()))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private String autoCode(String moduleCode) {
        Map<String, String> prefix = Map.of(
                "territory", "T", "routeLine", "R", "department", "D", "expenseType", "FY",
                "employee", "E", "owner", "H", "counterparty", "WL", "counterpartyType", "CT", "fundAccount", "A", "priceGroup", "PG"
        );
        String p = prefix.getOrDefault(moduleCode, "M");
        // 人员 / 货主 按 PRD 规则：字母 + 5 位流水号自动生成
        if ("employee".equals(moduleCode) || "owner".equals(moduleCode)) {
            MasterSpec spec = SPECS.get(moduleCode);
            if (spec != null) {
                Integer c = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + spec.table(), Integer.class);
                int next = (c == null ? 0 : c) + 1;
                for (int i = 0; i < 100; i++) {
                    String candidate = String.format("%s%05d", p, next + i);
                    Integer exists = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM " + spec.table() + " WHERE " + spec.codeCol() + " = ?",
                            Integer.class, candidate);
                    if (exists == null || exists == 0) return candidate;
                }
            }
        }
        // 6 位大写十六进制后缀，冲突概率远低于原先「timestamp%100000」
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return p + suffix;
    }
}
