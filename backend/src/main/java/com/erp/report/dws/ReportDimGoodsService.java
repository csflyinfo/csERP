package com.erp.report.dws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商品维度快照 rpt_dim_goods 全量刷新（每 30 分钟 + 启动时）。
 *
 * <p>unit_config 为 JSON 数组（0=小/基本单位，1=中，2=大/件），元素字段
 * unitName/enabled/convertQty/standardPrice。大单位换算率是视图/DWS 计算
 * 「件数 = 基本数量 ÷ convertQty」与录单单位归一的唯一来源。
 */
@Service
public class ReportDimGoodsService {

    private static final Logger log = LoggerFactory.getLogger(ReportDimGoodsService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ReportDimGoodsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全量重建（表小：数万行内；DELETE+INSERT 在一个事务内）。返回写入行数。 */
    @Transactional
    public int refreshAll() {
        List<Map<String, Object>> goods = jdbc.queryForList("""
                SELECT g.goods_code, g.goods_name, g.spec, g.brand_name, g.category_name,
                       g.base_unit, g.unit_config, g.default_supplier, g.default_warehouse,
                       g.can_purchase, g.status, g.barcode, g.storage_property,
                       (SELECT MIN(s.supplier_code) FROM base_supplier s
                         WHERE s.supplier_name = g.default_supplier) AS supplier_code
                FROM base_goods g
                """);
        jdbc.update("DELETE FROM rpt_dim_goods");
        for (Map<String, Object> g : goods) {
            String baseUnit = str(g.get("base_unit"));
            String largeUnit = null;
            BigDecimal largeConvert = null;
            String raw = str(g.get("unit_config"));
            if (raw.startsWith("[")) {
                try {
                    List<Map<String, Object>> units = JSON.readValue(raw, List.class);
                    if (units.size() >= 3) {
                        Map<String, Object> large = units.get(2);
                        Object en = large.get("enabled");
                        boolean enabled = en == null || Boolean.TRUE.equals(en) || "true".equals(String.valueOf(en));
                        String name = str(large.get("unitName"));
                        if (enabled && !name.isEmpty()) {
                            largeUnit = name;
                            Object cq = large.get("convertQty");
                            if (cq != null) {
                                try {
                                    BigDecimal v = new BigDecimal(String.valueOf(cq));
                                    if (v.signum() > 0) largeConvert = v;
                                } catch (NumberFormatException ignore) { /* 留空 */ }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("商品 {} unit_config 解析失败：{}", g.get("goods_code"), e.getMessage());
                }
            }
            jdbc.update("""
                    INSERT INTO rpt_dim_goods(goods_code, goods_name, spec, brand_name, category_name,
                        base_unit, large_unit, large_convert_qty, default_supplier_code,
                        default_supplier_name, default_warehouse, can_purchase, status,
                        barcode, storage_property, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    str(g.get("goods_code")), str(g.get("goods_name")), str(g.get("spec")),
                    str(g.get("brand_name")), str(g.get("category_name")),
                    baseUnit.isEmpty() ? null : baseUnit, largeUnit, largeConvert,
                    str(g.get("supplier_code")), emptyToNull(str(g.get("default_supplier"))),
                    emptyToNull(str(g.get("default_warehouse"))),
                    g.get("can_purchase") == null ? Boolean.TRUE : g.get("can_purchase"),
                    emptyToNull(str(g.get("status"))),
                    emptyToNull(str(g.get("barcode"))), emptyToNull(str(g.get("storage_property"))));
        }
        // 单位换算率物理维度（V113）：DWD 视图按主键 JOIN 它取换算率，
        // 避免每次查询对订单明细做百万行 GROUP BY。采购/销售两套，全量重建。
        jdbc.update("DELETE FROM rpt_dim_unit_factor");
        int pf = jdbc.update("""
                INSERT INTO rpt_dim_unit_factor(biz_type, goods_code, unit_name, factor_qty, updated_at)
                SELECT 'P', goods_code, unit_name, MIN(convert_qty), CURRENT_TIMESTAMP
                FROM purchase_order_detail
                WHERE COALESCE(convert_qty, 0) > 0 AND unit_name IS NOT NULL
                GROUP BY goods_code, unit_name
                """);
        int sf = jdbc.update("""
                INSERT INTO rpt_dim_unit_factor(biz_type, goods_code, unit_name, factor_qty, updated_at)
                SELECT 'S', goods_code, unit_name, MIN(convert_qty), CURRENT_TIMESTAMP
                FROM sales_order_detail
                WHERE COALESCE(convert_qty, 0) > 0 AND unit_name IS NOT NULL
                GROUP BY goods_code, unit_name
                """);
        log.info("rpt_dim_goods 刷新完成：{} 个商品；换算率维度 P={} / S={} 行", goods.size(), pf, sf);
        return goods.size();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
