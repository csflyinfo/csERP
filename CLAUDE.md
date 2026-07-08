# CLAUDE.md

#  ERP 项目 AI 协作规则

---

## 📋 项目信息

| 项           | 值                                    |
| ------------ | ------------------------------------- |
| **项目名称** | ERP + WMS + TMS 商贸经销商管理系统    |
| **当前版本** | V1.0 核心经营版                       |
| **技术栈**   | 后端：Spring Boot + H2 + JdbcTemplate |
|              | 前端：Vue 3 + Vite + Element Plus     |
| **后端端口** | 8080                                  |
| **前端端口** | 5173                                  |
| **演示账号** | admin / admin123                      |

---

## 📁 目录结构

```
erp-wms-tms/
├── backend/                    # 后端项目
│   ├── src/main/java/
│   │   └── com/erp/
│   │       ├── auth/           # 认证鉴权
│   │       ├── base/           # 基础资料（商品/客户/供应商）
│   │       ├── common/         # 公共工具/异常/响应
│   │       ├── inventory/      # 库存管理
│   │       ├── purchase/       # 采购管理
│   │       ├── sales/          # 销售管理
│   │       ├── finance/        # 财务管理
│   │       ├── report/         # 报表中心
│   │       ├── system/         # 系统管理
│   │       └── flow/           # 业务流程服务
│   └── src/main/resources/
│       ├── schema.sql          # 数据库表结构
│       └── data.sql            # 初始化种子数据
├── frontend/
│   ├── src/
│   │   ├── views/              # 页面组件
│   │   ├── components/         # 通用组件
│   │   ├── composables/        # 组合式函数
│   │   ├── module-config.js    # PRD 模块配置（字段/操作）
│   │   ├── module-api.js       # API 接口映射
│   │   └── fallback-menus.js   # 兜底菜单配置
│   └── package.json
├── development/06-testing/scripts/  # 自动化测试脚本
└── docs/PRD-版本化产品需求/    # PRD 需求文档
└── prototype/v1-erp-complete/    # 原型文档
│       └── v1-erp-ui/            # UI文档

```

## 📝 代码规范

### 通用规则

1. 优先可读性，禁止写晦涩简化代码；复杂逻辑必须加注释
2. 所有变量、函数使用**驼峰命名**，常量全大写下划线分割`ORDER_STATUS`
3. 禁止魔法数字、魔法字符串，全部提取至`constant.ts`统一管理
4. 新增功能必须补充JSDoc注释，说明入参、出参、业务用途
5. 所有日期按YYYY-MM-DD格式存储和展示。所有的时间按YYYY-MM-DD HH:MM:SS
6. 单价4位小数、金额两位小数、成本单价6位小数
7. 系统字体方案：思源黑体（中文）+ Inter（英文数字）+ Fira Code（等宽）。不要使用微软雅黑
8. **商品批次号生成规则（全局统一）**：格式为 `YYYYMMDD`（无前缀），根据输入的生产日期生成。商品库存支持空批次和空生产日期。具体逻辑：
   - 用户手动输入了批次号 → 直接使用用户输入值
   - 用户未输入批次号但填了生产日期 → 自动生成 = `生产日期.format("yyyyMMdd")`（如 `20260807`）
   - 生产日期也未填 → 批次号留空（不生成）
   - 涉及位置：后端 `PurchaseController.createInbound`、前端 `SalesReturnInboundDrawer.onProductionDateChange`

### 后端 Java

1. **必须** 使用统一 API 响应：`ApiResponse.success(data)` / `ApiResponse.fail(message)`
2. **必须** 使用 `PageResult.of(list, pageRequest)` 做分页
3. **优先** 使用 H2 兼容 SQL，避免数据库特有语法
4. **必须** 对业务操作写入 `sys_operation_log_runtime` 日志
5. **必须** 校验 `Authorization: Bearer demo-token` 鉴权
6. 遵循阿里Java开发手册，POJO属性禁止基础类型，使用包装类
7. SQL禁止`select *`，只查询需要字段；分页统一使用PageHelper
8. 事务仅加在业务服务层，Controller层不处理事务

### 前端 Vue

1. **必须** 使用 `apiClient` 统一发起请求，自动携带 token
2. **必须** 通过 `module-config.js` 配置模块字段和操作，避免硬编码
3. **优先** 复用 `GenericBusinessList.vue` 通用列表能力
4. **必须** 接口失败时降级展示 PRD 示例数据，不阻塞流程
5. 启严格模式`strict: true`，所有变量必须定义类型，禁止`any`滥用
6. 组件统一使用setup语法糖，组件名大写开头
7. 日志统一使用项目封装`log.ts`，**禁止直接console.log**
8. 接口返回统一封装响应类型，增加全局异常拦截
9. **查询条件只在点击「查询」按钮时生效**，选择下拉、修改日期、输入关键字等操作**不自动触发查询**。这一约定适用于所有模块的 QueryBar 查询，全局一致
10. **批量操作按钮的显示规则**：所有支持批量操作的业务模块，勾选列表记录后才在**左下角**显示对应的批量操作按钮（停用/启用/审核/删除等）。**只有勾选的记录中有符合该批量操作条件的记录时才显示按钮**（例如：批量审核只在勾选中有待审核记录时显示，批量删除只在勾选中有可删除记录时显示）。按钮不放在顶部操作栏





---

## 🔧 常用命令脚本

| 操作             | 命令                                                         |
| ---------------- | ------------------------------------------------------------ |
| **后端编译**     | `mvn -f backend/pom.xml -DskipTests package`                 |
| **后端启动**     | `mvn -f backend/pom.xml spring-boot:run`                     |
| **前端构建**     | `npm --prefix frontend run build`                            |
| **前端启动**     | `npm --prefix frontend run dev`                              |
| **核心冒烟测试** | `node development/06-testing/scripts/v1-core-smoke-test.js`  |
| **模块覆盖测试** | `node development/06-testing/scripts/v1-module-coverage-test.js` |
| **检查端口**     | `netstat -ano | grep ":8080\|5173"`                          |

### 项目运行&测试命令

#### 启动项目

```bash
# 安装依赖
npm install
# 本地开发启动
npm run dev
# 打包生产环境
npm run build
# 单元测试
npm run test
```

#### Maven打包

mvn clean package -DskipTests

#### 本地启动

java -jar target/xxx.jar

### 启动顺序

1. 优先启动后端 → 等待 8080 端口监听 → `curl /api/actuator/health` 验证 UP
2. 再启动前端 → 等待 5173 端口监听 → 访问页面

---

## 🔄 上下文管理（日常必做）

> 长会话必做项。目的：避免上下文塞满后丢失关键决策，导致重复踩坑或返工。

### 触发阈值

**上下文占用 ≥ 95%** 时，依次执行：① **提示用户**当前已接近上限 → ② **写入记录** → ③ **压缩上下文**

### 三步流程

1. **提示**：明确告知用户「上下文已达 95%，即将压缩」，不要静默执行
2. **记录**：追加写入 `docs/上下文压缩记录.md`，每次一节，格式见下。**必须在压缩前写完**
3. **压缩**：保留「当前任务目标 / 未完成事项 / 关键决策与原因 / 踩过的坑」，丢弃已完成的中间过程、文件全文、重复日志

记录格式：

```markdown
## YYYY-MM-DD HH:MM 压缩记录

- **当前任务**：一句话说明正在做什么
- **已完成**：要点列表（含改动文件路径）
- **未完成/待办**：要点列表
- **关键决策**：决策 + 为什么这么定（避免下次重新论证）
- **踩坑与结论**：问题现象 → 根因 → 解决方式
- **验证状态**：已验证 / 待验证（编译、测试、迁移是否跑过）
```

### 注意事项

1. **记录先写再压缩** —— 压缩后信息就取不回来了
2. 关键决策**必须写「为什么」**，只写「做了什么」等于没记录
3. 未完成事项要写到「下一步能直接接手」的粒度，不要只写模块名
4. 记录文件只追加不覆盖，历史节次保留，便于回溯整条决策链
5. 涉及数据库迁移时，务必记录**迁移版本号**（如 V24）与是否已执行，防止版本冲突

---

## ⛔ 禁止操作

1. **严禁删除** `development/06-testing/scripts/` 下的测试脚本，所有改动必须通过回归测试
2. **禁止** 大规模重构目录结构，现有单包分层架构保持稳定
3. **禁止** 引入新的外部依赖，当前阶段保持轻量底座
4. **禁止** 提交 `backend/data/` H2 数据库文件到 Git
5. **禁止** 直接 `rm -rf backend/data` 或删除 `erp-v1.mv.db` —— 里面是**用户手工建的业务数据**（商品/客户/供应商/仓库/订单）。遇到 Flyway 校验失败等情况：优先 `mvn flyway:repair`，或先 `cp erp-v1.mv.db erp-v1.mv.db.bak` 备份再操作。清库请走 `reset-db.bat`（脚本已内置自动备份到 `backend/data/backups/`）。

---

## 🔤 数据库字段名变更规则

> **当前阶段（开发期）：允许改字段名。上生产后：禁止改字段名，只能 `ADD COLUMN` 扩展。**

### 开发期改字段名的要求

改字段名**必须一次性改全链路**，不允许只改一半。改完要能编译、能构建、能跑通业务流程。

**改动清单（缺一项就算没改完）：**

1. **迁移脚本**：新建迁移文件用 `ALTER TABLE ... RENAME COLUMN 旧名 TO 新名`，**不要直接改历史迁移文件**（Flyway 校验和会失败）
2. **后端**：实体类字段与 `@TableField`、Mapper/XML、所有手写 SQL（含 `JdbcTemplate` 里的字符串 SQL）、DTO / `Map` 的 key
3. **前端**：`module-api.js` 的 `EXACT_TITLE_MAP` 映射、组件里读该字段的地方（`row.xxx` / `data.xxx`）、`module-config.js` 的列配置
4. **测试脚本**：`development/06-testing/scripts/` 下引用该字段的断言

**收尾验证：**

```bash
# 全局搜旧字段名，确认无残留（下划线与驼峰两种写法都要搜）
grep -rn "old_field_name\|oldFieldName" backend/src frontend/src development
mvn -f backend/pom.xml -DskipTests compile   # 后端编译
npm --prefix frontend run build              # 前端构建
```

⚠️ 前端漏改不会报错，只会**静默显示 undefined / 空白**，所以必须靠 grep 兜底，不能只依赖编译通过。

### 上生产后（后期）

生产环境有真实数据和外部集成，改名成本与风险都不可控，届时切换为：

- **禁止** 改已存在字段名，一律用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 新增字段
- 旧字段保留不动，由新代码写新字段，老数据与老接口继续可用
- 需要废弃的字段只停止写入，不做物理删除

---

## ⚠️ 已知坑点

1. **H2 数据库 ALTER 限制**：H2 不支持 `DROP COLUMN IF EXISTS`，新增字段必须用 `ADD COLUMN IF NOT EXISTS`
2. **自动化测试并发**：高频执行测试可能导致流水号冲突，脚本已加随机后缀
3. **前端本地存储**：字段设置、导航状态、token 均存储在 `localStorage`，开发阶段可手动清理
4. **端口占用**：8080/5173 端口被占用时需先 `taskkill /F /PID <PID>` 杀死旧进程
5. **后端热重载**：修改后端代码后必须重新编译重启，不支持热加载
6. **Windows Git Bash**：PowerShell 命令不兼容，优先用 Git Bash 执行 grep/netstat
7. **H2 列别名被大写**：连接串开了 `CASE_INSENSITIVE_IDENTIFIERS=TRUE`，`SELECT adjust_id adjustNo` 取回的 key 是 `ADJUSTNO`，前端读 `adjustNo` 全是 `undefined`。**查询不要写驼峰别名**，取原始列名后在应用层转驼峰（参考 `CustomerPriceController.toCamel()`）
8. **H2 无 MySQL 专有函数**：即使 `MODE=MySQL`，`DATE_FORMAT()` 等函数仍不存在。日期格式化一律在 Java 层用 `DateTimeFormatter` 处理
9. **Flyway 版本号冲突**：多人/多任务并行时容易撞号（曾出现两个 `V23__`），重复版本会让**后端直接启动失败**。新建迁移前先 `ls` 一遍 migration 目录

---

## ✅ 提交前检查清单

1. ✅ 后端编译通过：`mvn package -DskipTests`
2. ✅ 前端构建通过：`npm run build`
3. ✅ 核心冒烟测试通过
4. ✅ 模块覆盖测试通过
5. ✅ 新增功能已补充对应测试脚本
6. ✅ 新增迁移文件**版本号未与他人重复**（`ls backend/src/main/resources/db/migration/` 确认，重复会导致 Flyway 启动失败）
7. ✅ 长会话若触发过压缩，`docs/上下文压缩记录.md` 已更新
