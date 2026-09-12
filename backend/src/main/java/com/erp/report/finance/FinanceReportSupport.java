package com.erp.report.finance;

import com.erp.report.common.ReportQueryRequest;

import java.time.LocalDate;
import java.util.List;

/**
 * 财务域报表（#20~#24）共用口径：核销流水业务类型、截至日解析等。
 *
 * <p>核销流水 fin_reconcile_record 一笔回款/付款可能写成多种 business_type，
 * 各报表必须用同一套类型清单，禁止各写各的：
 * <ul>
 *   <li>应收侧：收款审核 FIFO 核销=SALES_RECEIPT；司机交账核销=AR_SETTLE；
 *       付款单核销应收（退款等）=SALES_PAYMENT；客户对账单核销=CUSTOMER_STATEMENT。</li>
 *   <li>应付侧：收款单核销应付（供应商退款等）=PURCHASE_RECEIPT；付款审核 FIFO=PURCHASE_PAYMENT；
 *       供应商对账单核销=SUPPLIER_STATEMENT。</li>
 * </ul>
 * 注意 EXPENSE_WRITEOFF（抹零）的 business_no 是费用单号而非往来单号，不归属具体账单，
 * 因此不进入按单归集的核销额（汇总表中作为「减免/抹零」信息列单独取数）。
 */
final class FinanceReportSupport {

    /** 核销应收的四类业务类型（按 ar_no 归集）。 */
    static final List<String> AR_CASH_TYPES =
            List.of("SALES_RECEIPT", "AR_SETTLE", "SALES_PAYMENT", "CUSTOMER_STATEMENT");

    /** 核销应付的三类业务类型（按 ap_no 归集）。 */
    static final List<String> AP_CASH_TYPES =
            List.of("PURCHASE_RECEIPT", "PURCHASE_PAYMENT", "SUPPLIER_STATEMENT");

    /** 账龄/余额比较零容差（金额 2 位小数）。 */
    static final double EPS = 0.005d;

    private FinanceReportSupport() {
    }

    /** 生成 "(?,?,...)" 占位片段。 */
    static String placeholders(int n) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.append(')').toString();
    }

    /**
     * 时点报表（#20/#21）的截至日：filters.cutoff 优先，其次 filters.endDate，
     * 都没有默认今天（设计稿口径：账龄默认查当前时点）。不允许未来日期。
     */
    static LocalDate cutoff(ReportQueryRequest req) {
        String cutoff = req.text("cutoff");
        LocalDate d;
        if (cutoff != null) {
            d = LocalDate.parse(cutoff.length() > 10 ? cutoff.substring(0, 10) : cutoff);
        } else {
            String end = req.text("endDate");
            d = end != null
                    ? LocalDate.parse(end.length() > 10 ? end.substring(0, 10) : end)
                    : LocalDate.now();
        }
        if (d.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("账龄截止日期不能晚于今天");
        }
        return d;
    }
}
