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
