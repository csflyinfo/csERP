# 优化记录 — PRD-31 总账模块 M4：业务钩子埋点、反审核联动与凭证导出

- **分支**：`feat/finance-gl-autovoucher-hooks`（基于 M3 分支，隔离 worktree `E:\work\erp-wms-tms-gl`）
- **迁移**：`V95__fin_gl_hooks_export.sql`（3 张档案表加 GL 科目映射列、fin_voucher_export_log 导出日志表、销售出库/销售退货成本模板行科目表达式改 @COST/@INCOME）
- **日期**：2026-09-08
- **冒烟脚本**：`development/06-testing/scripts/gl-m4-hooks-export.js`（空库全流程，已通过 ✅）

## 交付内容

### 后端

| 文件 | 说明 |
| --- | --- |
| `V95__fin_gl_hooks_export.sql` | `base_fund_account` 加 `gl_account_code`（资金账户→资金类科目）；`base_expense_type` 加 `gl_expense_account_code`（费用类型→费用科目）；`base_category` 加 `gl_income_account_code`/`gl_cost_account_code`（商品分类→收入/成本科目）；新建 `fin_voucher_export_log`（导出日志）；TPL_SALE_OUT_L1/TPL_SALE_RC_L2 科目表达式改 `@COST`、TPL_SALE_SIGN_L2/TPL_SALE_RET_L2 改 `@INCOME`（成本/收入科目按商品分类映射解析，未配回落 5401/500101 并警告）。全部 ALTER/INSERT 幂等，无外键、无 DROP |
| `GlHookService.java` | 中央钩子服务（13 类事件 payload 组装）：采购收货 PUR_IN、采购退货 PUR_RETURN（红字同构，数量金额取负）、销售出库 SALE_OUT（成本取 inv_stock_ledger 出库流水汇总，sales_outbound 成本列保持 0）、销售签收 SALE_SIGN（\|签收额\|<0.005 跳过）、销售退货 SALE_RETURN（价内税倒算；行数量取 `COALESCE(NULLIF(signed_qty,0), qty)`，V60 司机签收列默认 0 不能直接 COALESCE）、销售退货成本 SALE_RETURN_COST（退货入库明细表无成本列，同 SALE_OUT 取 inv_stock_ledger 入库流水汇总）、收款 RECEIPT/付款 PAYMENT（仅 business_source 为空或 BACKOFFICE 才发事件，费用联动自动单 EXPENSE 不发）、费用 EXPENSE/OTHER_INCOME（费用类型按名解析 gl_expense_account_code 注入行 subject_code；头变量 expense_deductible 门控进项税行，不可抵扣时税额资本化进费用行保证平衡）、盘点 STOCK_CHECK（无盈亏行跳过，loss_lines/profit_lines）、其他出库 OTHER_OUT/其他入库 OTHER_IN（类型名取数据字典；inbound_type='0' 期初库存不发事件）、报损 DAMAGE（成本≥参数 `fin.gl.damage_large_threshold` 默认 2000 标 large_loss）。资金账户按编码或名称解析，注入 fund_account_code/fund_account_name/fund_subject_code（缺失也放空串，模板条件变量必须存在）；往来单位按 CUSTOMER/SUPPLIER 挂客户/供应商辅助；部门/经手人按名反查编码。所有钩子 `safe()` 包裹，Throwable 只记日志绝不影响业务 |
| `GlHookEmitter.java` | M3 已建，M4 起被 13 个业务点调用；`emit`/`emitReverse` 语义不变 |
| `GlBizEventListener.java` | M4 重写为**反审核联动状态机**（见下「反审核联动」） |
| `GlEventService.java` | 生成凭证成功后，若为反向事件则把对应正向事件置「已冲销」（同事务，正向凭证保留、红蓝成对留痕）；processOne 终态（已生成/已冲回/已冲销/已忽略）直接短路返回 |
| `GlConst.java` | 新增事件状态 `已冲销`（E_WRITTEN_OFF）：红字凭证已生成冲销，区别于「已冲回」（无凭证/草稿被删，业务不复存在） |
| `GlExportService.java` | 凭证 CSV 导出：按期间+凭证字取**已过账**凭证（草稿/审核态不导出），一分录一行摊平，UTF-8 BOM；金蝶 KIS 表头「凭证日期,凭证字,凭证号,附件数,摘要,科目代码,科目名称,借方金额,贷方金额,核算项目」，用友 T+/U8 表头「凭证日期,凭证号,附单据数,摘要,科目编码,辅助项,借方金额,贷方金额」；写 fin_voucher_export_log 并给凭证打 export_flag；业务错误返回 JSON body（流式端点无全局异常处理），前端按 blob.type 识别 |
| `GlExportController.java` | `/finance/gl/export`：csv（流式）、logs |
| `GlArchiveMappingService.java` / `GlArchiveMappingController.java` | 档案科目映射集中页：资金账户（仅可映射 is_cash 末级科目）、费用类型（任意末级费用科目）、商品分类（收入+成本两科目）；`/finance/gl/archive-mapping`：list（三组档案+映射+末级科目下拉一次取齐）、save（末级+启用校验，留空=清除映射） |
| 业务埋点（13 处） | PurchaseReceiptController（收货审核/反审核）、PurchaseReturnController（退货单审核/反审核）、SalesOutboundController（出库审核/反审核）、SalesReceiptController（签收/取消签收/反审核）、SalesReturnController（退货入库审核 + 退货单审核/反审核）、FinanceController（收款/付款审核+取消审核、费用审核，含批量审核端点）、InventoryCountController（盘点审核/反审核）、OtherOutboundController、OtherInboundController（审核/反审核）、DamageController（报损审核/反审核） |

### 前端（src/views/gl/，原生 HTML + scoped CSS，无 UI 库）

| 页面 | 路由 | 说明 |
| --- | --- | --- |
| `ArchiveMapping.vue` | /gl/archive-mapping | 三页签：资金账户→资金科目（下拉只列 is_cash 末级科目）、费用类型→费用科目、商品分类→收入/成本科目；未配置红色标记，行内保存 |
| `VoucherList.vue`（改） | /gl/voucher | 增「导出 CSV」（金蝶 KIS / 用友 T+ 格式、期间区间、凭证字，默认取列表筛选期间）与「导出日志」（时间/格式/期间/字/文件名/凭证数/分录数/操作人）；blob 响应识别 JSON 错误体并提示；导出文件名 `凭证导出-{系统名}-{起}-{止}.csv` |

菜单：SystemController 总账管理组与 fallback-menus.js 增 glArchiveMapping；router 增懒加载路由（meta.module 一致）。

## 反审核联动状态机（M4 核心）

事件状态：`待生成 → 已生成（挂凭证）/ 已忽略 / 生成失败`；终态 `已冲回`（业务被反审核冲掉）、`已冲销`（正向凭证已被红字凭证冲销）。

反审核发反向事件（payload 与正向完全一致，渲染时全额取负 + 「红冲 」前缀）落池时：

1. **找不到正向事件**（审核时总账未启用）→ 反向事件置「已忽略」（无对象可冲）；
2. **正向事件无凭证，或凭证还是草稿/作废** → 删除草稿凭证（头+分录），正反事件都置「已冲回」；
3. **正向凭证已审核/已过账** → 凭证不可删，反向事件保留「待生成」并提示「正向凭证已「X」，请批量生成红字冲销凭证」；会计生成红字凭证后，正向事件在同事务内置「已冲销」（红冲凭证与原凭证都留账）。

**重新审核**：四元组唯一索引不允许重复插行 → 命中终态行时复用重开（正向：已冲回/已冲销可重开；反向：已冲回/已生成可重开），payload/期间/金额刷新、凭证链接清空；正向重开时，上一周期挂起（待生成/生成失败）尚未生成红字凭证的反向事件自动作废置「已冲回」，防止事后误生红字。

## 冒烟覆盖（gl-m4-hooks-export.js）

菜单含档案科目映射 → 基础档案（仓/供/客/两商品/资金账户挂 1001/费用类型挂 560201/部门）→ 档案映射 list/save（资金行带出 glAccountCode、费用行 glExpenseAccountCode、分类可存收入+成本科目）→ 启用总账（1001/3001 各 10 万，启用期 202609）→ 采购全链审核落 PUR_IN（含税/税/不含税勾稽、供应商编码、行商品数量）→ **反审核无凭证双向已冲回、重审复用事件行（始终恰好 2 行）** → 销售出库 SALE_OUT（成本取流水>0）+ 签收 SALE_SIGN（客户编码、行 60 件）→ 三事件生成草稿 → 收款 RECEIPT（fund_subject_code=1001）生成→审核→过账（借 1001 500 CF01 / 贷 1122·1221 500）→ **CSV 导出金蝶/用友两格式表头结构、含凭证号、导出日志 2 条、export_flag 回写** → **反审核已过账：反向事件留待红字（warn 含「红字」），生成红字凭证 isRed、1001 借 −500，正向事件置已冲销；重审复用行** → 费用场景 A（有资金账户：EXPENSE 借 560201 挂部门/贷 1001 CF07；自动 FK 单 business_source=EXPENSE 不发 PAYMENT 事件）→ 费用场景 B（无账户：贷 2202 挂供应商、无 1001；手工付款 PAYMENT 再成一张借 2202/贷 1001）→ OTHER_OUT 内部领用→560299、活动消耗→560105 → **反审核草稿凭证：凭证被删（detail 报不存在）、双向已冲回、重审可再生成** → OTHER_IN 赠品→5301/借 1405；期初库存（type=0）不发事件 → STOCK_CHECK 盘亏 1 行 loss_lines → DAMAGE 大额报损（G2 单价 1000 损 3 件，cost≥2000，large_loss=true）→ 采购退货 PUR_RETURN（payload 为负、行数量 −5、凭证红字冲 2202、借贷平衡）→ 销售退货 SALE_RETURN_COST（成本>0）+ SALE_RETURN（payload 为负、行 −5、客户编码、借贷平衡）→ 收尾一键生成全部待处理事件 fail=0。

## 过程中修复的缺陷

- **GlArchiveMappingService 查询别名大小写陷阱**：SQL 中写驼峰别名（`gl_account_code glAccountCode`、`is_cash isCash`）经 H2 折叠为小写后被 `TmsUtil.camelizeKey`（整体 toLowerCase 再按下划线转驼峰）转成 `glaccountcode`/`iscash`，前端与 is_cash 校验全部取不到值。改为直接选下划线原列名（费用类型列 `gl_expense_account_code AS gl_account_code` 统一输出 glAccountCode），由 camelize 正常转换。
- **费用单方向固定为 IN 的 BUG**：FinanceController 费用单创建/更新未取表单 direction（原逻辑按明细正数金额汇总推断方向，支出单也被当成收入），改为直接读表单 direction（IN/OUT）；方向决定事件码（OUT→EXPENSE、IN→OTHER_INCOME）与自动生单类型。
- **支出类费用单自动生单类型错误**：费用单审核联动自动生成的往来单据，支出（OUT）应生成付款单 FK（fin_payment_bill，business_source='EXPENSE'），原逻辑统一生成收款单 SK；已按方向分流。
- **凭证导出分录缺科目名**：fin_voucher_entry 只存科目编码无 account_name 列，导出查询直取报 `Column "ACCOUNT_NAME" not found`；改为 `LEFT JOIN fin_account a ON a.account_code = e.account_code` 取科目名（H2 MODE=MySQL 下 JOIN 同名列必须表别名限定）。
- **费用进项税模板条件变量缺失**：TPL_EXPENSE_L2 条件 `expense_deductible == true` 在费用 payload 中不存在，生成直接报「表达式引用了不存在的变量」。修复：钩子按「一般纳税人 && 税额>0」计算头变量 expense_deductible（模板条件只对头变量求值，必须放 payload 头）；不可抵扣时税额资本化进费用行金额（行 amount=不含税+税），保证借贷平衡。
- **销售退货成本事件静默丢失**：SALE_RETURN_COST 钩子原从 sales_return_inbound_detail 取成本，但该表（V28）无 cost_price/cost_amount 列，查询抛异常被 safe() 吞掉、事件永不落池。改为与 SALE_OUT 同源：inv_stock_ledger 按 source_bill=退货入库单号、direction='IN' 汇总（入库审核时 purchaseInbound 按移动加权平均写入）。
- **收/付款往来科目分流条件过窄**：TPL_RECEIPT_L2/L3、TPL_PAYMENT_L1/L2 原仅按 ar_bill_no/ap_bill_no（核销记录）分流应收/应付 vs 其他应收/应付；后台手工未核销的客商往来单（如付费用款）被错误计入 1221/2241 其他往来。条件改为「核销单号非空 **或** 对手方为客户/供应商」走 1122/2202，与费用模板 L4/L5 的分流口径一致（V94 种子模板条件同步修正）。
- **销售退货行数量取 0**：明细查询 `COALESCE(signed_qty, qty)` 中 signed_qty 是 V60 新增的司机签收列（DEFAULT 0，后台仓库退货流程不回写），COALESCE 只判 NULL 不判 0，取到 0 导致退货行金额数量全 0。改为 `COALESCE(NULLIF(signed_qty, 0), qty)`。
- **冒烟脚本盘点/采购退货断言修正**：盘点 /create 按批次生成多行明细、初始 real_qty 全 0，只更新第一行会把未动批次算成全盘亏——脚本改为每行都回填实盘数（首行盘亏 1，其余账实相符）；采购退货凭证 2202 为「贷方红字」（红字同构模板行方向不变、金额为负），断言由 debitAmount<0 修正为 creditAmount<0。

## 遗留与后续

- M5：期末处理（fin_report_item 报表项目种子、损益结转 JZ{period} 幂等转 3103、自动转账 ZZ、结账四检查、反结账、年结 12 月冻结、PeriodClose.vue）。
- 红冲凭证目前只生成草稿，审核/过账仍由会计在凭证列表完成（与 M2 状态机一致）；M5 结账检查可考虑提示未过账红冲凭证。
