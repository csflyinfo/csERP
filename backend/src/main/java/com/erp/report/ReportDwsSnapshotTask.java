package com.erp.report;

import com.erp.report.common.ReportGuard;
import com.erp.report.dws.PurchaseDwsService;
import com.erp.report.dws.ReportDimGoodsService;
import com.erp.report.dws.ReportDimPartnerService;
import com.erp.report.dws.SalesDwsService;
import com.erp.report.dws.StockMoveDwsService;
import com.erp.report.dws.StockSnapshotService;
import com.erp.report.export.ReportExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报表预聚合定时任务（方案 §4.5）：
 * <ul>
 *   <li>每 30 分钟：维度快照刷新 + 当天采购 DWS 增量（当天单据可能被反审核/修改，整分区重算）；</li>
 *   <li>每日 02:10：维度全量 + 采购 DWS 滚动近 3 天重算并对账 + 前一日库存快照（Q6 测试环境同样日结）；</li>
 *   <li>每日 03:10：rpt_query_log 超 90 天清理、导出文件 7 天保留期清理。</li>
 * </ul>
 * 全部幂等（DELETE 分区 + INSERT...SELECT），随时可由管理接口手工触发补算。
 */
@Component
public class ReportDwsSnapshotTask {

    private static final Logger log = LoggerFactory.getLogger(ReportDwsSnapshotTask.class);

    private final ReportDimGoodsService dimGoods;
    private final ReportDimPartnerService dimPartner;
    private final PurchaseDwsService purchaseDws;
    private final SalesDwsService salesDws;
    private final StockMoveDwsService stockMoveDws;
    private final StockSnapshotService stockSnapshot;
    private final ReportGuard guard;
    private final ReportExportService exportService;

    public ReportDwsSnapshotTask(ReportDimGoodsService dimGoods, ReportDimPartnerService dimPartner,
                                 PurchaseDwsService purchaseDws, SalesDwsService salesDws,
                                 StockMoveDwsService stockMoveDws, StockSnapshotService stockSnapshot,
                                 ReportGuard guard, ReportExportService exportService) {
        this.dimGoods = dimGoods;
        this.dimPartner = dimPartner;
        this.purchaseDws = purchaseDws;
        this.salesDws = salesDws;
        this.stockMoveDws = stockMoveDws;
        this.stockSnapshot = stockSnapshot;
        this.guard = guard;
        this.exportService = exportService;
    }

    /** 维度 + 当天增量：每小时 10 分、40 分。 */
    @Scheduled(cron = "0 10,40 * * * ?")
    public void intradayRefresh() {
        try {
            dimGoods.refreshAll();
            dimPartner.refreshAll();
            LocalDate today = LocalDate.now();
            purchaseDws.refreshRange(today, today);
            salesDws.refreshRange(today, today);
            stockMoveDws.refreshRange(today, today);
        } catch (Exception e) {
            log.warn("报表当天增量刷新失败：{}", e.getMessage());
        }
    }

    /** 夜间全量：02:10。 */
    @Scheduled(cron = "0 10 2 * * ?")
    public void nightlyRefresh() {
        try {
            dimGoods.refreshAll();
            dimPartner.refreshAll();
            LocalDate today = LocalDate.now();
            LocalDate from = today.minusDays(2);
            purchaseDws.refreshRange(from, today);
            Map<String, Object> recon = purchaseDws.reconcile(from, today);
            if (Boolean.FALSE.equals(recon.get("balanced"))) {
                log.warn("采购 DWS 夜间对账不平：{}", recon);
            }
            salesDws.refreshRange(from, today);
            Map<String, Object> salesRecon = salesDws.reconcile(from, today);
            if (Boolean.FALSE.equals(salesRecon.get("balanced"))) {
                log.warn("销售 DWS 夜间对账不平：{}", salesRecon);
            }
            stockMoveDws.refreshRange(from, today);
            Map<String, Object> moveRecon = stockMoveDws.reconcile(from, today);
            if (Boolean.FALSE.equals(moveRecon.get("balanced"))) {
                log.warn("库存流水 DWS 夜间对账不平：{}", moveRecon);
            }
            // 前一日库存快照（凌晨执行时余额即前一日日结余额）
            stockSnapshot.rebuild(today.minusDays(1));
        } catch (Exception e) {
            log.warn("报表夜间预聚合失败：{}", e.getMessage());
        }
    }

    /** 审计日志/导出文件保留期清理：每日 03:10。 */
    @Scheduled(cron = "0 10 3 * * ?")
    public void retentionCleanup() {
        try {
            int logs = guard.purgeQueryLog(LocalDateTime.now().minusDays(90));
            int files = exportService.purgeExpired();
            if (logs > 0 || files > 0) {
                log.info("报表保留期清理：审计日志 {} 条，导出任务 {} 个", logs, files);
            }
        } catch (Exception e) {
            log.warn("报表保留期清理失败：{}", e.getMessage());
        }
    }

    /** 管理接口手工触发（重算闭区间 + 对账），结果原样返回。 */
    public Map<String, Object> recomputePurchase(LocalDate start, LocalDate end) {
        dimGoods.refreshAll();
        dimPartner.refreshAll();
        int rows = purchaseDws.refreshRange(start, end);
        Map<String, Object> recon = purchaseDws.reconcile(start, end);
        Map<String, Object> r = new LinkedHashMap<>(recon);
        r.put("recomputedRows", rows);
        return r;
    }

    public int rebuildStockSnapshot(LocalDate date) {
        return stockSnapshot.rebuild(date);
    }

    /** 管理接口手工触发销售 DWS 重算闭区间 + 对账，结果原样返回。 */
    public Map<String, Object> recomputeSales(LocalDate start, LocalDate end) {
        dimGoods.refreshAll();
        dimPartner.refreshAll();
        int rows = salesDws.refreshRange(start, end);
        Map<String, Object> recon = salesDws.reconcile(start, end);
        Map<String, Object> r = new LinkedHashMap<>(recon);
        r.put("recomputedRows", rows);
        return r;
    }

    /** 管理接口手工触发库存流水 DWS 全量重建。 */
    public int rebuildStockMoveAll() {
        return stockMoveDws.rebuildAll();
    }
}
