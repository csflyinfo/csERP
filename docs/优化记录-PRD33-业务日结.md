# 优化记录：业务日结（按日封单）（PRD-33，V120）

- 分支：`feat/finance-daily-close`（squash 合入 main 后即删）
- 迁移：`V120__biz_day_close.sql`（全局唯一，下一可用号 V121）
- 需求来源：《业务日结-设计方案.md》v1.3 终审定稿；财务定位为「为业务服务的模块」，不做专业财务的发票-结算-记账链路
- 本期范围：
  - 按日封单：已结日期的业务单据禁止审核/反审核/删除/收付款/核销/对账结算等一切写操作
  - 日结管理页（7 步向导 + 日结记录 + 定版台账 + 操作日志）、RJ 日结单打印
  - 动态定时任务管理模块（cron/启停/立即执行/执行日志，handler 代码白名单）
  - 工作台红色待办提醒；总账月结与业务日结联动（P0186 硬拦截）
  - 三张定版日余额表（客户应收 / 供应商应付 / 资金账户），反日结按权限控制、全程留痕
- 验收脚本：`development/06-testing/scripts/v1-biz-day-close-test.js`（独立 H2 库 + 8081 端口 + JDK21 真实后端，全断言通过；脚本头部含运行方法，**不进 CI**）

---

## 1. V120 数据对象

| 对象 | 用途 |
| --- | --- |
| `biz_day_close` | 日结主记录，每个已结日期一行（反结删行，痕迹只在日志表）。单号 `RJ-yyyyMMdd`，`close_date` 唯一；存 7 步检查结果 JSON（`check_result`）、DWS/四套勾稽/资金对账标志、6 个关键合计、跨阶挂账数、期初确认、资金备注 |
| `biz_day_close_log` | 只增日志：`CLOSE/REOPEN` × `SUCCESS/FAIL`，反日结原因、批量批次号 `batch_no`、`MANUAL/AUTO` 触发方式、失败明细，索引 `(close_date)` |
| `biz_close_ar_daily` | 应收定版：主键 `(close_date, customer_code)`，含税净额、已收/未收、负余额重分类的**预收**、逾期金额、单数 |
| `biz_close_ap_daily` | 应付定版：同构，负余额重分类为**预付** |
| `biz_close_fund_daily` | 资金定版：主键 `(close_date, fund_account_code)`，编码为不可变维度、名称为**快照**；open/in/out/close 滚算，`cash_count` 现金实盘（可空备注） |
| `biz_simple_bill.bill_date` | 遗留费用单补业务日期列（历史行回填创建日），审核时回填当天 |
| `sys_scheduled_task` | 动态任务定义（task_code/handler_bean/cron/enabled/最近执行结果） |
| `sys_scheduled_task_log` | 任务执行日志（自动/手动都记，保留 90 天），索引 `(task_code, start_time)` |
| 系统参数 P0184~P0188 | 见 §6 参数表 |

种子：`MERGE ... KEY(task_code)` 幂等注册任务 `BIZ_DAY_CLOSE`（handler=`bizDayCloseJob`，cron `0 30 2 * * ?` 每天 02:30，enabled=Y）。

全部 SQL H2(MODE=MySQL)/MySQL 双跑：单引号字符串、`IF NOT EXISTS`、无 MySQL 专有函数。

---

## 2. v1.3 终审三项裁决（最高优先级）

1. **记账日期不回填**：盘点单 `count_date`、收款单 `receipt_date`、付款单 `payment_date`、费用单 `expense_date` 审核时一律**不覆盖**；用户填的单据日期就是入账归属日，守卫按单据日期判定。
   - 日期为空时沿用存量行为（系统现有逻辑本就是空→审核当天），不新增「必填」拦截。
   - 其他业务单据（出入库/订单/收发货/调拨等）审核时把业务日期列**持久化回填为 CURRENT_DATE**，使封单判定有稳定列可依。
2. **资金账户不做硬阻断**：向导第 6 步（资金台账末笔余额 vs `base_fund_account.balance` 档案差异、现金实盘、自由文本说明）**仅备注**：差异列入 `check_result`，实盘数落 `biz_close_fund_daily.cash_count`，说明落 `fund_remark`；`fund_balanced=N` 不阻止结账。第 5 步四套滚存勾稽（库存/应收/应付/资金流水自身滚平）仍为**硬项**，容差 0.01。
3. **反日结按角色权限控制**：不附加「仅最后一天」「仅本人」等业务规则之外的限制，只认 RBAC 功能点 `finance.day_close.unaudit`（单日反结、批量反结同一权限）。

其余已定口径：P0185 默认 AUTO；未审核单据永不封单（封的是动作不是单据）；采购发票链不纳入；TMS 纯物流（发车/回单等）不封，只封影响库存/往来/资金的动作；向导第 2 步挂账三类 = 已入库未审核采购收货单、已出库未审核采购退货单、已出库未签收销售发货单（另提示已发车未回单调度、未交账门店结算）；02:30 为每日入账截止点；首次日结建立期初基线；单号 `RJ-yyyyMMdd`；过 P0188 时刻工作台红色提醒。

---

## 3. 封单守卫

`com.erp.finance.dayclose.BizDayCloseGuard`（Spring Bean，构造注入）：

- `assertWritable(LocalDate, bizType, billNo)`：日期 ≤ 封单日即抛 `IllegalArgumentException`，文案统一：
  「单据日期 %s 已日结封单，不允许该操作。如需调整，请联系财务反日结后处理。」
- `assertBillWritable(table, dateCol, idCol, id, bizType)`：反审核/删除等「无日期入参」动作先查库取单据日期再判；
- `assertRangeOpen(from, to, bizType)`：DWS 重算/快照重建区间与已结日期相交即拒；
- `isClosed / lastClosedDate / enabled / evict`：总开关 P0184=N 时全部放行（紧急排障，切换记操作日志），已生成的日结记录不受影响。

落点共 **75 处守卫调用，覆盖 25 组业务落点**：

| 模块 | 落点 |
| --- | --- |
| 财务 `FinanceController` | 费用单审核/反审；收款单审核/反审/取消审核/核销、付款单同构（含批量核销按单逐笔校验）；往来快速核销（按当天）；客户/供应商对账单生成（当天）、反审核（按对账单日期查库）、对账结算（按结算日期） |
| 库存 | 报损报溢单（创建按当天、反审/删除查 `bill_date`）；其他入库单/其他出库单（含遗留调拨、成本调整入口）；盘点单审核/反审按**盘点日** `count_date` |
| 采购 | 采购订单审核/反审/删除（`bill_date`）；采购入库单审核（含 WMS 回写 `auditFromWms`）；采购收货单审核/反审；采购退货申请、退货出库、退货单审核/反审 |
| 销售 | 快速开单/销售订单（创建按当天、反审/删除/批量按 `bill_date`）；拒收入库单；销售出库单（含 WMS 回写）；销售发货单审核/反审（按 `receipt_date`）、**签收/批量签收按 `sign_time`**；销售退货申请、退货入库 |
| 调拨 | 调拨申请、调拨出库、调拨入库（出/入库两条链分别按各自单据日期守卫） |
| TMS | 运输结算单审核（守卫 + `settle_date` 回填在写库前）、争议（按存量 `settle_date`，且在 404 检查之前）；客户拒收接收按 `returned_at`（放在吞异常 catch 之外）；发运签收/批量签收按 `sign_time`（批量 IN 查日期，冲突合并为一句中文提示；整体受 `enabled()` 门控）；司机交账按当天/交账日期 |
| WMS | `WmsInternalService` 盘盈桥接 |
| 报表 | `ReportDwsSnapshotTask`：采购/销售 DWS 重算、库存快照重建区间守卫；自动快照任务跳过已结日期、起点从封单日次日开始（已结日期的快照不允许被覆盖） |

刻意不守卫：TMS 门店结算提交（系统当前**不存在**反提交/撤回端点，无写回路径；后续若增加撤回接口必须补守卫——此为已知遗留行）；采购发票（不纳入封单链）。

---

## 4. 结账事务与 7 步向导

`BizDayCloseService.wizard(date)` 返回 step1~step7；`close()` 硬门顺序：连续性/期初 → 手工知晓勾选 → DWS → 四套勾稽，任一不过整事务回滚。

| 步 | 内容 | 性质 |
| --- | --- | --- |
| 1 | 连续性（必须 = 封单日+1，禁未来日期）；首次日结的期初基线：未审核期初入库单（`inv_other_inbound.inbound_type='0'`）**硬阻断**，往来/资金期初由结账人勾选确认并留痕（`opening_confirmed`） | 🔴 硬 |
| 2 | 跨阶挂账单三类清单 + 已发车未回单/未交账门店结算提示；结账需勾选「已知晓」（自动任务视同知晓），结果入 `check_result` | 🟡 提示+勾选 |
| 3 | 负库存、成本单价 ≤0、往来单位匹配不到档案（日余额合并 code=`UNKNOWN`）清单；勾选继续 | 🟡 提示+勾选 |
| 4 | 重算目标日采购/销售/库存流水 DWS 并各自 reconcile，不平给差异明细 | 🔴 硬（`dws_balanced`） |
| 5 | 四套滚存勾稽（容差 0.01）：库存（数量/金额期望 vs 实际）、应收（prior+added−settled=current）、应付同构、资金流水自身滚平 | 🔴 硬（`tie_balanced`） |
| 6 | 台账末笔余额 vs 账户档案余额、现金账户实盘录入（名单 P0187）、资金情况说明 | ⚪ 仅备注（v1.3，`fund_balanced` 不阻断） |
| 7 | 待生成/生成失败 GL 事件提示（与 GL 向导口径一致，不阻断） | 🟡 提示 |

- 定版生成（`BizCloseSnapshotService`）：三张日余额表「按日 DELETE 再插入」，幂等可重跑，必须在结账事务内；应收/应付按 `fin_ar/fin_ap` 当前余额聚合（封单链保证结账时刻即该日时点），负余额绝对值重分类预收/预付；资金按 `fin_fund_ledger.occurred_at` 日期归属，以账户档案余额倒推历史期初后逐笔滚算 open/in/out/close。
- 账户**名称快照**落定版表，事后改账户名不改历史；编码是不可变维度。
- 幂等：同一日期重复结账直接返回 `{idempotent:true, closeNo}`，不重复落账。
- 反日结：单日仅反当前封单日（MAX），原因必填 ≥2 字；落入已结账 GL 期间硬拒绝；批量反结到指定日期生成同一 `batch_no`，逐行 CLOSE/REOPEN 日志可追溯。
- 自动补结：从封单日+1 循环到昨天逐日 `performClose`；某日失败 → 记 FAIL 日志并中止，等人工处理。

---

## 5. 动态定时任务模块

- `sys_scheduled_task` 库行管 cron/启停；任务体由代码在 `@PostConstruct` 向 `DynamicTaskRegistrar` 注册（`registerHandler(beanName, Runnable)`），调度器只从白名单 Map 取任务体——**库中不存任何脚本/类名，不存在任意代码执行面**；`handlerExists` 校验库行引用的 Bean。
- `DynamicTaskRegistrar` 基于 Spring `ThreadPoolTaskScheduler`，cron 变更/启停即时 `reschedule`，下次触发时间返回前端预览。
- 接口（`/system/schedule-task/*`，全部 POST）：`page` / `update`（改 cron，校验 6 段 cron 并返回未来 5 次触发时间）/ `toggle` / `run-once`（立即执行，记 MANUAL 日志）/ `next5`（预览）/ `log-page`（日志分页）。
- 本期只注册 `BIZ_DAY_CLOSE` 一个任务；框架为后续任务复用，新增任务 = 代码注册 handler + 插一行定义。
- 手动方式（P0185=MANUAL）任务到点只记「方式为手动，跳过」日志。

---

## 6. 参数与联动

| 参数 | 键 | 默认 | 说明 |
| --- | --- | --- | --- |
| P0184 | `BIZ_DAY_CLOSE_ENABLED` | Y | 总开关；N 时守卫全放行（排障用），不影响已生成记录 |
| P0185 | `BIZ_DAY_CLOSE_MODE` | AUTO | AUTO/MANUAL，手动只能页面结账 |
| P0186 | `BIZ_GL_CLOSE_REQUIRE_DAY_CLOSE` | Y | 总账月结向导发现本期（月初至结账当天；历史期间至月末）有未日结日期：Y=禁止月结并列日期清单，N=仅警告 |
| P0187 | `BIZ_CASH_ACCOUNT_NAMES` | 空 | 现金类账户名单（逗号分隔），第 6 步实盘录入位 |
| P0188 | `BIZ_DAY_CLOSE_LATE_HOUR` | 10（6~23） | 过此时刻昨日未结（或最近自动日结 FAIL），工作台红色待办 |

月结检查 `GlPeriodService.dayCloseCheck`：总开关停用或库中从无日结记录时跳过，不打扰未启用客户。

---

## 7. 前端

| 文件 | 内容 |
| --- | --- |
| `views/finance/DayClose.vue` | 日结管理四页签：① 日结向导（7 步卡片、硬项红/提示黄/跳过灰，KPI 合计，知晓勾选，结账）；② 日结记录（区间分页、打印、**仅首行可单日反结**、勾选后左下角批量反结，原因弹窗 ≥2 字）；③ 定版台账（应收/应付/资金三子页签，懒加载，关键字+区间）；④ 操作日志（CLOSE/REOPEN、批次、触发方式） |
| `views/finance/DayClosePrint.vue` | 独立顶层路由 `/finance/day-close-ticket`（layout 外），取 RJ 单数据自动 `window.print`，含关键合计/三张定版表/资金备注/签字栏，打印态隐藏工具条 |
| `views/system/ScheduleTask.vue` | 任务/日志两页签：改 cron + 未来 5 次预览、启停开关、立即执行、最近执行结果；日志保留 90 天提示 |
| `api/day-close.js`、`api/schedule-task.js` | 全部走 `apiClient` POST |
| 路由/菜单 | router 3 处（含独立打印路由）、`menu-map.js`、`fallback-menus.js` 各登记「日结管理」「定时任务」 |
| 工作台 | `useDashboard.loadDayCloseReminder` 静默拉 `/finance/day-close/reminder`（403/异常不显示，不影响首页）；`DashboardPage.vue` 顶部红色提醒卡（逾期未结 + 自动失败），点击进日结管理 |

手写 CSS 类（无 Element Plus）；查询只在点「查询」时触发；批量按钮左下角、勾选满足条件才出现；无 `console.log`。

功能点（`@RequirePerm`，自动进权限注册表与 `docs/perm-inventory.md`）：

- `finance.day_close.view`（向导/记录/台账/RJ/提醒）、`.audit`（执行日结）、`.unaudit`（反日结/批量反日结）
- `system.schedule_task.view`、`.edit`（改计划/启停）、`.execute`（立即执行）

操作日志：日结/反日结/批量反结/任务执行均写 `sys_operation_log_runtime`（任务同时写专用日志表）。

---

## 8. 验证证据

- `/c/Soft/apache-maven-3.9.16/bin/mvn -o -f backend/pom.xml compile`：通过（全部 75 处守卫落点、TMS 三件套参与编译）。
- `npm --prefix frontend run build`：通过（DayClose chunk 约 29.6 kB）。
- `node development/06-testing/scripts/v1-biz-day-close-test.js`：**全部断言通过**。运行方式（隔离库，不动用户手工库 `data/erp-v1.mv.db`）：
  1. `backend/` 目录下用 JDK21（`C:\Users\Administrator\jdk21`，PATH 上的 java 是 JDK17 跑不了）启动 jar：8081 端口 + 独立 H2 文件 + `--spring.autoconfigure.exclude` 排掉 Redis；
  2. 脚本 header 有完整命令；断言覆盖：菜单/6 个功能点注册、首次期初阻断与删除后放行、其他入库审核回填 `bill_date`、封单后反审核被中文硬拦、幂等重结、跳日拒绝、RJ 单字段/三定版表/备注/平衡标志、反日结原因校验与留痕、批量批次号、提醒 payload、任务分页/handler 白名单/cron 改动（改为 02:50 并校验未来 5 次均为 02:50）/非法 cron 拒绝/启停/立即执行/MANUAL 日志。脚本在隔离冒烟库上执行、用后即弃，V120 种子 cron 仍为 `0 30 2 * * ?`（02:30），用户库不受影响。
- `docs/perm-inventory.md` 随本次端点增加自动再生成（809→826 端点，630→647 已纳管），一并提交。

### 仅人工验收项（脚本尾部会打印清单）

挂账单三类链路、采购发票不封单、定版金额的红字/负数/核销符号、P0186 月结硬拦实物演示、自动补结跨 2 天首日失败即停、无权限 403 与 P0184=N 守卫放行、直接改库存后第 5 步出差异、改账户名后历史名称快照不变、四类单据不回填记账日期与 02:30 截止点。
