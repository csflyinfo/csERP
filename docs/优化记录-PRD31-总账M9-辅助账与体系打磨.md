# 优化记录 — PRD-31 总账模块 M9：多栏账/辅助核算账/凭证打印/模板与公式维护（体系打磨收尾）

- **分支**：`feat/finance-gl-aux-export`（基于 M8 提交，工作树 `E:\work\erp-wms-tms-gl`）
- **迁移**：**无新 Flyway 脚本**。多栏账/辅助账/打印为只读聚合；自动转账模板 CRUD 复用 V96 `fin_auto_transfer`/`fin_auto_transfer_entry`；报表公式编辑复用 V96 `fin_report_item`。（计划中「V99 起」未触发——本期无 DDL 需求。）
- **日期**：2026-09-08
- **冒烟脚本**：`development/06-testing/scripts/gl-m9-aux-export.js`（专用空库 erp-smoke-gl9，8081，已通过 ✅，后端日志 0 ERROR；前端 `npm run build` 通过）

## 交付内容

### 后端

| 文件 | 说明 |
| --- | --- |
| `GlLedgerService.java` | 新增三个聚合：`multiColumnLedger` 多栏账（上级科目末级子科目摊列，分析侧取科目余额方向）、`auxBalance` 辅助核算余额表（7 维度对象聚合期初/本期借贷/期末，档案 join 出名称）、`auxSubsidiary` 辅助核算明细账（单对象逐笔分录+滚动余额）；辅助 `AUX_ARCHIVE`（维度→档案表/编码列/名称列，片区自由文本无档案）、`auxNameMap`（缺表安全回退）、`auxColumn`（维度校验）、`monthsBetween`（逐月列表） |
| `GlLedgerController.java` | 新增 3 端点：`/finance/gl/ledger/multi-column`、`/aux-balance`、`/aux-subsidiary`（均 ApiResponse 包装） |
| `GlVoucherService.java` | 新增 `printData(id)`（detail 基础上补 companyName、amountCn 人民币大写、分录 auxText 缺失时按维度编码现查名称拼「维度：名称；…」）；`amountInChinese` 人民币大写（零壹贰…万亿，元角分，零元整/整 规则） |
| `GlVoucherController.java` | 新增 `/finance/gl/voucher/print-data` |
| `GlTransferService.java`（新增） | 自动转账模板 CRUD：`list`（头+分录）、`save`（新建 ZZ 自动编号/手填正则校验、更新分录全删重插；**保存即用当前进行中期间试算全部金额公式（GlReportCalc 白名单）与执行条件（GlExprEngine，变量 taxpayer/surtax_rate）**；分录 ≥2、借贷俱全、方向 借/贷、科目必须末级启用；系统预置可改不可删）、`toggle` 启停、`delete`（is_system=TRUE 拒绝）；全部动作 TmsUtil.log |
| `GlTransferController.java`（新增） | `/finance/gl/transfer/list|save|toggle|delete` |
| `GlReportService.java` | 新增 `formulaList(reportCode)`（BS/IS/CF 校验归一）、`formulaSave`（仅 itemName/formula/formulaBegin/remark 可改；行号/行类型/缩进锁定保表结构；公式保存即试算，不过拒存；calc.invalidate 清缓存；FORMULA 日志） |
| `GlReportController.java` | 新增 `/finance/gl/report/formula-list`、`/formula-save` |

### 前端（原生 HTML + scoped CSS，无 UI 库）

| 页面 | 路由 | 说明 |
| --- | --- | --- |
| `LedgerQuery.vue`（扩展） | /gl-ledger | 账簿查询加两个 Tab：**多栏账**（选上级科目+月份范围，提示分析侧方向；子科目编码/名称双行表头，逐月行+合计行）、**辅助账**（内嵌余额表/明细账两个子 Tab：余额表选维度+期间+可选科目，对象编码/名称/期初借贷/本期借贷/方向/期末；明细账选维度+对象（档案下拉，片区为自由文本输入）+日期范围+可选科目，期初行+逐笔滚动余额+合计+期末） |
| `VoucherPrint.vue`（新增） | /gl-voucher-print（顶层独立路由，不走 Layout） | 凭证打印窗口：单位名+记账凭证标题、表头（日期/凭证号/附件）、边框分录表（摘要/科目/辅助核算/借贷）、合计行大写金额、制单审核记账出纳签章位、未过账警示；onMounted 取数后自动 window.print，工具栏打印/关闭按钮；@media print 隐藏工具条 |
| `VoucherList.vue`（扩展） | /gl-voucher | 操作列加「打印」→ window.open 独立打印窗口 |
| `TransferTemplate.vue`（新增） | /gl-transfer-template | 转账模板维护：列表（编号/名称/执行条件/分录数/启停/系统预置标）+ 右侧抽屉编辑（编号留空自动 ZZ、名称、执行条件带变量提示、备注、启用勾选；分录表 方向/摘要/末级科目/金额公式，增行/删行最少 2 条；函数白名单提示 QM/QC/FSD/FSC/LJFS/LJFSC/CF）；系统模板隐藏删除 |
| `FinReport.vue`（扩展） | /gl-report | 页头加「公式编辑」抽屉：三表行次/项目名称/本期公式/年初公式可改，改动行黄色高亮、逐行保存（保存后即时重算报表），函数白名单提示 |
| `BizSubjectMap.vue`（扩展） | /gl-biz-subject-map | 加「导出对照配置（CSV）」：事件码/事件名/类型编码/类型名/对方科目编码/名称/配置状态，UTF-8 BOM |
| `ArchiveMapping.vue`（扩展） | /gl-archive-mapping | 加「导出对照配置（CSV）」：资金账户/费用类型/商品分类三类映射（分类含收入+成本两科目），含未配置兜底状态 |

菜单与路由：SystemController 菜单表 + fallback-menus.js 同步加「转账模板 glTransferTemplate」（总账管理组，凭证模板之后）；router 加 gl-transfer-template 子路由与 /gl-voucher-print 顶层路由。

## 业务规则（落地口径）

1. **多栏账**：仅非末级科目可查（末级科目引导查明细账）；栏目 = 该科目编码前缀下所有**启用的末级**科目（含间接下级），无末级子科目报错；**分析侧取上级科目余额方向**——借方向（成本/费用/资产类）分析借方发生，贷方向（收入/负债类）分析贷方发生；期初 = 各子栏启用期初 + 查询起始期间之前的已过账发生合计；逐月一行（cells 按子科目编码取分析侧金额）+ 行合计 + 栏目合计行；已冲销凭证按 M2 口径留账轧差。
2. **辅助核算余额表**：7 维度（客户/供应商/部门/员工/商品/项目/片区）；按辅助对象聚合启用期初（fin_init_balance 的 aux_* 行）与已过账发生（期间前归期初、区间内归本期），期末借/贷 = 期初+本期后按 splitBal 出方向；对象名按维度 join 档案表（base_customer/base_supplier/base_department/base_employee/base_goods/fin_aux_project），**片区无档案、回退显示原文**；档案表缺失时安全回退编码；可选科目编码前缀过滤（传父级按 LIKE 含全部下级）；全零对象不出场。
3. **辅助核算明细账**：单个辅助对象必填；期初 = 该对象启用期初 + 起始日期前已过账发生；逐笔列凭证日期/字号/摘要/科目/借贷/辅助文本，**滚动余额逐笔重算方向**；可跨科目或按科目前缀过滤；合计借/贷与期末余额。
4. **凭证打印**：数据走 detail() 同口径（头+分录 LEFT JOIN 科目名），补单位名称（sys.company.name，空则不显示）与合计人民币大写；分录 aux_text 缺失时按 7 个 aux_* 编码现查档案名拼展示串；草稿无凭证号显示「（未审核）」；打印页为独立路由新窗口，浏览器打印即出，无第三方依赖。
5. **自动转账模板维护**：编号 ZZ+两位顺序自动顺延（手填须匹配 `ZZ[0-9A-Z]{1,8}` 且唯一）；保存时**所有金额公式用当前进行中期间试算**（金额为 0 不阻断，函数不存在/语法错/科目异常才拒），执行条件用 GlExprEngine 试算（变量 taxpayer 取纳税人类型参数、surtax_rate 取附加税率参数默认 0.12）；分录必须借贷俱全、科目为末级启用；aux_config 可传 JSON 对象；**is_system=TRUE 的 ZZ01/ZZ02 可编辑可停用、删除被拒**（引导停用）；期末预览/执行（M5）仍只取 enabled=TRUE，零金额/借贷不平/条件不满足跳过、ZZ{期间} 幂等，本期未改动。
6. **报表公式编辑**：三表项目行可改项目名称、本期/期末公式、年初/累计公式、备注；**行号、行类型（项目/小计/合计）、缩进锁定**（保表结构与 BS 平衡校验按「资产总计」/「负债…总计」行名匹配的口径）；公式保存即按进行中期间试算（错误信息区分期末/年初公式）；保存后 calc.invalidate，报表即时反映；系统预置行同样可改（公式留空 = 该行不取数）。
7. **对照配置导出**：纯前端导出已加载的映射数据（业务类型映射含未配置/字典已删状态；档案映射含兜底状态），CSV 带 UTF-8 BOM 供 Excel 直开，逗号/引号/换行转义。
8. **辅助核算期初补录**：M1 期初接口已支持行内 auxXxx 字段（账户 aux_dimensions 必须含该维度，否则拒绝），本期冒烟验证部门辅助期初进入辅助账期初列，无需新代码。
9. **操作日志查漏**：M9 全部新写动作落 sys_operation_log_runtime——finance.gl.transfer 的 SAVE/TOGGLE/DELETE、finance.gl.report 的 FORMULA（审核/过账/结账动作 M2/M5 起已全量留日志）。

## 冒烟覆盖（gl-m9-aux-export.js，启用期 202609，专用空库 erp-smoke-gl9）

数据布局：期初 1001 借 50000 / 3001 贷 50000 + 1601 借 500（部门 DEPT）/ 1602 贷 500（部门 DEPT，辅助期初补录），试算 50500 平衡；V1（09-10）借 560202 950.00/贷 1602 950.00 挂 DEPT，V2（09-15）借 560202 1234.56/贷 1602 1234.56 挂 DEPT2，均审核过账。

- **菜单**：总账管理组含 glTransferTemplate。
- **多栏账**：5602 分析侧=借，栏目含 560202；202609 行 560202 栏 2184.56（950+1234.56）、行合计/栏目合计/总合计均 2184.56；末级科目 1001 查多栏账被「非末级科目」拒绝。
- **辅助余额表**：非法维度被「辅助核算维度应为」拒绝；department+科目 1602 过滤下 DEPT 行期初贷 500/方向贷、本期贷 950、**期末贷 1450**，DEPT2 本期/期末贷 1234.56；两行 auxName 均解析为部门档案名。
- **辅助明细账**：缺辅助对象被「请选择」拒绝；DEPT+1602 过滤后期初贷 500、仅 1 行（贷 950）、滚动余额贷 1450、合计贷 950；跨科目 2 行（借 560202 950 + 贷 1602 950）合计借贷各 950。
- **转账模板**：list 含预置 ZZ01/ZZ02（各 ≥2 分录、isSystem）；新建 ZZ03（借 5403/贷 1001，公式 FSD('560202')）自动编号成功；坏函数公式被「无法取数」拒、单分录被「至少需要 2 条分录」拒、双借被「同时有借方和贷方」拒、父级科目 5602 被「不是末级启用科目」拒、删 ZZ01 被「系统预置」拒；停用后 transfer-preview 不含 ZZ03，启用后预览 todo 且借贷合计各 **2184.56**；仅执行 ZZ03 生成「转」字草稿（借 5403 2184.56/贷 1001 2184.56），再预览 done、重复执行 exists 幂等；删除 ZZ03 后列表不含。
- **报表公式**：BS/IS/CF 公式行数齐全（BS≥20）；坏函数公式被「无法取数」拒；货币资金行公式改 QM('1001') 后报表该行取 **50000.00**（期初库存现金，无现金发生）；断言后还原原公式。
- **凭证打印**：V1 合计 950.00 → amountCn「玖佰伍拾元整」，1602 分录 auxText 含「部门」与部门名称；V2 合计 1234.56 →「壹仟贰佰叁拾肆元伍角陆分」；companyName 字段存在。
- **操作日志**：finance.gl.transfer SAVE/TOGGLE(≥2)/DELETE、finance.gl.report FORMULA(≥2，改+还原) 均在 sys_operation_log_runtime 查到。
- 后端日志 0 ERROR；前端 vite build 通过。

## 过程中修复的缺陷

- **打印分录辅助串方法名冲突**：首版 `buildAuxText(Map)` 与既有 `buildAuxText(Map,String)` 重载撞名且调用了不存在的 auxName()；改为 `buildEntryAuxText(Map<String,Object>)`，复用既有 `resolveName(dim, code)` 做编码→名称解析。
- **GlTransferService JSON 序列化**：误用不存在的 TmsUtil.toJson；按 GlAssetService/GlEventService 既有模式改为 `new ObjectMapper()`，序列化失败抛中文业务错误。
- **打印页模板直调 window**：Vue 模板不可直接访问 window，补 doPrint()/doClose() 包装。
- 冒烟侧修正两处预期：操作日志端点用裸 JdbcTemplate 查询，H2 返回**大写字段名**（MODULECODE/ACTION，非 queryCamel 路径），脚本按大写读取；失败的模板/公式保存在校验阶段即拒绝、不写日志，SAVE 日志断言改为 ≥1（并补 DELETE 断言）。

## 遗留与后续

- 多栏账目前固定按「上级科目余额方向」取分析侧发生额；若未来需要双向分析（如损益类既看借又看贷），在页面加分析侧切换即可（后端 cells 数据已可双侧扩展）。
- 凭证打印为浏览器打印窗口（window.print + @media print 样式），未做套打模板设计器；如需纸样定制，后续在系统参数加打印模板选择。
- 报表公式编辑目前对系统预置行也开放（可改名/改公式/留空）；如要更强管控，可加「恢复预置公式」按钮（公式种子在迁移脚本中可重新 MERGE）。
- 总账 M1~M9 全部里程碑完成；后续按用户核验反馈迭代。
