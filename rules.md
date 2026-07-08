# ERP 项目 AI 协作规则

## 项目信息

- **项目名称**：ERP + WMS + TMS 商贸经销商管理系统
- **当前版本**：V1.0 核心经营版
- **后端**：Spring Boot 3.3.6 + Java 21 + MyBatis Plus 3.5.7 + Flyway + H2（开发）/ MySQL 8（生产）+ Redis 7
- **前端**：Vue 3.5 + Vite 8 + Pinia + ECharts，纯手写 CSS（无 Element Plus / Ant Design 等 UI 库）
- **部署**：Docker Compose（MySQL + Redis + Spring Boot + Nginx）
- **后端端口**：8080 | **前端端口**：5173 | **API 统一前缀**：`/api`
- **演示账号**：admin / admin123

## 目录结构

```
erp-wms-tms/
├── backend/
│   ├── src/main/java/com/erp/
│   │   ├── auth/           # 认证鉴权（JWT + Spring Security）
│   │   ├── base/           # 基础资料（商品/客户/供应商/仓库）
│   │   ├── common/         # 公共工具（ApiResponse/PageResult/GlobalExceptionHandler/BaseEntity）
│   │   ├── inventory/      # 库存管理（库存查询/流水/调拨/盘点/预警）
│   │   ├── purchase/       # 采购管理（订单/收货/退货/费用/发票）
│   │   ├── sales/          # 销售管理（订单/出库/签收/退货）
│   │   ├── finance/        # 财务管理（应收/应付/收款/付款/凭证）
│   │   ├── report/         # 报表中心
│   │   ├── system/         # 系统管理（用户/角色/菜单/参数/日志）
│   │   └── flow/           # 业务流程服务（跨模块编排）
│   └── src/main/resources/
│       ├── schema.sql              # 数据库表结构（H2 开发环境）
│       ├── data.sql                # 初始化种子数据
│       ├── application.yml         # 主配置
│       └── db/migration/V*.sql    # Flyway 迁移脚本
├── frontend/
│   ├── src/
│   │   ├── views/              # 页面组件（GenericBusinessList.vue 为核心通用列表）
│   │   ├── components/         # 通用组件（QueryBar/ProTable/Drawer 等）
│   │   ├── composables/        # 组合式函数（useToast/useAuth 等）
│   │   ├── styles/app.css      # 全局样式 + CSS 设计令牌（:root 变量）
│   │   ├── module-config.js    # PRD 模块配置（字段/筛选/操作/布局）
│   │   ├── module-api.js       # API 接口映射 + EXACT_TITLE_MAP 字段映射
│   │   └── fallback-menus.js   # 兜底菜单配置
│   └── vite.config.js          # /api 代理转发到后端 8080
├── development/                # 00~08 编号的架构/DB/API/测试/Sprint 文档
├── docs/                       # PRD + UI 设计 + 优化记录
├── prototype/                  # 高保真 HTML 原型
├── docker-compose.yml          # 容器编排
└── start.bat / stop.bat / reset-db.bat  # 一键启动/停止/清库脚本
```

## 架构模式

### 后端分层

每个业务包严格遵循四层结构：

```
entity → mapper → service(impl) → controller
```

- **Entity**：继承 `BaseEntity`，POJO 属性必须使用包装类（禁止 `int`/`boolean` 等基础类型）
- **Mapper**：继承 MyBatis Plus `BaseMapper<T>`
- **Service**：接口 + Impl 实现，事务注解仅加在此层
- **Controller**：继承 `BaseController` 或 `BaseMasterController`，不处理事务

跨模块公共能力由 `common/` 提供：`ApiResponse<T>`、`PageResult<T>`、`GlobalExceptionHandler`、`BizState`（单据状态常量）。

### 前端架构

- **通用列表驱动**：绝大多数业务模块通过 `module-config.js` 声明配置 → `GenericBusinessList.vue` + `QueryBar.vue` + `ProTable.vue` 自动渲染 CRUD 页面，新增模块只需补充配置
- **字段映射**：`module-api.js` 中的 `EXACT_TITLE_MAP` 将中文列标题映射为后端字段名（camelCase）
- **菜单兜底**：`fallback-menus.js` 提供后端未返回菜单时的默认导航结构
- **状态管理**：Pinia 管理全局状态（用户/权限/主题）

## 代码规范

### 通用规则

- 优先可读性，禁止晦涩简化代码；复杂逻辑必须加注释
- 变量、函数使用**驼峰命名**，常量全大写下划线分割 `ORDER_STATUS`
- 禁止魔法数字/魔法字符串，提取至常量文件统一管理
- 新增功能必须补充注释，说明入参、出参、业务用途
- **日期格式** `YYYY-MM-DD`；**时间格式** `YYYY-MM-DD HH:MM:SS`
- **数值精度**：单价 4 位小数、金额 2 位小数、成本单价 6 位小数
- **字体方案**：思源黑体（中文）+ Inter（英文数字）+ Fira Code（等宽），禁止微软雅黑

### 后端 Java

- 统一 API 响应：`ApiResponse.ok(data)` / `ApiResponse.fail(code, message)`
- 统一分页：`PageResult.of(list, pageRequest)`
- 优先 H2 兼容 SQL，避免数据库特有语法
- 业务操作必须写入 `sys_operation_log_runtime` 操作日志
- 鉴权校验：`Authorization: Bearer demo-token`
- SQL 禁止 `SELECT *`，只查询需要的字段
- 业务校验失败抛出 `IllegalArgumentException`，由 `GlobalExceptionHandler` 统一捕获
- 使用 JSR-303 注解（`@NotBlank`/`@NotNull`/`@Positive`）+ `@Valid` 做入参校验

### 前端 Vue

- 必须使用 `apiClient` 统一发起请求，自动携带 token
- 必须通过 `module-config.js` 配置模块字段和操作，避免硬编码
- 优先复用 `GenericBusinessList.vue` 通用列表能力，新增模块只补配置
- 接口失败时降级展示 PRD 示例数据，不阻塞用户流程
- 组件统一使用 `<script setup>` 语法糖，组件名大写开头
- 禁止直接 `console.log`，使用项目封装的日志工具
- 前端通过 `useToast.js` composable 统一展示错误提示

### 前端样式

- **无 UI 组件库**，纯手写 CSS，以 `:root` CSS 变量为核心的设计令牌体系
- 颜色禁止硬编码十六进制值，一律引用 `var(--primary)`、`var(--text)`、`var(--line)` 等
- 字体禁止写死字体族名，使用 `var(--font-sans)` / `var(--font-mono)`
- 页面骨架复用 `.shell`、`.card`、`.tablebox`、`.filter`、`.page-ops` 等已有类
- 弹窗使用 `.modal-lite`（居中）或 `.drawer-overlay` + `.drawer-lite`（右侧抽屉）
- 列表使用 `.tablebox` + `<table>` 结构，表头 sticky、行高 32px，数值列加 `.num`
- 不引入 Tailwind / Sass / Less，只写原生 CSS

## 数据库规范

- 数据库变更使用 Flyway 迁移脚本，命名 `V{version}__{description}.sql`，存放 `src/main/resources/db/migration/`
- **严禁修改** `schema.sql` 中已存在的字段名，新增字段用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
- H2 不支持 `DROP COLUMN IF EXISTS`，只能 `ADD COLUMN IF NOT EXISTS`
- Flyway 校验失败时优先 `mvn flyway:repair`，或先备份 `.mv.db` 再操作
- 清库必须走 `reset-db.bat`（内置自动备份到 `backend/data/backups/`）
- 生产环境使用 MySQL 8 + utf8mb4 字符集 + Asia/Shanghai 时区

## 常用命令

| 操作         | 命令                                                              |
|--------------|-------------------------------------------------------------------|
| 一键启动     | `start.bat`（检查环境→释放端口→安装依赖→启动后端+前端→打开浏览器） |
| 一键停止     | `stop.bat`                                                        |
| 清库重置     | `reset-db.bat`（自动备份后清空重建 H2 数据）                       |
| 后端编译     | `mvn -f backend/pom.xml -DskipTests package`                      |
| 后端启动     | `mvn -f backend/pom.xml spring-boot:run`                          |
| 前端构建     | `npm --prefix frontend run build`                                 |
| 前端启动     | `npm --prefix frontend run dev`                                   |
| 核心冒烟测试 | `node development/06-testing/scripts/v1-core-smoke-test.js`       |
| 模块覆盖测试 | `node development/06-testing/scripts/v1-module-coverage-test.js`  |
| 容器化部署   | `docker compose up -d`                                            |

### 启动顺序

1. 先启动后端 → 等待 8080 端口监听 → `curl /api/actuator/health` 验证 UP
2. 再启动前端 → 等待 5173 端口监听 → 访问页面

## 禁止操作

- **严禁修改** `schema.sql` 中已存在的表结构字段名
- **严禁删除** `development/06-testing/scripts/` 下的测试脚本，所有改动必须通过回归测试
- **禁止** 大规模重构目录结构，现有单包分层架构保持稳定
- **禁止** 引入新的外部依赖，当前阶段保持轻量底座
- **禁止** 提交 `backend/data/` H2 数据库文件到 Git
- **禁止** 直接删除 `erp-v1.mv.db`——里面是用户手工建的业务数据（商品/客户/供应商/仓库/订单）
- **禁止** 硬编码十六进制色值，必须使用 CSS 变量
- **禁止** 引入 Tailwind / Sass / Less / Element Plus / Ant Design 等 UI 框架

## 已知坑点

- **H2 ALTER 限制**：不支持 `DROP COLUMN IF EXISTS`，新增字段必须用 `ADD COLUMN IF NOT EXISTS`
- **H2 与 MySQL 差异**：开发用 H2、生产用 MySQL，SQL 必须两端兼容
- **兜底异常 HTTP 200**：`GlobalExceptionHandler` 对未捕获异常返回 HTTP 200 而非 500，前端需按 `ApiResponse.code` 判断
- **自动化测试并发**：高频执行可能导致流水号冲突，脚本已加随机后缀
- **前端本地存储**：字段设置、导航状态、token 均存储在 `localStorage`，开发阶段可手动清理
- **端口占用**：8080/5173 被占用时需 `taskkill /F /PID <PID>` 杀死旧进程
- **后端无热重载**：修改 Java 代码后必须重新编译重启
- **Windows PowerShell**：不支持 `&&` 连接符，使用 `;` 分号替代
- **Flyway 基线**：设置了 `baseline-on-migrate=true`，老库升级不会报错

## 核心业务术语

| 术语 | 说明 |
|------|------|
| 移动加权平均法 | 系统成本计算核心算法，每次入库后重算库存平均成本单价，不同仓库成本分组独立核算 |
| 日结数据 | 每日定时生成的客户/供应商/商品结算快照，用于财务对账和报表 |
| 预计结款日 | 根据客户账期规则计算的应收款到期日，TMS 模式下以司机签收日为基准 |
| 截账日 | 月结账期的周期截止日，31 表示按自然月计算 |
| 成本分组 | 仓库级别的成本核算分组，不同仓库独立进行移动加权平均 |
| 价格组 | 系统默认 10 个价格组，销售开单按 客户专属价→等级价→标准售价→手工价 优先级匹配 |
| 多单位设置 | 商品支持大/中/基本单位多级换算，各单位可分别设置条码、价格、重量体积 |
| 波次下放 | WMS 拣货策略，按线路/片区聚合销售出库单为波次任务 |

## 提交前检查清单

1. 后端编译通过：`mvn -f backend/pom.xml -DskipTests package`
2. 前端构建通过：`npm --prefix frontend run build`
3. 核心冒烟测试通过
4. 模块覆盖测试通过
5. 新增功能已补充对应测试脚本
6. 新增数据库字段已添加 Flyway 迁移脚本
