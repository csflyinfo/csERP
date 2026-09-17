package com.erp.finance.init;

import com.erp.common.util.BillNoGenerator;
import com.erp.finance.account.SupplierAccountService;
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
import java.util.UUID;

/**
 * 供应商应付期初初始化（PRD-34；PRD-36 M4 起同批号支持期初预付）。
 *
 * <p>与应收期初同构：暂存 fin_ap_init（VALID/ERROR，line_kind='AP_INIT'）→ 过账：
 * <ul>
 *   <li>ap_amount&gt;0：写 fin_ap（paid=0、invoiced_amount=0、invoice_status='未来票'、
 *       source_bill=QCAP-{过批号}-{序号}，due_date 按建账日+30 立账）；</li>
 *   <li>prepay_amount&gt;0：写供应商账户预付期初流水（source_bill=QCYF-{过批号}-{序号}、
 *       bizKey=PPO:{批号}:{序号}，不造付款单、不动资金），回写 generated_prepay_flow_id；</li>
 *   <li>同一行两项可同时存在，序号同源；两项都为 0 的行不允许入账。</li>
 * </ul>
 * 日结前可整批反建账：应付侧要求未核销/无付款，预付侧要求期初流水未被下游占用。
 */
@Service
public class ApInitService {

    private static final String MODULE = "fin.init_ap";
    private static final String MODULE_LABEL = "供应商应付期初";

    public static final String[][] FIELDS = {
            {"供应商编码", "supplierCode"}, {"供应商名称", "supplierName"},
            {"原单据号", "originalBillNo"}, {"原单据日期", "originalBillDate"},
            {"应付金额", "apAmount"}, {"期初预付金额", "prepayAmount"}, {"备注", "remark"},
    };

    private final JdbcTemplate jdbc;
    private final InitSupport support;
    private final BillNoGenerator billNoGenerator;
    private final SupplierAccountService supplierAccountService;

    public ApInitService(JdbcTemplate jdbc, InitSupport support, BillNoGenerator billNoGenerator,
                         SupplierAccountService supplierAccountService) {
        this.jdbc = jdbc;
        this.support = support;
        this.billNoGenerator = billNoGenerator;
        this.supplierAccountService = supplierAccountService;
    }

    // ==================== 状态 / 分页 ====================

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean posted = support.isPosted(InitConst.PARAM_AP_POSTED);
        m.put("posted", posted);
        m.put("dayCloseLocked", support.hasDayClose());
        m.put("glInitialized", support.isGlInitialized());

        Map<String, Object> sums = TmsUtil.queryCamel(jdbc,
                "SELECT COUNT(*) total_count, " +
                        "COALESCE(SUM(CASE WHEN line_status='VALID' THEN 1 ELSE 0 END),0) valid_count, " +
                        "COALESCE(SUM(CASE WHEN line_status='ERROR' THEN 1 ELSE 0 END),0) error_count, " +
                        "COALESCE(SUM(CASE WHEN line_status='VALID' THEN ap_amount ELSE 0 END),0) total_ap_amount, " +
                        "COALESCE(SUM(CASE WHEN line_status='VALID' THEN prepay_amount ELSE 0 END),0) total_prepay_amount " +
                        "FROM fin_ap_init WHERE posted='N' AND line_kind='" + InitConst.AP_LINE_KIND_INIT + "'").get(0);
        m.put("lineCount", ((Number) sums.get("validCount")).intValue());
        m.put("errorCount", ((Number) sums.get("errorCount")).intValue());
        m.put("totalAmount", sums.get("totalApAmount"));
        m.put("totalPrepayAmount", sums.get("totalPrepayAmount"));
        m.put("canPost", !posted && !support.hasDayClose() && !support.isGlInitialized()
                && ((Number) sums.get("validCount")).intValue() > 0
                && ((Number) sums.get("errorCount")).intValue() == 0);
        m.put("canReverse", posted && !support.hasDayClose() && !support.isGlInitialized());

        Map<String, Object> post = support.activePost(InitConst.TYPE_AP);
        if (post != null) {
            m.put("postNo", post.get("postNo"));
            m.put("postAt", post.get("postedAt"));
            m.put("postByName", post.get("postedByName"));
            m.put("postLineCount", post.get("lineCount"));
            m.put("postTotalAmount", post.get("totalAmount"));
            // biz_init_post 只有一个总额列（记应付合计），预付合计从已过账暂存行回取
            m.put("postTotalPrepayAmount", jdbc.queryForObject(
                    "SELECT COALESCE(SUM(prepay_amount),0) FROM fin_ap_init "
                            + "WHERE posted='Y' AND line_kind='" + InitConst.AP_LINE_KIND_INIT + "'",
                    BigDecimal.class));
        }
        return m;
    }

    public Map<String, Object> linePage(Map<String, Object> req) {
        int pageNo = Math.max(1, TmsUtil.toInt(req.get("pageNo")));
        int sizeInput = TmsUtil.toInt(req.get("pageSize"));
        int pageSize = Math.min(200, sizeInput <= 0 ? 20 : sizeInput);
        StringBuilder where = new StringBuilder(" WHERE line_kind='" + InitConst.AP_LINE_KIND_INIT + "'");
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
            where.append(" AND (supplier_code LIKE ? OR supplier_name LIKE ? OR original_bill_no LIKE ?)");
            for (int i = 0; i < 3; i++) args.add("%" + keyword + "%");
        }
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_ap_init" + where, Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> records = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, import_batch_no, row_no, supplier_code, supplier_name, " +
                        "original_bill_no, original_bill_date, ap_amount, prepay_amount, remark, " +
                        "line_status, error_msg, task_no, posted, post_no, generated_ap_no, " +
                        "generated_prepay_flow_id, created_at " +
                        "FROM fin_ap_init" + where +
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

    public void saveLine(Map<String, Object> req) {
        support.assertEditable(InitConst.PARAM_AP_POSTED, MODULE_LABEL);
        ParsedLine line = parse(req, null);
        if (line.error != null) throw new IllegalArgumentException(line.error);
        insertLine(line, null, null, InitConst.LINE_VALID, null);
        support.opLog().log(MODULE, OperationAction.CREATE, "",
                "新增应付期初行：" + line.supplierName + " 应付 " + line.apAmount + " 预付 " + line.prepayAmount);
    }

    public void updateLine(Map<String, Object> req) {
        support.assertEditable(InitConst.PARAM_AP_POSTED, MODULE_LABEL);
        String lineId = TmsUtil.str(req.get("lineId"));
        if (lineId.isEmpty()) throw new IllegalArgumentException("缺少行 ID");
        Map<String, Object> old = getLine(lineId);
        if (old == null) throw new IllegalArgumentException("期初行不存在或已删除");
        if ("Y".equals(TmsUtil.str(old.get("posted")))) {
            throw new IllegalArgumentException("该行已建账，不能修改");
        }
        if (!InitConst.AP_LINE_KIND_INIT.equals(TmsUtil.str(old.get("lineKind")))) {
            throw new IllegalArgumentException("该行是预付补录行，请在预付补录中维护");
        }
        ParsedLine line = parse(req, null);
        if (line.error != null) throw new IllegalArgumentException(line.error);
        jdbc.update("UPDATE fin_ap_init SET supplier_code=?, supplier_name=?, original_bill_no=?, " +
                        "original_bill_date=?, ap_amount=?, prepay_amount=?, remark=?, " +
                        "line_status='VALID', error_msg=NULL WHERE line_id=?",
                line.supplierCode, line.supplierName, nullIfEmpty(line.originalBillNo),
                line.originalBillDate == null ? null : Date.valueOf(line.originalBillDate),
                line.apAmount, line.prepayAmount, nullIfEmpty(line.remark), lineId);
        support.opLog().log(MODULE, OperationAction.UPDATE, "",
                "修改应付期初行：" + line.supplierName + " 应付 " + line.apAmount + " 预付 " + line.prepayAmount);
    }

    public void deleteLine(String lineId) {
        support.assertEditable(InitConst.PARAM_AP_POSTED, MODULE_LABEL);
        Map<String, Object> old = getLine(lineId);
        if (old == null) return;
        if ("Y".equals(TmsUtil.str(old.get("posted")))) {
            throw new IllegalArgumentException("该行已建账，不能删除");
        }
        if (!InitConst.AP_LINE_KIND_INIT.equals(TmsUtil.str(old.get("lineKind")))) {
            throw new IllegalArgumentException("该行是预付补录行，请在预付补录中维护");
        }
        jdbc.update("DELETE FROM fin_ap_init WHERE line_id=?", lineId);
        support.opLog().log(MODULE, OperationAction.DELETE, "",
                "删除应付期初行：" + TmsUtil.str(old.get("supplierName")) + " 应付 " + old.get("apAmount"));
    }

    public int clear(String batchNo) {
        support.assertEditable(InitConst.PARAM_AP_POSTED, MODULE_LABEL);
        String sql = "DELETE FROM fin_ap_init WHERE posted='N' AND line_kind='"
                + InitConst.AP_LINE_KIND_INIT + "'";
        List<Object> args = new ArrayList<>();
        if (batchNo != null && !batchNo.isEmpty()) {
            sql += " AND import_batch_no=?";
            args.add(batchNo);
        }
        int n = jdbc.update(sql, args.toArray());
        support.opLog().log(MODULE, OperationAction.DELETE, "",
                "清空应付期初暂存行 " + n + " 行" + (batchNo == null || batchNo.isEmpty() ? "" : "（批次 " + batchNo + "）"));
        return n;
    }

    // ==================== 导入 ====================

    public Map<String, Object> importRows(List<Map<String, Object>> rows, String fileName) {
        support.assertEditable(InitConst.PARAM_AP_POSTED, MODULE_LABEL);
        String batchNo = support.nextBatchNo("AP");
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
                InitConst.TASK_MODULE_AP, "供应商应付期初导入", fileName, success, failures, FIELDS, rows);
        support.opLog().log(MODULE, OperationAction.IMPORT, recordedTaskNo,
                "供应商应付期初导入（文件 " + fileName + "）：成功 " + success + " 行，失败 " + failures.size() + " 行");

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
        support.assertEditable(InitConst.PARAM_AP_POSTED, MODULE_LABEL);
        Integer errorCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_ap_init WHERE posted='N' AND line_status='ERROR' "
                        + "AND line_kind='" + InitConst.AP_LINE_KIND_INIT + "'", Integer.class);
        if (errorCount != null && errorCount > 0) {
            throw new IllegalArgumentException("存在 " + errorCount + " 行错误数据，请修正或删除后再建账");
        }
        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, supplier_code, supplier_name, ap_amount, prepay_amount, remark " +
                        "FROM fin_ap_init WHERE posted='N' AND line_status='VALID' "
                        + "AND line_kind='" + InitConst.AP_LINE_KIND_INIT + "' "
                        + "ORDER BY COALESCE(row_no,999999), line_id");
        if (lines.isEmpty()) throw new IllegalArgumentException("没有可建账的有效应付/预付期初行，请先导入或手工新增");

        String postNo = support.nextPostNo();
        LocalDate today = LocalDate.now();
        Date dueDate = Date.valueOf(today.plusDays(30));
        BigDecimal apTotal = BigDecimal.ZERO;
        BigDecimal prepayTotal = BigDecimal.ZERO;
        int apCount = 0;
        int prepayCount = 0;
        int seq = 0;
        for (Map<String, Object> line : lines) {
            seq++;
            String lineId = TmsUtil.str(line.get("lineId"));
            String supplierCode = TmsUtil.str(line.get("supplierCode"));
            String supplierName = TmsUtil.str(line.get("supplierName"));
            BigDecimal apAmount = TmsUtil.toBd(line.get("apAmount"));
            BigDecimal prepayAmount = TmsUtil.toBd(line.get("prepayAmount"));
            String apNo = null;
            String prepayFlowId = null;

            if (apAmount.signum() > 0) {
                apNo = billNoGenerator.nextNo("AP", "fin_ap", "ap_no");
                String apId = "AP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
                String sourceBill = InitConst.PREFIX_AP + "-" + postNo + "-" + seq;
                jdbc.update("INSERT INTO fin_ap(ap_id, ap_no, source_bill, supplier, ap_amount, " +
                                "paid_amount, unpaid_amount, due_date, status, reconcile_status, " +
                                "invoiced_amount, invoice_status) " +
                                "VALUES (?,?,?,?,?,0,?,?,'UNVERIFIED','未对账',0,'未来票')",
                        apId, apNo, sourceBill, supplierName, apAmount, apAmount, dueDate);
                // 同步写 AP_OPENING 形成流水（bizKey=AP:单号，与账户 repair 补缺同源幂等），账户应付余额随流水滚存
                supplierAccountService.postApOpening(
                        apNo, sourceBill, today, supplierCode, supplierName, apAmount);
                apTotal = apTotal.add(apAmount);
                apCount++;
            }
            if (prepayAmount.signum() > 0) {
                // 与 QCAP 同批号同序号写 QCYF 预付期初流水（不造付款单、不动资金）
                prepayFlowId = supplierAccountService.postPrepayOpening(
                        postNo, seq, today, supplierCode, supplierName, prepayAmount);
                prepayTotal = prepayTotal.add(prepayAmount);
                prepayCount++;
            }
            jdbc.update("UPDATE fin_ap_init SET posted='Y', post_no=?, generated_ap_no=?, " +
                            "generated_prepay_flow_id=? WHERE line_id=?",
                    postNo, apNo, prepayFlowId, lineId);
        }
        support.insertPost(postNo, InitConst.TYPE_AP, lines.size(), null, apTotal,
                "供应商应付期初建账（应付 " + apCount + " 行/" + apTotal + " 元，期初预付 "
                        + prepayCount + " 行/" + prepayTotal + " 元）");
        support.setPostedFlag(InitConst.PARAM_AP_POSTED, InitConst.PARAM_AP_POST_NO, postNo, MODULE_LABEL);
        support.opLog().log(MODULE, OperationAction.CREATE, postNo,
                "供应商应付期初建账：批号 " + postNo + "，" + lines.size() + " 行，应付合计 " + apTotal
                        + " 元，期初预付合计 " + prepayTotal + " 元");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("postNo", postNo);
        result.put("lineCount", lines.size());
        result.put("totalAmount", apTotal);
        result.put("totalPrepayAmount", prepayTotal);
        return result;
    }

    @Transactional
    public void reverse(String reason) {
        support.assertReversable(InitConst.PARAM_AP_POSTED, MODULE_LABEL);
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("反建账必须填写原因");
        }
        Map<String, Object> post = support.activePost(InitConst.TYPE_AP);
        if (post == null) throw new IllegalArgumentException("未找到已生效的应付期初建账记录");
        String postNo = TmsUtil.str(post.get("postNo"));

        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, supplier_code, generated_ap_no, generated_prepay_flow_id " +
                        "FROM fin_ap_init WHERE posted='Y' AND post_no=? "
                        + "AND line_kind='" + InitConst.AP_LINE_KIND_INIT + "'", postNo);
        if (lines.isEmpty()) throw new IllegalArgumentException("批号 " + postNo + " 下没有期初行");
        // 第一轮：应付侧 + 预付侧下游守卫，任一被占用则整批拒绝
        for (Map<String, Object> line : lines) {
            String apNo = TmsUtil.str(line.get("generatedApNo"));
            if (!apNo.isEmpty()) {
                Integer reconcileCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fin_reconcile_record WHERE business_no=?", Integer.class, apNo);
                if (reconcileCount != null && reconcileCount > 0) {
                    throw new IllegalArgumentException("应付单 " + apNo + " 已发生付款核销，不能反建账；"
                            + "请先红冲/删除对应付款单后再操作");
                }
                BigDecimal paid = jdbc.queryForObject(
                        "SELECT COALESCE(paid_amount,0) FROM fin_ap WHERE ap_no=?", BigDecimal.class, apNo);
                if (paid != null && paid.signum() > 0) {
                    throw new IllegalArgumentException("应付单 " + apNo + " 已有付款金额，不能反建账");
                }
            }
            String prepayFlowId = TmsUtil.str(line.get("generatedPrepayFlowId"));
            if (!prepayFlowId.isEmpty()) {
                String blocked = supplierAccountService.checkPrepayOpeningReversable(
                        TmsUtil.str(line.get("supplierCode")), prepayFlowId);
                if (blocked != null) throw new IllegalArgumentException(blocked);
            }
        }
        // 第二轮：同批回退 QCAP 应付单与 QCYF 预付流水
        for (Map<String, Object> line : lines) {
            String apNo = TmsUtil.str(line.get("generatedApNo"));
            if (!apNo.isEmpty()) {
                jdbc.update("DELETE FROM fin_ap WHERE ap_no=?", apNo);
                // 同步删除 AP_OPENING 形成流水并重排应付余额链（守卫已确保无未冲销下游结算流水）
                supplierAccountService.deleteApOpening(
                        apNo, TmsUtil.str(line.get("supplierCode")));
            }
            String prepayFlowId = TmsUtil.str(line.get("generatedPrepayFlowId"));
            if (!prepayFlowId.isEmpty()) {
                supplierAccountService.deletePrepayOpening(
                        prepayFlowId, TmsUtil.str(line.get("supplierCode")));
            }
            jdbc.update("UPDATE fin_ap_init SET posted='N', post_no=NULL, generated_ap_no=NULL, " +
                            "generated_prepay_flow_id=NULL WHERE line_id=?",
                    line.get("lineId"));
        }
        support.markPostReversed(postNo, reason);
        support.clearPostedFlag(InitConst.PARAM_AP_POSTED, InitConst.PARAM_AP_POST_NO);
        support.opLog().log(MODULE, OperationAction.REVERSE, postNo,
                "供应商应付期初反建账：批号 " + postNo + "，回退 " + lines.size() + " 行。原因：" + reason);
    }

    // ==================== 行解析 / 落库 ====================

    private Map<String, Object> getLine(String lineId) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, supplier_name, ap_amount, prepay_amount, posted, line_kind " +
                        "FROM fin_ap_init WHERE line_id=?", lineId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ParsedLine parse(Map<String, Object> r, Set<String> batchKeys) {
        ParsedLine p = new ParsedLine();
        p.supplierCode = TmsUtil.str(r.get("supplierCode"));
        p.supplierName = TmsUtil.str(r.get("supplierName"));
        p.rawName = p.supplierName;
        p.originalBillNo = TmsUtil.str(r.get("originalBillNo"));
        p.remark = TmsUtil.str(r.get("remark"));

        if (p.supplierCode.isEmpty()) {
            p.error = "供应商编码必填";
            return p;
        }
        Map<String, Object> supplier = lookupSupplier(p.supplierCode);
        if (supplier == null) {
            p.error = "供应商编码「" + p.supplierCode + "」不存在，须先在供应商档案中维护";
            return p;
        }
        p.supplierName = TmsUtil.str(supplier.get("supplierName"));

        String dateRaw = TmsUtil.str(r.get("originalBillDate"));
        if (!dateRaw.isEmpty()) {
            p.originalBillDate = TmsUtil.toLocalDate(dateRaw);
            if (p.originalBillDate == null) {
                p.error = "原单据日期格式不正确：" + dateRaw + "（应为 yyyy-MM-dd）";
                return p;
            }
        }
        String amountError = parseAmount(r.get("apAmount"), "应付金额", p, true);
        if (amountError != null) {
            p.error = amountError;
            return p;
        }
        amountError = parseAmount(r.get("prepayAmount"), "期初预付金额", p, false);
        if (amountError != null) {
            p.error = amountError;
            return p;
        }
        if (p.apAmount.signum() == 0 && p.prepayAmount.signum() == 0) {
            p.error = "应付金额与期初预付金额至少一项大于 0（可只填一项，也可两项同填）";
            return p;
        }

        if (batchKeys != null) {
            String key = p.supplierCode + "|" + p.originalBillNo.toUpperCase(Locale.ROOT);
            if (!batchKeys.add(key)) {
                p.error = "同一文件内供应商+原单据号重复：" + p.supplierCode
                        + (p.originalBillNo.isEmpty() ? "" : " / " + p.originalBillNo);
            }
        }
        return p;
    }

    /**
     * 解析一个金额列。缺省/空白：应付列按 0（允许只有预付的行），期初预付列按 0（旧模板无此列，兼容导入）。
     * 填了就必须是 ≥0 的合法数字，最多 2 位小数。
     */
    private String parseAmount(Object raw, String label, ParsedLine p, boolean apSide) {
        String amountRaw = TmsUtil.str(raw);
        if (amountRaw.isEmpty()) {
            if (apSide) p.apAmount = BigDecimal.ZERO;
            else p.prepayAmount = BigDecimal.ZERO;
            return null;
        }
        BigDecimal v;
        try {
            v = new BigDecimal(amountRaw.replace(",", ""));
        } catch (NumberFormatException e) {
            return label + "不是合法数字：" + amountRaw;
        }
        if (v.signum() < 0) {
            return label + "必须大于等于 0（负数往来请走业务单据）：" + amountRaw;
        }
        v = v.setScale(2, RoundingMode.HALF_UP);
        if (apSide) p.apAmount = v;
        else p.prepayAmount = v;
        return null;
    }

    private Map<String, Object> lookupSupplier(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT supplier_code, supplier_name FROM base_supplier WHERE supplier_code=? LIMIT 1", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertLine(ParsedLine line, String batchNo, Integer rowNo, String status, String taskNo) {
        String name = line.supplierName.isEmpty() ? TmsUtil.str(line.rawName) : line.supplierName;
        jdbc.update("INSERT INTO fin_ap_init(line_id, import_batch_no, row_no, supplier_code, supplier_name, " +
                        "original_bill_no, original_bill_date, ap_amount, prepay_amount, remark, " +
                        "line_status, error_msg, task_no, posted, line_kind, created_by, created_by_name) " +
                        // 列顺序 task_no=?, posted='N', line_kind=?：前 13 个 ? 到 task_no，错位会把导入任务号写进 posted CHAR(1)
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, 'N', ?, ?, ?)",
                TmsUtil.uuid("PI"),
                batchNo == null ? support.nextBatchNo("AP") : batchNo,
                rowNo, line.supplierCode, name,
                nullIfEmpty(line.originalBillNo),
                line.originalBillDate == null ? null : Date.valueOf(line.originalBillDate),
                line.apAmount, line.prepayAmount, nullIfEmpty(line.remark),
                status, line.error, taskNo, InitConst.AP_LINE_KIND_INIT,
                support.currentUserId(), support.currentUserName());
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static class ParsedLine {
        String supplierCode;
        String supplierName;
        String rawName;
        String originalBillNo;
        LocalDate originalBillDate;
        BigDecimal apAmount = BigDecimal.ZERO;
        BigDecimal prepayAmount = BigDecimal.ZERO;
        String remark;
        String error;
    }
}
