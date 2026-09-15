package com.erp.inventory.init;

import com.erp.init.InitConst;
import com.erp.init.InitSupport;
import com.erp.inventory.service.InventoryCostService;
import com.erp.system.OperationAction;
import com.erp.tms.TmsUtil;
import com.erp.wms.BinStockService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 库存期初初始化（PRD-34）。
 *
 * <p>两种导入流程（同一商品+仓库+批次禁止混用）：
 * <ul>
 *   <li>BATCH：按商品+批次，过账写财务三层账（inv_batch_stock / inv_stock_balance / inv_stock_ledger）；</li>
 *   <li>BIN：按库位，过账在财务三层账之外同时写 wms_bin_stock 位置账（+wms_bin.used_qty+bin log）。</li>
 * </ul>
 *
 * <p>过账：按(商品+仓库)预聚合移动加权均价 Σ(qty×单价)/Σqty 作为建账成本，
 * 每(商品+仓库+批次)调一次 {@link InventoryCostService#inboundAtCurrentCost}，
 * 台账来源号 QTRK-{过批号}-{序号}；已有数量余额整批拒绝，必须先清零。
 * 反建账：无日结、总账未启用、过账后无任何后续出入库/移位流水时，按来源号回退全部三账。
 */
@Service
public class StockInitService {

    private static final String MODULE = "inv.init_stock";
    private static final String MODULE_LABEL = "库存期初";
    private static final DateTimeFormatter BATCH_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 按批次导入/失败文件列。 */
    public static final String[][] FIELDS_BATCH = {
            {"商品编码", "goodsCode"}, {"商品名称", "goodsName"},
            {"仓库编码", "warehouseCode"}, {"仓库名称", "warehouseName"},
            {"批号", "batchNoInput"}, {"生产日期", "productionDate"}, {"效期日期", "expiryDate"},
            {"数量", "qty"}, {"成本单价", "unitPrice"}, {"备注", "remark"},
    };

    /** 按库位导入/失败文件列。 */
    public static final String[][] FIELDS_BIN = {
            {"商品编码", "goodsCode"}, {"商品名称", "goodsName"},
            {"仓库编码", "warehouseCode"}, {"仓库名称", "warehouseName"},
            {"库位编码", "binCode"}, {"容器编码", "containerCode"},
            {"批号", "batchNoInput"}, {"生产日期", "productionDate"}, {"效期日期", "expiryDate"},
            {"数量", "qty"}, {"成本单价", "unitPrice"}, {"备注", "remark"},
    };

    private final JdbcTemplate jdbc;
    private final InitSupport support;
    private final InventoryCostService inventoryCost;
    private final BinStockService binStockService;

    public StockInitService(JdbcTemplate jdbc, InitSupport support,
                            InventoryCostService inventoryCost, BinStockService binStockService) {
        this.jdbc = jdbc;
        this.support = support;
        this.inventoryCost = inventoryCost;
        this.binStockService = binStockService;
    }

    // ==================== 状态 / 分页 ====================

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean posted = support.isPosted(InitConst.PARAM_STOCK_POSTED);
        m.put("posted", posted);
        m.put("dayCloseLocked", support.hasDayClose());
        m.put("glInitialized", support.isGlInitialized());

        Map<String, Object> sums = TmsUtil.queryCamel(jdbc,
                "SELECT COUNT(*) total_count, " +
                        "COALESCE(SUM(CASE WHEN line_status='VALID' THEN 1 ELSE 0 END),0) valid_count, " +
                        "COALESCE(SUM(CASE WHEN line_status='ERROR' THEN 1 ELSE 0 END),0) error_count, " +
                        "COALESCE(SUM(CASE WHEN line_status='VALID' THEN qty ELSE 0 END),0) total_qty, " +
                        "COALESCE(SUM(CASE WHEN line_status='VALID' THEN amount ELSE 0 END),0) total_amount, " +
                        "COALESCE(SUM(CASE WHEN line_status='VALID' AND COALESCE(unit_price,0)=0 THEN 1 ELSE 0 END),0) zero_cost_count, " +
                        "COALESCE(SUM(CASE WHEN init_mode='BIN' AND line_status='VALID' THEN 1 ELSE 0 END),0) bin_count, " +
                        "COALESCE(SUM(CASE WHEN init_mode='BATCH' AND line_status='VALID' THEN 1 ELSE 0 END),0) batch_count " +
                        "FROM inv_stock_init WHERE posted='N'").get(0);
        m.put("lineCount", ((Number) sums.get("validCount")).intValue());
        m.put("errorCount", ((Number) sums.get("errorCount")).intValue());
        m.put("totalQty", sums.get("totalQty"));
        m.put("totalAmount", sums.get("totalAmount"));
        m.put("zeroCostCount", ((Number) sums.get("zeroCostCount")).intValue());
        m.put("binCount", ((Number) sums.get("binCount")).intValue());
        m.put("batchCount", ((Number) sums.get("batchCount")).intValue());
        m.put("canPost", !posted && !support.hasDayClose() && !support.isGlInitialized()
                && ((Number) sums.get("validCount")).intValue() > 0
                && ((Number) sums.get("errorCount")).intValue() == 0);
        m.put("canReverse", posted && !support.hasDayClose() && !support.isGlInitialized());

        Map<String, Object> post = support.activePost(InitConst.TYPE_STOCK);
        if (post != null) {
            m.put("postNo", post.get("postNo"));
            m.put("postAt", post.get("postedAt"));
            m.put("postByName", post.get("postedByName"));
            m.put("postLineCount", post.get("lineCount"));
            m.put("postTotalQty", post.get("totalQty"));
            m.put("postTotalAmount", post.get("totalAmount"));
        }
        return m;
    }

    public Map<String, Object> linePage(Map<String, Object> req) {
        int pageNo = Math.max(1, TmsUtil.toInt(req.get("pageNo")));
        int sizeInput = TmsUtil.toInt(req.get("pageSize"));
        int pageSize = Math.min(200, sizeInput <= 0 ? 20 : sizeInput);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        for (String[] f : new String[][]{{"posted", "posted"}, {"lineStatus", "line_status"},
                {"initMode", "init_mode"}, {"batchNo", "import_batch_no"}}) {
            String v = TmsUtil.str(req.get(f[0]));
            if (!v.isEmpty()) {
                where.append(" AND ").append(f[1]).append("=?");
                args.add(v);
            }
        }
        String keyword = TmsUtil.str(req.get("keyword"));
        if (!keyword.isEmpty()) {
            where.append(" AND (goods_code LIKE ? OR goods_name LIKE ? OR warehouse_name LIKE ? " +
                    "OR bin_code LIKE ? OR batch_no LIKE ?)");
            for (int i = 0; i < 5; i++) args.add("%" + keyword + "%");
        }
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inv_stock_init" + where, Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> records = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, import_batch_no, row_no, init_mode, goods_code, goods_name, " +
                        "warehouse_code, warehouse_name, bin_code, container_code, batch_no, batch_no_input, " +
                        "production_date, expiry_date, qty, unit_price, amount, remark, " +
                        "line_status, error_msg, task_no, posted, post_no, " +
                        "generated_ledger_ids, generated_bin_stock_ids, created_at " +
                        "FROM inv_stock_init" + where +
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
        support.assertEditable(InitConst.PARAM_STOCK_POSTED, MODULE_LABEL);
        String mode = normalizeMode(req.get("initMode"));
        ParsedLine line = parse(req, mode, null, null);
        if (line.error != null) throw new IllegalArgumentException(line.error);
        assertNoMix(line, mode, existingModeMap(null));
        insertLine(line, mode, "MANUAL", null, InitConst.LINE_VALID, null);
        support.opLog().log(MODULE, OperationAction.CREATE, "",
                "新增库存期初行：" + line.goodsName + " / " + line.warehouseName + " x" + line.qty);
    }

    public void updateLine(Map<String, Object> req) {
        support.assertEditable(InitConst.PARAM_STOCK_POSTED, MODULE_LABEL);
        String lineId = TmsUtil.str(req.get("lineId"));
        if (lineId.isEmpty()) throw new IllegalArgumentException("缺少行 ID");
        Map<String, Object> old = getLine(lineId);
        if (old == null) throw new IllegalArgumentException("期初行不存在或已删除");
        if ("Y".equals(TmsUtil.str(old.get("posted")))) {
            throw new IllegalArgumentException("该行已建账，不能修改");
        }
        String mode = normalizeMode(req.get("initMode"));
        ParsedLine line = parse(req, mode, null, null);
        if (line.error != null) throw new IllegalArgumentException(line.error);
        assertNoMix(line, mode, existingModeMap(lineId));
        jdbc.update("UPDATE inv_stock_init SET init_mode=?, goods_code=?, goods_name=?, warehouse_code=?, " +
                        "warehouse_name=?, bin_code=?, container_code=?, batch_no=?, batch_no_input=?, " +
                        "production_date=?, expiry_date=?, qty=?, unit_price=?, amount=?, remark=?, " +
                        "line_status='VALID', error_msg=NULL WHERE line_id=?",
                mode, line.goodsCode, line.goodsName, line.warehouseCode, line.warehouseName,
                nullIfEmpty(line.binCode), nullIfEmpty(line.containerCode),
                nullIfEmpty(line.batchNo), nullIfEmpty(line.batchNoInput),
                line.productionDate == null ? null : Date.valueOf(line.productionDate),
                line.expiryDate == null ? null : Date.valueOf(line.expiryDate),
                line.qty, line.unitPrice, line.amount, nullIfEmpty(line.remark), lineId);
        support.opLog().log(MODULE, OperationAction.UPDATE, "",
                "修改库存期初行：" + line.goodsName + " / " + line.warehouseName + " x" + line.qty);
    }

    public void deleteLine(String lineId) {
        support.assertEditable(InitConst.PARAM_STOCK_POSTED, MODULE_LABEL);
        Map<String, Object> old = getLine(lineId);
        if (old == null) return;
        if ("Y".equals(TmsUtil.str(old.get("posted")))) {
            throw new IllegalArgumentException("该行已建账，不能删除");
        }
        jdbc.update("DELETE FROM inv_stock_init WHERE line_id=?", lineId);
        support.opLog().log(MODULE, OperationAction.DELETE, "",
                "删除库存期初行：" + TmsUtil.str(old.get("goodsName")) + " x" + old.get("qty"));
    }

    public int clear(String batchNo) {
        support.assertEditable(InitConst.PARAM_STOCK_POSTED, MODULE_LABEL);
        String sql = "DELETE FROM inv_stock_init WHERE posted='N'";
        List<Object> args = new ArrayList<>();
        if (batchNo != null && !batchNo.isEmpty()) {
            sql += " AND import_batch_no=?";
            args.add(batchNo);
        }
        int n = jdbc.update(sql, args.toArray());
        support.opLog().log(MODULE, OperationAction.DELETE, "",
                "清空库存期初暂存行 " + n + " 行" + (batchNo == null || batchNo.isEmpty() ? "" : "（批次 " + batchNo + "）"));
        return n;
    }

    // ==================== 导入 ====================

    public Map<String, Object> importRows(String modeRaw, List<Map<String, Object>> rows, String fileName) {
        support.assertEditable(InitConst.PARAM_STOCK_POSTED, MODULE_LABEL);
        String mode = normalizeMode(modeRaw);
        String batchNo = support.nextBatchNo(InitConst.MODE_BIN.equals(mode) ? "SK" : "SC");
        String taskNo = support.nextTaskNo();
        int success = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        Map<String, String> fileModeByKey = new HashMap<>();
        Set<String> fileDuplicateKeys = new HashSet<>();
        Map<String, String> dbModeByKey = existingModeMap(null);

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2;
            Map<String, Object> r = rows.get(i);
            ParsedLine line = parse(r, mode, fileModeByKey, fileDuplicateKeys);
            if (line.error == null) {
                String mixError = checkMix(line, mode, dbModeByKey);
                if (mixError != null) line.error = mixError;
            }
            if (line.error == null) {
                insertLine(line, mode, batchNo, rowNo, InitConst.LINE_VALID, taskNo);
                dbModeByKey.put(line.mixKey(), mode);
                fileModeByKey.putIfAbsent(line.mixKey(), mode);
                success++;
            } else {
                insertLine(line, mode, batchNo, rowNo, InitConst.LINE_ERROR, taskNo);
                failures.add(support.failure(rowNo, line.error));
            }
        }
        String[][] fields = InitConst.MODE_BIN.equals(mode) ? FIELDS_BIN : FIELDS_BATCH;
        String recordedTaskNo = support.recordImportTask(taskNo,
                InitConst.TASK_MODULE_STOCK,
                InitConst.MODE_BIN.equals(mode) ? "库存期初导入（按库位）" : "库存期初导入（按批次）",
                fileName, success, failures, fields, rows);
        support.opLog().log(MODULE, OperationAction.IMPORT, recordedTaskNo,
                "库存期初导入（" + (InitConst.MODE_BIN.equals(mode) ? "按库位" : "按批次")
                        + "，文件 " + fileName + "）：成功 " + success + " 行，失败 " + failures.size() + " 行");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", success);
        result.put("failed", failures.size());
        result.put("failures", failures);
        result.put("taskNo", recordedTaskNo);
        result.put("batchNo", batchNo);
        result.put("mode", mode);
        result.put("message", "导入完成：成功 " + success + " 条，失败 " + failures.size()
                + " 条，失败行已标记为错误数据，可在页面修正或删除；失败明细也可在【导入列表】下载");
        return result;
    }

    // ==================== 过账 ====================

    @Transactional
    public Map<String, Object> post() {
        support.assertEditable(InitConst.PARAM_STOCK_POSTED, MODULE_LABEL);
        Integer errorCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inv_stock_init WHERE posted='N' AND line_status='ERROR'", Integer.class);
        if (errorCount != null && errorCount > 0) {
            throw new IllegalArgumentException("存在 " + errorCount + " 行错误数据，请修正或删除后再建账");
        }
        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, init_mode, goods_code, goods_name, warehouse_code, warehouse_name, " +
                        "bin_code, container_code, batch_no, production_date, expiry_date, " +
                        "qty, unit_price, amount " +
                        "FROM inv_stock_init WHERE posted='N' AND line_status='VALID' " +
                        "ORDER BY goods_code, warehouse_name, COALESCE(batch_no,''), bin_code, line_id");
        if (lines.isEmpty()) throw new IllegalArgumentException("没有可建账的有效库存期初行，请先导入或手工新增");

        // 1) 余额守卫：任一(商品+仓)已有数量余额整批拒绝；零数量残留行清掉，保证真正干净的建账起点
        Map<String, GwAgg> gwMap = new LinkedHashMap<>();
        for (Map<String, Object> line : lines) {
            String gwKey = TmsUtil.str(line.get("goodsCode")) + "|" + TmsUtil.str(line.get("warehouseName"));
            GwAgg agg = gwMap.computeIfAbsent(gwKey, k -> {
                GwAgg a = new GwAgg();
                a.goodsCode = TmsUtil.str(line.get("goodsCode"));
                a.goodsName = TmsUtil.str(line.get("goodsName"));
                a.warehouseName = TmsUtil.str(line.get("warehouseName"));
                return a;
            });
            BigDecimal qty = TmsUtil.toBd(line.get("qty"));
            BigDecimal price = TmsUtil.toBd(line.get("unitPrice"));
            agg.totalQty = agg.totalQty.add(qty);
            agg.totalCost = agg.totalCost.add(qty.multiply(price));
        }
        for (GwAgg agg : gwMap.values()) {
            List<Map<String, Object>> balRows = TmsUtil.queryCamel(jdbc,
                    "SELECT COALESCE(SUM(COALESCE(physical_qty,0)),0) physical, " +
                            "COALESCE(SUM(COALESCE(available_qty,0)),0) available " +
                            "FROM inv_stock_balance WHERE goods_code=? AND warehouse=?",
                    agg.goodsCode, agg.warehouseName);
            BigDecimal physical = TmsUtil.toBd(balRows.get(0).get("physical"));
            if (physical.signum() > 0) {
                throw new IllegalArgumentException("商品 " + agg.goodsCode + "（" + agg.goodsName + "）在仓库「"
                        + agg.warehouseName + "」已有库存数量 " + physical.stripTrailingZeros().toPlainString()
                        + "，必须先清零才能导入期初");
            }
            // 零数量残留：删余额行 + 零批次行，让期初单价真正建立初始成本
            jdbc.update("DELETE FROM inv_stock_balance WHERE goods_code=? AND warehouse=? " +
                    "AND COALESCE(physical_qty,0)=0", agg.goodsCode, agg.warehouseName);
            jdbc.update("DELETE FROM inv_batch_stock WHERE goods_code=? AND warehouse=? " +
                    "AND COALESCE(qty,0)=0", agg.goodsCode, agg.warehouseName);
            agg.avgPrice = agg.totalQty.signum() == 0 ? BigDecimal.ZERO
                    : agg.totalCost.divide(agg.totalQty, 6, RoundingMode.HALF_UP);
        }

        // 2) 按(商品+仓+批次)聚合；BIN 行再按库位键聚合
        String postNo = support.nextPostNo();
        Map<String, BatchAgg> batchMap = new LinkedHashMap<>();
        for (Map<String, Object> line : lines) {
            String gwKey = TmsUtil.str(line.get("goodsCode")) + "|" + TmsUtil.str(line.get("warehouseName"));
            GwAgg gw = gwMap.get(gwKey);
            String batchNorm = TmsUtil.str(line.get("batchNo"));
            String bKey = gwKey + "||" + batchNorm;
            BatchAgg ba = batchMap.computeIfAbsent(bKey, k -> {
                BatchAgg a = new BatchAgg();
                a.goodsCode = gw.goodsCode;
                a.goodsName = gw.goodsName;
                a.warehouseName = gw.warehouseName;
                a.batchNo = batchNorm.isEmpty() ? null : batchNorm;
                a.avgPrice = gw.avgPrice;
                return a;
            });
            BigDecimal qty = TmsUtil.toBd(line.get("qty"));
            ba.qty = ba.qty.add(qty);
            ba.cost = ba.cost.add(qty.multiply(TmsUtil.toBd(line.get("unitPrice"))));
            LocalDate prod = TmsUtil.toLocalDate(line.get("productionDate"));
            LocalDate exp = TmsUtil.toLocalDate(line.get("expiryDate"));
            if (ba.productionDate == null) ba.productionDate = prod;
            if (ba.expiryDate == null) ba.expiryDate = exp;
            ba.lineIds.add(TmsUtil.str(line.get("lineId")));

            if (InitConst.MODE_BIN.equals(TmsUtil.str(line.get("initMode")))) {
                String binKey = bKey + "||" + TmsUtil.str(line.get("binCode")) + "||"
                        + TmsUtil.str(line.get("containerCode"));
                BinAgg bin = ba.binMap.computeIfAbsent(binKey, k -> {
                    BinAgg b = new BinAgg();
                    b.binCode = TmsUtil.str(line.get("binCode"));
                    b.containerCode = TmsUtil.str(line.get("containerCode"));
                    b.productionDate = prod;
                    b.expiryDate = exp;
                    return b;
                });
                bin.qty = bin.qty.add(qty);
                bin.lineIds.add(TmsUtil.str(line.get("lineId")));
            }
        }

        // 3) 逐批次写财务三账 + 库位位置账
        int seq = 0;
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (BatchAgg ba : batchMap.values()) {
            seq++;
            String sourceBill = InitConst.PREFIX_STOCK_LEDGER + "-" + postNo + "-" + seq;
            inventoryCost.inboundAtCurrentCost(ba.goodsCode, ba.goodsName, ba.warehouseName,
                    ba.batchNo, ba.qty, ba.avgPrice, sourceBill, ba.productionDate);
            // 金额校准：inboundAtCurrentCost 按余额行 2 位小价成本计价，均价被列截断后
            // 批次越多金额尾差越大（46×10.48=482.08 ≠ 实际 482.00）。与采购引擎口径一致——
            // 台账金额记本批实际成本，批次账记批次自身单价，余额金额在循环外按实际总额校准。
            BigDecimal batchAmount = ba.cost.setScale(2, RoundingMode.HALF_UP);
            BigDecimal batchPrice = ba.qty.signum() == 0 ? BigDecimal.ZERO
                    : ba.cost.divide(ba.qty, 4, RoundingMode.HALF_UP);
            jdbc.update("UPDATE inv_batch_stock SET cost_price=?, stock_amount=?, expiry_date=? " +
                            "WHERE goods_code=? AND warehouse=? AND COALESCE(batch_no,'')=?",
                    batchPrice, batchAmount, ba.expiryDate == null ? null : Date.valueOf(ba.expiryDate),
                    ba.goodsCode, ba.warehouseName, ba.batchNo == null ? "" : ba.batchNo);
            jdbc.update("UPDATE inv_stock_ledger SET amount=? WHERE source_bill=? " +
                            "AND goods_code=? AND warehouse=?",
                    batchAmount, sourceBill, ba.goodsCode, ba.warehouseName);

            Set<String> binIds = new LinkedHashSet<>();
            for (BinAgg bin : ba.binMap.values()) {
                String binStockId = binStockService.addInboundBinStock(ba.goodsCode, ba.goodsName,
                        ba.warehouseName, ba.batchNo, bin.binCode, bin.qty,
                        bin.containerCode.isEmpty() ? null : bin.containerCode,
                        bin.productionDate != null ? bin.productionDate : ba.productionDate,
                        bin.expiryDate != null ? bin.expiryDate : ba.expiryDate,
                        sourceBill);
                binIds.add(binStockId);
                jdbc.update("UPDATE inv_stock_init SET generated_bin_stock_ids=? WHERE line_id IN ("
                        + placeholders(bin.lineIds.size()) + ")",
                        joinParams(binStockId, bin.lineIds));
            }
            jdbc.update("UPDATE inv_stock_init SET posted='Y', post_no=?, generated_ledger_ids=? " +
                            "WHERE line_id IN (" + placeholders(ba.lineIds.size()) + ")",
                    joinParams(postNo, sourceBill, ba.lineIds));

            totalQty = totalQty.add(ba.qty);
            totalAmount = totalAmount.add(ba.cost);
        }
        totalAmount = totalAmount.setScale(2, RoundingMode.HALF_UP);
        // 余额层金额校准：cost_price 列精度 2 位（均价显示 10.48），但金额必须等于实际
        // 建账金额（482.00），不能用截断后的单价反乘数量，否则总账引数与台账/批次账轧不平
        for (GwAgg gw : gwMap.values()) {
            jdbc.update("UPDATE inv_stock_balance SET cost_price=?, stock_amount=? " +
                            "WHERE goods_code=? AND warehouse=?",
                    gw.avgPrice.setScale(2, RoundingMode.HALF_UP),
                    gw.totalCost.setScale(2, RoundingMode.HALF_UP),
                    gw.goodsCode, gw.warehouseName);
        }

        support.insertPost(postNo, InitConst.TYPE_STOCK, lines.size(), totalQty, totalAmount, "库存期初建账");
        support.setPostedFlag(InitConst.PARAM_STOCK_POSTED, InitConst.PARAM_STOCK_POST_NO, postNo, MODULE_LABEL);
        support.opLog().log(MODULE, OperationAction.CREATE, postNo,
                "库存期初建账：批号 " + postNo + "，" + lines.size() + " 行，数量 " + totalQty
                        + "，成本金额 " + totalAmount + " 元");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("postNo", postNo);
        result.put("lineCount", lines.size());
        result.put("totalQty", totalQty);
        result.put("totalAmount", totalAmount);
        return result;
    }

    // ==================== 反建账 ====================

    @Transactional
    public void reverse(String reason) {
        support.assertReversable(InitConst.PARAM_STOCK_POSTED, MODULE_LABEL);
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("反建账必须填写原因");
        }
        Map<String, Object> post = support.activePost(InitConst.TYPE_STOCK);
        if (post == null) throw new IllegalArgumentException("未找到已生效的库存期初建账记录");
        String postNo = TmsUtil.str(post.get("postNo"));
        String sourcePrefix = InitConst.PREFIX_STOCK_LEDGER + "-" + postNo + "-%";

        List<Map<String, Object>> lines = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, init_mode, goods_code, warehouse_name, batch_no, bin_code, " +
                        "qty, generated_ledger_ids, generated_bin_stock_ids " +
                        "FROM inv_stock_init WHERE posted='Y' AND post_no=?", postNo);
        if (lines.isEmpty()) throw new IllegalArgumentException("批号 " + postNo + " 下没有期初行");

        // 1) 守卫：过账后无后续出入库流水；批次/余额/库位余量必须等于过账量（无任何后续作业）
        // 用 List 作复合键，避免仓库名本身含分隔符时拼串拆错
        Map<List<String>, BigDecimal> gwPosted = new LinkedHashMap<>();
        Map<List<String>, BigDecimal> batchPosted = new LinkedHashMap<>();
        Map<String, BigDecimal> binPosted = new LinkedHashMap<>();
        for (Map<String, Object> line : lines) {
            String goodsCode = TmsUtil.str(line.get("goodsCode"));
            String warehouseName = TmsUtil.str(line.get("warehouseName"));
            String batchNo = TmsUtil.str(line.get("batchNo"));
            BigDecimal qty = TmsUtil.toBd(line.get("qty"));
            gwPosted.merge(List.of(goodsCode, warehouseName), qty, BigDecimal::add);
            batchPosted.merge(List.of(goodsCode, warehouseName, batchNo), qty, BigDecimal::add);
            String binId = TmsUtil.str(line.get("generatedBinStockIds"));
            if (!binId.isEmpty()) binPosted.merge(binId, qty, BigDecimal::add);
        }
        for (Map.Entry<List<String>, BigDecimal> e : gwPosted.entrySet()) {
            String goodsCode = e.getKey().get(0);
            String warehouseName = e.getKey().get(1);
            Integer otherLedgers = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inv_stock_ledger WHERE goods_code=? AND warehouse=? " +
                            "AND COALESCE(source_bill,'') NOT LIKE ?",
                    Integer.class, goodsCode, warehouseName, sourcePrefix);
            if (otherLedgers != null && otherLedgers > 0) {
                throw new IllegalArgumentException("商品 " + goodsCode + " 在仓库「" + warehouseName
                        + "」期初建账后已发生出入库流水，不能反建账");
            }
            List<Map<String, Object>> bal = TmsUtil.queryCamel(jdbc,
                    "SELECT COALESCE(SUM(COALESCE(physical_qty,0)),0) physical, " +
                            "COALESCE(SUM(COALESCE(available_qty,0)),0) available " +
                            "FROM inv_stock_balance WHERE goods_code=? AND warehouse=?",
                    goodsCode, warehouseName);
            BigDecimal physical = TmsUtil.toBd(bal.get(0).get("physical"));
            BigDecimal available = TmsUtil.toBd(bal.get(0).get("available"));
            if (physical.compareTo(e.getValue()) != 0 || available.compareTo(e.getValue()) != 0) {
                throw new IllegalArgumentException("商品 " + goodsCode + " 在仓库「" + warehouseName
                        + "」当前库存余量与期初过账量不一致，不能反建账（当前实物 " + physical + "，应为期初 " + e.getValue() + "）");
            }
        }
        for (Map.Entry<List<String>, BigDecimal> e : batchPosted.entrySet()) {
            String goodsCode = e.getKey().get(0);
            String warehouseName = e.getKey().get(1);
            String batchNo = e.getKey().get(2);
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                    "SELECT COALESCE(SUM(COALESCE(qty,0)),0) qty FROM inv_batch_stock " +
                            "WHERE goods_code=? AND warehouse=? AND COALESCE(batch_no,'')=?",
                    goodsCode, warehouseName, batchNo);
            BigDecimal qty = TmsUtil.toBd(rows.get(0).get("qty"));
            if (qty.compareTo(e.getValue()) != 0) {
                throw new IllegalArgumentException("商品 " + goodsCode + " 批次「" + (batchNo.isEmpty() ? "(无批次)" : batchNo)
                        + "」当前余量 " + qty + " 与期初过账量 " + e.getValue() + " 不一致，不能反建账");
            }
        }
        for (Map.Entry<String, BigDecimal> e : binPosted.entrySet()) {
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                    "SELECT COALESCE(qty,0) qty FROM wms_bin_stock WHERE bin_stock_id=?", e.getKey());
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("库位库存行 " + e.getKey() + " 已不存在，不能反建账");
            }
            BigDecimal qty = TmsUtil.toBd(rows.get(0).get("qty"));
            if (qty.compareTo(e.getValue()) != 0) {
                throw new IllegalArgumentException("库位库存行 " + e.getKey() + " 当前余量 " + qty
                        + " 与期初过账量 " + e.getValue() + " 不一致（库位内可能已有后续作业），不能反建账");
            }
        }

        // 2) 回退：台账 → 批次 → 余额 → 位置账
        jdbc.update("DELETE FROM inv_stock_ledger WHERE source_bill LIKE ?", sourcePrefix);
        for (Map.Entry<List<String>, BigDecimal> e : batchPosted.entrySet()) {
            jdbc.update("DELETE FROM inv_batch_stock WHERE goods_code=? AND warehouse=? " +
                            "AND COALESCE(batch_no,'')=?",
                    e.getKey().get(0), e.getKey().get(1), e.getKey().get(2));
        }
        for (List<String> gwKey : gwPosted.keySet()) {
            jdbc.update("DELETE FROM inv_stock_balance WHERE goods_code=? AND warehouse=?",
                    gwKey.get(0), gwKey.get(1));
        }
        for (Map.Entry<String, BigDecimal> e : binPosted.entrySet()) {
            // 余量守卫已保证等于过账量：直接删行、回减库位占用
            List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                    "SELECT warehouse, bin_code FROM wms_bin_stock WHERE bin_stock_id=?", e.getKey());
            if (!rows.isEmpty()) {
                String wh = TmsUtil.str(rows.get(0).get("warehouse"));
                String bin = TmsUtil.str(rows.get(0).get("binCode"));
                jdbc.update("DELETE FROM wms_bin_stock WHERE bin_stock_id=?", e.getKey());
                jdbc.update("UPDATE wms_bin SET used_qty = GREATEST(COALESCE(used_qty,0) - ?, 0) " +
                        "WHERE warehouse=? AND bin_code=?", e.getValue(), wh, bin);
            }
        }
        jdbc.update("DELETE FROM wms_bin_stock_log WHERE source_bill LIKE ?", sourcePrefix);

        jdbc.update("UPDATE inv_stock_init SET posted='N', post_no=NULL, " +
                "generated_ledger_ids=NULL, generated_bin_stock_ids=NULL WHERE post_no=?", postNo);
        support.markPostReversed(postNo, reason);
        support.clearPostedFlag(InitConst.PARAM_STOCK_POSTED, InitConst.PARAM_STOCK_POST_NO);
        support.opLog().log(MODULE, OperationAction.REVERSE, postNo,
                "库存期初反建账：批号 " + postNo + "，回退 " + lines.size() + " 行。原因：" + reason);
    }

    // ==================== 行解析 ====================

    private Map<String, Object> getLine(String lineId) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT line_id, goods_name, qty, posted, init_mode FROM inv_stock_init WHERE line_id=?", lineId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 解析校验一行。
     *
     * @param fileModeByKey      本文件内 商品+仓+批次 → 模式（混用校验），手工行传 null
     * @param fileDuplicateKeys  本文件内行去重集合，手工行传 null
     */
    private ParsedLine parse(Map<String, Object> r, String mode,
                             Map<String, String> fileModeByKey, Set<String> fileDuplicateKeys) {
        ParsedLine p = new ParsedLine();
        p.goodsCode = TmsUtil.str(r.get("goodsCode"));
        p.goodsName = TmsUtil.str(r.get("goodsName"));
        p.rawGoodsName = p.goodsName;
        p.warehouseCode = TmsUtil.str(r.get("warehouseCode"));
        p.warehouseName = TmsUtil.str(r.get("warehouseName"));
        p.rawWarehouseName = p.warehouseName;
        p.binCode = TmsUtil.str(r.get("binCode"));
        p.containerCode = TmsUtil.str(r.get("containerCode"));
        p.batchNoInput = TmsUtil.str(r.get("batchNoInput"));
        p.remark = TmsUtil.str(r.get("remark"));

        if (p.goodsCode.isEmpty()) {
            p.error = "商品编码必填";
            return p;
        }
        Map<String, Object> goods = lookupGoods(p.goodsCode);
        if (goods == null) {
            p.error = "商品编码「" + p.goodsCode + "」不存在，须先在商品档案中维护";
            return p;
        }
        if ("DELETED".equals(TmsUtil.str(goods.get("status")))) {
            p.error = "商品「" + p.goodsCode + "」已删除，不能导入期初";
            return p;
        }
        p.goodsName = TmsUtil.str(goods.get("goodsName"));
        int shelfLife = TmsUtil.toInt(goods.get("shelfLifeDays"));

        if (p.warehouseCode.isEmpty()) {
            p.error = "仓库编码必填";
            return p;
        }
        Map<String, Object> warehouse = lookupWarehouse(p.warehouseCode);
        if (warehouse == null) {
            p.error = "仓库编码「" + p.warehouseCode + "」不存在，须先在仓库档案中维护";
            return p;
        }
        p.warehouseName = TmsUtil.str(warehouse.get("warehouseName"));

        String prodRaw = TmsUtil.str(r.get("productionDate"));
        if (!prodRaw.isEmpty()) {
            p.productionDate = TmsUtil.toLocalDate(prodRaw);
            if (p.productionDate == null) {
                p.error = "生产日期格式不正确：" + prodRaw + "（应为 yyyy-MM-dd）";
                return p;
            }
        }
        String expRaw = TmsUtil.str(r.get("expiryDate"));
        if (!expRaw.isEmpty()) {
            p.expiryDate = TmsUtil.toLocalDate(expRaw);
            if (p.expiryDate == null) {
                p.error = "效期日期格式不正确：" + expRaw + "（应为 yyyy-MM-dd）";
                return p;
            }
        }
        if (p.productionDate != null && p.expiryDate != null && p.expiryDate.isBefore(p.productionDate)) {
            p.error = "效期日期不能早于生产日期";
            return p;
        }

        // 批次推导：手填批号 > 生产日期 yyyyMMdd > 空
        if (!p.batchNoInput.isEmpty()) {
            p.batchNo = p.batchNoInput;
        } else if (p.productionDate != null) {
            p.batchNo = p.productionDate.format(BATCH_FMT);
        }
        // 效期推导：显式优先，否则生产日期+保质期
        if (p.expiryDate == null && p.productionDate != null && shelfLife > 0) {
            p.expiryDate = p.productionDate.plusDays(shelfLife);
        }

        String qtyRaw = TmsUtil.str(r.get("qty"));
        if (qtyRaw.isEmpty()) {
            p.error = "数量必填";
            return p;
        }
        try {
            p.qty = new BigDecimal(qtyRaw.replace(",", ""));
        } catch (NumberFormatException e) {
            p.error = "数量不是合法数字：" + qtyRaw;
            return p;
        }
        if (p.qty.signum() <= 0) {
            p.error = "数量必须大于 0";
            return p;
        }
        if (InitConst.MODE_BIN.equals(mode) && p.qty.stripTrailingZeros().scale() > 3) {
            p.error = "按库位导入的数量最多支持 3 位小数：" + qtyRaw;
            return p;
        }
        p.qty = p.qty.setScale(4, RoundingMode.HALF_UP);

        String priceRaw = TmsUtil.str(r.get("unitPrice"));
        if (priceRaw.isEmpty()) {
            p.error = "成本单价必填（允许填 0，0 成本将在页面警示）";
            return p;
        }
        try {
            p.unitPrice = new BigDecimal(priceRaw.replace(",", ""));
        } catch (NumberFormatException e) {
            p.error = "成本单价不是合法数字：" + priceRaw;
            return p;
        }
        if (p.unitPrice.signum() < 0) {
            p.error = "成本单价不能为负数";
            return p;
        }
        p.unitPrice = p.unitPrice.setScale(6, RoundingMode.HALF_UP);
        p.amount = p.qty.multiply(p.unitPrice).setScale(2, RoundingMode.HALF_UP);

        if (InitConst.MODE_BIN.equals(mode)) {
            if (p.binCode.isEmpty()) {
                p.error = "按库位导入时库位编码必填";
                return p;
            }
            Map<String, Object> bin = lookupBin(p.warehouseName, p.binCode);
            if (bin == null) {
                p.error = "仓库「" + p.warehouseName + "」下不存在库位「" + p.binCode + "」，须先在 WMS 库位档案维护";
                return p;
            }
            if (!"NORMAL".equals(TmsUtil.str(bin.get("status")))) {
                p.error = "库位「" + p.binCode + "」已停用，不能导入期初";
                return p;
            }
            if ("Y".equalsIgnoreCase(TmsUtil.str(bin.get("frozen")))) {
                p.error = "库位「" + p.binCode + "」已冻结，不能导入期初";
                return p;
            }
        } else if (!p.binCode.isEmpty()) {
            p.error = "按批次导入流程不允许填写库位编码；按库位请切换到「按库位」页签导入";
            return p;
        }

        if (fileDuplicateKeys != null) {
            String dupKey = InitConst.MODE_BIN.equals(mode)
                    ? p.mixKey() + "||" + p.binCode + "||" + p.containerCode
                    : p.mixKey();
            if (!fileDuplicateKeys.add(dupKey)) {
                p.error = InitConst.MODE_BIN.equals(mode)
                        ? "同一文件内商品+仓库+批次+库位+容器重复：" + p.goodsCode + " / " + p.binCode
                        : "同一文件内商品+仓库+批次重复：" + p.goodsCode
                        + (p.batchNo.isEmpty() ? "" : " / 批次 " + p.batchNo)
                        + "（同批次多行请合并数量后导入）";
                return p;
            }
        }
        if (fileModeByKey != null) {
            // 同键同模式的重复已在上面拦截；这里拦同一文件内两种流程混用
            String existedMode = fileModeByKey.putIfAbsent(p.mixKey(), mode);
            if (existedMode != null && !existedMode.equals(mode)) {
                p.error = "同一文件内商品 " + p.goodsCode + (p.batchNo.isEmpty() ? "" : " 批次「" + p.batchNo + "」")
                        + " 在仓库「" + p.warehouseName + "」同时出现按库位/按批次行，不能混用两种流程";
                return p;
            }
        }
        return p;
    }

    /** 与已落库暂存行的模式混用校验（手工行用）。 */
    private void assertNoMix(ParsedLine line, String mode, Map<String, String> dbModeByKey) {
        String err = checkMix(line, mode, dbModeByKey);
        if (err != null) throw new IllegalArgumentException(err);
    }

    private String checkMix(ParsedLine line, String mode, Map<String, String> dbModeByKey) {
        String existed = dbModeByKey.get(line.mixKey());
        if (existed != null && !existed.equals(mode)) {
            return "商品 " + line.goodsCode + (line.batchNo.isEmpty() ? "" : " 批次「" + line.batchNo + "」")
                    + " 在仓库「" + line.warehouseName + "」已按"
                    + (InitConst.MODE_BIN.equals(existed) ? "库位" : "商品+批次")
                    + "导入，同一商品+仓库+批次不能混用两种流程";
        }
        return null;
    }

    /** 已暂存（未过账）行：商品+仓+批次 → 模式。excludeLineId 用于编辑时排除自身。 */
    private Map<String, String> existingModeMap(String excludeLineId) {
        Map<String, String> map = new HashMap<>();
        String sql = "SELECT DISTINCT goods_code, warehouse_name, COALESCE(batch_no,'') bk, init_mode " +
                "FROM inv_stock_init WHERE posted='N'";
        List<Object> args = new ArrayList<>();
        if (excludeLineId != null) {
            sql += " AND line_id <> ?";
            args.add(excludeLineId);
        }
        for (Map<String, Object> row : TmsUtil.queryCamel(jdbc, sql, args.toArray())) {
            String key = TmsUtil.str(row.get("goodsCode")) + "|"
                    + TmsUtil.str(row.get("warehouseName")) + "|" + TmsUtil.str(row.get("bk"));
            map.putIfAbsent(key, TmsUtil.str(row.get("initMode")));
        }
        return map;
    }

    private Map<String, Object> lookupGoods(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT goods_code, goods_name, status, shelf_life_days FROM base_goods WHERE goods_code=? LIMIT 1",
                code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> lookupWarehouse(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT warehouse_code, warehouse_name FROM base_warehouse WHERE warehouse_code=? LIMIT 1",
                code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> lookupBin(String warehouseName, String binCode) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT bin_code, warehouse, status, frozen FROM wms_bin WHERE warehouse=? AND bin_code=? LIMIT 1",
                warehouseName, binCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertLine(ParsedLine line, String mode, String batchNo, Integer rowNo,
                            String status, String taskNo) {
        String goodsName = line.goodsName == null || line.goodsName.isEmpty() ? line.rawGoodsName : line.goodsName;
        jdbc.update("INSERT INTO inv_stock_init(line_id, import_batch_no, row_no, init_mode, " +
                        "goods_code, goods_name, warehouse_code, warehouse_name, bin_code, container_code, " +
                        "batch_no, batch_no_input, production_date, expiry_date, qty, unit_price, amount, remark, " +
                        "line_status, error_msg, task_no, posted, created_by, created_by_name) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'N',?,?)",
                TmsUtil.uuid("SI"),
                batchNo == null ? support.nextBatchNo("SC") : batchNo,
                rowNo, mode,
                line.goodsCode, goodsName, line.warehouseCode,
                line.warehouseName == null || line.warehouseName.isEmpty() ? TmsUtil.str(line.rawWarehouseName) : line.warehouseName,
                nullIfEmpty(line.binCode), nullIfEmpty(line.containerCode),
                nullIfEmpty(line.batchNo), nullIfEmpty(line.batchNoInput),
                line.productionDate == null ? null : Date.valueOf(line.productionDate),
                line.expiryDate == null ? null : Date.valueOf(line.expiryDate),
                line.qty == null ? BigDecimal.ZERO : line.qty,
                line.unitPrice == null ? BigDecimal.ZERO : line.unitPrice,
                line.amount == null ? BigDecimal.ZERO : line.amount,
                nullIfEmpty(line.remark),
                status, line.error, taskNo,
                support.currentUserId(), support.currentUserName());
    }

    // ==================== 小工具 ====================

    private String normalizeMode(Object raw) {
        String m = TmsUtil.str(raw).toUpperCase(Locale.ROOT);
        return InitConst.MODE_BIN.equals(m) ? InitConst.MODE_BIN : InitConst.MODE_BATCH;
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static Object[] joinParams(Object first, List<String> ids) {
        Object[] out = new Object[ids.size() + 1];
        out[0] = first;
        for (int i = 0; i < ids.size(); i++) out[i + 1] = ids.get(i);
        return out;
    }

    private static Object[] joinParams(Object first, Object second, List<String> ids) {
        Object[] out = new Object[ids.size() + 2];
        out[0] = first;
        out[1] = second;
        for (int i = 0; i < ids.size(); i++) out[i + 2] = ids.get(i);
        return out;
    }

    // ==================== 聚合承载 ====================

    private static class GwAgg {
        String goodsCode;
        String goodsName;
        String warehouseName;
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal avgPrice = BigDecimal.ZERO;
    }

    private static class BatchAgg {
        String goodsCode;
        String goodsName;
        String warehouseName;
        String batchNo;
        BigDecimal qty = BigDecimal.ZERO;
        /** 该（商品+仓+批次）自身的实际成本额 Σ(qty×单价)，用于台账/批次账记实际金额 */
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal avgPrice;
        LocalDate productionDate;
        LocalDate expiryDate;
        List<String> lineIds = new ArrayList<>();
        Map<String, BinAgg> binMap = new LinkedHashMap<>();
    }

    private static class BinAgg {
        String binCode;
        String containerCode;
        BigDecimal qty = BigDecimal.ZERO;
        LocalDate productionDate;
        LocalDate expiryDate;
        List<String> lineIds = new ArrayList<>();
    }

    private static class ParsedLine {
        String goodsCode;
        String goodsName;
        String rawGoodsName;
        String warehouseCode;
        String warehouseName;
        String rawWarehouseName;
        String binCode;
        String containerCode;
        String batchNoInput;
        String batchNo;
        LocalDate productionDate;
        LocalDate expiryDate;
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal unitPrice = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        String remark;
        String error;

        String mixKey() {
            return goodsCode + "|" + (warehouseName == null ? "" : warehouseName) + "|" + (batchNo == null ? "" : batchNo);
        }
    }
}
