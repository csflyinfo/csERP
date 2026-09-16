package com.erp.finance.init;

import com.erp.finance.account.CustomerAccountService;
import com.erp.init.InitConst;
import com.erp.init.InitSupport;
import com.erp.system.OperationAction;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 客户期初预收初始化（PRD-35 M4）。
 *
 * <p>生命周期同应收期初：导入/手工行进 fin_adv_init 暂存（VALID/ERROR）→ 一次性过账，
 * 每客户每行写一笔 ADV_OPENING 预收增加流水（source_bill=QCYK-{过批号}-{序号}，
 * post_date=建账日，advance_balance 一次性建立；<b>不造收款单、不动资金</b>，
 * 总账侧随现有一键期初在 2203 贷方汇总引入）→ 日结/总账启用后锁定；
 * 反建账时预收已被核销/退款使用的客户拒绝，其余删除建账流水、重排余额链、暂存回退可重导。
 */
@Service
public class AdvInitService {

    private static final String MODULE = "fin.init_adv";
    private static final String MODULE_LABEL = "客户期初预收";

    /** 导入/失败文件列：{中文表头, 驼峰键}，顺序即列序。 */
    public static final String[][] FIELDS = {
            {"客户编码", "customerCode"}, {"客户名称", "customerName"},
            {"原单据号", "originalBillNo"}, {"原单据日期", "originalBillDate"},
            {"预收金额", "advAmount"}, {"备注", "remark"},
    };

    private final JdbcTemplate jdbc;
    private final InitSupport support;
    private final CustomerAccountService customerAccountService;

    public AdvInitService(JdbcTemplate jdbc, InitSupport support,
                          CustomerAccountService customerAccountService) {
        this.jdbc = jdbc;
        this.support = support;
        this.customerAccountService = customerAccountService;
    }

    // ==================== 状态 / 分页 ====================

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean posted = support.isPosted(InitConst.PARAM_ADV_POSTED);
        m.put("posted", posted);
        m.put("dayCloseLocked", support.hasDayClose());
        m.put("glInitialized", support.isGlInitialized());

        Map<String, Object> sums = TmsUtil.queryCamel(jdbc,
                "SELECT COUNT(*) total_count, " +
                        "COALESCE(SUM(CASE WHEN line_status='VALID' THEN 1 ELSE 0 END),0) valid_count, " +
                        "COALESCE(SUM(CASE WHEN line_status='ERROR' THEN 1 ELSE 0 END),0) error_count, " +
                        "COALESCE(SUM(CASE WHEN line_status='VALID' THEN adv_amount ELSE 0 END),0) total_amount " +
                        "FROM fin_adv_init WHERE posted='N'").get(0);
        m.put("lineCount", ((Number) sums.get("validCount")).intValue());
        m.put("errorCount", ((Number) sums.get("errorCount")).intValue());
        m.put("totalAmount", sums.get("totalAmount"));
        m.put("canPost", !posted && !support.hasDayClose() && !support.isGlInitialized()
                && ((Number) sums.get("validCount")).intValue() > 0
                && ((Number) sums.get("errorCount")).intValue() == 0);
        m.put("canReverse", posted && !support.hasDayClose() && !support.isGlInitialized());

        Map<String, Object> post = support.activePost(InitConst.TYPE_ADV);
        if (post != null) {
            m.put("postNo", post.get("postNo"));
            m.put("postAt", post.get("postedAt"));
            m.put("postByName", post.get("postedByName"));
            m.put("postLineCount", post.get("lineCount"));
            m.put("postTotalAmount", post.get("totalAmount"));
        }
        return m;
    }

    /** 暂存行分页。body: {pageNo,pageSize,posted,lineStatus,keyword,batchNo}。 */
    public Map<String, Object> linePage(Map<String, Object> req) {
        int pageNo = Math.max(1, TmsUtil.toInt(req.get("pageNo")));
        int pageSize = Math.min(200, TmsUtil.toInt(req.get("pageSize")) <= 0 ? 20 : TmsUtil.toInt(req.get("pageSize")));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String posted = TmsUtil.str(req.get("posted"));
        if (!posted.isEmpty()) {
            where.append(" AND posted=?");
            args.add(posted);
        }
        String lineStatus = TmsUtil.str(req.get("lineStatus"));
        if (!lineStatus.isEmpty()) {
            where.append(" AND line_status=?");
            args.add(lineStatus);
        }
        String batchNo = TmsUtil.str(req.get("batchNo"));
        if (!batchNo.isEmpty()) {
            where.append(" AND import_batch_no=?");
            args.add(batchNo);
        }
        String keyword = TmsUtil.str(req.get("keyword"));
        if (!keyword.isEmpty()) {
            where.append(" AND (customer_code LIKE ? OR customer_name LIKE ? OR original_bill_no LIKE ?)");
            for (int i = 0; i < 3; i++) args.add("%" + keyword + "%");
        }
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_adv_init" + where, Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> records = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, import_batch_no, row_no, customer_code, customer_name, " +
                        "original_bill_no, original_bill_date, adv_amount, remark, " +
                        "line_status, error_msg, task_no, posted, post_no, generated_flow_id, created_at " +
                        "FROM fin_adv_init" + where +
                        " ORDER BY COALESCE(row_no,999999), created_at, line_id LIMIT ? OFFSET ?",
                pageArgs.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("pageNo", pageNo);
        result.put("pageSize", pageSize);
        result.put("total", total);
        return result;
    }

    // ==================== 手工行 / 删除 / 清空 ====================

    /** 手工新增（校验不通过直接抛中文异常）。 */
    public void saveLine(Map<String, Object> req) {
        support.assertEditable(InitConst.PARAM_ADV_POSTED, MODULE_LABEL);
        ParsedLine line = parse(req, null);
        if (line.error != null) throw new IllegalArgumentException(line.error);
        insertLine(line, null, null, InitConst.LINE_VALID, null);
        support.opLog().log(MODULE, OperationAction.CREATE, "",
                "新增期初预收行：" + line.customerName + " " + line.advAmount);
    }

    /** 手工编辑（可修正 ERROR 行，保存时重新校验）。 */
    public void updateLine(Map<String, Object> req) {
        support.assertEditable(InitConst.PARAM_ADV_POSTED, MODULE_LABEL);
        String lineId = TmsUtil.str(req.get("lineId"));
        if (lineId.isEmpty()) throw new IllegalArgumentException("缺少行 ID");
        Map<String, Object> old = getLine(lineId);
        if (old == null) throw new IllegalArgumentException("期初行不存在或已删除");
        if ("Y".equals(TmsUtil.str(old.get("posted")))) {
            throw new IllegalArgumentException("该行已建账，不能修改");
        }
        ParsedLine line = parse(req, null);
        if (line.error != null) throw new IllegalArgumentException(line.error);
        jdbc.update("UPDATE fin_adv_init SET customer_code=?, customer_name=?, original_bill_no=?, " +
                        "original_bill_date=?, adv_amount=?, remark=?, " +
                        "line_status='VALID', error_msg=NULL WHERE line_id=?",
                line.customerCode, line.customerName, nullIfEmpty(line.originalBillNo),
                line.originalBillDate == null ? null : Date.valueOf(line.originalBillDate),
                line.advAmount, nullIfEmpty(line.remark), lineId);
        support.opLog().log(MODULE, OperationAction.UPDATE, "",
                "修改期初预收行：" + line.customerName + " " + line.advAmount);
    }

    public void deleteLine(String lineId) {
        support.assertEditable(InitConst.PARAM_ADV_POSTED, MODULE_LABEL);
        Map<String, Object> old = getLine(lineId);
        if (old == null) return;
        if ("Y".equals(TmsUtil.str(old.get("posted")))) {
            throw new IllegalArgumentException("该行已建账，不能删除");
        }
        jdbc.update("DELETE FROM fin_adv_init WHERE line_id=?", lineId);
        support.opLog().log(MODULE, OperationAction.DELETE, "",
                "删除期初预收行：" + TmsUtil.str(old.get("customerName")) + " " + old.get("advAmount"));
    }

    /** 清空未建账暂存行（可按导入批次清空）。 */
    public int clear(String batchNo) {
        support.assertEditable(InitConst.PARAM_ADV_POSTED, MODULE_LABEL);
        String sql = "DELETE FROM fin_adv_init WHERE posted='N'";
        List<Object> args = new ArrayList<>();
        if (batchNo != null && !batchNo.isEmpty()) {
            sql += " AND import_batch_no=?";
            args.add(batchNo);
        }
        int n = jdbc.update(sql, args.toArray());
        support.opLog().log(MODULE, OperationAction.DELETE, "",
                "清空期初预收暂存行 " + n + " 行" + (batchNo == null || batchNo.isEmpty() ? "" : "（批次 " + batchNo + "）"));
        return n;
    }

    // ==================== 导入 ====================

    /** 导入：逐行校验，VALID/ERROR 都落暂存（ERROR 可在页面修正/删除），同时生成失败 xlsx 与导入任务。 */
    public Map<String, Object> importRows(List<Map<String, Object>> rows, String fileName) {
        support.assertEditable(InitConst.PARAM_ADV_POSTED, MODULE_LABEL);
        String batchNo = support.nextBatchNo("ADV");
        String taskNo = support.nextTaskNo();
        int success = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        Set<String> batchKeys = new HashSet<>();

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2;
            Map<String, Object> r = rows.get(i);
            ParsedLine line = parse(r, batchKeys);
            if (line.error == null) {
                insertLine(line, batchNo, rowNo, InitConst.LINE_VALID, taskNo);
                success++;
            } else {
                insertLine(line, batchNo, rowNo, InitConst.LINE_ERROR, taskNo);
                failures.add(support.failure(rowNo, line.error));
            }
        }
        String recordedTaskNo = support.recordImportTask(taskNo,
                InitConst.TASK_MODULE_ADV, "客户期初预收导入", fileName, success, failures, FIELDS, rows);
        support.opLog().log(MODULE, OperationAction.IMPORT, recordedTaskNo,
                "客户期初预收导入（文件 " + fileName + "）：成功 " + success + " 行，失败 " + failures.size() + " 行");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", success);
        result.put("failed", failures.size());
        result.put("failures", failures);
        result.put("taskNo", recordedTaskNo);
        result.put("batchNo", batchNo);
        result.put("message", "导入完成：成功 " + success + " 条，失败 " + failures.size()
                + " 条，失败行已标记为错误数据，可在页面修正或删除；失败明细也可在【导入列表】下载");
        return result;
    }

    // ==================== 过账 / 反建账 ====================

    @Transactional
    public Map<String, Object> post() {
        support.assertEditable(InitConst.PARAM_ADV_POSTED, MODULE_LABEL);
        Integer errorCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_adv_init WHERE posted='N' AND line_status='ERROR'", Integer.class);
        if (errorCount != null && errorCount > 0) {
            throw new IllegalArgumentException("存在 " + errorCount + " 行错误数据，请修正或删除后再建账");
        }
        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, customer_code, customer_name, adv_amount, remark " +
                        "FROM fin_adv_init WHERE posted='N' AND line_status='VALID' " +
                        "ORDER BY COALESCE(row_no,999999), line_id");
        if (lines.isEmpty()) throw new IllegalArgumentException("没有可建账的有效期初行，请先导入或手工新增");

        String postNo = support.nextPostNo();
        LocalDate postDate = LocalDate.now();
        BigDecimal total = BigDecimal.ZERO;
        int seq = 0;
        for (Map<String, Object> line : lines) {
            seq++;
            BigDecimal amount = TmsUtil.toBd(line.get("advAmount"));
            String flowId = customerAccountService.postAdvanceOpening(postNo, seq, postDate,
                    TmsUtil.str(line.get("customerCode")), TmsUtil.str(line.get("customerName")), amount);
            jdbc.update("UPDATE fin_adv_init SET posted='Y', post_no=?, generated_flow_id=? WHERE line_id=?",
                    postNo, flowId, line.get("lineId"));
            total = total.add(amount);
        }
        support.insertPost(postNo, InitConst.TYPE_ADV, lines.size(), null, total, "客户期初预收建账");
        support.setPostedFlag(InitConst.PARAM_ADV_POSTED, InitConst.PARAM_ADV_POST_NO, postNo, MODULE_LABEL);
        support.opLog().log(MODULE, OperationAction.CREATE, postNo,
                "客户期初预收建账：批号 " + postNo + "，" + lines.size() + " 行，合计 " + total + " 元");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("postNo", postNo);
        result.put("lineCount", lines.size());
        result.put("totalAmount", total);
        return result;
    }

    @Transactional
    public void reverse(String reason) {
        support.assertReversable(InitConst.PARAM_ADV_POSTED, MODULE_LABEL);
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("反建账必须填写原因");
        }
        Map<String, Object> post = support.activePost(InitConst.TYPE_ADV);
        if (post == null) throw new IllegalArgumentException("未找到已生效的期初预收建账记录");
        String postNo = TmsUtil.str(post.get("postNo"));

        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, customer_code, generated_flow_id FROM fin_adv_init " +
                        "WHERE posted='Y' AND post_no=?", postNo);
        if (lines.isEmpty()) throw new IllegalArgumentException("批号 " + postNo + " 下没有期初行");
        // 先全量做下游守卫，任一客户已使用预收即整批拒绝（避免删一半）
        for (Map<String, Object> line : lines) {
            String flowId = TmsUtil.str(line.get("generatedFlowId"));
            if (flowId.isEmpty()) continue;
            String block = customerAccountService.checkAdvanceOpeningReversable(
                    TmsUtil.str(line.get("customerCode")), flowId);
            if (block != null) throw new IllegalArgumentException(block);
        }
        for (Map<String, Object> line : lines) {
            String flowId = TmsUtil.str(line.get("generatedFlowId"));
            String customerCode = TmsUtil.str(line.get("customerCode"));
            if (!flowId.isEmpty()) {
                customerAccountService.deleteAdvanceOpening(flowId, customerCode);
            }
            jdbc.update("UPDATE fin_adv_init SET posted='N', post_no=NULL, generated_flow_id=NULL WHERE line_id=?",
                    line.get("lineId"));
        }
        support.markPostReversed(postNo, reason);
        support.clearPostedFlag(InitConst.PARAM_ADV_POSTED, InitConst.PARAM_ADV_POST_NO);
        support.opLog().log(MODULE, OperationAction.REVERSE, postNo,
                "客户期初预收反建账：批号 " + postNo + "，回退 " + lines.size() + " 行。原因：" + reason);
    }

    // ==================== 行解析 / 落库 ====================

    private Map<String, Object> getLine(String lineId) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, customer_name, adv_amount, posted FROM fin_adv_init WHERE line_id=?", lineId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 解析并校验一行。错误信息挂在 {@link ParsedLine#error}（导入落 ERROR 行；手工增改直接抛）。
     *
     * @param batchKeys 本次导入文件内的去重集合（code + 原单号），手工行传 null
     */
    private ParsedLine parse(Map<String, Object> r, Set<String> batchKeys) {
        ParsedLine p = new ParsedLine();
        p.customerCode = TmsUtil.str(r.get("customerCode"));
        p.customerName = TmsUtil.str(r.get("customerName"));
        p.rawName = p.customerName;
        p.originalBillNo = TmsUtil.str(r.get("originalBillNo"));
        p.remark = TmsUtil.str(r.get("remark"));

        if (p.customerCode.isEmpty()) {
            p.error = "客户编码必填";
            return p;
        }
        Map<String, Object> customer = lookupCustomer(p.customerCode);
        if (customer == null) {
            p.error = "客户编码「" + p.customerCode + "」不存在，须先在客户档案中维护";
            return p;
        }
        // 一律按编码回写标准名称，名称列仅用于导入时人工核对
        p.customerName = TmsUtil.str(customer.get("customerName"));

        String dateRaw = TmsUtil.str(r.get("originalBillDate"));
        if (!dateRaw.isEmpty()) {
            p.originalBillDate = TmsUtil.toLocalDate(dateRaw);
            if (p.originalBillDate == null) {
                p.error = "原单据日期格式不正确：" + dateRaw + "（应为 yyyy-MM-dd）";
                return p;
            }
        }
        String amountRaw = TmsUtil.str(r.get("advAmount"));
        if (amountRaw.isEmpty()) {
            p.error = "预收金额必填";
            return p;
        }
        try {
            p.advAmount = new BigDecimal(amountRaw.replace(",", ""));
        } catch (NumberFormatException e) {
            p.error = "预收金额不是合法数字：" + amountRaw;
            return p;
        }
        if (p.advAmount.signum() <= 0) {
            p.error = "预收金额必须大于 0";
            return p;
        }
        p.advAmount = p.advAmount.setScale(2, RoundingMode.HALF_UP);

        if (batchKeys != null) {
            String key = p.customerCode + "|" + p.originalBillNo.toUpperCase(Locale.ROOT);
            if (!batchKeys.add(key)) {
                p.error = "同一文件内客户+原单据号重复：" + p.customerCode
                        + (p.originalBillNo.isEmpty() ? "" : " / " + p.originalBillNo);
            }
        }
        return p;
    }

    private Map<String, Object> lookupCustomer(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT customer_code, customer_name FROM base_customer WHERE customer_code=? LIMIT 1", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertLine(ParsedLine line, String batchNo, Integer rowNo, String status, String taskNo) {
        // 校验失败行也落暂存（保留原始编码/名称），便于页面定位修正
        String name = line.customerName.isEmpty() ? TmsUtil.str(line.rawName) : line.customerName;
        jdbc.update("INSERT INTO fin_adv_init(line_id, import_batch_no, row_no, customer_code, customer_name, " +
                        "original_bill_no, original_bill_date, adv_amount, remark, " +
                        "line_status, error_msg, task_no, posted, created_by, created_by_name) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'N',?,?)",
                TmsUtil.uuid("AVI"),
                batchNo == null ? support.nextBatchNo("ADV") : batchNo,
                rowNo, line.customerCode, name,
                nullIfEmpty(line.originalBillNo),
                line.originalBillDate == null ? null : Date.valueOf(line.originalBillDate),
                line.advAmount, nullIfEmpty(line.remark),
                status, line.error, taskNo,
                support.currentUserId(), support.currentUserName());
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /** 解析承载结构。 */
    private static class ParsedLine {
        String customerCode;
        String customerName;
        String rawName;
        String originalBillNo;
        LocalDate originalBillDate;
        BigDecimal advAmount = BigDecimal.ZERO;
        String remark;
        String error;
    }
}
