-- ============================================================
-- PRD-28 RBAC 卡片4：修复 V102 字段授权种子
-- ------------------------------------------------------------
-- 缺陷：V102「其余角色字段权限」系列语句的 WHERE 写成
--   f.field_id IN ('VIEW_SALE_PRICE', ...)
-- 但 sys_field_meta.field_id 存的是 'F_SALE_PRICE'，VIEW_* 是 field_code，
-- 导致销售主管/销售员/采购主管/采购员/财务出纳/仓库岗/PDA/TMS 共 10 组
-- 内置角色的字段授权一行都没插入（CROSS JOIN 全字段授权的 4 个角色不受影响）。
-- 本迁移按 field_code 重新补齐，NOT EXISTS 防重，可重复执行。
-- ============================================================

-- 销售主管：销售价/金额/成本/毛利/客户价/最低价/零售价/价格组 + 应收/信用/结算 + 库存数量/锁定 + 客户/司机电话（无采购价）
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD2_SAL_MGR_', REPLACE(f.field_id,'F_','')), 'R_SAL_MGR', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_code IN ('VIEW_SALE_PRICE','VIEW_SALE_AMOUNT','VIEW_COST','VIEW_COST_AMOUNT','VIEW_PROFIT',
  'VIEW_MIN_PRICE','VIEW_SUGGEST_RETAIL_PRICE','VIEW_PRICE_GROUP','VIEW_CUSTOMER_PRICE',
  'VIEW_AR_BALANCE','VIEW_CREDIT_LIMIT','VIEW_SETTLE_DETAIL',
  'VIEW_STOCK_AMOUNT','VIEW_STOCK_LOCK','VIEW_CUSTOMER_MOBILE','VIEW_DRIVER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id='R_SAL_MGR' AND x.field_id=f.field_id);

-- 销售员：销售价/金额/最低价/客户价 + 自己客户应收 + 库存数量 + 客户电话（无成本/毛利/采购价）
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD2_SAL_CLERK_', REPLACE(f.field_id,'F_','')), 'R_SAL_CLERK', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_code IN ('VIEW_SALE_PRICE','VIEW_SALE_AMOUNT','VIEW_MIN_PRICE','VIEW_CUSTOMER_PRICE',
  'VIEW_AR_BALANCE','VIEW_STOCK_AMOUNT','VIEW_CUSTOMER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id='R_SAL_CLERK' AND x.field_id=f.field_id);

-- 采购主管：采购价/金额/成本 + 应付/结算 + 库存数量/锁定/商品供应商 + 供应商电话（无销售毛利）
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD2_PUR_MGR_', REPLACE(f.field_id,'F_','')), 'R_PUR_MGR', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_code IN ('VIEW_PURCHASE_PRICE','VIEW_PURCHASE_AMOUNT','VIEW_COST','VIEW_COST_AMOUNT',
  'VIEW_AP_BALANCE','VIEW_SETTLE_DETAIL','VIEW_STOCK_AMOUNT','VIEW_STOCK_LOCK','VIEW_SUPPLIER_OF_GOODS',
  'VIEW_SUPPLIER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id='R_PUR_MGR' AND x.field_id=f.field_id);

-- 采购员：采购价/金额 + 自己供应商应付 + 库存数量/商品供应商 + 供应商电话
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD2_PUR_BUYER_', REPLACE(f.field_id,'F_','')), 'R_PUR_BUYER', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_code IN ('VIEW_PURCHASE_PRICE','VIEW_PURCHASE_AMOUNT','VIEW_AP_BALANCE',
  'VIEW_STOCK_AMOUNT','VIEW_SUPPLIER_OF_GOODS','VIEW_SUPPLIER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id='R_PUR_BUYER' AND x.field_id=f.field_id);

-- 财务出纳：销售/采购金额 + 应收应付/预收预付/资金/信用/结算 + 库存数量 + 客户/供应商电话
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD2_FIN_CLERK_', REPLACE(f.field_id,'F_','')), 'R_FIN_CLERK', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_code IN ('VIEW_SALE_AMOUNT','VIEW_PURCHASE_AMOUNT',
  'VIEW_AR_BALANCE','VIEW_AP_BALANCE','VIEW_PRE_RECEIVED','VIEW_FUND_ACCOUNT_BALANCE','VIEW_FUND_FLOW',
  'VIEW_CREDIT_LIMIT','VIEW_SETTLE_DETAIL','VIEW_STOCK_AMOUNT','VIEW_CUSTOMER_MOBILE','VIEW_SUPPLIER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id='R_FIN_CLERK' AND x.field_id=f.field_id);

-- 仓库主管/仓管员：库存数量/锁定 + 司机电话（无任何单价金额）
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD2_', REPLACE(r.role_id,'R_',''), '_', REPLACE(f.field_id,'F_','')), r.role_id, f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
CROSS JOIN (SELECT 'R_WH_MGR' AS role_id UNION ALL SELECT 'R_WH_KEEPER') r
WHERE f.field_code IN ('VIEW_STOCK_AMOUNT','VIEW_STOCK_LOCK','VIEW_DRIVER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id=r.role_id AND x.field_id=f.field_id);

-- PDA 作业员：库存数量；PDA 主管多锁定量与司机电话
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD2_', REPLACE(r.role_id,'R_',''), '_', REPLACE(f.field_id,'F_','')), r.role_id, f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
CROSS JOIN (SELECT 'R_WMS_RECEIVER' AS role_id UNION ALL SELECT 'R_WMS_PUTAWAY'
            UNION ALL SELECT 'R_WMS_PICKER' UNION ALL SELECT 'R_WMS_CHECKER' UNION ALL SELECT 'R_WMS_KEEPER') r
WHERE f.field_code='VIEW_STOCK_AMOUNT'
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id=r.role_id AND x.field_id=f.field_id);

INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD2_WMS_LEADER_', REPLACE(f.field_id,'F_','')), 'R_WMS_LEADER', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_code IN ('VIEW_STOCK_AMOUNT','VIEW_STOCK_LOCK','VIEW_DRIVER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id='R_WMS_LEADER' AND x.field_id=f.field_id);

-- 调度员：客户/司机电话；配送主管：客户/司机电话
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD2_', REPLACE(r.role_id,'R_',''), '_', REPLACE(f.field_id,'F_','')), r.role_id, f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
CROSS JOIN (SELECT 'R_TMS_DISPATCHER' AS role_id UNION ALL SELECT 'R_TMS_LEADER') r
WHERE f.field_code IN ('VIEW_CUSTOMER_MOBILE','VIEW_DRIVER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id=r.role_id AND x.field_id=f.field_id);

-- 司机：结算(仅自己收款) + 客户电话；配送主管同组（NOT EXISTS 去重）
INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD2_', REPLACE(r.role_id,'R_',''), '_', REPLACE(f.field_id,'F_','')), r.role_id, f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
CROSS JOIN (SELECT 'R_TMS_DRIVER' AS role_id UNION ALL SELECT 'R_TMS_LEADER') r
WHERE f.field_code IN ('VIEW_SETTLE_DETAIL','VIEW_CUSTOMER_MOBILE')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x WHERE x.role_id=r.role_id AND x.field_id=f.field_id);
