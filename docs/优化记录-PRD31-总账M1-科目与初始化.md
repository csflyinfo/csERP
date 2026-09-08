# 优化记录 — PRD-31 总账模块 M1：会计科目与初始化

- **分支**：`feat/finance-gl-account-init`（基于 main，隔离 worktree `E:\work\erp-wms-tms-gl`）
- **迁移**：V92（科目/期间/期初/项目档案）、V93（凭证主表/分录/现金流量项目，M2 使用，随 M1 建表避免跨期迁移并行撞号）
- **日期**：2026-09-07
- **冒烟脚本**：`development/06-testing/scripts/gl-m1-account-init.js`（空库全生命周期，已通过 ✅）

## 交付内容

### 后端（com.erp.finance.gl）

| 文件 | 说明 |
| --- | --- |
| `GlConst.java` | 系统参数键（fin.gl.*）、7 维辅助核算常量与中文标签、凭证/事件/期间/科目状态常量；科目类别默认方向、编码首位判类 |
| `GlAccountService.java` | 科目树/平铺列表、新增（顶级 4 位编码、子级自动编码=父+两位序号、最多 4 级、类别方向继承）、修改（预置科目编码/类别/方向不可动）、停用启用（级次校验+凭证引用校验，fin_voucher_entry 存在才查）、末级下拉 |
| `GlInitService.java` | 期间生成（12 期/年）、期初主科目行与 7 维辅助行 upsert、主辅互斥校验、试算平衡、一键引入业务期初（fin_ar→1122 客户、fin_ap→2202 供应商、inv_stock_balance→1405 商品数量金额）、启用总账（平衡门控+期间状态推进+参数落库+期初锁定） |
| `GlAuxProjectService.java` | 项目核算档案 CRUD + 下拉 |
| `GlAccountController.java` | `/finance/gl/account`：tree/list/create/update/toggle-status/leaf-options |
| `GlInitController.java` | `/finance/gl/init`：status/period-list/period-generate/balance-list/aux-balance-list/balance-save/trial-balance/business-import/enable |
| `GlAuxProjectController.java` | `/finance/gl/aux-project`：list/create/update/options |

### 前端（src/views/gl/，原生 HTML + app.css，无 UI 库）

| 页面 | 路由 | 说明 |
| --- | --- | --- |
| `SubjectMgmt.vue` | /gl-account | 科目平铺表（级次缩进、类别/方向/辅助维度/数量现金标志/状态）、新增一级/下级弹窗（辅助核算 7 维勾选）、编辑、停用启用 |
| `InitBalance.vue` | /gl-init-balance | 未启用：期初借/贷/数量/年累借/年累贷内联编辑、辅助明细弹窗（按科目已配维度逐户录入）、试算平衡表、一键引入业务期初、启用期间选择+启用门控；已启用：只读+12 期间状态表 |
| `AuxProject.vue` | /gl-aux-project | 项目档案列表/检索/新增编辑 |

菜单：`SystemController.userMenuTree()` 新增「总账管理」组（glAccount/glInitBalance/glAuxProject），`fallback-menus.js` 同步；`router/index.js` 三条懒加载路由。

### 数据迁移

- **V92**：`fin_account`（67 个预置科目，小企业会计准则 2013，4-2-2-2 编码；含 4001 生产成本、2221 全套明细且方向一律贷、1602/1702 贷）、`fin_accounting_period`、`fin_init_balance`（科目+7 维辅助唯一键）、`fin_aux_project`。
- **V93**：`fin_voucher`、`fin_voucher_entry`、`fin_cash_flow_item`（20 个现金流量项目种子）。M2 直接使用，提前建表避免迁移号并行冲突。

## 关键规则落地

1. **科目编码 4-2-2-2**：子级编码=父编码+两位序号（MAX+1，99 上限报错）；类别由首位决定（1 资产/2 负债/3 权益/4 成本/5 损益），方向按准则自动判定（损益 5001-5399 贷、5400 起借）；子级继承父级类别与方向。
2. **期初录入**：仅末级启用科目可录；借贷不能同时有余额；主科目行与辅助明细行互斥（后录者报错提示先清空对方）；辅助维度必须是科目已配置维度；全零行自动删除。
3. **启用门控**：试算不平衡拒绝启用（返回借贷合计与差额）；启用期间之前的期间标记「已结账」（期初已含年累发生）、启用月「进行中」、之后「未开始」；启用后 `fin.gl.initialized=Y`，期初锁定只能通过凭证调整。
4. **业务期初引入**：应收按客户汇总 `fin_ar.unreceived_amount`、应付按供应商汇总 `fin_ap.unpaid_amount`、库存按商品汇总 `inv_stock_balance`（数量+成本金额）；档案按名称反查编码、查不到用名称兜底；同辅助键重复引入覆盖不翻倍。资金账户期初待 M4 档案映射列（`base_fund_account.gl_account_code`）上线后引入。
5. **无外键、无 DROP、IF NOT EXISTS 幂等**，H2/MySQL 双兼容；SQL 别名保留下划线由 `TmsUtil.queryCamel` 统一转驼峰（驼峰别名会被 camelize 整体小写，本模块已全部规避）。

## 冒烟覆盖（gl-m1-account-init.js）

菜单组与三个菜单 → 科目种子断言（67+、1122 客户辅助、2221 明细全贷、4001 成本借、3103 本年利润、1002 非末级）→ 自动编码 180101/类别继承/父转非末级 → 重复编码/首位非法/非末级期初拦截 → 有启用子级禁停父级、停用科目不出现在下拉 → 期初主行+辅助行+主辅互斥+越权维度拦截 → 试算 15000/15000 平衡 → 空库业务引入全 0 → 项目档案 CRUD → 启用期间格式校验 → 启用后 12 期间状态正确（202608 已结账/202609 进行中/202610 未开始）→ 启用后期初锁定、重复启用拦截。

## 遗留与后续

- 资金账户期初引入依赖 M4 的 `base_fund_account.gl_account_code` 档案映射列。
- V93 凭证表已建，M2 实现凭证 CRUD/审核/过账/红冲与账簿实时聚合。
