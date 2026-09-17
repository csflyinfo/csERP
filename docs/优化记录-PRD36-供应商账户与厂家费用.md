# 优化记录：供应商账户与厂家费用（PRD-36）

- 需求文档：[供应商账户管理-设计方案.md](供应商账户管理-设计方案.md)（V1.2，18 章）
- 迁移区段：V128~V131
- 分支：feat/finance-supplier-account
- 范式参照：PRD-35 客户账户（真值 vs 可重建缓存、bizKey 幂等、红字只增不删、封账守卫、同刻先形成后结算）

## M1：供应商账户只读上线（V128，2026-09-16）

### 数据结构

- `fin_supplier_account`：每供应商一条，三余额 `ap_balance`/`prepay_balance`/`expense_balance`
  （应付/预付/费用）+ 档案快照（默认采购员/结算方式/账期天数）+ version 乐观锁。
- `fin_supplier_account_flow`：AP/PREPAY/EXPENSE 三本账流水，`biz_key` 唯一幂等；
  列含 ap_no、factory_expense_no、source_bill、payment_no、writeoff_no、reconcile_id、receipt_no。
- M2/M3 单据表先建：`fin_factory_expense(+detail)`（JF）、`fin_factory_settle(+detail/+ap/+fund)`（DX）、
  `fin_prepay_writeoff(+detail)`（FX）。
- 既有表加列：`fin_payment_bill.payment_type`（历史行 SETTLE）、
  `fin_ap_init.prepay_amount`/`generated_prepay_flow_id`（M4 启用）、
  `base_expense_type.factory_gl_credit_subject_code`（M3 启用）、
  `fin_expense_bill.factory_expense_no`（M3 FE 回指）、
  `biz_close_ap_daily.prepay_account_balance`/`expense_account_balance`（M4 启用）。

### 历史回填（迁移即修复）

- 供应商 key 解析链（fin_ap 只存名称）：pur_receipt.supplier_code → pur_return.supplier_code
  → fin_ap_init.generated_ap_no → base_supplier 按名称 → 名称本身兜底。
- AP 形成流水：一行 fin_ap 一行（AP_RECEIPT/AP_RETURN 负向红字/AP_OPENING 期初），
  窗口函数按 occurred_at+形成优先重排 balance_after。
- AP 结算流水：fin_reconcile_record 中命中 fin_ap 的 SUPPLIER 记录（费用类/抹零自然排除）。
- 账户应付余额按 Σ fin_ap.unpaid_amount 真值重算；历史负应付不自动转预付，历史厂家费用不补。

### 代码与页面

- 后端 `com.erp.finance.account`：SupplierAccountConst / SupplierAccountFlowLine /
  SupplierAccountService / SupplierAccountController（/finance/supplier-account：page、flow/page、repair）。
- 前端：财务管理 > 供应商账户（SupplierAccount.vue + SupplierAccountFlowDrawer.vue），
  三余额可点，抽屉三 tab（应付/预付/费用）带此前余额、快捷期间、结算/兑现标志过滤。
- 菜单权限：fin.supplier_account（查看/数据修复功能点）。

### 验证（隔离 8082 + verify-prd36-m1 库）

- 夹具：S001 收货应付 1000（已付 300）、退货 -100、期初 500、付款核销 300、费用记录 50（应排除）；S002 空账户。
- 迁移在带真值库上重跑 1 次成功；API E2E 28 断言全绿（含此前余额/过滤/中文错误/修复幂等）；
  Edge headless UI 验收全绿（截图 evidence/prd36-m1-01~04）。
- 脚本：development/06-testing/scripts/prd36-m1-{fixtures.sql,reset-v128.sql,api-e2e.mjs,ui-verify.js}。

## M2：付款三类型 + 预付核销 FX + 对账单预付结算（V129，2026-09-17）

### 功能范围

- 付款单三类型（fin_payment_bill.payment_type）：
  - SETTLE 应付结算（原逻辑）；PREPAY 预付付款（入供应商预付余额，不核销应付）；
    PREPAY_REFUND 预付退款（预付余额守卫：退款额不得超过预付余额，审核整单事务回滚）。
  - 预付类往来单位锁定供应商；审核生资金台账 OUT/IN 与供应商账户 PREPAY_PAYMENT/PREPAY_REFUND 流水，
    反审核写红字冲回行（只增不删）。
- 预付核销单 FX（fin_prepay_writeoff/_detail，RECONCILE_TYPE='PREPAY_WRITE_OFF'）：
  /finance/prepay-writeoff 8 端点（page/detail/create/update/delete/audit/cancel-audit）。
  行候选取该供应商未结应付（按到期日升序、空到期日末位，与 FIFO 同序）；
  四类行级守卫：超未结、不属于该供应商、重复行、合计超预付；审核冲减预付/应付并写真值核销记录，
  反审核红字全恢复；bizKey 轮次 `FX:单号#round` / `FXAP:单号:apNo#round`。
- 供应商对账单结算（ssSettle）支持 usePrepayAmount：预付部分自动生并审核 FX（business_source=
  STATEMENT_SETTLE），现金部分照常生付款单；全额预付（现金 0）也生 0 额付款单作为级联锚点，
  锚点单不发 PAYMENT 总账事件，预付凭证由 FX 的 PREPAY_WRITE_OFF 事件承担。
- 自动 FX 禁止手工反审核（中文拦截：请对对应付款单反审核以级联冲回）；锚点付款单反审核级联：
  自动 FX 置 CANCELLED、AP 真值/预付余额/对账单已付与抹零全部恢复。
- 总账 3 个事件模板（V129）：PREPAY_PAYMENT（借预付/贷资金）、PREPAY_REFUND（反向）、
  PREPAY_WRITE_OFF（借 2202 应付 / 贷 1123 预付，2202 带供应商辅助核算）；
  FinanceReportSupport 白名单登记三事件；BillNoGenerator 登记 FX 单号。
- 前端：财务管理 > 预付核销单（PrepayWriteoff.vue + PrepayWriteoffDrawer.vue，
  余额面板/未结候选/超额红字提示/自动单只读）；付款抽屉三类型 radio 与预付明细段；
  供应商对账结算弹窗「使用预付款」勾选（ERP 预付余额、预付上限受净额约束、现金需求与账户合计实时勾稽）。
- 菜单权限：fin.prepay_writeoff（add/edit/delete/audit/unaudit 功能点，已登记 perm-inventory）。

### E2E 捕获并修复的真实缺陷（3 处，均在 FinanceController）

1. ssSettle 锚点付款单 INSERT 占位符多 1 个（VALUES 中间组 8 占位 vs 7 列），
   H2 报 Column count does not match，对账单结算 500。已按列对齐。
2. 同 INSERT 把供应商编码误写入 counterparty_name/object_name 列，
   锚点单按供应商名称过滤查不到（paymentRow 返回 null）。已改为编码/名称各归其列。
3. 供应商侧缺客户侧早已镜像的抹零真值链：结算抹零未写 EXPENSE_WRITEOFF 真值记录，
   锚点单反审核也不回退 write_off_amount，导致对账单抹零金额无法清零。
   三处修齐：ssSettle 补写真值（挂对账单号）、反审核 AP 回退循环跳过 PREPAY_WRITE_OFF 与
   EXPENSE_WRITEOFF（前者 FX 级联自回、后者只冲对账单）、对账单回退 UPDATE 补 write_off_amount
   CASE 分支（镜像客户侧旧值语义）。SupplierAccountService.repair 的形成流水谓词带
   EXISTS(fin_ap) 且类型白名单不含这两类，抹零真值落表不会生成脏 AP 流水。

### 顺手修复（预存缺陷，M2 UI 验收发现）

- 供应商对账单列表「付款状态」列恒显「—」：后端返回 payStatus，EXACT_TITLE_MAP 只映了 paymentStatus
  （采购订单字段）。改为 ['paymentStatus','payStatus'] 双键兜底（两类记录不携带对方字段，互不影响）。
- 客户/供应商对账单 QueryBar 的收/付款状态过滤发的是中文键「收款状态/付款状态」，后端 trimF("payStatus")
  收不到、过滤静默失效；两个 select 补 `key:'payStatus'`。

### 验证（隔离 8082 + verify-prd36-m1 库，0 业务数据残留）

- API E2E：prd36-m2-api-e2e.mjs，84 项断言全绿。覆盖：三类型资金/台账/余额/退款守卫；
  手工 FX 四拦截 + 全生命周期（审核/反审核红字/删除）；对账单混合结算 FIFO 勾稽
  （预付 78 全归最早到期 CS1、现金真值 102 vs 资金 100 的 2 元抹零、对账单 paid 122/60）；
  自动 FX 来源挂接与禁单拆；0 额锚点不写资金；锚点反审核级联全恢复；
  GL 三事件入池 + FX 凭证借 2202/贷 1123 带供应商辅助、借贷各 30 平衡；分页过滤与 401。
- UI 验收：prd36-m2-ui-verify.js，4 组真实交互场景全绿（截图 evidence/prd36-m2-01~04）：
  FX 列表（手工/对账单来源、已审核/已作废标签）、新建抽屉（￥500 余额、8 行候选按到期日排序、
  勾选自动填额与合计联动）、付款三类型 radio（预付锁定供应商）、对账结算弹窗预付 50/现金 0/合计 0 勾稽；
  另含付款状态列与过滤的回归断言。
- 脚本：prd36-m2-{fixtures.sql,reset-v129.sql,api-e2e.mjs,ui-verify.js}
  （reset 只清 M2 单据与流水，M1 夹具不动，AP-M2-* 真值复位；可重复重跑）。

## M3：厂家费用单 JF + 兑现单 DX（V130，2026-09-17）

### 功能范围

- 厂家费用单 JF（`fin_factory_expense/_detail`，/finance/factory-expense 14 端点）：
  - 费用性质 ADV 代垫 / OTHER 其他；代垫行可关联客户费用单 FE 行。关联基数按评审定论：
    **一张客户费用单只能被一张 JF 关联（头回指 `fin_expense_bill.factory_expense_no`，整单占用），
    一张 JF 可对多张 FE（多行）**；候选接口带 `currentJfNo`，编辑本单时本单已占行仍可见可选。
  - 审核立费用账户（EXPENSE 余额 + EXPENSE_FORM 流水）并发 GL 事件；反审核守卫仅拦**已审核** DX
    （待审核 DX 是计划数，允许 JF 反审核修改），反审核清 FE 头回指，行上关联值保留兜底。
  - 红字单 red-create：只增不删，负金额冲减原单（含费用余额与 GL 红字事件）。
  - Excel/CSV 导入（ImportDialog 复用，10 列模板：供应商编码/费用类型/费用性质/垫付客户编码/
    关联客户费用单号/费用日期/金额/厂家协议号·票据号/贷方科目/备注），后端解析逐行校验、错误行回报；
    导出按过滤条件出表。
  - 「上线历史补录单」由参数 P0190（默认 N）控制；补录单审核只立费用账户流水、不发 GL 事件，
    避免与 1221 期初余额重复记账。
- 厂家费用兑现单 DX（`fin_factory_settle/_detail/_ap/_fund`，/finance/factory-settle 11 端点）三方式：
  - **CASH 现金结算**：资金多行（资金档案账户，如现金/银行卡），审核出资金台账 OUT，
    GL 现金流量固定 CF03。
  - **OFFSET 冲应付**：下半区取该供应商未结应付（到期日升序、空到期日末位，与 FX 同序），
    勾选自动填未付额、行级冲销额可改，审核写真值核销（fin_reconcile_record，FACTORY_SETTLE 来源）
    并联动 AP 未付余额；可关联多张应付单。
  - **OTHER 其他核销**：一单一个对方科目，四选——1123 预付账款（转预付，可填关联预付付款单号，
    审核增供应商预付余额）、1405 库存商品（厂家货补）、5601/5602 费用减免（末级科目下拉）、
    5711 营业外支出（坏账，备注必填原因）。
  - 抽屉上下两区勾稽：`balanced = JF 合计>0 且 |上区-下区|<0.005`，不平保存/保存并审核置灰，
    title 提示「上下两区合计不一致」；5711 未填原因拦截（中文错误）。
  - 扣款通知单：print-data 组装 + 前端打印样式单（CASH 含资金到账明细、OFFSET 含冲应付明细、
    签字栏），export-notice 导出。
- 总账 V130 四模板（反审核由事件池自动红字，不建反向模板）：
  FACTORY_EXPENSE（借 1221 供应商辅助 / 贷按明细行费用科目 EXPENSE_LINE 展开；代垫行取 FE 原路费用科目，
  其他行取费用类型档案 factory_gl_credit_subject_code，缺省 5401；红字同构负金额）、
  FACTORY_SETTLE_CASH（借资金 FUND_LINE 展开 CF03 / 贷 1221）、
  FACTORY_SETTLE_OFFSET（借 2202 / 贷 1221，均供应商辅助，无现金流量）、
  FACTORY_SETTLE_OTHER（借对方科目，1123 挂供应商辅助，其余科目未启用该维度自动不挂 / 贷 1221）。
  金额键约定：NONE 头行取事件顶层标量 `amount_tax_incl`；EXPAND 行 merge 行 vars、行金额键 `amount`。
  FinanceReportSupport 白名单登记四事件；BillNoGenerator 登记 JF/DX 单号。
- 前端：财务管理 > 厂家费用单（FactoryExpense.vue + FactoryExpenseDrawer.vue：
  FE 行选择器、红字标签/红字提示、查看态兑现记录含待审核计划数、红行 red-flag、批量审核左下角条、
  已审核行勾选禁用）、厂家费用兑现单（FactorySettle.vue + FactorySettleDrawer.vue：
  two-pane 勾选填额、contra-box 四科目、打印通知单）。
- 菜单权限：fin.factory_expense / fin.factory_settle 两个页面，功能点
  add/edit/delete/audit/unaudit/import/export/print（已登记 perm-inventory 共 25 个端点映射）；
  全部写操作落 sys_operation_log_runtime。

### E2E/UI 验收捕获并修复的真实缺陷（5 处）

1. **前端·导入按钮零响应**：FactoryExpense.vue 以 `<ImportDialog v-if="importVisible">` 挂载，
   却漏传 required prop `:visible`；组件根节点 `v-if="visible"` 取默认值 false，
   状态层已翻转、render 含 vnode，但 DOM 只剩 `<!--v-if-->` 注释。补传 `:visible="importVisible"`。
2. **前端·OTHER-FEE 费用科目下拉永远为空**：feeAccounts 在 leaf-options 结果上多筛了
   `a.status === '启用'`；而后端 /finance/gl/account/leaf-options 注释即「末级启用科目下拉」，
   SQL 已过滤 `is_leaf=TRUE AND status='启用'`，返回体**根本不含 status 字段**，
   过滤结果恒为空——UI 无法保存 5601/5602 费用减免类 DX（API 直调可过，DX-M3-0006 为证）。
   删除前端冗余过滤（其余 6 个 leaf-options 消费方也都不按 status 筛）。
3. **后端·红字 JF 费用余额冲减符号反向**：红字审核冲减已审核费用余额时误用减法方向，
   改为按 redAmount（负值）add，与「红字只增不删、负金额冲减」范式一致。
4. **后端·JF 改单/删单 FE 头回指兜底**：doCancel 反审核/删除时统一清
   `fin_expense_bill.factory_expense_no`（行上关联值保留），否则 FE 整单占用不释放、无法被新 JF 关联。
5. **GL 事件 vars 装配**：FACTORY_EXPENSE 头行金额必须放在顶层标量 `amount_tax_incl`
   （NONE 不 merge 行 vars），行科目经 EXPENSE_LINE 的行 vars.subject_code 展开；
   初版键位/层级不符模板约定，凭证取数为 0，已按 V95/V125/V129 约定修正。

### 测试基建调整

- prd36-m2-reset-v129.sql：外键孤儿清理扩展 FK% 全序列并豁免 FK-TEST 夹具，
  保证 M2/M3 reset 可在同一隔离库反复重跑。
- M3 夹具补资金档案基线（现金/银行卡账户），支撑 CASH 多行资金兑现。
- E2E 中资金行/冲销行返回 null 时的断言改为先判存在再取值（计划行与实存行区分）。

### 验证（隔离 8082 + verify-prd36-m1 库，UI 脚本 0 单据提交）

- API E2E：prd36-m3-api-e2e.mjs，**147 项断言全绿**（/tmp/m3-e2e-run5.log）。
  覆盖：JF 手工/FE 关联/导入/导出/红字/反审核守卫与 FE 回指释放；DX 三方式全生命周期、
  上下区不平拦截、5711 原因必填、1123 转预付、OFFSET 真值核销；四 GL 事件入池成券借贷平衡、
  辅助核算挂接、反审核红字；候选 FIFO、分页过滤、401。
- UI 验收：prd36-m3-ui-verify.js，**13 组场景全绿**（evidence/prd36-m3-01~13 共 16 张截图）：
  JF 列表红行/操作集/导入来源、JF 查看兑现记录（含待审核计划数）、红字单负金额、
  新建 FE 选择器全占用空态与 OTHER 隐藏列合计联动、JF0001 UI 反审核→currentJfNo 候选→
  finally 重新审核复原（脚本结束后 JF0001 必为 APPROVED）、导入弹窗 10 列模板（props 校验）、
  批量条、DX 六张三方式列表、CASH FIFO+勾选填满+50/30 不平置灰到 70/30 平衡启用、
  OFFSET 8 行应付候选+勾选填未付、OTHER 四科目+15 个 5601/5602 末级、
  三方式查看态（资金 select.value/冲销 input.value/1123 radio/关联单 YF-M3-1）、扣款通知单。
- CDP 经验：Vue3 给 v-model 赋值用 `el.value=v` + input/change 事件即可，勿用原生 setter
  （本场景偶发 Illegal invocation）；select/input 的值不在 innerText，断言表单值读 `.value`；
  `ev(\\`...\\`)` 内浏览器端正则写 `/60\\.00/` 一个反斜杠（模板字符串会消费一层）。
- 脚本：prd36-m3-{fixtures.sql,reset-v130.sql,api-e2e.mjs,ui-verify.js}，可重复重跑。

## M4：期初预付/补录、日结分列、业财对账、停用守卫（V131，2026-09-17）

### 数据结构（V131，唯一 DDL）

- `fin_ap_init` 加 `line_kind VARCHAR(24) NOT NULL DEFAULT 'AP_INIT'` + 索引：
  AP_INIT=初始化暂存行（历史行默认归属）、PREPAY_SUPPLEMENT=上线后预付补录行，两类同表隔离、互不可见。
- 复用 V128 预置列：fin_ap_init.prepay_amount/generated_prepay_flow_id、
  biz_close_ap_daily.prepay_account_balance/expense_account_balance。
- biz_init_post.init_type 用 `AP_PREPAY`（VARCHAR(10) 容得下 9 字符），补录**不写建账锁定标志**、可多批。

### 期初模块改造（fin_ap_init 一张表两流程）

- ApInitService：FIELDS 7 列加「期初预付金额」；parse 两列缺省按 0、≥0、两位小数、
  「应付与预付至少一项 >0」，老 6 列模板兼容；过账 seq 全行统一递增，ap>0 造 QCAP 应付单、
  prepay>0 调 SupplierAccountService.postPrepayOpening 生 QCYF-{批号}-{seq} 流水并回写 generated_prepay_flow_id；
  反建账第一轮逐行查往来核销 + checkPrepayOpeningReversable 双侧占用，任一占用整批拒；
  update/delete 拒绝补录行（中文提示去补录通道维护）；status 加 totalPrepayAmount/postTotalPrepayAmount（后者从已过账暂存行回取，biz_init_post 只有一个总额列）。
- 新增 ApPrepaySupplementService/Controller（/init/ap/prepay-supplement，11 端点，权限全复用 fin.init_ap.*）：
  独立 APP 批号、只生 QCYF 流水、不造单不动资金；过账守卫=当日未封账（BizDayCloseGuard）+
  总账期间 yyyyMM 非「已结账/已冻结」；反建账按批号整批（过账日封账/过账当月月结/流水被占用均拒）；
  status 输出批号历史（近 20）、各供应商 QCYF 累计、**负应付供应商黄名单**（防 2202 负余额与 1123 双计）。
- 模板：init-ap-template.xlsx 改 7 列 + 第二页 15 行说明（QCAP/QCYF、双侧占用、补录通道）；
  新增 init-ap-prepay-template.xlsx（4 列 + 13 行规则），zip surgery 保样式。
- 前端：InitAp.vue 加预付列/双合计/手工表单非必填改双列校验提示/「期初预付补录」入口；
  新组件 InitApPrepayPanel.vue（暂存面板复用 OpeningInitPanel + 状态守卫条 + 批号历史整批反建账 +
  QCYF 累计 + 负应付提示）；importPresets 同步 7 列映射并新增 initApPrepay。

### 日结分列（提示性，不进硬勾稽）

- BizCloseSnapshotService.rebuildAp：fin_supplier_account 三余额户并入定版（无未结 AP 也出行），
  回填 prepay_account_balance/expense_account_balance；
- BizDayCloseService.tieAp：追加 prepayAccountTotal/expenseAccountTotal/prepayReclassTotal/
  prepayReclassDiffs/prepayDoubleCount（同一供应商 2202 负应付与 1123 账户预付同时 >0 → 双计提示）；
  detail/listApDaily 查询补两列（RJ 单 ticket 同步带上）；
- 前端：DayClose.vue 台账应付加「预付(2202重分类)/预付余额(1123账户)/费用余额(1221)」三列、
  向导第 5 步加账户三合计与双计红字提示；DayClosePrint.vue 同步加列加表注。

### 业财对账扩五组 + 1123 一键引入

- GlReportService.reconcile：ar/ap/prepay/factoryExpense/cash 五组，matched 全量、
  **hardMatched 只看硬平衡三组**，advisory 组不翻总标志；
- reconcilePrepay：1123 借余 vs Σfin_supplier_account.prepay_balance（含 QCYF 补录），按供应商辅助出明细；
- reconcileFactoryExpense：GL 形成额=1221 供应商辅助期末借余+本年贷方兑现累计，
  业务形成额=EXPENSE 流水 FACTORY_EXP_FORM/RED/REVERSE 非 REVERSED 的 Σincrease；
  另出 glOutstanding/bizOutstanding/outstandingDiff/glCreditYtd/bizSettled 观察值；
- **真实缺陷修复**：初版复用 auxDetail 取 1221，其 `signed(d-c)≈0 就 continue` 会把已全额兑现（d=c）
  的供应商整户跳过——形成额与本年贷方全漏；新写 auxDcWithYearCredit（分录段 d/c 全量 + CASE 当年贷方 +
  UNION 启用期初，只过滤全零户）。
- GlInitService.importBusiness：ensureLeafSubject("1123") 后按 fin_supplier_account.prepay_balance>0.004
  逐户 upsertAuxBalance 到 1123 供应商辅助，result 出 prepayCount/prepayTotal，操作日志拼接。
- Reconcile.vue：五组 v-for、提示性徽章 + note、硬平衡总标志文案、厂家费用组增加
  期末余额/费用余额/余额差/本年贷方兑现辅助行，advisory 差异橙色不翻红。

### 档案与停用守卫

- base_supplier.ap_balance 旧列**停用不删**：supplierPage/supplierDetail 的 apBalance 改取
  fin_supplier_account（批量 IN 查询 map 覆盖），同时下发 prepayBalance/expenseBalance；
- SensitiveFieldRegistry：VIEW_AP_BALANCE 增绑 prepayBalance/prepay_balance/expenseBalance/expense_balance；
- BaseController.deleteSupplier/stopSupplier 统一过 assertSupplierBusinessFinished：
  三余额任一绝对值 >0.005、fin_ap 未结（ABS(unpaid)>0.005）、APPROVED 且 unsettled>0.005 的 JF、
  PENDING/APPROVED 的 DX/FX 即逐类拼中文 IllegalArgumentException；删除/停用同守。
- **UI 验收补漏（真实 BUG#4）**：供应商行内无独立「停用」按钮，唯一停用入口是编辑抽屉把状态下拉改
  「停用」后保存（/base/supplier/update），而该端点原本不跑守卫——有余额/未结单也能静默停用。
  现 updateSupplier 在 oldStatus≠STOPPED 且新状态=STOPPED 时同样过 assertSupplierBusinessFinished
  （在 updateById 之前抛错，零写入；抽屉 alert「保存失败：无法停用供应商…」）。

### 文档

- 新增 `docs/操作手册-供应商账户.md`（三余额/菜单地图、JF/DX/FX/预付全流程、税务调整分录、
  申报100实到98两方案示例、账财手工凭证边界、1221 勾稽公式、停用守卫与字段权限、FAQ）；
- 操作手册-业务期初初始化：表头版本、菜单表、第四章 7 列规则/QCYF、新增第六章预付补录（对比表/守卫/负应付）、
  反建账守卫与 FAQ 同步；操作手册-业务日结：应付三列分列与双计提示。

### 顺手修复（M4 回归暴露，主干遗留）

- FinanceController 收款核销/付款核销分页（/finance/ar-settlement/page、/finance/ap-settlement/page）
  预览单号用 `DATE_FORMAT(NOW(),...)`，H2 MODE=MySQL 不支持该函数，当当日无结算单走 0001 兜底分支时整页
  500「系统繁忙」；改为 Java 层 BASIC_ISO_DATE 拼日期前缀 + 占位符（main 上即存在，因付款核销属供应商
  账户近链路，本次顺带修复；CustomerPriceController 尚有 1 处同类遗留，未在失败端点内，另案处理）。

### 验证（2026-09-17 全绿）

- 后端 `mvn -o -q package -DskipTests` 通过；前端 `npm run build` 通过；隔离 8082 + verify-prd36-m1
  库（V131 已 migrate）。
- **API E2E**：prd36-m4-api-e2e.mjs 116 断言全绿，期间修掉 3 个真实 BUG（均在 V131 范围内）：
  ①ApInitService.insertLine 导入行占位符错位；②GlReportService 预付对账组漏标 advisory；
  ③QCAP 期初应付漏写 AP_OPENING 账户形成流水（新增 SupplierAccountService.postApOpening/
  deleteApOpening 并接入期初过账/反建账）。
- **浏览器真实交互 UI 验收**：prd36-m4-ui-verify.js 10 组场景全绿、0 单据提交（唯一转态=脚本
  API 侧日结 close 取数，finally 必 reopen，每轮失败也已复原），截图落 development/06-testing/
  evidence/prd36-m4-*.png：
  01 期初九列/双合计/AP 单号·SAF 回写；02 补录弹窗/批号历史/黄名单/累计/反建账内层弹窗；
  03 对账五组顺序与两枚 advisory 徽章；04 向导 AP 硬勾稽 190 与 1123/1221/2202 分列、NEG 双计；
  04b 已封账态；04c 定版台账三新列+NEG 30/70/0+纯预付户 billCount=0 仍出行；05 RJ 打印 10 列；
  06a 档案应付余额取账户值；06b 编辑抽屉改停用被五守卫 alert 拒绝（真实入口，见 BUG#4）；
  06c 行内删除被五守卫 toast 拒绝；06d m4field 无 VIEW_AP_BALANCE 三余额脱敏空白。
- **核心冒烟**：verify-prd36-smoke 副本库 + 8083，v1-core-smoke-test.js 全过（采购/销售闭环+红冲，
  清理 61 行 24 表），验毕删副本。**模块覆盖**：v1-module-coverage-test.js 58/63；余 5 个失败
  （/base/master/customer/page、/base/master/supplier/page、/sales/return/page、/sales/invoice/page、
  /sales/empty-adjust/page）经 git 核对在 main 上即无映射（NoResourceFoundException 基线漂移），
  非本次回归，未改基线脚本。
- 夹具注：隔离库 sys_menu_meta 历史 ENABLED 多为 FALSE（超管服务端短路不可见），普通角色用户的
  /system/menu/user-tree 只回 ENABLED=TRUE 菜单；E2E 脚本 8b 已对测试角色所授 NORMAL 菜单补
  UPDATE ENABLED=TRUE，UI 06d 才能按真实菜单码放行 /supplier。
