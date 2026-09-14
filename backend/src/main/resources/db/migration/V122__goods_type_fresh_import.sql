-- ============================================
-- V122: 商品档案优化
--   1. 新增「是否生鲜」is_fresh
--   2. 商品类型改为数字码：0 正常商品 / 1 赠品 / 2 设备辅材 / 3 包装物 / 4 兑换物
--      （VARCHAR 列存字符串数字；旧类型「组合商品/服务商品」保留原中文，页面原样显示）
--   3. 税率统一存纯数字（'13%' -> '13'），页面显示带 %
--   4. 按类型码回填 can_sale / can_purchase（0/1 可采可销；2/3 可采不可销；4 不可采不可销但可退）
--   5. 导入任务表增加失败文件列（真实失败明细 xlsx 落盘路径）
-- ============================================

ALTER TABLE base_goods ADD COLUMN is_fresh BOOLEAN DEFAULT FALSE;

-- 存量类型转码（仅转换有对应码的中文值；组合商品/服务商品保持不动）
UPDATE base_goods SET goods_type = '0' WHERE goods_type = '正常商品';
UPDATE base_goods SET goods_type = '1' WHERE goods_type = '赠品';

-- 存量税率去百分号（REPLACE 对不含 % 的值无副作用）
UPDATE base_goods SET tax_rate = REPLACE(tax_rate, '%', '') WHERE tax_rate IS NOT NULL;

-- 按类型码回填采销标志；0/1 与旧中文值保持 TRUE
UPDATE base_goods SET can_sale = FALSE, can_purchase = TRUE WHERE goods_type IN ('2', '3');
UPDATE base_goods SET can_sale = FALSE, can_purchase = FALSE WHERE goods_type = '4';

ALTER TABLE sys_import_task_runtime ADD COLUMN failure_file VARCHAR(500);
