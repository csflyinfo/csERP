package com.erp.sales;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.common.util.BillNoGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 飞单模块：供应商直送客户，跳过仓库入库/出库。
 * <p>审核时一键生成采购订单(APPROVED) + 销售订单(APPROVED) + 应付(fin_ap) + 应收(fin_ar)。
 * <p>不调用 InventoryCostService，不写 inv_batch_stock / inv_stock_balance。
 */
@RestController
public class FlyOrderController {

    private final JdbcTemplate jdbcTemplate;
    private final BillNoGenerator billNoGen;

    public FlyOrderController(JdbcTemplate jdbcTemplate, BillNoGenerator billNoGen) {
        this.jdbcTemplate = jdbcTemplate;
        this.billNoGen = billNoGen;
    }

    // ====================== 创建飞单（草稿） ======================

    @PostMapping("/sales/fly-order/create")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        String flyId = "FD" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String flyNo = billNoGen.nextNo(BillNoGenerator.BillType.FLY_ORDER, "fly_order", "fly_no");
        String supplierCode = str(req.get("supplierCode"));
        String supplierName = str(req.get("supplierName"));
        String customerCode = str(req.get("customerCode"));
        String customerName = str(req.get("customerName"));
        String salesman = str(req.get("salesman"));
        LocalDate billDate = parseDate(req.get("billDate"));
        String remark = str(req.get("remark"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = req.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();

        BigDecimal purchaseTotal = BigDecimal.ZERO;
        BigDecimal salesTotal = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            purchaseTotal = purchaseTotal.add(toBd(d.get("purchaseAmount")));
            salesTotal = salesTotal.add(toBd(d.get("salesAmount")));
        }
        BigDecimal profit = salesTotal.subtract(purchaseTotal);

        jdbcTemplate.update("""
                INSERT INTO fly_order (fly_id, fly_no, supplier_code, supplier_name, customer_code, customer_name,
                    salesman, bill_date, purchase_amount, sales_amount, profit_amount, status, remark, creator_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                """,
                flyId, flyNo, supplierCode, supplierName, customerCode, customerName,
                salesman, billDate, purchaseTotal, salesTotal, profit, remark, "系统管理员");

        for (Map<String, Object> d : details) {
            String detailId = "FDD" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO fly_order_detail (detail_id, fly_id, goods_code, goods_name, spec, unit_name,
                        unit_level, convert_qty, qty, purchase_price, sales_price, purchase_amount, sales_amount,
                        tax_rate, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    detailId, flyId,
                    str(d.get("goodsCode")), str(d.get("goodsName")), str(d.get("spec")),
                    str(d.get("unitName")), toInt(d.get("unitLevel"), 1), toBd(d.get("convertQty")),
                    toBd(d.get("qty")), toBd(d.get("purchasePrice")), toBd(d.get("salesPrice")),
                    toBd(d.get("purchaseAmount")), toBd(d.get("salesAmount")),
                    str(d.get("taxRate")), str(d.get("remark")));
        }

        Map<String, Object> out = new HashMap<>();
        out.put("flyId", flyId);
        out.put("flyNo", flyNo);
        out.put("status", "DRAFT");
        out.put("success", true);
        return ApiResponse.ok(out);
    }

    // ====================== 更新飞单（仅草稿） ======================

    @PostMapping("/sales/fly-order/update")
    @Transactional
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> req) {
        String flyId = str(req.get("flyId"));
        if (flyId.isBlank()) return ApiResponse.fail("400", "缺少 flyId");

        List<Map<String, Object>> exist = jdbcTemplate.queryForList(
                "SELECT status FROM fly_order WHERE fly_id = ?", flyId);
        if (exist.isEmpty()) return ApiResponse.fail("404", "飞单不存在");
        String status = str(pickCS(exist.get(0), "status"));
        if (!"DRAFT".equals(status)) return ApiResponse.fail("400", "只有待审核飞单可编辑");

        String supplierCode = str(req.get("supplierCode"));
        String supplierName = str(req.get("supplierName"));
        String customerCode = str(req.get("customerCode"));
        String customerName = str(req.get("customerName"));
        String salesman = str(req.get("salesman"));
        LocalDate billDate = parseDate(req.get("billDate"));
        String remark = str(req.get("remark"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = req.get("details") instanceof List<?> l
                ? (List<Map<String, Object>>) l : new ArrayList<>();

        BigDecimal purchaseTotal = BigDecimal.ZERO;
        BigDecimal salesTotal = BigDecimal.ZERO;
        for (Map<String, Object> d : details) {
            purchaseTotal = purchaseTotal.add(toBd(d.get("purchaseAmount")));
            salesTotal = salesTotal.add(toBd(d.get("salesAmount")));
        }
        BigDecimal profit = salesTotal.subtract(purchaseTotal);

        jdbcTemplate.update("""
                UPDATE fly_order SET supplier_code=?, supplier_name=?, customer_code=?, customer_name=?,
                    salesman=?, bill_date=?, purchase_amount=?, sales_amount=?, profit_amount=?, remark=?
                WHERE fly_id=?
                """,
                supplierCode, supplierName, customerCode, customerName,
                salesman, billDate, purchaseTotal, salesTotal, profit, remark, flyId);

        jdbcTemplate.update("DELETE FROM fly_order_detail WHERE fly_id = ?", flyId);
        for (Map<String, Object> d : details) {
            String detailId = "FDD" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO fly_order_detail (detail_id, fly_id, goods_code, goods_name, spec, unit_name,
                        unit_level, convert_qty, qty, purchase_price, sales_price, purchase_amount, sales_amount,
                        tax_rate, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    detailId, flyId,
                    str(d.get("goodsCode")), str(d.get("goodsName")), str(d.get("spec")),
                    str(d.get("unitName")), toInt(d.get("unitLevel"), 1), toBd(d.get("convertQty")),
                    toBd(d.get("qty")), toBd(d.get("purchasePrice")), toBd(d.get("salesPrice")),
                    toBd(d.get("purchaseAmount")), toBd(d.get("salesAmount")),
                    str(d.get("taxRate")), str(d.get("remark")));
        }

        Map<String, Object> out = new HashMap<>();
        out.put("flyId", flyId);
        out.put("success", true);
        return ApiResponse.ok(out);
    }

    // ====================== 分页查询（带筛选） ======================

    @PostMapping("/sales/fly-order/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody PageRequest request) {
        Map<String, Object> filters = request.filters();
        LocalDate dateFrom = parseDate(filters != null ? filters.get("dateFrom") : null);
        LocalDate dateTo = parseDate(filters != null ? filters.get("dateTo") : null);
        String customerCode = filters != null ? str(filters.get("customerCode")) : "";
        String supplierCode = filters != null ? str(filters.get("supplierCode")) : "";
        String status = filters != null ? str(filters.get("status")).trim() : "";

        StringBuilder sql = new StringBuilder("""
                SELECT fly_id, fly_no, supplier_code, supplier_name, customer_code, customer_name,
                       salesman, bill_date, purchase_amount, sales_amount, profit_amount, status,
                       purchase_order_no, sales_order_no, remark, creator_name, create_time,
                       audit_user, audit_time
                FROM fly_order WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();

        if (dateFrom != null) { sql.append(" AND bill_date >= ?"); params.add(dateFrom); }
        if (dateTo != null) { sql.append(" AND bill_date <= ?"); params.add(dateTo); }
        if (!customerCode.isBlank()) { sql.append(" AND customer_code = ?"); params.add(customerCode); }
        if (!supplierCode.isBlank()) { sql.append(" AND supplier_code = ?"); params.add(supplierCode); }
        if (!status.isBlank()) { sql.append(" AND status = ?"); params.add(status); }

        sql.append(" ORDER BY create_time DESC, fly_no DESC");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = camelize(r);
            String st = String.valueOf(row.getOrDefault("status", ""));
            row.put("statusText", switch (st) {
                case "DRAFT" -> "待审核";
                case "APPROVED" -> "已审核";
                case "CANCELLED" -> "已作废";
                default -> st;
            });
            Object creator = row.getOrDefault("creatorName", "");
            Object createdAt = row.getOrDefault("createTime", "");
            row.put("creatorInfo", (creator == null ? "" : creator) + " " + (createdAt == null ? "" : createdAt));
            mapped.add(row);
        }
        // SQL 已处理筛选条件，PageResult 只做分页；避免 PageResult.of 的文本过滤将 dateFrom/dateTo 等作为子串误杀
        PageRequest paginationOnly = new PageRequest(request.pageNo(), request.pageSize(), request.sortField(), request.sortOrder(), java.util.Map.of());
        return ApiResponse.ok(PageResult.of(mapped, paginationOnly));
    }

    // ====================== 查询明细 ======================

    @GetMapping("/sales/fly-order/detail")
    public ApiResponse<Map<String, Object>> detail(@RequestParam String flyId) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT * FROM fly_order WHERE fly_id = ? OR fly_no = ?", flyId, flyId);
        if (heads.isEmpty()) return ApiResponse.fail("404", "飞单不存在");
        Map<String, Object> head = camelize(heads.get(0));
        String realFlyId = String.valueOf(head.get("flyId"));
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM fly_order_detail WHERE fly_id = ? ORDER BY detail_id", realFlyId);
        head.put("details", details.stream().map(FlyOrderController::camelize).toList());
        return ApiResponse.ok(head);
    }

    // ====================== 商品价格自动带出 ======================

    /**
     * 根据商品+供应商+客户+单位级别，自动带出采购价和销售价。
     * <p>采购价：unit_config JSON 中的 purchasePrice → 兜底 base_goods.latest_purchase_price
     * <p>销售价：客户价格组(base_price_group_item) → 客户最近售价 → 兜底 base_goods.suggested_retail_price / standard_price
     */
    @GetMapping("/sales/fly-order/goods-price")
    public ApiResponse<Map<String, Object>> goodsPrice(
            @RequestParam String goodsCode,
            @RequestParam(required = false) String customerCode,
            @RequestParam(defaultValue = "1") int unitLevel) {

        Map<String, Object> out = new HashMap<>();
        out.put("goodsCode", goodsCode);
        out.put("unitLevel", unitLevel);

        // 查商品档案
        List<Map<String, Object>> goodsRows = jdbcTemplate.queryForList(
                "SELECT goods_code, goods_name, spec, base_unit, unit_config, latest_purchase_price, " +
                "suggested_retail_price, standard_price, tax_rate FROM base_goods WHERE goods_code = ? OR goods_id = ?",
                goodsCode, goodsCode);
        if (goodsRows.isEmpty()) {
            out.put("purchasePrice", BigDecimal.ZERO);
            out.put("salesPrice", BigDecimal.ZERO);
            out.put("taxRate", "");
            out.put("unitName", "");
            out.put("units", Collections.emptyList());
            return ApiResponse.ok(out);
        }
        Map<String, Object> g = goodsRows.get(0);
        String unitConfig = str(pickCS(g, "unit_config"));
        String taxRate = str(pickCS(g, "tax_rate"));

        // 解析 unit_config，提取单位列表和对应价格
        List<Map<String, Object>> units = parseUnitConfig(unitConfig);
        out.put("units", units);
        out.put("taxRate", taxRate);

        // 选中的单位信息
        int idx = unitLevel - 1;
        String unitName = "";
        BigDecimal convertQty = BigDecimal.ONE;
        BigDecimal cfgPurchasePrice = BigDecimal.ZERO;
        BigDecimal cfgStandardPrice = BigDecimal.ZERO;
        if (idx >= 0 && idx < units.size()) {
            Map<String, Object> u = units.get(idx);
            unitName = str(u.get("unitName"));
            convertQty = toBd(u.get("convertQty"));
            cfgPurchasePrice = toBd(u.get("purchasePrice"));
            cfgStandardPrice = toBd(u.get("standardPrice"));
        }
        if (unitName.isEmpty()) unitName = str(pickCS(g, "base_unit"));
        out.put("unitName", unitName);
        out.put("convertQty", convertQty);

        // === 采购价 ===
        BigDecimal purchasePrice = cfgPurchasePrice;
        if (purchasePrice.signum() == 0) {
            // 兜底：latest_purchase_price × convertQty（大单位时乘换算率）
            BigDecimal basePurchase = toBd(pickCS(g, "latest_purchase_price"));
            purchasePrice = basePurchase.multiply(convertQty);
        }
        out.put("purchasePrice", purchasePrice);

        // === 销售价（按销售订单取价逻辑） ===
        BigDecimal salesPrice = BigDecimal.ZERO;

        // 1. 客户价格组
        if (customerCode != null && !customerCode.isBlank()) {
            try {
                List<Map<String, Object>> custRows = jdbcTemplate.queryForList(
                        "SELECT price_group_code FROM base_customer WHERE customer_code = ?", customerCode);
                if (!custRows.isEmpty()) {
                    String pgCode = str(pickCS(custRows.get(0), "price_group_code"));
                    if (!pgCode.isBlank()) {
                        List<Map<String, Object>> pgRows = jdbcTemplate.queryForList(
                                "SELECT price FROM base_price_group_item WHERE goods_code = ? AND price_group_code = ? AND unit_level = ? AND is_active = TRUE",
                                goodsCode, pgCode, unitLevel);
                        if (!pgRows.isEmpty()) {
                            salesPrice = toBd(pickCS(pgRows.get(0), "price"));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. 客户最近售价
        if (salesPrice.signum() == 0 && customerCode != null && !customerCode.isBlank()) {
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT d.price FROM sales_order_detail d " +
                        "JOIN sales_order o ON o.order_id = d.order_id " +
                        "WHERE d.goods_code = ? AND o.customer_code = ? AND d.price > 0 " +
                        "ORDER BY o.create_time DESC LIMIT 1", goodsCode, customerCode);
                if (!rows.isEmpty()) {
                    salesPrice = toBd(pickCS(rows.get(0), "price"));
                }
            } catch (Exception ignored) {}
        }

        // 3. unit_config 中的 standardPrice
        if (salesPrice.signum() == 0) {
            salesPrice = cfgStandardPrice;
        }

        // 4. 兜底：商品建议零售价 / 标准售价 × convertQty
        if (salesPrice.signum() == 0) {
            BigDecimal suggested = toBd(pickCS(g, "suggested_retail_price"));
            if (suggested.signum() > 0) {
                salesPrice = suggested.multiply(convertQty);
            } else {
                BigDecimal std = toBd(pickCS(g, "standard_price"));
                salesPrice = std.multiply(convertQty);
            }
        }
        out.put("salesPrice", salesPrice);

        return ApiResponse.ok(out);
    }

    // ====================== 审核飞单（核心） ======================

    @PostMapping("/sales/fly-order/audit")
    @Transactional
    public ApiResponse<Map<String, Object>> audit(@RequestBody Map<String, Object> req) {
        String flyId = str(req.get("flyId"));
        if (flyId.isBlank()) flyId = str(req.get("flyNo"));
        if (flyId.isBlank()) return ApiResponse.fail("400", "缺少 flyId");

        // 查飞单
        List<Map<String, Object>> flyRows = jdbcTemplate.queryForList(
                "SELECT * FROM fly_order WHERE fly_id = ? OR fly_no = ?", flyId, flyId);
        if (flyRows.isEmpty()) return ApiResponse.fail("404", "飞单不存在");
        Map<String, Object> fly = flyRows.get(0);
        String realFlyId = str(pickCS(fly, "fly_id"));
        String status = str(pickCS(fly, "status"));
        if (!"DRAFT".equals(status)) return ApiResponse.fail("400", "只有待审核飞单可审核，当前状态：" + status);

        String supplierCode = str(pickCS(fly, "supplier_code"));
        String supplierName = str(pickCS(fly, "supplier_name"));
        String customerCode = str(pickCS(fly, "customer_code"));
        String customerName = str(pickCS(fly, "customer_name"));
        String salesman = str(pickCS(fly, "salesman"));
        LocalDate billDate = parseDate(pickCS(fly, "bill_date"));
        BigDecimal purchaseTotal = toBd(pickCS(fly, "purchase_amount"));
        BigDecimal salesTotal = toBd(pickCS(fly, "sales_amount"));

        // 查明细
        List<Map<String, Object>> details = jdbcTemplate.queryForList(
                "SELECT * FROM fly_order_detail WHERE fly_id = ? ORDER BY detail_id", realFlyId);

        // ============ 1. 生成采购订单 (purchase_order + pur_order) ============
        String poId = "PO" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String poNo = billNoGen.nextNo(BillNoGenerator.BillType.PURCHASE_ORDER, "purchase_order", "order_no");

        jdbcTemplate.update("""
                INSERT INTO purchase_order (order_id, order_no, supplier_code, supplier_name, buyer, warehouse,
                    bill_date, amount, paid_amount, unpaid_amount, inbound_status, payment_status,
                    status, creator_name, audit_info, remark)
                VALUES (?, ?, ?, ?, ?, NULL, ?, ?, 0, ?, '无需入库', '未付款', 'APPROVED', ?, '飞单审核自动生成', ?)
                """,
                poId, poNo, supplierCode, supplierName, salesman, billDate,
                purchaseTotal, purchaseTotal, "系统管理员",
                "飞单 " + str(pickCS(fly, "fly_no")) + " 自动生成");

        for (Map<String, Object> d : details) {
            String detailId = "POD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO purchase_order_detail (detail_id, order_id, goods_code, goods_name, spec, unit_name,
                        unit_level, convert_qty, qty, base_qty, price, amount, tax_rate, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    detailId, poId,
                    str(pickCS(d, "goods_code")), str(pickCS(d, "goods_name")), str(pickCS(d, "spec")),
                    str(pickCS(d, "unit_name")), toInt(pickCS(d, "unit_level"), 1), toBd(pickCS(d, "convert_qty")),
                    toBd(pickCS(d, "qty")), toBd(pickCS(d, "qty")).multiply(toBd(pickCS(d, "convert_qty"))),
                    toBd(pickCS(d, "purchase_price")), toBd(pickCS(d, "purchase_amount")),
                    str(pickCS(d, "tax_rate")), "飞单生成");
        }

        // 同步写入 pur_order（报表统计兼容）
        jdbcTemplate.update("""
                INSERT INTO pur_order (order_id, order_no, supplier, buyer, warehouse, bill_date, amount,
                    inbound_amount, payment_status, arrival_status, status, creator_info, cost_amount, audit_info)
                VALUES (?, ?, ?, ?, NULL, ?, ?, 0, '未付款', '无需入库', 'APPROVED', ?, ?, '飞单审核自动生成')
                """,
                poId, poNo, supplierName, salesman, billDate, purchaseTotal,
                "系统管理员", purchaseTotal);

        for (Map<String, Object> d : details) {
            String detailId = "POD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO pur_order_detail (detail_id, order_id, line_type, goods_code, goods_name, unit_name,
                        qty, price, tax_rate, amount, cost_amount)
                    VALUES (?, ?, '正常', ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    detailId, poId,
                    str(pickCS(d, "goods_code")), str(pickCS(d, "goods_name")), str(pickCS(d, "unit_name")),
                    toBd(pickCS(d, "qty")), toBd(pickCS(d, "purchase_price")),
                    str(pickCS(d, "tax_rate")), toBd(pickCS(d, "purchase_amount")),
                    toBd(pickCS(d, "purchase_amount")));
        }

        // ============ 2. 生成销售订单 (sales_order) ============
        String soId = "SO" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String soNo = billNoGen.nextNo(BillNoGenerator.BillType.SALES_ORDER, "sales_order", "order_no");

        jdbcTemplate.update("""
                INSERT INTO sales_order (order_id, order_no, customer, customer_code, salesman, warehouse,
                    bill_date, amount, paid_amount, unpaid_amount, outbound_status, status, cost_amount,
                    creator_name, audit_info, remark)
                VALUES (?, ?, ?, ?, ?, NULL, ?, ?, 0, ?, '无需出库', 'APPROVED', ?, ?, '飞单审核自动生成', ?)
                """,
                soId, soNo, customerName, customerCode, salesman, billDate,
                salesTotal, salesTotal, purchaseTotal,
                "系统管理员", "飞单 " + str(pickCS(fly, "fly_no")) + " 自动生成");

        for (Map<String, Object> d : details) {
            String detailId = "SOD" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            jdbcTemplate.update("""
                    INSERT INTO sales_order_detail (detail_id, order_id, goods_code, goods_name, unit_name,
                        unit_level, convert_qty, qty, base_qty, price, amount, tax_rate, line_type,
                        sales_attribute, remark, cost_price, cost_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '正常', '正常', ?, ?, ?)
                    """,
                    detailId, soId,
                    str(pickCS(d, "goods_code")), str(pickCS(d, "goods_name")), str(pickCS(d, "unit_name")),
                    toInt(pickCS(d, "unit_level"), 1), toBd(pickCS(d, "convert_qty")),
                    toBd(pickCS(d, "qty")), toBd(pickCS(d, "qty")).multiply(toBd(pickCS(d, "convert_qty"))),
                    toBd(pickCS(d, "sales_price")), toBd(pickCS(d, "sales_amount")),
                    str(pickCS(d, "tax_rate")), "飞单生成",
                    toBd(pickCS(d, "purchase_price")), toBd(pickCS(d, "purchase_amount")));
        }

        // ============ 3. 生成应付 fin_ap ============
        String apId = "AP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String apNo = billNoGen.nextNo("AP", "fin_ap", "ap_no");
        jdbcTemplate.update("""
                INSERT INTO fin_ap (ap_id, ap_no, source_bill, supplier, ap_amount, paid_amount, unpaid_amount,
                    due_date, status)
                VALUES (?, ?, ?, ?, ?, 0, ?, DATEADD('DAY', 30, CURRENT_DATE), 'UNVERIFIED')
                """,
                apId, apNo, poNo, supplierName, purchaseTotal, purchaseTotal);

        // ============ 4. 生成应收 fin_ar ============
        String arId = "AR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String arNo = billNoGen.nextNo("AR", "fin_ar", "ar_no");
        jdbcTemplate.update("""
                INSERT INTO fin_ar (ar_id, ar_no, source_bill, customer, salesman, ar_amount,
                    received_amount, unreceived_amount, due_date, overdue_days, invoice_status, status)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, DATEADD('DAY', 30, CURRENT_DATE), 0, '未开票', 'UNVERIFIED')
                """,
                arId, arNo, soNo, customerName, salesman, salesTotal, salesTotal);

        // ============ 5. 更新飞单状态 ============
        jdbcTemplate.update("""
                UPDATE fly_order SET status = 'APPROVED', purchase_order_id = ?, purchase_order_no = ?,
                    sales_order_id = ?, sales_order_no = ?, audit_user = ?, audit_time = CURRENT_TIMESTAMP,
                    audit_info = ?
                WHERE fly_id = ?
                """,
                poId, poNo, soId, soNo, "系统管理员",
                "审核通过：采购单 " + poNo + " + 销售单 " + soNo + " + 应付 " + apNo + " + 应收 " + arNo,
                realFlyId);

        Map<String, Object> out = new HashMap<>();
        out.put("flyId", realFlyId);
        out.put("status", "APPROVED");
        out.put("purchaseOrderNo", poNo);
        out.put("salesOrderNo", soNo);
        out.put("apNo", apNo);
        out.put("arNo", arNo);
        out.put("success", true);
        out.put("effect", "已生成采购订单(" + poNo + ") + 销售订单(" + soNo + ") + 应付(" + apNo + ") + 应收(" + arNo + ")，未经过仓库");
        return ApiResponse.ok(out);
    }

    // ====================== 反审核 ======================

    @PostMapping("/sales/fly-order/unaudit")
    @Transactional
    public ApiResponse<Map<String, Object>> unaudit(@RequestBody Map<String, Object> req) {
        String flyId = str(req.get("flyId"));
        if (flyId.isBlank()) flyId = str(req.get("flyNo"));
        if (flyId.isBlank()) return ApiResponse.fail("400", "缺少 flyId");

        List<Map<String, Object>> flyRows = jdbcTemplate.queryForList(
                "SELECT * FROM fly_order WHERE fly_id = ? OR fly_no = ?", flyId, flyId);
        if (flyRows.isEmpty()) return ApiResponse.fail("404", "飞单不存在");
        Map<String, Object> fly = flyRows.get(0);
        String realFlyId = str(pickCS(fly, "fly_id"));
        String status = str(pickCS(fly, "status"));
        if (!"APPROVED".equals(status)) return ApiResponse.fail("400", "只有已审核飞单可反审核");

        String poNo = str(pickCS(fly, "purchase_order_no"));
        String soNo = str(pickCS(fly, "sales_order_no"));

        // 检查是否已付款/收款
        if (!poNo.isBlank()) {
            List<Map<String, Object>> apRows = jdbcTemplate.queryForList(
                    "SELECT paid_amount FROM fin_ap WHERE source_bill = ?", poNo);
            for (Map<String, Object> ap : apRows) {
                if (toBd(pickCS(ap, "paid_amount")).signum() > 0) {
                    return ApiResponse.fail("400", "采购订单 " + poNo + " 已有付款记录，无法反审核");
                }
            }
        }
        if (!soNo.isBlank()) {
            List<Map<String, Object>> arRows = jdbcTemplate.queryForList(
                    "SELECT received_amount FROM fin_ar WHERE source_bill = ?", soNo);
            for (Map<String, Object> ar : arRows) {
                if (toBd(pickCS(ar, "received_amount")).signum() > 0) {
                    return ApiResponse.fail("400", "销售订单 " + soNo + " 已有收款记录，无法反审核");
                }
            }
        }

        // 删除关联单据
        if (!poNo.isBlank()) {
            // 删 fin_ap
            jdbcTemplate.update("DELETE FROM fin_ap WHERE source_bill = ?", poNo);
            // 删 pur_order_detail + pur_order
            jdbcTemplate.update("DELETE FROM pur_order_detail WHERE order_id IN (SELECT order_id FROM pur_order WHERE order_no = ?)", poNo);
            jdbcTemplate.update("DELETE FROM pur_order WHERE order_no = ?", poNo);
            // 删 purchase_order_detail + purchase_order
            jdbcTemplate.update("DELETE FROM purchase_order_detail WHERE order_id IN (SELECT order_id FROM purchase_order WHERE order_no = ?)", poNo);
            jdbcTemplate.update("DELETE FROM purchase_order WHERE order_no = ?", poNo);
        }
        if (!soNo.isBlank()) {
            // 删 fin_ar
            jdbcTemplate.update("DELETE FROM fin_ar WHERE source_bill = ?", soNo);
            // 删 sales_order_detail + sales_order
            jdbcTemplate.update("DELETE FROM sales_order_detail WHERE order_id IN (SELECT order_id FROM sales_order WHERE order_no = ?)", soNo);
            jdbcTemplate.update("DELETE FROM sales_order WHERE order_no = ?", soNo);
        }

        // 飞单回退到草稿
        jdbcTemplate.update("""
                UPDATE fly_order SET status = 'DRAFT', purchase_order_id = NULL, purchase_order_no = NULL,
                    sales_order_id = NULL, sales_order_no = NULL, audit_user = NULL, audit_time = NULL,
                    audit_info = NULL
                WHERE fly_id = ?
                """, realFlyId);

        return ApiResponse.ok(Map.of("flyId", realFlyId, "status", "DRAFT", "success", true,
                "effect", "已删除关联的采购订单、销售订单、应付和应收单据"));
    }

    // ====================== 作废 ======================

    @PostMapping("/sales/fly-order/cancel")
    @Transactional
    public ApiResponse<Map<String, Object>> cancel(@RequestBody Map<String, Object> req) {
        String flyId = str(req.get("flyId"));
        if (flyId.isBlank()) flyId = str(req.get("flyNo"));
        if (flyId.isBlank()) return ApiResponse.fail("400", "缺少 flyId");

        List<Map<String, Object>> flyRows = jdbcTemplate.queryForList(
                "SELECT fly_id, status FROM fly_order WHERE fly_id = ? OR fly_no = ?", flyId, flyId);
        if (flyRows.isEmpty()) return ApiResponse.fail("404", "飞单不存在");
        String status = str(pickCS(flyRows.get(0), "status"));
        if ("CANCELLED".equals(status)) return ApiResponse.fail("400", "飞单已作废");
        String realFlyId = str(pickCS(flyRows.get(0), "fly_id"));

        jdbcTemplate.update("UPDATE fly_order SET status = 'CANCELLED' WHERE fly_id = ?", realFlyId);
        return ApiResponse.ok(Map.of("flyId", realFlyId, "status", "CANCELLED", "success", true));
    }

    // ====================== 删除（仅草稿） ======================

    @PostMapping("/sales/fly-order/delete")
    @Transactional
    public ApiResponse<Map<String, Object>> delete(@RequestBody Map<String, Object> req) {
        String flyId = str(req.get("flyId"));
        if (flyId.isBlank()) flyId = str(req.get("flyNo"));
        if (flyId.isBlank()) return ApiResponse.fail("400", "缺少 flyId");

        List<Map<String, Object>> flyRows = jdbcTemplate.queryForList(
                "SELECT fly_id, status FROM fly_order WHERE fly_id = ? OR fly_no = ?", flyId, flyId);
        if (flyRows.isEmpty()) return ApiResponse.fail("404", "飞单不存在");
        String status = str(pickCS(flyRows.get(0), "status"));
        if (!"DRAFT".equals(status)) return ApiResponse.fail("400", "只有待审核飞单可删除");
        String realFlyId = str(pickCS(flyRows.get(0), "fly_id"));

        jdbcTemplate.update("DELETE FROM fly_order_detail WHERE fly_id = ?", realFlyId);
        jdbcTemplate.update("DELETE FROM fly_order WHERE fly_id = ?", realFlyId);
        return ApiResponse.ok(Map.of("flyId", realFlyId, "success", true));
    }

    // ====================== 批量审核 ======================

    @PostMapping("/sales/fly-order/batch-audit")
    @Transactional
    public ApiResponse<Map<String, Object>> batchAudit(@RequestBody Map<String, Object> req) {
        @SuppressWarnings("unchecked")
        List<String> ids = req.get("ids") instanceof List<?> l ? (List<String>) l : Collections.emptyList();
        if (ids.isEmpty()) return ApiResponse.fail("400", "请选择要审核的飞单");
        int ok = 0, fail = 0;
        for (String id : ids) {
            try {
                Map<String, Object> r = audit(Map.of("flyId", id)).data();
                if (r != null && Boolean.TRUE.equals(r.get("success"))) ok++; else fail++;
            } catch (Exception e) { fail++; }
        }
        return ApiResponse.ok(Map.of("success", true, "ok", ok, "fail", fail, "message", "成功审核 " + ok + " 张" + (fail > 0 ? "，失败 " + fail + " 张" : "")));
    }

    // ====================== 批量取消审核 ======================

    @PostMapping("/sales/fly-order/batch-unaudit")
    @Transactional
    public ApiResponse<Map<String, Object>> batchUnaudit(@RequestBody Map<String, Object> req) {
        @SuppressWarnings("unchecked")
        List<String> ids = req.get("ids") instanceof List<?> l ? (List<String>) l : Collections.emptyList();
        if (ids.isEmpty()) return ApiResponse.fail("400", "请选择要取消审核的飞单");
        int ok = 0, fail = 0;
        for (String id : ids) {
            try {
                Map<String, Object> r = unaudit(Map.of("flyId", id)).data();
                if (r != null && Boolean.TRUE.equals(r.get("success"))) ok++; else fail++;
            } catch (Exception e) { fail++; }
        }
        return ApiResponse.ok(Map.of("success", true, "ok", ok, "fail", fail, "message", "成功取消审核 " + ok + " 张" + (fail > 0 ? "，失败 " + fail + " 张" : "")));
    }

    // ====================== 批量删除 ======================

    @PostMapping("/sales/fly-order/batch-delete")
    @Transactional
    public ApiResponse<Map<String, Object>> batchDelete(@RequestBody Map<String, Object> req) {
        @SuppressWarnings("unchecked")
        List<String> ids = req.get("ids") instanceof List<?> l ? (List<String>) l : Collections.emptyList();
        if (ids.isEmpty()) return ApiResponse.fail("400", "请选择要删除的飞单");
        int ok = 0, fail = 0;
        for (String id : ids) {
            try {
                Map<String, Object> r = delete(Map.of("flyId", id)).data();
                if (r != null && Boolean.TRUE.equals(r.get("success"))) ok++; else fail++;
            } catch (Exception e) { fail++; }
        }
        return ApiResponse.ok(Map.of("success", true, "ok", ok, "fail", fail, "message", "成功删除 " + ok + " 张" + (fail > 0 ? "，失败 " + fail + " 张" : "")));
    }

    // ====================== 导出 ======================

    @PostMapping("/sales/fly-order/export")
    public ApiResponse<List<Map<String, Object>>> exportAll(@RequestBody Map<String, Object> req) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT fly_no AS 飞单号, supplier_name AS 供应商, customer_name AS 客户, salesman AS 业务员,
                       bill_date AS 单据日期, purchase_amount AS 采购金额, sales_amount AS 销售金额,
                       profit_amount AS 毛利, status AS 状态, statusText AS 状态文本,
                       purchase_order_no AS 关联采购单, sales_order_no AS 关联销售单,
                       remark AS 备注, creator_name AS 制单人, create_time AS 制单时间,
                       audit_user AS 审核人, audit_time AS 审核时间
                FROM fly_order ORDER BY create_time DESC
                """);
        // 给状态加中文显示
        for (Map<String, Object> r : rows) {
            String st = String.valueOf(r.getOrDefault("STATUS", ""));
            r.put("状态", switch (st) {
                case "DRAFT" -> "待审核"; case "APPROVED" -> "已审核"; case "CANCELLED" -> "已作废"; default -> st;
            });
        }
        return ApiResponse.ok(rows);
    }

    @GetMapping("/sales/fly-order/export-detail")
    public ApiResponse<List<Map<String, Object>>> exportDetail(@RequestParam String flyId) {
        List<Map<String, Object>> heads = jdbcTemplate.queryForList(
                "SELECT fly_no FROM fly_order WHERE fly_id = ? OR fly_no = ?", flyId, flyId);
        String flyNo = heads.isEmpty() ? flyId : String.valueOf(heads.get(0).get("fly_no"));
        List<Map<String, Object>> details = jdbcTemplate.queryForList("""
                SELECT d.goods_code AS 商品编码, d.goods_name AS 商品名称, d.spec AS 规格,
                       d.unit_name AS 单位, d.qty AS 数量, d.purchase_price AS 采购价,
                       d.sales_price AS 销售价, d.purchase_amount AS 采购金额,
                       d.sales_amount AS 销售金额, d.tax_rate AS 税率, d.remark AS 备注
                FROM fly_order_detail d WHERE d.fly_id = ? ORDER BY d.detail_id
                """, flyId);
        // 在前面插入飞单号标识行
        Map<String, Object> headerRow = new LinkedHashMap<>();
        headerRow.put("商品编码", "飞单号: " + flyNo);
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(headerRow);
        result.addAll(details);
        return ApiResponse.ok(result);
    }

    // ====================== 工具方法 ======================

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static Object pickCS(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        return row.get(key.toUpperCase(Locale.ROOT));
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(String.valueOf(o)); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static int toInt(Object o, int dft) {
        if (o == null) return dft;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return dft; }
    }

    private static LocalDate parseDate(Object o) {
        if (o == null) return LocalDate.now();
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return LocalDate.now(); }
    }

    private static Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char c : k.toCharArray()) {
                if (c == '_') { upper = true; continue; }
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
            out.put(sb.toString(), e.getValue());
        }
        return out;
    }

    /** 解析 unit_config JSON，返回单位列表。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseUnitConfig(String json) {
        if (json == null || json.isBlank() || !json.trim().startsWith("[")) return Collections.emptyList();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
