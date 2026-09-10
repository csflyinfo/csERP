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

### 2026-09-10 卡片7 落地：基础档案/库存/财务/GL/报表全量鉴权 + 前端按钮列权限收尾（feat/rbac-7-inv-fin-base，零迁移）

> 本卡**未消耗 Flyway 版本号**（V105 空出、V106 原规划给本卡均未使用）；权限同步日志「菜单 192 / 功能点 1620（新增 42）/ 26 字段对拍通过」。后端启动 RC=0、fail-fast 通过；`docs/perm-inventory.md` 由 PermInventoryDumper 启动时自动重生成（770 端点 / 509 已挂 @RequirePerm / 238 未纳管，其中写操作经 /system/perm/health 实测仅余 155，全部归属 WMS/TMS，登记给卡片9/10，**卡片7 范围（base/inventory/finance+GL/report/transfer/system PC 端）写端点清零**）。

**后端：30 改 + 2 新类**

- base 111 端点（BaseController/BaseMasterController/CustomerPriceController/GoodsPriceAdjustController/PriceGroupExtController/DictionaryController）：CRUD/导入/停用/冻结/销量排序/选择器逐端点挂功能点，view/add/edit/delete/import/biz_stop/biz_freeze/biz_sale_ranking/biz_selector/customActions（客户价 biz_stop/biz_submit/biz_reject/biz_confirm、价格组 biz_toggle/biz_goods_count）；选仓/选人等登录态通用查询用 @ProgrammaticPerm（新注解 common/security/ProgrammaticPerm.java，标记「有注解、鉴权在方法内按场景程序化判定」，同样从 health 未鉴权清单排除，但语义区别于白名单前缀）。
- 七维数据范围：库存三表（balance/lock/flow）强制 warehouse + goods 分类/品牌（WarehouseScopeGuard 新类统一列表/锁批/调整出入参的仓库校验，**无任何维度配置 → 1=0 fail-closed**）；调拨/报损/其他出入库/盘点同口径；财务全部列表/详情 appendCpScope（CUSTOMER/SUPPLIER 维度 OR 门控：配任一维即按所配收窄，零维 fail-closed），核销弹窗数据源、对账单 available-bills 一并收窄；基础档案按分类/品牌/供应商维度过滤（goodsLines 可见明细 scoped_amount 子查询复用卡片6）。
- FieldMasker 26 字段注册表的关键绑定收口：财务 FUND_AMOUNT_KEYS（裸键名 price 也绑 VIEW_FUND_FLOW）、AR/AP 余额（arBalance/debtAmount/receivableBalance 与 AP 对称）、对账单金额、客户价 12 键（customerPrice/agreedPrice/price/oldPrice/newPrice…）、价格组 groupPrice/deliveryPrice、商品调价、资金账户裸 "balance"（BaseMasterController fundAccount override 绑 VIEW_FUND_ACCOUNT_BALANCE）、核销明细 writeOffAmount/handoverAmount、库存数量/锁定/成本金额三视角；查库失败 fail-closed、超管短路。
- FinanceController 50 端点 + 14 个 GL 控制器、ReportController（chart/stock/finance/dashboard 系列 report.chart.view 等）全部挂注；GL 功能点按后端实际动作登记（voucher: add/save/audit/unaudit/post/red/close(void)/print/export，模板类 add/save 端点即「add」、toggle 才是 edit；archive_mapping/biz_subject_map 仅 view/edit；event emit/generate/close/edit；period_close audit/close/unaudit；init_balance edit/import；depreciation audit；asset_card add/edit/audit）。
- **顺带补齐历史功能缺口：付款单侧核销端点从未实现**——付款单列表已审核行的「核销」按钮与收款单共用 openReconcileDialog，前端硬编码 POST /finance/receipt/unsettled-bills 与 /finance/receipt/reconcile，而后端只有收款单侧实现（付款单 ID 查 fin_receipt_bill 必返回「收款单不存在」，git -S 确认 payment/unsettled-bills、payment/reconcile 从未存在过）。本卡补对称实现：`POST /finance/payment/unsettled-bills`（fin.payment_verify.view）与 `POST /finance/payment/reconcile`（fin.payment_verify.writeoff）——勾单核销 AR/AP、回写 fin_payment_bill.verified_amount、fin_reconcile_record 以新 bizType SALES_PAYMENT/PURCHASE_PAYMENT 落痕（核销记录页本就原样展示 code 不做文案映射）、往来单位数据范围 assertHeadCpVisible/assertCpVisible 与收款侧逐条对称；前端 openReconcileDialog/confirmReconcile/核销内快速建费刷新三处按 moduleCode 分支，弹窗标题回退 paymentNo。
- 状态机自定义动作 settle/writeoff/post/red 均按 8 标准动作之外的合法后缀登记，不强行归并；PermissionRegistry.scanMappings 只扫 POST/PUT/DELETE/PATCH（GET 不计未鉴权写），白名单前缀维持 /auth/、/testing/、/flow/、/system/menu-manage/、/system/notification/、/system/todo/、/actuator/、/tms/app/、/operation-log/、/error。

**前端（零新依赖）**

- useRbac 两处关键修复/扩展：① **resolveCodes 致命缺陷修复**——跨模块码（如收单行核销 fin.receipt_verify.writeoff）被按「当前菜单动作集」误判幽灵码丢弃，改为按 lastIndexOf('.') 拆码后以**码自身菜单**的 MENU_ACTION_SET 校验；global.* 恒合法；卡片7 未覆盖菜单（finance.gl.* 显式挂码）不做白名单校验直接放行；② **fieldForColumn 支持字段码数组并集语义**——混合语义列任一视角可见即保留列，行内值仍由后端按行脱敏；MODULE_MENU 71 模块、ACTION_OVERRIDES 增补收/付款单行「核销」→ receipt_verify/payment_verify.writeoff（注意 /查看核销记录/ 类顺序陷阱：长正则必须排在 /核销/ 前）、fin.ap 等三规则顺序；financeExpense 列规则放宽识别抽屉短标题（^金额$/^单价$/不含税，后端 FUND key 口径 VIEW_FUND_FLOW）；MENU_ACTION_SET 补 fin.payment_verify: view+writeoff。
- 独立页面（不经 GenericBusinessList）三段式收尾：入口 v-permission/v-action-perms 隐藏 + 处理函数 guard/guardCode 闸门 + 后端 403 兜底；敏感列在 allColumns/visibleColumns computed 层过滤使表格/列设置/拖拽/合计/本地 XLSX 全链路跟随，action 列恒保留。覆盖：StockQuery（TAB 双表列过滤 + 锁批/解锁 biz_batch_lock/unlock 双闸门）、客户价格 4 页（CustomerPriceAdjust/Query/ChangeQuery/Edit）、ReceiptDrawer（fin.receipt|fin.payment 的 add/edit 保存控权）、ExpenseDrawer（**纠错**：原误绑 purchaseExpense 采购费用模块——该抽屉仅服务财务费用单，改 financeExpense，保存 fin.fee.add/edit，明细 金额/税额/不含税/单价 VIEW_FUND_FLOW）。
- GL 14 个页面约 45 处按钮全部反查后端注解后逐一显式挂码（不凭文案想当然）：凭证 add/save/audit/unaudit/post/red/close/print/export、模板（/save=add、toggle=edit、删除=delete）、科目 add/edit、辅助/项目/档案映射/业务映射 edit、资产卡片 add/edit/audit（「清理」入口 [audit,view] 数组合规：有权预览可见、最终执行严格 audit）、折旧 audit、事件 generate/edit/close、期初 edit/import、期末 audit/close/unaudit、报表公式 edit；LedgerQuery/Reconcile 纯查询页坚持不挂，靠菜单可见性 + book.view/report.view 兜底；VoucherList CSV 导出与 ArchiveMapping/BizSubjectMap 客户端导出统一 finance.gl.voucher.export；「导出日志」按钮后端是 voucher.view，不另挂。
- 聚合屏零改动过验收：Dashboard 仅调白名单 /system/todo/*、/system/notification/* 且 catch 吞错降级；ReportChart 6 个 GET /report/chart/* 后端全部 report.chart.view 注解、前端逐卡 catch 留空——满足「有菜单可见聚合、金额按字段脱敏、聚合无行级 scope」口径。
- 临时 sed 副本+perm stub 的纯函数 node 测试覆盖：新增收/付款核销映射、取消审核、费用单单价/金额/税额/不含税、库存成本/金额、客户价现价、调拨行内幽灵「核销」恒 null（前端不显示但后端 403 兜底的既有死按钮不在本卡扩范围）；GL 页不经 resolver 走显式挂码（测试中 codesForAction('voucherList') 断言为 null 才是正确行为）。

**验收证据（自动化等价覆盖 40 用例口径：功能点矩阵+契约+构建）**

- 越权扫描：零授权角色 RBAC7SCAN（四分区全空）+ 用户 rbac7scan 登录拿真实 token，从源码反射解析 **509 个 @RequirePerm 端点**（兼容注解正反两序、类级 RequestMapping 精确定位、跨方法/跨类边界排除）逐个真实请求——结果 8 个包 base 103/103、finance（含 GL）144/144、inventory 58/58、purchase 46/46、report 12/12、sales 70/70、system 54/54、transfer 20/20 **全部 403，越权候选 0**；两个设计内自助接口（/system/perm/mine、/system/menu/user-tree）本就无注解放行（只返回本人数据）。
- 新端点功能点契约 7/7：零权两 403；仅授 view 时 unsettled-bills 过拦截（零数据范围 fail-closed 400/404）、reconcile 仍 403；授 view+writeoff 后 reconcile 过拦截（空 body 返回业务错「付款单不存在」）；payment_verify 不触及 receipt 侧两端点（交叉 403）。
- 字段脱敏实证：无任何字段授权的账号查 /base/master/fund-account/page，balance 键保留值置 null；admin 同参看到数值。数据范围负向实证：inv.balance.view/fin.ar_detail.view 零维度 → 列表 total=0；授 WAREHOUSE:ALL / CUSTOMER:ALL 后查询与 admin 同口径（本开发库业务行本就 0，正向仅做同口径对比，机制与卡片4~6 同一套 DataScopeService）。
- 双构建：后端 mvn -o compile RC=0；前端 npm run build ✓ built（GenericBusinessList/useRbac chunk 正常产出）；合并前停服再跑 mvn package（见提交说明）。
- 数据安全：操作前备份 backend/data/backups/erp-v1.pre-rbac7-verify-20260910-041949.mv.db；夹具 RBAC7SCAN/rbac7scan 验收后停服 H2 Shell 精确删除（sys_user_runtime/sys_role_runtime 及 user_role/data_scope/warehouse/pwd_history/role 四 rel/登录日志/操作日志按 ID 与编码精确命中），H2 直查 after-user=0、after-role=0、orphan-rel=0；本次全部空参请求，无任何业务单据写入；未打印密码哈希，夹具密码走 API 明文 'Passw0rd!'；tmp-rbac7/ 验收后整目录删除。

### 2026-09-10 卡片8 落地：前端动态三级菜单/路由裁剪 + 403/401 分流 + ADMIN 硬编码清零（feat/rbac-10-frontend，零后端改动）

> 本卡**无 Flyway、无后端源码改动**（启动权限同步日志「菜单 192 / 功能点 1620 新增0」）；仅前端 13 改 + 3 新增 + 4 删除。前端权限收口卡：菜单从静态 fallback 改为用户授权树驱动，侧边栏支持三级。

**新增 3 文件**

- `router/menu-map.js`：后端菜单码（base.goods / finance.gl.voucher 域.模块命名）↔ 前端历史扁平路径（/goods、/gl-voucher）唯一双向映射表 **147 条**（MENU_PATH + 反向 PATH_MENU）。新增页面三处同改约束写在文件头：router 注册 + MenuConfig.java 声明 + 本表一行。
- `stores/menu-tree.js`：树纯函数（无 Vue 依赖可 node 单测）——collectCodes 全层收码、firstPageCode 深度优先首叶（点一级目录跳转）、rootOfCode 反查根、findNode 名称/编码模糊搜、buildFallbackTree 把 fallback-menus.js 转成与服务端同构节点（adminOnly 标记保留交侧边栏按超管过滤）。
- `stores/menu.js`（Pinia）：GET /system/menu/user-tree（**不带 roleCode**，后端只返回本人树）；source 三态 server/fallback/empty——server 时守卫按授权码 fail-closed 裁页面；接口失败（非 401）降级本地菜单仅保侧边栏可用、**不做前端拦截**（后端 403 兜底，降级态不越权给访问权只放宽 UI 提示）；ensure 并发复用 + force 重拉，reset 联动登出/换账号。

**改造**

- `router/index.js` 守卫：登录态并行 ensure perm+menu；PATH_MENU 反查当前页菜单码，server 来源且不在授权集 → 回首页；`/dashboard` 是登录落点与零菜单用户兜底页永不拦；未入映射表的子页面（customer-price/new、edit/:id、/counterparty-type、/wms-order-pool 同组件别名、/gl-voucher-print 独立打印页）不拦；system-menu 维持 meta.superAdmin 硬守卫（与菜单裁剪双保险）。
- `AppShell.vue`：侧边栏删除 fallbackMenus 直引，按授权树渲染——一级根 / 二级页面或子目录（财务管理 > 总账 展开箭头）/ 三级叶子；进入三级页自动展开父目录；快捷搜索走树；顶栏「导出中心」按 system.export_center 菜单授权显隐；降级态显示「菜单服务不可用，已使用本地菜单」提示；activeRoot 按路由反查、子页面保持手动展开项。
- `api/client.js` 403/401 分流（notifyAuthOutcome 收口 4 个 fetch 出口）：401 清 token + erp-auth-expired（App.vue 跳登录，已在登录页不重复跳）；403 **保留会话**不清 token，广播 erp-perm-denied 但不全局 toast（ApiError 已带「无操作权限：xxx」抛给触发操作的页面 catch，避免双弹；后台静默请求的 403 不打扰用户）；下载链路 403 改抛 ApiError 而非泛化「下载失败」。
- `main.js` 注册全局模板属性 $hasFunc/$hasAnyFunc/$canViewField；`usePerm.js` 新增 useFieldPerm()（canViewField + canViewAnyField 数组并集，空数组不校验）；v-permission/v-action-perms 维持全局指令。
- `stores/auth.js`：clearToken/login 同步 reset 菜单 store，杜绝换账号沿用旧树。

**删除死代码 4 文件 + 1 处历史字样**

- composables/useNavigation.js（loadUserMenus 默认参 roleCode='ADMIN'、拼 roleCode query 的旧调用，全仓零引用）、composables/usePermission.js（/system/field-scope 旧字段范围，被 perm store 取代，零引用）、layout/AppSidebar.vue / AppHeader.vue / AppTabBar.vue（AppShell 内联实现后零引用的旧三件）。
- GenericBusinessList.vue:39 历史注释含 roleCode='ADMIN' 字样会永久触发 grep 门槛，改写注释。门槛 `grep -rnE "roleCode\s*=\s*['\"]ADMIN" frontend/src/` 清零；auth.js 保留 includes('ADMIN') 是 V102 前老令牌角色码的**向后兼容判定**（有注释，非硬编码假设），不属于本门槛。

**验收（2 个 node 测试文件 + 17 条真实后端 e2e 断言，全绿）**

- menu-map 双向完整性（node 解析 router/index.js 全部 path + MenuConfig.java 全部 .page/.statePage/.adminPage 声明）：147 条映射路径全部在 router 注册且无重复；router 未映射路径仅 5 个白名单（/login、/gl-voucher-print、customer-price/new、edit/:id、/wms-order-pool、/counterparty-type——后两者经源码核实为同组件别名与往来单位页的子页面）；MenuConfig 的 ERP 页面码 100% 有映射（PDA/DRIVER 端不适用）。
- 树纯函数 17 断言：三级 collectCodes、firstPageCode 各级取值、rootOfCode/findNode 二三级命中、kebab 转换、降级树根/叶结构与 adminOnly 保留。
- 真实后端 e2e（夹具 RBAC8LOW 零菜单角色 + rbac8low 用户）：admin user-tree 三级实证（finance > finance.gl(DIR) > 16 个 PAGE 含 voucher）且 147 映射码全覆盖、admin_only 的 system.menu 超管可见；零菜单用户树为空、按守卫同构规则除 /dashboard 外 146 路径全拦截、/system/perm/mine 自助可取、业务接口 HTTP 403（无操作权限：sales.order.view）；授「sales 根+sales.order」后树仅 2 码、/sales-order 放行而 /ar、/gl-voucher、/wms-pick、/user 全拦、非超管树无 system.menu；**菜单可见但零功能点时数据接口仍 403**（菜单裁剪≠功能授权的关键边界）。
- 门槛：roleCode ADMIN grep 清零；新增文件无 console.log；`npm run build` ✓ built；后端本卡零改动，fat jar 复跑启动权限同步新增0、fail-fast 通过。
- 数据安全：操作前备份 backend/data/backups/erp-v1.pre-rbac8-verify-20260910-051043.mv.db；夹具验收后停服 H2 Shell 精确清理（user/role + user_role/user_data_scope/user_warehouse/pwd_history/role 四 rel/login_log），H2 直查 after-user=0、after-role=0、双 orphan-rel=0；未打印密码哈希，夹具密码走 API 明文 'Passw0rd!'；tmp-rbac8/ 验收后整目录删除。

### 2026-09-10 卡片9 落地：PDA 工号选仓登录 + 六角色矩阵 + 仓库硬隔离（feat/rbac-8-pda-login，V107）

> M3 移动权限卡。后端 PDA 端 58 个端点全部纳管（57 个 @RequirePerm + /login 豁免），Flutter 端登录/菜单/按钮全链路按授权裁剪；启动同步日志「菜单 192 / 功能点 1666 / PDA 六角色授权 199 行」。

**后端**

- `V107__rbac_pda_role_grants.sql`：唯一内容是给内置 R_WMS_LEADER 幂等补 `VIEW_COST`/`VIEW_COST_AMOUNT` 两个字段授权（MERGE 式存在即跳过）；六角色菜单/功能点授权不再用 SQL 维护，统一由 `PermissionRegistry.PDA_ROLE_FUNCS` 矩阵在启动时**幂等对账**（先删矩阵外 wms_pda 授权再补齐缺失，代码即唯一授权来源；非 wms_pda 授权与自定义角色不动）。
- `WmsPdaAuthService` + `POST /wms/app/login`：工号+密码两步选仓——0 绑定仓 400 拒绝、单绑定仓直发 token、多仓返回 `needWarehouse+warehouses` 不发 token，第二步带非绑定 warehouseId 返回 400「未绑定所选仓库」；SYS_ADMIN 不绑仓也可登录（端内选仓，功能校验短路）。JWT claims：`sub`=工号、userId、displayName、roleCodes、appType=WMS_PDA、warehouseId、employeeId。登录载荷含 token/user/warehouses/menus 树/funcs/fields/superAdmin/参数快照（盲收/容差/复检等 PARAM_KEYS）。
- `PdaAppGuardInterceptor`：`/wms/app/**`（除 /login）强制 WMS_PDA appType，ERP/DRIVER 旧令牌与无令牌一律 HTTP 401 JSON（`请使用 PDA 重新登录（选择作业仓库）`）。
- `WmsAppController`：58 端点全部 CurrentUser 化——`operator()` 只认 JWT，忽略请求体 operator；create_by/receiver/assignee/绩效操作人落自然人。同端点多动作用 `@RequirePerm(alsoRegister=...)` 注册载荷码 + 方法体 `checkPerm` 分支裁决（check/pass 的 scan/pack、load/ship 的 scan、入库三组 view/scan/confirm、绩效 view_team/export、盘点 bins 的 scan、库存批次/成本、首页 profile 三开关、receive 的 start/print_label/over_receive、putaway 的 scan/free_bin/split、拣货 all 范围的 assign.view）。
- `WmsWarehouseResolver`：token warehouseId→仓库名每请求解析（空名兜底总仓），全部 PDA 查询强制当前仓；`assertIfPda/assertCurrent` 对跨仓详情/操作抛 IllegalArgumentException「该单据属于仓库「X」，非当前作业仓库「Y」，禁止操作」。
- `WmsInboundService.recheck`：PDA 请求且参数 `WMS_RECHECK_SELF_NG=1`（**默认 1=禁止**，方案旧文 "=0" 为笔误已更正）时 receiver=当前人即拒「不能复检本人收货单」；超收在 service 层校验 `wms_pda.receive.over_receive` + 必填原因。
- 库存查询：批次列受 view_batch、成本单价/金额受 view_cost 功能 + VIEW_COST/VIEW_COST_AMOUNT 字段**双控**（service 内列裁剪/金额清零）。

**Flutter（wms_pda_app，无新依赖）**

- 登录页两步式（工号密码 → 多仓选仓列表），首登改密强制、dio 401 统一清会话回登录页；`AuthService` 持有 menus 树/funcs/fields/参数快照，提供 hasMenu/can/字段判定。
- 首页卡片完全由 menus 派生：**仅 view/view_self 类功能点派生页面菜单**（纯动作授权如 PUTAWAY 的 move.confirm、PICKER 的 replenish.urgent 不再产生打不开的卡片）；异常/绩效是全员菜单但首页卡片仅管理者可见（handle|assign / view_team），普通作业员从「我的」页"异常中心/我的绩效"进入。收货员首页严格 4 卡（PDA-002）。
- `pda_perms.dart` 65 个功能码常量与后端逐字一致；全 App 53 处 `can(PdaPerm.xxx)` 按钮闸门 + 页面分组入库 receiveGroupView/Scan/Confirm 按入库类型切换码；`flutter analyze` 零 issue。
- 预留功能点（home.scan 全局扫码、putaway.scan 扫托、putaway.split 拆托、replenish.urgent 加急按钮）后端已注册并强制、本期前端无入口，后续补按钮直接按 funcs 裁剪。

**验收（backend/tmp-card9-accept/accept.js，46 断言全绿，脚本用后即删）**

- PDA-001（8）：单仓直发；JWT appType/warehouseId/sub 三 claims；ERP token 与无令牌访问 /wms/app/* 均 401；错密 400「账号或密码错误」。
- PDA-002（16）：多仓第一步 needWarehouse 且无 token；收货员菜单恰为 receive/receive_return/other_inbound/stock_query 四页，pick/check/load/putaway/stocktake/move/damage/task_assign 全无；首页卡片 deep-equal 恰好 4 张；PUTAWAY 持 move.confirm 不出现移库卡但有上架/补货卡。
- PDA-003（4）：c9recv 开始→逐行收货→完成（WMS_RECHECK_ENABLED=1/MODE=0 进 RECHECK）；PC 端核对任务 receiver=工号 c9recv，非管理员/系统管理员。
- PDA-004（4）：复制 PICKER 建自定义角色 WMS_PICK_C9 去掉 pick.short_pick——菜单仍在、funcs 已无该码（按钮隐藏依据）；直调 /pick/short-pick 403；正常 PICKER 同接口权限层放行。
- PDA-005（2）：本人收货本人复检 400「不能复检本人收货单」；他人（KEEPER）复检通过进 PUTAWAY。
- PDA-006（5）：本人绩效 200、全仓 403、导出 403；LEADER 全仓/导出均 200。
- PDA-007（7）：A 仓任务列表只见 A 不见 B；A token 操作 B 单详情 400 跨仓拒绝；选未绑定仓 400；重登 B 后 JWT warehouseId=B 且只见 B 任务。
- 权限对拍：docs/perm-inventory.md 启动自动重生成（793 端点 / 566 已挂），wms 段 58 个 app 端点 57 挂注解；前端 65 常量 == Controller 65 功能码（双向差集为空），矩阵 66 码仅多 home.view（首页菜单派生用）。
- 过程坑：卡片6/7 验收残留的 R6_SUP 孤儿收货任务（采购单已删、任务未删，仓库=总仓）占用了 RK/CGDD 当日 MAX+1 序号，导致本卡脚本按单号捞到旧任务误判 PDA-003；停服 H2 Shell 查明后精确删除孤儿任务与 S_C9 单据（先备份 backups/erp-v1.pre-cleanup-c9.*），重跑全绿。
- 数据安全：操作前备份 H2；未打印密码哈希，夹具密码走 API 明文 'Passw0rd!'；验收后停服精确清理 C9 夹具（用户/自定义角色/仓库/供应商/商品/单据/参数复位），tmp-card9-accept/ 与 tmp-card9-boot.log 用后即删。

