-- ============================================================
-- PRD-28 RBAC 卡片9：PDA 登录与作业权限（V107）
-- ------------------------------------------------------------
-- 时序说明：PDA 菜单（sys_menu_meta）与功能点（sys_func_meta）由应用启动后的
-- PermissionRegistry 同步写入（@RequirePerm 扫描 + 标准派生），Flyway 迁移阶段
-- 这些记录尚不存在，因此六角色的菜单/功能点授权在 Java seeder
-- （PermissionRegistry.ensurePdaRoleGrants，方案 §7.2.1 矩阵）中按 menu_code/
-- func_code 幂等下发，本迁移只补「迁移时已存在数据」的补丁。
--
-- 本迁移内容：仓库主管(PDA) LEADER 的库存成本双控字段——
-- wms_pda.stock_query.view_cost 功能点要求同时具备字段权限 VIEW_COST，
-- 查看成本金额再需 VIEW_COST_AMOUNT（方案 §7.2.1 第 4 点）。V102/V103 未授予。
-- ============================================================

INSERT INTO sys_role_field_rel (id, role_id, field_id, created_at)
SELECT CONCAT('FD3_WMS_LEADER_', REPLACE(f.field_id,'F_','')), 'R_WMS_LEADER', f.field_id, CURRENT_TIMESTAMP
FROM sys_field_meta f
WHERE f.field_code IN ('VIEW_COST','VIEW_COST_AMOUNT')
  AND NOT EXISTS (SELECT 1 FROM sys_role_field_rel x
                   WHERE x.role_id='R_WMS_LEADER' AND x.field_id=f.field_id);
