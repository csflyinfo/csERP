-- =====================================================================
-- V99 总账 M8：固定资产（下）——变更留痕 / 清理 / 盘点
--   1. fin_asset_change  资产变更记录：部门转移、费用科目/折旧参数调整（原值禁改），
--      拆分/合并也在此留痕（不生凭证）；
--   2. fin_asset_disposal 资产清理单：1606 固定资产清理过渡，损益入 5301 营业外收入
--      / 5711 营业外支出，生成 QL{清理单号} 转字凭证草稿（幂等）；
--   3. fin_asset_check 资产盘点单：明细以 CLOB JSON 存储，盘亏/盘盈只留痕、不生凭证、
--      不改卡片状态（清理动作由会计在资产卡片上另行发起）。
-- 卡片状态扩展：使用中/已停用/已清理/已拆分/已合并（V98 已建 status 列，此处仅注释说明）。
-- 全部 CREATE/MERGE 幂等，无外键、无 DROP。
-- =====================================================================

-- ---------- 资产变更记录 ----------
CREATE TABLE IF NOT EXISTS fin_asset_change (
    change_id          VARCHAR(32)  PRIMARY KEY,
    change_no          VARCHAR(40)  NOT NULL,
    card_id            VARCHAR(32)  NOT NULL,
    card_no            VARCHAR(40),
    change_type        VARCHAR(20)  NOT NULL,          -- 信息变更/拆分/合并
    before_json        CLOB,                            -- 变更前快照
    after_json         CLOB,                            -- 变更后快照/拆分合并明细
    reason             VARCHAR(200),
    maker_name         VARCHAR(50),
    create_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_asset_change_card ON fin_asset_change(card_id);
CREATE INDEX IF NOT EXISTS idx_fin_asset_change_no ON fin_asset_change(change_no);

-- ---------- 资产清理单 ----------
CREATE TABLE IF NOT EXISTS fin_asset_disposal (
    disposal_id        VARCHAR(32)  PRIMARY KEY,
    disposal_no        VARCHAR(40)  NOT NULL,
    card_id            VARCHAR(32)  NOT NULL,
    card_no            VARCHAR(40),
    asset_name         VARCHAR(100),
    period             VARCHAR(6),
    original_value     DECIMAL(18,2) DEFAULT 0,         -- 清理时原值
    accum_depreciation DECIMAL(18,2) DEFAULT 0,         -- 清理时累计折旧
    net_value          DECIMAL(18,2) DEFAULT 0,         -- 清理时净值
    income_amount      DECIMAL(18,2) DEFAULT 0,         -- 清理收入
    expense_amount     DECIMAL(18,2) DEFAULT 0,         -- 清理费用
    gain_loss          DECIMAL(18,2) DEFAULT 0,         -- 损益（贷正借负：收益为正/损失为负）
    result_type        VARCHAR(10),                     -- 收益/损失/持平
    cash_account       VARCHAR(20),                     -- 收入/费用对方科目（现金类）
    voucher_id         VARCHAR(32),
    status             VARCHAR(10)  DEFAULT '已清理',   -- 已清理
    remark             VARCHAR(200),
    maker_name         VARCHAR(50),
    make_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_asset_disposal_card ON fin_asset_disposal(card_id);
CREATE INDEX IF NOT EXISTS idx_fin_asset_disposal_period ON fin_asset_disposal(period);
CREATE INDEX IF NOT EXISTS idx_fin_asset_disposal_no ON fin_asset_disposal(disposal_no);

-- ---------- 资产盘点单 ----------
CREATE TABLE IF NOT EXISTS fin_asset_check (
    check_id           VARCHAR(32)  PRIMARY KEY,
    check_no           VARCHAR(40)  NOT NULL,
    period             VARCHAR(6)   NOT NULL,
    department_code    VARCHAR(50),
    department_name    VARCHAR(100),
    total_count        INT          DEFAULT 0,
    loss_count         INT          DEFAULT 0,          -- 盘亏数
    profit_count       INT          DEFAULT 0,          -- 盘盈数（账外资产登记）
    detail_json        CLOB,                            -- 明细：[{cardId,cardNo,assetName,bookStatus,checkResult,remark}]
    status             VARCHAR(10)  DEFAULT '已完成',
    remark             VARCHAR(200),
    maker_name         VARCHAR(50),
    make_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_fin_asset_check_period ON fin_asset_check(period);
CREATE INDEX IF NOT EXISTS idx_fin_asset_check_no ON fin_asset_check(check_no);
