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

### 2026-09-09 卡片1 落地：V102 RBAC 建表与种子（feat/rbac-1-schema）

- 新增 `V102__sys_rbac.sql`：sys_user/role_runtime 扩列；新建 12 张 RBAC 表（user_role_rel、user_warehouse、menu_meta[含 admin_only+3 个 *_customized]、func_meta、field_meta、role_menu/func/field_rel、role/user_data_scope、login_log_runtime、sms_code）；操作日志表扩 4 列。
- 种子：21 个内置角色、26 个字段权限、20 个全局功能 + M_GLOBAL_FUNC 占位；按 §12.3 矩阵授各角色字段权限；39 条角色默认数据范围；存量 U0001 admin 迁挂 R_SYS_ADMIN，旧角色名（管理员组/销售员组/采购员组）映射，无法映射的用户挂 R_SYS_VIEWER 兜底。
- 全部 DDL 用 IF NOT EXISTS、种子用 ON DUPLICATE KEY / MERGE KEY / WHERE NOT EXISTS，可 flyway:repair 后重复执行。
- 踩坑：首版关联表主键 id 用 CONCAT 拼前缀，① 超 32 长度（RFD_R_SYS_VIEWER_F_GOODS_SUPPLIER=33）迁移失败；② 各角色字段授权 id 漏角色前缀会跨角色撞主键。统一改为 `FD_<角色去R_前缀>_<字段去F_前缀>`，最长 32。
- 验证：本机 H2 备份后启动，V102 Successfully applied；Shell 核对行数（角色21/字段26/全局功能20/admin 功能20+字段26/owner字段26/数据范围39/U0001→R_SYS_ADMIN/新列在）；二次启动正常（幂等）。

### 2026-09-09 卡片2 落地：认证改造与功能权限骨架（feat/rbac-2-auth，无迁移）

- `CurrentUser`（ThreadLocal Principal：userId/username/displayName/employeeId/roleCodes/primaryRoleCode/appType/warehouseId，SYS_ADMIN 短路）+ `@RequirePerm` 注解（方法/类，运行时鉴权 + 启动扫描双用途）。
- `RequirePermInterceptor`（HandlerInterceptor，方法注解优先于类）+ `PermissionService`（角色并集查 sys_role_func_rel×func_meta，SYS_ADMIN 放行，请求内缓存，查库失败 fail-closed）；选拦截器不选 @Aspect：离线仓无 aspectjweaver，且注解只标 Controller，零新依赖。`PermissionDeniedException` → 全局处理器 HTTP 403。
- JWT 新增强声明重载：roleCodes(List)/appType/warehouseId/employeeId，保留旧 4 参重载（TMS 司机登录仍在用）；`JwtAuthFilter` 优先读 roleCodes 回落 roleCode，老令牌角色码 ADMIN 归一为 SYS_ADMIN；填充 CurrentUser + 按角色集授权。
- `AuthController` 重写：删除账号不存在时的 admin/admin123 硬编码后门；改从 sys_user_role_rel 聚合多角色（只取 NORMAL）；无角色账号拒绝登录；登录失败 5 次锁 15 分钟（fail_count/lock_time，锁定期内正确密码也拒绝），成功清零并回写 last_login_time/ip；返回 mustChangePwd 与兼容字段 roleCode/roleName（卡片8 后摘除）。
- 配套：SecurityConfig `/system/**`、`/testing/**`、`/flow/**` 由 ROLE_ADMIN 改 ROLE_SYS_ADMIN；OperationLogController.isAdmin 改 CurrentUser 优先 + 双角色回落；TmsNotifyService 通知角色 DISPATCH/ADMIN → TMS_DISPATCHER/SYS_ADMIN；SystemController 两处默认角色码 ADMIN → SYS_ADMIN。
- 登录日志继续写既有 sys_login_log（V102 建的 sys_login_log_runtime 本轮暂不启用，后续卡片再切）。
- 踩坑：①H2（CASE_INSENSITIVE_IDENTIFIERS）列标签全大写，Spring 6.1 queryForList 的 LinkedCaseInsensitiveMap 让 get("roleId") 侥幸命中、但原始 Map 直接 JSON 序列化键全大写；角色行改为显式重建驼峰键 Map。②打包 jar 被运行中进程占用导致 repackage 重命名失败，先 taskkill 8080 端口进程。
- 验证：mvn -o package BUILD SUCCESS；admin/admin123 登录返回 roleCodes=[SYS_ADMIN] 且 JWT 含 roleCodes/appType 声明；current-user 正常；/system/** 带 token 200、无 token 401；连错 5 次后正确密码返回「账号已锁定，请 15 分钟后再试或联系管理员」；H2 Shell 已将 admin fail_count/lock_time 复位，sys_login_log 成功/失败/锁定三类记录齐全；二次启动迁移幂等。

### 2026-09-09 卡片3 落地：权限元数据自动同步 + 模块菜单管理后端（feat/rbac-3-meta-sync，无迁移）

- 代码即元数据：`MenuNode`（链式节点，addChild 强校验 L1 DIR / DIR 仅 L1-2 / PAGE 仅 L2-3 / 深度≤3）+ `MenuConfig`（12 棵根：ERP 10 端 + WMS_PDA + DRIVER，192 节点；财务管理>总账 为三级示例）+ `MenuCatalog`（@PostConstruct 校验根类型/深度/编码唯一/父子链）。
- `@RequirePerm` 扫描 + 标准派生：`PermissionRegistry` 在 ApplicationReadyEvent（及 POST /system/perm/refresh）同步——PAGE 自动派生 8 基础功能点，statePage 或路径探测命中 audit/approve/close 再加 3 状态机功能；@RequirePerm 声明覆盖派生；非 global 功能点前缀必须命中已声明菜单，否则启动失败；MODULE 孤儿置 STOPPED（GLOBAL V102 种子豁免），代码删除菜单置 STOPPED 不物理删；同步后给 R_SYS_ADMIN 自动补齐全部 NORMAL 菜单/功能/字段。菜单名/上级/排序受 *_customized 标志保护，路由/类型/图标/admin_only 由代码强制。
- 敏感字段 `SensitiveFieldRegistry`：26 个 field_code 绑定响应 key（驼峰+下划线两套），@PostConstruct 与 sys_field_meta 对拍，缺码 fail-fast；卡片4 脱敏器消费。
- `MenuMetaService` + `MenuManageController`（/system/menu-manage/**）：改名/换上级/批量排序/单节点恢复/整树恢复；7 条保存校验（层级≤3=目标父层级+子树深度、DIR/PAGE 层位、防环、空目录二次确认 needConfirm、同级同名、admin_only 禁移动可改名、STOPPED 禁移动）；每个变更写 MENU_CHANGE 审计（RENAME/MOVE/SORT/RESET/RESET_ALL，新旧值 JSON）；写操作再硬编码 SYS_ADMIN 双保险。
- 查询：`PermissionQueryController`（/system）提供 menu/tree（全量含 STOPPED+标志）、menu/grant-tree（排 admin_only/STOPPED、剪空目录）、func/list、field/list、perm/mine（自助）、perm/refresh、perm/health（同步统计 + 未纳管写端点清单，供卡片5~7 收敛）；旧 `/system/menu/user-tree` 改为 DB 实时三级树，SystemController 130 行硬编码树与 filterMenus/menuScope/menu() 辅助全部删除；无授权用户返回空树不做全量兜底。
- SecurityConfig：`/system/menu/user-tree`、`/system/perm/mine` 提到 `/system/**` 封禁之前，登录用户可取「自己的」权限；其余 /system/** 仍仅 SYS_ADMIN。
- dev 工具 `PermInventoryDumper`（@Profile("dev")）：每次启动反射全部 Mapping 生成 docs/perm-inventory.md（750 端点；模块/HTTP/路径/Handler/建议功能点编码/归属菜单/动作/注解状态；非标准动作 biz_ 前缀），生产 profile 不加载。
- 踩坑：①actuator 引入第二个 RequestMappingHandlerMapping（controllerEndpointHandlerMapping），注入必须 @Qualifier("requestMappingHandlerMapping")；②链式 builder 的 page() 返回父目录，`.page().adminOnly()` 会标到根上——新增显式 adminPage()/statePage() 方法（14 个状态机页、3 个专属页已改），首跑曾把 system 根误标 admin_only，重启强制同步自动纠正；③内置角色 role_id 是缩写（R_SAL_CLERK）而 role_code 是 SALES_CLERK，无外键的关联表插入不报错只产生悬空授权，后续卡片造数据先 SELECT role_id；④H2 文件被运行中后端独占，Shell 直连需先停进程（AUTO_SERVER 对非 AUTO_SERVER 打开的库无效）。
- 验证：mvn -o package BUILD SUCCESS；连续 6 次启动，同步最终 192 菜单/1560 功能点（1516 模块+20 全局+24 显式状态机）/26 字段，第 2 次重启起 0 新增 0 停用（幂等），改名自定义跨重启保留；admin 全量树含三级总账、grant-tree 不含 system.user/role/menu；造 R_SALES_CLERK 测试用户（3 菜单+目录+3 功能）验证 user-tree 只见工作台/销售、perm/mine 恰返回 3 功能码、6 个管理端点全 403、匿名 401、零授权用户空树；改名同名/空名、移动专属页/到根/到 PAGE 下均 400 中文报错；空目录 confirm=false 返回 needConfirm 不落库、confirm=true 执行并在用户树剪枝；批量排序跨父级拒绝同父级成功；单节点/整树恢复默认均回代码值；sys_operation_log_runtime 审计齐全；验证后测试用户/授权/登录日志/临时文件已清理。

### 2026-09-09 卡片4 落地：七维数据权限引擎 + 字段脱敏 + 4 试点列表（feat/rbac-4-data-scope，V103）

**新增代码**

- `common/security/datascope/DataScopeService.java`：七维数据权限引擎（§5.3）。
  - 流式声明 `dataScope.target().warehouse("so.warehouse").customer(...).supplier(...).salesman(...).owner(...).creator("so.creator_name").goodsColumn("b.goods_code")/goodsLines(主单表达式, 明细表, 明细外键).build()`，输出 `ScopeClause`（SQL 片段 ` AND (...)` + 有序参数，JdbcTemplate 直拼；采购入库走 MyBatis-Plus QueryWrapper，把 `?` 转 `{n}` 占位用 `qw.apply` 注入）。
  - 解析（请求级缓存 attr `rbac.dataScope`）：多角色 sys_role_data_scope 同维 UNION；sys_user_data_scope 与角色结果 INTERSECT（只减不增，ALL×ids/SELF×ids/regions 均有交叠规则）；sys_user_warehouse 绑定仓强制收窄 WAREHOUSE；PDA token 内 warehouseId 强制本仓（§5.3.3-7）；SYS_ADMIN/无登录人短路。
  - 旧表存量列存的是名称/编码不是 ID：仓库/客户/供应商/业务员/商品全部翻译成名称子查询（base_warehouse.warehouse_name、base_customer.customer_name[salesman=当前业务员名 / territory 片区]、base_supplier.supplier_name、base_employee.employee_name 递归 parent_salesman 层级≤5、base_goods 按 category_name 子树（parent_id BFS≤10 层）/brand_name）。
  - 商品维度：行表直接 `goods_code IN (SELECT ... FROM base_goods)`；主单 EXISTS 明细子查询（全部明细不可见则主单隐藏），销售订单另加关联 SUM 子查询用可见明细重算 amount（注意 JDBC 参数绑定顺序：SELECT 子查询参数先于 WHERE）。
  - 规则语义（验收中校正）：仅"角色零维度配置（且无绑定仓/PDA 强制仓）"才触发 DEFAULT DENY——有建档人列回落 `creator_name=当前用户姓名`，无建档人列 fail-closed `1=0`；目标声明但角色没配的维度按典型角色表的「—」处理＝不加条件，不参与兜底。配置了但解析为空（SELF 未绑员工等）一律 `1=0` fail-closed。
- `common/security/FieldMasker.java`：Map/List 递归脱敏，按 SensitiveFieldRegistry 的响应 key→field_code 映射，无权限置 null（键保留）；SYS_ADMIN 短路，查库异常 fail-closed；提供 `mask(payload, allowedCodes)` 供无 ThreadLocal 场景复用。
- `PermissionService` 增加 `hasField/currentFieldCodes`（sys_role_field_rel×sys_field_meta status=NORMAL，请求级缓存 attr `rbac.fieldCodes`，超管返回空集=不脱敏）。

**4 个试点接线**：销售订单（OrderController.salesPage + 销售/采购订单详情脱敏；顺带把销售/采购订单建档人从硬编码"系统管理员"改为当前登录人）、采购入库（PurchaseController.inboundPage，QueryWrapper apply）、库存查询（InventoryController.balancePage，可变 ArrayList 承载过滤结果后脱敏）、应收列表（FinanceController.arPage，业务员列 `COALESCE(c.salesman,a.salesman)`）。通用导出 `ExcelController.export` 查询后立即过脱敏器（GLOBAL-002 导出与页面同一套）。

**V103__rbac_field_grant_fix.sql（修复 V102 种子缺陷）**：V102 的 10 组内置角色字段授权 WHERE 误写 `f.field_id IN ('VIEW_SALE_PRICE',...)`（VIEW_* 是 field_code，field_id 实为 F_SALE_PRICE 等），INSERT...SELECT 静默 0 行——销售主管/销售员/采购主管/采购员/财务出纳/仓库岗/PDA 5 岗/PDA 主管/调度/司机全部零字段授权（仅 4 个 CROSS JOIN 全字段角色不受影响）。V103 按 `f.field_code IN (...)` 用 NOT EXISTS 防重补齐；已对生产影响评估：升级 V102 的环境这些角色本就看不到金额列，修复只会"放开应有权限"，无收窄风险。后续卡片迁移号顺延（卡片5→V104 … 清理卡→V109）。

**配套修复**

- `SensitiveFieldRegistry`：① key 映射表改 `CASE_INSENSITIVE_ORDER`——H2 未加引号列标签驱动返回大写下划线（LATEST_PURCHASE_PRICE），而 Java 手工映射行是驼峰，小写绑定两边漏一边；② 补绑 standard_price→VIEW_SALE_PRICE、min_sale_price→VIEW_MIN_PRICE、suggested_retail_price→VIEW_SUGGEST_RETAIL_PRICE（商品导出列）。
- `ExcelController.buildBody`：6 个模块的 `List.of(...)` 改 `Arrays.asList(...)`——脱敏后敏感列是 null，`List.of` 抛 NPE 导致导出 500。
- `pom.xml`：显式锁 `commons-io:2.15.1`。easyexcel 3.3.4 传递 2.11.0 但其 doWrite 已用 2.12+ 的 `org.apache.commons.io.build.AbstractStreamBuilder`，任何真实导出都 NoClassDefFoundError（此前被上面的 NPE 抢先抛出未暴露）。仅版本提升，无新依赖。

**踩坑**

1. INSERT...SELECT 的 WHERE 不匹配时**静默插入 0 行**，Flyway 成功、启动正常，只有矩阵测试抓到"销售员字段集为空"；种子脚本对拍行数不能只看迁移成功。
2. DEFAULT DENY 首版误按"目标声明但角色缺失的维度"兜底 creator=self，导致只绑了仓库的仓管员在销售单列表 0 行；《方案》§5.3.3-5 原文是"角色**完全未配置任何**数据范围维度"，典型角色表「—」=不限制。
3. 商品分类勾选父类含全部子类（§10.2），夹具中"食品"是"饮料"父类，配 食品∩A牌 会同时命中 G1(饮料/A) 与 G3(食品/A)——引擎行为正确，是测试期望写错。
4. EasyExcel 3.x 字符串默认写 inlineStr 不进 sharedStrings.xml，验导出 xlsx 要解 `xl/worksheets/sheet1.xml`；Windows 解压用 `C:/Windows/System32/tar.exe`（bsdtar 认 zip），PATH 里的 GNU tar 和 Git Bash 都不认 zip。
5. 原生 Windows node 不认 MSYS `/tmp`，临时文件一律放工程目录。

**验证**：H2 先备份 `erp-v1.mv.db.bak-rbac4`；夹具（11 用户/10 角色/8 销售单/2 入库/4 库存/2 应收/4 商品含父子分类）跑 34 项断言全绿——销售单 9 视角矩阵（admin/老板 8、绑定甲仓仓管 4、SELF 销售员 3、SUB_TREE 两级主管 6、分类∩品牌 3、老板∩用户层乙仓收窄 4、采购岗跨维 3、零维度角色 0）、采购入库/库存/应收同构、scoped_amount 可见行重算、无 VIEW_COST 时 costPrice/stockAmount/availableQty=null 而 physicalQty 保留、销售员应收金额字段（V103 修复点）正常、导出 xlsx 无权限不含 12.34/有权限含/非敏感编码列保留、匿名 401；管理端真实库 admin 四列表与商品导出回归正常。夹具与 10 个测试账号、登录/操作日志按 RBAC4/U_R4_ 前缀全部清理（18 张表 COUNT=0），临时 SQL/脚本/xlsx 已删，备份确认无误后删除；后端日志 0 ERROR。

### 2026-09-09 卡片5 落地：system 模块全量权限标注 + 用户/角色/菜单管理三页 + 个人中心（feat/rbac-5-system-annotate，V104）

**后端**

- `RbacUserAdminController`（/system/rbac/user/**，660 行）：分页/详情/新建/编辑/停用启用/解锁/重置密码；多角色分配（sys_user_role_rel，仅 NORMAL 角色可挂）、绑仓库（sys_user_warehouse）、用户层七维数据范围收窄（sys_user_data_scope，与角色结果 INTERSECT 只减不增）；员工选项 GET /employees、范围值选项 GET /scope-values（仓库/客户/供应商/业务员/分类/品牌，按维度关键词搜）；内置 admin 账号禁删禁停用禁解绑超管角色；全部方法 `@RequirePerm("system.user.*")`。
- `RbacRoleAdminController`（/system/rbac/role/**，458 行）：角色分页/新建/编辑/复制/停用启用/删除（无用户挂载才允许删）；grants 四分区整存（菜单 menuIds/功能 funcCodes/字段 fieldCodes/数据范围 dataScopes，全量替换事务内完成）；**内置角色保护**：菜单/功能/字段三分区拒绝改写、仅数据范围可调（§12.2）；越权功能点/菜单 code 服务端按 func_meta/menu_meta 白名单过滤，防伪造提交。
- `PasswordService`（100 行，新增）：改密/重置统一入口——强度校验（8+ 位含字母数字）、**不能与最近 3 次历史相同**（sys_pwd_history 留最近 3 条）、重置/新建置 must_change_pwd=TRUE、本人改密成功清除该标记；哈希只写库不打印不回传。
- `AuthController` 增补个人中心：GET /auth/profile（登录人资料+多角色+绑仓+数据范围，roleCodes 由 roles[].roleCode 派生）、PUT /auth/profile（昵称/手机）、POST /auth/change-password（验旧密码+历史查重，强制改密场景同一接口）。
- system 模块全量标注：SystemController、SystemLogController、PermissionQueryController、RbacUserAdminController、RbacRoleAdminController、MenuManageController 等共 **54 个端点**挂 @RequirePerm（perm-inventory：总 768/已标注 54/余 637 待卡片6~7）；菜单写操作维持「控制器 requireSuperAdmin + SecurityConfig `/system/**` hasRole SYS_ADMIN」双保险，自助查询（user-tree、perm/mine）放行顺序在前。
- `V104__rbac_user_mgmt.sql`：sys_user_runtime 加 remark 列；新建 sys_pwd_history(user_id, password_hash, created_at) + 索引；存量账号当前密码回填为首条历史（NOT EXISTS 幂等），避免上线后第一次改密就与「当前密码」撞历史校验。

**MENU-012 审计修复（真实 BUG，非测试妥协）**

- 现象：菜单改名/移动/排序后，操作日志列表页「操作内容」列为空。根因：旧 4 参 `opLog.log(module, action, bizNo, detail)` 只写 detail 列（结构化 JSON），而列表页 SELECT 展示的是 operation_content 列。
- 修复：OperationLogService 新增 `logContent(moduleCode, action, bizNo, detailJson, content)` 双写通道——detailJson 进 detail 保留机器可读结构，content 进 operation_content 做人话摘要；RecB builder 同步加 content。MenuMetaService 五个审计点改写带 menuId 前缀的可读内容：RENAME「[M_sales_order] 菜单「销售订单」重命名为「订单管理」」、MOVE「菜单「销售发货单」更换上级：「销售管理」→「总账」」（新旧上级均翻译为菜单名，null=「一级菜单」）、SORT「「销售管理」下 2 个菜单同层排序」、RESET/RESET_ALL 同理；opLog 整体 try/catch 永不影响业务。

**前端（Vue 3 setup，原生控件，无新依赖）**

- `views/system/UserManage.vue`（625 行）：用户列表（查询条件仅「查询」按钮触发）+ 编辑抽屉（基本信息/多角色勾选/绑仓/七维范围收窄表格）+ 重置密码二次确认弹窗 + 停用/启用/解锁；按钮全部 v-if 功能点（卡片8 统一切 v-permission 指令，本卡先用 store 判定）。
- `views/system/RoleManage.vue`：三栏角色管理（左角色列表含内置角标/停用态、中 MenuGrantTree 授权树（grant-tree 排 admin_only/STOPPED/空目录）、右功能点（全局+模块分组）+字段权限+DataScopeEditor 数据范围）；内置角色三分区只读、复制角色带完整四分区；ScopeValuePicker 按维度搜选项。
- `views/system/MenuManage.vue`：模块菜单管理——三级树（DIR/PAGE/STOPPED 分色、自定义标志角标）+ 编辑面板（改名、换上级下拉含层级提示、同层排序）、单节点「恢复默认」与「整树恢复」（confirm 参数二次确认，前端二次弹窗）；所有写操作仅超管可见入口，后端再硬校验。
- `components/rbac/`：ChangePasswordDialog（普通/forced 两态，forced 无关闭无遮罩退出，mustChangePwd 时全屏强制）、ProfileDialog（个人中心内嵌修改密码）、MenuGrantTree、DataScopeEditor、ScopeValuePicker 共 5 个组件；api/rbac.js 收口全部 /system/rbac、/system/menu-manage 请求，client.js 补 403 统一提示。
- 接线：auth store 增加 isSuperAdmin（roleCodes 含 SYS_ADMIN，兼容老令牌 ADMIN）/mustChangePwd 计算属性、fetchProfile（并发复用同一 Promise，token 持久化+刷新补拉 profile）、markPasswordChanged；路由注册 system-menu（meta.superAdmin），守卫改 async——直连超管页未拉 profile 先补拉、非超管重定向首页；AppShell 头像改下拉菜单（个人中心/修改密码/退出登录）、侧栏二级菜单按 adminOnly × isSuperAdmin 裁剪、挂载强制改密弹窗；fallback-menus 增「模块菜单管理」adminOnly 节点。

**踩坑**

1. **mvn package 前必须先停后端（本卡最重事故）**：后端运行中执行 repackage，Windows 下 jar 被 JVM 独占，rename jar→jar.original 失败的同时 spring-boot-maven-plugin **已把运行中的 fat jar 从 97MB 截断成 1.36MB 薄 jar**；旧 JVM 进程还活着但 classpath 文件被覆写，随后任意请求抛 NoClassDefFoundError（JdbcTemplate$1UpdateStatementCallback）。处理：TaskStop → 确认 8080 FREE → 重新 package 恢复 97,194,699 字节。纪律：任何 package/Shell 直连前先停服确认端口。
2. 权限自助接口真实路径是 `/system/perm/mine`（控制器挂 @RequestMapping("/system")），脚本误打 /perm/mine 返回 500「No static resource」；SecurityConfig 显式提前放行的也是 /system/perm/mine。
3. reset-all 因事故中断后菜单自定义标志残留库中：停服后 H2 Shell 对 is_system=TRUE 行三标志清零，重启时 PermissionRegistry.sync 按代码重算默认名/上级/排序——验证了「标志兜底清零 + 启动同步重算」这条人工还原路径有效。
4. H2 Shell 直连仍须显式盘符 URL（jdbc:h2:E:/work/erp-wms-tms/backend/data/erp-v1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE），先停后端，sa 空密码。

**验证（共 111 条断言全绿）**

- MENU 端到端 A 段 38/38：真实「销售主管」角色 R5_MMGR + 用户 r5mgr——MENU-001 用户树无任何 system.* 且保留 sales.order、非超管取管理树 403；MENU-002 改名/移动/整树恢复 403 矩阵；MENU-003 授权树无 system.menu；MENU-004 改名+nameCustomized+被授权用户树即时显示新名+空名拒绝；MENU-006 销售发货单移入财务>总账成三级；MENU-007 致四级的自挂拒绝；MENU-008 自挂/祖先挂后代防环；MENU-009 同层排序+跨父级拒绝+sortCustomized；MENU-011 改名移动后按 menu_code 的授权/功能点不失效；MENU-012 RENAME/MOVE/SORT 三条留痕含操作人 admin/时间/menu_id/新旧值。
- 跨重启 B 段 15/15：MENU-005 改名、MENU-006 移动、MENU-009 排序均抗启动同步；MENU-010 三节点 reset 名称/上级/顺序/标志全还原；整树恢复缺 confirm 拒绝、confirm=true 执行、全树零标志残留；恢复后授权树完整。
- 卡片2~4 回归 58/58；`npm --prefix frontend run build` 通过（UserManage/RoleManage/MenuManage 独立 chunk 齐全）；后端 mvn -o package BUILD SUCCESS。
- 数据安全：操作前备份 `backend/data/backups/erp-v1.before-menu-retest.mv.db`（另有 before-rbac5）；夹具清理 SQL 执行后 H2 直查——R5 夹具用户/角色=0、system.menu 审计行=0、is_system 自定义标志=0，R_SAL_MGR 还原为 16 字段/5 范围/0 菜单种子，全库 users=1(admin)/roles=24 回到干净种子；全程未打印任何密码哈希，夹具密码走 API 明文 'Passw0rd!'。

### 2026-09-10 卡片6 落地：销售/采购全量鉴权脱敏 + 三条审批红线（feat/rbac-6-sales-purchase，无迁移）

> 本卡**未消耗 Flyway 版本号**（V105 空出，允许跳号）；卡片7 开工仍按纪律先 `ls migration/` 确认最大号（当前 V104）。

**后端：12 个类 121 处 @RequirePerm**

- 销售 6 个 Controller：OrderController 16（销售/采购订单同源，create/update/page/detail/goods-price/audit/unaudit/cancel/delete/batch-* 写操作逐项、查询挂 view）、FlyOrderController 14、SalesReturnController 21、SalesOutboundController 7、SalesReceiptController 6、RejectInboundController 6；采购 4 个：PurchaseReturnController 19、PurchaseInvoiceController 15、PurchaseController 7、PurchaseReceiptController 5；另 ReportController 3、ExcelController 2。
- 七维 DataScope 推广到销售/采购全部列表与详情：销售侧 warehouse/customer/salesman/creator/goodsLines，采购侧 warehouse/supplier/owner(采购员)/creator/goodsLines；商品维度受限时主单金额继续用卡片4 的可见明细 `scoped_amount` 子查询重算（SELECT 参数先于 WHERE 绑定）。
- 价格/成本/毛利脱敏：page/detail/goods-price 经 FieldMasker；新增 `common/security/MaskProfiles.java` 收口 SALES_BILL/PURCHASE_BILL 的 key→field_code 映射（蛇形 paid_amount/inbound_amount/invoiced_amount 与驼峰两套）。

**三条审批红线（common/security/approval/ 新包，ApprovalService + ApprovalType + NeedApprovalException）**

- 闸门点：销售订单保存时低价（OrderController.createSales，库存校验后 INSERT 前）、销售订单审核时超信用（auditSales，PENDING 校验后）、出库审核负库存（SalesOutboundController.audit，抢占 PENDING→AUDITING、probeNegativeStock 之后）。
- 协议：本人持 `global.*_approval` 功能直接放行（SOURCE_SELF，免弹窗）；无凭证抛 NeedApprovalException → **HTTP 400 + body `{code:'NEED_APPROVAL', data:{approvalType,bizNo}}`**；前端弹窗收账号密码合并进**原请求体**（approverAccount/approverPassword）重放一次。
- 授权人校验链：账号存在→未停用→未锁定→密码正确→userIdHasFunc 确实持权→不能给本人授权；通过 `opLog.logContent` 双写（`<TYPE>_APPROVAL`，detail JSON + 含"授权通过/操作员/授权人/单号/原因"的人话 content），失败 `opLog.logFail`（result='FAIL'，原因写 fail_reason 列，防暴力试密码审计），日志异常永不影响业务。
- **负库存产品口径（验收中校正，需同步《方案》）**：余额表无记录（该商品从未入库）→"该商品从未入库，负库存授权也无法出库，请先做入库"，**授权也不能放行**；有余额行但可用量（含来源单锁回补）不足 → 可授权放行；批次可用量另校验。
- **反向收窄（全局授予 + 模块级收回）**：`MODULE_TO_GLOBAL_ACTION` 仅 8 个标准动作可回落（audit/unaudit/close/delete/import/export/print/log→log_view）；判定=①显式模块功能点命中即放行；②否则按角色遍历，该角色持对应 global.* **且其在目标菜单下零个 MODULE 功能点配置**才回落放行。角色一旦在某菜单配过任何模块点（含仅 view），global 对该菜单即收窄；view/add/edit/biz_* 永不回落。前端有效集由 `effectiveCodes()` 同规则展开。

**报表旁路加固（GLOBAL/DATA 验收前的真实缺口）**

- ReportController 的 /report/sales/page、/report/purchase/page 此前完全不经业务 Controller：无鉴权、无 DataScope、金额裸出。补 `report.sales.view`/`report.purchase.view`（菜单码是 report.sales/report.purchase，不是 salesReport——非 global 功能点前缀必须命中 MenuCatalog 否则启动 fail-fast）、七维 DataScope、FieldMasker + 驼峰转换；dashboard/chart/stock/finance 留卡片7。/report/export 挂 global.export。
- GLOBAL-002 导出脱敏三入口统一走新 `maskExport(payload[, profile])`：超管跳过；无 `global.data_export_sensitive` 时先移除 EXPORT_RESTRICTED_FIELDS（VIEW_COST/VIEW_COST_AMOUNT/VIEW_STOCK_COST/VIEW_PROFIT/VIEW_PROFIT_TOTAL）再 walk；入口=ExcelController 通用导出（salesOrder→SALES_BILL、purchaseOrder→PURCHASE_BILL、default 无 profile）、FlyOrderController 飞单导出与飞单明细导出（FLY_EXPORT_FIELDS：采购价/采购金额→采购类字段、销售价/销售金额/毛利→销售/利润类字段）；通用导出 queryOrderExport 对销售/采购订单补全维 DataScope。

**两个顺带修复的真实 BUG（非测试妥协）**

1. **飞单导出对所有用户 500**：FlyOrderController.export 的 SQL 选了表中不存在的 Java 计算字段 `statusText AS 状态文本`（H2 Column "STATUSTEXT" not found [42122]）。删除该列，中文状态仍由后续 Java switch 计算，无依赖损失；全仓 grep 无同类残留。
2. **销售/采购订单 Excel 导出 0 字节 + HTTP 200**：ExcelController.buildBody 两处把 bill_date（java.sql.Date）直写 EasyExcel，3.3.4 无内置 Date Converter 抛 ExcelWriteDataConvertException，响应头已提交后异常 → 下载到 0 字节 xlsx 且状态码 200（极难被发现）。新增 `fmtDate()` 在 Java 层统一转字符串（sql.Date/Timestamp/Util Date/LocalDate/LocalDateTime 全覆盖），两个 case 应用。

**前端（Vue 3 setup，零新依赖）**

- 基建 5 个新文件：`directives/permission.js`（v-permission 无权限移除元素 / {disable:true} 改禁用；v-action-perms 供操作区/批量栏扫描）main.js 全局注册；`composables/useRbac.js`（16 模块 MODULE_MENU 映射、permOf/codesForAction、canViewField/canViewColumn、actionHidden/guard）；`composables/usePerm.js`、`stores/perm.js`（权限集缓存）；`api/approval-dialog.js`（原生 DOM 授权弹窗，TITLE_MAP 三类标题、textContent 防注入、并发去重不叠加）。
- `api/client.js` 拦截 body `code==='NEED_APPROVAL'`（与 HTTP 状态无关）→ 弹窗 → 合并 approverAccount/approverPassword 重放，`__approvalRetried` 标志保证最多一次；取消则按原业务错误提示。
- `GenericBusinessList.vue`：列集经 canViewColumn 过滤、明细弹层单价/金额/成本金额三个敏感列 v-if、行操作与左下角批量栏 actionHidden/guard 控权；15 个销售/采购 Drawer/Dialog（Bill/BillDetail/FlyOrder/SalesOutbound/ReceiptSign/SalesReturn 两态/RejectInbound/PurchaseInbound/Receipt/Return 三态/Invoice/Expense）保存/审核/加行/删行按钮全部 v-permission，价格字段 canViewField。
- 抽查结论：销售/采购页面全部经 GenericBusinessList 收口无旁路；全仓无角色码 ADMIN 硬编码新增；旧 composables/usePermission.js 已无任何引用（死文件，与零按钮零引用的 FallbackBusinessList.vue 一起留卡片12 清旧）；客户价格调整/查询等 4 页面走 /base/ 接口，归卡片7。

**踩坑**

1. 业务错误在本系统是 **HTTP 200 + body code='400'**（e.status 恒 200，必须看 body）；唯 NEED_APPROVAL 走 HTTP 400，前端按 body code 分流两种都能兜住。
2. 手开（无来源订单）出库单 sourceOrder 空串落库撞 `UK_SALES_OUTBOUND_SOURCE_ORDER`（NULLS FIRST，空串只许一条）；契约改用唯一占位单号（查不到订单即按手开处理，不做余量校验）。
3. H2 的 LIKE **不支持 `[_]` 字符类转义**（`LIKE 'R6[_]G%'` 实测 0 行），下划线要么裸写 `LIKE 'R6_G%'`（确认无 R6xG 干扰数据）要么 `ESCAPE`；清理盘点首轮因此全 0，靠等值计数才定位。
4. 契约每轮自建 PENDING 超信用单（客户额度 1 元、120 元必超）与一次性专属客户 R6_RC_<ts>，避免多轮累积单被审核/外行业务员单污染集合断言；报表"G2 整张不可见"必须用专属客户隔离，否则历史低价单的 G1 行会被误计。
5. xlsx 断言：EasyExcel 3.x 字符串走 inlineStr（`<c t="inlineStr"><is><t>`）非 sharedStrings，null 是自闭合空 c；解包用 `C:/Windows/System32/tar.exe`（bsdtar 认 zip），单元格正则要同时兼容 inlineStr/`<v>`/自闭合三种形态。
6. RunScript 执行 DML 不打印 SELECT 结果，盘点用 Shell -sql；长 SQL 写 UTF-8 文件 `-sql "$(cat file)"`（双引号内单引号原样保留），别在 bash 单引号串里写 SQL 字符串字面量（'' 转义在 bash 单引号内不生效，引号被静默吞掉）。

**验证（72 项契约断言全绿，0 FAIL）**

- 匿名 401×3；零权账号 403×15（10 Controller + 采购订单 + 销售/采购报表 + 2 导出点）+ 只读账号审核 403；脱敏 8（订单 page/详情/goods-price：非敏感键保留、金额/单价/采购价 null）；
- GLOBAL-001 通用导出 5（有权 200 PK 头/无权 403/5 单齐全/无字段权金额列空/admin 有值 120）；GLOBAL-002 飞单导出 5（exp 毛利 null 且采购/销售金额按字段权保留、exps 毛利=40）；GLOBAL-003 反向收窄 3（销售 audit 403、admin 造采购单、仅持 global.audit 且菜单零配置的 gap 回落放行 APPROVED）；
- DATA 10（分类/品牌/SUB_TREE/SELF 集合行数、可见行金额、报表 view、报表 G1 可见行金额重算、专属客户 G2 整张隐藏、报表估算毛利 grossProfit 脱敏、sov 报表 403）；
- 低价闸门 5（NEED_APPROVAL→错密码/无权账号失败留痕→正确授权重放成功→持权人 SELF 免凭证）；超信用 3（每轮自建 PENDING 单/NEED_APPROVAL/boss 重放 APPROVED）；负库存 5（两张单/NEED_APPROVAL/持权人重放 APPROVED/boss 零模块回落+SELF）；
- 留痕 4（成功 content 含操作员/授权人/原因/单号、失败 result=FAIL 且 fail_reason 含"密码|权限"≥2、超信用单号、负库存出库单号）。
- 双构建绿：`mvn -o compile` RC=0；停服后 `mvn -o package -DskipTests` RC=0（fat jar 97,232,972 字节）；后端运行日志 grep ERROR=0、Resolved 异常=0，唯一 WARN 为 Flyway 对 H2 2.2.224 的版本兼容提示；`npm run build` 通过（合并前复跑）。
- 数据安全：停服后备份 `backups/erp-v1.pre-rbac6-cleanup-20260910-003535.mv.db`（另有 20260909-232414 一份）；清理后 H2 直查 30 项 R6 痕迹=0（12 用户/12 角色及 8 张关联表悬空行=0、R6 商品/客户/员工/分类/品牌=0、销售/出库/发货/飞单/采购订单及明细=0、R6BAL1 余额与 4 条库存流水=0、u_r6 操作/登录日志=0），admin 与 24 内置角色完好；本开发库商品/客户/供应商/仓库主数据本就为空种子，删除条件全部按 R6 编码精确命中未做整表清空；tmp-rbac6/ 临时脚本/SQL/xlsx 验收后删除；未打印任何密码哈希，夹具密码走 API 明文（'Passw0rd!'，admin/admin123）。
