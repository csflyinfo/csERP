# Sprint 1 进度记录

## 2026-06-14

### 已完成

1. 创建实际工程目录：
   - `backend/`
   - `frontend/`

2. 后端 Spring Boot 骨架：
   - `pom.xml`
   - `ErpApplication`
   - 统一响应 `ApiResponse`
   - 分页结构 `PageRequest` / `PageResult`
   - 全局异常处理
   - Auth Demo 接口
   - System Demo 接口
   - Base Demo 接口

3. 前端 Vue/Vite 骨架：
   - `package.json`
   - `vite.config.js`
   - `src/main.js`
   - `src/App.vue`
   - `src/api/client.js`
   - `src/styles/app.css`

4. 无依赖预览与联调兜底：
   - `frontend/preview.html`
   - `backend/mock-server.js`

5. 工程配置：
   - `.gitignore`
   - 前端 Vite 代理到 `http://localhost:8080`

### 验证结果

- Node.js 可用：已通过脚本语法校验。
- `backend/mock-server.js` 语法校验通过。
- `frontend/preview.html` 内联 JS 语法校验通过。
- Mock API 已启动并完成冒烟验证：
  - `GET /api/auth/current-user`
  - `GET /api/system/menu/user-tree`

### 环境情况

- 当前机器未安装或未配置 `java`。
- 当前机器未安装或未配置 `mvn`。
- `npm install` 因执行策略限制未运行。
- 已用 Node.js Mock API + 静态预览页保持开发推进，不阻塞 Sprint 1。

### 下一步

1. 安装/配置 JDK 17 与 Maven 后执行后端编译。
2. 允许 npm 依赖安装后执行前端真实构建。
3. 接入真实数据库与 ORM。
4. 将前端 `App.vue` 拆分为正式页面和通用组件。

## 2026-06-14 继续开发

### 已完成

1. 后端接口骨架扩展：
   - `CustomerPriceController`
   - `GoodsController`
   - 客户价格调整单分页、详情、新建、修改、审核、作废、导入
   - 客户价格查询、停用
   - 商品分页、商品选择器

2. Mock API 扩展：
   - 商品档案分页/选择
   - 客户价格调整单完整流程
   - 客户价格查询与停用
   - 商品分类新建

3. 前端组件拆分：
   - `components/ProTable.vue`
   - `components/QueryBar.vue`
   - `views/CustomerPriceAdjust.vue`
   - `views/CustomerPriceEdit.vue`
   - `views/CustomerPriceQuery.vue`
   - `App.vue` 改为引用组件和页面骨架

4. 数据库补充：
   - `003_seed_initial_data.sql`
   - 初始化管理员、权限组、系统参数、编号规则、商品分类、单位、品牌、客户、商品

### 当前说明

JDK/Maven 仍未安装，Spring Boot 真实编译暂不可执行；继续使用 Node Mock API 保障前端联调和接口结构推进。

## 2026-06-14 环境安装与真实构建

### 已安装/确认

1. JDK：Eclipse Temurin JDK 17 已安装成功。
2. Maven：Apache Maven 3.9.16 已下载、解压并配置到用户环境变量。
3. Node.js/npm：本机已存在 Node.js v25.2.1、npm 11.6.2。
4. 前端依赖：`npm install --prefix frontend` 成功，0 个漏洞。

### 构建验证

1. 后端编译：
   - 命令：`mvn -f backend/pom.xml -DskipTests compile`
   - 结果：成功。
   - 修复项：`CustomerPriceController` 中超过 `Map.of` 参数限制的问题，改为 `Map.ofEntries`。

2. 真实后端启动：
   - 命令：`mvn -f backend/pom.xml spring-boot:run`
   - 端口：`http://localhost:8080/api`
   - 健康检查：`/api/actuator/health` 返回 `UP`。
   - 冒烟接口：
     - `GET /api/auth/current-user` 成功。
     - `POST /api/base/customer-price-adjust/page` 成功。

3. 前端构建：
   - 命令：`npm run build --prefix frontend`
   - 结果：成功。
   - 输出目录：`frontend/dist/`。

4. 前端开发服务器：
   - 命令：`npm run dev --prefix frontend`
   - 地址：`http://localhost:5173`
   - 结果：可访问。

### 当前运行进程

- Spring Boot 后端正在后台运行。
- Vite 前端开发服务器正在后台运行。

## 2026-06-14 业务模块继续开发

### 已完成

1. 新增后端业务模块接口骨架：
   - `inventory/InventoryController.java`
   - `purchase/PurchaseController.java`
   - `sales/SalesController.java`
   - `finance/FinanceController.java`

2. 已覆盖接口骨架：
   - 库存余额、库存流水、库存锁定
   - 采购订单、采购入库、采购收货单
   - 销售订单、销售快速开单、销售出库、销售收货单
   - 应收、应付、收付款单、资金流水

3. 新增数据库迁移草案：
   - `004_inventory_purchase_sales_finance.sql`
   - 覆盖库存余额、库存流水、采购订单/明细、销售订单/明细、应收、应付、资金流水

4. 前端业务模块补充：
   - `GenericBusinessList.vue`
   - 菜单新增采购管理、销售管理、库存管理、财务管理
   - 通用业务列表支持 purchase/sales/inventory/finance 四类展示

### 构建验证

1. 后端编译：成功。
2. 前端构建：成功。
3. 新增业务接口冒烟：成功。
   - `POST /api/inventory/balance/page`
   - `POST /api/purchase/order/page`
   - `POST /api/sales/order/page`
   - `POST /api/finance/ar/page`

### 修复项

- `SalesController` 中超过 `Map.of` 参数限制的问题，已改为 `Map.ofEntries`。

## 2026-06-14 V1.0 核心闭环可执行流程与自测

### 已完成

1. 新增核心闭环服务：
   - `common/biz/BizState.java`
   - `flow/BusinessFlowService.java`
   - `flow/BusinessFlowController.java`

2. 新增可执行闭环接口：
   - `POST /api/flow/purchase-cycle/run`
   - `POST /api/flow/sales-cycle/run`
   - `POST /api/flow/ar/receive-and-verify`
   - `POST /api/flow/ap/pay-and-verify`
   - `POST /api/flow/customer-price/audit`
   - `POST /api/flow/v1-core/self-test`
   - `GET /api/flow/dashboard`

3. 闭环覆盖：
   - 采购订单审核 → 采购入库审核 → 库存增加/流水/成本 → 采购收货单审核 → 应付生成
   - 销售订单审核 → 库存锁定 → 销售出库审核 → 扣库存/释放锁定/流水 → 销售收货单审核 → 应收生成
   - 收款核销 → 应收变已核销 → 资金流水
   - 付款核销 → 应付变已核销 → 资金流水
   - 客户价格调整单审核 → 新价格生效 → 历史有效价自动停用

4. 新增自测脚本：
   - `development/06-testing/scripts/v1-core-smoke-test.js`

5. 前端新增入口：
   - 顶部按钮“核心闭环自测”
   - 首页展示采购闭环、销售闭环、应收核销、应付核销、客户价格自测结果

### 验证结果

1. 后端编译：成功。
2. 前端构建：成功。
3. V1.0 核心闭环自测：成功。

输出：

```text
V1 core smoke test passed
```

### 修复项

- 自测脚本对采购应付生成的断言过严，因后续付款核销会把同一应付状态更新为 `VERIFIED`，已调整为先校验应付单号存在，再校验付款后状态为 `VERIFIED`。

## 2026-06-14 V1.0 模块覆盖补齐

### 已完成

1. 基础资料后端接口补齐：
   - 价格组、客户、供应商、往来单位、资金账户、费用类型、片区、线路、人员、部门、货主
   - 通用保存、停用接口

2. 库存模块接口补齐：
   - 批次库存、库存预警、调拨单、报损单、成本调整单

3. 采购模块接口补齐：
   - 采购退货单、采购费用单、采购发票
   - 采购费用审核：费用分摊、成本重算、应付生成

4. 销售模块接口补齐：
   - 销售退货单、销售发票、飞单、客户空退空出

5. 财务模块接口补齐：
   - 应收结算、应付结算、费用单、收款核销、付款核销

6. 前端菜单补齐：
   - 基础资料、采购管理、销售管理、库存管理、财务管理、系统管理主要 V1.0 页面均已进入菜单
   - 通用业务列表覆盖采购、销售、库存、财务模块

7. 自动化自测补充：
   - `v1-module-coverage-test.js`
   - 覆盖 50 个分页/查询接口

### 验证结果

1. 后端编译：成功。
2. 前端构建：成功。
3. V1 模块覆盖自测：成功。

```text
V1 module coverage test passed: 50 endpoints
```

4. V1 核心闭环自测：成功。

```text
V1 core smoke test passed
```

### 修复项

- `BaseMasterController.priceGroupPage` 参数数量不匹配，已补齐状态字段。
- 新增模块后端已重新启动并通过接口覆盖测试。

## 2026-06-14 前端操作接入后端接口

### 已完成

1. 新增前端模块接口映射：
   - `frontend/src/module-api.js`
   - 为基础资料、客户价格、库存、采购、销售、财务、系统管理模块配置分页和操作接口

2. 通用列表接入真实后端分页：
   - `GenericBusinessList.vue` 不再只使用静态示例数据
   - 页面加载和查询时调用对应模块 `page` 接口
   - 接口失败时降级显示 PRD 示例数据

3. 字段映射：
   - 后端 record 自动映射到 PRD 列表字段
   - 支持商品、客户、供应商、单据号、金额、库存、状态等常用字段别名

4. 按钮操作接入接口：
   - 新建/编辑/复制 → save/create 接口
   - 审核 → audit 接口
   - 停用 → stop 接口
   - 作废 → cancel 接口
   - 核销 → reconcile 接口
   - 导入/导出/打印/字段设置保留对应交互反馈

5. 新建/编辑窗口可操作：
   - 按 PRD 字段生成表单
   - 单据类生成明细表
   - 保存后写入前端列表并尝试调用后端接口

### 验证结果

1. 前端构建：成功。
2. 后端模块覆盖自测：成功。

```text
V1 module coverage test passed: 50 endpoints
```

## 2026-06-14 H2/JDBC 持久化开发

### 已完成

1. 后端接入本地 H2 数据库：
   - `spring-boot-starter-jdbc`
   - `h2`
   - H2 文件库：`backend/data/erp-v1`
   - H2 控制台：`/api/h2-console`

2. 新增数据库初始化脚本：
   - `backend/src/main/resources/schema.sql`
   - `backend/src/main/resources/data.sql`

3. 基础资料真实 CRUD 初步接入：
   - 商品分类分页、新建、修改
   - 单位分页、新建
   - 品牌分页、新建
   - 仓库分页、新建
   - 商品分页、选择器、新建

4. 客户价格调整单持久化：
   - 调整单分页
   - 调整单详情
   - 新建调整单
   - 审核调整单
   - 审核后生成客户价格
   - 审核后停用历史生效价格
   - 客户价格查询
   - 客户价格停用

5. 前端通用模块列表已接入真实后端分页接口和操作接口。

### 验证结果

1. 后端编译：成功。
2. 后端启动：成功。
3. H2 持久化接口自测：成功。

```text
H2 persistence smoke OK
```

### 当前说明

当前数据库为本地 H2 开发库，适合开发和自测。后续进入测试/生产部署时，将迁移到 MySQL/PostgreSQL，并复用当前 JDBC/SQL 结构继续演进。

## 2026-06-15 系统增强模块与覆盖测试

### 已完成

1. 系统增强接口补齐：
   - 显示精度设置：`/system/precision/page`、`/system/precision/save`
   - 用户数据字典：`/system/dictionary/page`、`/system/dictionary/save`
   - 审批流配置：`/system/workflow/page`、`/system/workflow/save`
   - 打印模板设置：`/system/print-template/page`、`/system/print-template/save`
   - 导入列表：`/system/import-list/page`
   - 导出中心：`/system/export-center/page`

2. 系统管理前端接口映射已接入真实接口。

3. 系统管理自测通过：

```text
V1 system smoke test passed
```

4. 模块覆盖测试扩展到 56 个接口并通过：

```text
V1 module coverage test passed: 56 endpoints
```

5. 前端构建通过：

```text
npm run build --prefix frontend
✓ built
```

## 2026-06-15 报表中心前端接入

### 已完成

1. 前端菜单新增“报表中心”：
   - 销售报表
   - 采购报表
   - 库存报表
   - 财务报表

2. 前端报表模块配置补齐：
   - `salesReport`
   - `purchaseReport`
   - `stockReport`
   - `financeReport`

3. 前端接口映射接入后端报表接口：
   - `/report/sales/page`
   - `/report/purchase/page`
   - `/report/stock/page`
   - `/report/finance/page`
   - `/report/export`

4. 通用列表导出操作增强：
   - 有导出接口的模块调用后端导出任务接口。
   - 无导出接口的模块保留前端导出任务提示。

5. 模块覆盖测试扩展到 60 个接口。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 使用 18080 验证端口启动后端并通过模块覆盖测试：

```text
V1 module coverage test passed: 60 endpoints
```

4. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

### 当前说明

- 本机 8080 端口已有后端进程在运行，未强制停止该进程。
- 为避免影响现有进程，本次使用 `--server.port=18080` 启动临时验证后端，验证完成后已停止该后台任务。
- 当前环境未识别 `git` 命令，未能输出工作区 diff 状态。

## 2026-06-15 经营概览真实接口接入

### 已完成

1. 首页经营概览卡片接入后端汇总接口：
   - `GET /report/dashboard/summary`

2. 首页指标从静态演示值改为接口数据：
   - 销售金额
   - 采购金额
   - 库存金额
   - 可用库存
   - 应收余额
   - 应付余额

3. 首页新增经营指标刷新入口。

4. 接口失败时保留演示指标兜底，并提示加载失败。

5. 修复增强业务流程重复自测时费用单已审核后的断言兼容问题：
   - 无待审核费用单时返回文案包含“往来”，保持测试可重复执行。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 8080 端口后端健康检查通过：

```text
UP
```

4. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

5. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

6. 经营概览汇总接口返回成功：

```text
GET http://localhost:8080/api/report/dashboard/summary
code: 0
```

### 当前说明

- 8080 端口后端已重启并加载本次改动。
- Git 已加入用户 PATH，项目已建立 Git 基线，可正常查看 status/diff。

## 2026-06-15 登录态与动态权限菜单接入

### 已完成

1. 前端新增登录页：
   - 演示账号：`admin`
   - 演示密码：`admin123`
   - 登录成功后保存 demo token 到 `localStorage`

2. 前端接入登录与退出：
   - `POST /auth/login`
   - `POST /auth/logout`
   - `GET /auth/current-user`

3. 前端菜单从静态硬编码切换为优先读取后端权限菜单：
   - `GET /system/menu/user-tree`
   - 接口失败时自动降级为本地菜单

4. 后端用户菜单补齐到当前 V1.0 前端菜单覆盖范围：
   - 基础资料完整菜单
   - 采购、销售、库存、财务增强菜单
   - 报表中心
   - 系统管理

5. 顶部用户区显示当前用户名称，并支持退出登录。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 8080 端口后端健康检查通过：

```text
UP
```

4. 登录接口验证通过：

```text
POST http://localhost:8080/api/auth/login
code: 0
```

5. 用户菜单接口验证通过：

```text
GET http://localhost:8080/api/system/menu/user-tree
code: 0
```

6. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

7. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

### 当前说明

- 8080 端口后端已重启并加载动态菜单补齐。
- 前端现在进入系统前需要先登录，刷新页面后可通过本地 token 保持登录态。

## 2026-06-15 前端 API 认证头与菜单自测补充

### 已完成

1. 前端 API 客户端统一处理认证头：
   - 从 `localStorage.erp-demo-token` 读取 token。
   - 自动附加 `Authorization: Bearer <token>`。

2. 前端 API 客户端统一处理异常响应：
   - 非 JSON 响应返回“服务响应异常”。
   - 401 响应清理本地 token 并触发登录过期事件。

3. 登录失败时清理本地 token，避免残留登录态。

4. 新增认证与菜单冒烟脚本：
   - `development/06-testing/scripts/v1-auth-menu-smoke-test.js`
   - 覆盖登录、当前用户、动态菜单、报表中心菜单、基础资料菜单关键项。

### 验证结果

1. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

2. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

3. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

4. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 后端 Demo Token 鉴权接入

### 已完成

1. 新增后端 Demo Token 鉴权过滤器：
   - `backend/src/main/java/com/erp/auth/DemoAuthFilter.java`
   - 除登录、登出、健康检查、H2 控制台外，其余接口需携带 `Authorization: Bearer demo-token`

2. 未携带或 token 错误时返回：

```json
{"code":"401","message":"登录已过期，请重新登录"}
```

3. 自动化测试脚本补充认证头：
   - `v1-module-coverage-test.js`
   - `v1-enhanced-flow-test.js`
   - `v1-core-smoke-test.js`
   - `v1-system-smoke-test.js`
   - `v1-auth-menu-smoke-test.js`

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 未携带 token 访问受保护接口返回 401：

```text
GET /api/auth/current-user
401
```

4. 携带 token 访问受保护接口成功：

```text
GET /api/auth/current-user
Authorization: Bearer demo-token
code: 0
```

5. 登录接口仍可公开访问：

```text
POST /api/auth/login
code: 0
```

6. 自动化测试全部通过：

```text
V1 auth menu smoke test passed
V1 module coverage test passed: 60 endpoints
V1 enhanced flow test passed
V1 core smoke test passed
V1 system smoke test passed
```

## 2026-06-15 通用表格字段设置落地

### 已完成

1. 通用业务列表支持字段显示/隐藏：
   - 字段设置弹窗列出当前模块全部列表字段。
   - 操作列固定显示，不允许隐藏。

2. 字段设置按模块本地持久化：
   - 存储键：`erp-field-setting:<moduleCode>`
   - 切换模块后自动加载对应模块字段设置。

3. 字段设置支持恢复默认。

4. 通用表格按可见字段渲染表头、单元格和插槽。

### 验证结果

1. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

2. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

3. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

4. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 通用分页能力接入

### 已完成

1. 后端 `PageResult.of` 从只返回全部记录改为按 `pageNo/pageSize` 切片：
   - `records` 返回当前页数据。
   - `total` 保留切片前总记录数。
   - `pageNo/pageSize` 使用安全值。

2. 前端 `ProTable` 新增分页条：
   - 总数显示
   - 每页 10/20/50 条切换
   - 上一页/下一页
   - 当前页/总页数

3. 通用业务列表接入分页状态：
   - 请求后端时携带当前 `pageNo/pageSize`。
   - 查询、重置、树节点切换时回到第一页。
   - 接口失败降级示例数据时同步总数。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 分页接口验证通过：

```text
POST /api/system/operation-log/page
pageNo=1&pageSize=1
records.length=1
total=9
```

4. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

5. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

6. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 查询条件传递接入

### 已完成

1. `QueryBar` 支持收集查询条件：
   - 输入框、下拉框统一写入查询值。
   - 回车触发查询。
   - 重置清空查询值。

2. 通用业务列表接入查询状态：
   - 查询时将 filters 传给分页接口。
   - 查询、重置后回到第一页。
   - 树节点切换时把节点写入 filters。

3. 报表导出任务携带当前查询条件。

### 验证结果

1. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

2. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

3. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

4. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 后端通用分页过滤接入

### 已完成

1. `PageResult.of` 支持通用 filters 过滤：
   - 对 Map 记录的所有字段值做文本匹配。
   - 多个查询值按 AND 规则匹配。
   - 空值不参与过滤。
   - `treeNode=全部*` 不参与过滤。

2. 过滤在分页切片前执行：
   - `total` 返回过滤后的总数。
   - `records` 返回过滤后的当前页。

3. 新增分页过滤冒烟脚本：
   - `development/06-testing/scripts/v1-page-filter-test.js`
   - 覆盖商品关键字命中、无结果、过滤后分页大小。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 分页过滤测试通过：

```text
V1 page filter test passed
```

4. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

5. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

6. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 通用排序能力接入

### 已完成

1. 后端 `PageResult.of` 支持通用排序：
   - 支持 `sortField` / `sortOrder`。
   - 排序在 filters 过滤后、分页切片前执行。
   - 支持字符串字段和数值字段排序。
   - `sortOrder=asc/desc/descending` 均可识别。

2. 前端表格表头支持点击排序：
   - 第一次点击升序。
   - 第二次点击降序。
   - 第三次点击取消排序。
   - 当前排序列展示 ↑ / ↓。

3. 通用业务列表请求分页接口时携带排序参数。

4. 新增分页排序冒烟脚本：
   - `development/06-testing/scripts/v1-page-sort-test.js`
   - 覆盖字符串排序、数值排序、排序后分页大小。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 分页排序测试通过：

```text
V1 page sort test passed
```

4. 分页过滤测试通过：

```text
V1 page filter test passed
```

5. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

6. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

7. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 导出中心任务落库

### 已完成

1. 新增导出任务运行表：
   - `sys_export_task_runtime`
   - 记录任务号、报表名称、模块编码、筛选条件、文件名、状态、创建/完成时间。

2. 报表导出接口从仅返回提示改为创建导出任务：
   - `POST /report/export`
   - Demo 模式下任务立即完成并生成模拟文件名。

3. 导出中心改为查询真实导出任务表：
   - `POST /system/export-center/page`
   - 支持分页、过滤、排序的通用能力。

4. 初始化数据新增一条导出任务样例。

5. 新增导出中心冒烟脚本：
   - `development/06-testing/scripts/v1-export-center-test.js`
   - 覆盖创建导出任务、按任务号查询导出中心。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 导出中心测试通过：

```text
V1 export center test passed
```

4. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

5. 分页排序测试通过：

```text
V1 page sort test passed
```

6. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

7. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 导出中心下载接口与前端下载操作

### 已完成

1. 导出中心新增下载接口：
   - `POST /system/export-center/download`
   - 按任务号查询导出任务。
   - 校验任务存在且状态为已完成。
   - 返回模拟下载地址、文件名和提示信息。

2. 导出中心前端配置细化：
   - 列表字段改为任务号、报表名称、模块编码、状态、文件名、筛选条件、创建时间、完成时间、操作。
   - 导出中心接口映射新增 `download`。

3. 通用列表新增“下载”操作处理：
   - 有下载接口时调用后端下载接口。
   - 下载成功后提示文件名。

4. 导出中心测试扩展：
   - 创建导出任务。
   - 查询导出中心。
   - 调用下载接口并校验下载地址与文件名。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 导出中心下载测试通过：

```text
V1 export center test passed
```

4. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

5. 分页排序测试通过：

```text
V1 page sort test passed
```

6. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

7. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 导出中心顶部入口与刷新动作

### 已完成

1. 顶部“导出中心”按钮从提示文案改为真实跳转：
   - 点击后打开 `exportCenter` 模块页签。

2. 通用列表新增“刷新”动作处理：
   - 点击刷新回到第一页并重新加载当前列表。
   - 导出中心配置中的“刷新”按钮 now 可用。

3. 导出中心触发导出后自动刷新列表，便于立即看到新增任务。

### 验证结果

1. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

2. 导出中心测试通过：

```text
V1 export center test passed
```

3. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

4. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

5. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 导入列表任务落库

### 已完成

1. 新增导入任务运行表：
   - `sys_import_task_runtime`
   - 记录任务号、模块编码、任务名称、文件名、成功/失败行数、状态、结果说明、创建/完成时间。

2. 初始化数据新增一条导入任务样例。

3. 导入列表改为查询真实导入任务表：
   - `POST /system/import-list/page`
   - 支持分页、过滤、排序的通用能力。

4. 新增导入任务创建接口：
   - `POST /system/import-list/create`
   - Demo 模式下任务立即完成并返回成功/失败行数。

5. 前端导入列表配置细化：
   - 列表字段改为任务号、任务名称、模块编码、状态、文件名、成功行数、失败行数、结果说明、创建时间、完成时间、操作。
   - 导入列表接口映射新增 `import`。

6. 通用导入弹窗接入后端导入任务创建接口。

7. 新增导入列表冒烟脚本：
   - `development/06-testing/scripts/v1-import-list-test.js`
   - 覆盖创建导入任务、按任务号查询导入列表。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 导入列表测试通过：

```text
V1 import list test passed
```

4. 导出中心测试通过：

```text
V1 export center test passed
```

5. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

6. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

7. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 导入失败原因下载接口

### 已完成

1. 导入列表新增失败原因下载接口：
   - `POST /system/import-list/download-failures`
   - 按任务号查询导入任务。
   - 返回模拟失败原因文件名、下载地址、失败行数和提示信息。

2. 导入列表前端接口映射新增 `download`。

3. 通用列表“下载失败原因”操作复用下载逻辑：
   - 点击后调用导入列表下载接口。
   - 下载成功后提示失败原因文件名。

4. 导入列表测试扩展：
   - 创建导入任务。
   - 查询导入列表。
   - 调用失败原因下载接口并校验下载地址与文件名。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 导入失败原因下载测试通过：

```text
V1 import list test passed
```

4. 导出中心测试通过：

```text
V1 export center test passed
```

5. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

6. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

7. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 导入导出操作日志补充

### 已完成

1. 导入任务创建写入操作日志：
   - 模块：`system.import`
   - 动作：`CREATE`
   - 业务号：导入任务号

2. 导入失败原因下载写入操作日志：
   - 模块：`system.import`
   - 动作：`DOWNLOAD_FAILURES`
   - 业务号：导入任务号

3. 报表导出任务创建写入操作日志：
   - 模块：`report.export`
   - 动作：`EXPORT`
   - 业务号：导出任务号

4. 导出文件下载写入操作日志：
   - 模块：`system.export`
   - 动作：`DOWNLOAD`
   - 业务号：导出任务号

5. 导入/导出测试补充操作日志断言。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 导入列表操作日志测试通过：

```text
V1 import list test passed
```

4. 导出中心操作日志测试通过：

```text
V1 export center test passed
```

5. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

6. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

7. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 首页核心闭环自测入口修复

### 已完成

1. 首页“核心闭环自测”按钮显式展示在经营概览操作区。

2. 前端核心闭环自测从原生 `fetch` 改为统一 API 客户端：
   - 自动携带 `Authorization: Bearer demo-token`。
   - 复用统一错误处理。
   - 失败时显示错误提示，不再静默抛错。

### 验证结果

1. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

2. 核心闭环冒烟测试通过：

```text
V1 core smoke test passed
```

3. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

4. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

5. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 首页最近操作动态接入

### 已完成

1. 首页新增“最近操作动态”卡片区：
   - 读取 `POST /system/operation-log/page`。
   - 默认展示最近 6 条操作日志。
   - 支持刷新动态。

2. 登录初始化时同步加载最近操作日志。

3. 顶部“消息”按钮从提示文案改为真实跳转：
   - 点击后打开系统管理下的“操作日志”模块。

### 验证结果

1. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

2. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

3. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

4. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 经营概览指标扩展

### 已完成

1. 后端经营概览接口扩展：
   - `GET /report/dashboard/summary`
   - 新增销售单数、采购单数、未核销应收数、未核销应付数、导入完成数、导出完成数、操作日志数。

2. 前端首页指标卡扩展展示：
   - 销售单数
   - 采购单数
   - 未核销应收
   - 未核销应付
   - 导入完成
   - 导出完成

3. 新增经营概览汇总测试脚本：
   - `development/06-testing/scripts/v1-dashboard-summary-test.js`
   - 校验经营概览关键指标完整返回。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 经营概览汇总测试通过：

```text
V1 dashboard summary test passed
```

4. 核心闭环冒烟测试通过：

```text
V1 core smoke test passed
```

5. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

6. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

7. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 登录登出操作日志补充

### 已完成

1. 登录成功写入操作日志：
   - 模块：`auth`
   - 动作：`LOGIN`
   - 结果：`SUCCESS`

2. 登录失败写入操作日志：
   - 模块：`auth`
   - 动作：`LOGIN`
   - 结果：`FAIL`

3. 退出登录写入操作日志：
   - 模块：`auth`
   - 动作：`LOGOUT`
   - 结果：`SUCCESS`

4. 认证菜单冒烟测试补充登录/登出操作日志断言。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

4. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

5. 经营概览汇总测试通过：

```text
V1 dashboard summary test passed
```

6. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 前端导航状态持久化

### 已完成

1. 前端页签与当前模块本地持久化：
   - 存储键：`erp-nav-state`
   - 保存当前模块、当前一级菜单、已打开页签。

2. 刷新页面后自动恢复上次工作区：
   - 当前模块恢复。
   - 已打开页签恢复。
   - 当前一级菜单高亮恢复。

3. 页签切换、打开、关闭时同步更新本地导航状态。

4. 退出登录时清理导航状态，避免下个登录用户继承旧工作区。

### 验证结果

1. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

2. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

3. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

4. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 导入导出真实文件下载内容

### 已完成

1. 导出中心下载接口返回可下载内容：
   - `mimeType: text/csv;charset=UTF-8`
   - `fileContent` 返回 CSV 文本内容。

2. 导入失败原因下载接口返回可下载内容：
   - `mimeType: text/csv;charset=UTF-8`
   - `fileContent` 返回失败原因 CSV 文本内容。

3. 前端 API 客户端新增文本文件下载工具：
   - `saveTextFile(fileName, content, mimeType)`
   - 通过 Blob + object URL 触发浏览器下载。

4. 通用列表下载动作接入真实文件生成：
   - 后端返回 `fileContent` 时自动生成并下载本地文件。
   - 下载成功后仍保留文件名提示。

5. 导入/导出测试补充文件内容断言：
   - 校验 `mimeType`。
   - 校验 `fileContent` 包含对应任务号。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 导出中心文件内容测试通过：

```text
V1 export center test passed
```

4. 导入失败原因文件内容测试通过：

```text
V1 import list test passed
```

5. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

6. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

7. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 PRD 列表字段与操作自查优化

### 自查结论

1. 已按 PRD 自查 `docs/PRD-版本化产品需求/V1.0-ERP核心经营版/` 与 `frontend/src/module-config.js`。
2. 主要缺口集中在：
   - 系统管理模块配置缺失：用户、权限组、系统参数、单据编号规则、操作日志。
   - 客户价格调整单、客户价格查询配置缺失。
   - 调拨单未体现调出/调入仓库、调拨模式、在途数量。
   - 采购费用单、采购发票、费用单字段过粗。
   - 应收/应付操作缺少结算、预收/预付抵扣、核销记录。

### 已优化

1. 补齐系统管理 P0 模块配置：
   - `user`
   - `role`
   - `param`
   - `billNo`
   - `log`

2. 补齐客户价格相关配置：
   - `customerPrice`
   - `customerPriceQuery`

3. 细化关键业务模块字段与操作：
   - `transfer`
   - `purchaseExpense`
   - `purchaseInvoice`
   - `financeExpense`
   - `ar`
   - `ap`

4. 新增 PRD 配置一致性测试：
   - `development/06-testing/scripts/v1-module-config-prd-test.js`
   - 覆盖 P0/P1 字段和操作配置。

### 验证结果

1. PRD 配置一致性测试通过：

```text
V1 module config PRD test passed
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

4. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

5. 经营概览汇总测试通过：

```text
V1 dashboard summary test passed
```

6. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 PRD 模块功能继续补全

### 已优化

1. 基础资料补齐：
   - 商品档案增加商品类型、保质期、存储属性、建议零售价、库存上下限、默认供应商、默认仓库、可退标记。
   - 客户资料增加渠道类型、账期类型、截账日、付款日、发票抬头、税号，并补充冻结/解冻操作。
   - 供应商资料增加简称、账期天数、发票抬头、税号、维护收款账户操作。
   - 仓库资料增加货主、地址、是否默认。

2. 采购链路补齐：
   - 采购订单增加货主、预计到货日期、结算方式、成本金额，并补充保存草稿、反审核、终止、删除操作。
   - 采购入库增加库位、应入数量、实收数量、批次号、生产日期、到期日期、入库前/后成本、分摊费用。

3. 销售链路补齐：
   - 销售快速开单增加折扣、历史订单、组合收款和完整快捷键提示。
   - 销售订单增加创建人、行类型、成本金额，并补充保存草稿、反审核、关闭、删除操作。
   - 销售出库增加批次号、成本金额。
   - 销售收货单补充修改签收单价、反审核、查看应收操作。

4. PRD 配置一致性测试扩展覆盖 P2 模块字段和操作。

### 验证结果

1. PRD 配置一致性测试通过：

```text
V1 module config PRD test passed
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

4. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

5. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 客户供应商真实字段落库

### 已完成

1. 新增真实主数据表：
   - `base_customer`
   - `base_supplier`

2. 新增客户/供应商种子数据，覆盖 PRD 字段：
   - 客户：渠道类型、账期类型、截账日、付款日、发票抬头、税号、信用额度、应收/逾期余额。
   - 供应商：简称、类型、账期天数、默认采购员、默认收款账户、发票抬头、税号、应付余额。

3. `BaseMasterController` 客户/供应商分页从硬编码改为查询真实表。

4. `BaseMasterController.save/stop` 对客户、供应商走真实表：
   - 支持新增/更新客户。
   - 支持新增/更新供应商。
   - 支持客户/供应商停用并持久化状态。

5. 前端字段映射补充客户/供应商新增字段别名。

6. 新增持久化测试脚本：
   - `development/06-testing/scripts/v1-base-master-persistence-test.js`
   - 覆盖种子数据查询、新增、停用、状态持久化。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 客户供应商持久化测试通过：

```text
V1 base master persistence test passed
```

4. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

5. PRD 配置一致性测试通过：

```text
V1 module config PRD test passed
```

6. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

7. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 商品档案真实扩展字段落库

### 已完成

1. `base_goods` 表补充 PRD 扩展字段：
   - 商品类型
   - 保质期天数
   - 存储属性
   - 建议零售价
   - 库存上限/下限
   - 默认供应商
   - 默认仓库
   - 是否可退

2. 新增兼容既有 H2 文件库的 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，避免历史本地库启动失败。

3. 商品种子数据补充扩展字段，并修复 `MERGE INTO base_goods` 使用显式字段列表，避免表结构扩展后列数量不匹配。

4. 商品创建接口保存扩展字段：
   - `POST /base/goods/create`

5. 商品分页接口返回扩展字段：
   - `POST /base/goods/page`

6. 前端字段映射补充商品扩展字段别名。

7. `v1-base-master-persistence-test.js` 扩展商品档案断言：
   - 种子商品扩展字段存在。
   - 新增商品扩展字段可落库并查询。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 商品/客户/供应商持久化测试通过：

```text
V1 base master persistence test passed
```

4. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

5. PRD 配置一致性测试通过：

```text
V1 module config PRD test passed
```

6. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

7. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

## 2026-06-15 采购销售订单明细真实落库

### 已完成

1. 采购订单表补充扩展字段：
   - 货主
   - 预计到货日期
   - 结算方式
   - 成本金额
   - 审核信息

2. 销售订单表补充扩展字段：
   - 行类型
   - 成本金额
   - 创建人
   - 审核信息

3. 新增订单明细表：
   - `pur_order_detail`
   - `sales_order_detail`

4. 采购订单创建接口改为按请求明细落库：
   - 自动计算订单金额和成本金额。
   - 写入采购订单明细。

5. 采购订单详情接口改为从真实明细表读取。

6. 销售订单创建接口改为按请求明细落库：
   - 自动计算订单金额、未收金额和成本金额。
   - 写入销售订单明细。

7. 采购/销售订单分页接口返回新增扩展字段。

8. 新增订单持久化测试脚本：
   - `development/06-testing/scripts/v1-order-persistence-test.js`
   - 覆盖采购订单创建、详情明细、销售订单创建、分页扩展字段。

### 验证结果

1. 后端编译：成功。

```text
mvn -f backend/pom.xml -DskipTests compile
BUILD SUCCESS
```

2. 前端构建：成功。

```text
npm run build --prefix frontend
✓ built
```

3. 采购销售订单持久化测试通过：

```text
V1 order persistence test passed
```

4. 模块覆盖测试通过：

```text
V1 module coverage test passed: 60 endpoints
```

5. 增强业务流程测试通过：

```text
V1 enhanced flow test passed
```

6. 认证与菜单冒烟测试通过：

```text
V1 auth menu smoke test passed
```

## 2026-06-16 业务操作真实接口、前端拆分与权限范围

### 已完成

1. 基础资料生命周期操作细化：
   - 商品新增编辑、停用、冻结、软删除接口。
   - 客户/供应商新增冻结、解冻接口。
   - 客户/供应商保存时保留既有状态，避免编辑后误恢复为正常。
   - 基础资料变更写入操作日志。

2. 采购/销售订单生命周期补齐：
   - 采购订单新增反审核、关闭、软删除接口。
   - 销售订单新增详情、反审核、关闭、软删除接口。
   - 采购/销售订单分页状态补充已关闭、已删除展示。
   - 订单审核、反审核、关闭、删除写入操作日志。

3. 前端通用操作接入真实接口：
   - `module-api.js` 补充基础资料和订单新增动作映射。
   - `GenericBusinessList.vue` 支持编辑优先调用 update。
   - `GenericBusinessList.vue` 支持冻结、解冻、反审核、关闭、删除动作路由。

4. `App.vue` 拆分：
   - 新增 `fallback-menus.js`。
   - 新增 `useAuth`、`useNavigation`、`useDashboard`、`useToast`、`usePermission`。
   - 新增 `AppShell`、`AppHeader`、`AppSidebar`、`AppTabBar`。
   - 新增 `LoginPage`、`DashboardPage`、`FallbackBusinessList`。
   - `App.vue` 改为应用编排入口，保留现有页签/伪路由机制，未引入大规模路由改造。

5. 权限范围基础能力：
   - 角色运行表补充 `data_scope`。
   - 登录和当前用户接口返回角色、菜单范围、字段范围、数据范围。
   - 菜单接口支持按 `roleCode` 过滤演示菜单范围。
   - 新增字段范围接口：`POST /system/field-scope`。
   - 通用列表字段可见性同时受本地字段设置和字段权限控制。

6. 兼容性修复：
   - 库存流水号和资金流水号增加随机后缀，避免高频自动化测试下唯一键冲突。

7. 新增/扩展测试脚本：
   - 扩展 `v1-base-master-persistence-test.js`，覆盖商品编辑/冻结/停用、客户冻结/解冻/保留状态。
   - 新增 `v1-order-lifecycle-test.js`，覆盖采购/销售订单审核、反审核、关闭、软删除和销售详情。
   - 新增 `v1-permission-scope-test.js`，覆盖当前用户权限信息、菜单范围、字段范围。

### 验证结果

1. 后端编译：成功。

```text
mvn -f erp-wms-tms/backend/pom.xml -DskipTests package
```

2. 前端构建：成功。

```text
npm --prefix erp-wms-tms/frontend run build
✓ built
```

3. 新增和关键回归测试通过：

```text
V1 base master persistence test passed
V1 order persistence test passed
V1 order lifecycle test passed
V1 permission scope test passed
V1 module coverage test passed: 60 endpoints
V1 enhanced flow test passed
V1 auth menu smoke test passed
```

## 2026-06-16 权限上下文与数据范围继续增强

### 已完成

1. 前端权限上下文贯通：
   - 登录后先加载当前用户，再按 `currentUser.roleCode` 拉取菜单。
   - `GenericBusinessList` 接收当前用户 `roleCode`。
   - 字段权限请求按当前 `roleCode` 和 `moduleCode` 获取隐藏字段。
   - 分页请求在 `filters.roleCode` 中携带当前角色，供后端做数据范围演示过滤。

2. 后端订单数据范围演示过滤：
   - 采购订单分页按 `roleCode` 区分可见范围。
   - 销售订单分页按 `roleCode` 区分可见范围。
   - ADMIN 查看全部；采购/销售角色查看对应业务范围；跨域角色只查看演示本人数据。

3. 权限测试扩展：
   - `v1-permission-scope-test.js` 增加采购/销售订单数据范围断言。

### 验证结果

1. 后端编译：成功。

```text
mvn -f erp-wms-tms/backend/pom.xml -DskipTests package
```

2. 前端构建：成功。

```text
npm --prefix erp-wms-tms/frontend run build
✓ built
```

3. 权限、订单和关键回归测试通过：

```text
V1 permission scope test passed
V1 order lifecycle test passed
V1 module coverage test passed: 60 endpoints
V1 enhanced flow test passed
V1 auth menu smoke test passed
```

## 2026-06-16 订单编辑与详情接口增强

### 已完成

1. 采购订单编辑接口：
   - 新增 `POST /purchase/order/update`。
   - 仅待审核采购订单允许编辑。
   - 支持更新供应商、采购员、仓库、货主、结算方式。
   - 编辑时重算订单金额、成本金额。
   - 编辑时删除旧明细并重写采购订单明细。
   - 编辑操作写入操作日志。

2. 销售订单编辑接口：
   - 新增 `POST /sales/order/update`。
   - 仅待审核销售订单允许编辑。
   - 支持更新客户、业务员、仓库、行类型。
   - 编辑时重算订单金额、未收金额、成本金额。
   - 编辑时删除旧明细并重写销售订单明细。
   - 编辑操作写入操作日志。

3. 前端通用列表详情增强：
   - `purchaseOrder`、`salesOrder` 新增 `update` API 映射。
   - 通用列表“编辑”优先调用 `update` 接口。
   - 通用列表“查看/详情”动作会调用模块 `detail` 接口。
   - 详情弹窗展示后端返回的订单明细。

4. 新增订单编辑详情测试：
   - `development/06-testing/scripts/v1-order-edit-detail-test.js`
   - 覆盖采购订单编辑后表头与明细替换。
   - 覆盖销售订单编辑后表头、行类型与明细替换。

### 验证结果

1. 后端编译：成功。

```text
mvn -f erp-wms-tms/backend/pom.xml -DskipTests package
```

2. 前端构建：成功。

```text
npm --prefix erp-wms-tms/frontend run build
✓ built
```

3. 订单编辑详情与关键回归测试通过：

```text
V1 order edit detail test passed
V1 order lifecycle test passed
V1 permission scope test passed
V1 module coverage test passed: 60 endpoints
V1 auth menu smoke test passed
```

## 2026-06-16 通用表单真实 Payload 映射

### 已完成

1. 通用表单模型落地：
   - `GenericBusinessList.vue` 新增 `formModel`。
   - 打开新建/编辑表单时按当前模块字段初始化表单数据。
   - 表单输入、下拉、文本域统一使用 `v-model` 写入 `formModel`。

2. 基础资料保存 Payload 真实化：
   - 商品表单字段映射为 `goodsCode/goodsName/goodsType/spec/categoryName/...`。
   - 客户表单字段映射为 `customerCode/customerName/channelType/contactName/...`。
   - 供应商表单字段映射为 `supplierCode/supplierName/shortName/supplierType/...`。

3. 订单保存 Payload 真实化：
   - 采购订单表单字段映射为 `supplierId/warehouseId/buyer/ownerName/settlementMethod/details`。
   - 销售订单表单字段映射为 `customerId/warehouseId/salesman/lineType/details`。
   - 编辑保存成功后自动刷新列表，避免前端列表仍显示旧数据。

### 验证结果

1. 后端编译：成功。

```text
mvn -f erp-wms-tms/backend/pom.xml -DskipTests package
```

2. 前端构建：成功。

```text
npm --prefix erp-wms-tms/frontend run build
✓ built
```

3. 基础资料、订单和权限关键回归测试通过：

```text
V1 base master persistence test passed
V1 order edit detail test passed
V1 order lifecycle test passed
V1 permission scope test passed
V1 auth menu smoke test passed
```
