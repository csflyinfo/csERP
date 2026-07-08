-- ============================================
-- V35: 商品调价单 —— 全新模块，与原价格组调价单独立
--
-- 业务背景：
--   原 price_group 调价单按「价格组」维度调一种价格，
--   本模块按「商品」维度同时调六种价格：
--     标准售价/参考进价/最低价/建议零售价 + 启用价格组的价格
--
-- 与 base_price_adjust_order* 表独立，互不干扰。
--   原价格组调价单保持不变继续使用。
--
-- 快速调价入口：
--   商品档案多单位矩阵【快速调价】→ 创建本模块的单商品锁定的草稿
--   （goods_locked=TRUE，不可增删商品），审核后价格写回 unit_config。
-- ============================================

CREATE TABLE base_goods_price_adjust_order (
  order_id      VARCHAR(32)    PRIMARY KEY,
  order_no      VARCHAR(30)    NOT NULL UNIQUE COMMENT 'SPTJ+yyyyMMdd+3位流水',
  goods_code    VARCHAR(50),                             -- 快速调价锁定商品，NULL=多商品
  goods_name    VARCHAR(200),
  goods_locked  BOOLEAN       DEFAULT FALSE,             -- TRUE=不可增删商品
  goods_count   INT           DEFAULT 0,
  status        VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT/PENDING/APPROVED/REJECTED
  remark        VARCHAR(500),
  reject_reason VARCHAR(500),
  create_user   VARCHAR(50),
  create_time   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  submit_user   VARCHAR(50),
  submit_time   TIMESTAMP,
  audit_user    VARCHAR(50),
  audit_time    TIMESTAMP
);
CREATE INDEX idx_gpao_status ON base_goods_price_adjust_order(status);

CREATE TABLE base_goods_price_adjust_order_item (
  id            VARCHAR(32)    PRIMARY KEY,
  order_id      VARCHAR(32)    NOT NULL,
  goods_code    VARCHAR(50)    NOT NULL,
  goods_name    VARCHAR(200),
  unit_level    TINYINT        NOT NULL DEFAULT 1,       -- 1小/2中/3大
  -- 标准售价
  standard_price_new  DECIMAL(14, 4),
  standard_price_old  DECIMAL(14, 4),
  -- 参考进价
  purchase_price_new  DECIMAL(14, 4),
  purchase_price_old  DECIMAL(14, 4),
  -- 最低价
  min_price_new       DECIMAL(14, 4),
  min_price_old       DECIMAL(14, 4),
  -- 建议零售价
  suggest_retail_price_new  DECIMAL(14, 4),
  suggest_retail_price_old  DECIMAL(14, 4),
  -- 价格组价格 JSON：[{pgCode, pgName, newPrice, oldPrice}]
  price_group_prices VARCHAR(4000)
);
CREATE INDEX idx_gpaoi_order ON base_goods_price_adjust_order_item(order_id);
