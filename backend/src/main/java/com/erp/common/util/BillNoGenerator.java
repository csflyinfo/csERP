package com.erp.common.util;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单据编号生成器（V1.0）—— 按项目根目录 {@code docs/PRD-版本化产品需求/V1.0-ERP核心经营版/单据编号生成规则表.md} 落地。
 *
 * <p>规则：{@code 单据类型 + yyyyMMdd + 4 位流水}，流水号每天从 0001 开始。
 * <p>示例：{@code XSDD202607220001}
 *
 * <p><b>单据类型前缀（BillType）</b>：
 * <ul>
 *   <li>采购订单 CGDD</li>
 *   <li>采购入库单 CGRK</li>
 *   <li>采购收货单 CGSH</li>
 *   <li>采购退货申请单 CTSQ / 采购退货出库 CTCK / 采购退货单 CGTH</li>
 *   <li>销售订单 XSDD</li>
 *   <li>销售出库单 XSCK</li>
 *   <li>销售发货单 XSFH（对应 {@code sales_receipt}）</li>
 *   <li>销售退货申请 XTSQ / 销售退货入库 THRK / 销售退货单 XSTH</li>
 *   <li>拒收入库单 JSRK（对应 {@code inv_reject_inbound}，由发货单签收拒收自动生成）</li>
 * </ul>
 */
@Component
public class BillNoGenerator {

    /** 单据类型前缀常量集合，避免 controller 里散落硬编码。 */
    public static final class BillType {
        public static final String PURCHASE_ORDER = "CGDD";     // 采购订单
        public static final String PURCHASE_INBOUND = "CGRK";   // 采购入库单
        public static final String PURCHASE_RECEIPT = "CGSH";   // 采购收货单
        public static final String PURCHASE_RETURN_REQ = "CTSQ";
        public static final String PURCHASE_RETURN_OUT = "CTCK";
        public static final String PURCHASE_RETURN = "CGTH";
        public static final String PURCHASE_INVOICE = "PINV";  // 采购发票（进项来票登记）
        public static final String SALES_ORDER = "XSDD";        // 销售订单
        public static final String SALES_OUTBOUND = "XSCK";     // 销售出库单
        public static final String SALES_RECEIPT = "XSFH";      // 销售发货单
        public static final String SALES_RETURN_REQ = "XTSQ";
        public static final String SALES_RETURN_IN = "THRK";
        public static final String SALES_RETURN = "XSTH";
        public static final String FLY_ORDER = "FD";            // 飞单
        public static final String TRANSFER_APPLY = "DBSQ";     // 调拨申请单
        public static final String TRANSFER_OUTBOUND = "DBCK";  // 调拨出库单
        public static final String TRANSFER_INBOUND = "DBRK";   // 调拨入库单（含差异退回）
        public static final String STOCK_TAKE = "PDD";          // 盘点单
        public static final String DAMAGE = "BSD";              // 报损单
        public static final String OTHER_INBOUND = "QTRK";      // 其他入库单
        public static final String OTHER_OUTBOUND = "QTCK";     // 其他出库单
        public static final String REJECT_INBOUND = "JSRK";     // 拒收入库单（签收拒收自动生成）
        public static final String TMS_DISPATCH = "DD";         // TMS 调度单
        public static final String TMS_TRIP = "XC";             // TMS 配送行程
        public static final String TMS_SIGN = "QS";             // TMS 签收记录
        public static final String TMS_RESCHEDULE_RETURN = "GPRC"; // TMS 改派返仓单
        public static final String TMS_CUSTOMER_REJECT = "KHJS";   // TMS 客户拒收单
        public static final String TMS_SETTLEMENT = "JZ";          // TMS 交账单
        public static final String TMS_EXCEPTION_REPORT = "YCSB";  // TMS 异常上报单
        public static final String WMS_WAVE = "BC";          // WMS 波次
        public static final String WMS_PICK_TASK = "JH";     // WMS 拣货任务
        public static final String WMS_RECHECK = "FH";       // WMS 复核记录
        public static final String WMS_HANDOVER = "ZC";      // WMS 装车交接
        public static final String WMS_EXCEPTION = "YC";     // WMS 异常
        public static final String WMS_INBOUND = "RK";       // WMS 入库任务
        public static final String WMS_PUTAWAY = "SJ";       // WMS 上架任务
        public static final String WMS_REPLENISH = "BH";     // WMS 补货任务
        public static final String WMS_MOVE = "YK";          // WMS 移库任务
        public static final String WMS_ADJUST = "TZ";        // WMS 库存调整
        public static final String WMS_DAMAGE = "BS";        // WMS 报损
        public static final String WMS_ASSEMBLY = "ZZ";      // WMS 组装拆卸
        public static final String WMS_STOCKTAKE = "PD";     // WMS 盘点任务
        private BillType() {}
    }

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 表白名单：table / column 直接拼进 SQL（JDBC 不支持标识符占位），
     * 必须在调用方传入前被校验，防止未来某个调用点把用户输入透传进来造成 SQL 注入。
     * 表名/列名以实际 schema 为准（见各 Flyway 迁移）。
     */
    private static final Map<String, Set<String>> ALLOWED_TABLE_COLUMNS = Map.ofEntries(
            Map.entry("base_customer_price_adjust", Set.of("adjust_no")),
            Map.entry("purchase_order", Set.of("order_no")),
            Map.entry("pur_inbound", Set.of("inbound_no")),
            Map.entry("pur_receipt", Set.of("receipt_no")),
            Map.entry("pur_return_apply", Set.of("apply_no")),
            Map.entry("pur_return_outbound", Set.of("outbound_no")),
            Map.entry("pur_return", Set.of("return_no")),
            Map.entry("pur_invoice", Set.of("invoice_no")),
            Map.entry("sales_order", Set.of("order_no")),
            Map.entry("sales_outbound", Set.of("outbound_no")),
            Map.entry("sales_receipt", Set.of("receipt_no")),
            Map.entry("sales_return_apply", Set.of("apply_no")),
            Map.entry("sales_return_inbound", Set.of("inbound_no")),
            Map.entry("sales_return", Set.of("return_no")),
            Map.entry("fly_order", Set.of("fly_no")),
            Map.entry("transfer_apply", Set.of("apply_no")),
            Map.entry("transfer_outbound", Set.of("outbound_no")),
            Map.entry("transfer_inbound", Set.of("inbound_no")),
            Map.entry("inv_count_sheet", Set.of("sheet_no")),
            Map.entry("inv_damage", Set.of("damage_no")),
            Map.entry("inv_other_inbound", Set.of("inbound_no")),
            Map.entry("inv_other_outbound", Set.of("outbound_no")),
            Map.entry("inv_reject_inbound", Set.of("inbound_no")),
            Map.entry("fin_receipt_bill", Set.of("receipt_no")),
            Map.entry("fin_payment_bill", Set.of("payment_no")),
            Map.entry("fin_expense_bill", Set.of("expense_no")),
            Map.entry("fin_customer_statement", Set.of("statement_no")),
            Map.entry("fin_supplier_statement", Set.of("statement_no")),
            Map.entry("fin_ar", Set.of("ar_no")),
            Map.entry("fin_ap", Set.of("ap_no")),
            Map.entry("tms_dispatch", Set.of("dispatch_no")),
            Map.entry("tms_delivery_trip", Set.of("trip_no")),
            Map.entry("tms_reschedule_return", Set.of("return_no")),
            Map.entry("tms_customer_reject", Set.of("reject_no")),
            Map.entry("tms_settlement", Set.of("settlement_no")),
            Map.entry("tms_store_settlement", Set.of("settle_no")),
            Map.entry("tms_exception_report", Set.of("report_no")),
            Map.entry("wms_wave", Set.of("wave_no")),
            Map.entry("wms_pick_task", Set.of("task_no")),
            Map.entry("wms_recheck_record", Set.of("recheck_no")),
            Map.entry("wms_handover", Set.of("handover_no")),
            Map.entry("wms_exception", Set.of("exception_no")),
            Map.entry("wms_inbound_task", Set.of("task_no")),
            Map.entry("wms_putaway_task", Set.of("putaway_no")),
            Map.entry("wms_replenish_task", Set.of("task_no")),
            Map.entry("wms_move_task", Set.of("task_no")),
            Map.entry("wms_adjust_record", Set.of("adjust_no")),
            Map.entry("wms_damage_record", Set.of("damage_no")),
            Map.entry("wms_assembly_task", Set.of("task_no")),
            Map.entry("wms_stocktake_task", Set.of("task_no"))
    );

    private final JdbcTemplate jdbcTemplate;

    public BillNoGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按当前日期生成下一个单据号。
     *
     * @param billType 单据类型前缀（如 {@code CGDD}），来自 {@link BillType}
     * @param table    单据主表表名（如 {@code purchase_order}），必须在白名单内
     * @param noColumn 单据号字段名（如 {@code order_no}），必须在该表白名单列内
     * @return 完整单据号：{@code 类型 + yyyyMMdd + 4 位流水}
     */
    public String nextNo(String billType, String table, String noColumn) {
        // 标识符白名单校验：即便将来有调用点把外部输入透传进来，也无法越权拼 SQL
        Set<String> cols = ALLOWED_TABLE_COLUMNS.get(table);
        if (cols == null || !cols.contains(noColumn)) {
            throw new IllegalArgumentException("非法的单据号目标表/列：" + table + "." + noColumn);
        }
        String date = LocalDate.now().format(YYYYMMDD);
        // 用 MAX 而非 COUNT：反审核/删除会减少行数，COUNT+1 会复用已存在的号码 → UNIQUE 冲突。
        // noColumn 是固定长度（前缀+8位日期+4位流水），VARCHAR 字典序等价于数字序，MAX 安全。
        // 高并发下两个线程可能读到相同 MAX，第二次 INSERT 会撞唯一索引；重试几次把流水号让过去。
        for (int attempt = 0; attempt < 5; attempt++) {
            String like = billType + date + "%";
            String max = jdbcTemplate.queryForObject(
                    "SELECT MAX(" + noColumn + ") FROM " + table + " WHERE " + noColumn + " LIKE ?",
                    String.class, like);
            int next = 1;
            if (max != null && !max.isEmpty() && max.length() >= 4) {
                try { next = Integer.parseInt(max.substring(max.length() - 4)) + 1; }
                catch (NumberFormatException e) { /* 解析失败从头开始，不阻塞业务 */ }
            }
            String candidate = String.format("%s%s%04d", billType, date, next);
            // 预占：只有当该号确实不存在时才返回，否则让下一轮重新取 MAX（并发冲突已被对方提交）
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + noColumn + " = ?",
                    Integer.class, candidate);
            if (cnt == null || cnt == 0) return candidate;
        }
        // 理论上不可达：当天流水超过 9999 或持续冲突才会到这里
        throw new IllegalStateException("无法为 " + table + " 生成唯一单据号，请稍后重试");
    }
}
