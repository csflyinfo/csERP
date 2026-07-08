package com.erp.system;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.erp.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/excel")
public class ExcelController {

    private final JdbcTemplate jdbcTemplate;

    public ExcelController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 通用导出：根据模块编码查询数据并导出为 Excel
     */
    @PostMapping("/export/{moduleCode}")
    public void export(@PathVariable String moduleCode, @RequestBody Map<String, Object> params, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> data = queryData(moduleCode, params);
        String fileName = URLEncoder.encode(moduleCode + "_导出_" + System.currentTimeMillis() + ".xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + fileName);

        List<List<String>> head = buildHead(moduleCode);
        List<List<Object>> body = buildBody(data, moduleCode);

        EasyExcel.write(response.getOutputStream())
                .sheet(moduleCode)
                .head(head)
                .doWrite(body);
    }

    /**
     * 通用导入：上传 Excel 文件解析并入库
     */
    @PostMapping("/import/{moduleCode}")
    public ApiResponse<Map<String, Object>> importExcel(@PathVariable String moduleCode, @RequestParam("file") MultipartFile file,
                                                        @RequestParam(value = "taskName", required = false) String taskName) throws IOException {
        List<Map<Integer, String>> rows = new ArrayList<>();
        EasyExcel.read(file.getInputStream())
                .sheet()
                .doReadSync()
                .forEach(row -> {
                    Map<Integer, String> map = new LinkedHashMap<>();
                    for (int i = 0; i < ((List<?>) row).size(); i++) {
                        map.put(i, String.valueOf(((List<?>) row).get(i) != null ? ((List<?>) row).get(i) : ""));
                    }
                    rows.add(map);
                });

        if (rows.size() <= 1) {
            return ApiResponse.fail("400", "文件内容为空或只有表头");
        }

        // 跳过表头，写入数据库
        int success = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();

        for (int i = 1; i < rows.size(); i++) {
            Map<Integer, String> row = rows.get(i);
            try {
                insertRow(moduleCode, row);
                success++;
            } catch (Exception e) {
                failed++;
                failures.add("行" + (i + 1) + ": " + e.getMessage());
            }
        }

        // 记录导入任务
        String taskNo = "IMP" + System.currentTimeMillis();
        jdbcTemplate.update(
            "INSERT INTO sys_import_task_runtime(task_id, task_no, module_code, task_name, file_name, success_rows, failed_rows, status, result_text, created_at, finished_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'FINISHED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            "IMP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
            taskNo, moduleCode, taskName != null ? taskName : moduleCode + "导入", file.getOriginalFilename(), success, failed,
            failed > 0 ? "成功" + success + "行，失败" + failed + "行" : "导入完成，共" + success + "行"
        );

        return ApiResponse.ok(Map.of(
            "taskNo", taskNo,
            "successRows", success,
            "failedRows", failed,
            "totalRows", rows.size() - 1,
            "failures", failures,
            "message", "导入完成：成功" + success + "行，失败" + failed + "行"
        ));
    }

    /**
     * 下载导入模板
     */
    @GetMapping("/template/{moduleCode}")
    public void downloadTemplate(@PathVariable String moduleCode, HttpServletResponse response) throws IOException {
        String fileName = URLEncoder.encode(moduleCode + "_导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + fileName);

        List<List<String>> head = buildTemplateHead(moduleCode);
        List<List<Object>> demo = buildDemoRow(moduleCode);

        EasyExcel.write(response.getOutputStream())
                .sheet(moduleCode)
                .head(head)
                .doWrite(demo);
    }

    // ========== 私有辅助方法 ==========

    private List<Map<String, Object>> queryData(String moduleCode, Map<String, Object> params) {
        String sql = switch (moduleCode) {
            case "goods" -> "SELECT goods_code, goods_name, spec, category_name, brand_name, base_unit, barcode, standard_price, latest_purchase_price, min_sale_price, goods_type, shelf_life_days, storage_property, suggested_retail_price, stock_upper_limit, stock_lower_limit, default_supplier, default_warehouse, status FROM base_goods ORDER BY goods_code";
            case "customer" -> "SELECT customer_code, customer_name, channel_type, contact_name, mobile, territory, route_line, salesman, customer_level, account_period_type, cutoff_day, payment_day, credit_limit, invoice_title, tax_no, status FROM base_customer ORDER BY customer_code";
            case "supplier" -> "SELECT supplier_code, supplier_name, short_name, supplier_type, contact_name, phone, delivery_days, settlement_method, account_period_days, invoice_title, tax_no, status FROM base_supplier ORDER BY supplier_code";
            case "warehouse" -> "SELECT warehouse_code, warehouse_name, warehouse_type, inventory_type, cost_group, manager_name, status FROM base_warehouse ORDER BY warehouse_code";
            case "purchaseOrder" -> "SELECT order_no, supplier, buyer, warehouse, bill_date, amount, inbound_amount, payment_status, arrival_status, status FROM pur_order ORDER BY order_no DESC";
            case "salesOrder" -> "SELECT order_no, customer, salesman, warehouse, bill_date, amount, paid_amount, unpaid_amount, outbound_status, sign_status, status FROM sales_order ORDER BY order_no DESC";
            case "expenseType" -> "SELECT expense_type_code, expense_type_name, parent_code, direction, cost_participation, status FROM base_expense_type ORDER BY COALESCE(parent_code, ''), expense_type_code";
            default -> "SELECT '示例数据' as demo";
        };
        return jdbcTemplate.queryForList(sql);
    }

    private List<List<String>> buildHead(String moduleCode) {
        return switch (moduleCode) {
            case "goods" -> List.of(List.of("商品编码"), List.of("商品名称"), List.of("规格"), List.of("分类"), List.of("品牌"), List.of("基本单位"), List.of("条码"), List.of("标准售价"), List.of("参考进价"), List.of("最低售价"), List.of("商品类型"), List.of("保质期(天)"), List.of("存储属性"), List.of("建议零售价"), List.of("库存上限"), List.of("库存下限"), List.of("默认供应商"), List.of("默认仓库"), List.of("状态"));
            case "customer" -> List.of(List.of("客户编码"), List.of("客户名称"), List.of("渠道类型"), List.of("联系人"), List.of("手机号"), List.of("片区"), List.of("线路"), List.of("业务员"), List.of("客户等级"), List.of("账期类型"), List.of("截账日"), List.of("付款日"), List.of("信用额度"), List.of("发票抬头"), List.of("税号"), List.of("状态"));
            case "supplier" -> List.of(List.of("供应商编码"), List.of("供应商名称"), List.of("简称"), List.of("类型"), List.of("联系人"), List.of("电话"), List.of("到货天数"), List.of("结算方式"), List.of("账期天数"), List.of("发票抬头"), List.of("税号"), List.of("状态"));
            case "warehouse" -> List.of(List.of("仓库编码"), List.of("仓库名称"), List.of("仓库类型"), List.of("存货类型"), List.of("成本组"), List.of("负责人"), List.of("状态"));
            case "purchaseOrder" -> List.of(List.of("采购单号"), List.of("供应商"), List.of("采购员"), List.of("仓库"), List.of("单据日期"), List.of("订单金额"), List.of("入库金额"), List.of("付款状态"), List.of("到货状态"), List.of("状态"));
            case "salesOrder" -> List.of(List.of("销售单号"), List.of("客户"), List.of("业务员"), List.of("仓库"), List.of("单据日期"), List.of("订单金额"), List.of("已收金额"), List.of("未收金额"), List.of("出库状态"), List.of("签收状态"), List.of("状态"));
            case "expenseType" -> List.of(List.of("费用类型编码"), List.of("费用类型名称"), List.of("上级费用类型"), List.of("费用方向"), List.of("成本参与"), List.of("状态"));
            default -> List.of(List.of("字段1"), List.of("字段2"), List.of("字段3"));
        };
    }

    private List<List<Object>> buildBody(List<Map<String, Object>> data, String moduleCode) {
        // 费用类型导出需要把 parent_code 翻译为上级名称，先构建 code -> name 索引
        Map<String, String> expenseNameMap = new HashMap<>();
        if ("expenseType".equals(moduleCode)) {
            jdbcTemplate.queryForList("SELECT expense_type_code, expense_type_name FROM base_expense_type")
                    .forEach(r -> expenseNameMap.put(String.valueOf(r.get("expense_type_code")), String.valueOf(r.get("expense_type_name"))));
        }
        List<List<Object>> result = new ArrayList<>();
        for (Map<String, Object> row : data) {
            result.add(switch (moduleCode) {
                case "goods" -> List.of(row.get("goods_code"), row.get("goods_name"), row.get("spec"), row.get("category_name"), row.get("brand_name"), row.get("base_unit"), row.get("barcode"), row.get("standard_price"), row.get("latest_purchase_price"), row.get("min_sale_price"), row.get("goods_type"), row.get("shelf_life_days"), row.get("storage_property"), row.get("suggested_retail_price"), row.get("stock_upper_limit"), row.get("stock_lower_limit"), row.get("default_supplier"), row.get("default_warehouse"), row.get("status"));
                case "customer" -> List.of(row.get("customer_code"), row.get("customer_name"), row.get("channel_type"), row.get("contact_name"), row.get("mobile"), row.get("territory"), row.get("route_line"), row.get("salesman"), row.get("customer_level"), row.get("account_period_type"), row.get("cutoff_day"), row.get("payment_day"), row.get("credit_limit"), row.get("invoice_title"), row.get("tax_no"), row.get("status"));
                case "supplier" -> List.of(row.get("supplier_code"), row.get("supplier_name"), row.get("short_name"), row.get("supplier_type"), row.get("contact_name"), row.get("phone"), row.get("delivery_days"), row.get("settlement_method"), row.get("account_period_days"), row.get("invoice_title"), row.get("tax_no"), row.get("status"));
                case "warehouse" -> List.of(row.get("warehouse_code"), row.get("warehouse_name"), row.get("warehouse_type"), row.get("inventory_type"), row.get("cost_group"), row.get("manager_name"), row.get("status"));
                case "purchaseOrder" -> List.of(row.get("order_no"), row.get("supplier"), row.get("buyer"), row.get("warehouse"), row.get("bill_date"), row.get("amount"), row.get("inbound_amount"), row.get("payment_status"), row.get("arrival_status"), row.get("status"));
                case "salesOrder" -> List.of(row.get("order_no"), row.get("customer"), row.get("salesman"), row.get("warehouse"), row.get("bill_date"), row.get("amount"), row.get("paid_amount"), row.get("unpaid_amount"), row.get("outbound_status"), row.get("sign_status"), row.get("status"));
                case "expenseType" -> {
                    String parentCode = row.get("parent_code") == null ? "" : String.valueOf(row.get("parent_code"));
                    String parentName = parentCode.isEmpty() ? "" : expenseNameMap.getOrDefault(parentCode, parentCode);
                    yield Arrays.asList(
                            row.get("expense_type_code"),
                            row.get("expense_type_name"),
                            parentName,
                            row.get("direction"),
                            row.get("cost_participation"),
                            statusText(row.get("status")));
                }
                default -> List.of(row.values().toArray());
            });
        }
        return result;
    }

    private static String statusText(Object status) {
        if (status == null) return "";
        return switch (String.valueOf(status)) {
            case "NORMAL", "ACTIVE" -> "正常";
            case "STOPPED", "INACTIVE" -> "停用";
            case "FROZEN" -> "冻结";
            default -> String.valueOf(status);
        };
    }

    /**
     * 导入模板表头：与列表展示字段解耦，仅列出必要的可导入列。
     * 用户下载模板 → 填数据 → 上传，与导出的字段口径可能不同。
     */
    private List<List<String>> buildTemplateHead(String moduleCode) {
        return switch (moduleCode) {
            case "expenseType" -> List.of(List.of("类型编号"), List.of("类型名称"), List.of("上级编号"), List.of("方向"));
            // 其它模块暂沿用导出表头
            default -> buildHead(moduleCode);
        };
    }

    private List<List<Object>> buildDemoRow(String moduleCode) {
        return switch (moduleCode) {
            case "goods" -> List.of(List.of("SP003", "测试商品", "500ml*12", "饮料", "测试品牌", "箱", "690000000003", 50.00, 42.00, 40.00, "正常商品", 365, "常温", 55.00, 1000, 100, "测试供应商", "总仓", "NORMAL"));
            case "customer" -> List.of(List.of("C002", "测试客户", "零售商超", "李经理", "13800000001", "西湖区", "朝阳线", "张三", "普通", "月结", "25日", "次月5日", 30000.00, "测试公司", "9133****", "NORMAL"));
            case "supplier" -> List.of(List.of("G002", "测试供应商", "测试简称", "普通供应商", "王经理", "0571-8888", 3, "月结30天", 30, "测试公司", "9133****", "NORMAL"));
            case "warehouse" -> List.of(List.of("W003", "测试仓库", "正常仓", "平台主仓", "CG01", "赵六", "NORMAL"));
            case "expenseType" -> List.of(
                    List.of("FY001", "运输费", "", "支出"),
                    List.of("FY001-01", "长途运输费", "FY001", "支出")
            );
            default -> List.of(List.of("示例值1", "示例值2", "示例值3"));
        };
    }

    private void insertRow(String moduleCode, Map<Integer, String> row) {
        switch (moduleCode) {
            case "goods" -> jdbcTemplate.update(
                "INSERT INTO base_goods(goods_id, goods_code, goods_name, spec, category_name, brand_name, base_unit, barcode, standard_price, latest_purchase_price, min_sale_price, goods_type, shelf_life_days, storage_property, suggested_retail_price, stock_upper_limit, stock_lower_limit, default_supplier, default_warehouse, can_return, current_stock, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, 0, 'NORMAL')",
                "G" + System.currentTimeMillis(), row.get(0), row.get(1), row.get(2), row.get(3), row.get(4), row.get(5), row.get(6), parseDecimal(row.get(7)), parseDecimal(row.get(8)), parseDecimal(row.get(9)), row.get(10), parseInt(row.get(11)), row.get(12), parseDecimal(row.get(13)), parseDecimal(row.get(14)), parseDecimal(row.get(15)), row.get(16), row.get(17)
            );
            case "customer" -> jdbcTemplate.update(
                "INSERT INTO base_customer(customer_id, customer_code, customer_name, channel_type, contact_name, mobile, territory, route_line, salesman, customer_level, account_period_type, cutoff_day, payment_day, credit_limit, invoice_title, tax_no, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NORMAL')",
                "C" + System.currentTimeMillis(), row.get(0), row.get(1), row.get(2), row.get(3), row.get(4), row.get(5), row.get(6), row.get(7), row.get(8), row.get(9), row.get(10), row.get(11), parseDecimal(row.get(12)), row.get(13), row.get(14)
            );
            case "supplier" -> jdbcTemplate.update(
                "INSERT INTO base_supplier(supplier_id, supplier_code, supplier_name, short_name, supplier_type, contact_name, phone, delivery_days, settlement_method, account_period_days, invoice_title, tax_no, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NORMAL')",
                "S" + System.currentTimeMillis(), row.get(0), row.get(1), row.get(2), row.get(3), row.get(4), row.get(5), parseInt(row.get(6)), row.get(7), parseInt(row.get(8)), row.get(9), row.get(10)
            );
            case "warehouse" -> jdbcTemplate.update(
                "INSERT INTO base_warehouse(warehouse_id, warehouse_code, warehouse_name, warehouse_type, inventory_type, cost_group, manager_name, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'NORMAL')",
                "WH" + System.currentTimeMillis(), row.get(0), row.get(1), row.get(2), row.get(3), row.get(4), row.get(5)
            );
            case "expenseType" -> insertExpenseTypeRow(row);
            default -> throw new IllegalArgumentException("暂不支持的导入模块：" + moduleCode);
        }
    }

    private java.math.BigDecimal parseDecimal(String val) {
        try { return new java.math.BigDecimal(val); } catch (Exception e) { return java.math.BigDecimal.ZERO; }
    }

    private Integer parseInt(String val) {
        try { return Integer.parseInt(val); } catch (Exception e) { return 0; }
    }

    /**
     * 费用类型导入行处理：
     *   列顺序：类型编号(0) | 类型名称(1) | 上级编号(2) | 方向(3)
     * 规则：
     *   - 类型名称必填；上级编号为空视为一级；上级编号非空必须存在于 base_expense_type。
     *   - 类型名称在全表内不可重复。
     *   - 类型编号为空时按上级前缀自动生成 (一级 FY + 3 位序号，二级及以下 上级编号 + '-' + 2 位序号)。
     *   - 方向仅接受 收入/支出，为空默认 支出。
     */
    private void insertExpenseTypeRow(Map<Integer, String> row) {
        String code = trim(row.get(0));
        String name = trim(row.get(1));
        String parentCode = trim(row.get(2));
        String direction = trim(row.get(3));

        if (name.isEmpty()) throw new IllegalArgumentException("类型名称不能为空");

        // 方向校验
        if (direction.isEmpty()) direction = "支出";
        if (!"收入".equals(direction) && !"支出".equals(direction)) {
            throw new IllegalArgumentException("方向仅支持 收入/支出，当前值：" + direction);
        }

        // 上级存在性校验
        if (!parentCode.isEmpty()) {
            Integer pc = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM base_expense_type WHERE expense_type_code = ?", Integer.class, parentCode);
            if (pc == null || pc == 0) {
                throw new IllegalArgumentException("上级编号 " + parentCode + " 不存在，请先建立上级类型");
            }
        }

        // 名称唯一性校验
        Integer nameCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM base_expense_type WHERE expense_type_name = ?", Integer.class, name);
        if (nameCount != null && nameCount > 0) {
            throw new IllegalArgumentException("类型名称 " + name + " 已存在，不可重复");
        }

        // 编码生成/校验
        if (code.isEmpty()) {
            code = nextExpenseTypeCode(parentCode);
        } else {
            Integer codeCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM base_expense_type WHERE expense_type_code = ?", Integer.class, code);
            if (codeCount != null && codeCount > 0) {
                throw new IllegalArgumentException("类型编号 " + code + " 已存在");
            }
        }

        String id = "ET" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbcTemplate.update(
                "INSERT INTO base_expense_type(expense_type_id, expense_type_code, expense_type_name, parent_code, direction, status) VALUES (?, ?, ?, ?, ?, 'NORMAL')",
                id, code, name, parentCode.isEmpty() ? null : parentCode, direction);
    }

    private String nextExpenseTypeCode(String parentCode) {
        if (parentCode == null || parentCode.isEmpty()) {
            // 一级：FY + 3 位流水
            for (int i = 1; i <= 999; i++) {
                String candidate = String.format("FY%03d", i);
                Integer c = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM base_expense_type WHERE expense_type_code = ?", Integer.class, candidate);
                if (c == null || c == 0) return candidate;
            }
            return "FY" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        }
        // 子级：上级编号-2 位流水
        for (int i = 1; i <= 99; i++) {
            String candidate = parentCode + "-" + String.format("%02d", i);
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM base_expense_type WHERE expense_type_code = ?", Integer.class, candidate);
            if (c == null || c == 0) return candidate;
        }
        return parentCode + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
