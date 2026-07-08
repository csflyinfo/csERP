package com.erp.testing;

import com.erp.common.api.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试数据清理端点 —— 供冒烟脚本 finally 收尾使用。
 *
 * <p><b>两个端点，作用范围差别很大，别选错</b>：
 * <ul>
 *   <li>{@code /cleanup-smoke}：<b>按单号前缀全表清</b>（CGDD/CGRK/CGSH/XSDD/XSCK/XSFH/JSRK…）。
 *       它会连带删掉<b>用户手工建的同前缀单据</b>——单号前缀是全局统一的，没法区分谁建的。
 *       跑之前务必先备份 {@code backend/data/erp-v1.mv.db}。</li>
 *   <li>{@code /cleanup-scoped}：<b>只删调用方点名的单号 / 编码</b>。新脚本优先用这个，
 *       跑在有真实数据的库上不会伤到别人的单据。</li>
 * </ul>
 *
 * <p><b>安全边界</b>：基础资料只清带明确测试前缀的记录（{@code GDT / GDS / WHT / WHS / CTT / CTS / SPT / SPS}）。
 * 用户手工建的资料（{@code G001} 等无测试前缀的记录）不受影响。
 *
 * <p><b>为什么不复用 /base/*\/delete</b>：那些端点是单条删除、且不清理关联的业务单据；
 * 冒烟脚本每次会产生一条完整链（订单 → 入库 → 收货 → 应付），需要按外键顺序一次性清空。
 *
 * <p>如需完全禁用，把 {@link com.erp.common.config.SecurityConfig} 的白名单里加
 * 排除或加个开关（V1.0 暂未加，默认已由 JWT 校验保护）。
 */
@RestController
@RequestMapping("/testing")
public class TestingController {

    private final JdbcTemplate jdbcTemplate;

    public TestingController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 清理本次冒烟测试链路产生的所有数据。可重复调用（幂等）。
     *
     * <p><b>⚠️ 按前缀全表清</b>：单号前缀（CGDD/CGRK/CGSH/XSDD/XSCK/XSFH/JSRK）是全局统一的，
     * 所以<b>用户手工建的同前缀单据也会一起被删</b>。在有真实数据的库上跑之前先备份
     * {@code backend/data/erp-v1.mv.db}；新脚本请改用 {@link #cleanupScoped}。
     *
     * <p>清理顺序（先业务单据、后基础资料，避免外键麻烦）：
     * <ol>
     *   <li>库存流水 / 批次库存 / 库存余额（按 GDT/GDS 前缀 goods_code）</li>
     *   <li>应付 / 应收（按 source_bill 前缀 PR/SR）</li>
     *   <li>采购收货单 / 采购入库单 / 采购订单（按前缀 PR/PI/PO）</li>
     *   <li>销售发货单 / 销售出库单 / 销售订单（按前缀 SR/SOU/SO），以及拒收入库单（JSRK）</li>
     *   <li>基础资料：商品/仓库/客户/供应商（按测试前缀 GDT GDS WHT WHS CTT CTS SPT SPS）</li>
     *   <li>相关操作日志</li>
     * </ol>
     *
     * @return 每张表被删除的行数，方便脚本断言清理效果
     */
    @PostMapping("/cleanup-smoke")
    @Transactional
    public ApiResponse<Map<String, Integer>> cleanupSmoke() {
        Map<String, Integer> counts = new LinkedHashMap<>();

        // 1. 库存 3 表 —— 按 GDT/GDS 前缀 goods_code
        counts.put("inv_stock_ledger", jdbcTemplate.update(
                "DELETE FROM inv_stock_ledger WHERE goods_code LIKE 'GDT%' OR goods_code LIKE 'GDS%'"));
        counts.put("inv_batch_stock", jdbcTemplate.update(
                "DELETE FROM inv_batch_stock WHERE goods_code LIKE 'GDT%' OR goods_code LIKE 'GDS%'"));
        counts.put("inv_stock_balance", jdbcTemplate.update(
                "DELETE FROM inv_stock_balance WHERE goods_code LIKE 'GDT%' OR goods_code LIKE 'GDS%'"));

        // 2. 应付 / 应收 —— 按 source_bill 前缀（收货单号 CGSH / XSFH）
        counts.put("fin_ap", jdbcTemplate.update(
                "DELETE FROM fin_ap WHERE source_bill LIKE 'CGSH%'"));
        counts.put("fin_ar", jdbcTemplate.update(
                "DELETE FROM fin_ar WHERE source_bill LIKE 'XSFH%'"));

        // 3. 采购链：收货 → 入库 → 订单（按主键前缀 CGSH / CGRK / CGDD）
        counts.put("pur_receipt_detail", jdbcTemplate.update("""
                DELETE FROM pur_receipt_detail WHERE receipt_id IN
                  (SELECT receipt_id FROM pur_receipt WHERE receipt_no LIKE 'CGSH%')
                """));
        counts.put("pur_receipt", jdbcTemplate.update(
                "DELETE FROM pur_receipt WHERE receipt_no LIKE 'CGSH%'"));
        counts.put("pur_inbound_detail", jdbcTemplate.update("""
                DELETE FROM pur_inbound_detail WHERE inbound_id IN
                  (SELECT inbound_id FROM pur_inbound WHERE inbound_no LIKE 'CGRK%')
                """));
        counts.put("pur_inbound", jdbcTemplate.update(
                "DELETE FROM pur_inbound WHERE inbound_no LIKE 'CGRK%'"));
        counts.put("purchase_order_detail", jdbcTemplate.update("""
                DELETE FROM purchase_order_detail WHERE order_id IN
                  (SELECT order_id FROM purchase_order WHERE order_no LIKE 'CGDD%')
                """));
        counts.put("purchase_order", jdbcTemplate.update(
                "DELETE FROM purchase_order WHERE order_no LIKE 'CGDD%'"));

        // 4. 销售链：拒收入库 → 收货 → 出库 → 订单（JSRK / XSFH / XSCK / XSDD）
        //    拒收入库单靠 source_receipt_no 关联发货单，必须先删它再删发货单
        counts.put("inv_reject_inbound_detail", jdbcTemplate.update("""
                DELETE FROM inv_reject_inbound_detail WHERE reject_inbound_id IN
                  (SELECT reject_inbound_id FROM inv_reject_inbound WHERE inbound_no LIKE 'JSRK%')
                """));
        counts.put("inv_reject_inbound", jdbcTemplate.update(
                "DELETE FROM inv_reject_inbound WHERE inbound_no LIKE 'JSRK%'"));
        counts.put("sales_receipt_detail", jdbcTemplate.update("""
                DELETE FROM sales_receipt_detail WHERE receipt_id IN
                  (SELECT receipt_id FROM sales_receipt WHERE receipt_no LIKE 'XSFH%')
                """));
        counts.put("sales_receipt", jdbcTemplate.update(
                "DELETE FROM sales_receipt WHERE receipt_no LIKE 'XSFH%'"));
        counts.put("sales_outbound_detail", jdbcTemplate.update("""
                DELETE FROM sales_outbound_detail WHERE outbound_id IN
                  (SELECT outbound_id FROM sales_outbound WHERE outbound_no LIKE 'XSCK%')
                """));
        counts.put("sales_outbound", jdbcTemplate.update(
                "DELETE FROM sales_outbound WHERE outbound_no LIKE 'XSCK%'"));
        counts.put("sales_order_detail", jdbcTemplate.update("""
                DELETE FROM sales_order_detail WHERE order_id IN
                  (SELECT order_id FROM sales_order WHERE order_no LIKE 'XSDD%')
                """));
        counts.put("sales_order", jdbcTemplate.update(
                "DELETE FROM sales_order WHERE order_no LIKE 'XSDD%'"));

        // 5. 基础资料：仅带测试前缀的（严格前缀避免误删）
        counts.put("base_goods", jdbcTemplate.update(
                "DELETE FROM base_goods WHERE goods_code LIKE 'GDT%' OR goods_code LIKE 'GDS%'"));
        counts.put("base_warehouse", jdbcTemplate.update(
                "DELETE FROM base_warehouse WHERE warehouse_code LIKE 'WHT%' OR warehouse_code LIKE 'WHS%'"));
        counts.put("base_customer", jdbcTemplate.update(
                "DELETE FROM base_customer WHERE customer_code LIKE 'CTT%' OR customer_code LIKE 'CTS%'"));
        counts.put("base_supplier", jdbcTemplate.update(
                "DELETE FROM base_supplier WHERE supplier_code LIKE 'SPT%' OR supplier_code LIKE 'SPS%'"));

        // 6. 操作日志
        counts.put("sys_operation_log_runtime", jdbcTemplate.update("""
                DELETE FROM sys_operation_log_runtime
                WHERE biz_no LIKE 'CGDD%' OR biz_no LIKE 'CGRK%' OR biz_no LIKE 'CGSH%'
                   OR biz_no LIKE 'XSDD%' OR biz_no LIKE 'XSCK%' OR biz_no LIKE 'XSFH%'
                   OR biz_no LIKE 'JSRK%'
                """));

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        counts.put("_total_rows_deleted", total);
        return ApiResponse.ok(counts);
    }

    /**
     * 精准清理：只删调用方<b>点名</b>的单号和基础资料编码，不按前缀扫全表。
     *
     * <p>请求体（每个字段都可省略，省略即不清那类数据）：
     * <pre>{@code
     * {
     *   "billNos":        ["XSDD2026...", "XSCK2026...", "XSFH2026...", "JSRK2026...", "CGDD...", "CGRK...", "CGSH..."],
     *   "goodsCodes":     ["GDS12345678"],   // 连带清 库存流水/批次库存/库存余额
     *   "warehouseCodes": ["WHS12345678"],
     *   "customerCodes":  ["CTS12345678"],
     *   "supplierCodes":  ["SPS12345678"]
     * }
     * }</pre>
     *
     * <p>{@code billNos} 一次性喂进来即可，端点自己按各表的单号列去匹配（匹配不上的自然删 0 行）。
     * 应付/应收按 {@code source_bill} 命中收货单号删除。
     *
     * <p>全部字段为空时<b>什么都不删</b>（返回全 0），避免手滑清库。
     */
    @PostMapping("/cleanup-scoped")
    @Transactional
    public ApiResponse<Map<String, Integer>> cleanupScoped(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> req = request == null ? Map.of() : request;
        List<String> billNos = strList(req.get("billNos"));
        List<String> goodsCodes = strList(req.get("goodsCodes"));
        List<String> warehouseCodes = strList(req.get("warehouseCodes"));
        List<String> customerCodes = strList(req.get("customerCodes"));
        List<String> supplierCodes = strList(req.get("supplierCodes"));

        Map<String, Integer> counts = new LinkedHashMap<>();
        if (billNos.isEmpty() && goodsCodes.isEmpty() && warehouseCodes.isEmpty()
                && customerCodes.isEmpty() && supplierCodes.isEmpty()) {
            counts.put("_total_rows_deleted", 0);
            return ApiResponse.ok(counts);
        }

        // 1. 单据（明细 → 主表；拒收入库排最前，它挂在发货单号上）
        deleteChild(counts, billNos, "inv_reject_inbound_detail", "reject_inbound_id",
                "inv_reject_inbound", "reject_inbound_id", "inbound_no");
        deleteBy(counts, billNos, "inv_reject_inbound", "inbound_no");

        deleteChild(counts, billNos, "sales_receipt_detail", "receipt_id",
                "sales_receipt", "receipt_id", "receipt_no");
        deleteBy(counts, billNos, "sales_receipt", "receipt_no");
        deleteChild(counts, billNos, "sales_outbound_detail", "outbound_id",
                "sales_outbound", "outbound_id", "outbound_no");
        deleteBy(counts, billNos, "sales_outbound", "outbound_no");
        deleteChild(counts, billNos, "sales_order_detail", "order_id",
                "sales_order", "order_id", "order_no");
        deleteBy(counts, billNos, "sales_order", "order_no");

        deleteChild(counts, billNos, "pur_receipt_detail", "receipt_id",
                "pur_receipt", "receipt_id", "receipt_no");
        deleteBy(counts, billNos, "pur_receipt", "receipt_no");
        deleteChild(counts, billNos, "pur_inbound_detail", "inbound_id",
                "pur_inbound", "inbound_id", "inbound_no");
        deleteBy(counts, billNos, "pur_inbound", "inbound_no");
        deleteChild(counts, billNos, "purchase_order_detail", "order_id",
                "purchase_order", "order_id", "order_no");
        deleteBy(counts, billNos, "purchase_order", "order_no");

        // 2. 应付 / 应收（挂在收货单号上）
        deleteBy(counts, billNos, "fin_ap", "source_bill");
        deleteBy(counts, billNos, "fin_ar", "source_bill");

        // 3. 库存 3 表（按商品编码）
        deleteBy(counts, goodsCodes, "inv_stock_ledger", "goods_code");
        deleteBy(counts, goodsCodes, "inv_batch_stock", "goods_code");
        deleteBy(counts, goodsCodes, "inv_stock_balance", "goods_code");

        // 4. 基础资料
        deleteBy(counts, goodsCodes, "base_goods", "goods_code");
        deleteBy(counts, warehouseCodes, "base_warehouse", "warehouse_code");
        deleteBy(counts, customerCodes, "base_customer", "customer_code");
        deleteBy(counts, supplierCodes, "base_supplier", "supplier_code");

        // 5. 操作日志
        deleteBy(counts, billNos, "sys_operation_log_runtime", "biz_no");

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        counts.put("_total_rows_deleted", total);
        return ApiResponse.ok(counts);
    }

    /** {@code DELETE FROM table WHERE column IN (...)}；values 为空则跳过（不记 0 行噪音）。 */
    private void deleteBy(Map<String, Integer> counts, List<String> values, String table, String column) {
        if (values.isEmpty()) return;
        int n = jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE " + column + " IN (" + placeholders(values.size()) + ")",
                values.toArray());
        counts.merge(table, n, Integer::sum);
    }

    /** 删明细：{@code WHERE fk IN (SELECT pk FROM head WHERE noColumn IN (...))}。 */
    private void deleteChild(Map<String, Integer> counts, List<String> billNos, String childTable,
                             String fkColumn, String headTable, String pkColumn, String noColumn) {
        if (billNos.isEmpty()) return;
        int n = jdbcTemplate.update(
                "DELETE FROM " + childTable + " WHERE " + fkColumn + " IN ("
                        + "SELECT " + pkColumn + " FROM " + headTable
                        + " WHERE " + noColumn + " IN (" + placeholders(billNos.size()) + "))",
                billNos.toArray());
        counts.merge(childTable, n, Integer::sum);
    }

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    /** 请求体里的字符串数组 → 去空去重的 List；单个字符串也接受。 */
    private static List<String> strList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
        } else if (raw != null) {
            String s = String.valueOf(raw).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
