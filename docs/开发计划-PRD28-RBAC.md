# PRD-28 系统用户与权限管理 V2（RBAC）开发计划

> 配套文档：[系统用户及权限管理方案.md](./系统用户及权限管理方案.md)（以下简称《方案》，章节引用以 § 标注）、[优化记录-PRD28-RBAC.md](./优化记录-PRD28-RBAC.md)
> 基线：`main`（2026-09-09，最新迁移 V101）｜总工作量：约 **40 人天**｜建议周期：**4 个里程碑、6~7 周**（后端+前端可并行）

---

## 1. 排期总览

| 周次 | 里程碑 | 分支（按序合入） | Flyway | 后端 | 前端/APP | 测试 |
|---|---|---|---|---|---|---|
| W1 | **M1 权限底座** | rbac-1-schema → rbac-2-auth | V102 | 3d | 1d | 0.5d |
| W2 | M1 | rbac-3-meta-sync | — | 2d | 1d（菜单管理页前端在 W3 联调） | 0.5d |
| W3 | **M2 PC 闭环** | rbac-4-data-scope（含 V103 种子修复） → rbac-5-system-annotate | V103、V104 | 3d | 2d（用户/角色/菜单管理页） | 1.5d |
| W4 | M2 | rbac-6-sales-purchase | V105 | 2.5d | 1.5d | 1.5d |
| W5 | M2 | rbac-7-inv-fin-base → rbac-10-frontend | V106 | 2.5d | 1.5d（指令/动态路由收口） | 1d |
| W6 | **M3 三端闭环** | rbac-8-pda-login → rbac-9-driver-login | V107、V108 | 4d | APP 5d（可与 W4 起并行） | 3d |
| W7 | 缓冲/回归 | 缺陷修复、存量角色授权演练、上线演练 | — | — | — | — |
| 上线后 ≥2 周 | **M4 清理** | chore/rbac-cleanup-legacy | V109 | 1d | 1d | 1d |

> 单人全栈串行时按"后端+前端+测试"列纵向相加，约 7~8 周；后端 1 人 + 前端/APP 1 人并行约 6 周。APP 工程师在 W4 即可介入（先做登录页，不阻塞后端）。

**关键路径**：rbac-1 → rbac-2 → rbac-3 → rbac-4 → rbac-6（销售/采购是业务核心，数据权限与脱敏必须最早验证）→ 回归上线。rbac-8/9（APP 端）与 PC 链无依赖，可并行。

---

## 2. 开工前检查（每支分支通用，强制执行）

```bash
git checkout main && git pull
ls backend/src/main/resources/db/migration/ | sort -V | tail -1   # 确认最大迁移号
```

1. 本计划版本号基于 **V101**，RBAC 占 **V102–V109**（V103 为卡片4 种子修复，后续顺延）；开工任一带迁移的分支前必须重新确认最大号，被占则顺延并改《方案》+本文件；
2. 在 PRD 索引表登记 PRD-28 与占用区段（项目全局串行资源规则）；
3. 默认串行开发：同一时间只开一个含 Flyway 迁移的分支；
4. 涉及 H2 验证前备份 `data/erp-v1.mv.db`；冒烟测试会按单号前缀删单据（见仓库记忆）；
5. 每支分支合入前：`mvn -f backend/pom.xml package -DskipTests` + `npm --prefix frontend run build` + 无冲突标记；
6. 合入方式：`git merge --squash` → 提交信息 `feat(system): <简述>（PRD-28, V102-Vxx）` → `git branch -D`；
7. 每支分支必须在 `docs/优化记录-PRD28-RBAC.md` 追加落地记录（日期/hash/迁移号/验证方式）。

---

## 3. 任务卡

### 卡片 1 ｜ `feat/rbac-1-schema`（M1，V102，约 1.5d）

**前置**：无，第一支。**目标**：表结构与种子落地，旧逻辑零影响。

- [ ] 新建 `V102__sys_rbac.sql`，严格按《方案》§5.2：
  - [ ] `sys_user_runtime` / `sys_role_runtime` 扩列（全用 `ADD COLUMN IF NOT EXISTS`）
  - [ ] 12 张新表：user_role_rel、user_warehouse、menu_meta（含 `admin_only`+3 个 `*_customized`）、func_meta、field_meta、role_menu_rel、role_func_rel、role_field_rel、role_data_scope、user_data_scope、login_log_runtime、sms_code
  - [ ] 索引与唯一约束（uk_menu_code、uk_func_code、uk_user_role 等）
- [ ] 种子（同文件或 V102 内分段）：18+ 内置角色（§12.1）、26 个字段权限（§12.3）、20 个全局功能点 + `M_GLOBAL_FUNC` 占位（§12.4）
- [ ] 存量迁移（§12.2/§18.4）：旧用户按 `role_name` 写 user_role_rel；旧角色先授"我的桌面 + view"兜底；admin 绑 SYS_ADMIN + 全仓库
- [ ] **不写菜单/模块功能点种子**（由卡片 3 代码同步产生）
- [ ] H2 本地启动验证迁移成功；`SELECT count(*)` 核对角色/字段/全局功能行数
- [ ] 后端编译通过，旧接口回归（登录、列表抽查 3 个模块）

**验收**：重启幂等（二次启动不报错、数据不重复）；旧账号能登录、看到的菜单与现状一致。
**回滚**：新表新列均为增量，回退代码即可；必要时 DROP 新表（旧表未动）。

### 卡片 2 ｜ `feat/rbac-2-auth`（M1，无迁移，约 2d）

**前置**：卡片 1。**目标**：认得"人"和"角色"，为后续鉴权铺底。

- [ ] `JwtAuthFilter` 增强：claim 增 `roleCodes`(多角色数组)、`appType`、`warehouseId`；兼容旧单角色 token
- [ ] 新增 `CurrentUser`（userId/username/角色集合/端/当前仓，ThreadLocal），替换散落的 `TmsUtil.currentUser()` 取法
- [ ] `AuthController`：**删除 admin 硬编码后门**（§8.5）；登录改为查库+聚合多角色；登录响应增 `permissions/menus/fields` 占位（菜单树在卡片 3 后返回真值）
- [ ] 登录失败锁定（fail_count/lock_time）、`must_change_pwd`、登录日志写 `sys_login_log_runtime`
- [ ] BCrypt 登录统一；`/auth/current-user` 接口
- [ ] 定义 `@RequirePerm` 注解 + AOP 切面（先建框架，此卡不批量标注解）：无权限返回标准 403 JSON
- [ ] 单测：多角色聚合、锁定、403 切面

**验收**：admin/admin123 正常登录；错误密码 5 次锁定；旧 token 平滑过渡。
**注意**：删后门后必须确认 V102 种子里 admin 存在且密码哈希可用，否则会把自己锁在门外。

### 卡片 3 ｜ `feat/rbac-3-meta-sync`（M1，无迁移，约 3d）

**前置**：卡片 2。**目标**：权限元数据代码化 + 模块菜单管理后端。

- [ ] `MenuConfig`：PC 全量菜单（§7.1 约 70 页面，含三级示例如 `finance > finance.gl > 凭证`）+ PDA/司机端目录；`system.menu` 标 `.adminOnly()`
- [ ] `MenuNode` 解析期校验：深度 ≤3、PAGE 只在 2/3 层、编码唯一，违规启动失败
- [ ] `@RequirePerm` 扫描器：方法路径 → func_code，upsert `sys_func_meta`；标准动作自动派生（§6.4.3）
- [ ] `PermissionRegistry`：`ApplicationReadyEvent` 同步，upsert + 孤儿置 STOPPED；**菜单字段归属**（§6.4.5）：`name/parent/sort_customized` 保护；SYS_ADMIN 自动补全权限
- [ ] `SensitiveFieldRegistry`：26 个字段的 Map key 注册；启动 fail-fast 对拍 `sys_field_meta`
- [ ] `POST /system/perm/refresh`（SYS_ADMIN）、`GET /system/perm/health`（孤儿功能点=0、未鉴权写接口清单）
- [ ] **模块菜单管理后端**（§9.4/§10.3）：
  - [ ] `GET /system/menu/grant-tree`（过滤 admin_only/STOPPED）
  - [ ] `PUT /system/menu-manage/{id}/name|parent`、`PUT .../sort`、`POST .../{id}/reset`
  - [ ] **拦截器硬编码 SYS_ADMIN**（不经 @RequirePerm）；层级/防环/同名/空目录 7 条校验
  - [ ] 全部操作写审计日志（MENU_CHANGE，新旧值 JSON）
- [ ] 单测：同步幂等、自定义标志抗覆盖、四级/防环拒绝
- [ ] dev 工具 `PermInventoryDumper` 产出 `docs/perm-inventory.md`（§18.1），供卡片 5~9 标注解用

**验收**：《方案》MENU-001~012 的后端部分通过；重启两次库里数据稳定；`/perm/health` 全绿。

### 卡片 4 ｜ `feat/rbac-4-data-scope`（M2，实际新增 V103 修复迁移，约 3d）

> 落地补充（2026-09-09）：实现类为 `DataScopeService`/`FieldMasker`；新增 `V103__rbac_field_grant_fix.sql`——V102 内置角色字段授权种子误用 `f.field_id IN ('VIEW_*')`（VIEW_* 是 field_code）致 10 组授权 0 行，V103 按 field_code 补齐。后续迁移号整体顺延：卡片5→V104、卡片6→V105、卡片7→V106、卡片9→V107、卡片10→V108、卡片12→V109（RBAC 占用 V102–V109）。

**前置**：卡片 2（CurrentUser）。可与卡片 3 后半并行。

- [ ] `DataScopeHelper` 七维（仓库/客户/供应商/业务员 SELF·SUB_TREE·ALL/建档人/商品分类/品牌，§5.3）
  - [ ] 输出 SQL 片段 + 参数，适配 JdbcTemplate 字符串拼接（本项目无 MyBatis 拦截器）
  - [ ] 单据主表 join 明细按商品分类/品牌过滤的写法封装（H2 MODE=MySQL 下列名要加表别名限定）
  - [ ] 角色默认范围 + 用户层收窄（只减不增）合并逻辑
- [ ] `FieldMaskingSerializer`：按 `SensitiveFieldRegistry` 对 Map 响应脱敏（无权限置 null），导出/打印链路同样走脱敏（区别见 GLOBAL-002）
- [ ] 先接 4 个典型列表：销售订单、采购入库、库存查询、应收列表
- [ ] 数据权限矩阵测试：5 个角色打同一接口断言行数（§19.5-3）

**验收**：销售员只见自己+下级单、只见授权仓库；无 VIEW_COST 时响应里 costPrice=null 且导出同样脱敏。

### 卡片 5 ｜ `feat/rbac-5-system-annotate`（M2，V104，约 2.5d）

**前置**：卡片 3、4。**目标**：系统管理模块端到端可用。

- [x] system 模块全部 Controller 方法补 `@RequirePerm`（以 perm-inventory 为准）——54/768 端点已标注（system 模块全量，余 637 属卡片6~7 业务模块）
- [x] 用户管理页改造（§10.1）：多角色、绑仓库、数据范围收窄、重置密码二次确认
- [x] 角色管理三栏页（§10.2）：菜单树（用 grant-tree）、全局功能、字段权限、数据范围；内置角色保护
- [x] **模块菜单管理页**（§10.3）：端页签 + 树 + 编辑面板 + 恢复默认；全部 MENU 验收用例（排序用同层整组保存，未做拖拽）
- [x] 个人中心、修改密码
- [x] V104 仅在需要给内置角色补默认授权时才存在（纯插关联表数据；无则不建）——实际需要：sys_user_runtime.remark + sys_pwd_history（最近 3 次历史查重）+ 存量回填

**验收**：✅ 2026-09-09 通过。MENU-001~012 端到端通过（A 段 38 + 跨重启 B 段 15 + 卡片2~4 回归 58，共 111 断言全绿）；建"销售主管"角色 R5_MMGR 配权后用 r5mgr 登录验证菜单裁剪（无任何 system.*、保留 sales.order、写操作 403）。详见 docs/优化记录-PRD28-RBAC.md 卡片5 节。

### 卡片 6 ｜ `feat/rbac-6-sales-purchase`（M2，V105，约 4d，最高优先业务卡）

> 落地（2026-09-10）：**本卡无 Flyway 迁移，V105 未消耗（允许跳号）**；卡片7 开工仍先 `ls migration/` 确认最大号（当前 V104）。

- [x] sales/purchase 全部 Controller 补注解（写操作必须，查询补 view）——10 个业务 Controller + Report/Excel 共 12 类 121 处
- [x] 列表/详情全部接 DataScopeHelper；价格/成本/毛利/采购价字段脱敏（含 ReportController 销售/采购报表旁路加固、maskExport 三入口、MaskProfiles）
- [x] 三个审批型全局功能落地：`global.low_price_approval`、`global.over_credit_approval`、`global.negative_stock_approval`（NEED_APPROVAL 协议 + 弹窗授权人账号密码重放，成功/失败均留痕）
- [x] 全局授予 + 模块级收回的反向收窄逻辑（8 个标准动作白名单 + 角色在目标菜单零 MODULE 配置才回落 global）
- [x] 前端：按钮 `v-permission`、敏感列 `canViewField` 覆盖销售/采购全部页面（GenericBusinessList + 15 个 Drawer/Dialog，零新依赖）
- [x] 越权契约测试（每 Controller ≥1 个 403）+ 冒烟补"低权限"账号场景

**验收**：✅ 2026-09-10 通过。72 项契约断言全绿（401×3、403×16、脱敏×8、GLOBAL×13、DATA×10、三审批闸门×13、留痕×4 等），覆盖 GLOBAL-001~004、DATA-007~009；顺带修复两个真实 BUG（飞单导出 statusText 列 500、订单 Excel 导出 Date 无 Converter 致 0 字节）。详见 docs/优化记录-PRD28-RBAC.md 卡片6 节。

### 卡片 7 ｜ `feat/rbac-7-inv-fin-base`（M2，V106，约 3d）

- [ ] inventory/finance/base 模块补注解 + 数据权限（库存强制按仓库；财务金额脱敏；基础档案按分类/品牌/供应商）
- [ ] 前端按钮/列权限收尾
- [ ] 全模块越权扫描：`/perm/health` 未鉴权写接口清零（或登记白名单）

**验收**：40 条 PC 验收用例全过；低权限账号扫全接口无越权。

### 卡片 8 ｜ `feat/rbac-10-frontend`（M2，无迁移，约 2d，可与 6/7 穿插收尾）

- [ ] 删 `GenericBusinessList.vue` 的 `const roleCode = 'ADMIN'`（全局搜 `roleCode\s*=\s*['"]ADMIN`）
- [ ] AppShell 改用 `/system/menu/user-tree` 渲染，**侧边栏支持三级**；动态路由按树生成；`fallback-menus.js` 仅作降级
- [ ] `v-permission` 指令、`usePerm()/useFieldPerm()` 注册为全局；`/system/menu` 路由守卫仅放 SYS_ADMIN
- [ ] 403/401 拦截分流；权限集 Pinia 缓存与重拉
- [ ] 合并门槛：grep 无 ADMIN 硬编码残留

### 卡片 9 ｜ `feat/rbac-8-pda-login`（M3，V107，约 5d）

- [ ] `POST /wms/app/login`：工号+密码+选仓；JWT 带 `appType=WMS_PDA/warehouseId`；未带该 appType 的旧 token 访问 `/wms/app/*` 返回 401
- [ ] `WmsAppController` 全部操作改读 CurrentUser，create_by/operator 落到自然人
- [ ] PDA 菜单/按钮按 §7.2 矩阵裁剪（60+ 按钮逐项核对 perm-inventory）
- [ ] 仓库强制隔离（所有查询带当前仓）、复检防自检（参数 WMS_RECHECK_SELF_NG）
- [ ] Flutter 登录页 + 首页按 menus 渲染 + `hasPermission` 包装
- [ ] PDA 验收 PDA-001~007

### 卡片 10 ｜ `feat/rbac-9-driver-login`（M3，V108，约 5d，与卡片 9 可并行）

- [ ] 司机登录增强：`base_employee(is_deliveryman=TRUE,status=NORMAL)` 首次登录自动建用户绑 TMS_DRIVER（§5.4）；离职/取消标记拒绝登录
- [ ] 短信验证码表 `sys_sms_code`（5 分钟有效、5 次失败锁 15 分钟）；**dev 保留 888888 回落，prod 启动强制校验短信配置为空则拒启**
- [ ] TMS 接口按 §7.3 矩阵控权（50+ 按钮：签收/拒收/现场退货/结算/退回调度/装车）
- [ ] 司机数据隔离：只返回本人调度单；装车员、不收款司机角色
- [ ] Flutter 登录响应增 permissions/menus，首页与按钮裁剪；权限+参数双控（如 TMS_ONSITE_RETURN_ENABLED）
- [ ] DRIVER-001~007 验收

### 卡片 11 ｜ 上线准备（W7，无独立分支）

- [ ] 预发环境用生产快照演练 V102（重点：长脚本锁表、存量角色兜底授权）
- [ ] 准备上线 SQL 核对脚本（各表行数、管理员权限数、孤儿=0）
- [ ] 上线首日：管理员现场按角色矩阵逐角色补授权；公告默认权限变化
- [ ] prod 短信通道配置确认（否则卡片 10 的启动校验会拦停）

### 卡片 12 ｜ `chore/rbac-cleanup-legacy`（M4，V109，上线观察 ≥2 周后）

- [ ] 删 `SystemController` 硬编码菜单树与分支逻辑、前端 `fallback-menus.js`
- [ ] 评估并下线 `sys_user_runtime.role_name`、`sys_role_runtime.menu_scope/field_scope/data_scope` 冗余列（**H2 不支持 DROP COLUMN IF EXISTS，先确认两库语法，宁可保留不删**）
- [ ] 全量冒烟（先备份 H2）+ 回归
- [ ] 更新《方案》§17 兼容说明，标记旧列状态

---

## 4. 分支依赖图

```
rbac-1-schema ── rbac-2-auth ──┬── rbac-3-meta-sync ── rbac-5-system ──┐
                               └── rbac-4-data-scope ──┬── rbac-6-sales ── rbac-7-inv ── rbac-10-frontend
                                                      ┘                              │
 rbac-8-pda（可 W4 并行）────────────────────────────────────────────────────────────┤
 rbac-9-driver（可 W4 并行）──────────────────────────────────────────────────────────┴── 上线 ──(观察2周)── rbac-cleanup
```

## 5. 每支分支 DoD（完成定义）

1. 后端 `package -DskipTests` BUILD SUCCESS（看 BUILD 结果，不能只 grep ERROR——本机 javac 中文输出会漏）；
2. 前端 `npm run build` 通过；
3. 新增/变更接口有对应权限注解与 ≥1 个 403 用例；数据权限变更有矩阵断言；
4. `docs/优化记录-PRD28-RBAC.md` 已追加；涉及行为变化时同步《方案》；
5. 无冲突标记、无 `console.log`/调试后门、无新外部依赖；
6. 含迁移的分支：本地 H2 全新启动 + 二次启动（幂等）均通过。

## 6. 风险检查点（在 W1/W3/W6 末各评审一次）

| 检查项 | 通过标准 |
|---|---|
| Flyway 占号 | 合并前最大号仍为本分支登记号，否则顺延 |
| 存量用户可登录 | V102 后旧账号登录率 100%，兜底菜单可见 |
| 越权扫描 | `/perm/health` 未鉴权写接口 = 0（或白单有记录） |
| 硬编码残留 | `ADMIN`/`fallback-menus` 新增引用 = 0 |
| 脱敏彻底 | 成本/毛利在列表、详情、导出、打印四链路均不可越权看到 |
| APP 固定验证码 | 888888 仅 dev profile 生效，prod 缺短信配置拒启 |
| 回滚可行 | 每张卡验证代码回退后旧逻辑可用（新表成孤岛不影响） |
