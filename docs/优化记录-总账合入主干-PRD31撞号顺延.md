# 优化记录 — 总账模块（PRD-32）合并主干：回灌 PRD-31、Flyway 撞号顺延、冲突合并

- **日期**：2026-09-08
- **合并方式**：主干先 `merge` 回灌特性分支（保留双方历史）→ 验证 → `--squash` 合入 `main`（一个功能一个提交）
- **迁移**：总账 7 个脚本 **V92~V98 顺延为 V93~V99**（V92 被 PRD-31 占用）

## 背景

总账 M1~M9 开发期间，`feat/system-operation-log`（PRD-31 统一操作日志）先行 squash 合入主干（`main` → 4286b85，含 `V92__operation_log_enhance.sql`）。总账分支基于旧主干 9e78bcc，与 PRD-31 存在两处并行冲突面：

1. **Flyway 版本号撞车**：总账 M1 占用 V92（`V92__fin_gl_account_init.sql`），PRD-31 也占用 V92（`V92__operation_log_enhance.sql`）。文件名不同 git 不冲突，但运行时 Flyway 报重复版本、后端起不来。
2. **同一批控制器双方都改**：PRD-31 给采购/销售/库存/财务 10 个控制器注入 `OperationLogService` 并把私有 `log()` 改为委托统一日志；总账给同一批发起业务事件的控制器注入 `GlHookService` 并在审核/反审核点调用总账钩子。构造器与审计点文本冲突。

## 处理内容

### 1. 主干回灌（merge，非 squash）

`git merge main` 进 `feat/finance-gl-aux-export`，保留双方历史。

### 2. Flyway 撞号顺延（总账脚本整体后移一位）

| 原版本 | 新版本 | 内容 |
| --- | --- | --- |
| V92__fin_gl_account_init.sql | **V93** | 会计科目/期间/期初/项目档案（M1） |
| V93__fin_gl_voucher.sql | **V94** | 凭证/分录/现金流量（M2） |
| V94__fin_gl_event_template.sql | **V95** | 事件池/凭证模板/业务类型映射（M3） |
| V95__fin_gl_hooks_export.sql | **V96** | 档案科目列/凭证导出（M4） |
| V96__fin_gl_period_transfer_report.sql | **V97** | 期末处理/自动转账/报表种子（M5~M6） |
| V97__fin_gl_asset.sql | **V98** | 固定资产卡片/折旧（M7） |
| V98__fin_asset_life.sql | **V99** | 资产变更/清理/盘点（M8） |

V92 保留给主干的 `V92__operation_log_enhance.sql`（PRD-31）。文件头注释版本号同步修正；SQL 内容不动（版本号仅文件名与注释引用，无内部依赖）。

### 3. 代码冲突合并口径（10 个控制器 + 2 个配置/文档）

- 构造器：**两个依赖都保留**——`OperationLogService opLog`（PRD-31，统一操作日志）与 `GlHookService glHooks`（总账事件钩子）并存注入；
- 审计/反审核点：**两个调用都保留**——先/后调用互不影响（钩子写事件池，日志写操作流水）；
- `log()` 私有方法统一采用 PRD-31 版本（委托 `OperationLogService`，真实操作人/IP/耗时）；
- `SystemController`：PRD-31 的「登录日志」菜单与总账的「总账管理」菜单组自动合并共存；`/system/operation-log/page` 端点迁移到 `SystemLogController`（路径不变，冒烟兼容）；
- `BaseMasterController`：费用类型/资金账户的 `gl_*_account_code` 列规格与 PRD-31 改动自动合并共存；
- `vite.config.js`：双方都已支持 `VITE_API_TARGET` 环境变量（平行实现），保留 8082 注释；
- PRD 路线图：总账由占位号 **PRD-31 更正为 PRD-32**（PRD-31 归操作日志），状态改「已完成（待业务核验）」，迁移列更正为 V93~V99。

### 4. 合并回归修复（全量冒烟暴露）

- **条件表达式宽松模式**（`GlExprEngine`）：业务凭证 payload 是稀疏的（收款单客户/员工/供应商三选一），模板分流条件形如 `ar_bill_no == '' && customer_code == ''` 判断"某字段为空"时该字段本就不存在。引擎新增 `lenient` 模式：**业务模板条件**缺失变量视为空串（`GlTemplateRenderService` 用宽松模式）；**转账/报表公式**仍走严格模式（写错变量名必须报错，保存即试算拦截）。M3 冒烟中员工收款单（无 customer_code）此前抛"表达式引用了不存在的变量：customer_code"即此回归。
- **gl-m5 冒烟两处旧口径断言更正**：折旧③在无使用中资产时状态为 `pass`（无需计提），非 M7 上线前的 `skip`；业财对账⑧为非阻断查询项，恒为 `skip`（结账后可随时查），断言保留但更正说明。
- 二次回灌：合并期间主干又进 `af5f916`（PRD-31 TMS/WMS 日志委托统一 OperationLogService），仅改 TMS/system 日志类，与 GL 无文件交集，干净合并；GL 经 `TmsUtil.log` 写日志的调用随之走新通道，M9 冒烟确认日志正常落 `sys_operation_log_runtime`。

### 5. 验证

- 后端 `mvn -o package -DskipTests` 通过；前端 `npm run build` 通过；
- Flyway 空库校验 **96 个迁移全部通过**（含 V92 操作日志 + V93~V99 总账），无重复版本；
- 总账 M1~M9 全套冒烟（gl1~gl9 独立空库、8081 端口、Redis 自动配置排除，驱动脚本 `development/06-testing/scripts/_gl-merge-smoke-driver.sh`）**9 套全部 PASS，后端日志 0 ERROR**。

## 合并后注意

- 总账验证环境的旧 dev 库（`backend/data/erp-v1`，按旧 V92~V98 号迁移过）与新脚本版本号不一致，**合并后首次启动需空库重迁**（冒烟库已逐套重建；8082 验证库同步重建）。
- 历史 `docs/优化记录-*总账M*.md` 中出现的 V92~V98 字样为当时记录，以本文件顺延结果 V93~V99 为准。
- 后续测试问题另开 `fix/gl-*` 短命分支（从合并后的 main 起），不得复用已 squash 的特性分支。
