-- ============================================
-- V5: 多价格组管理 - 商品价格 / 调价单 / 变价日志
-- 参照 PRD v2.0（erp-multi-price-group）
-- ============================================

-- 价格组商品价格表：product_id × price_group_code × unit_level 三键
CREATE TABLE base_price_group_item (
  id            VARCHAR(32)    PRIMARY KEY,
  goods_code    VARCHAR(50)    NOT NULL,
  goods_name    VARCHAR(200),
  price_group_code VARCHAR(50) NOT NULL,
  unit_level    TINYINT        NOT NULL COMMENT '1-小 2-中 3-大',
  price         DECIMAL(14,4)  NOT NULL DEFAULT 0,
  is_active     BOOLEAN        NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_goods_group_unit (goods_code, price_group_code, unit_level)
);
CREATE INDEX idx_price_item_group ON base_price_group_item(price_group_code, is_active);
CREATE INDEX idx_price_item_goods ON base_price_group_item(goods_code);

-- 调价单头：状态 0-草稿 / 1-待审 / 2-已审 / 3-驳回
CREATE TABLE base_price_adjust_order (
  order_id      VARCHAR(32)    PRIMARY KEY,
  order_no      VARCHAR(30)    NOT NULL UNIQUE COMMENT 'TJ+yyyyMMdd+3位流水',
  price_group_code VARCHAR(50) NOT NULL,
  price_group_name VARCHAR(100),
  goods_count   INT            DEFAULT 0,
  status        VARCHAR(20)    NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/REJECTED',
  remark        VARCHAR(500),
  reject_reason VARCHAR(500),
  create_user   VARCHAR(50),
  create_time   TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
  submit_user   VARCHAR(50),
  submit_time   TIMESTAMP      NULL,
  audit_user    VARCHAR(50),
  audit_time    TIMESTAMP      NULL
);
CREATE INDEX idx_padj_group ON base_price_adjust_order(price_group_code);
CREATE INDEX idx_padj_status ON base_price_adjust_order(status);

-- 调价单明细
CREATE TABLE base_price_adjust_order_item (
  id            VARCHAR(32)    PRIMARY KEY,
  order_id      VARCHAR(32)    NOT NULL,
  goods_code    VARCHAR(50)    NOT NULL,
  goods_name    VARCHAR(200),
  small_new_price  DECIMAL(14,4),
  medium_new_price DECIMAL(14,4),
  large_new_price  DECIMAL(14,4),
  small_old_price  DECIMAL(14,4),
  medium_old_price DECIMAL(14,4),
  large_old_price  DECIMAL(14,4)
);
CREATE INDEX idx_padj_item_order ON base_price_adjust_order_item(order_id);

-- 变价日志
CREATE TABLE base_price_change_log (
  id            VARCHAR(32)    PRIMARY KEY,
  order_id      VARCHAR(32),
  order_no      VARCHAR(30),
  goods_code    VARCHAR(50)    NOT NULL,
  goods_name    VARCHAR(200),
  price_group_code VARCHAR(50) NOT NULL,
  price_group_name VARCHAR(100),
  unit_level    TINYINT        NOT NULL,
  old_price     DECIMAL(14,4),
  new_price     DECIMAL(14,4)  NOT NULL,
  change_type   VARCHAR(30)    NOT NULL COMMENT 'ADJUST_INIT / ADJUST_UPDATE',
  operator      VARCHAR(50),
  remark        VARCHAR(500),
  created_at    TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_pclog_group ON base_price_change_log(price_group_code);
CREATE INDEX idx_pclog_goods ON base_price_change_log(goods_code);
CREATE INDEX idx_pclog_time  ON base_price_change_log(created_at);
