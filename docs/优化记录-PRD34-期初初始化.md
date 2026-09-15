# 优化记录 - PRD-34 业务期初初始化（库存 / 客户应收 / 供应商应付）

- 分支：`feat/finance-opening-init`
- Flyway：V123（四表）；后端里程碑 commit 100ced9，前端/模板/文档同分支追加提交
- 关联：PRD-32 总账初始化（一键引入业务期初）、PRD-33 业务日结（日结后锁定）

## 一、需求与方案要点

新账套启用时导入存量：库存期初（含启用 WMS 的库位期初）、客户应收期初、供应商应付期初。统一范式：

> Excel 导入 / 手工增改删 → 暂存表（VALID/ERROR 全行落库）→ 一次性期初建账写正式账表 → 参数锁定 → 首次业务日结前有条件反建账。

用户拍板的 7 项决策见 PRD 文档第 2 节，核心：库存按批次 / 按库位双流程禁止同键混用；成本必填允许 0；账龄自建账日起算（到期日 +30），原单日期仅留存；商品+仓已有数量余额整批拒绝；只导未达余额不造历史收付款。

## 二、改动清单

### 后端

- V123 建表：`inv_stock_init`、`fin_ar_init`、`fin_ap_init`、`biz_init_post`（风格同 V93：VARCHAR(32) PK、DECIMAL、IF NOT EXISTS、无外键）。
- 新包 `com.erp.inventory.init`：`StockInitService`（~880 行）/ `StockInitController`（`/init/stock`，含双模板 GET）。
- 新包 `com.erp.finance.init`：`ArInitService`/`ArInitController`、`ApInitService`/`ApInitController`（`/init/ar`、`/init/ap`）。
- `com.erp.init.InitSupport`：三模块公共守卫（posted / 无日结 / GL 未启用）、post_no（QC+日期+4 位 MAX+1）、过账主表读写、参数置位/清除、导入任务与失败文件落盘；本次新增 `nextTaskNo()` 与带 taskNo 的 `recordImportTask` 重载。
- `com.erp.wms.BinStockService`：从 WmsInboundService / WmsInternalService 两份私有 addBinStock 抽取的公共服务，`addInboundBinStock` 支持来源单号参数化（期初 QTRK-），两个旧调用方改为委托。
- `MenuConfig`：库存菜单加 `inv.init_stock`；财务菜单加 `fin.init_ar`、`fin.init_ap`（应收期初→应付期初→日结→总账期初动线）；启动自动同步菜单 + 授权超管。
- 权限码：`inv.init_stock.*` / `fin.init_ar.*` / `fin.init_ap.*`，`.post`（期初建账）、`.reverse`（反建账）为独立功能点。

### 前端

- 新页 `views/init/`：`InitStock.vue`（按批次 / 按库位两页签，共用一次过账）、`InitAr.vue`、`InitAp.vue`；公共组件 `OpeningShell.vue`（状态/锁定 banner/建账/反建账弹窗）、`OpeningInitPanel.vue`（暂存分页、筛选、导入、行 CRUD、清空）。
- `importPresets.js` 新增 initStockBatch / initStockBin / initAr / initAp 四个 preset（templateUrl 指向后端真实模板）。
- 路由 `/inventory/init-stock`、`/finance/init-ar`、`/finance/init-ap`；`router/index.js` + `menu-map.js` 三处同改。
- 库位下拉按所选仓库级联 `/wms/bin/page`（只列 NORMAL），切换仓库清空已选库位。

### 模板与工具

- `backend/src/main/resources/templates/` 四个 xlsx（红底白字必填表头 + 冻结首行 + 筛选 + 「导入说明」第二页签）。
- `development/04-backend/gen-init-templates.py`：纯 Python 标准库 zipfile 手写最小 OOXML（inlineStr，免 sharedStrings）生成器。

## 三、关键实现口径

1. **库存成本**：按（商品+仓）聚合 Σ(qty×单价)/Σqty 作建账成本（移动加权平均是商品+仓维度、不按批次）；按（商品+仓+批次）各调一次 `InventoryCostService.inboundAtCurrentCost`；过账前先清 physical=0 的残留余额/批次行，保证期初单价真正建立 cost_price。**金额三层校准（M4 实测补修）**：`inv_stock_balance.cost_price`/`inv_stock_ledger.cost_price` 列为 DECIMAL(18,2)，6 位均价 10.478261 被截成 10.48 后后续批次沿用截断价，46×10.48=482.08 比实际多 0.08 尾差，会直接轧不平总账 1405 引数。过账时改按采购引擎既有口径校准：台账 amount 记批次实际成本、批次账记批次自身 4 位单价（8/12/7，与 purchaseInbound 的 upsertBatchStock 一致）、循环末把余额 stock_amount 校准为实际总额（482.00），cost_price 显示 10.48 属列精度正常表现。
2. **双流程混用拦截**：文件内 fileModeByKey、跨批 DB 历史 existingModeMap、手工行保存三处；复合键一律用 `List.of(商品,仓[,批次])` 做 Map 键，禁止拼分隔符字符串。
3. **批次/效期推导**：手填批号 > 生产日期 yyyyMMdd > 空；效期显式 > 生产日期+保质期天数；成本引擎不写效期时显式 UPDATE `inv_batch_stock.expiry_date`。
4. **AR/AP 立账**：BillNoGenerator 真实插入取号；AR status=UNVERIFIED / 未开票 / received=0 / created_at=建账日；AP 未来票 / paid=0 / invoiced_amount=0；due_date 统一建账日+30；source_bill=QCAR-/QCAP-/QTRK-{过批号}-{seq}。
5. **反建账守卫**：库存四层余量校验（台账无后续流水、balance=过账量、batch=过账量、bin_stock=过账量）后按 台账→批次→余额→库位账（回减 used_qty，GREATEST 兜底）→库位流水 顺序回退；AR/AP 查 fin_reconcile_record 无核销才允许；原因必填、主表 REVERSED 留痕。
6. **导入任务三号一致**：暂存行 task_no、sys_import_task_runtime、失败文件名共用 support.nextTaskNo()。

## 四、踩坑记录（避免重犯）

- **模板表头不能加 `*` 后缀**：ImportDialog 按表头全名精确匹配 fieldMap，加星号整列失配；必填只靠红底表头表达。
- **SheetJS 重写丢样式**：模板改走 Python 标准库 zipfile 直接生成 OOXML；本环境 Python 3.14 可用（旧「无 python」记忆已过时）。
- **inv_stock_init 24 列 INSERT 占位符**：posted 是字面量 'N'，占位符为 21 个 `?` + 'N' + 2 个 `?`，不是 24 个。
- **Edit 曾把 \x01 控制字符写进 Java 字面量/注释**：用 `grep -nP '\x01' | cat -v` 排查、sed 删行修复；复合键重写为 `List.of(...)` 后彻底消除。
- **Vue 模板**：`v-for` 与 `v-else` 不能放同一元素，用 `<template v-else>` 包裹；空态 colspan 要随 locked 切换（未锁多「失败原因/操作」两列）。
- **Map 复合键禁用 `|` 拼接 split**：仓库名可含任意字符；用 `List.of(a,b,c)` 不可变对象做键。
- H2：字符串单引号；不支持 UPDATE...FROM；JOIN 同名列必须别名限定；queryForList 可能回大写 key（本模块状态 SQL 已用裸列别名 + 显式 get）。
- **空表 SUM 返回 NULL 连 COALESCE 列都无效**：零行时聚合根本没有输入行，`COALESCE(SUM(CASE WHEN...),0)` 才是正解，只包列不包 SUM 拆箱仍 NPE（M1 实测三模块 status 全挂「系统繁忙」，10 处聚合统一改法）。
- **LocalDate 不能 format 含时分秒的 pattern**：`LocalDate.now().format(yyyyMMddHHmmss)` 抛 UnsupportedTemporalTypeException: HourOfDay（M2 实测导入直接 500）；nextBatchNo 改用 LocalDateTime。
- **mvn spring-boot:run 编译/fork 竞态**：实测 forked JVM 在 javac 写完 class 前启动（ApiResponse.class 落盘晚于扫描 3 秒），ClassNotFoundException 启动失败。规避：先 `mvn -o -DskipTests package`，再直接 `$JAVA_HOME/bin/java -jar` 起隔离实例。
- **验收造数必须与正式验收仓隔离**：其他入库单反审核会留存「入库/反审核出库」两行台账（设计如此）且可能带来分位成本尾差；期初反建账守卫按「同商品+仓存在非期初前缀台账」拒绝。M3 余额守卫改用专用仓 WHM3GD 造数，WHINIT 保持干净可全链路重跑。
- **page 接口返回中文标签不是码值**：台账 direction 返「入库/出库」（InventoryController 映射）、AR status 返「未核销/已核销」（SQL CASE）；断言要码值/标签双兼容。批次分页数量字段是 physicalQty，且 keyword 不匹配 batch_no，须全量拉回客户端过滤。
- **过账号无连字符**：QC+yyyyMMdd+4 位（QC202609150007），来源号才是 QTRK-/QCAR-/QCAP-{过批号}-{seq}，正则别写错。

## 五、验证

- 后端离线编译通过（`/c/Soft/apache-maven-3.9.16/bin/mvn -o -f backend/pom.xml -DskipTests compile`）。
- 前端 `npm --prefix frontend run build` 通过，InitStock/InitAr/InitAp/OpeningInitPanel chunk 正常产出。
- 四个模板用 SheetJS 解析验证：双页签、表头列数正确（10/12/7/6）、无 `*` 后缀。
- **隔离 8081 空库全链路实测 m1~m6 全绿 ✅**（2026-09-15）：m1 菜单/4 模板/初始状态；m2 按批次 2 成 7 败 + 按库位 1 成 3 败 + AR/AP 坏行、失败文件下载；m3 手工增改删/双流程混用/ERROR 拒过账/余额守卫；m4 过账核账——余额 46 个显示均价 10.48、实际金额 482.00，台账/批次金额合计 582、wms 库位 6 与 used_qty=6、fin_ar 未核销/未开票/0 已收/建账日/+30、AP 未来票；m5 首次业务日结 RJ-20260915 成功（滚存全平）、日后锁定、反日结恢复；m6 GL 一键引数 1122=800/2202=400/1405=582(51)、反建账三账/往来/库位全部归零、重新过账引数恢复、试算补 3001 后启用总账、期初三模块 GL 锁定。
- **浏览器三页面真实交互验收通过 ✅**（2026-09-15，m7 脚本 `development/06-testing/scripts/init-m7-ui-verify.js`）：为避开用户占用的 5173，vite 起 **5174** 代理隔离 8081 冒烟库，用 **Edge headless + CDP（9222，node 原生 WebSocket，零第三方依赖）** 驱动真实页面：菜单可见、SearchSelect 拼音下拉与库位按仓库级联、库存手工批次行新增（UIB1 10@8）→编辑改 12、按库位页签新增（UIBIN1 6@7）、导入弹窗真实模板入口、建账 confirm、反建账原因必填弹窗；AR（CUSINIT UIAR1 500）/AP（SUPINIT UIAP1 300）手工行→建账→正式账单号回显→反建账全链路。8 张截图证据见 `development/06-testing/evidence/`（含 README 索引）。CDP 两个坑：整页导航后 window 重置，助手函数须每次重新注入；取值表达式必须显式 `return`（async 块体不返回表达式值）。
- perm-inventory.md（端点 861）随后端启动已重新生成，同批提交。
