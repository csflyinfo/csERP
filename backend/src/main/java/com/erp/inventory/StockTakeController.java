package com.erp.inventory;

import com.alibaba.excel.EasyExcel;
import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.util.BillNoGenerator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * 盘点单 Controller —— 库存盘点全流程。
 *
 * <p>流程：创建盘点单（全盘/抽盘）→ 填写实盘数量 → 审核（重取成本单价、更新库存、写流水）→ 可反审核回滚。
 * <p>路径前缀：{@code /inventory/stock-take}
 */
@RestController
@RequestMapping("/inventory/stock-take")
public class StockTakeController {

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGenerator;

    public StockTakeController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGenerator = billNoGenerator;
    }

    // ==================== 列表 ====================

    @PostMapping("/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT s.count_sheet_id, s.sheet_no, s.warehouse, s.count_date, s.count_type,
                       s.status, s.remark, s.create_by, s.create_time, s.audit_by, s.audit_time,
                       (SELECT COUNT(*) FROM inv_count_detail d WHERE d.sheet_no = s.sheet_no) AS detail_count,
                       (SELECT COALESCE(SUM(d.book_amount), 0) FROM inv_count_detail d WHERE d.sheet_no = s.sheet_no) AS book_amount,
                       (SELECT COALESCE(SUM(d.real_amount), 0) FROM inv_count_detail d WHERE d.sheet_no = s.sheet_no) AS real_amount,
                       (SELECT COALESCE(SUM(d.diff_amount), 0) FROM inv_count_detail d WHERE d.sheet_no = s.sheet_no) AS diff_amount,
                       (SELECT COALESCE(SUM(d.diff_qty), 0) FROM inv_count_detail d WHERE d.sheet_no = s.sheet_no AND d.diff_qty > 0) AS surplus_qty,
                       (SELECT COALESCE(SUM(ABS(d.diff_qty)), 0) FROM inv_count_detail d WHERE d.sheet_no = s.sheet_no AND d.diff_qty < 0) AS shortage_qty
                FROM inv_count_sheet s
                ORDER BY s.create_time DESC, s.sheet_no DESC
                """);

        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> r = camelize(row);
            String ct = String.valueOf(r.getOrDefault("countType", "1"));
            r.put("countTypeText", "2".equals(ct) ? "抽盘" : "全盘");
            String st = String.valueOf(r.getOrDefault("status", "PENDING"));
            r.put("statusText", "APPROVED".equals(st) ? "已审核" : "待审核");
            // 时间格式化
            Object ctObj = r.get("createTime");
            if (ctObj != null) r.put("createdAt", String.valueOf(ctObj).replace("T", " ").substring(0, Math.min(19, String.valueOf(ctObj).length())));
            Object atObj = r.get("auditTime");
            if (atObj != null) r.put("auditTimeFormatted", String.valueOf(atObj).replace("T", " ").substring(0, Math.min(19, String.valueOf(atObj).length())));
            // 制单人/审核人直接取 create_by / audit_by
            r.put("creatorName", r.get("createBy"));
            r.put("auditorName", r.get("auditBy"));
            mapped.add(r);
        }

        // filters
        Map<String, Object> filters = request.filters();
        if (filters != null && !filters.isEmpty()) {
            String keyword = strLower(filters.get("keyword"));
            String warehouse = strLower(filters.get("warehouse"));
            String status = strLower(filters.get("status"));
            mapped = mapped.stream().filter(r -> {
                if (!keyword.isBlank()) {
                    String hay = strLower(r.get("sheetNo")) + " " + strLower(r.get("warehouse")) + " " + strLower(r.get("remark"));
                    if (!hay.contains(keyword)) return false;
                }
                if (!warehouse.isBlank() && !strLower(r.get("warehouse")).contains(warehouse)) return false;
                if (!status.isBlank()) {
                    String st = String.valueOf(r.getOrDefault("status", ""));
                    if ("待审核".equals(status) && !"PENDING".equals(st)) return false;
                    if ("已审核".equals(status) && !"APPROVED".equals(st)) return false;
                }
                return true;
            }).toList();
        }

        return ApiResponse.ok(PageResult.of(mapped, request));
    }

    // ==================== 详情 ====================

    @PostMapping("/detail")
    public ApiResponse<Map<String, Object>> detail(@RequestBody Map<String, Object> req) {
        Object id = req.get("countSheetId");
        Object no = req.get("sheetNo");
        List<Map<String, Object>> sheets;
        if (no != null && !String.valueOf(no).isBlank()) {
            sheets = jdbcTemplate.queryForList("SELECT * FROM inv_count_sheet WHERE sheet_no = ?", String.valueOf(no));
        } else {
            sheets = jdbcTemplate.queryForList("SELECT * FROM inv_count_sheet WHERE count_sheet_id = ?",
                    id instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(id)));
        }
        if (sheets.isEmpty()) throw new IllegalArgumentException("盘点单不存在");

        Map<String, Object> master = camelize(sheets.get(0));
        String ct = String.valueOf(master.getOrDefault("countType", "1"));
        master.put("countTypeText", "2".equals(ct) ? "抽盘" : "全盘");

        String sheetNo = String.valueOf(master.get("sheetNo"));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_count_detail WHERE sheet_no = ? ORDER BY line_no, detail_id", sheetNo);
        List<Map<String, Object>> camelDetails = new ArrayList<>();
        for (Map<String, Object> d : details) camelDetails.add(camelize(d));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("master", master);
        result.put("details", camelDetails);
        return ApiResponse.ok(result);
    }

    // ==================== 导入商品 ====================

    @PostMapping("/parse-items")
    public ApiResponse<Map<String, Object>> parseItems(@RequestBody Map<String, Object> req) {
        String warehouse = String.valueOf(req.getOrDefault("warehouse", ""));
        @SuppressWarnings("unchecked")
        List<String> itemCodes = (List<String>) req.get("itemCodes");
        if (itemCodes == null || itemCodes.isEmpty()) {
            // 尝试逗号分隔的字符串
            String codesStr = String.valueOf(req.getOrDefault("itemCodes", ""));
            itemCodes = Arrays.stream(codesStr.split("[,，\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        if (itemCodes.isEmpty()) throw new IllegalArgumentException("请提供商品编号");

        List<Map<String, Object>> items = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        for (String code : itemCodes) {
            List<Map<String, Object>> goods = jdbcTemplate.queryForList(
                    "SELECT goods_code, goods_name, spec, base_unit FROM base_goods WHERE goods_code = ? AND status != 'STOPPED'", code);
            if (goods.isEmpty()) {
                notFound.add(code);
                continue;
            }
            Map<String, Object> g = goods.get(0);
            // 从 inv_batch_stock 取批次和成本价
            List<Map<String, Object>> batches = jdbcTemplate.queryForList(
                    "SELECT batch_no, production_date, qty, cost_price FROM inv_batch_stock WHERE goods_code = ? AND warehouse = ? AND qty > 0 ORDER BY batch_no",
                    code, warehouse);
            if (batches.isEmpty()) {
                // 无现有批次，返回空批次（新商品）
                BigDecimal costPrice = BigDecimal.ZERO;
                List<Map<String, Object>> bal = jdbcTemplate.queryForList(
                        "SELECT cost_price FROM inv_stock_balance WHERE goods_code = ? AND warehouse = ?", code, warehouse);
                if (!bal.isEmpty()) costPrice = toBigDecimal(bal.get(0).get("COST_PRICE"));

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("goodsCode", code);
                item.put("goodsName", String.valueOf(g.getOrDefault("GOODS_NAME", "")));
                item.put("spec", String.valueOf(g.getOrDefault("SPEC", "")));
                item.put("unitName", String.valueOf(g.getOrDefault("BASE_UNIT", "")));
                item.put("batchNo", "");
                item.put("productionDate", null);
                item.put("bookQty", 0);
                item.put("costPrice", costPrice);
                items.add(item);
            } else {
                for (Map<String, Object> b : batches) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("goodsCode", code);
                    item.put("goodsName", String.valueOf(g.getOrDefault("GOODS_NAME", "")));
                    item.put("spec", String.valueOf(g.getOrDefault("SPEC", "")));
                    item.put("unitName", String.valueOf(g.getOrDefault("BASE_UNIT", "")));
                    item.put("batchNo", String.valueOf(b.getOrDefault("BATCH_NO", "")));
                    item.put("productionDate", b.get("PRODUCTION_DATE"));
                    item.put("bookQty", toBigDecimal(b.get("QTY")));
                    item.put("costPrice", toBigDecimal(b.getOrDefault("COST_PRICE", 0)));
                    items.add(item);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("notFound", notFound);
        result.put("total", items.size());
        return ApiResponse.ok(result);
    }

    // ==================== Excel 导入商品（抽盘创建阶段） ====================

    @PostMapping("/parse-excel")
    public ApiResponse<Map<String, Object>> parseExcel(@RequestParam("file") MultipartFile file,
                                                       @RequestParam("warehouse") String warehouse) {
        if (file.isEmpty()) throw new IllegalArgumentException("文件为空");
        List<String> itemCodes = new ArrayList<>();
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        try {
            if (filename.endsWith(".csv")) {
                // CSV：按行解析，跳过空行和"商品编号"表头行
                String content = new String(file.getBytes(), StandardCharsets.UTF_8);
                // 去掉 BOM
                if (content.startsWith("﻿")) content = content.substring(1);
                for (String line : content.split("[\r\n]+")) {
                    String code = line.trim();
                    if (code.isEmpty() || "商品编号".equals(code)) continue;
                    // 处理带引号或逗号的情况：取第一个字段
                    if (code.contains(",")) code = code.split(",")[0].trim();
                    if (!code.isEmpty()) itemCodes.add(code);
                }
            } else {
                // Excel：EasyExcel 解析
                List<Object> allRows = EasyExcel.read(file.getInputStream()).sheet().doReadSync();
                for (int i = 1; i < allRows.size(); i++) {
                    List<?> row = (List<?>) ((List<?>) allRows.get(i));
                    if (row.isEmpty()) continue;
                    String code = String.valueOf(row.get(0) != null ? row.get(0) : "").trim();
                    if (!code.isEmpty() && !"商品编号".equals(code)) itemCodes.add(code);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("文件解析失败：" + e.getMessage());
        }
        if (itemCodes.isEmpty()) throw new IllegalArgumentException("未解析到商品编号");

        // 复用 parseItems 逻辑但避免重复批次
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String code : itemCodes) {
            List<Map<String, Object>> goods = jdbcTemplate.queryForList(
                    "SELECT goods_code, goods_name, spec, base_unit FROM base_goods WHERE goods_code = ? AND status != 'STOPPED'", code);
            if (goods.isEmpty()) { notFound.add(code); continue; }
            Map<String, Object> g = goods.get(0);
            List<Map<String, Object>> batches = jdbcTemplate.queryForList(
                    "SELECT batch_no, production_date, qty, cost_price FROM inv_batch_stock WHERE goods_code = ? AND warehouse = ? AND qty > 0 ORDER BY batch_no",
                    code, warehouse);
            if (batches.isEmpty()) {
                BigDecimal costPrice = BigDecimal.ZERO;
                List<Map<String, Object>> bal = jdbcTemplate.queryForList(
                        "SELECT cost_price FROM inv_stock_balance WHERE goods_code = ? AND warehouse = ?", code, warehouse);
                if (!bal.isEmpty()) costPrice = toBigDecimal(bal.get(0).get("COST_PRICE"));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("goodsCode", code);
                item.put("goodsName", String.valueOf(g.getOrDefault("GOODS_NAME", "")));
                item.put("spec", String.valueOf(g.getOrDefault("SPEC", "")));
                item.put("unitName", String.valueOf(g.getOrDefault("BASE_UNIT", "")));
                item.put("batchNo", ""); item.put("productionDate", null);
                item.put("bookQty", 0); item.put("costPrice", costPrice);
                items.add(item);
            } else {
                for (Map<String, Object> b : batches) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("goodsCode", code);
                    item.put("goodsName", String.valueOf(g.getOrDefault("GOODS_NAME", "")));
                    item.put("spec", String.valueOf(g.getOrDefault("SPEC", "")));
                    item.put("unitName", String.valueOf(g.getOrDefault("BASE_UNIT", "")));
                    item.put("batchNo", String.valueOf(b.getOrDefault("BATCH_NO", "")));
                    item.put("productionDate", b.get("PRODUCTION_DATE"));
                    item.put("bookQty", toBigDecimal(b.get("QTY")));
                    item.put("costPrice", toBigDecimal(b.getOrDefault("COST_PRICE", 0)));
                    items.add(item);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("notFound", notFound);
        result.put("total", items.size());
        return ApiResponse.ok(result);
    }

    // ==================== 导出盘点明细 ====================

    @PostMapping("/export-detail")
    public void exportDetail(@RequestBody Map<String, Object> req, HttpServletResponse response) throws IOException {
        String sheetNo = String.valueOf(req.getOrDefault("sheetNo", req.getOrDefault("bizId", "")));
        if (sheetNo.isBlank()) throw new IllegalArgumentException("缺少盘点单号");

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_count_detail WHERE sheet_no = ? ORDER BY line_no, detail_id", sheetNo);
        if (details.isEmpty()) throw new IllegalArgumentException("无明细数据");

        String fileName = URLEncoder.encode("盘点明细_" + sheetNo + ".xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + fileName);

        // 表头
        List<List<String>> head = List.of(
                List.of("行号"), List.of("商品编号"), List.of("商品名称"), List.of("规格"),
                List.of("批次号"), List.of("生产日期"), List.of("账面数量"), List.of("实盘数量"),
                List.of("差异数量"), List.of("成本单价"), List.of("账面金额"), List.of("实盘金额"),
                List.of("差异金额"), List.of("差异备注")
        );

        List<List<Object>> body = new ArrayList<>();
        for (Map<String, Object> d : details) {
            body.add(List.of(
                    d.get("LINE_NO") != null ? ((Number) d.get("LINE_NO")).intValue() : "",
                    String.valueOf(d.getOrDefault("GOODS_CODE", "")),
                    String.valueOf(d.getOrDefault("GOODS_NAME", "")),
                    String.valueOf(d.getOrDefault("SPEC", "")),
                    String.valueOf(d.getOrDefault("BATCH_NO", "")),
                    d.get("PRODUCTION_DATE") != null ? String.valueOf(d.get("PRODUCTION_DATE")).substring(0, 10) : "",
                    toBigDecimal(d.get("BOOK_QTY")).stripTrailingZeros().toPlainString(),
                    toBigDecimal(d.get("REAL_QTY")).stripTrailingZeros().toPlainString(),
                    toBigDecimal(d.get("DIFF_QTY")).stripTrailingZeros().toPlainString(),
                    toBigDecimal(d.get("COST_PRICE")).toPlainString(),
                    toBigDecimal(d.get("BOOK_AMOUNT")).toPlainString(),
                    toBigDecimal(d.get("REAL_AMOUNT")).toPlainString(),
                    toBigDecimal(d.get("DIFF_AMOUNT")).toPlainString(),
                    String.valueOf(d.getOrDefault("DIFF_REMARK", ""))
            ));
        }

        EasyExcel.write(response.getOutputStream()).sheet("盘点明细").head(head).doWrite(body);
    }

    // ==================== 导入实盘数量 ====================

    @PostMapping("/import-real")
    @Transactional
    public ApiResponse<Map<String, Object>> importReal(@RequestParam("file") MultipartFile file,
                                                       @RequestParam("sheetNo") String sheetNo) {
        if (file.isEmpty()) throw new IllegalArgumentException("文件为空");
        if (sheetNo.isBlank()) throw new IllegalArgumentException("缺少盘点单号");

        List<Map<String, Object>> updates = new ArrayList<>();
        try {
            List<Object> allRows = EasyExcel.read(file.getInputStream()).sheet().doReadSync();
            // 跳过表头，读取 行号(第1列) + 实盘数量(第8列，0-indexed=7)
            for (int i = 1; i < allRows.size(); i++) {
                List<?> row = (List<?>) ((List<?>) allRows.get(i));
                if (row.size() < 2) continue;
                try {
                    int lineNo = Integer.parseInt(String.valueOf(row.get(0) != null ? row.get(0) : "0").trim());
                    BigDecimal realQty = row.size() >= 8 && row.get(7) != null
                            ? new BigDecimal(String.valueOf(row.get(7)).trim())
                            : BigDecimal.ZERO;
                    if (lineNo > 0) {
                        Map<String, Object> u = new LinkedHashMap<>();
                        u.put("lineNo", lineNo);
                        u.put("realQty", realQty);
                        updates.add(u);
                    }
                } catch (NumberFormatException ignored) { /* skip unparseable rows */ }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Excel 解析失败：" + e.getMessage());
        }

        if (updates.isEmpty()) throw new IllegalArgumentException("未解析到有效的实盘数据");

        int count = 0;
        for (Map<String, Object> u : updates) {
            int lineNo = ((Number) u.get("lineNo")).intValue();
            BigDecimal realQty = (BigDecimal) u.get("realQty");

            // 按 sheet_no + line_no 匹配明细
            List<Map<String, Object>> details = jdbcTemplate.queryForList(
                    "SELECT detail_id, book_qty, cost_price FROM inv_count_detail WHERE sheet_no = ? AND line_no = ?",
                    sheetNo, lineNo);
            if (details.isEmpty()) continue;

            for (Map<String, Object> d : details) {
                long detailId = ((Number) d.get("DETAIL_ID")).longValue();
                BigDecimal bookQty = toBigDecimal(d.get("BOOK_QTY"));
                BigDecimal costPrice = toBigDecimal(d.get("COST_PRICE"));
                BigDecimal diffQty = realQty.subtract(bookQty);
                BigDecimal realAmount = realQty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
                BigDecimal bookAmount = bookQty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
                BigDecimal diffAmount = diffQty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);

                jdbcTemplate.update(
                        "UPDATE inv_count_detail SET real_qty=?, diff_qty=?, real_amount=?, book_amount=?, diff_amount=? WHERE detail_id=?",
                        realQty, diffQty, realAmount, bookAmount, diffAmount, detailId);
                count++;
            }
        }

        return ApiResponse.ok(Map.of("updated", count, "sheetNo", sheetNo));
    }

    // ==================== 创建 ====================

    @PostMapping("/create")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        String warehouse = String.valueOf(req.getOrDefault("warehouse", ""));
        if (warehouse.isBlank()) throw new IllegalArgumentException("请选择盘点仓库");

        String countType = String.valueOf(req.getOrDefault("countType", "1"));
        String remark = String.valueOf(req.getOrDefault("remark", ""));
        String countDate = String.valueOf(req.getOrDefault("countDate", LocalDate.now().toString()));

        String sheetNo = billNoGenerator.nextNo(BillNoGenerator.BillType.STOCK_TAKE, "inv_count_sheet", "sheet_no");

        // 插入主表
        jdbcTemplate.update(
                "INSERT INTO inv_count_sheet(sheet_no, warehouse, count_date, count_type, status, remark, create_by, create_time) VALUES (?,?,?,?,'PENDING',?,'管理员',CURRENT_TIMESTAMP)",
                sheetNo, warehouse, countDate, countType, remark);

        // 获取明细数据源
        @SuppressWarnings("unchecked")
        List<String> itemNos = (List<String>) req.get("itemNos");
        List<Map<String, Object>> batchRows;

        if ("2".equals(countType) && itemNos != null && !itemNos.isEmpty()) {
            // 抽盘：只查指定商品
            String placeholders = String.join(",", itemNos.stream().map(s -> "?").toList());
            batchRows = jdbcTemplate.queryForList(
                    "SELECT bs.*, g.spec, g.base_unit FROM inv_batch_stock bs LEFT JOIN base_goods g ON bs.goods_code = g.goods_code WHERE bs.warehouse = ? AND bs.goods_code IN (" + placeholders + ") ORDER BY bs.goods_code, bs.batch_no",
                    Stream.concat(Stream.of(warehouse), itemNos.stream()).toArray());
        } else {
            // 全盘：该仓所有有库存的商品
            batchRows = jdbcTemplate.queryForList(
                    "SELECT bs.*, g.spec, g.base_unit FROM inv_batch_stock bs LEFT JOIN base_goods g ON bs.goods_code = g.goods_code WHERE bs.warehouse = ? ORDER BY bs.goods_code, bs.batch_no",
                    warehouse);
        }

        int lineNo = 1;
        Set<String> seenGoods = new HashSet<>();

        for (Map<String, Object> br : batchRows) {
            String goodsCode = String.valueOf(br.getOrDefault("GOODS_CODE", ""));
            String goodsName = String.valueOf(br.getOrDefault("GOODS_NAME", ""));
            String spec = String.valueOf(br.getOrDefault("SPEC", ""));
            String unitName = String.valueOf(br.getOrDefault("BASE_UNIT", ""));
            String batchNo = String.valueOf(br.getOrDefault("BATCH_NO", ""));
            Object prodDate = br.get("PRODUCTION_DATE");
            BigDecimal qty = toBigDecimal(br.get("QTY"));
            BigDecimal costPrice = toBigDecimal(br.getOrDefault("COST_PRICE", 0));
            BigDecimal amount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);

            jdbcTemplate.update(
                    "INSERT INTO inv_count_detail(sheet_no, goods_code, goods_name, spec, unit_name, batch_no, production_date, book_qty, real_qty, diff_qty, cost_price, book_amount, real_amount, diff_amount, is_new_batch, line_no) VALUES (?,?,?,?,?,?,?,?,0,0,?,?,0,0,0,?)",
                    sheetNo, goodsCode, goodsName, spec, unitName, batchNo, prodDate, qty, costPrice, amount, lineNo);
            seenGoods.add(goodsCode);
            lineNo++;
        }

        // 抽盘：对 itemNos 中但没有批次库存的商品，生成 book_qty=0 的行
        if ("2".equals(countType) && itemNos != null) {
            for (String itemNo : itemNos) {
                if (seenGoods.contains(itemNo)) continue;
                // 从 base_goods 取基本信息
                List<Map<String, Object>> goodsList = jdbcTemplate.queryForList(
                        "SELECT goods_code, goods_name, spec, base_unit, cost_price FROM base_goods WHERE goods_code = ?", itemNo);
                if (goodsList.isEmpty()) continue;
                Map<String, Object> g = goodsList.get(0);
                // 尝试从 inv_stock_balance 取成本价
                BigDecimal costPrice = BigDecimal.ZERO;
                List<Map<String, Object>> bal = jdbcTemplate.queryForList(
                        "SELECT cost_price FROM inv_stock_balance WHERE goods_code = ? AND warehouse = ?", itemNo, warehouse);
                if (!bal.isEmpty()) costPrice = toBigDecimal(bal.get(0).get("COST_PRICE"));

                jdbcTemplate.update(
                        "INSERT INTO inv_count_detail(sheet_no, goods_code, goods_name, spec, unit_name, batch_no, book_qty, real_qty, diff_qty, cost_price, book_amount, real_amount, diff_amount, is_new_batch, line_no) VALUES (?,?,?,?,?,'',0,0,0,?,0,0,0,1,?)",
                        sheetNo, String.valueOf(g.get("GOODS_CODE")), String.valueOf(g.getOrDefault("GOODS_NAME", "")),
                        String.valueOf(g.getOrDefault("SPEC", "")), String.valueOf(g.getOrDefault("BASE_UNIT", "")),
                        costPrice, lineNo);
                lineNo++;
            }
        }

        int detailCount = lineNo - 1;
        return ApiResponse.ok(Map.of("sheetNo", sheetNo, "detailCount", detailCount));
    }

    // ==================== 保存实盘 ====================

    @PostMapping("/update-real")
    @Transactional
    public ApiResponse<Map<String, Object>> updateReal(@RequestBody Map<String, Object> req) {
        String sheetNo = String.valueOf(req.getOrDefault("sheetNo", req.getOrDefault("bizId", "")));
        if (sheetNo.isBlank()) throw new IllegalArgumentException("缺少盘点单号");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) req.get("details");
        if (details == null || details.isEmpty()) throw new IllegalArgumentException("缺少明细数据");

        for (Map<String, Object> d : details) {
            long detailId = d.get("detailId") instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(d.get("detailId")));
            BigDecimal realQty = toBigDecimal(d.get("realQty"));

            // 取当前行的账面数量和成本单价
            List<Map<String, Object>> row = jdbcTemplate.queryForList(
                    "SELECT book_qty, cost_price FROM inv_count_detail WHERE detail_id = ?", detailId);
            if (row.isEmpty()) continue;
            BigDecimal bookQty = toBigDecimal(row.get(0).get("BOOK_QTY"));
            BigDecimal costPrice = toBigDecimal(row.get(0).get("COST_PRICE"));

            BigDecimal diffQty = realQty.subtract(bookQty);
            BigDecimal realAmount = realQty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
            BigDecimal bookAmount = bookQty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
            BigDecimal diffAmount = diffQty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
            String diffRemark = String.valueOf(d.getOrDefault("diffRemark", ""));

            jdbcTemplate.update(
                    "UPDATE inv_count_detail SET real_qty=?, diff_qty=?, real_amount=?, book_amount=?, diff_amount=?, diff_remark=? WHERE detail_id=?",
                    realQty, diffQty, realAmount, bookAmount, diffAmount, diffRemark, detailId);
        }

        return ApiResponse.ok(Map.of("updated", details.size()));
    }

    // ==================== 审核 ====================

    @PostMapping("/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> audit(@RequestBody Map<String, Object> req) {
        String sheetNo = String.valueOf(req.getOrDefault("sheetNo", req.getOrDefault("bizId", "")));
        if (sheetNo.isBlank()) throw new IllegalArgumentException("缺少盘点单号");

        // 支持按 count_sheet_id 或 sheet_no 查找
        List<Map<String, Object>> sheets;
        try {
            long id = Long.parseLong(sheetNo);
            sheets = jdbcTemplate.queryForList("SELECT * FROM inv_count_sheet WHERE count_sheet_id = ?", id);
        } catch (NumberFormatException ex) {
            sheets = jdbcTemplate.queryForList("SELECT * FROM inv_count_sheet WHERE sheet_no = ?", sheetNo);
        }
        if (sheets.isEmpty()) throw new IllegalArgumentException("盘点单不存在");
        sheetNo = String.valueOf(sheets.get(0).get("SHEET_NO"));
        if ("APPROVED".equals(String.valueOf(sheets.get(0).get("STATUS"))))
            throw new IllegalArgumentException("盘点单已审核，不能重复审核");

        String warehouse = String.valueOf(sheets.get(0).get("WAREHOUSE"));

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_count_detail WHERE sheet_no = ? ORDER BY line_no", sheetNo);

        int surplusCount = 0, shortageCount = 0;

        for (Map<String, Object> d : details) {
            long detailId = ((Number) d.get("DETAIL_ID")).longValue();
            String goodsCode = String.valueOf(d.getOrDefault("GOODS_CODE", ""));
            String goodsName = String.valueOf(d.getOrDefault("GOODS_NAME", ""));
            String batchNo = String.valueOf(d.getOrDefault("BATCH_NO", ""));
            BigDecimal bookQty = toBigDecimal(d.get("BOOK_QTY"));
            BigDecimal realQty = toBigDecimal(d.get("REAL_QTY"));
            BigDecimal diffQty = toBigDecimal(d.get("DIFF_QTY"));

            // 重新取最新成本单价
            BigDecimal costPrice = getCostPrice(goodsCode, warehouse, batchNo);

            // 重算金额
            BigDecimal bookAmount = bookQty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
            BigDecimal realAmount = realQty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);
            BigDecimal diffAmount = diffQty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);

            // 更新明细的成本单价和金额
            jdbcTemplate.update(
                    "UPDATE inv_count_detail SET cost_price=?, book_amount=?, real_amount=?, diff_amount=? WHERE detail_id=?",
                    costPrice, bookAmount, realAmount, diffAmount, detailId);

            if (diffQty.compareTo(BigDecimal.ZERO) == 0) continue;

            if (diffQty.compareTo(BigDecimal.ZERO) > 0) {
                // 盘盈：增加库存
                addStock(goodsCode, goodsName, warehouse, batchNo, diffQty, costPrice);
                BigDecimal balance = getBalanceQty(goodsCode, warehouse);
                insertLedger(goodsCode, goodsName, warehouse, batchNo, "IN", diffQty, costPrice, diffAmount, balance, sheetNo);
                surplusCount++;
            } else {
                // 盘亏：扣减库存
                BigDecimal absQty = diffQty.abs();
                deductStock(goodsCode, warehouse, batchNo, absQty);
                BigDecimal balance = getBalanceQty(goodsCode, warehouse);
                insertLedger(goodsCode, goodsName, warehouse, batchNo, "OUT", absQty, costPrice, diffAmount.abs(), balance, sheetNo);
                shortageCount++;
            }
        }

        // 更新主表状态
        jdbcTemplate.update(
                "UPDATE inv_count_sheet SET status='APPROVED', audit_by='管理员', audit_time=CURRENT_TIMESTAMP WHERE sheet_no=?",
                sheetNo);

        return ApiResponse.ok(Map.of(
                "status", "APPROVED",
                "sheetNo", sheetNo,
                "surplusCount", surplusCount,
                "shortageCount", shortageCount,
                "effect", "盘点审核完成：盘盈 " + surplusCount + " 行，盘亏 " + shortageCount + " 行"
        ));
    }

    // ==================== 反审核 ====================

    @PostMapping("/reverse-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> reverseAudit(@RequestBody Map<String, Object> req) {
        String sheetNo = String.valueOf(req.getOrDefault("sheetNo", req.getOrDefault("bizId", "")));
        if (sheetNo.isBlank()) throw new IllegalArgumentException("缺少盘点单号");

        List<Map<String, Object>> sheets = jdbcTemplate.queryForList(
                "SELECT * FROM inv_count_sheet WHERE sheet_no = ?", sheetNo);
        if (sheets.isEmpty()) throw new IllegalArgumentException("盘点单不存在");
        if (!"APPROVED".equals(String.valueOf(sheets.get(0).get("STATUS"))))
            throw new IllegalArgumentException("盘点单未审核，不能反审核");

        String warehouse = String.valueOf(sheets.get(0).get("WAREHOUSE"));

        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM inv_count_detail WHERE sheet_no = ? ORDER BY line_no", sheetNo);

        for (Map<String, Object> d : details) {
            String goodsCode = String.valueOf(d.getOrDefault("GOODS_CODE", ""));
            String goodsName = String.valueOf(d.getOrDefault("GOODS_NAME", ""));
            String batchNo = String.valueOf(d.getOrDefault("BATCH_NO", ""));
            BigDecimal diffQty = toBigDecimal(d.get("DIFF_QTY"));
            BigDecimal costPrice = toBigDecimal(d.get("COST_PRICE"));
            BigDecimal diffAmount = toBigDecimal(d.get("DIFF_AMOUNT"));

            if (diffQty.compareTo(BigDecimal.ZERO) == 0) continue;

            if (diffQty.compareTo(BigDecimal.ZERO) > 0) {
                // 原来盘盈，反审核：扣回
                deductStock(goodsCode, warehouse, batchNo, diffQty);
                BigDecimal balance = getBalanceQty(goodsCode, warehouse);
                insertLedger(goodsCode, goodsName, warehouse, batchNo, "OUT", diffQty, costPrice, diffAmount, balance,
                        sheetNo + "(反审核)");
            } else {
                // 原来盘亏，反审核：加回
                BigDecimal absQty = diffQty.abs();
                addStock(goodsCode, goodsName, warehouse, batchNo, absQty, costPrice);
                BigDecimal balance = getBalanceQty(goodsCode, warehouse);
                insertLedger(goodsCode, goodsName, warehouse, batchNo, "IN", absQty, costPrice, diffAmount.abs(), balance,
                        sheetNo + "(反审核)");
            }
        }

        jdbcTemplate.update("UPDATE inv_count_sheet SET status='PENDING', audit_by=NULL, audit_time=NULL WHERE sheet_no=?", sheetNo);
        return ApiResponse.ok(Map.of("status", "PENDING", "sheetNo", sheetNo));
    }

    // ==================== 删除 ====================

    @PostMapping("/delete")
    @Transactional
    public ApiResponse<Map<String, Object>> delete(@RequestBody Map<String, Object> req) {
        String sheetNo = String.valueOf(req.getOrDefault("sheetNo", req.getOrDefault("bizId", "")));
        if (sheetNo.isBlank()) throw new IllegalArgumentException("缺少盘点单号");

        List<Map<String, Object>> sheets = jdbcTemplate.queryForList(
                "SELECT * FROM inv_count_sheet WHERE sheet_no = ?", sheetNo);
        if (sheets.isEmpty()) throw new IllegalArgumentException("盘点单不存在");
        if ("APPROVED".equals(String.valueOf(sheets.get(0).get("STATUS"))))
            throw new IllegalArgumentException("已审核的盘点单不能删除");

        jdbcTemplate.update("DELETE FROM inv_count_detail WHERE sheet_no = ?", sheetNo);
        jdbcTemplate.update("DELETE FROM inv_count_sheet WHERE sheet_no = ?", sheetNo);
        return ApiResponse.ok(Map.of("deleted", sheetNo));
    }

    // ==================== 库存操作辅助方法 ====================

    private BigDecimal getCostPrice(String goodsCode, String warehouse, String batchNo) {
        // 优先按批次取成本
        List<Map<String, Object>> rows;
        if (batchNo != null && !batchNo.isBlank()) {
            rows = jdbcTemplate.queryForList(
                    "SELECT cost_price FROM inv_batch_stock WHERE goods_code=? AND warehouse=? AND batch_no=?",
                    goodsCode, warehouse, batchNo);
        } else {
            rows = jdbcTemplate.queryForList(
                    "SELECT cost_price FROM inv_batch_stock WHERE goods_code=? AND warehouse=? AND (batch_no IS NULL OR batch_no='') ORDER BY last_inout_time DESC LIMIT 1",
                    goodsCode, warehouse);
        }
        if (!rows.isEmpty() && rows.get(0).get("COST_PRICE") != null)
            return toBigDecimal(rows.get(0).get("COST_PRICE"));
        // fallback to balance
        rows = jdbcTemplate.queryForList(
                "SELECT cost_price FROM inv_stock_balance WHERE goods_code=? AND warehouse=?",
                goodsCode, warehouse);
        if (!rows.isEmpty() && rows.get(0).get("COST_PRICE") != null)
            return toBigDecimal(rows.get(0).get("COST_PRICE"));
        return BigDecimal.ZERO;
    }

    private BigDecimal getBalanceQty(String goodsCode, String warehouse) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT physical_qty FROM inv_stock_balance WHERE goods_code=? AND warehouse=?",
                goodsCode, warehouse);
        if (rows.isEmpty()) return BigDecimal.ZERO;
        return toBigDecimal(rows.get(0).get("PHYSICAL_QTY"));
    }

    private void deductStock(String goodsCode, String warehouse, String batchNo, BigDecimal qty) {
        // 扣减批次库存
        if (batchNo != null && !batchNo.isBlank()) {
            jdbcTemplate.update(
                    "UPDATE inv_batch_stock SET qty = qty - ?, stock_amount = (qty - ?) * cost_price, last_inout_time = CURRENT_TIMESTAMP WHERE goods_code = ? AND warehouse = ? AND batch_no = ?",
                    qty, qty, goodsCode, warehouse, batchNo);
        }
        // 扣减汇总库存
        int updated = jdbcTemplate.update(
                "UPDATE inv_stock_balance SET physical_qty = physical_qty - ?, available_qty = available_qty - ?, stock_amount = (physical_qty - ?) * cost_price, last_inout_time = CURRENT_TIMESTAMP WHERE goods_code = ? AND warehouse = ? AND available_qty >= ?",
                qty, qty, qty, goodsCode, warehouse, qty);
        if (updated == 0) {
            throw new IllegalArgumentException("库存不足或记录不存在：" + goodsCode + " / " + warehouse);
        }
    }

    private void addStock(String goodsCode, String goodsName, String warehouse, String batchNo, BigDecimal qty, BigDecimal costPrice) {
        BigDecimal stockAmount = qty.multiply(costPrice).setScale(2, RoundingMode.HALF_UP);

        // 更新或创建批次库存
        if (batchNo != null && !batchNo.isBlank()) {
            int batchUpdated = jdbcTemplate.update(
                    "UPDATE inv_batch_stock SET qty = qty + ?, stock_amount = (qty + ?) * cost_price, last_inout_time = CURRENT_TIMESTAMP WHERE goods_code = ? AND warehouse = ? AND batch_no = ?",
                    qty, qty, goodsCode, warehouse, batchNo);
            if (batchUpdated == 0) {
                // 创建新批次
                String batchStockId = "BS" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
                jdbcTemplate.update(
                        "INSERT INTO inv_batch_stock(batch_stock_id, goods_code, goods_name, warehouse, batch_no, qty, cost_price, stock_amount, status, last_inout_time, created_at) VALUES (?,?,?,?,?,?,?,?,'NORMAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                        batchStockId, goodsCode, goodsName, warehouse, batchNo, qty, costPrice, stockAmount);
            }
        }

        // 更新汇总库存
        int updated = jdbcTemplate.update(
                "UPDATE inv_stock_balance SET physical_qty = physical_qty + ?, available_qty = available_qty + ?, stock_amount = (physical_qty + ?) * cost_price, last_inout_time = CURRENT_TIMESTAMP WHERE goods_code = ? AND warehouse = ?",
                qty, qty, qty, goodsCode, warehouse);
        if (updated == 0) {
            // 创建新库存记录
            String balanceId = "SB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update(
                    "INSERT INTO inv_stock_balance(balance_id, goods_code, goods_name, warehouse, batch_no, physical_qty, locked_qty, frozen_qty, available_qty, purchase_on_way, cost_price, stock_amount, last_inout_time) VALUES (?,?,?,?,?,?,0,0,?,0,?,?,CURRENT_TIMESTAMP)",
                    balanceId, goodsCode, goodsName, warehouse, batchNo != null ? batchNo : "", qty, qty, costPrice, stockAmount);
        }
    }

    private void insertLedger(String goodsCode, String goodsName, String warehouse, String batchNo, String direction,
                              BigDecimal qty, BigDecimal costPrice, BigDecimal amount, BigDecimal balanceQty, String sourceBill) {
        String ledgerId = "SL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String ledgerNo = "INV" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        jdbcTemplate.update(
                "INSERT INTO inv_stock_ledger(ledger_id, ledger_no, occurred_at, source_bill, goods_code, goods_name, warehouse, batch_no, direction, qty, cost_price, amount, balance_qty, operator_name) VALUES (?,?,CURRENT_TIMESTAMP,?,?,?,?,?,?,?,?,?,?,'管理员')",
                ledgerId, ledgerNo, sourceBill, goodsCode, goodsName, warehouse,
                batchNo != null ? batchNo : "", direction, qty, costPrice, amount, balanceQty);
    }

    // ==================== 工具方法 ====================

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    static Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String key = e.getKey();
            StringBuilder sb = new StringBuilder();
            boolean up = false;
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                if (c == '_') { up = true; continue; }
                sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
                up = false;
            }
            result.put(sb.toString(), e.getValue());
        }
        return result;
    }

    private static String strLower(Object v) {
        return v == null ? "" : String.valueOf(v).trim().toLowerCase(Locale.ROOT);
    }
}
