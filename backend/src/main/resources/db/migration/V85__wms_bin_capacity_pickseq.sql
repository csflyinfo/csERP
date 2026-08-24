-- V85: 库位容量三件套（件/重量/体积）+ 拣货顺序
-- 对应需求：库位除最多件数外可设最大存货重量/体积；库位加拣货顺序，PDA 按顺序引导路径。
-- 重量单位 kg，体积单位 m³（与 base_goods.net_weight/volume 保持一致）。
ALTER TABLE wms_bin ADD COLUMN IF NOT EXISTS capacity_weight DECIMAL(18,3) DEFAULT 0;
ALTER TABLE wms_bin ADD COLUMN IF NOT EXISTS capacity_volume DECIMAL(18,4) DEFAULT 0;
ALTER TABLE wms_bin ADD COLUMN IF NOT EXISTS pick_seq INT DEFAULT 0;

-- 拣货顺序主要用于 PICK/SHELF 类库位；COLLECT/STAGE 给 0 不参与排序。
CREATE INDEX IF NOT EXISTS idx_bin_pick_seq ON wms_bin(warehouse, zone_code, pick_seq);
CREATE INDEX IF NOT EXISTS idx_bin_type ON wms_bin(warehouse, zone_code, bin_type);

-- 给已有库位按 bin_code 字母序回填一个默认顺序，避免列表全是 0 看不出层级。
-- H2 不支持 UPDATE ... FROM 子查询连接语法；用 MERGE INTO ... KEY(...) SELECT 实现。
MERGE INTO wms_bin (bin_id, pick_seq) KEY(bin_id)
SELECT bin_id, ROW_NUMBER() OVER (PARTITION BY warehouse, zone_code ORDER BY bin_code) AS rn
FROM wms_bin
WHERE COALESCE(pick_seq, 0) = 0;
