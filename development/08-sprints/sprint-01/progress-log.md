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
