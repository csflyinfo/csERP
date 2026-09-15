package com.erp.wms;

import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WMS 库位实物账（wms_bin_stock）写入服务（PRD-34 抽取）。
 *
 * <p>原 WmsInboundService / WmsInternalService 各有一份私有 addBinStock，
 * 逻辑漂移风险高（一份写日志+效期、一份不写）。期初库位导入也要写位置账且
 * source_bill 必须可溯源（QTRK-过批号-序号），故统一抽到本服务：
 * <ul>
 *   <li>{@link #addBinStock} 库内作业（盘点/移库等）：只 upsert 数量，不写 bin log，保持旧 WmsInternalService 行为；</li>
 *   <li>{@link #addInboundBinStock} 入库类（上架/期初建账）：upsert + wms_bin.used_qty + bin log(IN)。</li>
 * </ul>
 * 唯一键：goods_code + warehouse + bin_code + COALESCE(batch_no,'') + COALESCE(container_code,'')。
 */
@Service
public class BinStockService {

    private final JdbcTemplate jdbc;

    public BinStockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 库位实物数量增加（库内调整口径，不写流水日志）。
     *
     * @return 命中的 wms_bin_stock 主键
     */
    public String addBinStock(String goodsCode, String goodsName, String warehouse,
                              String batchNo, String bin, BigDecimal qty) {
        return upsert(goodsCode, goodsName, warehouse, batchNo, bin, qty,
                null, null, null, null, false);
    }

    /**
     * 入库类库位数量增加：upsert 实物账 + wms_bin.used_qty + wms_bin_stock_log(IN)。
 *
     * @param sourceBill     来源单号（WMS_PUTAWAY / QTRK-过批号-序号 等），写 bin log 可溯源
     * @param containerCode  容器编码，可空
     * @param productionDate 生产日期，可空
     * @param expiryDate     到期日期，可空
     * @return 命中的 wms_bin_stock 主键
     */
    public String addInboundBinStock(String goodsCode, String goodsName, String warehouse,
                                     String batchNo, String bin, BigDecimal qty, String containerCode,
                                     LocalDate productionDate, LocalDate expiryDate, String sourceBill) {
        return upsert(goodsCode, goodsName, warehouse, batchNo, bin, qty,
                containerCode, productionDate, expiryDate, sourceBill, true);
    }

    private String upsert(String goodsCode, String goodsName, String warehouse,
                          String batchNo, String bin, BigDecimal qty, String containerCode,
                          LocalDate productionDate, LocalDate expiryDate,
                          String sourceBill, boolean writeLog) {
        String cc = containerCode == null ? "" : containerCode;
        String bn = batchNo == null ? "" : batchNo;

        List<Map<String, Object>> existing = TmsUtil.queryCamel(jdbc,
                "SELECT bin_stock_id FROM wms_bin_stock " +
                        "WHERE goods_code=? AND warehouse=? AND bin_code=? " +
                        "AND COALESCE(batch_no,'')=? AND COALESCE(container_code,'')=?",
                goodsCode, warehouse, bin, bn, cc);
        if (!existing.isEmpty()) {
            String id = TmsUtil.str(existing.get(0).get("binStockId"));
            jdbc.update("UPDATE wms_bin_stock SET qty = qty + ?, updated_at = CURRENT_TIMESTAMP " +
                            "WHERE bin_stock_id = ?", qty, id);
            return id;
        }

        String id = "BS" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        if (productionDate != null || expiryDate != null || writeLog) {
            // 上架/期初口径：带容器与效期列
            jdbc.update("INSERT INTO wms_bin_stock " +
                            "(bin_stock_id, goods_code, goods_name, warehouse, batch_no, bin_code, container_code, " +
                            "production_date, expiry_date, qty, locked_qty, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP)",
                    id, goodsCode, goodsName, warehouse, emptyToNull(batchNo), bin,
                    cc.isBlank() ? "" : cc, productionDate, expiryDate, qty);
        } else {
            // 库内作业旧口径：无容器无效期列
            jdbc.update("INSERT INTO wms_bin_stock " +
                            "(bin_stock_id, goods_code, goods_name, warehouse, batch_no, bin_code, container_code, " +
                            "qty, locked_qty, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, '', ?, 0, CURRENT_TIMESTAMP)",
                    id, goodsCode, goodsName, warehouse, emptyToNull(batchNo), bin, qty);
        }
        jdbc.update("UPDATE wms_bin SET used_qty = COALESCE(used_qty,0) + ? WHERE warehouse=? AND bin_code=?",
                qty, warehouse, bin);
        if (writeLog) {
            try {
                jdbc.update("INSERT INTO wms_bin_stock_log " +
                                "(log_id, warehouse, goods_code, batch_no, to_bin, container_code, " +
                                "direction, qty, source_bill, operator) " +
                                "VALUES (?, ?, ?, ?, ?, ?, 'IN', ?, ?, ?)",
                        "BL" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase(),
                        warehouse, goodsCode, emptyToNull(batchNo), bin, cc.isBlank() ? null : cc,
                        qty, sourceBill, TmsUtil.currentUser());
            } catch (Exception ignore) {
                // bin log 失败不阻断库存主流程（与旧实现一致）
            }
        }
        return id;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
