# 优化记录 PRD-30 — 采购发票管理（供应商来票登记与勾稽核销）

> 本文件记录 PRD-30 采购发票管理的完整落地，方案见 `docs/采购发票管理-设计方案.md`，
> 面向业务用户的系统说明与操作手册见 `docs/操作手册-采购发票管理.md`。
> 按项目约定：每条记录包含 **改了什么、为什么这么改、影响哪些文件、验证方式**，只追加不覆盖。

**分支**：`feat/purchase-invoice`（从 main 拉出）｜**Flyway**：V90（初版 V89 撞号顺延，见文末「修复记录」）｜**日期**：2026-09-07

---

## 背景（一句话）

商贸批发企业收货入账（应付在收货审核时已生成）与供应商开票之间存在时间差，业务需要登记进项发票、把发票与收货单勾稽核销，清楚回答「哪些采购商品来了票、哪些没来票、来票率多少」。

**核心架构决策：票账分离。** 发票模块只做来票登记、勾稽、来票状态跟踪与报表统计：

- **不生成应付**：`fin_ap` 在采购收货单审核时已按含税金额生成，发票不再动应付金额；
- **不动库存成本**：发票与成本核算无关，价格差错走既有「采购改价单」流程；
- 发票审核只回写 `pur_receipt / fin_ap` 的 `invoiced_amount / invoice_status` 两个跟踪字段，作废/反审核按勾稽快照 `matched_before` 逆向回退。

这与金蝶「暂估冲回+钩稽生成应付」、SAP GR/IR 三单匹配刻意不同——本系统应付在收货时已立账，发票不承担财务入账职责，实施更轻、不引入暂估科目。

## 数据库（V90__pur_invoice.sql）

新建表：

- `pur_invoice` 发票头：invoice_id PK、invoice_no（PINV+日期+流水，唯一）、invoice_number（税票号码）、invoice_code、invoice_type（专票/普票/电子/全电）、direction（蓝/红字，一期只开蓝字）、supplier_code/name、buyer_title/tax_no、issue_date/receive_date、untaxed_amount/tax_amount/total_amount/matched_amount、match_status（未勾稽/部分勾稽/已勾稽）、cert_status（未认证/已认证/无需认证）、cert_date、status（草稿/已审核/已作废）、void_reason、附件、备注、审核留痕。
  - 唯一索引 `(supplier_code, invoice_number, direction)`：同供应商税票号码+红蓝方向唯一，**作废票号也占用不可复用**。
- `pur_invoice_line` 发票明细：goods/数量/含税单价/税率/不含税额/税额/含税金额/source_receipt_no（带入来源）。
- `pur_invoice_match` 勾稽记录：invoice_id ↔ bill_no（收货单）多对多、bill_amount、**matched_before（审核时快照来票前金额，回退依据）**、this_amount、settle_flag（本单结清）、diff_amount、diff_reason。
- 追加跟踪字段：`pur_receipt`、`pur_return`（二期红字发票用）、`fin_ap` 各加 `invoiced_amount DECIMAL(18,4) DEFAULT 0`、`invoice_status VARCHAR(12) DEFAULT '未来票'`（未来票/部分来票/已来票）。

## 后端

新增 `com.erp.purchase.PurchaseInvoiceController`（`/purchase/invoice/*`）：

| 端点 | 说明 |
|---|---|
| POST /page | 发票台账（含合计口径字段） |
| GET /detail | 头+明细+勾稽行；勾稽行带收货单**实时未票金额**，草稿期也可回显 |
| POST /create /update | 草稿保存；update 仅草稿态，子表先删后插 |
| POST /audit | 核心：逐勾稽行校验并回写收货单/应付来票状态，写 matched_before 快照 |
| POST /reverse-audit | 按快照回退来票状态；已认证发票禁止 |
| POST /void | 作废（原因必填），回退勾稽；已认证禁止（须走红字流程） |
| POST /delete | 仅草稿可删 |
| POST /certify | 认证状态登记（未认证/已认证/无需认证） |
| POST /available-bills | 同供应商已审核、未完全来票的收货单选单 |
| POST /report/track/page | R1 来票跟踪：dimension=bill（单据级，含来票率/账龄/关联发票号）/ goods（商品级，UNION ALL 汇总收货明细 vs 发票明细） |
| POST /report/supplier/page | R2 供应商来票统计（收货额/来票额/发票份数/税额/已认证税额/未认证份数/来票率） |
| POST /report/unmatched/page | R4：dimension=unmatched（未勾稽/部分勾稽发票）/ diff（勾稽差异明细） |

关键口径：

- **价内税**（与采购收货单一致）：税额 = 含税金额 × 税率 ÷ (1+税率)；不含税 = 含税 − 税额。
- **勾稽规则**：一发票对多收货单、一收货单对多发票，允许部分勾稽；`this_amount` 超未票额 ≤ 1 元容差自动按结清置平；勾选「本单结清」且尾差 > 1 元必须填差异原因；发票勾稽合计不得超过发票含税金额（+容差）。审核时二次校验（防止保存后被其他发票抢先勾稽）。
- **来票状态**：来票额 ≤ 0 → 未来票；|单额−来票额| ≤ 1 元 → 已来票；否则部分来票。
- 作废/反审核按 `matched_before` 快照把 `pur_receipt / fin_ap` 回退到勾稽前。

配套改动：

- `BillNoGenerator`：新增前缀 `PURCHASE_INVOICE = "PINV"`；**白名单补 `pur_invoice → invoice_no`**（不补时 nextNo 直接抛「非法的单据号目标表/列」——冒烟首测即暴露）。
- `PurchaseController`：删除原 mock 的 /purchase/invoice/page 占位。
- `PurchaseReceiptController.page`、`FinanceController` 的 /ap/page：SELECT 补 `invoiced_amount, invoice_status`。

## 前端

- `module-api.js`：purchaseInvoice 全套端点 + 5 个报表端点；EXACT_TITLE_MAP 补发票/勾稽/来票报表的中文列名映射（采购发票单号、发票号码、发票代码、发票类型、红蓝标志、本次勾稽金额、勾稽前已来票、差异原因、来票状态、未票金额、来票率、关联发票、账龄天数、来票金额、发票份数、进项税额、已认证税额、未认证份数等）。
- `module-config.js`：purchaseInvoice 列表配置（筛选/列/新建发票动作/提示）+ 5 个 report 配置（type:'report', mode:'readonly'）。
- `fallback-menus.js`：报表中心加 5 个菜单（来票跟踪按单据/按商品、供应商来票统计、未勾稽发票、勾稽差异明细）；`router/index.js` 加对应路由。
- 新增 `components/PurchaseInvoiceDrawer.vue`：头部票号/税票号/代码/类型/供应商/日期/购买方/认证状态；Tab 勾稽收货单（选单弹层调 available-bills，显示未票额、本次勾稽额、本单结清勾选、差异原因，尾差实时红字提示，「添加收货单」自动把收货明细按商品合并带入发票明细）+ 发票商品明细（内联编辑、价内税实时算税额/不含税）；页脚含税/税额/不含税/已勾稽/未勾稽余额合计；按钮按状态收敛（草稿：保存草稿/审核/删除；已审核：认证或撤销认证/反审核/作废；已作废只读）。
- `stores/app.js`：invoiceDrawer 状态 + open/close；`layout/AppShell.vue`：挂载抽屉（仅 purchaseInvoice 模块可见）；`views/GenericBusinessList.vue`：purchaseInvoice 分支（新建/编辑/查看走抽屉；审核/反审核/作废（原因 prompt）/认证/删除直调端点）+ 行内操作列按状态出按钮。

## 顺带修复

1. **pom.xml 显式声明 `project.build.sourceEncoding=UTF-8`**：不写死时 Windows 本机 `mvn` 按 GBK 读源码，编译出的中文提示/日志常量全乱码（Linux 容器构建默认 UTF-8 不受影响，故线上无感知，本机冒烟/部署必踩）。
2. **商品级来票报表 SQL**：H2 严格模式下派生表 `GROUP BY goods_code ORDER BY recv_amt - inv_amt` 报 `Column "RECV_AMT" must be in the GROUP BY list`（H2 把别名解析成底层列）；改为 `ORDER BY SUM(recv_amt) - SUM(inv_amt) DESC`，H2/MySQL 均兼容。

## 验证（8081 隔离空库冒烟，2026-09-07）

独立 worktree 思路的轻量版：本机起 8081 端口 + 独立 H2 文件 `data/erp-smoke-pinv`（Flyway 全量迁移，采购发票脚本当时编号 V89、后顺延 V90），H2 Shell 播种 2 张已审核收货单+应付，API 全流程验证，用后删除冒烟库（正式 erp-v1 未动）：

- ✅ 超容差多勾 100 元不结清 → 拒绝；结清但尾差 65 元无差异原因 → 拒绝；
- ✅ 发票 1 全额勾稽收货单 1（2260）审核 → 收货单/应付 **已来票**，发票**已勾稽**；
- ✅ 发票 2 勾 564.50（尾差 0.50 在容差内）勾选结清 → 自动置平，diff_amount=−0.50 落差异报表；
- ✅ 收货单结清后再勾 → 拒绝（未票金额 0）；
- ✅ 发票认证后作废 → 拒绝「已认证发票不可作废，请登记红字发票」；
- ✅ 反审核 → 收货单 1 回 **未来票**（来票额 0）；重新审核 → 恢复 **已来票**（快照回退闭环）；
- ✅ 撤销认证后作废（带原因）→ 发票**已作废**、收货单 2 回 **未来票**、available-bills 重新出现该单；
- ✅ 发票 3 部分勾稽收货单 2（300/565）→ 收货单/应付 **部分来票**、来票率 53.1%、发票 **部分勾稽**（未勾稽余额 39）、R4 未勾稽报表明细正确；
- ✅ 同供应商同税票号重复登记 → 拒绝（作废票号也不可复用）；
- ✅ R1 单据级/商品级、R2 供应商统计（作废发票不计入份数/税额）数据正确；
- ✅ 前端 `npm run build` 通过。

## 已知边界（二期/三期，方案文档已列）

- 红字发票与采购退货的勾稽（表结构已预留 `pur_return.invoiced_amount`）；
- 认证期限预警（开票后 N 天未认证提醒）、供应商对账单「是票开票」自动关联；
- OCR 取票/查验（三期）。

---

## 修复记录：Flyway V89 撞号致后端启动失败（2026-09-07）

**现象**：前端登录提示「服务响应异常」，后端启动即退出。

**根因**：采购发票迁移初版编号 `V89__pur_invoice.sql`，与并行分支 `feat/wms-inbound-unify` 的 `V89__wms_inbound_unify.sql`（PRD-28 入库统一）**版本号撞车**。该 WMS 迁移已于 2026-08-25 应用到本机正式库 `erp-v1`（flyway_schema_history 中 version=89、description='wms inbound unify'、checksum=-956482430）。含采购发票 V89 的新包启动时 Flyway 校验：同版本号本地脚本与已应用记录校验和不一致 → `Migration checksum mismatch for migration version 89` → Flyway 初始化失败 → 整个后端起不来。冒烟时用独立空库未暴露（空库里 V89 只有一个，不存在冲突）。

**修复**：

1. 采购发票迁移顺延为 **`V90__pur_invoice.sql`**（内容不变，仅文件名与头注释）；
2. 把 WMS 分支的 `V89__wms_inbound_unify.sql` 原样取入本分支 `db/migration/`——否则 Flyway 会因「已应用的 V89 在本地找不到」报缺失迁移；
3. 设计方案、操作手册、PRD 索引中的 Flyway 版本号同步改为 V90；
4. 迁移前备份 `data/erp-v1.mv.db.bak-before-V89-*`。

**验证**：重新打包启动，Flyway 校验 V89（wms）通过、顺利应用 V90（pur_invoice 三表 + 跟踪字段），登录恢复。

**教训（后续开工必做）**：新建迁移前先 `ls backend/src/main/resources/db/migration/` 取**本地目录最大号 +1**，同时查并行分支已占号；冒烟空库只能验证脚本语法，验证不了与正式库迁移历史的兼容性。

---

## 改版记录：V91 商品行级勾稽 + 发票金额录入 + 审核后追加备注（2026-09-07）

**背景**：用户验收反馈 5 点——① 流程应为「录入发票（必填发票金额，可先不勾稽）→ 勾稽发票 → 取消勾稽」；② 审核后主信息锁定但应可认证、可追加备注；③ 勾稽应直接选「该供应商未开票商品行」并按**数量**勾稽，列固定为进货单号/入库单号/入库日期/商品编号/单位/规格/数量/单价/金额/已开票数量/未开票数量，不要金额分摊+结清+差异原因那套复杂操作；④ 列表要有发票金额/已勾稽金额/未勾稽金额/勾稽状态（含查询条件）；⑤ 报表中心看不到发票报表菜单。

**设计变更（票账分离原则不变）**：

- 勾稽粒度从「收货单 × 金额」改为「**收货单商品行 × 数量**」：新表 `pur_invoice_match_line`（V91）保存每条勾稽商品行（收货单号/入库单号/进货单号/商品/数量快照/含税单价快照/本次开票数量/本次金额）；原 `pur_invoice_match`（按收货单聚合）保留，保存时由商品行聚合生成，审核/反审核/作废的单据级回写与快照回退逻辑不变。
- **发票金额由用户录入**（`pur_invoice.total_amount`，必填 >0，票面价税合计），不再由明细合计倒算；税额/不含税金额保存时按勾稽行+手工补行聚合计算。
- 未开票数量防重复占量：`occupiedQty(收货单,商品,排除本发票, 是否仅已审核)`——选单与草稿保存含草稿占量（`status<>'已作废'`），审核时点仅统计已审核发票；超量一律拒绝。
- 尾差置平规则不变但**全自动**：审核时按收货单聚合，少票尾差 ≤1 元自动结清置平、多勾 >1 元拒绝，不再需要手工勾「本单结清」、填「差异原因」。

**后端改动（PurchaseInvoiceController）**：

- 新增 `POST /purchase/invoice/available-lines`：入参 `{supplierCode, excludeInvoiceId, keyword}`，返回该供应商已审核收货单中尚有未开票数量的商品行（11 列 + 仓库/税率），已被其他发票（含草稿、不含作废）占用数量实时扣减；
- 新增 `POST /purchase/invoice/update-remark`：仅已审核发票可用，追加备注（≤256 字），主信息不动；
- `create/update` 入参改为 `{...头, totalAmount, matchLines:[{receiptNo,goodsCode,thisQty}], lines:[仅手工补行]}`；修复 update 头表误用 INSERT 致「数据已存在或编码重复」的缺陷（改为 UPDATE 并重置 matched/match_status）；
- `audit` 增加商品行数量二次校验（防草稿期间被抢勾）；审核/反审核/作废联动删除/回写 match_line；
- 列表 `page` 增 `invoiceAmount`（发票金额）；`detail` 返回 `matchLines`（商品行）+ `matches`（聚合，带实时已来票/未票）。

**前端改动**：

- `PurchaseInvoiceDrawer.vue` 重写：发票信息区新增必填**发票金额**输入；勾稽区改为「+ 勾稽商品」未开票商品选单（11 列、关键字搜索、默认按未开票数量全额勾稽、本次数量可改、金额自动算）；每行「取消勾稽」即移除并恢复未开票数量；发票商品明细由勾稽行按商品自动汇总（只读）+「手工补行」；审核后底部出现「追加备注」区；页脚合计显示发票金额/税额/已勾稽/未勾稽/勾稽状态；
- `module-config.js`：列表列改为 发票金额/税额/已勾稽金额/未勾稽金额/勾稽状态 等，筛选加「勾稽状态」下拉（未勾稽/部分勾稽/已勾稽）；
- `module-api.js`：端点增 `availableLines`、`updateRemark`，标题映射增「发票金额」；
- 报表菜单：`SystemController` 后端菜单树补 5 个发票报表（report 权限码 + PURCHASE 角色 scope），此前非 admin 角色/菜单树看不到；
- 顺带修复共享报表端点从未收到维度参数的缺陷（按商品跟踪返回单据数据、差异明细返回未勾稽数据）：`GenericBusinessList.loadRows` 合并 `config.fixedFilters`，商品级/差异报表配置 `fixedFilters.dimension`。

**数据库**：`V91__pur_invoice_match_line.sql`（新表 + 2 索引），启动自动迁移；erp-v1 已迁移至 v91。

**验证（8081 隔离空库冒烟，2026-09-07，36 项全过）**：

- ✅ 只填发票金额不勾稽可保存（未勾稽），缺金额被拒；后续 update 补勾稽成功；
- ✅ available-lines 11 列齐全（进货单号 CGDD/入库单号 CGRK/入库日期/商品/规格/单位/数量/单价/金额/已开票/未开票）；草稿占量实时扣减（20/80），排除本单时含自身数量（100）；
- ✅ 超未开票数量拒绝（A 剩 80 填 90 拒；B 剩 45 填 46 拒，含审核时点二次校验）；勾稽合计超发票金额拒绝；
- ✅ 两张发票分数量勾稽同一收货单，依次审核后来票金额 1100（A 全额 1000+B 100），状态部分来票，关联发票号正确；
- ✅ 反审核按快照回退（来票额归 0/未来票）；取消勾稽后未开票数量恢复（A 20、B 45）；
- ✅ 已认证禁止反审核，撤销认证后可反审核；审核后改主信息被拒，追加备注两次累积保存；
- ✅ 作废无勾稽发票不影响来票金额；未勾稽发票报表含部分勾稽发票（余额 100）；商品级维度 A 100个/1000元、B 5个/100元；供应商汇总 1100；
- ✅ 列表发票金额/已勾稽/未勾稽三列正确，勾稽状态筛选生效；
- ✅ 前端 Vite 编译新抽屉无错误。

## 改版记录：V1.2 抽屉单表化 + 勾稽商品弹窗 + 列表列显示修复（2026-09-07）

**需求来源**：用户反馈两点——① 发票主表（列表）要看到「发票金额」「税额」字段；② 新建/编辑抽屉下方两个表格操作复杂，去掉只保留一个勾稽明细，「勾稽商品」改为弹出窗口选商品，勾稽数量不可超过源单据数量且保存时校验。

**问题 1：列表看不到发票金额/税额列**

- 根因：列设置按模块存 localStorage（`erp-field-setting-v2:module:<模块>`，键为位置位 c0/c1…）。V91 改版在列配置中插入了「发票金额/税额」等新列，老用户本地存的是旧版列位设置，新列继承了旧位置的隐藏/宽度，导致新列不显示；
- 修复：`GenericBusinessList.vue` 的 storageKey 对 purchaseInvoice 单独升到 `purchaseInvoice-v3`，该模块列布局按新配置重置一次（其他模块不受影响）；列配置与字段映射 V91 已具备（发票金额→invoiceAmount/totalAmount、税额→taxAmount），无需后端改动。

**问题 2：抽屉双表格简化为单表 + 弹窗勾稽（`PurchaseInvoiceDrawer.vue` 重写）**

- 删除「发票商品明细」自动汇总表与「+ 手工补行」编辑表（连同 manualLines/invoiceLines/商品选择等全部逻辑），抽屉下方只保留**一张「勾稽明细」表**；发票商品明细行改由后端按勾稽行自动聚合落库（`pur_invoice_line`），前端不再提交 `lines`；
- 「+ 勾稽商品」由抽屉内展开面板改为**模态弹窗**（全屏遮罩 + 居中窗口）：供应商未开票商品列表（进货单号/入库单号/入库日期/商品/规格/单位/数量/单价/金额/已开票数量/未开票数量）、关键字搜索、复选框多选、「添加选中（N）」回填勾稽明细后关闭；已加入明细的行不再出现在弹窗；
- 勾稽明细「本次开票数量」默认 = 未开票数量（全额），可改；行内即时校验：数量必须 > 0、**不得超过源单据（收货单）数量**、不得超过未开票数量，红字提示；保存/审核时前端拦截 + 后端既有校验兜底（「商品 X 在收货单 Y 的未开票数量仅 Z，本次填写 W」）；勾稽合计超发票金额（+1 元容差）拒绝；
- 切换供应商自动清空勾稽明细；审核后的「认证 / 反审核 / 作废 / 追加备注」行为不变；
- `module-config.js`：purchaseInvoice 的 sections 同步为「发票信息 / 勾稽明细 / 认证信息 / 附件备注」，tips 更新为弹窗勾稽流程与数量红线。

**文档**：操作手册 4.2 改为「弹窗选择未开票商品行」、4.4 改为「勾稽明细（抽屉内唯一明细表）」并写明数量红线与后端保存校验。

**验证**：前端 Vite 编译抽屉/配置/列表页均 200 无错；后端契约未变（缺失 `lines` 时按空列表处理、明细由勾稽行聚合），8080 后端（V91 jar）与 5173 前端均在线。

## 改版记录：V1.3 审核后继续勾稽 + 勾稽明细铺满 + 数量/金额/税率可编辑（2026-09-08）

**需求来源**：用户验收反馈三点——① **发票审核后，可继续进行勾稽**（不必反审核）；② 勾稽明细表格铺满窗口下部，不要随记录条数收缩；③ 勾稽时**数量、金额都可修改**，增加**税率**字段（默认从源收货单明细带入），填入金额自动计算税额。

**核心设计：applied_amount（本发票对收货单来票额的实际贡献）**

- V91 的回退模型是「审核时快照 `matched_before`，反审核/作废整体复位到快照」。这隐含假设：一张收货单的来票额只被本发票动过。审核后允许继续勾稽后，同一张收货单可能被**多张发票先后勾稽**（发票1 勾 A、发票2 勾 B），若发票1 反审核时整体复位到自己的快照，会把发票2 已经勾上的来票额一起抹掉。
- V93 给 `pur_invoice_match` 加 **`applied_amount`**：本发票本次审核/保存对该收货单来票额的**实际**贡献（置平封顶/尾差上调后可能 ≠ this_amount）。
  - 审核后勾稽保存（/update-matches）：每张收货单按「**目标来票额 = 当前来票额 − 本发票已贡献 applied_old + 本次勾稽额**」重算，复用既有容差/置平规则，新 applied = 目标 − (当前 − applied_old)；
  - 反审核/作废：逐单 `目标 = 当前来票额 − applied`（下限 0），**只剥离本发票自己的贡献**，不再整体复位快照；其后勾稽同一收货单的其他发票不受影响；
  - 存量已审核数据迁移时回填 `applied_amount = this_amount − diff_amount`（尾差上调场景误差 ≤1 元，可接受）。

**后端改动（PurchaseInvoiceController）**

- 新增 `POST /purchase/invoice/update-matches`：仅**已审核**发票可用（草稿/已作废拒绝）。入参 `{invoiceId, matchLines:[{receiptNo,goodsCode,thisQty,thisAmount,taxRate}]}`；校验供应商/收货单/数量（含草稿占量，排除本发票）后，按收货单聚合重算来票额、回写 `pur_receipt + fin_ap`，重建三张子表（match/match_line/invoice_line），刷新头表 `untaxed_amount/tax_amount/matched_amount/match_status`，记操作日志 MATCH_SAVE；返回勾稽状态与本次影响额。
- 勾稽行支持**手工金额** `thisAmount`（缺省 = 数量 × 收货单价；2 位小数；负数拒绝）与**税率** `taxRate`（缺省取收货明细税率，再缺省 13%）；发票明细行与头税额由勾稽行重新聚合：税额 = 含税金额 × 税率 ÷ (1+税率)（价内税口径不变）。
- `audit` 写回时同步落 `applied_amount`；`reverse-audit/void` 改为按 applied 精确剥离（见上）。
- 抽出公共校验 `collectMatchLines(...)`（create/update/update-matches 三处共用）、`matchSumOf(...)`、`buildInvoiceLines(...)`，三处勾稽口径统一。

**前端改动（PurchaseInvoiceDrawer.vue）**

- 已审核发票抽屉从只读变为**勾稽区可编辑**：头部主信息仍锁定（仅草稿可改），勾稽明细的税率/本次开票数量/本次金额/取消勾稽在「草稿 + 已审核」两态均可用；已审核态工具栏出现「**保存勾稽**」主按钮（调 update-matches，保存后原地刷新），并提示「勾稽明细可继续维护，改完点保存勾稽即回写来票状态」；
- 勾稽明细表新增**税率**列（可编辑文本，默认带入收货明细税率）与**本次税额**列（= 本次金额 × 税率 ÷ (1+税率)，实时计算展示）；**本次金额**列可编辑：未手工改金额前随数量联动（数量×单价），手工填写后保持手填值（amountTouched 标记）；勾稽商品弹窗同步增列税率；
- 布局：抽屉体改为 flex 纵向，勾稽明细卡片 `flex:1`、表格区 `flex:1; overflow:auto` + 粘性表头，**表格铺满窗口下部**，记录少时不收缩、多时表内滚动；
- 保存/审核提交体增 `thisAmount/taxRate`。

**数据库**：`V100__pur_invoice_match_tax_rate_and_applied.sql`——`pur_invoice_match_line.tax_rate VARCHAR(20)`、`pur_invoice_match.applied_amount DECIMAL(18,2) DEFAULT 0` + 存量回填。V92 编号留给并行分支 `feat/system-operation-log`（操作日志增强）。

**文档**：操作手册增「4.6 审核后继续勾稽」、勾稽明细列说明补税率/本次金额可编辑与税额自动计算、布局说明；设计方案补 V93 DDL、applied_amount 模型与 /update-matches 接口。

**验证（8081 隔离空库冒烟，2026-09-08，32 项全过，脚本 smoke-v13.mjs）**：

- ✅ 草稿勾稽行手工金额 900（≠ 默认 1000）保存回显 900；税率改 9% 后头税额 = 900×9%/1.09 = 74.31（13% 时 103.54），价内税自动计算正确；数量超源单据（101>100）拒绝；
- ✅ 审核后来票 900/部分来票；已审核票走 update 改主信息被拒（仅草稿）；
- ✅ **审核后 update-matches 追加 B 50个/1000元** → 来票 1900、发票部分勾稽；A 金额 900→1000 尾差置平 → 来票 2000/已来票、发票已勾稽；
- ✅ 审核后勾稽校验：超数量拒绝、勾稽合计超发票金额拒绝（2100/2500 两档）、**跨票后单收货单超额拒绝**（发票2 占 A 1000 后，发票1 勾 B 填 1100：本票合计 1100 ≤ 发票额 2000 但收货单维度 1000+1100=2100 > 2000，超容差拒绝且无半写）；
- ✅ 审核后清空全部勾稽 → 来票 0/未来票、发票未勾稽；
- ✅ **跨发票精确回退**：发票2 勾 A 审核（来票1000）→ 发票1 审核后勾 B（来票2000）→ 反审核发票2 后来票 **1000**（B 保留，旧快照模型会归 0）；发票2 重审回 2000 → 反审核发票1 后来票 1000（A 保留）；
- ✅ 作废带勾稽的已审核票精确回退（2000→1000），作废票再勾稽被拒；
- ✅ available-lines 带税率、excludeInvoiceId 释放量正确（作废后 B 未票恢复 50）。

## 改版记录：V1.4 已勾稽金额回写源单据商品行 + 引入勾稽带出剩余未开票金额 + 报表中心菜单修复（2026-09-08）

**需求来源**：用户验收反馈两点——① **勾稽发票后的已勾稽金额也要更新给源单据**（不能只回写收货单头），且源单据引入勾稽（勾稽商品选单）时要带出**剩余未开票金额**；② **报表中心菜单点不开**，发票相关报表无法查看。

**问题②根因（前端路由）**：`AppShell.vue` 侧边栏一级菜单展开条件是 `activeTop === 该分组`，而 `activeTop = moduleToCategory[route.meta.module] || '首页'`。报表中心分组的第一个菜单项是「图表报表」（chartReport），其路由 `chart-report` **缺少 `meta.module`**——点击报表中心时导航到 /chart-report，activeTop 回落为「首页」，整个报表中心子菜单（含 5 个发票报表）永远不渲染。同类隐患一并修复：`dashboard`（首页/经营概览）、`todo`（待办中心）、`notification`（消息通知）路由也缺 meta.module，导致工作台/首页分组同样无法展开。

- 修复：`router/index.js` 给上述 4 条路由补 `meta.module`（dashboard / chartReport / todo / notification，取值与 fallback-menus.js 的菜单 code 一致）。

**问题①后端：收货单商品行级来票回写（V94）**

- V90~V93 只把已来票金额回写到收货单**头**（`pur_receipt.invoiced_amount/invoice_status`）与 `fin_ap`，商品行上看不到每个商品开了多少票。V94 给 `pur_receipt_detail` 加 **`invoiced_qty`（已开票数量）/ `invoiced_amount`（已开票金额，含税）** 两列，并按「收货单 + 商品」汇总存量已审核勾稽行回填。
- 新增 `refreshReceiptLineInvoiced(receiptNo)`：按收货单全量重算——`invoiced_qty/invoiced_amount = 该收货单所有【已审核】发票 pur_invoice_match_line 的 this_qty/this_amount 按商品汇总`（与单据级 invoiced_amount 口径一致：草稿不回写、作废不计）。在四个状态流转点、**发票状态翻转之后**调用，保证本发票行计入/剔除正确：
  - 审核（audit）后；审核后改勾稽（update-matches）后（新旧涉及的全部收货单）；反审核（reverse-audit）后；作废（void）后。
- `/available-lines` 选单接口在已开票数量占用子查询上增加 **`SUM(this_amount) AS occ_amt`**（草稿占量、排除本发票口径与数量一致），外层返回新增 **`invoicedAmount`（已开票金额）/ `uninvoicedAmount`（剩余未开票金额 = 行含税金额 − 占用金额）** 两列；过滤条件仍是有剩余未开票数量的行。
- 收货单详情 `/purchase/receipt/detail` 本就 SELECT *，新列经 queryCamel 自动带出 `invoicedQty/invoicedAmount`，无需改 SQL。

**问题①前端**

- `PurchaseInvoiceDrawer.vue`：勾稽商品弹窗新增**已开票金额 / 未开票金额**两列（未开票金额红色加粗）；选中带入勾稽明细时，**本次金额默认 = 剩余未开票金额**（原来默认数量×单价，尾差场景与实际剩余额不一致）；勾稽明细表同步增两列只读展示；提示语改为「默认带出未开票数量与剩余未开票金额；数量、金额、税率均可在勾稽明细中调整」。编辑本发票时 live-patch 同步刷新两列。
- `PurchaseReceiptDrawer.vue`：单据信息区增「来票状态」「已来票金额（含税）」；商品明细表在**已审核**态增「已开票数量 / 已开票金额 / 未开票金额」三列（未开票金额 = 数量×单价 − 已开票金额，红色）。

**数据库**：`V101__pur_receipt_detail_invoiced.sql`（2 列 + 存量回填，标量子查询 UPDATE；V92 仍留给并行分支 feat/system-operation-log）。

**文档**：操作手册补「收货单上的来票情况」「勾稽选单带出剩余未开票金额」；设计方案补 V94 DDL、行级回写时机与选单金额列。

**验证（8083 隔离空库冒烟，2026-09-08，30 项全过，脚本 smoke-v14.mjs）**：

- ✅ 初始选单 A/B 行已开票金额 0、未开票金额 1000；收货单行回写列为 0；
- ✅ 草稿不回写源单据（行/头均为 0）；草稿占量含金额（A 数量占满对他票不可见、B 未开票金额仍 1000）；编辑本票排除自身后 A 未开票金额恢复 1000；
- ✅ 审核发票1（A 100个/900元）→ A 行回写 100个/900元、B 行不动；审核发票2（B 50个/1000元）→ 行级 A=900 / B=50个1000元、头 1900/部分来票；
- ✅ 审核后改勾稽 A 900→1000（票面 1000）→ 置平结清：头 2000/已来票，行级 A=1000、B=1000；
- ✅ 反审核发票2 → 头回落到 1000，A 行 100个/1000元不受影响，B 行剥离归零；重审发票2 → B 恢复 1000；
- ✅ 发票1 清空全部勾稽 → A 行归零、B 行保留发票2 贡献 1000，A 行重新出现在选单且未开票金额 1000；
- ✅ 草稿占金额：发票3 草稿 A 60个/500元 → 他票视角 A 未开票数量 40、已开票金额 500、未开票金额 500；删草稿后恢复 1000；
- ✅ 作废发票2 → 头 0/未来票、两行金额均归零，选单 A/B 未开票金额均恢复 1000；
- ✅ 前端 `npm run build` 通过（RC=0），路由 meta.module 修复后报表中心/工作台/首页分组均可正常展开。

## 改版记录：V1.5 勾稽明细表精简为本次开票四列 + 未勾稽发票报表增勾稽入口（2026-09-08，纯前端）

**需求来源**：用户验收反馈两点——① 发票新建/编辑/详情页勾稽明细表格列太多（数量/单价/金额/已开票数量/未开票数量/已开票金额/未开票金额），只保留**税率/本次开票数量/本次开票金额/本次税额**四列；添加勾稽商品后，在「本次开票数量」输入框下方显示**未开票数量**、「本次开票金额」下方显示**未开票金额**，供保存时对照校验；② **未勾稽发票报表**每行增加【勾稽】按钮，点击弹出发票抽屉直接勾稽，操作与采购发票模块完全一致。

**① 勾稽明细表精简（PurchaseInvoiceDrawer.vue）**

- 明细表头从 19 列精简为：序号 / 进货单号 / 入库单号 / 入库日期 / 商品编号 / 商品名称 / 规格 / 单位 / **税率 / 本次开票数量 / 本次开票金额 / 本次税额** / 操作（编辑态）。移除数量、单价、金额、已开票/未开票数量、已开票/未开票金额 7 个只读列。
- 「本次开票数量」「本次开票金额」输入框（仅编辑态）下方新增小字提示 `.cell-hint`：`未开票 {数量}` 与 `未开票 ¥{金额}`，数据取该行实时的 uninvoicedQty/uninvoicedAmount（加载时 available-lines 排除本发票、勾稽商品弹窗带入时、live-patch 时都会刷新）。只读态（已作废）不显示提示。
- **被隐藏的列数据仍保留在行数据中**（qty/price/invoicedQty/uninvoicedQty/invoicedAmount/uninvoicedAmount），matchWarn 校验（数量 > 0、≤ 源单据数量、≤ 未开票数量、金额 ≥ 0）与「勾稽合计 ≤ 发票金额」校验口径不变。
- **勾稽商品选单弹窗保持全列不变**（数量/单价/金额/税率/已开票数量/未开票数量/已开票金额/未开票金额）——弹窗是选数场景，需要完整信息；本次只精简抽屉内明细表。
- 只读态（详情/已作废）本次开票金额列展示 `matchThisAmount(m)` 计算值（手工金额或数量×单价），本次税额列展示 `lineTax(m)`，与编辑态口径一致。

**② 未勾稽发票报表【勾稽】按钮（GenericBusinessList.vue + AppShell.vue）**

- 报表行操作列新增 `invoiceUnmatchedReport` 专用模板分支：一个【勾稽】主链接按钮；`handleAction` 新增分发：取 `row._raw.invoiceNo`（报表接口返回字段；兜底 invoiceId）调 `app.openInvoiceDrawer(invoiceNo, false)`。
- 抽屉是全局单例（AppShell 挂载一次），可见性门禁从仅 `purchaseInvoice` 扩展为 `purchaseInvoice | invoiceUnmatchedReport`；报表内发票均为已审核，抽屉以**非只读**打开——「+ 勾稽商品」「保存勾稽」（update-matches 增量回写）、认证、反审核、作废全部可用，与采购发票模块同一组件同一接口；保存后 `app.refreshSignal++` 触发报表列表自动刷新（勾稽完的发票从未勾稽报表消失）。
- 抽屉 `detail` 接口本就支持按 invoice_no 查询（requireInvoice `WHERE invoice_id = ? OR invoice_no = ?`），后端零改动。

**顺带修复的入口缺陷**：采购发票列表「查看」已审核发票此前以 `readonly=true` 打开抽屉，导致 V1.3 的「审核后继续勾稽」从列表入口**根本进不去**（只读态没有勾稽商品/保存勾稽按钮，只能从新建流之外的入口碰运气）。改为：仅**已作废**发票查看时只读；草稿「编辑」、已审核「查看/编辑」均非只读打开。报表勾稽入口与列表入口行为由此统一。

**数据库 / 后端**：无改动（纯前端；无新增 Flyway 脚本，当前最高版本仍为 V94）。

**文档**：操作手册 §4.4 勾稽明细表列说明更新为四列 + 输入框下余量提示（§4.2 选单弹窗全列不变）、§5.4 未勾稽发票报表补【勾稽】按钮用法；设计方案补 V1.5 条目。

**验证（2026-09-08）**：

- ✅ 前端 `npm run build` 通过（RC=0，✓ built in 4.75s）；
- ✅ 静态核对：表头/表体列数一致（12 列 + 编辑态操作列）；`row._raw = record` 携带 camelCase 原始字段（含 invoiceNo）；handleAction 分发链中报表分支模块隔离不影响其他报表；
- ✅ 数据流核对：报表行 invoiceNo → openInvoiceDrawer → detail（按 invoice_no 命中）→ 已审核非只读 → 勾稽商品/保存勾稽（update-matches）→ refreshSignal 报表刷新，全链路复用采购发票模块既有接口，无后端改动。
