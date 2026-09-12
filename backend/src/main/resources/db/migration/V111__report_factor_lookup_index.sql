-- =====================================================================
-- 报表中心一期补充索引（V110 性能实测追加）
--
-- 背景：DWD 视图 v_rpt_purchase_detail / v_rpt_sales_detail 对每个明细行用
--   SELECT MIN(convert_qty) FROM purchase_order_detail
--   WHERE goods_code=? AND unit_name=? AND COALESCE(convert_qty,0)>0
-- 反查单据换算率。规模实测（10 天 × 2,000 单 × 50 行 = 100 万订单明细）下，
-- 仅有 (goods_code) 单列索引时，DWS 日分区重算在 H2 上约 5 分钟/天；
-- (goods_code, unit_name, convert_qty) 覆盖索引让该子查询纯索引取 MIN，
-- MySQL 上夜间日结/滚动重算开销显著下降；对销售域后续报表同样生效。
-- 幂等：IF NOT EXISTS，可重复执行。
-- =====================================================================

CREATE INDEX IF NOT EXISTS idx_po_detail_rpt_factor
    ON purchase_order_detail(goods_code, unit_name, convert_qty);

CREATE INDEX IF NOT EXISTS idx_so_detail_rpt_factor
    ON sales_order_detail(goods_code, unit_name, convert_qty);
