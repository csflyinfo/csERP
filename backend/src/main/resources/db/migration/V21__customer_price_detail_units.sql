-- ============================================
-- V21: 客户价格调整单明细 —— 三级单位（小/中/大）定价改造
--
-- 业务背景：
--   原客户价格调整单只支持「单一基本单位 + 原价/现价」，
--   与商品档案的多单位体系（unit_config：小/中/大 三级单位各自独立标价）不匹配。
--   本次改造让调价单明细同时对三个单位定价，并补齐展示字段（品牌/分类/存储属性）。
--
-- 字段设计说明：
--   1. 保留原有 base_unit / original_price / current_price 三个字段不动，
--      老数据与老接口继续可用（CLAUDE.md 禁止修改已存在字段名）；
--      新增字段承载三级单位信息，写入时新旧字段同时落库。
--   2. medium_unit_enabled / large_unit_enabled 冗余存商品档案当时的启用状态，
--      而非查询时实时解析 unit_config —— 单据是历史凭证，
--      商品档案后续停用中/大单位不应改变已开单据的展示。
--   3. 单位标价/现价统一 DECIMAL(18,2)，与表内既有价格字段精度保持一致。
--
-- H2 限制：必须用 ADD COLUMN IF NOT EXISTS，H2 不支持 DROP COLUMN IF EXISTS。
-- ============================================

-- ---------- 调价单明细表 ----------
-- 商品展示字段（来自商品档案快照）
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS brand_name VARCHAR(100);
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS category_name VARCHAR(100);
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS storage_property VARCHAR(50);

-- 三级单位名称
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS small_unit VARCHAR(50);
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS medium_unit VARCHAR(50);
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS large_unit VARCHAR(50);

-- 中/大单位启用状态（小单位恒启用，故不单独存）
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS medium_unit_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS large_unit_enabled BOOLEAN DEFAULT FALSE;

-- 三级单位标价（商品档案原价快照）
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS small_standard_price DECIMAL(18,2);
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS medium_standard_price DECIMAL(18,2);
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS large_standard_price DECIMAL(18,2);

-- 三级单位现价（用户录入的调整后价格）
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS small_current_price DECIMAL(18,2);
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS medium_current_price DECIMAL(18,2);
ALTER TABLE base_customer_price_adjust_detail ADD COLUMN IF NOT EXISTS large_current_price DECIMAL(18,2);

-- ---------- 生效客户价格表 ----------
-- 审核时由明细表结转过来，字段保持一致，便于客户价格查询页同样展示三级单位
ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS brand_name VARCHAR(100);
ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS category_name VARCHAR(100);
ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS storage_property VARCHAR(50);

ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS small_unit VARCHAR(50);
ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS medium_unit VARCHAR(50);
ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS large_unit VARCHAR(50);

ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS medium_unit_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS large_unit_enabled BOOLEAN DEFAULT FALSE;

ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS small_standard_price DECIMAL(18,2);
ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS medium_standard_price DECIMAL(18,2);
ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS large_standard_price DECIMAL(18,2);

ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS small_current_price DECIMAL(18,2);
ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS medium_current_price DECIMAL(18,2);
ALTER TABLE base_customer_price ADD COLUMN IF NOT EXISTS large_current_price DECIMAL(18,2);

-- ---------- 老数据回填 ----------
-- 历史单据只有基本单位一档价，把它归入「小单位」，保证新版明细表格不出现空白列
UPDATE base_customer_price_adjust_detail
   SET small_unit = base_unit,
       small_standard_price = original_price,
       small_current_price = current_price
 WHERE small_unit IS NULL;

UPDATE base_customer_price
   SET small_unit = base_unit,
       small_standard_price = original_price,
       small_current_price = current_price
 WHERE small_unit IS NULL;
