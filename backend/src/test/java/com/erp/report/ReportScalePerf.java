package com.erp.report;

import com.erp.common.api.PageResult;
import com.erp.common.security.CurrentUser;
import com.erp.common.security.FieldMasker;
import com.erp.common.security.PermissionService;
import com.erp.common.security.datascope.DataScopeService;
import com.erp.report.common.ReportGuard;
import com.erp.report.dws.PurchaseDwsService;
import com.erp.report.dws.ReportDimGoodsService;
import com.erp.report.meta.ReportDefinition;
import com.erp.report.meta.ReportQueryEngine;
import com.erp.report.meta.ReportRegistry;
import com.erp.report.purchase.PurchaseGoodsSummaryDefinition;
import com.erp.report.purchase.PurchaseMoveDetailDefinition;
import com.erp.report.purchase.PurchaseOrderDetailDefinition;
import com.erp.report.purchase.PurchaseSupplierSummaryDefinition;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 报表中心一期·规模化性能验收驱动（手工运行，不进 surefire：类名不带 Test 后缀）。
 *
 * <p>验证目标（用户给定容量）：PC 后台 50+ 用户、日采购订单 2,000 张、商品行 10 万行；
 * 多人并发查报表不能拖慢 OLTP。做法：
 * <ol>
 *   <li>文件 H2 跑<b>与生产同一份</b>全量 Flyway 迁移（MODE=MySQL）；</li>
 *   <li>按 stated scale 造数（默认 10 天 × 2,000 单 × 50 行 = 100 万入库行 + 100 万订单行 + 5 万退货行）；</li>
 *   <li>接线<b>真实的</b> ReportQueryEngine / 定义 / ReportGuard / DWS 服务（非自写 SQL）；</li>
 *   <li>单用户冷查询 p50/p95、50 用户并发（全局 15 凭证信号量）、OLTP 写入受载前后对比。</li>
 * </ol>
 *
 * <p>运行：
 * <pre>
 * mvn -o test-compile
 * mvn -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt -q
 * $JAVA_HOME/bin/java -cp target/test-classes:target/classes:$(cat target/cp.txt) com.erp.report.ReportScalePerf
 * # 可选参数（-D）：perf.days=10 perf.ordersPerDay=2000 perf.linesPerOrder=50
 * #                 perf.returnsPerDay=100 perf.users=50 perf.iters=3 perf.rounds=5
 * </pre>
 * 生产 MySQL 上同流程见 docs/优化记录-报表中心一期.md「性能验收」一节。
 */
public class ReportScalePerf {

    static final int DAYS = Integer.getInteger("perf.days", 10);
    static final int ORDERS_PER_DAY = Integer.getInteger("perf.ordersPerDay", 2000);
    static final int LINES_PER_ORDER = Integer.getInteger("perf.linesPerOrder", 50);
    static final int RETURNS_PER_DAY = Integer.getInteger("perf.returnsPerDay", 100);
    static final int USERS = Integer.getInteger("perf.users", 50);
    static final int ITERS = Integer.getInteger("perf.iters", 3);
    static final int ROUNDS = Integer.getInteger("perf.rounds", 5);

    static final int GOODS = 1000;
    static final int SUPPLIERS = 50;
    static final String WAREHOUSE = "总仓";

    static final LocalDate START = LocalDate.of(2026, 8, 1);
    static LocalDate endDate() { return START.plusDays(DAYS - 1L); }

    static final AtomicLong USER_SEQ = new AtomicLong();

    /**
     * 冷查询 nonce：阶段一每轮放入只影响缓存键、不参与 SQL 的 _nocache 入参，强制不走结果缓存。
     * （早期靠换超管账号绕缓存，但缓存已改为按「数据范围+字段权限」可见性共享，超管同键。）
     * 阶段二不设置本变量，50 个同视角账号相同入参必须落到同一缓存项——这是被测能力本身。
     */
    static final ThreadLocal<Long> COLD_NONCE = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        // -Dperf.reuse=true：复用上次造好的性能库，只跑测量段（调整测量代码时省去 20 分钟造数/重算）
        Path dbFile = Path.of("target/rpt-perf-data.mv.db");
        boolean reuse = Boolean.getBoolean("perf.reuse") && Files.exists(dbFile);
        if (!reuse) {
            Files.deleteIfExists(dbFile);
            Files.deleteIfExists(Path.of("target/rpt-perf-data.trace.db"));
        }

        JdbcDataSource ds = new JdbcDataSource();
        ds.setUrl("jdbc:h2:file:./target/rpt-perf-data;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;"
                + "DB_CLOSE_DELAY=-1;CACHE_SIZE=262144");
        ds.setUser("sa");
        ds.setPassword("");

        long t0 = System.nanoTime();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        log("Flyway 迁移完成，用时 %.1f s", (System.nanoTime() - t0) / 1e9);

        JdbcTemplate primary = new JdbcTemplate(ds);
        JdbcTemplate reportJdbc = new JdbcTemplate(ds);
        reportJdbc.setQueryTimeout(30);

        if (reuse) {
            log("perf.reuse=true：复用已有性能库，跳过造数/维度/DWS 重算/对账（数据一致性此前已验证）");
        } else {
            long seedStart = System.nanoTime();
            seed(ds);
            double seedSec = (System.nanoTime() - seedStart) / 1e9;

            long dimStart = System.nanoTime();
            int goods = new ReportDimGoodsService(primary).refreshAll();
            log("rpt_dim_goods 快照 %d 个商品，%.1f s", goods, (System.nanoTime() - dimStart) / 1e9);

            // DWS 逐日重算（生产是夜间日结/滚动近 3 天），记录单分区吞吐
            PurchaseDwsService dws = new PurchaseDwsService(primary);
            long dwsStart = System.nanoTime();
            long dwsRows = 0;
            for (int d = 0; d < DAYS; d++) {
                LocalDate day = START.plusDays(d);
                dwsRows += dws.refreshRange(day, day);
            }
            double dwsSec = (System.nanoTime() - dwsStart) / 1e9;

            long orderLines = (long) DAYS * ORDERS_PER_DAY * LINES_PER_ORDER;
            long returnLines = (long) DAYS * RETURNS_PER_DAY * LINES_PER_ORDER;
            log("造数完成：%d 天，订单/入库单明细各 %,d 行，退货明细 %,d 行，用时 %.1f s",
                    DAYS, orderLines, returnLines, seedSec);
            log("DWS 分区 %,d 行，重算用时 %.1f s（日均 %.0f ms/分区）",
                    dwsRows, dwsSec, dwsSec * 1000 / DAYS);

            // DWS 与 DWD 视图对账（性能库同样必须账平）
            Map<String, Object> rec = dws.reconcile(START, endDate());
            log("DWS 对账：balanced=%s dwsAmount=%s liveAmount=%s diffAmount=%s",
                    rec.get("balanced"), rec.get("dwsAmount"), rec.get("liveAmount"), rec.get("diffAmount"));
            if (!Boolean.TRUE.equals(rec.get("balanced"))) {
                throw new IllegalStateException("性能库 DWS 对账不平：" + rec);
            }
        }

        DataScopeService dataScope = new DataScopeService(primary);
        ReportGuard guard = new ReportGuard(primary, dataScope, new PermissionService(primary));
        FieldMasker masker = new FieldMasker(null, null);
        ReportQueryEngine engine = new ReportQueryEngine(reportJdbc, guard, masker);
        List<ReportDefinition> defs = List.of(
                new PurchaseOrderDetailDefinition(dataScope, reportJdbc),
                new PurchaseMoveDetailDefinition(dataScope),
                new PurchaseGoodsSummaryDefinition(dataScope),
                new PurchaseSupplierSummaryDefinition(dataScope));
        ReportRegistry registry = new ReportRegistry(defs);

        String fullStart = START.toString();
        String fullEnd = endDate().toString();
        String oneDay = endDate().toString();
        // 造数按销售单量级（2000 单/日）压采购，3 天≈真实采购一个月的明细行规模
        String threeDay = START.plusDays(2).toString();

        // ===== 阶段一：单用户冷查询延迟（每轮换用户绕开 60s/5min 结果缓存）=====
        log("");
        log("========== 阶段一：单用户冷查询延迟（%d 轮取中位数/p95）==========", ROUNDS);
        latency("#1 采购订单明细-全区间首页(1000行)", () -> engine.page(registry.require("purchase_order_detail"),
                pageBody(fullStart, fullEnd, 1, 1000, null)));
        latency("#1 采购订单明细-全区间深翻页(第100页)", () -> engine.page(registry.require("purchase_order_detail"),
                pageBody(fullStart, fullEnd, 100, 1000, null)));
        latency("#2 采购明细-当日首页(1000行)", () -> engine.page(registry.require("purchase_move_detail"),
                pageBody(oneDay, oneDay, 1, 1000, null)));
        latency("#2 采购明细-3日区间首页(约月量级)", () -> engine.page(registry.require("purchase_move_detail"),
                pageBody(fullStart, threeDay, 1, 1000, null)));
        latency("#2 采购明细-当日合计", () -> engine.summary(registry.require("purchase_move_detail"),
                pageBody(oneDay, oneDay, 1, 1000, null)));
        latency("#3 商品采购汇总-全区间(DWS)", () -> engine.page(registry.require("purchase_goods_summary"),
                summaryBody(fullStart, fullEnd, List.of("goods"))));
        latency("#4 供应商商品汇总-当日(DWS)", () -> engine.page(registry.require("purchase_supplier_summary"),
                summaryBody(oneDay, oneDay, List.of("supplier", "goods"))));
        // 护栏验证：全区间最细叶子（造数把商品/供应商打散，54 万叶子）必须被 10 万行上限友好拦截
        expectFastFail("#4 全区间最细叶子-10万行上限拦截",
                () -> engine.page(registry.require("purchase_supplier_summary"),
                        summaryBody(fullStart, fullEnd, List.of("supplier", "goods", "buyer"))),
                "汇总结果超过 10 万行");

        // ===== 阶段二+三：50 用户并发报表 + 并发下 OLTP 写入 =====
        log("");
        log("========== 阶段二：%d 用户并发（每人 %d 次，全局信号量 15）==========", USERS, ITERS);
        try (Connection oltp = ds.getConnection(); Statement st = oltp.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS rpt_perf_oltp(id BIGINT PRIMARY KEY, ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            // 复用库可能残留上轮压测数据，id 从 1 重插会主键冲突
            st.execute("TRUNCATE TABLE rpt_perf_oltp");
        }
        double oltpBaseline = oltpCommitLatencyMs(ds, 200);
        log("OLTP 单行提交基线（无报表负载）：中位数 %.2f ms", oltpBaseline);

        ExecutorService pool = Executors.newFixedThreadPool(USERS);
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicReference<Double> oltpUnderLoad = new AtomicReference<>(-1.0);
        List<Future<?>> futures = new ArrayList<>();
        // OLTP 施压线程：与报表并发跑同样 200 个单行提交
        futures.add(pool.submit(() -> {
            try {
                startGun.await();
                oltpUnderLoad.set(oltpCommitLatencyMs(ds, 200));
            } catch (Exception e) { throw new RuntimeException(e); }
        }));
        List<Map<String, Object>> concSamples = new ArrayList<>();
        for (int u = 0; u < USERS; u++) {
            final int userIdx = u;
            futures.add(pool.submit(() -> {
                CurrentUser.set(superUser("CONC" + userIdx));
                try {
                    startGun.await();
                    for (int i = 0; i < ITERS; i++) {
                        boolean dwsQuery = (userIdx + i) % 2 == 0;
                        long s = System.currentTimeMillis();
                        try {
                            if (dwsQuery) {
                                engine.page(registry.require("purchase_goods_summary"),
                                        summaryBody(fullStart, fullEnd, List.of("goods")));
                            } else {
                                engine.page(registry.require("purchase_move_detail"),
                                        pageBody(oneDay, oneDay, 1, 1000, null));
                            }
                            record(concSamples, s, true, null);
                        } catch (IllegalArgumentException fastFail) {
                            // 护栏快速失败（排队/繁忙）
                            record(concSamples, s, false, fastFail.getMessage());
                        } catch (Exception other) {
                            record(concSamples, s, false, "其他异常：" + rootMsg(other));
                        }
                    }
                } finally {
                    CurrentUser.clear();
                }
                return null;
            }));
        }
        long waveStart = System.currentTimeMillis();
        startGun.countDown();
        for (Future<?> f : futures) {
            try {
                f.get(10, TimeUnit.MINUTES);
            } catch (ExecutionException workerFailed) {
                log("并发工作线程异常（不影响其他测量）：%s", rootMsg(workerFailed));
            }
        }
        long wall = System.currentTimeMillis() - waveStart;
        pool.shutdown();

        long ok = concSamples.stream().filter(m -> Boolean.TRUE.equals(m.get("ok"))).count();
        long fast = concSamples.size() - ok;
        log("并发波次：%d 次查询，成功 %d，护栏快速失败 %d，墙钟 %.2f s",
                concSamples.size(), ok, fast, wall / 1000.0);
        List<Long> okLat = concSamples.stream().filter(m -> Boolean.TRUE.equals(m.get("ok")))
                .map(m -> (Long) m.get("ms")).sorted().toList();
        if (!okLat.isEmpty()) {
            log("成功查询延迟：p50 %.0f ms / p95 %.0f ms / max %.0f ms",
                    percentile(okLat, 0.50), percentile(okLat, 0.95),
                    (double) okLat.get(okLat.size() - 1));
        }
        Map<String, Long> failReasons = new LinkedHashMap<>();
        concSamples.stream().filter(m -> Boolean.FALSE.equals(m.get("ok")))
                .forEach(m -> failReasons.merge(String.valueOf(m.get("err")), 1L, Long::sum));
        failReasons.forEach((reason, cnt) -> log("快速失败 x%d：%s", cnt, reason));

        log("");
        log("========== 阶段三：OLTP 写入受报表并发影响 ==========");
        double underLoadMs = oltpUnderLoad.get();
        log("单行提交：基线中位数 %.2f ms → 受载中位数 %.2f ms（%+.1f%%）",
                oltpBaseline, underLoadMs,
                (underLoadMs - oltpBaseline) * 100.0 / Math.max(0.001, oltpBaseline));
        log("说明：报表走独立 erp-report-pool（readOnly + 30s 语句超时），连接池物理隔离；H2 文件库仅验证无表锁互锁。");
    }

    // ============================== 造数 ==============================

    static void seed(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement wh = c.prepareStatement(
                    "INSERT INTO base_warehouse(warehouse_id, warehouse_code, warehouse_name, status) "
                            + "VALUES ('W001','WH01','总仓','NORMAL')")) {
                wh.executeUpdate();
            }
            try (PreparedStatement sup = c.prepareStatement(
                    "INSERT INTO base_supplier(supplier_id, supplier_code, supplier_name, supplier_type, default_buyer, status) "
                            + "VALUES (?,?,?,?,?, 'NORMAL')")) {
                for (int s = 0; s < SUPPLIERS; s++) {
                    sup.setString(1, String.format("SID%03d", s));
                    sup.setString(2, String.format("GYS%03d", s));
                    sup.setString(3, String.format("规模供应商%03d", s));
                    sup.setString(4, s % 5 == 0 ? "核心供应商" : "普通供应商");
                    sup.setString(5, "采购员" + (s % 5 + 1));
                    sup.addBatch();
                }
                sup.executeBatch();
            }
            try (PreparedStatement g = c.prepareStatement(
                    "INSERT INTO base_goods(goods_id, goods_code, goods_name, spec, category_name, brand_name, base_unit, "
                            + "barcode, standard_price, latest_purchase_price, storage_property, stock_lower_limit, "
                            + "default_supplier, default_warehouse, can_purchase, status, goods_manager, tax_rate, unit_config) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE,'NORMAL',?,'13%',?)")) {
                String[] cats = {"饮料", "食品", "休闲食品", "粮油", "日化"};
                for (int gi = 0; gi < GOODS; gi++) {
                    boolean large = gi % 10 < 3; // 30% 商品配置箱（1 箱=24 基本单位）
                    String unit = switch (gi % 3) { case 0 -> "瓶"; case 1 -> "袋"; default -> "斤"; };
                    g.setString(1, String.format("GID%04d", gi));
                    g.setString(2, String.format("G%04d", gi));
                    g.setString(3, String.format("规模商品%04d", gi));
                    g.setString(4, "规格" + gi);
                    g.setString(5, cats[gi % cats.length]);
                    g.setString(6, gi % 2 == 0 ? "品牌甲" : "品牌乙");
                    g.setString(7, unit);
                    g.setString(8, String.format("69%010d", gi));
                    g.setBigDecimal(9, java.math.BigDecimal.valueOf(2 + gi % 20));
                    g.setBigDecimal(10, java.math.BigDecimal.valueOf(2 + gi % 15));
                    g.setString(11, "常温");
                    g.setInt(12, 10);
                    g.setString(13, String.format("规模供应商%03d", gi % SUPPLIERS));
                    g.setString(14, WAREHOUSE);
                    g.setString(15, "张三");
                    g.setString(16, large
                            ? "[{\"unitName\":\"" + unit + "\",\"enabled\":true,\"convertQty\":1},"
                              + "{\"unitName\":\"箱\",\"enabled\":true,\"convertQty\":24}]"
                            : null);
                    g.addBatch();
                }
                g.executeBatch();
            }
            c.commit();

            // 订单 / 入库 / 退货：逐日批量提交
            try (PreparedStatement po = c.prepareStatement(
                    "INSERT INTO purchase_order(order_id, order_no, supplier_code, supplier_name, buyer, warehouse, bill_date, "
                            + "amount, inbound_amount, inbound_status, status, creator_name, audit_user, audit_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?, '已入库','APPROVED','规模造数','规模造数', CURRENT_TIMESTAMP)");
                 PreparedStatement pod = c.prepareStatement(
                    "INSERT INTO purchase_order_detail(detail_id, order_id, goods_code, goods_name, unit_name, unit_level, "
                            + "convert_qty, qty, base_qty, price, amount, tax_rate) "
                            + "VALUES (?,?,?,?,?, 1, 1, ?, ?, ?, ?, '13%')");
                 PreparedStatement pi = c.prepareStatement(
                    "INSERT INTO pur_inbound(inbound_id, inbound_no, source_order, supplier, warehouse, bill_date, "
                            + "qty, amount, status, stock_updated) VALUES (?,?,?,?,?,?,?,?,'APPROVED',TRUE)");
                 PreparedStatement pid = c.prepareStatement(
                    "INSERT INTO pur_inbound_detail(detail_id, inbound_id, goods_code, goods_name, warehouse, unit_name, "
                            + "expected_qty, received_qty, price, amount) VALUES (?,?,?,?,?,?,?,?,?,?)");
                 PreparedStatement pr = c.prepareStatement(
                    "INSERT INTO pur_return(return_id, return_no, source_apply_no, source_outbound_no, supplier_code, supplier_name, "
                            + "warehouse, return_date, goods_amount, tax_amount, final_amount, status, creator_name, audit_user, audit_time) "
                            + "VALUES (?,?, 'AP',?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED','规模造数','规模造数', CURRENT_TIMESTAMP)");
                 PreparedStatement prd = c.prepareStatement(
                    "INSERT INTO pur_return_detail(detail_id, return_id, goods_code, goods_name, unit_name, qty, price, "
                            + "amount, tax_rate, tax_amount) VALUES (?,?,?,?,?, 1, ?, ?, '13%', ?)")) {

                long detailSeq = 0;
                for (int d = 0; d < DAYS; d++) {
                    LocalDate day = START.plusDays(d);
                    java.sql.Date sqlDate = java.sql.Date.valueOf(day);
                    String ymd = day.toString().replace("-", "");
                    for (int i = 0; i < ORDERS_PER_DAY; i++) {
                        int supIdx = (d * 31 + i * 7) % SUPPLIERS;
                        String supplierCode = String.format("GYS%03d", supIdx);
                        String supplierName = String.format("规模供应商%03d", supIdx);
                        String buyer = "采购员" + (i % 5 + 1);
                        String orderNo = String.format("CGDD%s%06d", ymd, i + 1);
                        String inboundNo = String.format("CGRK%s%06d", ymd, i + 1);
                        java.math.BigDecimal amount = java.math.BigDecimal.ZERO;
                        for (int k = 0; k < LINES_PER_ORDER; k++) {
                            int gi = (d * 131 + i * 17 + k * 7) % GOODS;
                            String goodsCode = String.format("G%04d", gi);
                            java.math.BigDecimal price = java.math.BigDecimal.valueOf(2L + gi % 15);
                            java.math.BigDecimal lineAmount = price.multiply(java.math.BigDecimal.TEN);
                            amount = amount.add(lineAmount);

                            pod.setString(1, String.format("POD%010d", ++detailSeq));
                            pod.setString(2, orderNo);
                            pod.setString(3, goodsCode);
                            pod.setString(4, String.format("规模商品%04d", gi));
                            pod.setString(5, unitOf(gi));
                            pod.setInt(6, 10);
                            pod.setInt(7, 10);
                            pod.setBigDecimal(8, price);
                            pod.setBigDecimal(9, lineAmount);
                            pod.addBatch();

                            pid.setString(1, String.format("PID%010d", detailSeq));
                            pid.setString(2, inboundNo);
                            pid.setString(3, goodsCode);
                            pid.setString(4, String.format("规模商品%04d", gi));
                            pid.setString(5, WAREHOUSE);
                            pid.setString(6, unitOf(gi));
                            pid.setInt(7, 10);
                            pid.setInt(8, 10);
                            pid.setBigDecimal(9, price);
                            pid.setBigDecimal(10, lineAmount);
                            pid.addBatch();
                        }
                        po.setString(1, orderNo);
                        po.setString(2, orderNo);
                        po.setString(3, supplierCode);
                        po.setString(4, supplierName);
                        po.setString(5, buyer);
                        po.setString(6, WAREHOUSE);
                        po.setDate(7, sqlDate);
                        po.setBigDecimal(8, amount);
                        po.setBigDecimal(9, amount);
                        po.addBatch();

                        pi.setString(1, inboundNo);
                        pi.setString(2, inboundNo);
                        pi.setString(3, orderNo);
                        pi.setString(4, supplierName);
                        pi.setString(5, WAREHOUSE);
                        pi.setDate(6, sqlDate);
                        pi.setInt(7, LINES_PER_ORDER * 10);
                        pi.setBigDecimal(8, amount);
                        pi.addBatch();
                    }
                    // 退货（约入库单数 5%）
                    for (int i = 0; i < RETURNS_PER_DAY; i++) {
                        int supIdx = (d * 11 + i * 3) % SUPPLIERS;
                        String returnNo = String.format("CGTH%s%06d", ymd, i + 1);
                        java.math.BigDecimal goodsAmount = java.math.BigDecimal.ZERO;
                        java.math.BigDecimal taxAmount = java.math.BigDecimal.ZERO;
                        for (int k = 0; k < LINES_PER_ORDER; k++) {
                            int gi = (d * 97 + i * 13 + k * 5) % GOODS;
                            java.math.BigDecimal price = java.math.BigDecimal.valueOf(2L + gi % 15);
                            java.math.BigDecimal lineAmount = price; // 退 1 个基本单位
                            java.math.BigDecimal tax = lineAmount.multiply(java.math.BigDecimal.valueOf(0.13))
                                    .setScale(2, java.math.RoundingMode.HALF_UP);
                            goodsAmount = goodsAmount.add(lineAmount);
                            taxAmount = taxAmount.add(tax);
                            prd.setString(1, String.format("PRD%010d", ++detailSeq));
                            prd.setString(2, returnNo);
                            prd.setString(3, String.format("G%04d", gi));
                            prd.setString(4, String.format("规模商品%04d", gi));
                            prd.setString(5, unitOf(gi));
                            prd.setBigDecimal(6, price);
                            prd.setBigDecimal(7, lineAmount);
                            prd.setBigDecimal(8, tax);
                            prd.addBatch();
                        }
                        pr.setString(1, returnNo);
                        pr.setString(2, returnNo);
                        // UK_PUR_RETURN_SOURCE_OUTBOUND：来源出库单号唯一，逐单造号
                        pr.setString(3, "OB" + ymd + String.format("%06d", i + 1));
                        pr.setString(4, String.format("GYS%03d", supIdx));
                        pr.setString(5, String.format("规模供应商%03d", supIdx));
                        pr.setString(6, WAREHOUSE);
                        pr.setDate(7, sqlDate);
                        pr.setBigDecimal(8, goodsAmount);
                        pr.setBigDecimal(9, taxAmount);
                        pr.setBigDecimal(10, goodsAmount.add(taxAmount));
                        pr.addBatch();
                    }
                    po.executeBatch();
                    pod.executeBatch();
                    pi.executeBatch();
                    pid.executeBatch();
                    pr.executeBatch();
                    prd.executeBatch();
                    c.commit();
                    if ((d + 1) % 2 == 0 || d == DAYS - 1) {
                        log("  造数进度：%d/%d 天（%,d 明细行）", d + 1, DAYS, detailSeq);
                    }
                }
            }
        }
    }

    static String unitOf(int gi) {
        return switch (gi % 3) { case 0 -> "瓶"; case 1 -> "袋"; default -> "斤"; };
    }

    // ============================== 测量 ==============================

    interface Call { Object run() throws Exception; }

    // 查询失败（如 30s 语句超时）只记录不中断：要让后续报表/并发/OLTP 阶段继续出数
    static void latency(String name, Call call) {
        List<Long> samples = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Object last = null;
        for (int r = 0; r <= ROUNDS; r++) {
            boolean warmup = r == 0; // 首轮预热（计划编译/索引），不计时
            CurrentUser.set(superUser("LAT" + USER_SEQ.incrementAndGet()));
            COLD_NONCE.set(USER_SEQ.incrementAndGet());
            try {
                long s = System.currentTimeMillis();
                last = call.run();
                if (!warmup) samples.add(System.currentTimeMillis() - s);
            } catch (Exception e) {
                if (!warmup) errors.add(rootMsg(e));
            } finally {
                COLD_NONCE.remove();
                CurrentUser.clear();
            }
        }
        if (samples.isEmpty()) {
            log("%-38s %d 轮全部失败：%s", name, ROUNDS, errors.get(0));
            return;
        }
        samples.sort(Long::compare);
        int rows = -1;
        if (last instanceof PageResult<?> pr) rows = pr.records().size();
        else if (last instanceof Map<?, ?> m && m.get("records") instanceof List<?> l) rows = l.size();
        log("%-38s p50 %6.0f ms  p95 %6.0f ms  max %6.0f ms  行数=%d%s",
                name, percentile(samples, 0.50), percentile(samples, 0.95),
                (double) samples.get(samples.size() - 1), rows,
                errors.isEmpty() ? "" : "  ⚠ " + errors.size() + " 轮失败：" + errors.get(0));
    }

    /** 护栏用例：必须以包含 expectedFragment 的中文 IllegalArgumentException 快速失败。 */
    static void expectFastFail(String name, Call call, String expectedFragment) {
        boolean fired = false;
        String actual = null;
        for (int r = 0; r <= ROUNDS; r++) {
            CurrentUser.set(superUser("GRD" + USER_SEQ.incrementAndGet()));
            try {
                call.run();
            } catch (Exception e) {
                actual = rootMsg(e);
                for (Throwable t = e; t != null; t = t.getCause()) {
                    if (String.valueOf(t.getMessage()).contains(expectedFragment)) fired = true;
                }
            } finally {
                CurrentUser.clear();
            }
        }
        if (fired) log("%-38s 护栏生效（%s）", name, expectedFragment);
        else log("%-38s ⚠ 护栏未按预期触发，最后异常：%s", name, actual);
    }

    static String rootMsg(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String msg = String.valueOf(t.getMessage());
        return t.getClass().getSimpleName() + ": " + (msg.length() > 120 ? msg.substring(0, 120) : msg);
    }

    static void record(List<Map<String, Object>> out, long start, boolean ok, String err) {
        synchronized (out) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ms", System.currentTimeMillis() - start);
            m.put("ok", ok);
            m.put("err", err);
            out.add(m);
        }
    }

    static final AtomicLong OLTP_SEQ = new AtomicLong();

    // 本地 H2 单行提交常在 1ms 以内，必须保留微秒精度，否则中位数恒为 0
    static double oltpCommitLatencyMs(DataSource ds, int txns) throws Exception {
        List<Long> samplesUs = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO rpt_perf_oltp(id) VALUES (?)")) {
            c.setAutoCommit(true);
            for (int i = 0; i < txns; i++) {
                ps.setLong(1, OLTP_SEQ.incrementAndGet());
                long s = System.nanoTime();
                ps.executeUpdate();
                samplesUs.add((System.nanoTime() - s) / 1_000);
            }
        }
        samplesUs.sort(Long::compare);
        return percentile(samplesUs, 0.50) / 1000.0;
    }

    static double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return -1;
        int idx = (int) Math.min(sorted.size() - 1, Math.round(p * (sorted.size() - 1)));
        return sorted.get(idx);
    }

    static CurrentUser.Principal superUser(String userId) {
        return CurrentUser.of(userId, userId, "性能用户" + userId, null,
                Set.of("SYS_ADMIN"), "SYS_ADMIN", "ERP", null);
    }

    static Map<String, Object> pageBody(String start, String end, int pageNo, int pageSize,
                                        Map<String, String> filters) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dateRange", Map.of("startDate", start, "endDate", end));
        body.put("pageNo", pageNo);
        body.put("pageSize", pageSize);
        if (filters != null) body.put("filters", filters);
        if (COLD_NONCE.get() != null) body.put("_nocache", COLD_NONCE.get());
        return body;
    }

    static Map<String, Object> summaryBody(String start, String end, List<String> groupBy) {
        Map<String, Object> body = pageBody(start, end, 1, 100000, null);
        body.put("groupBy", groupBy);
        return body;
    }

    static void log(String fmt, Object... args) {
        System.out.printf(fmt + "%n", args);
    }
}
