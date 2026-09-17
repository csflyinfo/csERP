package com.erp.finance.init;

import com.erp.finance.account.SupplierAccountService;
import com.erp.finance.dayclose.BizDayCloseGuard;
import com.erp.init.InitConst;
import com.erp.init.InitSupport;
import com.erp.system.OperationAction;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已上线供应商期初预付补录（PRD-36 M4，设计 §7.2）。
 *
 * <p>与初始化期「应付期初里带一列预付」的区别：
 * <ul>
 *   <li>暂存同样落 fin_ap_init，但 line_kind='PREPAY_SUPPLEMENT'，ap_amount 恒 0；</li>
 *   <li>独立批号写 biz_init_post(init_type='AP_PREPAY')，不设「已建账」锁定标志，可反复多批补录；</li>
 *   <li>过账日=审核当日（QCYF 流水 post_date/occurred_at 均为当日），不回填历史日期；</li>
 *   <li>过账前提：当日未业务日结封账；总账已启用时当月会计期间不能是已结账/已冻结；</li>
 *   <li>每批可在「批号内任一行未被下游占用」时整批反建账（封账/月结同样拦截）。</li>
 * </ul>
 * 权限复用 fin.init_ap.*（补录是应付期初模块的上线后通道，不另造权限码）。
 */
@Service
public class ApPrepaySupplementService {

    private static final String MODULE = "fin.init_ap_prepay";
    private static final String MODULE_LABEL = "供应商预付补录";
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    public static final String[][] FIELDS = {
            {"供应商编码", "supplierCode"}, {"供应商名称", "supplierName"},
            {"期初预付金额", "prepayAmount"}, {"备注", "remark"},
    };

    private final JdbcTemplate jdbc;
    private final InitSupport support;
    private final BizDayCloseGuard dayClose;
    private final SupplierAccountService supplierAccountService;

    public ApPrepaySupplementService(JdbcTemplate jdbc, InitSupport support, BizDayCloseGuard dayClose,
                                     SupplierAccountService supplierAccountService) {
        this.jdbc = jdbc;
        this.support = support;
        this.dayClose = dayClose;
        this.supplierAccountService = supplierAccountService;
    }

    // ==================== 状态 ====================

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        boolean todayClosed = dayClose.isClosed(today);
        m.put("today", today.toString());
        m.put("todayClosed", todayClosed);

        String glStatus = glMonthClosedStatus(today);
        boolean glInitialized = support.isGlInitialized();
        m.put("glInitialized", glInitialized);
        m.put("glPeriod", today.format(PERIOD_FMT));
        m.put("glMonthClosed", glStatus != null);
        m.put("glBlockReason", glStatus == null ? null
                : "总账会计期间 " + today.format(PERIOD_FMT) + glStatus + "，不能过账");

        Map<String, Object> sums = TmsUtil.queryCamel(jdbc,
                "SELECT COUNT(*) total_count, " +
                        "COALESCE(SUM(CASE WHEN line_status='VALID' THEN 1 ELSE 0 END),0) valid_count, " +
                        "COALESCE(SUM(CASE WHEN line_status='ERROR' THEN 1 ELSE 0 END),0) error_count, " +
                        "COALESCE(SUM(CASE WHEN line_status='VALID' THEN prepay_amount ELSE 0 END),0) total_prepay_amount " +
                        "FROM fin_ap_init WHERE posted='N' AND line_kind=?",
                InitConst.AP_LINE_KIND_PREPAY_SUPPLEMENT).get(0);
        m.put("lineCount", ((Number) sums.get("validCount")).intValue());
        m.put("errorCount", ((Number) sums.get("errorCount")).intValue());
        m.put("totalPrepayAmount", sums.get("totalPrepayAmount"));
        m.put("canPost", ((Number) sums.get("validCount")).intValue() > 0
                && ((Number) sums.get("errorCount")).intValue() == 0
                && !todayClosed && glStatus == null);

        m.put("batches", listBatches());
        m.put("supplierOpening", supplierOpeningTotals());
        m.put("negativeAp", negativeApSuppliers());
        return m;
    }

    /** 最近补录批号（POSTED/REVERSED 都展示，便于追溯与整批反建账）。 */
    private List<Map<String, Object>> listBatches() {
        return TmsUtil.queryCamel(jdbc,
                "SELECT post_no, line_count, total_amount, status, posted_at, posted_by_name, " +
                        "reverse_reason, reversed_at, reversed_by_name " +
                        "FROM biz_init_post WHERE init_type='" + InitConst.TYPE_AP_PREPAY + "' " +
                        "ORDER BY posted_at DESC LIMIT 20");
    }

    /**
     * 全部 QCYF 期初预付流水（含初始化批号与补录批号；反建账流水是物理删除，查得到即生效），
     * 按供应商汇总，页面提示「该供应商期初/补录预付累计」。
     */
    private List<Map<String, Object>> supplierOpeningTotals() {
        return TmsUtil.queryCamel(jdbc,
                "SELECT supplier_code, MAX(supplier_name) supplier_name, " +
                        "SUM(increase_amount) opening_amount, COUNT(*) batch_line_count " +
                        "FROM fin_supplier_account_flow " +
                        "WHERE account_type='PREPAY' AND biz_type='PREPAY_OPENING' " +
                        "AND COALESCE(reverse_status,'') <> 'REVERSED' " +
                        "GROUP BY supplier_code ORDER BY supplier_code");
    }

    /** 负应付余额供应商（2202 借方重分类预付口径），提示不要把同一笔钱又补录成 1123 预付。 */
    private List<Map<String, Object>> negativeApSuppliers() {
        return TmsUtil.queryCamel(jdbc,
                "SELECT supplier, COALESCE(SUM(unpaid_amount),0) unpaid_amount " +
                        "FROM fin_ap GROUP BY supplier HAVING COALESCE(SUM(unpaid_amount),0) < -0.005 " +
                        "ORDER BY supplier");
    }

    // ==================== 暂存行 / 删除 / 清空 ====================

    public Map<String, Object> linePage(Map<String, Object> req) {
        int pageNo = Math.max(1, TmsUtil.toInt(req.get("pageNo")));
        int sizeInput = TmsUtil.toInt(req.get("pageSize"));
        int pageSize = Math.min(200, sizeInput <= 0 ? 20 : sizeInput);
        StringBuilder where = new StringBuilder(" WHERE line_kind=?");
        List<Object> args = new ArrayList<>();
        args.add(InitConst.AP_LINE_KIND_PREPAY_SUPPLEMENT);
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
            where.append(" AND (supplier_code LIKE ? OR supplier_name LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_ap_init" + where, Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> records = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, import_batch_no, row_no, supplier_code, supplier_name, " +
                        "prepay_amount, remark, line_status, error_msg, task_no, posted, post_no, " +
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

    public void saveLine(Map<String, Object> req) {
        ParsedLine line = parse(req, null);
        if (line.error != null) throw new IllegalArgumentException(line.error);
        insertLine(line, null, null, InitConst.LINE_VALID, null);
        support.opLog().log(MODULE, OperationAction.CREATE, "",
                "新增预付补录行：" + line.supplierName + " " + line.prepayAmount);
    }

    public void updateLine(Map<String, Object> req) {
        String lineId = TmsUtil.str(req.get("lineId"));
        if (lineId.isEmpty()) throw new IllegalArgumentException("缺少行 ID");
        Map<String, Object> old = getLine(lineId);
        if (old == null) throw new IllegalArgumentException("补录行不存在或已删除");
        if ("Y".equals(TmsUtil.str(old.get("posted")))) {
            throw new IllegalArgumentException("该行已随批号 " + TmsUtil.str(old.get("postNo"))
                    + " 过账，不能修改；如需调整请先整批反建账");
        }
        ParsedLine line = parse(req, null);
        if (line.error != null) throw new IllegalArgumentException(line.error);
        jdbc.update("UPDATE fin_ap_init SET supplier_code=?, supplier_name=?, ap_amount=0, " +
                        "prepay_amount=?, original_bill_no=NULL, original_bill_date=NULL, remark=?, " +
                        "line_status='VALID', error_msg=NULL WHERE line_id=?",
                line.supplierCode, line.supplierName, line.prepayAmount,
                line.remark == null || line.remark.isEmpty() ? null : line.remark, lineId);
        support.opLog().log(MODULE, OperationAction.UPDATE, "",
                "修改预付补录行：" + line.supplierName + " " + line.prepayAmount);
    }

    public void deleteLine(String lineId) {
        Map<String, Object> old = getLine(lineId);
        if (old == null) return;
        if ("Y".equals(TmsUtil.str(old.get("posted")))) {
            throw new IllegalArgumentException("该行已随批号过账，不能单独删除；请整批反建账");
        }
        jdbc.update("DELETE FROM fin_ap_init WHERE line_id=?", lineId);
        support.opLog().log(MODULE, OperationAction.DELETE, "",
                "删除预付补录行：" + TmsUtil.str(old.get("supplierName")) + " " + old.get("prepayAmount"));
    }

    public int clear(String batchNo) {
        String sql = "DELETE FROM fin_ap_init WHERE posted='N' AND line_kind=?";
        List<Object> args = new ArrayList<>();
        args.add(InitConst.AP_LINE_KIND_PREPAY_SUPPLEMENT);
        if (batchNo != null && !batchNo.isEmpty()) {
            sql += " AND import_batch_no=?";
            args.add(batchNo);
        }
        int n = jdbc.update(sql, args.toArray());
        support.opLog().log(MODULE, OperationAction.DELETE, "",
                "清空预付补录暂存行 " + n + " 行" + (batchNo == null || batchNo.isEmpty() ? "" : "（批次 " + batchNo + "）"));
        return n;
    }

    // ==================== 导入 ====================

    public Map<String, Object> importRows(List<Map<String, Object>> rows, String fileName) {
        String batchNo = support.nextBatchNo("APP");
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
                InitConst.TASK_MODULE_AP_PREPAY, "供应商期初预付补录导入", fileName, success, failures, FIELDS, rows);
        support.opLog().log(MODULE, OperationAction.IMPORT, recordedTaskNo,
                "供应商期初预付补录导入（文件 " + fileName + "）：成功 " + success + " 行，失败 " + failures.size() + " 行");

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

    // ==================== 过账 / 整批反建账 ====================

    @Transactional
    public Map<String, Object> post() {
        LocalDate today = LocalDate.now();
        if (dayClose.isClosed(today)) {
            throw new IllegalArgumentException("今日（" + today + "）已做业务日结封账，不能补录过账；请先反日结");
        }
        String glStatus = glMonthClosedStatus(today);
        if (glStatus != null) {
            throw new IllegalArgumentException("总账会计期间 " + today.format(PERIOD_FMT) + glStatus
                    + "，不能补录过账；请先在总账反结账");
        }

        Integer errorCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_ap_init WHERE posted='N' AND line_status='ERROR' AND line_kind=?",
                Integer.class, InitConst.AP_LINE_KIND_PREPAY_SUPPLEMENT);
        if (errorCount != null && errorCount > 0) {
            throw new IllegalArgumentException("存在 " + errorCount + " 行错误数据，请修正或删除后再过账");
        }
        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, supplier_code, supplier_name, prepay_amount " +
                        "FROM fin_ap_init WHERE posted='N' AND line_status='VALID' AND line_kind=? " +
                        "ORDER BY COALESCE(row_no,999999), line_id",
                InitConst.AP_LINE_KIND_PREPAY_SUPPLEMENT);
        if (lines.isEmpty()) throw new IllegalArgumentException("没有可过账的有效预付补录行，请先导入或手工新增");

        String postNo = support.nextPostNo();
        BigDecimal total = BigDecimal.ZERO;
        int seq = 0;
        for (Map<String, Object> line : lines) {
            seq++;
            String supplierCode = TmsUtil.str(line.get("supplierCode"));
            String supplierName = TmsUtil.str(line.get("supplierName"));
            BigDecimal amount = TmsUtil.toBd(line.get("prepayAmount"));
            String flowId = supplierAccountService.postPrepayOpening(postNo, seq, today,
                    supplierCode, supplierName, amount);
            jdbc.update("UPDATE fin_ap_init SET posted='Y', post_no=?, generated_prepay_flow_id=? WHERE line_id=?",
                    postNo, flowId, line.get("lineId"));
            total = total.add(amount);
        }
        support.insertPost(postNo, InitConst.TYPE_AP_PREPAY, lines.size(), null, total, "已上线供应商期初预付补录");
        support.opLog().log(MODULE, OperationAction.CREATE, postNo,
                "供应商期初预付补录过账：批号 " + postNo + "，" + lines.size() + " 行，合计 " + total + " 元");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("postNo", postNo);
        result.put("lineCount", lines.size());
        result.put("totalPrepayAmount", total);
        return result;
    }

    @Transactional
    public void reverse(String postNoInput, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("反建账必须填写原因");
        }
        String postNo = TmsUtil.str(postNoInput);
        Map<String, Object> post;
        if (postNo.isEmpty()) {
            // 不传批号默认最近一个生效补录批号
            List<Map<String, Object>> latest = TmsUtil.queryCamel(jdbc,
                    "SELECT post_no FROM biz_init_post WHERE init_type=? AND status='POSTED' "
                            + "ORDER BY posted_at DESC LIMIT 1", InitConst.TYPE_AP_PREPAY);
            if (latest.isEmpty()) throw new IllegalArgumentException("没有可反建账的预付补录批号");
            postNo = TmsUtil.str(latest.get(0).get("postNo"));
        }
        List<Map<String, Object>> posts = TmsUtil.queryCamel(jdbc,
                "SELECT post_no, status, posted_at FROM biz_init_post WHERE post_no=? AND init_type=?",
                postNo, InitConst.TYPE_AP_PREPAY);
        if (posts.isEmpty()) {
            throw new IllegalArgumentException("批号 " + postNo + " 不是预付补录批号");
        }
        post = posts.get(0);
        if (!"POSTED".equals(TmsUtil.str(post.get("status")))) {
            throw new IllegalArgumentException("补录批号 " + postNo + " 已反建账，不能重复操作");
        }

        LocalDate postDate = BizDayCloseGuard.toLocalDate(post.get("postedAt"));
        if (postDate != null && dayClose.isClosed(postDate)) {
            throw new IllegalArgumentException("批号 " + postNo + " 的过账日 " + postDate
                    + " 已业务日结封账，不能反建账；请先反日结");
        }
        LocalDate glProbeDate = postDate == null ? LocalDate.now() : postDate;
        String glStatus = glMonthClosedStatus(glProbeDate);
        if (glStatus != null) {
            throw new IllegalArgumentException("批号 " + postNo + " 过账当月会计期间 "
                    + glProbeDate.format(PERIOD_FMT) + glStatus + "，不能反建账；请先在总账反结账");
        }

        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, supplier_code, generated_prepay_flow_id " +
                        "FROM fin_ap_init WHERE posted='Y' AND post_no=? AND line_kind=?",
                postNo, InitConst.AP_LINE_KIND_PREPAY_SUPPLEMENT);
        if (lines.isEmpty()) throw new IllegalArgumentException("批号 " + postNo + " 下没有补录行");
        for (Map<String, Object> line : lines) {
            String flowId = TmsUtil.str(line.get("generatedPrepayFlowId"));
            if (flowId.isEmpty()) continue;
            String blocked = supplierAccountService.checkPrepayOpeningReversable(
                    TmsUtil.str(line.get("supplierCode")), flowId);
            if (blocked != null) throw new IllegalArgumentException(blocked);
        }
        for (Map<String, Object> line : lines) {
            String flowId = TmsUtil.str(line.get("generatedPrepayFlowId"));
            if (!flowId.isEmpty()) {
                supplierAccountService.deletePrepayOpening(flowId, TmsUtil.str(line.get("supplierCode")));
            }
            jdbc.update("UPDATE fin_ap_init SET posted='N', post_no=NULL, generated_prepay_flow_id=NULL " +
                    "WHERE line_id=?", line.get("lineId"));
        }
        support.markPostReversed(postNo, reason);
        support.opLog().log(MODULE, OperationAction.REVERSE, postNo,
                "供应商期初预付补录反建账：批号 " + postNo + "，回退 " + lines.size() + " 行。原因：" + reason);
    }

    // ==================== 解析 / 落库 / 守卫 ====================

    /** 总账已启用时，指定日期所在会计期间若已结账/已冻结，返回状态串（「已结账」/「已冻结」）；否则 null。 */
    private String glMonthClosedStatus(LocalDate date) {
        if (!support.isGlInitialized()) return null;
        String period = date.format(PERIOD_FMT);
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM fin_accounting_period WHERE period=?", String.class, period);
        if (statuses.isEmpty()) return null; // 期间行尚未建（未走到月结流程），不拦截
        String status = statuses.get(0);
        if ("已结账".equals(status) || "已冻结".equals(status)) return status;
        return null;
    }

    private Map<String, Object> getLine(String lineId) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, supplier_name, prepay_amount, posted, post_no FROM fin_ap_init WHERE line_id=?",
                lineId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ParsedLine parse(Map<String, Object> r, Set<String> batchKeys) {
        ParsedLine p = new ParsedLine();
        p.supplierCode = TmsUtil.str(r.get("supplierCode"));
        p.supplierName = TmsUtil.str(r.get("supplierName"));
        p.rawName = p.supplierName;
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

        String amountRaw = TmsUtil.str(r.get("prepayAmount"));
        if (amountRaw.isEmpty()) {
            p.error = "期初预付金额必填";
            return p;
        }
        try {
            p.prepayAmount = new BigDecimal(amountRaw.replace(",", ""));
        } catch (NumberFormatException e) {
            p.error = "期初预付金额不是合法数字：" + amountRaw;
            return p;
        }
        if (p.prepayAmount.signum() <= 0) {
            p.error = "期初预付金额必须大于 0";
            return p;
        }
        p.prepayAmount = p.prepayAmount.setScale(2, RoundingMode.HALF_UP);

        if (batchKeys != null && !batchKeys.add(p.supplierCode)) {
            p.error = "同一文件内供应商「" + p.supplierCode + "」出现多行，请先在表内合并金额";
        }
        return p;
    }

    private Map<String, Object> lookupSupplier(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT supplier_code, supplier_name FROM base_supplier WHERE supplier_code=? LIMIT 1", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertLine(ParsedLine line, String batchNo, Integer rowNo, String status, String taskNo) {
        String name = line.supplierName.isEmpty() ? TmsUtil.str(line.rawName) : line.supplierName;
        jdbc.update("INSERT INTO fin_ap_init(line_id, import_batch_no, row_no, supplier_code, supplier_name, " +
                        "ap_amount, prepay_amount, remark, line_status, error_msg, task_no, posted, line_kind, " +
                        "created_by, created_by_name) " +
                        "VALUES (?,?,?,?,?,0,?,?,?,?,?,'N',?, ?,?)",
                TmsUtil.uuid("PI"),
                batchNo == null ? support.nextBatchNo("APP") : batchNo,
                rowNo, line.supplierCode, name,
                line.prepayAmount, line.remark == null || line.remark.isEmpty() ? null : line.remark,
                status, line.error, taskNo, InitConst.AP_LINE_KIND_PREPAY_SUPPLEMENT,
                support.currentUserId(), support.currentUserName());
    }

    private static class ParsedLine {
        String supplierCode;
        String supplierName;
        String rawName;
        BigDecimal prepayAmount = BigDecimal.ZERO;
        String remark;
        String error;
    }
}
