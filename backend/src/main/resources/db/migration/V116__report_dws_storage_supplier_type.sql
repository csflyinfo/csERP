-- =====================================================================
-- 报表中心一期性能修复⑤：档案属性下沉采购 DWS，#3/#4 热路径零档案表 JOIN
--
-- 压测发现商品采购汇总（#3）/供应商商品汇总（#4）在 100 万 DWS 行上
-- 每行对 rpt_dim_goods / base_supplier 做探测（偶发超 30s）。
-- 存储属性、供应商分类都是档案型慢变属性，随日汇总快照冗余：
--   * 分组/筛选直接走 DWS 列，聚合过程不碰任何档案表；
--   * 条码/基本单位仍在分组后按商品叶子行关联维度（行数=分组结果，≤10 万）。
-- 历史分区用 MERGE 回填（H2 不支持 UPDATE...FROM）；DWS 重算 SQL 同步带出。
-- =====================================================================

ALTER TABLE rpt_dws_purchase_d ADD COLUMN storage_property VARCHAR(50);
ALTER TABLE rpt_dws_purchase_d ADD COLUMN supplier_type VARCHAR(50);

MERGE INTO rpt_dws_purchase_d
    (bill_date, supplier_code, buyer, goods_code, warehouse, storage_property)
KEY (bill_date, supplier_code, buyer, goods_code, warehouse)
SELECT x.bill_date, x.supplier_code, x.buyer, x.goods_code, x.warehouse,
       dg.storage_property
FROM rpt_dws_purchase_d x
LEFT JOIN rpt_dim_goods dg ON dg.goods_code = x.goods_code;

MERGE INTO rpt_dws_purchase_d
    (bill_date, supplier_code, buyer, goods_code, warehouse, supplier_type)
KEY (bill_date, supplier_code, buyer, goods_code, warehouse)
SELECT x.bill_date, x.supplier_code, x.buyer, x.goods_code, x.warehouse,
       s.supplier_type
FROM rpt_dws_purchase_d x
LEFT JOIN base_supplier s ON s.supplier_code = x.supplier_code;
