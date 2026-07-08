-- ============================================
-- V25: 客户商品变价查询 + 客户价格查询（按单位维度）
--
-- 业务背景：
--   现有 base_customer_price 是「一个商品一行、三个单位挤在同一行的列」结构，
--   两个新模块都需要「按单位类型」的行粒度：
--     · 客户商品变价查询 —— 每次变价一条记录（变价前/变价后）
--     · 客户价格查询     —— 按单位类型筛选、单独停用某个单位的价格
--   因此对照价格组模块（base_price_group_item / base_price_change_log）
--   建两张按 unit_level 拆行的表，语义与命名与之保持一致，便于维护。
--
-- 设计说明：
--   1. unit_level：1-小 2-中 3-大，与 base_price_group_item 完全一致。
--   2. base_customer_price 老表保留不动 —— 客户价格调整单的审核仍写它（兼容旧接口），
--      同时把每个启用单位拆行写入新表 base_customer_price_item。
--   3. 变价日志 old_price 可为空（首次设价），前端按空值显示「首次设价」。
--   4. valid_range / effective_mode 冗余进两张表：单据是历史凭证，
--      调整单后续被改动不应改变已生成价格的有效期口径。
-- ============================================

-- ---------- 客户商品价格（按单位拆行）----------
CREATE TABLE IF NOT EXISTS base_customer_price_item (
  id               VARCHAR(32)   PRIMARY KEY,
  customer_code    VARCHAR(50)   NOT NULL,
  customer_name    VARCHAR(100),
  goods_code       VARCHAR(50)   NOT NULL,
  goods_name       VARCHAR(200),
  unit_level       TINYINT       NOT NULL COMMENT '1-小 2-中 3-大',
  unit_name        VARCHAR(50),
  standard_price   DECIMAL(18,2) COMMENT '标价（商品档案快照）',
  price            DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '客户专属现价',
  adjust_no        VARCHAR(50)   COMMENT '来源调整单号',
  effective_mode   VARCHAR(20),
  valid_range      VARCHAR(100),
  is_active        BOOLEAN       NOT NULL DEFAULT TRUE,
  created_at       TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cust_goods_unit (customer_code, goods_code, unit_level)
);
CREATE INDEX IF NOT EXISTS idx_cpi_customer ON base_customer_price_item(customer_code, is_active);
CREATE INDEX IF NOT EXISTS idx_cpi_goods ON base_customer_price_item(goods_code);

-- ---------- 客户商品变价日志 ----------
CREATE TABLE IF NOT EXISTS base_customer_price_change_log (
  id               VARCHAR(32)   PRIMARY KEY,
  adjust_no        VARCHAR(50)   COMMENT '来源调整单号',
  bill_date        DATE          COMMENT '调价单日期',
  customer_code    VARCHAR(50)   NOT NULL,
  customer_name    VARCHAR(100),
  goods_code       VARCHAR(50)   NOT NULL,
  goods_name       VARCHAR(200),
  unit_level       TINYINT       NOT NULL COMMENT '1-小 2-中 3-大',
  unit_name        VARCHAR(50),
  category_name    VARCHAR(100),
  brand_name       VARCHAR(100),
  old_price        DECIMAL(18,2) COMMENT '变价前；空=首次设价',
  new_price        DECIMAL(18,2) NOT NULL COMMENT '变价后',
  effective_mode   VARCHAR(20),
  valid_range      VARCHAR(100),
  operator         VARCHAR(100),
  remark           VARCHAR(500),
  created_at       TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_cpcl_customer ON base_customer_price_change_log(customer_code);
CREATE INDEX IF NOT EXISTS idx_cpcl_goods ON base_customer_price_change_log(goods_code);
CREATE INDEX IF NOT EXISTS idx_cpcl_created ON base_customer_price_change_log(created_at);
