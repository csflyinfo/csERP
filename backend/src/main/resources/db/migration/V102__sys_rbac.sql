-- ============================================================
-- V102: RBAC 权限体系（PRD-28）
--   用户/角色/菜单/功能/字段/数据范围 四维权限底座
--   约定：
--   1) 全部用 IF NOT EXISTS / ON DUPLICATE KEY，可在 flyway:repair 后重复执行；
--   2) 旧表旧列一律保留，只增不删（H2 不支持 DROP COLUMN IF EXISTS）；
--   3) 菜单树与模块功能点不在本脚本播种——由 PermissionRegistry 启动时
--      扫描 MenuConfig/@RequirePerm 自动 upsert（见方案 §6.4）；
--   4) 本脚本只建结构 + 内置角色 + 字段字典 + 全局功能 + 存量账号迁移。
-- ============================================================

-- ------------------------------------------------------------
-- 5.2.1 用户表扩列
-- ------------------------------------------------------------
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS email VARCHAR(100);
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS employee_id VARCHAR(32);
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS avatar VARCHAR(255);
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS primary_role_id VARCHAR(32);
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS pwd_update_time TIMESTAMP;
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS last_login_time TIMESTAMP;
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS last_login_ip VARCHAR(50);
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS fail_count INT DEFAULT 0;
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS lock_time TIMESTAMP;
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS must_change_pwd BOOLEAN DEFAULT FALSE;
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS created_by VARCHAR(32);
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) DEFAULT 'DEFAULT';   -- V3.0 预留
ALTER TABLE sys_user_runtime ADD COLUMN IF NOT EXISTS is_system BOOLEAN DEFAULT FALSE;         -- 内置账号不可删
-- 旧列 role_name 降级为"主角色名"冗余展示；data_scope 保留兼容，新逻辑走关联表。

-- ------------------------------------------------------------
-- 5.2.2 角色表扩列
-- ------------------------------------------------------------
ALTER TABLE sys_role_runtime ADD COLUMN IF NOT EXISTS role_group VARCHAR(20) DEFAULT 'BIZ';    -- SYS/BIZ/WMS/TMS
ALTER TABLE sys_role_runtime ADD COLUMN IF NOT EXISTS app_type VARCHAR(20) DEFAULT 'ERP';      -- ERP/WMS_PDA/DRIVER/ALL
ALTER TABLE sys_role_runtime ADD COLUMN IF NOT EXISTS is_system BOOLEAN DEFAULT FALSE;
ALTER TABLE sys_role_runtime ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
ALTER TABLE sys_role_runtime ADD COLUMN IF NOT EXISTS created_by VARCHAR(32);
ALTER TABLE sys_role_runtime ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE sys_role_runtime ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE sys_role_runtime ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) DEFAULT 'DEFAULT';

-- ------------------------------------------------------------
-- 5.2.3 用户-角色关联（多对多）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user_role_rel (
  id VARCHAR(32) PRIMARY KEY,
  user_id VARCHAR(32) NOT NULL,
  role_id VARCHAR(32) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);
CREATE INDEX IF NOT EXISTS idx_ur_user ON sys_user_role_rel(user_id);
CREATE INDEX IF NOT EXISTS idx_ur_role ON sys_user_role_rel(role_id);

-- 5.2.4 用户-绑定仓库（PDA 登录选仓 / 多仓收窄）
CREATE TABLE IF NOT EXISTS sys_user_warehouse (
  id VARCHAR(32) PRIMARY KEY,
  user_id VARCHAR(32) NOT NULL,
  warehouse_id VARCHAR(32) NOT NULL,
  is_primary BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_user_wh UNIQUE (user_id, warehouse_id)
);

-- 5.2.5 菜单/页面元数据
--   admin_only=TRUE        仅 SYS_ADMIN 可见，角色授权树不展示、不可授权
--   *_customized=TRUE      管理员在【模块菜单管理】改过，代码同步不再覆盖
--   层级（服务层强校验）：L1=DIR，L2=DIR/PAGE，L3 只能 PAGE；PAGE 不能有下级。
CREATE TABLE IF NOT EXISTS sys_menu_meta (
  menu_id VARCHAR(32) PRIMARY KEY,
  parent_id VARCHAR(32),
  app_type VARCHAR(20) NOT NULL,
  menu_code VARCHAR(100) NOT NULL,
  menu_name VARCHAR(100) NOT NULL,
  menu_type VARCHAR(20) NOT NULL,          -- DIR/MENU/PAGE/GROUP
  route_path VARCHAR(200),
  component_path VARCHAR(200),
  icon VARCHAR(100),
  sort_order INT DEFAULT 0,
  visible BOOLEAN DEFAULT TRUE,
  admin_only BOOLEAN DEFAULT FALSE,
  name_customized BOOLEAN DEFAULT FALSE,
  parent_customized BOOLEAN DEFAULT FALSE,
  sort_customized BOOLEAN DEFAULT FALSE,
  is_system BOOLEAN DEFAULT FALSE,
  status VARCHAR(20) DEFAULT 'NORMAL',     -- NORMAL/STOPPED
  remark VARCHAR(500),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_menu_code ON sys_menu_meta(menu_code);
CREATE INDEX IF NOT EXISTS idx_menu_app_parent ON sys_menu_meta(app_type, parent_id);

-- 5.2.6 功能点（按钮/动作/API）
--   func_scope=GLOBAL 跨模块（挂占位菜单 M_GLOBAL_FUNC）；MODULE 仅作用于所属菜单。
CREATE TABLE IF NOT EXISTS sys_func_meta (
  func_id VARCHAR(32) PRIMARY KEY,
  menu_id VARCHAR(32) NOT NULL,
  func_code VARCHAR(100) NOT NULL,
  func_name VARCHAR(100) NOT NULL,
  func_scope VARCHAR(10) NOT NULL DEFAULT 'MODULE',   -- GLOBAL/MODULE
  func_type VARCHAR(20) NOT NULL,                     -- BUTTON/API/ACTION
  api_method VARCHAR(10),
  api_path VARCHAR(200),
  sort_order INT DEFAULT 0,
  is_system BOOLEAN DEFAULT FALSE,
  status VARCHAR(20) DEFAULT 'NORMAL',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_func_code UNIQUE (func_code)
);

-- 5.2.7 字段权限元数据（敏感字段全局字典）
CREATE TABLE IF NOT EXISTS sys_field_meta (
  field_id VARCHAR(32) PRIMARY KEY,
  field_code VARCHAR(100) NOT NULL UNIQUE,
  field_name VARCHAR(100) NOT NULL,
  field_group VARCHAR(20) NOT NULL DEFAULT 'AMOUNT',  -- PRICE/AMOUNT/STOCK/CONTACT
  module_scope VARCHAR(500),
  remark VARCHAR(500),
  status VARCHAR(20) DEFAULT 'NORMAL'
);

-- 5.2.8/9/10 角色授权关联
CREATE TABLE IF NOT EXISTS sys_role_menu_rel (
  id VARCHAR(32) PRIMARY KEY,
  role_id VARCHAR(32) NOT NULL,
  menu_id VARCHAR(32) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_role_menu UNIQUE (role_id, menu_id)
);
CREATE TABLE IF NOT EXISTS sys_role_func_rel (
  id VARCHAR(32) PRIMARY KEY,
  role_id VARCHAR(32) NOT NULL,
  func_id VARCHAR(32) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_role_func UNIQUE (role_id, func_id)
);
CREATE TABLE IF NOT EXISTS sys_role_field_rel (
  id VARCHAR(32) PRIMARY KEY,
  role_id VARCHAR(32) NOT NULL,
  field_id VARCHAR(32) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_role_field UNIQUE (role_id, field_id)
);

-- 5.2.11 角色数据范围
--   scope_type: WAREHOUSE/CUSTOMER/SUPPLIER/SALESMAN/OWNER/GOODS_CATEGORY/BRAND
--   scope_value: ALL / SELF / SUB_TREE / id1,id2
CREATE TABLE IF NOT EXISTS sys_role_data_scope (
  id VARCHAR(32) PRIMARY KEY,
  role_id VARCHAR(32) NOT NULL,
  scope_type VARCHAR(30) NOT NULL,
  scope_value VARCHAR(2000) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_role_scope UNIQUE (role_id, scope_type)
);

-- 5.2.12 用户数据范围收窄（与角色结果取交集，只能更窄）
CREATE TABLE IF NOT EXISTS sys_user_data_scope (
  id VARCHAR(32) PRIMARY KEY,
  user_id VARCHAR(32) NOT NULL,
  scope_type VARCHAR(30) NOT NULL,
  scope_value VARCHAR(2000) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_user_scope UNIQUE (user_id, scope_type)
);

-- 5.2.13 登录日志
CREATE TABLE IF NOT EXISTS sys_login_log_runtime (
  log_id VARCHAR(32) PRIMARY KEY,
  username VARCHAR(50),
  app_type VARCHAR(20),
  login_type VARCHAR(20),
  login_ip VARCHAR(50),
  device_info VARCHAR(300),
  status VARCHAR(20),                       -- SUCCESS/FAIL/LOCKED/DISABLED
  fail_reason VARCHAR(200),
  login_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_loginlog_user ON sys_login_log_runtime(username, login_at);

-- 5.2.14 短信验证码（司机登录，替代硬编码 888888）
CREATE TABLE IF NOT EXISTS sys_sms_code (
  id VARCHAR(32) PRIMARY KEY,
  mobile VARCHAR(20) NOT NULL,
  code VARCHAR(10) NOT NULL,
  biz_type VARCHAR(20) DEFAULT 'LOGIN',
  used BOOLEAN DEFAULT FALSE,
  expire_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sms_mobile ON sys_sms_code(mobile, created_at);

-- 5.2.15 操作日志扩列
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS user_id VARCHAR(32);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS app_type VARCHAR(20);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS ip VARCHAR(50);
ALTER TABLE sys_operation_log_runtime ADD COLUMN IF NOT EXISTS request_url VARCHAR(300);

-- ============================================================
-- 种子一：内置角色（21 个，方案 §12.1）
-- ============================================================
INSERT INTO sys_role_runtime (role_id, role_code, role_name, role_group, app_type, is_system, data_scope, status, remark)
VALUES
 ('R_SYS_ADMIN','SYS_ADMIN','系统管理员','SYS','ALL',TRUE,'ALL','NORMAL','内置超管，不可停用'),
 ('R_SYS_VIEWER','SYS_VIEWER','只读观察员','SYS','ALL',TRUE,'ALL','NORMAL','全模块只读'),
 ('R_BIZ_OWNER','BIZ_OWNER','老板/管理层','BIZ','ERP',FALSE,'ALL','NORMAL',''),
 ('R_PUR_MGR','PURCHASE_MANAGER','采购主管','BIZ','ERP',FALSE,'ALL','NORMAL',''),
 ('R_PUR_BUYER','PURCHASE_BUYER','采购员','BIZ','ERP',FALSE,'SELF','NORMAL',''),
 ('R_SAL_MGR','SALES_MANAGER','销售主管','BIZ','ERP',FALSE,'ALL','NORMAL',''),
 ('R_SAL_CLERK','SALES_CLERK','销售员','BIZ','ERP',FALSE,'SELF','NORMAL',''),
 ('R_WH_MGR','WAREHOUSE_MANAGER','仓库主管','BIZ','ERP',FALSE,'SELF','NORMAL',''),
 ('R_WH_KEEPER','WAREHOUSE_KEEPER','仓管员','BIZ','ERP',FALSE,'SELF','NORMAL',''),
 ('R_FIN_MGR','FINANCE_MANAGER','财务主管','BIZ','ERP',FALSE,'ALL','NORMAL',''),
 ('R_FIN_CLERK','FINANCE_CLERK','财务出纳','BIZ','ERP',FALSE,'ALL','NORMAL',''),
 ('R_WMS_RECEIVER','WMS_RECEIVER','收货员','WMS','WMS_PDA',FALSE,'SELF','NORMAL',''),
 ('R_WMS_PUTAWAY','WMS_PUTAWAY','上架员','WMS','WMS_PDA',FALSE,'SELF','NORMAL',''),
 ('R_WMS_PICKER','WMS_PICKER','拣货员','WMS','WMS_PDA',FALSE,'SELF','NORMAL',''),
 ('R_WMS_CHECKER','WMS_CHECKER','复核员','WMS','WMS_PDA',FALSE,'SELF','NORMAL',''),
 ('R_WMS_KEEPER','WMS_KEEPER','仓管员(多面手)','WMS','WMS_PDA',FALSE,'SELF','NORMAL',''),
 ('R_WMS_LEADER','WMS_LEADER','仓库主管(PDA)','WMS','WMS_PDA',FALSE,'ALL','NORMAL',''),
 ('R_TMS_DISPATCHER','TMS_DISPATCHER','调度员','TMS','ERP',FALSE,'ALL','NORMAL',''),
 ('R_TMS_DRIVER','TMS_DRIVER','司机','TMS','DRIVER',FALSE,'SELF','NORMAL',''),
 ('R_TMS_LOADER','TMS_LOADER','装车员','TMS','DRIVER',FALSE,'SELF','NORMAL','仅装车，无签收/结算'),
 ('R_TMS_LEADER','TMS_LEADER','配送主管','TMS','ALL',FALSE,'ALL','NORMAL','')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), role_group = VALUES(role_group),
  app_type = VALUES(app_type), is_system = VALUES(is_system), remark = VALUES(remark);

-- 存量 R0001 管理员组标记为内置（用户关系在下面迁移到 R_SYS_ADMIN）
UPDATE sys_role_runtime SET role_group='SYS', app_type='ALL', is_system=TRUE
WHERE role_code='ADMIN' AND role_id='R0001';

-- ============================================================
-- 种子二：字段权限字典（26 个，方案 §12.3）
-- ============================================================
INSERT INTO sys_field_meta (field_id, field_code, field_name, field_group, remark) VALUES
 ('F_SALE_PRICE','VIEW_SALE_PRICE','查看销售价','PRICE','标准售价/销售单价'),
 ('F_SALE_AMOUNT','VIEW_SALE_AMOUNT','查看销售金额','PRICE','销售金额/价税合计'),
 ('F_PUR_PRICE','VIEW_PURCHASE_PRICE','查看采购价','PRICE','参考进价/最新进价'),
 ('F_PUR_AMOUNT','VIEW_PURCHASE_AMOUNT','查看采购金额','PRICE','采购金额/价税合计'),
 ('F_COST','VIEW_COST','查看成本价','PRICE','成本单价/出库成本'),
 ('F_COST_AMT','VIEW_COST_AMOUNT','查看库存成本金额','PRICE','库存余额金额'),
 ('F_PROFIT','VIEW_PROFIT','查看毛利','PRICE','毛利额/毛利率'),
 ('F_MIN_PRICE','VIEW_MIN_PRICE','查看最低售价','PRICE','低价控制线'),
 ('F_RETAIL_PRICE','VIEW_SUGGEST_RETAIL_PRICE','查看建议零售价','PRICE',''),
 ('F_PRICE_GROUP','VIEW_PRICE_GROUP','查看价格组价格','PRICE','配送价/价格组调价'),
 ('F_CUST_PRICE','VIEW_CUSTOMER_PRICE','查看客户价格','PRICE','客户专属价'),
 ('F_AR','VIEW_AR_BALANCE','查看客户应收/欠款','AMOUNT','应收余额/账龄'),
 ('F_AP','VIEW_AP_BALANCE','查看供应商应付/余额','AMOUNT','应付余额'),
 ('F_PRE','VIEW_PRE_RECEIVED','查看预收/预付','AMOUNT','预收款/预付款余额'),
 ('F_FUND_BAL','VIEW_FUND_ACCOUNT_BALANCE','查看资金账户余额','AMOUNT','现金/银行余额'),
 ('F_FUND_FLOW','VIEW_FUND_FLOW','查看资金流水','AMOUNT','收支明细'),
 ('F_CREDIT','VIEW_CREDIT_LIMIT','查看信用额度','AMOUNT','客户信用额度'),
 ('F_SETTLE','VIEW_SETTLE_DETAIL','查看结算/核销明细','AMOUNT','核销/交账明细'),
 ('F_PROFIT_TOTAL','VIEW_PROFIT_TOTAL','查看经营利润','AMOUNT','利润表/净利'),
 ('F_STOCK_QTY','VIEW_STOCK_AMOUNT','查看库存数量','STOCK','库存余额/可用量'),
 ('F_STOCK_LOCK','VIEW_STOCK_LOCK','查看锁定库存','STOCK','订单锁定量'),
 ('F_STOCK_COST','VIEW_STOCK_COST','查看库存金额','STOCK','数量×成本'),
 ('F_GOODS_SUPPLIER','VIEW_SUPPLIER_OF_GOODS','查看商品供应商','STOCK','默认供应商/采购来源'),
 ('F_CUST_MOBILE','VIEW_CUSTOMER_MOBILE','查看客户电话','CONTACT','客户联系人手机'),
 ('F_SUP_MOBILE','VIEW_SUPPLIER_MOBILE','查看供应商电话','CONTACT','供应商联系人手机'),
 ('F_DRV_MOBILE','VIEW_DRIVER_MOBILE','查看司机电话','CONTACT','司机联系方式')
ON DUPLICATE KEY UPDATE field_name = VALUES(field_name), field_group = VALUES(field_group), remark = VALUES(remark);

-- ============================================================
-- 种子三：全局功能占位菜单 + 20 个全局功能（方案 §12.4）
-- ============================================================
INSERT INTO sys_menu_meta (menu_id, parent_id, app_type, menu_code, menu_name, menu_type, sort_order, is_system, status)
VALUES ('M_GLOBAL_FUNC', NULL, 'ALL', '_global_', '全局功能', 'GROUP', 9999, TRUE, 'NORMAL')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name);

INSERT INTO sys_func_meta (func_id, menu_id, func_code, func_name, func_scope, func_type, sort_order, is_system, status) VALUES
 ('GF_EXPORT',     'M_GLOBAL_FUNC','global.export',                  '数据导出',      'GLOBAL','BUTTON', 10, TRUE,'NORMAL'),
 ('GF_IMPORT',     'M_GLOBAL_FUNC','global.import',                  '数据导入',      'GLOBAL','BUTTON', 20, TRUE,'NORMAL'),
 ('GF_PRINT',      'M_GLOBAL_FUNC','global.print',                   '打印',          'GLOBAL','BUTTON', 30, TRUE,'NORMAL'),
 ('GF_PRINT_TPL',  'M_GLOBAL_FUNC','global.print_template',          '打印模板设计',  'GLOBAL','BUTTON', 31, TRUE,'NORMAL'),
 ('GF_AUDIT',      'M_GLOBAL_FUNC','global.audit',                   '审核',          'GLOBAL','BUTTON', 40, TRUE,'NORMAL'),
 ('GF_UNAUDIT',    'M_GLOBAL_FUNC','global.unaudit',                 '反审核',        'GLOBAL','BUTTON', 50, TRUE,'NORMAL'),
 ('GF_CLOSE',      'M_GLOBAL_FUNC','global.close',                   '关闭/作废',     'GLOBAL','BUTTON', 60, TRUE,'NORMAL'),
 ('GF_DELETE',     'M_GLOBAL_FUNC','global.delete',                  '删除',          'GLOBAL','BUTTON', 70, TRUE,'NORMAL'),
 ('GF_BATCH',      'M_GLOBAL_FUNC','global.batch_operate',           '批量操作',      'GLOBAL','BUTTON', 80, TRUE,'NORMAL'),
 ('GF_LOG_VIEW',   'M_GLOBAL_FUNC','global.log_view',                '操作日志查看',  'GLOBAL','BUTTON', 90, TRUE,'NORMAL'),
 ('GF_ATT_VIEW',   'M_GLOBAL_FUNC','global.attachment_view',         '附件查看',      'GLOBAL','BUTTON',100, TRUE,'NORMAL'),
 ('GF_ATT_UPLOAD', 'M_GLOBAL_FUNC','global.attachment_upload',       '附件上传',      'GLOBAL','BUTTON',110, TRUE,'NORMAL'),
 ('GF_LOW_PRICE',  'M_GLOBAL_FUNC','global.low_price_approval',      '低价销售授权',  'GLOBAL','ACTION',120, TRUE,'NORMAL'),
 ('GF_OVER_CREDIT','M_GLOBAL_FUNC','global.over_credit_approval',    '超信用授权',    'GLOBAL','ACTION',130, TRUE,'NORMAL'),
 ('GF_NEG_STOCK',  'M_GLOBAL_FUNC','global.negative_stock_approval', '负库存授权',    'GLOBAL','ACTION',140, TRUE,'NORMAL'),
 ('GF_CHANGE_OWNER','M_GLOBAL_FUNC','global.change_created_by',      '修改制单人/业务员','GLOBAL','ACTION',150, TRUE,'NORMAL'),
 ('GF_CHANGE_PRICE','M_GLOBAL_FUNC','global.change_price',           '修改单价',      'GLOBAL','ACTION',160, TRUE,'NORMAL'),
 ('GF_CHANGE_DISC','M_GLOBAL_FUNC','global.change_discount',         '修改折扣',      'GLOBAL','ACTION',170, TRUE,'NORMAL'),
 ('GF_SETTLE',     'M_GLOBAL_FUNC','global.settle',                  '结算/核销',     'GLOBAL','ACTION',180, TRUE,'NORMAL'),
 ('GF_EXP_SENS',   'M_GLOBAL_FUNC','global.data_export_sensitive',   '敏感数据导出',  'GLOBAL','ACTION',190, TRUE,'NORMAL')
ON DUPLICATE KEY UPDATE func_name = VALUES(func_name), menu_id = VALUES(menu_id), status='NORMAL';

-- ============================================================
-- 种子四：SYS_ADMIN / SYS_VIEWER / BIZ_OWNER 授权
--   模块菜单与模块功能点由 PermissionRegistry 启动同步后自动补授 SYS_ADMIN，
--   这里先授全局功能占位菜单 + 全部全局功能 + 全部字段。
-- ============================================================
INSERT INTO sys_role_menu_rel (id, role_id, menu_id, created_at)
SELECT 'RM_SYSADM_GF', 'R_SYS_ADMIN', 'M_GLOBAL_FUNC', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu_rel x WHERE x.role_id='R_SYS_ADMIN' AND x.menu_id='M_GLOBAL_FUNC');

INSERT INTO sys_role_func_rel (id, role_id, func_id, created_at)
SELECT CONCAT('RF_SYSADM_', f.func_id), 'R_SYS_ADMIN', f.func_id, CURRENT_TIMESTAMP
FROM sys_func_meta f
WHERE f.func_scope='GLOBAL'
  AND NOT EXISTS (SELECT 1 FROM sys_role_func_rel x WHERE x.role_id='R_SYS_ADMIN' AND x.func_id=f.func_id);

-- 老板/管理层：全部全局功能
INSERT INTO sys_role_func_rel (id, role_id, func_id, created_at)
SELECT CONCAT('RF_OWNER_', f.func_id), 'R_BIZ_OWNER', f.func_id, CURRENT_TIMESTAMP
FROM sys_func_meta f
WHERE f.func_scope='GLOBAL'
  AND NOT EXISTS (SELECT 1 FROM sys_role_func_rel x WHERE x.role_id='R_BIZ_OWNER' AND x.func_id=f.func_id);

-- 全字段授权：SYS_ADMIN / SYS_VIEWER / BIZ_OWNER / FINANCE_MANAGER
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD_', REPLACE(r.role_id,'R_',''), '_', REPLACE(f.field_id,'F_','')), r.role_id, f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
CROSS JOIN (SELECT 'R_SYS_ADMIN' AS role_id UNION ALL SELECT 'R_SYS_VIEWER'
            UNION ALL SELECT 'R_BIZ_OWNER' UNION ALL SELECT 'R_FIN_MGR') r
WHERE NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id=r.role_id AND x.field_id=f.field_id);

-- 其余角色字段权限（方案 §12.3 默认矩阵）
-- 销售主管：销售价/金额/成本/毛利/客户价/最低价/零售价/价格组 + 应收/信用/结算 + 库存数量/锁定 + 客户/司机电话（无采购价）
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD_SAL_MGR_', REPLACE(f.field_id,'F_','')), 'R_SAL_MGR', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_id IN ('VIEW_SALE_PRICE','VIEW_SALE_AMOUNT','VIEW_COST','VIEW_COST_AMOUNT','VIEW_PROFIT',
  'VIEW_MIN_PRICE','VIEW_SUGGEST_RETAIL_PRICE','VIEW_PRICE_GROUP','VIEW_CUSTOMER_PRICE',
  'VIEW_AR_BALANCE','VIEW_CREDIT_LIMIT','VIEW_SETTLE_DETAIL',
  'VIEW_STOCK_AMOUNT','VIEW_STOCK_LOCK','VIEW_CUSTOMER_MOBILE','VIEW_DRIVER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id='R_SAL_MGR' AND x.field_id=f.field_id);

-- 销售员：销售价/金额/最低价/客户价 + 自己客户应收 + 库存数量 + 客户电话（无成本/毛利/采购价）
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD_SAL_CLERK_', REPLACE(f.field_id,'F_','')), 'R_SAL_CLERK', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_id IN ('VIEW_SALE_PRICE','VIEW_SALE_AMOUNT','VIEW_MIN_PRICE','VIEW_CUSTOMER_PRICE',
  'VIEW_AR_BALANCE','VIEW_STOCK_AMOUNT','VIEW_CUSTOMER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id='R_SAL_CLERK' AND x.field_id=f.field_id);

-- 采购主管：采购价/金额/成本 + 应付/结算 + 库存数量/锁定/商品供应商 + 供应商电话（无销售毛利）
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD_PUR_MGR_', REPLACE(f.field_id,'F_','')), 'R_PUR_MGR', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_id IN ('VIEW_PURCHASE_PRICE','VIEW_PURCHASE_AMOUNT','VIEW_COST','VIEW_COST_AMOUNT',
  'VIEW_AP_BALANCE','VIEW_SETTLE_DETAIL','VIEW_STOCK_AMOUNT','VIEW_STOCK_LOCK','VIEW_SUPPLIER_OF_GOODS',
  'VIEW_SUPPLIER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id='R_PUR_MGR' AND x.field_id=f.field_id);

-- 采购员：采购价/金额 + 自己供应商应付 + 库存数量/商品供应商 + 供应商电话
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD_PUR_BUYER_', REPLACE(f.field_id,'F_','')), 'R_PUR_BUYER', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_id IN ('VIEW_PURCHASE_PRICE','VIEW_PURCHASE_AMOUNT','VIEW_AP_BALANCE',
  'VIEW_STOCK_AMOUNT','VIEW_SUPPLIER_OF_GOODS','VIEW_SUPPLIER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id='R_PUR_BUYER' AND x.field_id=f.field_id);

-- 财务出纳：销售/采购金额 + 应收应付/预收预付/资金/信用/结算 + 库存数量 + 客户/供应商电话
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD_FIN_CLERK_', REPLACE(f.field_id,'F_','')), 'R_FIN_CLERK', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_id IN ('VIEW_SALE_AMOUNT','VIEW_PURCHASE_AMOUNT',
  'VIEW_AR_BALANCE','VIEW_AP_BALANCE','VIEW_PRE_RECEIVED','VIEW_FUND_ACCOUNT_BALANCE','VIEW_FUND_FLOW',
  'VIEW_CREDIT_LIMIT','VIEW_SETTLE_DETAIL','VIEW_STOCK_AMOUNT','VIEW_CUSTOMER_MOBILE','VIEW_SUPPLIER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id='R_FIN_CLERK' AND x.field_id=f.field_id);

-- 仓库主管/仓管员：库存数量/锁定 + 司机电话（无任何单价金额）
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD_', REPLACE(r.role_id,'R_',''), '_', REPLACE(f.field_id,'F_','')), r.role_id, f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
CROSS JOIN (SELECT 'R_WH_MGR' AS role_id UNION ALL SELECT 'R_WH_KEEPER') r
WHERE f.field_id IN ('VIEW_STOCK_AMOUNT','VIEW_STOCK_LOCK','VIEW_DRIVER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id=r.role_id AND x.field_id=f.field_id);

-- PDA 作业员：库存数量；PDA 主管多锁定量与司机电话
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD_', REPLACE(r.role_id,'R_',''), '_', REPLACE(f.field_id,'F_','')), r.role_id, f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
CROSS JOIN (SELECT 'R_WMS_RECEIVER' AS role_id UNION ALL SELECT 'R_WMS_PUTAWAY'
            UNION ALL SELECT 'R_WMS_PICKER' UNION ALL SELECT 'R_WMS_CHECKER' UNION ALL SELECT 'R_WMS_KEEPER') r
WHERE f.field_id='VIEW_STOCK_AMOUNT'
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id=r.role_id AND x.field_id=f.field_id);

INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD_WMS_LEADER_', REPLACE(f.field_id,'F_','')), 'R_WMS_LEADER', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_id IN ('VIEW_STOCK_AMOUNT','VIEW_STOCK_LOCK','VIEW_DRIVER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id='R_WMS_LEADER' AND x.field_id=f.field_id);

-- 调度员：客户/司机电话；司机：结算(仅自己收款) + 客户电话；配送主管：客户/司机电话+结算
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD_', REPLACE(r.role_id,'R_',''), '_', REPLACE(f.field_id,'F_','')), r.role_id, f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
CROSS JOIN (SELECT 'R_TMS_DISPATCHER' AS role_id UNION ALL SELECT 'R_TMS_LEADER') r
WHERE f.field_id IN ('VIEW_CUSTOMER_MOBILE','VIEW_DRIVER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id=r.role_id AND x.field_id=f.field_id);

INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD_', REPLACE(r.role_id,'R_',''), '_', REPLACE(f.field_id,'F_','')), r.role_id, f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
CROSS JOIN (SELECT 'R_TMS_DRIVER' AS role_id UNION ALL SELECT 'R_TMS_LEADER') r
WHERE f.field_id IN ('VIEW_SETTLE_DETAIL','VIEW_CUSTOMER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id=r.role_id AND x.field_id=f.field_id);

-- ============================================================
-- 种子五：内置角色默认数据范围（方案 §5.3.4，未列维度走"仅自己创建"）
-- ============================================================
MERGE INTO sys_role_data_scope (id, role_id, scope_type, scope_value, created_at) KEY(role_id, scope_type)
SELECT CONCAT('RS_', r.role_id, '_', t.scope_type), r.role_id, t.scope_type, 'ALL', CURRENT_TIMESTAMP
FROM (SELECT 'R_BIZ_OWNER' AS role_id UNION ALL SELECT 'R_FIN_MGR') r
CROSS JOIN (SELECT 'WAREHOUSE' AS scope_type UNION ALL SELECT 'CUSTOMER' UNION ALL SELECT 'SUPPLIER'
            UNION ALL SELECT 'SALESMAN' UNION ALL SELECT 'OWNER'
            UNION ALL SELECT 'GOODS_CATEGORY' UNION ALL SELECT 'BRAND') t;

-- 采购主管：仓库/供应商/分类/品牌 全部
MERGE INTO sys_role_data_scope (id, role_id, scope_type, scope_value, created_at) KEY(role_id, scope_type)
SELECT CONCAT('RS_R_PUR_MGR_', t.scope_type), 'R_PUR_MGR', t.scope_type, 'ALL', CURRENT_TIMESTAMP
FROM (SELECT 'WAREHOUSE' AS scope_type UNION ALL SELECT 'SUPPLIER'
      UNION ALL SELECT 'GOODS_CATEGORY' UNION ALL SELECT 'BRAND') t;

-- 销售主管：仓库/客户 ALL，业务员 SUB_TREE，分类/品牌 ALL
MERGE INTO sys_role_data_scope (id, role_id, scope_type, scope_value, created_at) KEY(role_id, scope_type)
SELECT 'RS_R_SAL_MGR_WAREHOUSE', 'R_SAL_MGR', 'WAREHOUSE', 'ALL', CURRENT_TIMESTAMP
UNION ALL SELECT 'RS_R_SAL_MGR_CUSTOMER', 'R_SAL_MGR', 'CUSTOMER', 'ALL', CURRENT_TIMESTAMP
UNION ALL SELECT 'RS_R_SAL_MGR_SALESMAN', 'R_SAL_MGR', 'SALESMAN', 'SUB_TREE', CURRENT_TIMESTAMP
UNION ALL SELECT 'RS_R_SAL_MGR_GOODS_CATEGORY', 'R_SAL_MGR', 'GOODS_CATEGORY', 'ALL', CURRENT_TIMESTAMP
UNION ALL SELECT 'RS_R_SAL_MGR_BRAND', 'R_SAL_MGR', 'BRAND', 'ALL', CURRENT_TIMESTAMP;

-- 销售员：客户=归属自己，业务员=自己，分类/品牌 ALL
MERGE INTO sys_role_data_scope (id, role_id, scope_type, scope_value, created_at) KEY(role_id, scope_type)
SELECT 'RS_R_SAL_CLERK_CUSTOMER', 'R_SAL_CLERK', 'CUSTOMER', 'SELF', CURRENT_TIMESTAMP
UNION ALL SELECT 'RS_R_SAL_CLERK_SALESMAN', 'R_SAL_CLERK', 'SALESMAN', 'SELF', CURRENT_TIMESTAMP
UNION ALL SELECT 'RS_R_SAL_CLERK_GOODS_CATEGORY', 'R_SAL_CLERK', 'GOODS_CATEGORY', 'ALL', CURRENT_TIMESTAMP
UNION ALL SELECT 'RS_R_SAL_CLERK_BRAND', 'R_SAL_CLERK', 'BRAND', 'ALL', CURRENT_TIMESTAMP;

-- 财务出纳：仓库/客户/供应商/业务员 ALL
MERGE INTO sys_role_data_scope (id, role_id, scope_type, scope_value, created_at) KEY(role_id, scope_type)
SELECT CONCAT('RS_R_FIN_CLERK_', t.scope_type), 'R_FIN_CLERK', t.scope_type, 'ALL', CURRENT_TIMESTAMP
FROM (SELECT 'WAREHOUSE' AS scope_type UNION ALL SELECT 'CUSTOMER'
      UNION ALL SELECT 'SUPPLIER' UNION ALL SELECT 'SALESMAN') t;

-- 仓库岗：仓库 ALL（由用户绑定仓 sys_user_warehouse 收窄；PDA 登录仓强制）
MERGE INTO sys_role_data_scope (id, role_id, scope_type, scope_value, created_at) KEY(role_id, scope_type)
SELECT CONCAT('RS_', r.role_id, '_WAREHOUSE'), r.role_id, 'WAREHOUSE', 'ALL', CURRENT_TIMESTAMP
FROM (SELECT 'R_WH_MGR' AS role_id UNION ALL SELECT 'R_WH_KEEPER'
      UNION ALL SELECT 'R_WMS_RECEIVER' UNION ALL SELECT 'R_WMS_PUTAWAY' UNION ALL SELECT 'R_WMS_PICKER'
      UNION ALL SELECT 'R_WMS_CHECKER' UNION ALL SELECT 'R_WMS_KEEPER' UNION ALL SELECT 'R_WMS_LEADER') r;

-- ============================================================
-- 存量迁移（方案 §12.2 / §18.4）
--   旧 sys_user_runtime.role_name 存的是角色"名称"（管理员组/销售员组/采购员组），
--   映射到新内置角色并写关联表；存量角色先保证能登录，具体菜单授权上线后补配。
-- ============================================================
INSERT INTO sys_user_role_rel (id, user_id, role_id, created_at)
SELECT CONCAT('URR_', u.user_id, '_', nr.role_id), u.user_id, nr.role_id, CURRENT_TIMESTAMP
FROM sys_user_runtime u
JOIN sys_role_runtime nr ON nr.role_code = CASE u.role_name
      WHEN '管理员组' THEN 'SYS_ADMIN'
      WHEN '销售员组' THEN 'SALES_CLERK'
      WHEN '采购员组' THEN 'PURCHASE_BUYER' END
WHERE u.role_name IN ('管理员组','销售员组','采购员组')
  AND NOT EXISTS (SELECT 1 FROM sys_user_role_rel x WHERE x.user_id=u.user_id AND x.role_id=nr.role_id);

-- 兜底：role_name 无法映射的存量用户，先挂只读观察员，保证升级后能登录看到桌面
INSERT INTO sys_user_role_rel (id, user_id, role_id, created_at)
SELECT CONCAT('URR_', u.user_id, '_R_SYS_VIEWER'), u.user_id, 'R_SYS_VIEWER', CURRENT_TIMESTAMP
FROM sys_user_runtime u
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role_rel x WHERE x.user_id=u.user_id)
  AND u.status='NORMAL';

-- admin 账号补主角色与内置标记
UPDATE sys_user_runtime
SET primary_role_id='R_SYS_ADMIN', is_system=TRUE
WHERE username='admin';
