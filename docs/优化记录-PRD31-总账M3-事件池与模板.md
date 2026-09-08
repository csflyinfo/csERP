# 优化记录 — PRD-31 总账模块 M3：会计事件池与凭证模板

- **分支**：`feat/finance-gl-event-template`（基于 M2 分支，隔离 worktree `E:\work\erp-wms-tms-gl`）
- **迁移**：`V94__fin_gl_event_template.sql`（4 表 + 16 模板 + ~40 模板行 + 12 条业务类型映射种子）
- **日期**：2026-09-08
- **冒烟脚本**：`development/06-testing/scripts/gl-m3-event-template.js`（空库全流程，已通过 ✅）

## 交付内容

### 后端（com.erp.finance.gl）

| 文件 | 说明 |
| --- | --- |
| `V94__fin_gl_event_template.sql` | fin_gl_event（事件池，4 元组幂等唯一索引 `source_bill_type, source_bill_no, event_code, reverse_flag`，reverse_flag 用 CHAR(1) 因 NULL 不参与唯一约束）、fin_voucher_template / fin_voucher_template_line（模板与分录行）、fin_gl_biz_subject_map（其他出/入库类型 → 对方科目映射）；预置 16 个事件模板（FLY_ORDER 预置停用）、~40 行模板分录、12 条业务映射种子 |
| `GlBizEvent.java` | Spring ApplicationEvent：eventCode/billType/billNo/bizDate/amount/payload/reverse/reverseOfEventId |
| `GlHookEmitter.java` | 业务侧唯一入口：`emit(...)` / `emitReverse(...)`，M4 起埋点调用；业务代码不直接写事件表 |
| `GlBizEventListener.java` | `@TransactionalEventListener(AFTER_COMMIT)`：业务事务提交后才落事件（绝不污染业务事务）；Throwable 全吞只记日志；`fin.gl.initialized=false` 或 `fin.gl.auto_event=false` 跳过；4 元组幂等；发布时注入 taxpayer（参数 `fin.gl.taxpayer_type`，默认 GENERAL）；payload 以 JSON 存 LONGTEXT |
| `GlExprEngine.java` | 零依赖表达式引擎：分词 + 递归下降；白名单 = 数字/字符串/负载变量/true/false/null + `- * / %`、一元 `-!`、比较、`&& ||`；**函数调用在解析期拒绝**（标识符后接 `(` 即报「不支持函数调用」，getClass()/open() 无路可走）、禁点号成员访问、未知变量报错、除零报错；金额 BigDecimal HALF_UP 2 位（中间除法 10 位）；`&&/||` 短路（右操作数只求语法不求值，变量缺失/除零不触发）；ExprException 继承 IllegalArgumentException，中文消息直接回传前端 |
| `GlTemplateRenderService.java` | 模板渲染管线：条件过滤 → 展开（NONE/GOODS_LINE/EXPENSE_LINE，lines_key 可配，盘点用 loss_lines/profit_lines）→ 金额求值（<0.005 跳过）→ 科目解析（固定码 / @AR=1122 / @AP=2202 / @FUND=资金账户映射科目 / @EXPENSE=明细行费用科目（M4 起取 base_expense_type.gl_expense_account_code，缺失回落 560299 借/5051 贷并警告）/ @BIZ=业务类型映射）→ 末级+启用校验（停用报「科目「编码 名称」已停用」）→ 辅助核算（aux_expr JSON，只挂科目配置的维度）→ 同科目+方向+辅助+现金流量合并 → 借贷平衡校验（0.005）→ 反向事件全额取负并加「红冲 」前缀；IgnoreSignal 表示「本条事件不生凭证」（如期初库存） |
| `GlEventService.java` | 事件池工作台：分页过滤/待处理角标/payload 查看/手工补录（幂等）/批量生成（逐条独立事务，失败不连坐；成功插草稿凭证 source='自动' 复用 M2 状态机与凭证号，事件回写 voucher_id；IgnoreSignal → 已忽略；异常 → 生成失败并落根因中文 err_msg 480 字）/忽略/取消忽略/重置（仅草稿凭证可删，已审核/过账拒绝并提示红冲；凭证头分联动删除，事件回待生成）；期间锁定复用 M2 规则（已结账/未开始拦截） |
| `GlEventController.java` | `/finance/gl/event`：page/pending-count/payload/emit/generate/ignore/unignore/reset |
| `GlTemplateService.java` | 模板维护：列表嵌套分录、整表保存（同事件码只允许一张模板，预置不可删可停用）、启停、删除、**试渲染 preview**（不落库，返回分录/合计/警告，配置即测） |
| `GlTemplateController.java` | `/finance/gl/template`：list/save/toggle/delete/preview |
| `GlBizSubjectMapService.java` | 业务类型映射：类型仍留在数据字典（other_outbound_type/other_inbound_type，不剥离），映射独立成表；列表左右两栏（字典 × 映射 LEFT JOIN 科目名），未配置红色标记、字典已删孤儿标记；对方科目留空 = 不生凭证（事件自动已忽略）；保存校验末级+启用并快照类型名 |
| `GlBizSubjectMapController.java` | `/finance/gl/biz-subject-map`：list/save |

### 前端（src/views/gl/，原生 HTML + scoped CSS，无 UI 库）

| 页面 | 路由 | 说明 |
| --- | --- | --- |
| `EventWorkbench.vue` | /gl-event | 待生成凭证工作台：事件/状态/期间/日期/关键词/仅红字过滤，待处理角标，勾选批量生成/一键生成全部，结果汇总（成功 N 张、忽略 N 条、失败 N 条+首条原因）；行内生成/忽略/取消忽略/重置/数据（payload JSON）/凭证联查；红字事件橙色标记 |
| `VoucherTemplate.vue` | /gl-voucher-template | 左模板列表右编辑器：凭证字/摘要模式/分录行（方向、摘要、科目表达式含 @AR/@AP/@FUND/@EXPENSE/@BIZ 提示与末级科目 datalist、金额/数量/辅助/现金流量/条件表达式、展开方式、lines_key、启停）；底部 payload 试渲染面板，分录预览+借贷合计+警告即时反馈 |
| `BizSubjectMap.vue` | /gl-biz-subject-map | 其他出库/其他入库两栏映射表：类型编码/名称、对方科目下拉（含留空=不生凭证）、未配置/字典已删/预置标记；顶部说明类型维护仍在数据字典 |

菜单：SystemController.userMenuTree() 与 fallback-menus.js 增 glEvent/glVoucherTemplate/glBizSubjectMap；router 增三条懒加载路由（meta.module 与菜单码一致）。

## 关键设计决策

1. **事件只在业务事务提交后落库**：AFTER_COMMIT + 异常全吞，总账任何故障都不影响业务单据；事件表写入失败只留日志，会计可在工作台「手工补录」。
2. **事件永不自动过账**：业务只管发事件，会计在工作台批量生成**草稿**凭证，审核/过账仍走 M2 状态机；生成失败可补配置（映射/科目/模板）后原地重试。
3. **其他出/入库类型不剥离字典**：类型仍是 sys_dictionary 数据（基础资料统一维护），科目映射独立 fin_gl_biz_subject_map；映射缺失 → 生成失败并指明类型名与配置入口（总账→业务类型科目映射）；映射科目留空 → 事件自动「已忽略」（如期初库存不生凭证）。
4. **表达式安全**：模板表达式是配置不是代码入口——函数调用/成员访问在解析期硬拒绝，未知变量/除零报错；payload 值只是数据（恶意字符串只参与字符串比较，冒烟实测注入无路可走）；表达式仅在渲染（生成/试渲染）时求值，保存模板不触发。
5. **税务条件化**：税行以 `taxpayer == 'GENERAL'` 条件门控（taxpayer 由系统参数在事件发布时注入），小规模纳税人自动不拆税；费用进项税另需 `expense_deductible == true`。
6. **反向事件**：反审核类业务发 reverse 事件（reverse_flag='1'，幂等元组含该位），渲染时全额取负生成红字凭证（is_red），与 M2 红冲口径一致。
7. **H2/MySQL 双兼容**：迁移脚本全部 CREATE TABLE/INSERT，幂等可重复执行；条件表达式内含单引号，SQL 中一律 `''` 转义，**严禁双引号字符串**（H2 视双引号为标识符，曾致 V94 首次启动失败）。

## 冒烟覆盖（gl-m3-event-template.js）

菜单三项 → 启用总账（1001/3001 各 10 万，启用期 202609）→ 种子核对（16 模板、PUR_IN 3 行、FLY_ORDER 停用、出/入库各 6 条映射、期初库存留空）→ 试渲染：一般纳税人 11300 含税拆 1405 借 10000（数量 10）/222103 借 1300/2202 贷 11300，小规模 2 行不拆税 1405=11300 → 表达式注入：`forName('java.lang.Runtime')` 试渲染报「函数调用」，恶意 taxpayer 字符串只致借贷不平（值不被执行）→ PUR-001 生成草稿（source 自动、3 分录、1405 挂商品 G001、2202 挂供应商 S001）→ 重复发事件命中幂等 → 审核占号 记-202609-0001 → OTHER_OUT 内部领用→560299、活动消耗→560105、自定义类型 9 失败并指明「业务类型科目映射」入口、补映射后重试成功 → OTHER_IN 期初库存自动已忽略无凭证 → 停用科目（自定义模板引用 100201，规避 M2「已有分录引用不可停用」保护）生成失败报「已停用」，恢复后重试成功 → EXPENSE 两场景：有资金账户 3 分录（560201 300 挂部门、560106 200、贷 1001 500 带 CF07）；无账户挂供应商贷 2202 400 → RECEIPT 两场景：有 AR 号借 100201 5000 CF01/贷 1122 挂客户，无 AR 号贷 1221 不出现 1122 → 反向事件生成红字凭证（1405 借 −10000、数量 −10、isRed）→ FLY_ORDER 无启用模板失败 → 忽略/取消忽略 → 重置联动删除草稿凭证（detail 报不存在）后重新生成成功 → 202608 已结账期间事件生成失败报「已结账」→ 待处理角标返回数字。

## 过程中修复的缺陷

- GlEventService 生成凭证 INSERT 漏传 maker_name 占位参数（Parameter #12 not set）。
- GlTemplateService 分录行 INSERT 漏传 direction 参数（Parameter #14 not set）。
- GlExprEngine：ExprException 原继承 RuntimeException，被全局异常处理成「系统繁忙」；改为继承 IllegalArgumentException，中文报错直达前端。
- GlExprEngine：`&&/||` 原为急切求值，左操作数已决定结果时右操作数缺失变量仍报错；改为短路求值（跳过右操作数语法树，函数调用语法检查不跳过）。

## 遗留与后续

- M4：13 类业务单据埋点（GlHookEmitter 调用 + payload 组装）、反审核发反向事件；资金账户/费用类型/商品分类档案加 GL 科目映射列；费用单 business_source='EXPENSE' 自动单不发收付款事件；金蝶/用友 CSV 导出与导出日志；单据→凭证联查接口。
- @EXPENSE 的费用类型科目回落（560299/5051+警告）为 M4 前兼容路径，M4 档案映射列上线后以配置为准。
