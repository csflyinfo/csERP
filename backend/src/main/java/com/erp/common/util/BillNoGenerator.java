package com.erp.common.util;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

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
        private BillType() {}
    }

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbcTemplate;

    public BillNoGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按当前日期生成下一个单据号。
     *
     * @param billType 单据类型前缀（如 {@code CGDD}），来自 {@link BillType}
     * @param table    单据主表表名（如 {@code purchase_order}）
     * @param noColumn 单据号字段名（如 {@code order_no}）
     * @return 完整单据号：{@code 类型 + yyyyMMdd + 4 位流水}
     */
    /**
     * 按当前日期生成下一个单据号。
     *
     * @param billType 单据类型前缀（如 {@code CGDD}），来自 {@link BillType}
     * @param table    单据主表表名（如 {@code purchase_order}）
     * @param noColumn 单据号字段名（如 {@code order_no}）
     * @return 完整单据号：{@code 类型 + yyyyMMdd + 4 位流水}
     */
    public String nextNo(String billType, String table, String noColumn) {
        String date = LocalDate.now().format(YYYYMMDD);
        String like = billType + date + "%";
        // 用 MAX 而非 COUNT：反审核/删除会减少行数，COUNT+1 会复用已存在的号码 → UNIQUE 冲突。
        // noColumn 是固定长度（前缀+8位日期+4位流水），VARCHAR 字典序等价于数字序，MAX 安全。
        String max = jdbcTemplate.queryForObject(
                "SELECT MAX(" + noColumn + ") FROM " + table + " WHERE " + noColumn + " LIKE ?",
                String.class, like);
        int next = 1;
        if (max != null && !max.isEmpty() && max.length() >= 4) {
            try { next = Integer.parseInt(max.substring(max.length() - 4)) + 1; }
            catch (NumberFormatException e) { /* 解析失败从头开始，不阻塞业务 */ }
        }
        return String.format("%s%s%04d", billType, date, next);
    }
}
