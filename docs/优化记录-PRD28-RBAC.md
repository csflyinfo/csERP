# 优化记录 — PRD-28 系统用户与权限管理 V2（RBAC）

- **PRD 编号**：PRD-28
- **方案文档**：[系统用户及权限管理方案.md](./系统用户及权限管理方案.md)
- **起始 Flyway**：V102
- **起始日期**：2026-08-24
- **主干分支**：`main`
- **状态**：方案设计完成，待进入 M1 开发

## 1. 背景

原系统权限存在 11 项缺口：菜单/角色名硬编码、`role_name` 字符串 JOIN、数据范围字段未生效、`admin` 账号硬编码兜底、固定验证码 `888888`、PDA 未登录、字段脱敏不统一、无操作/数据权限校验、多角色不支持、司机未入用户表、无审计日志。本次按"RBAC + 菜单/功能/字段/数据四维权限 + 三端（PC/PDA/司机）"重构，并约定权限元数据由代码自动注册。

## 2. 范围

- 12 张新权限表（V102）+ 26 个字段权限 + 20 个全局功能点 + 18+ 内置角色；
- 权限元数据代码化：`MenuConfig` + `@RequirePerm` 扫描 + `SensitiveFieldRegistry` + 启动 `PermissionRegistry` upsert；
- `DataScopeHelper` 七维数据权限（仓库/客户/供应商/业务员/建档人/商品分类/品牌）；
- `FieldMaskingSerializer` Map 响应字段脱敏；
- PDA 工号+密码+选仓登录、司机手机号+验证码首次自动建档；
- 存量 46 个 Controller 分 8 批补注解；前端移除 `roleCode='ADMIN'` 硬编码。

## 3. 里程碑与分支

| 里程碑 | 分支 | Flyway | 状态 |
|---|---|---|---|
| M1 权限底座 | `feat/rbac-1-schema`、`feat/rbac-2-auth`、`feat/rbac-3-meta-sync` | V102 | 待启动 |
| M2 PC 端闭环 | `feat/rbac-4-data-scope` ~ `feat/rbac-7-inv-fin-base`、`feat/rbac-10-frontend` | V103–V105 | 待启动 |
| M3 三端闭环 | `feat/rbac-8-pda-login`、`feat/rbac-9-driver-login` | V106–V107 | 待启动 |
| M4 清理收尾 | `chore/rbac-cleanup-legacy` | V108 | 待启动 |

分支存活 ≤ 5 天；合入 main 用 `--squash`，合完 `git branch -D`。

## 4. 变更记录

> 每次提交合入 main 后在此追加一条：日期 / 分支 / Flyway / 提交 hash / 变更点 / 影响范围 / 验证方式。

### 2026-08-24 方案定稿

- 新增 `docs/系统用户及权限管理方案.md`（19 章）；
- 新增本变更记录文件（按全局规则"每次优化必须追加 docs/优化记录-*.md"）；
- 尚未产生代码 / Flyway 变更，M1 启动后在此追加首批落地记录。

### 2026-09-09 方案补充：模块菜单管理 + 三级菜单结构

需求：系统增加【模块菜单管理】，仅系统管理员可操作；该菜单在角色权限设置中不可见、不可授权给其他角色；支持菜单改名、换上级、调顺序；菜单层级由二级扩展到三级。方案文档变更点（均为文档，无代码/Flyway 变更）：

- §5.2 DDL `sys_menu_meta` 新增 `admin_only`、`name_customized`/`parent_customized`/`sort_customized` 列，及层级校验规则注释；
- §6.4.1 `MenuConfig` 支持二级 `dir` 嵌套（三级菜单）、`.adminOnly()` 声明；
- §6.4.5 同步算法改为"字段归属"：路由/组件/类型归代码强制同步，名称/上级/排序管理员自定义后同步保留（标志位保护），支持"恢复默认"；
- §9.4 新增 `/system/menu/grant-tree`（过滤 admin_only）与 `/system/menu-manage/**` 一组接口（SYS_ADMIN 硬校验，不走功能点授权）；
- §10.3 由 P2 占位扩写为完整页面规格（访问三硬规则、功能项、7 条保存校验、与自动注册边界）；
- §7.1 种子树标记 `system.menu` 为 admin_only 专属菜单；
- §11.1 前端三级渲染、`/system/menu` 路由守卫、授权树数据源约束；
- §13.1 审计事件增加 MENU_CHANGE；
- §14 模块菜单管理由 P1 提前到 P0，新增 12 条 MENU-001~012 验收用例；
- §19.2 任务表 #3/#5/#10 分支纳入菜单管理后端、页面与三级渲染。

## 5. 风险与回滚

- V102 在生产数据库先备份（H2 拷贝 `data/erp-v1.mv.db`，MySQL `mysqldump`）；
- 所有新表/新列均为新增，不删旧列，代码回滚后旧逻辑可继续运行；
- 司机固定验证码 `888888` 仅在 dev profile 生效，prod profile 启动时强制校验短信配置；
- 存量角色 V102 先授"我的桌面 + view"兜底，防止升级后用户无法登录。

### 2026-09-09 细化开发计划

- 新增 `docs/开发计划-PRD28-RBAC.md`：W1~W7 排期、12 张任务卡（含勾选清单/依赖/验收/回滚）、分支依赖图、每分支 DoD、三次风险评审检查点；《方案》§19.1 增加指向该文件的链接。纯文档，无代码/Flyway 变更。
