-- =============================================================================
-- V121 业务日结补充：商品收发存定版日余额（PRD-33 补充件 v1.4，Q1 含签收口径列）
-- 粒度：日 + 商品 + 仓库（Q2 终审：不到批次级）
-- 与三张往来资金定版同生命周期：结账事务内先删后插、反结物理删除、重算不可改。
-- 分类口径与报表 #8《商品进销存汇总表》完全一致（rpt_dws_stock_move_d 透视）。
-- H2(MODE=MySQL)/MySQL 双跑：单引号、IF NOT EXISTS、蛇形别名。
-- =============================================================================

CREATE TABLE IF NOT EXISTS biz_close_goods_daily (
    close_date                 DATE NOT NULL,
    goods_code                 VARCHAR(50) NOT NULL,
    warehouse                  VARCHAR(100) NOT NULL,
    -- 档案快照（结账时刻值，事后改名/改分类不动历史）
    goods_name                 VARCHAR(200),
    spec                       VARCHAR(200),
    barcode                    VARCHAR(100),
    base_unit                  VARCHAR(50),
    large_unit                 VARCHAR(50),
    large_convert_qty          DECIMAL(18, 4),
    brand_name                 VARCHAR(100),
    category_name              VARCHAR(100),
    storage_property           VARCHAR(50),
    -- 期初（= 前一已结日期末；首次日结为 0，由期初入库单审核+期初确认背书）
    opening_qty                DECIMAL(18, 4) DEFAULT 0,
    opening_amount             DECIMAL(18, 2) DEFAULT 0,
    -- 本期收入（按单据类型分类，口径同报表 #8）
    purchase_in_qty            DECIMAL(18, 4) DEFAULT 0,   -- CGRK/WMS_INBOUND 采购入库
    purchase_in_amount         DECIMAL(18, 2) DEFAULT 0,
    sales_return_in_qty        DECIMAL(18, 4) DEFAULT 0,   -- THRK 销售退货入库
    sales_return_in_amount     DECIMAL(18, 2) DEFAULT 0,
    other_in_qty               DECIMAL(18, 4) DEFAULT 0,   -- QTRK/JSRK/PDD盘盈/WMS_ADJUST_GAIN/OTHER
    other_in_amount            DECIMAL(18, 2) DEFAULT 0,
    transfer_in_qty            DECIMAL(18, 4) DEFAULT 0,   -- DBRK 调入
    transfer_in_amount         DECIMAL(18, 2) DEFAULT 0,
    in_qty                     DECIMAL(18, 4) DEFAULT 0,   -- 收入小计
    in_amount                  DECIMAL(18, 2) DEFAULT 0,
    adjust_amount              DECIMAL(18, 2) DEFAULT 0,   -- 成本调整金额（数量不动）
    -- 本期发出
    sales_out_qty              DECIMAL(18, 4) DEFAULT 0,   -- XSCK 销售出库
    sales_out_amount           DECIMAL(18, 2) DEFAULT 0,
    purchase_return_out_qty    DECIMAL(18, 4) DEFAULT 0,   -- CTCK 采购退货出库
    purchase_return_out_amount DECIMAL(18, 2) DEFAULT 0,
    other_out_qty              DECIMAL(18, 4) DEFAULT 0,   -- QTCK/BSD报损/PDD盘亏/OTHER
    other_out_amount           DECIMAL(18, 2) DEFAULT 0,
    transfer_out_qty           DECIMAL(18, 4) DEFAULT 0,   -- DBCK 调出
    transfer_out_amount        DECIMAL(18, 2) DEFAULT 0,
    out_qty                    DECIMAL(18, 4) DEFAULT 0,   -- 发出小计
    out_amount                 DECIMAL(18, 2) DEFAULT 0,
    -- 业务账（签收确认口径，与实物发出并列冻结、互不勾稽；Q1 采纳同期冻结）
    signed_qty                 DECIMAL(18, 4) DEFAULT 0,   -- 签收净额=签收-退货(基本数量)
    signed_amount              DECIMAL(18, 2) DEFAULT 0,   -- 签收含税净额
    signed_cost_amount         DECIMAL(18, 2) DEFAULT 0,   -- 签收成本净额
    gross_profit               DECIMAL(18, 2) DEFAULT 0,   -- 毛利=签收净额-成本净额
    -- 期末（结账时刻 inv_stock_balance 实物）
    ending_qty                 DECIMAL(18, 4) DEFAULT 0,
    ending_amount              DECIMAL(18, 2) DEFAULT 0,
    ending_cost_price          DECIMAL(18, 6),             -- 期末移动加权单位成本
    -- 勾稽（恒等式与第 5 步硬门同源；不平直接回滚，理论上不会有 N 行进表）
    qty_diff                   DECIMAL(18, 4) DEFAULT 0,
    amount_diff                DECIMAL(18, 2) DEFAULT 0,
    tie_flag                   CHAR(1) DEFAULT 'Y',
    negative_flag              CHAR(1) DEFAULT 'N',        -- 日终负库存标志
    frozen_time                TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (close_date, goods_code, warehouse)
);

CREATE INDEX IF NOT EXISTS idx_biz_close_goods_goods
    ON biz_close_goods_daily(goods_code, close_date);
CREATE INDEX IF NOT EXISTS idx_biz_close_goods_wh
    ON biz_close_goods_daily(warehouse, close_date);
