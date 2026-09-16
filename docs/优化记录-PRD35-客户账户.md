# 优化记录：客户账户模块（PRD-35）

- 分支：`feat/finance-customer-account`
- 需求文档：`docs/客户账户管理-设计方案.md`（含上下游流程、真值/缓存模型、业务键与红字规则）
- 迁移：`V124__customer_account.sql`
- 里程碑：M1 账户列表与往来流水（已验收）→ M2 收款单预收（已验收）→ M3 预收核销/预收结算（已验收）→ M4 TMS 溢收转预收/期初预收/日结勾稽（已验收，V127）

---

## M1：客户账户列表 + 往来流水 + 数据修复（2026-09-16 验收）

### 数据模型（V124）

| 表 | 说明 |
| --- | --- |
| `fin_customer_account` | 每客户一行：应收余额、预收余额 + 客户档案/账期快照（业务员、渠道、线路、月结截款/付款日） |
| `fin_customer_account_flow` | 往来流水：账户类型 AR/ADVANCE、增减金额、滚存 `balance_after`、结算标志/已结金额、业务单号族（要货/出库退货/收款/核销）、`biz_key` 唯一键幂等 |
| `fin_advance_writeoff`(+`detail`) | 预收核销单主/明细（M3 启用） |
| `fin_receipt_bill.receipt_type` | 收款单类型：收款结算 / 预收收款 / 预收退款（M2 启用） |

**真值与缓存**：`fin_ar` + `fin_reconcile_record` 是真值；账户余额与流水结算标志是可重建缓存，由「数据修复」按真值重算，不允许手工改缓存。

### 功能

1. **客户账户列表**（财务管理 → 客户账户，权限 `fin.customer_account.view`）
   - 每客户一行；渠道/业务员/关键字（编号、店名、助记）/「本页不体现余额为 0」筛选，条件仅点「查询」生效。
   - 应收、预收余额蓝色链接，点开该客户的往来流水抽屉。
2. **往来流水抽屉**（`CustomerAccountFlowDrawer.vue`）
   - 应收/预收两本账 tab；默认近三个月；快捷期间（昨天/上周/本周/上月/本月）立即查询，其余条件点「查询」生效。
   - 展示此前余额（窗口前滚存）、当前余额、滚存账户金额、结算标志徽标（未结算/部分结算/已结算）、要货单号、出库/退货单号、结算单据号。
   - 应收流水业务键：形成 `AR:<ar_no>`、结算 `ARR:<record_id>`；客户编码按 收款单→退货申请→费用单→客户档案名→名称兜底 逐级解析。
3. **数据修复**（权限 `fin.customer_account.repair`）
   - MERGE 账户 → 按真值补缺形成/结算流水（biz_key 幂等）→ 回写结算标志与已结金额 → 按客户重排两本账余额链 → 账户余额=链尾。
   - 支持全量/单客户；反馈「账户 N 个，补形成流水 X 条、结算流水 Y 条，重排余额链 Z 条」；写系统操作日志（模块 fin.customer_account / DATA_REPAIR）。
   - 费用核销等不对应 `fin_ar` 的结算记录不进应收流水（与设计一致）。

### 验收

- 真实业务数据备份库上手工执行 V124（Flyway 历史分叉，用 RunScript 验证脚本本身）：13 笔应收全部生成形成流水，8 笔应收结算生成结算流水（1 笔费用核销正确排除）；3 个账户余额与真值差异 0；余额链断点 0；结算标志不一致 0。
- 接口 E2E（`/tmp/e2e-repair.mjs`，34 项断言）：冷缓存重建、余额 250/0、滚存链 [100,300,250]、关键字/渠道/零余额过滤、未结算过滤、预收空账、未来窗口此前余额、缺客户中文报错、二次修复幂等补缺 0、单客户范围。
- 浏览器真实交互（`development/06-testing/scripts/prd35-m1-ui-verify.js`，Edge headless + CDP，截图 4 张存 `development/06-testing/evidence/prd35-0*.png`）：列表账期快照、hideZero、抽屉滚存列 DESC 250/300/100、未结算过滤、本月 3 行/上月空态此前余额 250、预收空账 0.00、数据修复 confirm 与幂等反馈。
- 全程隔离验证：8082 + `backend/data/verify-v124*` 临时库 + 5174 临时 vite；用户 8080/5173 与 `erp-v1` 未触碰，验完临时库与进程已清。

### 踩坑

- INSERT…SELECT…WHERE NOT EXISTS 的占位符要把末尾反关联参数也算上（19 列 + biz_key 共 20 个 `?`），少一个报「Column count does not match」。
- `base_customer` 没有 simple_code/settlement_type/term_type/term_days 字段，账期快照取 `account_period_type/cutoff_day/payment_day`。
- 抽屉不展示 `ar_no`，界面单据标识是出库/退货单号（XSFH…），UI 断言不能用 AR 单号。
- 抽屉打开后页面上有两个「查询」按钮，脚本点击必须限定在 `.flow-modal` 内；快捷期间不重置结算标志，切期间断言前先把标志复位。

---

## M2：收款单三类型与预收联动（2026-09-16 验收）

### 功能范围

1. **收款单三类型**（`fin_receipt_bill.receipt_type`）：应收结算 `SETTLE`（既有）/ 预收收款 `ADVANCE` / 预收退款 `ADVANCE_REFUND`；付款单不变。
2. **收款抽屉**（`ReceiptDrawer.vue`）：三选一单选，默认应收结算；预收类**强制往来单位=客户**（供应商/往来单位单选置灰），切换类型清空已选往来单位。
   - 预收收款：金额列头「预收金额」，附说明「预收款计入客户预收余额，不核销应收」，不显示余额提示。
   - 预收退款：金额列头「退款金额」，选客户后实时查 `/finance/customer-account/advance-balance` 显示「当前预收余额 ￥x」，超额变橙色警告；前端阻断 + 后端守卫双重拦截。
3. **审核记账链**（预收类不走核销，不产生 reconcile 记录）：
   - 预收收款：资金流入 + 往来单位台账（biz `RECEIPT`）+ 客户预收流水 `ADV_RECEIPT(+)` + 账户预收余额增加 + GL 事件 `ADVANCE_RECEIPT`（V125 模板：借 1001 资金 / 贷 2203 预收账款，2203 带客户辅助核算）。
   - 预收退款：资金流出 + 台账 `RECEIPT_REFUND` + 流水 `ADV_REFUND(-)` + GL 事件 `ADVANCE_REFUND`（借 2203 / 贷 1001）；守卫「退款额 ≤ 预收余额」。
4. **反审核链**：红字流水 `ADV_REVERSE`（`is_red='Y'`，金额带符号）+ 原行置 `REVERSED`，资金/台账/GL 事件对称冲回；红字凭证规则复用总账既有状态机（草稿凭证直接删、已过账留待批量红字）。
   - 守卫：预收收款已被退款/预收核销占用时禁止反审核——「该预收款已被预收核销或退款使用（当前预收余额 X 元），请先反审核相关预收核销单或预收退款单」；反审核同样过日结封单守卫。
5. **轮次业务键**：`ADV_RECEIPT:<单号>#<轮次>` / `ADV_REFUND:<单号>#<轮次>` / `ADV_REVERSE:<单号>#<轮次>`，轮次=该单既有预收流水数+1，完整支持「反审核 → 改金额 → 再审核」。
6. **列表**：新增「收款类型」列与筛选（中文 ↔ 枚举双向映射）；非 SETTLE 单核销金额列显示「—」；按核销状态筛选时后端强制 `receipt_type='SETTLE'`，预收类不参与核销口径。
7. 反审核独立权限 `fin.receipt.unaudit`；GL 开关 `fin.gl.initialized='Y'` 后事件才落池，未建账静默跳过（`@TransactionalEventListener(AFTER_COMMIT)`，绝不影响业务事务）。
8. 迁移 `V125__fin_advance_receipt_templates.sql`：两个凭证模板 TPL_ADV_RECEIPT / TPL_ADV_REFUND。

### 顺带修复的产品缺陷（真实浏览器验收发现，均为现网逻辑问题而非测试问题）

1. **行操作「取消审核」误送审核接口（严重）**：收款/付款模块 `handleAction` 中 `/审核/` 正则分支排在 `/取消审核/` 之前，「取消审核」同样命中 `/审核/`，点反审核实际调 `/finance/receipt/audit`，报「仅待审核单据可审核」。已调整为先判取消审核并加注释；全文件排查其余模块均为 `===` 精确匹配，无同类问题。
2. **预收类单据核销金额列显示 0**：`module-api.js#valueForTitle` 增加信号判断，后端对非 SETTLE 单统一置 `reconcileStatusText='—'`，前端据此显示「—」。
3. **抽屉默认单据日期用 UTC**：`new Date().toISOString().slice(0,10)` 在东八区凌晨 0~8 点得到「昨天」，若昨日已日结封单，新建单据审核必 400。新增 `utils/dateTime.js#todayStr()`（本地今天），替换 `ReceiptDrawer.vue` 两处默认值。
4. **「展开更多」筛选名显示 `[object Object]`**：对象型筛选（`{label,type,options}`）被 `fields.join('、')` 直接拼接；`handleMore` 改为统一取 `label`。

**遗留（不在本次范围，已记录待统一处理）**：

- `QueryBar` 首屏只露 4 个筛选，第 5 个起收进「展开更多」，而该弹窗仅罗列名称、**不能录入**——收款单的核销状态/业务来源/收款日期等 UI 不可达（全模块既有缺陷，需统一改造 QueryBar）。M2 核销状态筛选语义由接口 E2E 覆盖。
- 通用列表金额列按原值渲染（`260` 而非 `260.00`），全模块既有行为，非本次回归。

### 验收

- **接口 E2E**：`development/06-testing/scripts/prd35-m2-api-e2e.mjs`，**66/66 通过**。覆盖：建单校验（非法类型/供应商预收拒绝）、预收审核四件套（资金/台账/流水/余额）、列表类型与「—」、退款与超额守卫、反审核占用守卫、红字冲回（ADV_REVERSE is_red + 原行 REVERSED）、改金额再审核轮次 #2、SETTLE 结算回归（含反审核恢复夹具基线）、列表筛选（中文/码值/核销状态排除预收类）、GL 草稿凭证（借 1001/贷 2203 客户辅助、退款对称）。
- **浏览器真实交互**：`development/06-testing/scripts/prd35-m2-ui-verify.js`（Edge headless + CDP），**11 段全绿**；截图 8 张存 `development/06-testing/evidence/prd35-m2-01..08-*.png`（预收抽屉、超额拦截、审核后列表、客户账户 300.00、退款余额提示、反审核守卫 toast、退款类型筛选、展开更多）。
- 全程隔离验证：8082 + `backend/data/verify-m2`（erp-v1 拷贝的临时库）+ 5174（`--host 127.0.0.1`）；用户 8080/5173 与 erp-v1 数据未触碰。

### 踩坑

- **Toast 只存活 2 秒**：「点完再等」的抓法会错过反馈，要在点击后立即以 100~150ms 轮询并把文案快照到 node 侧；断言以行状态（已审核/待审核）为真值，toast 仅作文案证据。
- **v-show 常驻遮罩**：`FundBillDrawer.vue` 用 `v-show`，页面里永远有一个隐藏的 `.bill-drawer-mask`；找当前抽屉必须按 `getBoundingClientRect()` 宽高可见性过滤，不能只判 DOM 存在。
- **Vite 8 默认绑 IPv6**（`[::1]`），headless Edge 把 localhost 解析到 127.0.0.1 直接拒连；临时前端必须 `--host 127.0.0.1 --port 5174 --strictPort`。
- **清数表名/列名**：凭证是 `fin_voucher`（非 fin_gl_voucher）、资金流水 `fin_fund_ledger.source_bill`（非 bill_no）、往来台账 `fin_counterparty_ledger.source_bill_no`（非 fin_counterparty_balance_flow）；`fin_customer_account_flow` **没有 id 列**，清理不能 ORDER BY id。
- **GL 只落事件池**：有 `fin_gl_event` 行而 `fin_voucher` 为空是正常状态，草稿凭证由会计「批量生成凭证」动作产生；`fin.gl.initialized` 懒缓存，SQL 改参数后必须重启后端。
- **下拉异步加载**：抽屉客户/资金账户选项在请求返回后才填充，脚本须 `waitFor` 选项出现再赋值，否则偶发 setNative undefined。
- **登录入参**是 `{username, password}`（不是 account）；demo-token 会过期，脚本一律走登录拿 token。
- 接口 E2E 的单据日期不能硬编码：夹具日结封单到「昨天」，硬编码隔日日期审核必 400；改为本地今天动态值。

---

## M3：预收核销单（XH）+ 结算使用预收（2026-09-16 验收）

### 功能范围

1. **预收核销单**（财务管理 → 预收核销，权限码 `fin.advance_writeoff.view/add/edit/audit/unaudit/delete`，单字 **XH**，`BillNoGenerator` 已登记 `fin_advance_writeoff.writeoff_no`）
   - 列表（`AdvanceWriteoff.vue`）：客户/单号/状态/业务来源（手工/应收结算/对账单结算）/核销日期区间筛选（条件仅点「查询」生效）；自动单行内只有「查看」，带「结算生成」标记。
   - 新建抽屉（`AdvanceWriteoffDrawer.vue`）：选客户后展示 ERP 预收余额与按 **FIFO（到期日升序，空到期日排最后）** 列出的未结应收行，勾选自动带入未结金额、可手工调整；守卫：只选本客户 AR、单行 ≤ 该单未结、合计 >0 且 **≤ 客户预收余额**，前端中文拦截 + 后端 IllegalArgumentException 双重校验。
   - 审核：按明细更新 `fin_ar` 真值（received/unreceived/status）→ 预收账户扣减流水（`ADVANCE_WRITE_OFF(-)`）+ 应收账户「预收冲应收」形成流水的结算标志回写 → 两本账余额链重排 → 按来源发货单聚合回写 `sales_receipt.receive_status`（未收款/部分收款/已收款）→ GL 事件 `ADVANCE_WRITE_OFF`。
   - 反审核：**直接删除该 XH 的真值行**（预收冲应收是往来转账，无红字流水），按客户重排两本账余额链，发货单收款状态按剩余真值重算；待审核可删除。
   - 幂等业务键：手工/自动 XH `XH:<单号>#<轮次>`、逐 AR `XHAR:<单号>:<arNo>#<轮次>`；反审核再审核完整支持。
2. **结算弹窗「使用预收款」**（参照业务确认的弹窗样式）
   - 两个弹窗（`ARSettlementDialog.vue` 应收明细、`StatementSettlementDialog.vue` 客户对账）统一交互：灰色「使用预收款」面板显示「客户 ERP 预收余额：￥x」+ 勾选框 + 预收结算金额输入（提示「最多 ￥X，其余走资金账户」）；下面多行资金账户表，图例「账户合计须 = ￥X」实时联动；校验 `预收 ≤ min(净额, 预收余额)`、`账户合计 = 净额 − 预收`，不满足确认按钮置灰/中文拦截。
   - 应收结算副标题「已选 N 张单据，本次结算净额 ￥X ……退货负单参与净额抵扣」；标题「收款结算」。
   - 提交语义：预收部分**按客户汇总自动生成并审核一张 XH**（`source_bill_no=SK单号`，禁止单独反审核）；现金部分在线写 fin_ar/核销记录/账户 AR 结算流水（业务键 `ARR:<record_id>`，与数据修复同源、幂等不双补；反审核为 `ARRR:`）；**现金与预收可共存、可逐行手工改金额**；**全额预收 0 现金也照生 SK 收款单**，作为反审核级联锚点。
   - 对账单结算：按对账单归属拆单（验证场景两张对账单各生一张自动 XH）；**抹零不压缩 AR 核销计划**——增加对账单 paid_amount 并另写 `EXPENSE_WRITEOFF` 真值行，故现金真值行合计可大于 SK 现金总额；自动 XH `source_bill_no=CS单号`。
   - 反审核链：SK「取消审核」→ 先按 source_bill_no 找齐自动 XH 连带作废（预收真值/流水回滚），再回退 fin_ar/f/ap 与现金红字流水，发货状态聚合重算；凭证按总账既有状态机处理；状态回 PENDING 为既有约定。
3. **GL 模板 V126**：`ADVANCE_WRITE_OFF` → 借 2203 预收账款 / 贷 1122 应收账款，两侧均带客户辅助核算（纯往来转账无资金科目）；事件在 `fin.gl.initialized='Y'` 事务提交后落池，source_bill_type 中文「预收核销单」，支持按单号联查。
4. **菜单/权限**：`MenuConfig` 新增预收核销菜单项与 4 个权限点，`perm-inventory.md` 同步；前端 router / menu-map / fallback-menus 同步登记。

### 顺带修复的产品缺陷（全部现网逻辑问题，非测试问题）

1. **客户/供应商对账单收付款状态恒为「未收款/未付款」（严重，两侧同 bug）**：结算 UPDATE 在同一条语句 SET 右侧用 `CASE WHEN paid_amount>=total_amount ...` 判状态，**H2 同一 UPDATE 的 SET RHS 读到的是旧行值**，新收款永远落进旧状态分支，部分/完成收款状态失效。改为 Java 侧按新已收（付）额算好状态再绑定参数（`FinanceController` 客户对账单、供应商对账单两处，附注释）。
2. **应收结算弹窗未收金额重复扣减**：可收金额在 unreceivedAmount 基础上又减了一次 receivedAmount，默认带入值偏小；改为直接取 unreceivedAmount。
3. **应收列表页整页白屏（TDZ，严重）**：`ARSettlementDialog.vue` 中 `arLines`/`totalSettle` 文本声明在引用它们的 `netSettle` 等 computed **之后**，Vue 3 `<script setup>` setup 执行期抛 `ReferenceError: Cannot access 'totalSettle' before initialization`；弹窗被列表页静态引入，异常直接打挂整页渲染（不是组件局部失败）。按依赖顺序前置声明并加注释。
4. **对账单结算弹窗 props 当 ref 用**：`props.selectedRows.value` 等写法（props 数组不是 ref）抛 ReferenceError；统一 props.x 直取。
5. **待办/通知角标接口 GET/POST 错配，角标静默失效（本轮定位修复）**：`SystemController` 的 `/system/todo/pending-count`、`/system/notification/unread-count`、`/system/todo/summary` 三个接口标成 `@GetMapping`，而 `AppShell.vue`/`DashboardPage.vue`/`TodoPage.vue` 全部用 `post()` 调用——每次登录、路由切换、轮询都 405。更隐蔽的是兜底异常处理器把它当服务端故障：整段堆栈打 ERROR，却回 **HTTP 200 + `{code:500}`**，网络面板只见 200，角标因前端 try/catch 静默不显示。修复：① 三接口改 `@PostMapping`（ERP 端接口统一 POST 的既有约定，`TmsNotificationController` 类注释里早记录过同一个坑）；② `GlobalExceptionHandler` 增 `HttpRequestMethodNotSupportedException` 专项处理——回真实 **HTTP 405** + 中文「请求方式不支持」，日志只打一行 WARN（方法名 + 允许的方法），不再刷 ERROR 堆栈。

### 验收

- **接口 E2E**：`development/06-testing/scripts/prd35-m3-api-e2e.mjs`（夹具 `prd35-m3-fixtures.sql`，7 行 AR + 4 张配对发货单 + 经手人），**94/94 通过**：A 预收审核 500；B 手工 XH 四类建单守卫/审核/两本账流水/发货状态/反审核恢复/删除；C 应收结算跨客户预收守卫、金额守卫、余额不足守卫、FIFO、自动 XH 禁单反审核、SK 反审核级联、0 现金全额预收也生 SK；D 两张 CS 合并结算（抹零 2 + 预收 78 + 现金 100 = 180）按对账单各生 XH、反审核级联；E GL 启用后事件池→草稿凭证（借2203/贷1122、客户辅助、30=30 平衡、单据联查）；F 分页过滤与无令牌 401；总览预收 470/应收 800。
- **浏览器真实交互**：`development/06-testing/scripts/prd35-m3-ui-verify.js`（Edge headless + CDP），**A~D 全绿**；截图 9 张存 `development/06-testing/evidence/prd35-m3-01..09-*.png`（XH 列表、新建抽屉 FIFO/超额联动、待审核、已审核、应收结算使用预收、结算完成、对账单结算使用预收、结算完成、自动单来源列）。
- **角标修复回归**：修复后全链路（接口 E2E + 浏览器全流程）后端日志「系统异常」0 条、405 警告 0 条；Vite 代理日志中 43 次角标轮询全部真实 200；curl 验证 GET 误调返回 HTTP 405 + `{code:"405",message:"请求方式不支持"}`。
- 前端 `npm run build` 通过；后端 mvn 离线打包通过。
- 全程隔离验证：8082 + `backend/data/verify-m3`（仓库受控种子 erp-v1 的拷贝，验完即弃）+ 5174；用户 8080/5173 与 erp-v1 数据未触碰。

### 踩坑

- **H2 同一 UPDATE 的 SET 右侧读旧行值**（本库第三次踩，前两次是金额字段）：派生状态（pay_status）必须在 Java 侧算好再落库，不能在 SQL 里引用本行刚改的列。
- **`<script setup>` 常量暂时性死区会打挂整页**：computed/ref 的文本声明顺序必须满足依赖顺序；被静态引入的组件 setup 抛 ReferenceError 时，引用它的路由页整页白屏。排查不能只看网络面板，要在页面里挂 `window.addEventListener('error'/'unhandledrejection')`，配合 CDP `Runtime.exceptionThrown` 才能看见。
- **兜底异常处理器把异常包成 HTTP 200+code:500** 时，任何按 HTTP 状态码的网络抓包/代理日志都抓不到故障；定位手段是后端开 `DispatcherServlet=DEBUG`，比对同线程「请求行 → 异常 → Completed 200」即可判定异常属于该请求本身而非嵌套分派。
- **CDP `Runtime.evaluate` 传源码的反斜杠陷阱**：JS 源码以模板字符串送入浏览器时 `\d`/`\s` 会被吞成 `d`/`s`（`[\s\S]` 变成匹配字面反斜杠），断言正则一律写 `[0-9]`/`[\s\S]`，或双写反斜杠。
- 列表「业务来源」是枚举中文标签列、「来源单号」单列 SK/CS 号、操作列另有「结算生成」标记，三处是分开渲染的，UI 断言不能按一个拼接字符串匹配。

---

## M4：TMS 溢收自动转预收 + 期初预收 + 日结预收勾稽（2026-09-16 验收）

迁移：`V127__prd35_m4_advance_opening.sql`（日结应收快照加列 + 期初预收暂存表 + 参数 P0189）。

### 功能范围

1. **TMS 门店结算溢收自动转预收（AC-13）**
   - 参数 **P0189 `tms.settle.overpay-to-advance`**（分组「TMS配送」，默认 **Y**）：Y=司机交账单审核核销后，某门店「实缴 > 本次核销应收合计」的未匹配多收款，自动生成并审核一张 **ADVANCE 预收收款单**；N=维持原状仅写日志（`RECONCILE_REMAIN`），财务人工处理。
   - 自动单走真实 SK 序号（不使用测试前缀），`receipt_type='ADVANCE'`、`business_source='DRIVER_OVERPAY_ADV'`、`related_bill_no=门店结算号`，列表中与手工预收单完全同构，可按来源号溯源到交账链路。
   - **资金不双计**：资金已由结算收款单按实缴全额记入资金流水（一笔），自动单**不写 `fin_fund_ledger`**，只补三件套——客户预收流水 `ADV_RECEIPT(+)`、往来单位台账、GL 事件（借资金科目 / 贷 2203 预收账款，2203 带客户辅助）；资金科目取门店结算第一条正额资金账户明细，其资金账户→总账科目映射必须已配置（见踩坑）。
   - 幂等：按门店结算号查重，交账单重复审核/二次核销不重复生单；一张交账单多个门店溢收时逐门店生成。
   - 反审核：自动单可在收款单列表走**收款单标准反审核链**（红字 `ADV_REVERSE` 流水、GL 对称冲回；因本来就没有资金流水，红行同样不产生资金流水，资金始终只一笔）。
   - 实现：`FinanceController#createAdvanceReceiptFromStoreOverpay`（交账审核核销事务内调用）。
2. **客户期初预收 ADV_OPENING（AC-14）**
   - 新表 `fin_adv_init` 镜像 `fin_ar_init`：Excel 导入（部分成功，VALID/ERROR 全行落库 + 失败文件下载）、手工新增/编辑/删除行、整批清空、合计展示；模板列=客户编码/客户名称/原单据号/原单据日期/预收金额/备注，金额必须 >0，只认客户编码。
   - 菜单 **财务管理 → 客户期初预收**（`/finance/init-adv`），权限点 `fin.init_adv.view/edit/delete/import/post/reverse` 六个，沿用期初向导外壳（`OpeningShell` + `OpeningInitPanel`，与库存/应收/应付同构）。
   - 过账：每客户一行 `ADV_OPENING` 预收流水（`post_date=建账日`、来源号 `QCYK-<批号>-<seq>`、bizKey `ADVO:<批号>:<seq>`），`advance_balance` 一次性建立并重排余额链；**不造收款单、不动资金流水**；批号 `QCyyyyMMdd+4位`，biz_init_post 记 `init_type='ADV'`。
   - 总账：ADV_OPENING 本身**不发 GL 事件**；2203 期初余额随总账「一键引入业务期初」引入，借/贷平衡由总账期初体系保证。
   - 反建账：原因必填（≥2 字留痕）；已有业务日结 / 总账已启用拒绝；某客户建账流水之后存在**仍生效**的预收核销/退款流水时拒绝（守卫见下「修复的真实缺陷」）；通过后删除 ADV_OPENING 流水、重排余额链、暂存行恢复可编辑，可改后重新建账。
3. **日结预收勾稽**
   - `biz_close_ar_daily` 新增快照列 **`advance_account_balance`**：应收定版时直接取 `fin_customer_account.advance_balance`（结账时点客户账户缓存），与每客户行一同冻结；原「负应收重分类为预收」的 `advance_amount` 列保留作兜底口径，正常数据下两列应相等。
   - 日结向导新增勾稽提示（`.tie-advance`）：展示客户账户预收余额合计与重分类兜底口径，金额异常时提示排查；定版台账页签与 RJ 打印单 AR 表新增「预收余额」列。
   - 封账守卫：所有客户账户流水的审核/反审核（含自动溢收单）一律过 PRD-33 封单硬校验。
4. **菜单/权限/前端**：MenuConfig 注册 `fin.init_adv` 页面；router / menu-map / fallback-menus 同步；`perm-inventory.md` 启动自动登记 `/init/adv` 十端点；新增 `InitAdv.vue`（期初预收面板）、日结向导/定版台账/打印单改造（`DayClose.vue`）、参数页可检索 P0189（系统参数管理，TMS配送分组）。

### 顺带修复的产品缺陷（现网逻辑问题，非测试问题）

1. **期初预收反建账守卫未排除 REVERSED 行，撤销路径永久卡死（严重）**：XH 核销单/预收退款单反审核后，原 `ADV_WRITE_OFF`/`ADV_REFUND` 流水行**保留在表中**（仅置 `reverse_status='REVERSED'`，另追加红字 `ADV_REVERSE` 冲回行）。反建账守卫初版按「建账后是否存在核销/退款行」COUNT 统计，把这些已冲回行也算作占用——于是「先反审核下游单据 → 再反建账」这条设计内的撤销路径永远过不去，客户一旦做过期初+核销演练就只能动数据库。修复：`CustomerAccountService#checkAdvanceOpeningReversable` 增加 `COALESCE(reverse_status,'') <> 'REVERSED'` 条件，只统计仍生效的下游行（约 `CustomerAccountService.java:531-552`，含原理注释）。

### 验收

- **接口 E2E**：`development/06-testing/scripts/prd35-m4-api-e2e.mjs`（夹具 `prd35-m4-fixtures.sql`），**76/76 通过，连续 3 轮全绿**（夹具幂等可重跑）。覆盖：
  - AC-13 三张交账单：① 应收 200 实缴 300 溢收 100（参数 Y）→ 自动 ADVANCE 单已审核、预收流水 +100、**资金流水 0 新增**、GL 凭证借 1001/贷 2203 各 100 且 2203 带客户辅助、`related_bill_no`=门店结算号；自动单反审核 → 红字 ADV_REVERSE、资金流水仍为 0；② 溢收 40 重复触发幂等只生一张；③ 应收 30 实缴 80 溢收 50（参数 N）→ 无单据仅 `RECONCILE_REMAIN` 日志；
  - AC-14：Excel 导入 2 成功/4 失败行（批号、`ADV_OPENING`/`QCYK-`/post_date=建账日、bizKey 幂等、无资金流水无收款单）、XH 核销后反建账被拒、反审核 XH 后反建账成功、暂存恢复后重建账、总账一键引入 2203 辅助数；
  - 日结：向导预收合计 240、定版快照两列预收、封账后审核/重复审核守卫、无令牌 401。
- **浏览器真实交互**：`development/06-testing/scripts/prd35-m4-ui-verify.js`（Edge headless + CDP），**24/24 通过，连续 2 轮全绿**；截图 10 张存 `development/06-testing/evidence/prd35-m4-01..10-*.png`：期初预收空态/建账钮禁用、两行录入合计 ￥200.00、必填拦截、导入弹窗（模板下载 .xlsx/选文件）、建账后批号 QC 与行只读、反建账原因拦截与成功、日结向导勾稽提示（账户预收合计 **340.00**=200 期初+100+40）与重分类兜底提示、定版台账两列预收（M4ADV1=120.00）、RJ 打印单 AR 表预收列、参数页检索「溢收」P0189 行（TMS配送 / 当前值 Y / 备注含「未匹配」）。
- 后端日志核对：ERROR 0 条；WARN 仅幂等建客户的预期唯一键冲突（脚本已 catch）与 Flyway/H2 版本提示。
- 前端 `npm run build`、后端 mvn 离线打包均通过。
- 全程隔离验证：8082 + `backend/data/verify-m4`（受控种子拷贝，验完即弃）+ 5174；用户 8080/5173 与 erp-v1 数据未触碰。

隔离库复跑命令：

```bash
# 后端（backend/ 目录下）
/c/Users/Administrator/jdk21/bin/java -jar target/erp-wms-tms-backend-0.1.0-SNAPSHOT.jar --server.port=8082 \
  "--spring.datasource.url=jdbc:h2:file:./data/verify-m4;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
  "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration"
# 前端
cd frontend && VITE_API_TARGET=http://localhost:8082 npm run dev -- --host 127.0.0.1 --port 5174 --strictPort
# 跑测（脚本自带 反日结复位 → 套夹具 → 参数缓存 evict → 建客户 序列，可直接连跑）
node development/06-testing/scripts/prd35-m4-api-e2e.mjs
node development/06-testing/scripts/prd35-m4-ui-verify.js   # 需先起 Edge --remote-debugging-port=9224
```

### 踩坑

- **两个 JVM 内存缓存让「直改 SQL 复位」失效（本里程碑最费时）**：
  - `SysParamService` 用 volatile Map 缓存全部参数，且布尔取值只认字面量 `"Y"`；直改 `sys_param_runtime` 后必须调 `/system/param/update`（接口内部 evict）或重启后端才生效。
  - `BizDayCloseGuard.lastClosedDate` 是懒加载缓存（volatile + loaded 标志），**直删 `biz_day_close` 表清不掉它**，之后业务持续报「已存在业务日结记录（封单日 …）」；只有日结/反日结事务 afterCommit 里的 `guard.evict()` 能复位。因此夹具式重跑的正确序列是：先调 `POST /finance/day-close/reopen`（reason ≥2 字，忽略「未日结」）走正式反日结链 → 再 RunScript 套夹具 → 再调参数更新接口 evict 参数缓存。缓存若已被直删表搞脏，只能重启后端。
- **`@FUND` 模板占位符强制要求资金账户→总账科目映射**：`base_fund_account.gl_account_code` 为 NULL 时，自动溢收单的 GL 事件生成失败并报「资金账户「X」未映射总账科目」（事件落「生成失败」，不影响业务事务但凭证出不来）。这是配置前置项不是代码缺陷：总账管理 → 档案科目映射里把资金账户配齐（如 现金→1001），详见 `docs/总账模块-系统说明与操作手册.md`。另外 H2 Shell 直连读中文错误信息必须加 `-Dstdout.encoding=UTF-8`，否则 GBK 乱码看不出原因。
- **自动溢收单用真实 SK 序号**（SK20260916xxxx，不是 SK-M4 前缀），夹具清理必须按 `business_source='DRIVER_OVERPAY_ADV' AND related_bill_no LIKE 'MJ-M4-%'` 反查六张表（FK 顺序：voucher_entry→voucher→gl_event/counterparty_ledger→receipt_detail→receipt_bill），按前缀删会残留并污染下一轮。
- **收款单 `receipt_type` 合法值只有 SETTLE/ADVANCE/ADVANCE_REFUND**（`normalizeReceiptType`）：旧种子库惯用的 `'NORMAL'` 现在建单直接报「收款类型不正确：NORMAL」；测试夹具与新代码一律用 `'SETTLE'`（`tms_store_settlement_detail.sign_type` 的 `'NORMAL'` 是另一字段，别连带改）。
- **UI 脚本侧（均非产品缺陷）**：期初表单的客户下拉是 SearchSelect 自定义控件（占掉普通 input 索引），要按字段标题定位、点 `.ss-control` 展开选项；`setNativeValue` 要覆盖 INPUT/TEXTAREA/SELECT 三个原型否则 textarea 触发 Illegal invocation；导入弹窗没有表头文字列表，只能断言「下载模板/选择文件」；建账后页面展示只读行而非空态文案；Node 侧正则不要双写反斜杠（`/\bY\b/`），页面 ev 模板字符串内的 `\s` 才需双写；打印单有多个 table，找「预收余额」表头要限定 table 作用域；参数列表没有「参数编号」列（P0189），只有参数键/名称/当前值/默认值/分组/备注。
- RunScript 套含中文的夹具要加 `-Dfile.encoding=UTF-8`；后端必须在 `backend/` 目录启动（H2 相对路径老坑）。
