package com.erp.finance.gl;

import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭证导出金蝶 KIS / 用友 U8/T+：按期间+凭证字取已过账凭证，一行一分录摊平为 CSV（UTF-8 BOM），
 * 写 fin_voucher_export_log 并给凭证打 export_flag。科目体系本就是小企业准则标准码，默认零科目对照。
 */
@Service
public class GlExportService {

    private final JdbcTemplate jdbc;

    public GlExportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static class ExportResult {
        public String fileName;
        public int voucherCount;
        public int entryCount;
        public String csv;
    }

    /**
     * 生成导出 CSV（不落盘，由 controller 写文件流），同时写日志、打 export_flag。
     */
    @Transactional
    public ExportResult export(String format, String periodFrom, String periodTo, String voucherWord) {
        boolean kingdee = "KINGDEE".equals(format);
        if (!kingdee && !"YONYOU".equals(format))
            throw new IllegalArgumentException("导出格式只支持 KINGDEE（金蝶）/ YONYOU（用友）");
        if (periodFrom == null || periodFrom.isEmpty() || periodTo == null || periodTo.isEmpty())
            throw new IllegalArgumentException("请选择导出期间范围");
        if (periodFrom.compareTo(periodTo) > 0)
            throw new IllegalArgumentException("起始期间不能晚于截止期间");

        StringBuilder where = new StringBuilder("status = '已过账' AND period >= ? AND period <= ? ");
        List<Object> args = new ArrayList<>(List.of(periodFrom, periodTo));
        if (voucherWord != null && !voucherWord.isEmpty()) {
            where.append("AND voucher_word = ? ");
            args.add(voucherWord);
        }
        List<Map<String, Object>> vouchers = TmsUtil.queryCamel(jdbc,
                "SELECT id, voucher_no, voucher_word, voucher_date, period, attachments, summary, is_red " +
                        "FROM fin_voucher WHERE " + where + "ORDER BY voucher_date, voucher_no", args.toArray());
        if (vouchers.isEmpty())
            throw new IllegalArgumentException("期间 " + periodFrom + "~" + periodTo + " 内没有已过账凭证可导出");

        List<String> voucherIds = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        // UTF-8 BOM，Excel 直接打开不乱码
        sb.append('﻿');
        if (kingdee) {
            sb.append("凭证日期,凭证字,凭证号,附件数,摘要,科目代码,科目名称,借方金额,贷方金额,核算项目\n");
        } else {
            sb.append("凭证日期,凭证号,附单据数,摘要,科目编码,辅助项,借方金额,贷方金额\n");
        }

        int entryCount = 0;
        for (Map<String, Object> v : vouchers) {
            voucherIds.add(TmsUtil.str(v.get("id")));
            // 分录表只存科目编码，科目名 LEFT JOIN fin_account 取（H2 MODE=MySQL 下列名必须加表别名限定）
            List<Map<String, Object>> entries = TmsUtil.queryCamel(jdbc,
                    "SELECT e.line_no, e.summary, e.account_code, a.account_name, " +
                            "e.debit_amount, e.credit_amount, e.aux_text " +
                            "FROM fin_voucher_entry e LEFT JOIN fin_account a ON a.account_code = e.account_code " +
                            "WHERE e.voucher_id = ? ORDER BY e.line_no",
                    TmsUtil.str(v.get("id")));
            for (Map<String, Object> e : entries) {
                entryCount++;
                String date = String.valueOf(v.get("voucherDate"));
                String word = TmsUtil.str(v.get("voucherWord"));
                String no = TmsUtil.str(v.get("voucherNo"));
                String att = String.valueOf(v.get("attachments") == null ? 0 : v.get("attachments"));
                String vSummary = TmsUtil.str(v.get("summary"));
                String eSummary = TmsUtil.str(e.get("summary"));
                String summary = eSummary.isEmpty() ? vSummary : eSummary;
                if (Boolean.TRUE.equals(v.get("isRed"))) summary = "红冲 " + summary;
                String code = TmsUtil.str(e.get("accountCode"));
                String name = TmsUtil.str(e.get("accountName"));
                String debit = money(e.get("debitAmount"));
                String credit = money(e.get("creditAmount"));
                String aux = TmsUtil.str(e.get("auxText"));
                if (kingdee) {
                    sb.append(csv(date)).append(',').append(csv(word)).append(',').append(csv(no)).append(',')
                            .append(csv(att)).append(',').append(csv(summary)).append(',').append(csv(code)).append(',')
                            .append(csv(name)).append(',').append(csv(debit)).append(',').append(csv(credit)).append(',')
                            .append(csv(aux)).append('\n');
                } else {
                    // 用友导入后为未审核状态；凭证号含凭证字（记-202609-0001）
                    sb.append(csv(date)).append(',').append(csv(no)).append(',').append(csv(att)).append(',')
                            .append(csv(summary)).append(',').append(csv(code)).append(',').append(csv(aux)).append(',')
                            .append(csv(debit)).append(',').append(csv(credit)).append('\n');
                }
            }
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String sysName = kingdee ? "金蝶KIS" : "用友T+";
        String fileName = "凭证导出-" + sysName + "-" + periodFrom + "-" + periodTo + "-" + stamp + ".csv";

        String logId = TmsUtil.uuid("vex");
        jdbc.update("INSERT INTO fin_voucher_export_log(id, export_format, period_from, period_to, voucher_word, " +
                        "file_name, voucher_count, entry_count, creator) VALUES (?,?,?,?,?,?,?,?,?)",
                logId, format, periodFrom, periodTo,
                voucherWord == null ? "" : voucherWord, fileName, vouchers.size(), entryCount, TmsUtil.currentUser());
        jdbc.update("UPDATE fin_voucher SET export_flag = ? WHERE id IN ("
                + String.join(",", voucherIds.stream().map(x -> "?").toList()) + ")",
                joinArgs(format, voucherIds));

        ExportResult r = new ExportResult();
        r.fileName = fileName;
        r.voucherCount = vouchers.size();
        r.entryCount = entryCount;
        r.csv = sb.toString();
        return r;
    }

    /** 导出日志分页（全量不多，直接按时间倒序返回）。 */
    public List<Map<String, Object>> logs() {
        return TmsUtil.queryCamel(jdbc,
                "SELECT id, export_format, period_from, period_to, voucher_word, file_name, " +
                        "voucher_count, entry_count, creator, create_time " +
                        "FROM fin_voucher_export_log ORDER BY create_time DESC LIMIT 100");
    }

    private Object[] joinArgs(String format, List<String> ids) {
        Object[] args = new Object[ids.size() + 1];
        args[0] = format;
        for (int i = 0; i < ids.size(); i++) args[i + 1] = ids.get(i);
        return args;
    }

    private static String money(Object v) {
        if (v == null) return "";
        BigDecimal bd = v instanceof BigDecimal ? (BigDecimal) v : new BigDecimal(String.valueOf(v));
        if (bd.compareTo(BigDecimal.ZERO) == 0) return "";
        return bd.toPlainString();
    }

    /** CSV 字段：统一双引号包裹，内部双引号转义；防逗号/换行破列。 */
    private static String csv(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
