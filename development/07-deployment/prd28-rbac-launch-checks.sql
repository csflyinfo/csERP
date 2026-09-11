-- ============================================================================
-- PRD-28 RBAC 上线核对脚本（卡片11 交付物）
--
-- 用途：V102~V109 迁移 + 应用首次启动权限同步完成后执行，全部结果应为 PASS。
-- 只读：本脚本不含任何 INSERT/UPDATE/DELETE，可重复执行。
--
-- 运行方式（二选一）：
--   1) H2 文件库（停应用后）：
--      java -cp h2-2.2.224.jar org.h2.tools.Shell \
--        -url "jdbc:h2:file:./data/erp-v1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
--        -user sa -sql "$(cat prd28-rbac-launch-checks.sql)"
--      或在 H2 Console / DBeaver 中分段执行。
--   2) MySQL：mysql -u<user> -p <db> < prd28-rbac-launch-checks.sql
--      （语法仅用标准 SQL + information_schema，两库通用；布尔列 success=TRUE
--        在 MySQL 中等价 =1。）
--
-- 判读：任何 FAIL 先不要放流量，按各项注释排查；期望值对应 V109 代码基线
--       （功能点 1699 / 代码菜单 192 + _global_ 占位 = 193 / 字段 26）。
--       管理员自建菜单(is_system=FALSE)与自定义角色不在固定期望内，属正常。
-- ============================================================================

-- A. Flyway 迁移记录：RBAC 占用 V102/V103/V104/V107/V108/V109（V105/V106 空出允许跳号）
SELECT 'A. Flyway 迁移' AS section;
SELECT chk, expected, actual, CASE WHEN actual = expected THEN 'PASS' ELSE 'FAIL' END AS result FROM (
  SELECT 'A1 V102 RBAC 建表与种子' chk, 1 expected,
    (SELECT COUNT(*) FROM flyway_schema_history WHERE script LIKE 'V102%' AND success = TRUE) actual
  UNION ALL SELECT 'A2 V103 字段授权修补', 1,
    (SELECT COUNT(*) FROM flyway_schema_history WHERE script LIKE 'V103%' AND success = TRUE)
  UNION ALL SELECT 'A3 V104 用户管理/密码历史', 1,
    (SELECT COUNT(*) FROM flyway_schema_history WHERE script LIKE 'V104%' AND success = TRUE)
  UNION ALL SELECT 'A4 V107 PDA 角色授权', 1,
    (SELECT COUNT(*) FROM flyway_schema_history WHERE script LIKE 'V107%' AND success = TRUE)
  UNION ALL SELECT 'A5 V108 短信锁定列', 1,
    (SELECT COUNT(*) FROM flyway_schema_history WHERE script LIKE 'V108%' AND success = TRUE)
  UNION ALL SELECT 'A6 失败迁移记录必须为 0', 0,
    (SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE)
  UNION ALL SELECT 'A7 V109 菜单启用开关/自定义目录', 1,
    (SELECT COUNT(*) FROM flyway_schema_history WHERE script LIKE 'V109%' AND success = TRUE)
) t;

-- B. 权限元数据总量（与启动日志「菜单 192 / 功能点 1699 / 字段 26」对齐）
SELECT 'B. 元数据总量' AS section;
SELECT chk, expected, actual, CASE WHEN actual = expected THEN 'PASS' ELSE 'FAIL' END AS result FROM (
  SELECT 'B1 系统内置菜单 NORMAL（192 代码菜单 + _global_ 占位）' chk, 193 expected,
    (SELECT COUNT(*) FROM sys_menu_meta WHERE is_system = TRUE AND status = 'NORMAL') actual
  UNION ALL SELECT 'B2 _global_ 全局功能占位菜单保留', 1,
    (SELECT COUNT(*) FROM sys_menu_meta WHERE menu_code = '_global_')
  UNION ALL SELECT 'B3 功能点 NORMAL', 1699,
    (SELECT COUNT(*) FROM sys_func_meta WHERE status = 'NORMAL')
  UNION ALL SELECT 'B4 字段权限字典', 26,
    (SELECT COUNT(*) FROM sys_field_meta)
  UNION ALL SELECT 'B5 存量系统菜单 V109 回填后全部启用（193；全新库预期=1，全新部署不适用本项）', 193,
    (SELECT COUNT(*) FROM sys_menu_meta WHERE is_system = TRUE AND status = 'NORMAL' AND enabled = TRUE)
) t;

-- C. 21 个内置角色必须存在且启用（缺任一会导致对应岗位登录后无授权）
SELECT 'C. 内置角色齐备性（21 个）' AS section;
SELECT chk, expected, actual, CASE WHEN actual = expected THEN 'PASS' ELSE 'FAIL' END AS result FROM (
  SELECT 'C1 内置角色 NORMAL 数量' chk, 21 expected, (SELECT COUNT(*) FROM (
      SELECT role_code FROM sys_role_runtime WHERE status = 'NORMAL' AND role_code IN (
        'SYS_ADMIN','SYS_VIEWER','BIZ_OWNER',
        'PURCHASE_MANAGER','PURCHASE_BUYER','SALES_MANAGER','SALES_CLERK',
        'WAREHOUSE_MANAGER','WAREHOUSE_KEEPER','FINANCE_MANAGER','FINANCE_CLERK',
        'WMS_RECEIVER','WMS_PUTAWAY','WMS_PICKER','WMS_CHECKER','WMS_KEEPER','WMS_LEADER',
        'TMS_DISPATCHER','TMS_DRIVER','TMS_LOADER','TMS_LEADER')
      GROUP BY role_code) x) actual
  UNION ALL SELECT 'C2 SYS_ADMIN 不可停用(is_system=TRUE)', 1,
    (SELECT COUNT(*) FROM sys_role_runtime WHERE role_code = 'SYS_ADMIN' AND is_system = TRUE AND status = 'NORMAL')
) t;
-- 若 C1 FAIL，用下句定位缺哪个角色：
-- SELECT column1 AS missing_role FROM (VALUES ('SYS_ADMIN'),('SYS_VIEWER'),('BIZ_OWNER'),('PURCHASE_MANAGER'),
--   ('PURCHASE_BUYER'),('SALES_MANAGER'),('SALES_CLERK'),('WAREHOUSE_MANAGER'),('WAREHOUSE_KEEPER'),
--   ('FINANCE_MANAGER'),('FINANCE_CLERK'),('WMS_RECEIVER'),('WMS_PUTAWAY'),('WMS_PICKER'),
--   ('WMS_CHECKER'),('WMS_KEEPER'),('WMS_LEADER'),('TMS_DISPATCHER'),('TMS_DRIVER'),('TMS_LOADER'),('TMS_LEADER')) v
-- WHERE column1 NOT IN (SELECT role_code FROM sys_role_runtime WHERE status='NORMAL');

-- D. 三端内置角色授权行数（代码矩阵启动幂等对账后的期望，driver.* / wms_pda.* 口径）
SELECT 'D. 移动三端角色授权矩阵' AS section;
SELECT chk, expected, actual, CASE WHEN actual = expected THEN 'PASS' ELSE 'FAIL' END AS result FROM (
  SELECT 'D1 司机 TMS_DRIVER 功能点' chk, 46 expected,
    (SELECT COUNT(*) FROM sys_role_func_rel r JOIN sys_func_meta f ON f.func_id = r.func_id
     WHERE r.role_id = 'R_TMS_DRIVER' AND f.func_code LIKE 'driver.%') actual
  UNION ALL SELECT 'D2 装车员 TMS_LOADER 功能点', 10,
    (SELECT COUNT(*) FROM sys_role_func_rel r JOIN sys_func_meta f ON f.func_id = r.func_id
     WHERE r.role_id = 'R_TMS_LOADER' AND f.func_code LIKE 'driver.%')
  UNION ALL SELECT 'D3 带组长 TMS_LEADER 功能点', 48,
    (SELECT COUNT(*) FROM sys_role_func_rel r JOIN sys_func_meta f ON f.func_id = r.func_id
     WHERE r.role_id = 'R_TMS_LEADER' AND f.func_code LIKE 'driver.%')
  UNION ALL SELECT 'D4 TMS_DRIVER 菜单（含 driver 根）', 16,
    (SELECT COUNT(*) FROM sys_role_menu_rel WHERE role_id = 'R_TMS_DRIVER')
  UNION ALL SELECT 'D5 TMS_LOADER 菜单', 5,
    (SELECT COUNT(*) FROM sys_role_menu_rel WHERE role_id = 'R_TMS_LOADER')
  UNION ALL SELECT 'D6 TMS_LEADER 菜单', 16,
    (SELECT COUNT(*) FROM sys_role_menu_rel WHERE role_id = 'R_TMS_LEADER')
  UNION ALL SELECT 'D7 PDA 六角色功能点合计（启动日志=199 行）', 199,
    (SELECT COUNT(*) FROM sys_role_func_rel r JOIN sys_func_meta f ON f.func_id = r.func_id
     WHERE r.role_id IN ('R_WMS_RECEIVER','R_WMS_PUTAWAY','R_WMS_PICKER','R_WMS_CHECKER','R_WMS_KEEPER','R_WMS_LEADER')
       AND f.func_code LIKE 'wms_pda.%')
  UNION ALL SELECT 'D8 超管功能点（应=全部功能点 1699）', 1699,
    (SELECT COUNT(*) FROM sys_role_func_rel WHERE role_id = 'R_SYS_ADMIN')
) t;

-- E. 孤儿关系（全部必须为 0）：授权指向已删除的用户/角色/菜单/功能/字段/仓库
SELECT 'E. 孤儿关系（期望全 0）' AS section;
SELECT chk, expected, actual, CASE WHEN actual = expected THEN 'PASS' ELSE 'FAIL' END AS result FROM (
  SELECT 'E1 用户-角色：用户不存在' chk, 0 expected,
    (SELECT COUNT(*) FROM sys_user_role_rel r LEFT JOIN sys_user_runtime u ON u.user_id = r.user_id
     WHERE u.user_id IS NULL) actual
  UNION ALL SELECT 'E2 用户-角色：角色不存在', 0,
    (SELECT COUNT(*) FROM sys_user_role_rel r LEFT JOIN sys_role_runtime g ON g.role_id = r.role_id
     WHERE g.role_id IS NULL)
  UNION ALL SELECT 'E3 角色-菜单：菜单不存在', 0,
    (SELECT COUNT(*) FROM sys_role_menu_rel r LEFT JOIN sys_menu_meta m ON m.menu_id = r.menu_id
     WHERE m.menu_id IS NULL)
  UNION ALL SELECT 'E4 角色-功能：功能不存在', 0,
    (SELECT COUNT(*) FROM sys_role_func_rel r LEFT JOIN sys_func_meta f ON f.func_id = r.func_id
     WHERE f.func_id IS NULL)
  UNION ALL SELECT 'E5 角色-字段：字段不存在', 0,
    (SELECT COUNT(*) FROM sys_role_field_rel r LEFT JOIN sys_field_meta f ON f.field_id = r.field_id
     WHERE f.field_id IS NULL)
  UNION ALL SELECT 'E6 角色数据范围：角色不存在', 0,
    (SELECT COUNT(*) FROM sys_role_data_scope r LEFT JOIN sys_role_runtime g ON g.role_id = r.role_id
     WHERE g.role_id IS NULL)
  UNION ALL SELECT 'E7 用户数据范围：用户不存在', 0,
    (SELECT COUNT(*) FROM sys_user_data_scope r LEFT JOIN sys_user_runtime u ON u.user_id = r.user_id
     WHERE u.user_id IS NULL)
  UNION ALL SELECT 'E8 用户-仓库：用户不存在', 0,
    (SELECT COUNT(*) FROM sys_user_warehouse r LEFT JOIN sys_user_runtime u ON u.user_id = r.user_id
     WHERE u.user_id IS NULL)
  UNION ALL SELECT 'E9 用户-仓库：仓库不存在', 0,
    (SELECT COUNT(*) FROM sys_user_warehouse r LEFT JOIN base_warehouse w ON w.warehouse_id = r.warehouse_id
     WHERE w.warehouse_id IS NULL)
) t;

-- F. 账号面
SELECT 'F. 账号与登录' AS section;
SELECT chk, expected, actual, CASE WHEN actual = expected THEN 'PASS' ELSE 'FAIL' END AS result FROM (
  SELECT 'F1 启用中账号必须至少有一个角色' chk, 0 expected,
    (SELECT COUNT(*) FROM sys_user_runtime u WHERE u.status = 'NORMAL'
     AND NOT EXISTS (SELECT 1 FROM sys_user_role_rel r WHERE r.user_id = u.user_id)) actual
  UNION ALL SELECT 'F2 admin 挂 SYS_ADMIN', 1,
    (SELECT COUNT(*) FROM sys_user_runtime u JOIN sys_user_role_rel r ON r.user_id = u.user_id
     WHERE u.username = 'admin' AND r.role_id = 'R_SYS_ADMIN')
  UNION ALL SELECT 'F3 内置超管角色被停用数（必须 0）', 0,
    (SELECT COUNT(*) FROM sys_role_runtime WHERE role_code = 'SYS_ADMIN' AND status <> 'NORMAL')
) t;

-- G. V108 短信风控列
SELECT 'G. 短信验证码风控（V108）' AS section;
SELECT chk, expected, actual, CASE WHEN actual = expected THEN 'PASS' ELSE 'FAIL' END AS result FROM (
  SELECT 'G1 sys_sms_code 风控三列齐全(fail_count/lock_until/send_ip)' chk, 3 expected,
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE UPPER(table_name) = 'SYS_SMS_CODE'
       AND UPPER(column_name) IN ('FAIL_COUNT','LOCK_UNTIL','SEND_IP')) actual
) t;
