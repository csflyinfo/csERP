-- =============================================================================
-- V120 业务日结（按日封单）PRD-33
-- ①~⑤ 日结主记录/只增日志/三类定版日余额
-- ⑥   遗留单据补业务日期列
-- ⑦⑧ 动态定时任务定义/执行日志（本期注册“业务日结”一个任务）
-- ⑨   系统参数 P0184~P0188
-- 全部 H2(MODE=MySQL)/MySQL 双跑：单引号字符串、IF NOT EXISTS、MERGE...KEY
-- =============================================================================

-- ① 日结主记录（每个当前已结日期一行；反结删行，痕迹在日志表） -----------------
CREATE TABLE IF NOT EXISTS biz_day_close (
    id                  VARCHAR(32) PRIMARY KEY,
    close_no            VARCHAR(20),                 -- 日结单号 RJ-yyyyMMdd
    close_date          DATE NOT NULL UNIQUE,
    scope               VARCHAR(10) DEFAULT 'ALL',   -- 预留：一期恒 ALL
    check_result        CLOB,                        -- 向导各步结果 JSON（对账值/提示项/期初确认/资金备注）
    dws_balanced        CHAR(1) DEFAULT 'Y',
    tie_balanced        CHAR(1) DEFAULT 'Y',         -- 四套滚存勾稽是否均平（硬项）
    fund_balanced       CHAR(1) DEFAULT 'Y',         -- 流水末笔 vs 账户档案余额是否一致（仅备注，不阻断）
    -- 日结单关键合计（取数口径与 DWS/台账一致，金额含税、两位小数）
    sales_signed_amount DECIMAL(18,2) DEFAULT 0,     -- 当日签收金额（收入口径）
    receipt_amount      DECIMAL(18,2) DEFAULT 0,     -- 当日收款核销
    purchase_amount     DECIMAL(18,2) DEFAULT 0,     -- 当日采购入库金额
    payment_amount      DECIMAL(18,2) DEFAULT 0,     -- 当日付款
    stock_in_amount     DECIMAL(18,2) DEFAULT 0,     -- 当日入库成本合计
    stock_out_amount    DECIMAL(18,2) DEFAULT 0,     -- 当日出库成本合计
    pending_bill_count  INT DEFAULT 0,               -- 跨阶挂账单总条数
    opening_confirmed   CHAR(1) DEFAULT 'N',         -- 首次日结的往来/资金期初核对勾选
    fund_remark         VARCHAR(1000),               -- 第 6 步资金情况说明（结账人手填备注）
    close_name          VARCHAR(50),
    close_time          TIMESTAMP,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ② 日结操作日志（只增不改不删：日结/反日结/重新日结全生命周期可追溯） -----------
CREATE TABLE IF NOT EXISTS biz_day_close_log (
    log_id          VARCHAR(32) PRIMARY KEY,
    close_date      DATE NOT NULL,
    action          VARCHAR(10) NOT NULL,           -- CLOSE / REOPEN
    result          VARCHAR(10) NOT NULL,           -- SUCCESS / FAIL
    operator_name   VARCHAR(50),
    operate_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    reason          VARCHAR(500),                   -- 反日结原因
    batch_no        VARCHAR(32),                    -- 批量反结同一批次号
    trigger_type    VARCHAR(10) DEFAULT 'MANUAL',   -- MANUAL / AUTO
    detail          CLOB,                           -- 对账摘要/失败原因
    biz_id          VARCHAR(32)
);
CREATE INDEX IF NOT EXISTS idx_biz_close_log_date ON biz_day_close_log(close_date);

-- ③ 客户应收日结余额 -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS biz_close_ar_daily (
    close_date        DATE NOT NULL,
    customer_code     VARCHAR(32) NOT NULL,
    customer_name     VARCHAR(100) NOT NULL,
    ar_amount         DECIMAL(18,2) DEFAULT 0,      -- 应收总额（含税，含退货红负，净额）
    received_amount   DECIMAL(18,2) DEFAULT 0,
    unreceived_amount DECIMAL(18,2) DEFAULT 0,
    advance_amount    DECIMAL(18,2) DEFAULT 0,      -- 预收重分类：期末负余额绝对值
    overdue_amount    DECIMAL(18,2) DEFAULT 0,      -- 截至日已到期未收（负余额不计逾期）
    bill_count        INT DEFAULT 0,
    PRIMARY KEY (close_date, customer_code)
);

-- ④ 供应商应付日结余额 ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS biz_close_ap_daily (
    close_date      DATE NOT NULL,
    supplier_code   VARCHAR(32) NOT NULL,
    supplier_name   VARCHAR(100) NOT NULL,
    ap_amount       DECIMAL(18,2) DEFAULT 0,
    paid_amount     DECIMAL(18,2) DEFAULT 0,
    unpaid_amount   DECIMAL(18,2) DEFAULT 0,
    prepaid_amount  DECIMAL(18,2) DEFAULT 0,        -- 预付重分类：期末负余额绝对值
    overdue_amount  DECIMAL(18,2) DEFAULT 0,
    bill_count      INT DEFAULT 0,
    PRIMARY KEY (close_date, supplier_code)
);

-- ⑤ 资金账户日结余额（归属日取收付款单记账日期） --------------------------------
CREATE TABLE IF NOT EXISTS biz_close_fund_daily (
    close_date        DATE NOT NULL,
    fund_account_code VARCHAR(64) NOT NULL,         -- 账户编码（不可变维度；无编码档案时存账户名兜底）
    fund_account_name VARCHAR(100) NOT NULL,        -- 账户名称快照
    open_balance      DECIMAL(18,2) DEFAULT 0,
    in_amount         DECIMAL(18,2) DEFAULT 0,      -- 当日收入合计（含取消审核红字行）
    out_amount        DECIMAL(18,2) DEFAULT 0,      -- 当日支出合计
    close_balance     DECIMAL(18,2) DEFAULT 0,
    cash_count        DECIMAL(18,2),                -- 现金实盘数（备注项，可空）
    PRIMARY KEY (close_date, fund_account_code)
);

-- ⑥ 遗留单据补业务日期列（审核回填，历史行取创建日） -----------------------------
ALTER TABLE biz_simple_bill ADD COLUMN IF NOT EXISTS bill_date DATE;
UPDATE biz_simple_bill SET bill_date = CAST(created_at AS DATE) WHERE bill_date IS NULL;

-- ⑦ 定时任务定义（代码注册 handler，数据库管 cron/启停，不允许任意脚本） ----------
CREATE TABLE IF NOT EXISTS sys_scheduled_task (
    task_code        VARCHAR(64) PRIMARY KEY,       -- BIZ_DAY_CLOSE
    task_name        VARCHAR(100) NOT NULL,
    handler_bean     VARCHAR(100) NOT NULL,         -- Spring Bean 名，白名单调用
    cron_expr        VARCHAR(128) NOT NULL,         -- Spring 6 段 cron
    enabled          CHAR(1) DEFAULT 'Y',
    last_fire_time   TIMESTAMP,
    last_finish_time TIMESTAMP,
    last_result      VARCHAR(10),                   -- SUCCESS/FAIL/RUNNING
    last_message     VARCHAR(1000),
    remark           VARCHAR(255),
    update_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ⑧ 定时任务执行日志（自动/手动触发都记，保留 90 天） ----------------------------
CREATE TABLE IF NOT EXISTS sys_scheduled_task_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_code     VARCHAR(64) NOT NULL,
    trigger_type  VARCHAR(10) NOT NULL,             -- AUTO/MANUAL
    operator_name VARCHAR(50),
    start_time    TIMESTAMP NOT NULL,
    finish_time   TIMESTAMP,
    result        VARCHAR(10),                      -- SUCCESS/FAIL
    message       CLOB
);
CREATE INDEX IF NOT EXISTS idx_schedule_log_task ON sys_scheduled_task_log(task_code, start_time);

-- 注册业务日结任务（默认每天 02:30；方式=自动且总开关启用时才真正日结） -----------
MERGE INTO sys_scheduled_task (task_code, task_name, handler_bean, cron_expr, enabled, remark)
KEY(task_code) VALUES
('BIZ_DAY_CLOSE','业务日结','bizDayCloseJob','0 30 2 * * ?','Y','每日凌晨自动日结（补结封单日次日至昨日；参数 BIZ_DAY_CLOSE_ENABLED=Y 且方式=自动时生效）');

-- ⑨ 系统参数 P0184~P0188（BOOL 统一 Y/N，getBool 只认 Y） ------------------------
INSERT INTO sys_param_runtime
  (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0184','BIZ_DAY_CLOSE_ENABLED','启用业务日结封单','Y','Y','业务日结','BOOL','[{"value":"Y","label":"启用"},{"value":"N","label":"停用"}]',184,NULL,NULL,NULL,
       'N 时封单守卫全部放行（紧急排障用），切换动作记操作日志；不影响已生成的日结记录'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key = 'BIZ_DAY_CLOSE_ENABLED');

INSERT INTO sys_param_runtime
  (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0185','BIZ_DAY_CLOSE_MODE','日结方式','AUTO','AUTO','业务日结','SELECT','[{"value":"AUTO","label":"自动日结"},{"value":"MANUAL","label":"手动日结"}]',185,NULL,NULL,NULL,
       '默认自动；自动还需定时任务处于启用状态。手动方式下定时任务到点只记“方式为手动，跳过”日志'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key = 'BIZ_DAY_CLOSE_MODE');

INSERT INTO sys_param_runtime
  (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0186','BIZ_GL_CLOSE_REQUIRE_DAY_CLOSE','总账月结要求本期业务已全部日结','Y','Y','业务日结','BOOL','[{"value":"Y","label":"硬拦截"},{"value":"N","label":"仅提示"}]',186,NULL,NULL,NULL,
       '月结向导发现本期（月初至结账当天）存在未日结日期时，Y=禁止月结并列日期清单，N=仅警告'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key = 'BIZ_GL_CLOSE_REQUIRE_DAY_CLOSE');

INSERT INTO sys_param_runtime
  (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0187','BIZ_CASH_ACCOUNT_NAMES','现金类账户名单','','','业务日结','TEXT',NULL,187,NULL,NULL,NULL,
       '资金账户名称，多个用英文逗号分隔；名单内账户在日结向导第 6 步展示账面余额并可录实盘数（仅备注，不阻断结账）；为空则不展示实盘录入'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key = 'BIZ_CASH_ACCOUNT_NAMES');

INSERT INTO sys_param_runtime
  (param_id, param_key, param_name, param_value, default_value, param_group, param_type, option_json, sort_no, min_value, max_value, unit, remark)
SELECT 'P0188','BIZ_DAY_CLOSE_LATE_HOUR','未日结提醒时刻（小时）','10','10','业务日结','NUMBER',NULL,188,6,23,'时',
       '过此时刻昨日仍未日结（或最近一次自动日结失败），工作台首页显示红色待办'
WHERE NOT EXISTS (SELECT 1 FROM sys_param_runtime WHERE param_key = 'BIZ_DAY_CLOSE_LATE_HOUR');
