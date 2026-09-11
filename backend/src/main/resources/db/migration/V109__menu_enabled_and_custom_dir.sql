-- ============================================================================
-- PRD-28 模块菜单管理增强（V109）
--   1) sys_menu_meta 增加 enabled（是否启用）开关：
--      - 存量菜单（含自定义目录）一律回填 TRUE，行为与升级前完全一致；
--      - 此后代码新注册（发版新增）的菜单由 PermissionRegistry 以 enabled=FALSE
--        插入，即「新开发模块默认不启用」，需管理员在模块菜单管理中勾选启用；
--      - 停用上级时服务端级联停用整棵子树；角色授权树与普通用户菜单树均过滤
--        enabled=FALSE（SYS_ADMIN 用户树不过滤，便于预览与启用操作）。
--   2) 自定义目录（is_system=FALSE 的 DIR）无需结构变更：本表已支持，
--      新增/删除走 MenuManageController，编码 custom.dir.* 、主键 M_C_*。
-- 兼容 H2(MODE=MySQL) 与 MySQL：H2 的 ADD COLUMN 不支持 IF EXISTS，按规范直接 ADD。
-- ============================================================================
ALTER TABLE sys_menu_meta ADD enabled BOOLEAN DEFAULT TRUE;

-- 兜底回填（DEFAULT 已对存量行生效，显式 UPDATE 防止个别方言行为差异）
UPDATE sys_menu_meta SET enabled = TRUE WHERE enabled IS NULL;
