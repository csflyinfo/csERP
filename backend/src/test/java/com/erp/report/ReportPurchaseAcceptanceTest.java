package com.erp.report;

import com.erp.common.api.PageResult;
import com.erp.common.security.CurrentUser;
import com.erp.common.security.FieldMasker;
import com.erp.common.security.PermissionService;
import com.erp.common.security.datascope.DataScopeService;
import com.erp.report.common.ReportDateRange;
import com.erp.report.common.ReportGuard;
import com.erp.report.dws.PurchaseDwsService;
import com.erp.report.dws.ReportDimGoodsService;
import com.erp.report.meta.ReportDefinition;
import com.erp.report.meta.ReportQueryEngine;
import com.erp.report.meta.ReportRegistry;
import com.erp.report.purchase.PurchaseForecastService;
import com.erp.report.purchase.PurchaseGoodsSummaryDefinition;
import com.erp.report.purchase.PurchaseMoveDetailDefinition;
import com.erp.report.purchase.PurchaseOrderDetailDefinition;
import com.erp.report.purchase.PurchaseSupplierSummaryDefinition;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表中心一期·采购域口径验收（对账）测试。
 *
 * <p>不启动 Spring 容器：对内存 H2 跑<b>真实的全量 Flyway 迁移</b>（与生产同一份 V*.sql），
 * 灌入固定场景 sql/report-purchase-seed.sql，手工接线报表引擎/定义/DWS 服务。
 * 无登录主体时 {@link DataScopeService} 与 {@link FieldMasker} 均短路（等同超管，
 * 不加数据范围、不脱敏），正好用于锁定「口径本身」；RBAC 收窄/脱敏由接口冒烟覆盖。
 *
 * <p>锁定的跨报表不变量（一条链路四张报表 + DWS + 预测，数字必须互相严丝合缝）：
 * <pre>
 *   入库：350 瓶/袋/斤（水240+面60+瓜子50），1160 元
 *   退货：-24 瓶，-54.24 元（含税）
 *   净额：326 / 1105.76
 *   报表1 已入库 == 报表2 采购入库发生额；报表3/4 叶子合计 == DWS 净额 == 报表2 净额
 *   预测（2026-08-10~09-11，预销20天）：水期间销150、日均4.5455、可用20、在途12 → 建议59瓶/2.4583箱
 * </pre>
 */
class ReportPurchaseAcceptanceTest {

    private static final String SEP_START = "2026-09-01";
    private static final String SEP_END = "2026-09-30";

    private static ReportRegistry registry;
    private static ReportQueryEngine engine;
    private static PurchaseDwsService dws;
    private static PurchaseForecastService forecast;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setUrl("jdbc:h2:mem:rpt-accept;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        // 与应用启动完全一致的迁移路径（114 个迁移脚本到 V116，含 V110 报表中心主体）
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();

        try (java.sql.Connection c = ds.getConnection()) {
            ScriptUtils.executeSqlScript(c,
                    new EncodedResource(new ClassPathResource("sql/report-purchase-seed.sql"),
                            StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("验收造数失败", e);
        }

        JdbcTemplate primary = new JdbcTemplate(ds);
        jdbc = primary;
        JdbcTemplate reportJdbc = new JdbcTemplate(ds);
        reportJdbc.setQueryTimeout(30);

        // 维度快照（件数/箱价依赖大单位换算率）+ 采购域 DWS 重算
        assertEquals(3, new ReportDimGoodsService(primary).refreshAll(), "商品维度快照应为 3 个商品");
        dws = new PurchaseDwsService(primary);
        assertEquals(5, dws.refreshRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
                "DWS 分区应为 5 个粒度行（9/6 三行 + 9/8 一行 + 9/9 退货一行）");

        DataScopeService dataScope = new DataScopeService(primary);
        ReportGuard guard = new ReportGuard(primary, dataScope, new PermissionService(primary));
        // registry/permissionService 传 null：无 ThreadLocal 用户时 mask 直接短路，不会触达
        FieldMasker masker = new FieldMasker(null, null);
        engine = new ReportQueryEngine(reportJdbc, guard, masker);
        List<ReportDefinition> defs = List.of(
                new PurchaseOrderDetailDefinition(dataScope, reportJdbc),
                new PurchaseMoveDetailDefinition(dataScope),
                new PurchaseGoodsSummaryDefinition(dataScope),
                new PurchaseSupplierSummaryDefinition(dataScope));
        registry = new ReportRegistry(defs);
        // 仅验证预测查询口径：BillNoGenerator/OperationLogService 只在生成订单时使用，查询路径不触达
        forecast = new PurchaseForecastService(reportJdbc, primary, dataScope, guard, null, null);
    }

    // ============================ DWS 对账 ============================

    @Test
    @DisplayName("DWS 日汇总与 DWD 视图同期发生额完全一致：326 基本单位 / 1105.76 元")
    void dwsReconcileBalanced() {
        Map<String, Object> r = dws.reconcile(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertTrue((Boolean) r.get("balanced"), () -> "对账不平：" + r);
        assertNum(326.0, r.get("dwsQty"));
        assertNum(326.0, r.get("liveQty"));
        assertNum(0.0, r.get("diffQty"));
        assertNum(1105.76, r.get("dwsAmount"));
        assertNum(1105.76, r.get("liveAmount"));
        assertNum(0.0, r.get("diffAmount"));
    }

    // ============================ 报表1 采购订单明细 ============================

    @Test
    @DisplayName("报表1：下单362/已入库350/未入库12，订单金额1184，4个订单行")
    void report1OrderExecution() {
        Map<String, Object> body1 = rangeBody(SEP_START, SEP_END);
        PageResult<Map<String, Object>> page = page("purchase_order_detail", body1);
        assertEquals(4, page.total());
        // 明细页合计懒加载：合计由 /summary 独立返回，不再内嵌于分页响应
        Map<String, Object> s = sum("purchase_order_detail", body1);
        assertNum(4.0, s.get("lineCount"));
        assertNum(362.0, s.get("baseQty"));          // 水240+面60+瓜子50+在途12
        assertNum(350.0, s.get("receivedBase"));
        assertNum(12.0, s.get("unreceivedBase"));
        assertNum(1184.00, s.get("amount"));        // PO1 1160 + PO2 24

        Map<String, Object> po1Water = row(page, "orderNo", "CGDD20260905001", "goodsCode", "G001");
        assertNum(240, po1Water.get("baseQty"));
        assertNum(240, po1Water.get("receivedBase"));
        assertNum(10, po1Water.get("packageQty"));           // 240/24
        assertNum(0, po1Water.get("unreceivedBase"));

        Map<String, Object> po2Water = row(page, "orderNo", "CGDD20260910001", "goodsCode", "G001");
        assertNum(12, po2Water.get("baseQty"));
        assertNum(0, po2Water.get("receivedBase"));
        assertNum(12, po2Water.get("unreceivedBase"));
        assertNum(0.5, po2Water.get("packageQty"));          // 0.5 箱在途

        // K4：无大单位商品件数=基本数量（瓜子 50 斤）
        Map<String, Object> seeds = row(page, "orderNo", "CGDD20260905001", "goodsCode", "G003");
        assertNum(50, seeds.get("baseQty"));
        assertNum(50, seeds.get("packageQty"));
    }

    // ============================ 报表2 采购明细 + K3/K4/K5/K6 ============================

    @Test
    @DisplayName("报表2：入库350/1160、退货-24/-54.24、净326/1105.76（含税 K3、退货负数 K6）")
    void report2InboundReturnNet() {
        Map<String, Object> body2 = rangeBody(SEP_START, SEP_END);
        PageResult<Map<String, Object>> page = page("purchase_move_detail", body2);
        assertEquals(5, page.total());
        Map<String, Object> s = sum("purchase_move_detail", body2);
        assertNum(350.0, s.get("inboundQtyBase"));
        assertNum(1160.0, s.get("inboundAmount"));
        assertNum(-24.0, s.get("returnQtyBase"));
        assertNum(-54.24, s.get("returnAmount"));
        assertNum(326.0, s.get("baseQty"));
        assertNum(1105.76, s.get("amount"));

        // K5：退货行数量/金额同为负，单价=金额÷数量必须为正（红冲行负数量正单价）
        Map<String, Object> ret = row(page, "billNo", "CGTH20260909001");
        assertNum(-24, ret.get("baseQty"));
        assertNum(-1, ret.get("packageQty"));
        assertNum(-54.24, ret.get("amount"));
        assertNum(2.26, ret.get("unitPriceBase"));          // 54.24/24
        assertNum(54.24, ret.get("boxPrice"));             // 54.24/1箱

        // K4：无大单位的瓜子，入库行件数=基本数量 50；水按 24 换算，8 箱=192 瓶
        Map<String, Object> seeds = row(page, "billNo", "CGRK20260906001", "goodsCode", "G003");
        assertNum(50, seeds.get("baseQty"));
        assertNum(50, seeds.get("packageQty"));
        Map<String, Object> water1 = row(page, "billNo", "CGRK20260906001", "goodsCode", "G001");
        assertNum(192, water1.get("baseQty"));
        assertNum(8, water1.get("packageQty"));
    }

    @Test
    @DisplayName("报表2单据类型筛选：只查采购退货仅 1 行 -54.24")
    void report2FilterReturnType() {
        Map<String, Object> body = rangeBody(SEP_START, SEP_END);
        body.put("filters", Map.of("billType", "采购退货"));
        PageResult<Map<String, Object>> page = page("purchase_move_detail", body);
        assertEquals(1, page.total());
        assertNum(-54.24, sum("purchase_move_detail", body).get("amount"));
    }

    // ============================ 跨报表对账（验收核心） ============================

    @Test
    @DisplayName("对账不变量：报表1已入库量 == 报表2入库发生量（350）")
    void reconcileOrderReceivedVsMoveInbound() {
        Map<String, Object> recBody = rangeBody(SEP_START, SEP_END);
        Map<String, Object> s1 = sum("purchase_order_detail", recBody);
        Map<String, Object> s2 = sum("purchase_move_detail", recBody);
        assertEquals(n(s1.get("receivedBase")), n(s2.get("inboundQtyBase")), 0.0001);
        assertNum(350.0, s2.get("inboundQtyBase"));
    }

    @Test
    @DisplayName("对账不变量：报表3（按商品）叶子行合计 = 报表2净额 = 326/1105.76")
    void reconcileGoodsSummaryVsDetailView() {
        Map<String, Object> body = rangeBody(SEP_START, SEP_END);
        body.put("groupBy", List.of("goods"));
        PageResult<Map<String, Object>> page = page("purchase_goods_summary", body);
        assertEquals(3, page.total(), "水/面/瓜子三个商品叶子行");

        double leafNetQty = sum(page.records(), "netQtyBase");
        double leafNetAmount = sum(page.records(), "netAmount");
        assertNum(326.0, leafNetQty);
        assertNum(1105.76, leafNetAmount);
        // 引擎总计与叶子行实时汇总一致（导出/页面同口径）
        assertNum(leafNetQty, page.summary().get("netQtyBase"));
        assertNum(leafNetAmount, page.summary().get("netAmount"));

        // 水：入库 240/480，退货发生 24/54.24（汇总表退货列展示正数发生额），净 216/425.76
        Map<String, Object> water = row(page, "goodsCode", "G001");
        assertNum(240, water.get("inboundQtyBase"));
        assertNum(480, water.get("inboundAmount"));
        assertNum(24, water.get("returnQtyBase"));
        assertNum(54.24, water.get("returnAmount"));
        assertNum(216, water.get("netQtyBase"));
        assertNum(425.76, water.get("netAmount"));
        // 面 60/180（随华联订单采购，供应商取单据上的，不取商品默认供应商）
        Map<String, Object> noodle = row(page, "goodsCode", "G002");
        assertNum(60, noodle.get("netQtyBase"));
        assertNum(180, noodle.get("netAmount"));
    }

    @Test
    @DisplayName("对账不变量：报表4（供应商+商品+采购员）叶子合计 = DWS 净额；面挂在单据供应商华联名下")
    void reconcileSupplierSummaryVsDws() {
        PageResult<Map<String, Object>> page = page("purchase_supplier_summary", rangeBody(SEP_START, SEP_END));
        assertEquals(3, page.total());
        assertNum(326.0, sum(page.records(), "netQtyBase"));
        assertNum(1105.76, sum(page.records(), "netAmount"));
        assertNum(326.0, page.summary().get("netQtyBase"));
        assertNum(1105.76, page.summary().get("netAmount"));

        Map<String, Object> noodle = row(page, "goodsCode", "G002");
        assertEquals("GYS001", noodle.get("supplierCode"), "面的入库单供应商是华联，与商品默认中粮无关");
        assertNum(60, noodle.get("netQtyBase"));
    }

    // ============================ 报表5 采购预测 ============================

    @Test
    @DisplayName("报表5：水期间净销150（含8/20签收与9/9退货）、日均4.5455、在途12，预销20天建议59瓶/2.4583箱")
    void forecastWaterSuggestion() {
        List<Map<String, Object>> rows = forecast.forecast(forecastBody("2026-08-10", "2026-09-11", 20, true, false));
        Map<String, Object> water = find(rows, "goodsCode", "G001");
        assertNotNull(water);
        assertNum(150, water.get("salesQtyPeriod"));        // 100(8/20) + 60(9/10) - 10(9/9退)
        assertNum(50, water.get("salesQty7d"));            // 9/5~9/11：仅 9/10 的 60 - 9/9 的 10 = 50
        assertNum(4.5455, water.get("avgDailySales"));     // 150/33 天
        assertNum(20, water.get("availableBase"));
        assertNum(12, water.get("onWayQty"));              // PO2 的 12 瓶
        assertNum(59, water.get("suggestBase"));           // round(4.5455*20-20-12)=59
        assertNum(2.4583, water.get("suggestPackage"));    // 59/24
        assertNum(2.0, water.get("latestPrice"));          // 最近入库小单位含税价
    }

    @Test
    @DisplayName("报表5：无销量商品低于库存下限时按下限建议（面缺20），无下限的散装瓜子不出现")
    void forecastUnsoldGoods() {
        List<Map<String, Object>> rows = forecast.forecast(forecastBody("2026-08-10", "2026-09-11", 20, true, false));
        Map<String, Object> noodle = find(rows, "goodsCode", "G002");
        assertNotNull(noodle, "showUnsold=true 时无销量但低于下限的面应出现");
        assertNum(0, noodle.get("salesQtyPeriod"));
        assertNum(10, noodle.get("availableBase"));
        assertNum(20, noodle.get("suggestBase"));          // 下限30 - 可用10
        assertFalse(rows.stream().anyMatch(r -> "G003".equals(r.get("goodsCode"))),
                "无销量且无库存下限缺口的瓜子不应出现");

        // showZeroSuggestion=true 时零建议商品也出现
        List<Map<String, Object>> rowsAll = forecast.forecast(forecastBody("2026-08-10", "2026-09-11", 20, true, true));
        assertNotNull(find(rowsAll, "goodsCode", "G003"));
    }

    // ============================ 护栏 ============================

    @Test
    @DisplayName("护栏：明细报表跨度>366天拒绝并引导异步导出")
    void guardDetailSpan() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.page(registry.require("purchase_move_detail"),
                        rangeBody("2024-01-01", "2026-09-12")));
        assertTrue(ex.getMessage().contains("1 年"), ex.getMessage());
    }

    @Test
    @DisplayName("护栏：汇总报表跨度>731天拒绝")
    void guardSummarySpan() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.page(registry.require("purchase_goods_summary"),
                        rangeBody("2023-01-01", "2026-09-12")));
        assertTrue(ex.getMessage().contains("2 年"), ex.getMessage());
    }

    @Test
    @DisplayName("护栏：分组最多3级；报表4必须含供应商/供应商分类维度")
    void guardGroupLevelsAndSupplierRequired() {
        Map<String, Object> four = rangeBody(SEP_START, SEP_END);
        four.put("groupBy", List.of("goods", "buyer", "brand", "category"));
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class,
                () -> engine.page(registry.require("purchase_goods_summary"), four));
        assertTrue(ex1.getMessage().contains("3 级"), ex1.getMessage());

        Map<String, Object> noSupplier = rangeBody(SEP_START, SEP_END);
        noSupplier.put("groupBy", List.of("goods"));
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
                () -> engine.page(registry.require("purchase_supplier_summary"), noSupplier));
        assertTrue(ex2.getMessage().contains("供应商"), ex2.getMessage());
    }

    @Test
    @DisplayName("护栏：预销天数仅允许 1~90")
    void guardPreSaleDays() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> forecast.forecast(forecastBody("2026-08-10", "2026-09-11", 91, false, false)));
        assertTrue(ex.getMessage().contains("预销天数"), ex.getMessage());
    }

    @Test
    @DisplayName("护栏：深分页 offset>10万拒绝")
    void guardDeepPaging() {
        Map<String, Object> body = rangeBody(SEP_START, SEP_END);
        body.put("pageNo", 1000);
        body.put("pageSize", 1000);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.page(registry.require("purchase_move_detail"), body));
        assertTrue(ex.getMessage().contains("10 万"), ex.getMessage());
    }

    // ============================ 结果缓存：可见性隔离 / 跨账号共享 ============================

    @Test
    @DisplayName("结果缓存按可见性签名共享：字段权限不同不共享、同角色跨账号共享、超管共享")
    void cacheSharedByVisibilitySignatureNotAccount() {
        // 两个角色：数据范围同为 SUPPLIER ALL（范围签名相同），A 有采购金额字段权、B 无（字段签名不同）
        jdbc.update("MERGE INTO sys_role_runtime (role_id, role_code, role_name, status) KEY (role_id) "
                + "VALUES ('R_CACHE_A','RCACHEA','缓存测试角色A','NORMAL')");
        jdbc.update("MERGE INTO sys_role_runtime (role_id, role_code, role_name, status) KEY (role_id) "
                + "VALUES ('R_CACHE_B','RCACHEB','缓存测试角色B','NORMAL')");
        for (String roleId : List.of("R_CACHE_A", "R_CACHE_B")) {
            jdbc.update("MERGE INTO sys_role_data_scope (id, role_id, scope_type, scope_value) KEY (id) "
                    + "VALUES (CONCAT('DS_', ?), ?, 'SUPPLIER', 'ALL')", roleId, roleId);
        }
        jdbc.update("MERGE INTO sys_role_field_rel (id, role_id, field_id) KEY (id) "
                + "VALUES ('FF_R_CACHE_A','R_CACHE_A','F_PUR_AMOUNT')");
        jdbc.update("MERGE INTO sys_user_role_rel (id, user_id, role_id) KEY (id) VALUES "
                + "('UR_CA','U_CA','R_CACHE_A'),('UR_CB','U_CB','R_CACHE_B'),('UR_CC','U_CC','R_CACHE_A')");
        try {
            ReportGuard localGuard = new ReportGuard(jdbc, new DataScopeService(jdbc),
                    new PermissionService(jdbc));
            Map<String, Object> body = Map.of("dateRange",
                    Map.of("startDate", SEP_START, "endDate", SEP_END), "k", "cache-visibility");
            int[] runs = {0};

            CurrentUser.set(cacheUser("U_CA", "RCACHEA"));
            assertEquals("RESULT-U_CA-1", localGuard.run("rpt.cache_visibility", body, true,
                    () -> "RESULT-" + CurrentUser.get().userId() + "-" + (++runs[0])));
            assertEquals(1, runs[0], "首次查询应执行 loader");

            // B 字段授权不同：缓存键必须不同，绝不能拿到 A 视角（含金额）的结果
            CurrentUser.set(cacheUser("U_CB", "RCACHEB"));
            assertEquals("RESULT-U_CB-2", localGuard.run("rpt.cache_visibility", body, true,
                    () -> "RESULT-" + CurrentUser.get().userId() + "-" + (++runs[0])));
            assertEquals(2, runs[0], "字段权限不同的账号必须各查一次，禁止共享缓存");

            // C 与 A 同角色（范围+字段签名一致）：直接共享 A 的结果，不再落库统计
            CurrentUser.set(cacheUser("U_CC", "RCACHEA"));
            assertEquals("RESULT-U_CA-1", localGuard.run("rpt.cache_visibility", body, true,
                    () -> "RESULT-" + CurrentUser.get().userId() + "-" + (++runs[0])));
            assertEquals(2, runs[0], "同角色同范围的不同账号应共享同一缓存项");

            // 超管视角固定签名，两个超管账号共享
            CurrentUser.set(CurrentUser.of("U_S1", "s1", "超管一", null,
                    Set.of("SYS_ADMIN"), "SYS_ADMIN", "ERP", null));
            assertEquals("RESULT-U_S1-3", localGuard.run("rpt.cache_visibility", body, true,
                    () -> "RESULT-" + CurrentUser.get().userId() + "-" + (++runs[0])));
            CurrentUser.set(CurrentUser.of("U_S2", "s2", "超管二", null,
                    Set.of("SYS_ADMIN"), "SYS_ADMIN", "ERP", null));
            assertEquals("RESULT-U_S1-3", localGuard.run("rpt.cache_visibility", body, true,
                    () -> "RESULT-" + CurrentUser.get().userId() + "-" + (++runs[0])));
            assertEquals(3, runs[0], "超管之间应共享缓存");
        } finally {
            CurrentUser.clear();
            jdbc.update("DELETE FROM sys_user_role_rel WHERE user_id IN ('U_CA','U_CB','U_CC')");
            jdbc.update("DELETE FROM sys_role_field_rel WHERE role_id IN ('R_CACHE_A','R_CACHE_B')");
            jdbc.update("DELETE FROM sys_role_data_scope WHERE role_id IN ('R_CACHE_A','R_CACHE_B')");
            jdbc.update("DELETE FROM sys_role_runtime WHERE role_id IN ('R_CACHE_A','R_CACHE_B')");
        }
    }

    private static CurrentUser.Principal cacheUser(String userId, String roleCode) {
        return CurrentUser.of(userId, userId.toLowerCase(), "缓存测试" + userId, null,
                Set.of(roleCode), roleCode, "ERP", null);
    }

    @Test
    @DisplayName("单飞合并：同键并发只落库一次、跟随者共享结果；异常不缓存，可重试")
    void singleFlightCoalescesConcurrentIdenticalQueries() throws Exception {
        ReportGuard localGuard = new ReportGuard(jdbc, new DataScopeService(jdbc),
                new PermissionService(jdbc));
        CurrentUser.set(CurrentUser.of("U_SF", "sf", "单飞", null,
                Set.of("SYS_ADMIN"), "SYS_ADMIN", "ERP", null));
        Map<String, Object> body = Map.of("dateRange",
                Map.of("startDate", SEP_START, "endDate", SEP_END), "k", "single-flight");
        try {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger runs = new AtomicInteger();
            java.util.function.Supplier<String> loader = () -> {
                runs.incrementAndGet();
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "测试闸门应被释放");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return "ONLY-ONE";
            };
            CurrentUser.Principal sf = CurrentUser.get();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // CurrentUser 是 ThreadLocal：工作线程必须各自携带同一主体，生产请求线程同理
                Future<String> f1 = pool.submit(() -> {
                    CurrentUser.set(sf);
                    try { return localGuard.run("rpt.sf", body, true, loader); }
                    finally { CurrentUser.clear(); }
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS), "首个查询应进入 loader");
                Future<String> f2 = pool.submit(() -> {
                    CurrentUser.set(sf);
                    try { return localGuard.run("rpt.sf", body, true, loader); }
                    finally { CurrentUser.clear(); }
                });
                Thread.sleep(300);
                assertEquals(1, runs.get(), "同键并发必须单飞，第二个请求不得落库重查");
                release.countDown();
                assertEquals("ONLY-ONE", f1.get(5, TimeUnit.SECONDS));
                assertEquals("ONLY-ONE", f2.get(5, TimeUnit.SECONDS), "跟随者应共享领导者结果");
            } finally {
                pool.shutdownNow();
            }
            // 领导者完成后同键请求走结果缓存，不再执行 loader
            assertEquals("ONLY-ONE", localGuard.run("rpt.sf", body, true,
                    () -> { throw new IllegalStateException("命中缓存时不应再次执行 loader"); }));

            // 异常（护栏拦截/超时）不写缓存：同键重试必须重新执行
            Map<String, Object> boomBody = Map.of("dateRange",
                    Map.of("startDate", SEP_START, "endDate", SEP_END), "k", "boom");
            AtomicInteger boomRuns = new AtomicInteger();
            java.util.function.Supplier<String> boom = () -> {
                boomRuns.incrementAndGet();
                throw new IllegalArgumentException("汇总结果超过 10 万行，请增加分组层级或缩小日期范围");
            };
            for (int i = 0; i < 2; i++) {
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                        () -> localGuard.run("rpt.boom", boomBody, true, boom));
                assertTrue(ex.getMessage().contains("10 万"), ex.getMessage());
            }
            assertEquals(2, boomRuns.get(), "失败结果不得缓存，重试必须重新落库执行");
        } finally {
            CurrentUser.clear();
        }
    }

    // ============================ K2 默认期间口径 ============================

    @Test
    @DisplayName("K2：只给截止日时起始=截止上月同日的前一天（截止2026-09-10 → 起2026-08-09）")
    void defaultPeriodCaliber() {
        ReportDateRange r = ReportDateRange.from(Map.of("dateRange", Map.of("endDate", "2026-09-10")));
        assertEquals(LocalDate.of(2026, 8, 9), r.startDate());
        assertEquals(LocalDate.of(2026, 9, 10), r.endDate());
        // 完全不传期间：截止=昨天
        ReportDateRange def = ReportDateRange.from(Map.of());
        assertEquals(LocalDate.now().minusDays(1), def.endDate());
        assertEquals(def.endDate().minusMonths(1).minusDays(1), def.startDate());
    }

    // ============================ 工具 ============================

    private PageResult<Map<String, Object>> page(String code, Map<String, Object> body) {
        return engine.page(registry.require(code), body);
    }

    /** 明细报表合计走独立 /summary（分页响应不再内嵌合计）。 */
    private Map<String, Object> sum(String code, Map<String, Object> body) {
        return engine.summary(registry.require(code), body);
    }

    private static Map<String, Object> rangeBody(String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dateRange", Map.of("startDate", start, "endDate", end));
        body.put("pageNo", 1);
        body.put("pageSize", 1000);
        return body;
    }

    private static Map<String, Object> forecastBody(String start, String end, int preSaleDays,
                                                    boolean showUnsold, boolean showZero) {
        Map<String, Object> body = rangeBody(start, end);
        body.put("preSaleDays", preSaleDays);
        body.put("showUnsold", showUnsold);
        body.put("showZeroSuggestion", showZero);
        return body;
    }

    private static Map<String, Object> row(PageResult<Map<String, Object>> page, String... kv) {
        Map<String, Object> found = find(page.records(), kv);
        assertNotNull(found, () -> "未找到行：" + List.of(kv) + "，实际：" + page.records());
        return found;
    }

    private static Map<String, Object> find(List<Map<String, Object>> rows, String... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("kv 必须成对");
        for (Map<String, Object> row : rows) {
            boolean all = true;
            for (int i = 0; i < kv.length; i += 2) {
                if (!String.valueOf(kv[i + 1]).equals(String.valueOf(row.get(kv[i])))) {
                    all = false;
                    break;
                }
            }
            if (all) return row;
        }
        return null;
    }

    private static double sum(List<Map<String, Object>> rows, String key) {
        double v = 0;
        for (Map<String, Object> r : rows) v += n(r.get(key));
        return round(v);
    }

    private static double n(Object v) {
        return v == null ? 0d : ((Number) v).doubleValue();
    }

    /** 金额/数量统一保留 4 位以内比较，规避 H2 二进制浮点尾巴。 */
    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(4, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static void assertNum(double expected, Object actual) {
        assertNotNull(actual, "数值不应为 null");
        assertEquals(round(expected), round(((Number) actual).doubleValue()), 0.0001,
                () -> "期望 " + expected + " 实际 " + actual);
    }
}
