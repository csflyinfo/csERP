# 优化记录：商品收发存定版日余额（PRD-33 补充件，V121）

- 分支：`feat/finance-goods-daily-close`（基于 PRD-33 squash 提交 ca15436；squash 合入 main 后即删）
- 前置：**PRD-33（V120）必须先合入 main**，本分支依赖四张日结主表与 7 步结账事务链
- 迁移：`V121__biz_close_goods_daily.sql`（全局唯一，下一可用号 V122）
- 需求来源：《业务日结-商品日结补充方案.md》v1.4-supplement 评审定稿版
- 验收脚本：`development/06-testing/scripts/v1-biz-day-close-test.js` 同文件追加 V121 断言，独立 H2 空库 + 8081 + JDK21 真实后端**全绿（2026-09-14）**，不进 CI

---

## 1. 为什么补

PRD-33 索引行承诺「固化库存/应收/应付/资金**四类**定版日余额」，V120 只落了往来/资金三张：库存仅在向导第 5 步做商品+仓硬勾稽，**结果不落定版**；`inv_stock_daily_snapshot` 是分析快照（在途、动销预测列，无分类收发、无签收口径、无日结烙印，可重建）。

V121 补齐第四类：**商品收发存定版日余额**，反结即物理删除、重算不可改、RJ 单可打印、台账可区间滚查。

## 2. 终审两项裁决

| 待评审点 | 结论 | 落地 |
| --- | --- | --- |
| Q1 签收口径四列是否同期冻结 | **采纳，同期做** | signed_qty / signed_amount / signed_cost_amount / gross_profit 与实物收发并列冻结，互不勾稽（已出库未签收=在途差，允许） |
| Q2 是否做到批次级 | **不做**。粒度只到 **日 + 商品 + 仓** | 不建 `biz_close_goods_batch_daily`；批次/效期仍走实时批次库存与报表 #9，日后有合规需求单独立项 |

## 3. V121 数据对象（仅 1 张表 + 2 个索引）

`biz_close_goods_daily`，主键 `(close_date, goods_code, warehouse)`：

- **档案快照列**：goods_name / spec / barcode / base_unit / large_unit / large_convert_qty / brand_name / category_name / storage_property（结账时刻冻结，事后改名不动历史，规则同资金账户名快照）；
- **滚存列**：opening_qty/amount（前日定版期末；首日无定版行则 0，基线由期初入库单审核背书）；
- **八类收发（数量 18,4 + 金额 18,2，分类逐字复用报表 #8 `InventoryRollSummaryDefinition` 的 bill_type_code CASE）**：
  - 收入：purchase_in（CGRK、WMS_INBOUND）/ sales_return_in（THRK）/ other_in（QTRK、JSRK、PDD、WMS_ADJUST_GAIN、OTHER）/ transfer_in（DBRK）
  - 发出：sales_out（XSCK）/ purchase_return_out（CTCK）/ other_out（QTCK、BSD、PDD、OTHER）/ transfer_out（DBCK）
  - `in_amount` 小计**排除 CGSH**（采购收货只立应付不动物账）；`adjust_amount` = direction '成本调整'
  - in_qty/out_qty 小计；
- **签收口径列**：signed_qty / signed_amount / signed_cost_amount 取 `rpt_dws_sales_d` 当日 签收−退货 净额；gross_profit = signed_amount − signed_cost_amount；
- **期末列**：ending_qty / ending_amount 取结账时刻 `inv_stock_balance` 按商品+仓 SUM；ending_cost_price = 金额/数量 ROUND 6 位（量 0 置空；成本仍是商品+仓移动加权，不按批次）；
- **勾稽列**：qty_diff、amount_diff（SELECT 内直接算出差异落库便于直查）、tie_flag（提交行恒 'Y'）、negative_flag（期末负库存 Y/N）、frozen_time。

索引：`(goods_code, close_date)`（商品区间查主路径）、`(warehouse, close_date)`（仓库数据范围）。SQL 全程单引号、`IF NOT EXISTS`、无 MySQL 专有函数，H2(MODE=MySQL)/MySQL 双跑。

## 4. 生成 / 反结逻辑

新类 `com.erp.finance.dayclose.BizGoodsCloseSnapshotService`（JdbcTemplate 构造注入）：

1. **结账事务内先刷商品维度**：`rebuildGoods()` 首行调 `ReportDimGoodsService.refreshAll()`（默认传播 REQUIRED，加入结账事务，失败一起回滚）。
   - 踩坑：`rpt_dim_goods` 是 Java 全量刷新的快照表（启动 + 30 分钟任务），**全新库首次日结时可能从未刷新过**，首版实现直接 LEFT JOIN 导致档案列全空、只能靠名称兜底。定版不能依赖 ETL 时机，故改为结账时刻自刷（连带刷新 V113 单位换算率维度，表小，数万行内）。
2. 键集 UNION：前日定版键 ∪ `rpt_dws_stock_move_d` 当日键 ∪ `inv_stock_balance` 非零键（当日耗尽有收发也落行，不断历史）。
3. 单条大 INSERT...SELECT（普通字符串拼接显式留空格，**不用 Java 文本块**——行尾裁剪曾致 SQL 粘连 500）：LEFT JOIN 档案维度、DWS 八类透视、sales DWS 签收净额、balance 期末、前日定版期初，六处日期参数顺序固定（close_date/键集前日/键集当日/移动 DWS/签收 DWS/期初前日）。
4. INSERT 后 `COUNT(*) WHERE ABS(qty_diff)>0.001 OR ABS(amount_diff)>0.01`，>0 抛「商品收发存定版恒等式校验不平（日期，N 行），已中止日结」整笔回滚（IllegalArgumentException 中文用户可见，N 行绝不落库）：

   ```
   期末数量 = 期初 + 收入小计 − 发出小计            （容差 0.001）
   期末金额 = 期初 + 收入金额 + 成本调整 − 发出金额  （容差 0.01）
   ```

5. **总额闭环**：`performClose` 收集 totals 后追加断言 Σgoods.in_amount = `biz_day_close.stock_in_amount`、Σout_amount = stock_out_amount（容差 0.01，BigDecimal 转 double 比较；防止定版聚合与主表两套口径漂移）。
6. `tieStock` 期初来源切换：D-1 有定版行优先取定版 ending；无行（首日/历史日期）回落 `inv_stock_daily_snapshot`，再无则 0。
7. 反结 DELETE 链在三张往来资金表之后、库存分析快照与主记录之前增加 `DELETE FROM biz_close_goods_daily WHERE close_date=?`；`biz_day_close_log` 仍只增。
8. 幂等：同日重复 close 走既有 idempotent 直接返回不重建；反结后重结走 DELETE+INSERT 安全重跑。

## 5. 接口与前端（无新增菜单/权限点/参数）

| 改动 | 说明 |
| --- | --- |
| `POST /finance/day-close/goods-daily/page` | perm `finance.day_close.view`；filters：from/to/keyword（编码/名称/条码）。区间滚算：区间内出现过的商品+仓为键，期初取首日 opening、收发与签收 SUM 全区间、期末取末日 ending；POST + PageRequest + PageResult.of，filters 下推 SQL + pagingOnly 物理分页；默认区间=今天，start>end 中文拒绝 |
| `/ticket` 返回体 | 新增 `goodsSummary`（行数/收入/发出/调整/签收数量金额/成本/毛利/期末/负库存行数）与 `goodsDaily`（当日全量行：档案快照 + 八类收发数量金额 + 签收 + 期末单价 + 勾稽/负库存标志），按仓库、期末金额、编码排序 |
| `DayClose.vue` | 定版台账加第 4 个子页签「商品收发存日余额」，懒加载，27 列；商品页签区间行显示 from~to；负库存行整行红字；查询仅在「查询」点击触发（全局约定） |
| `DayClosePrint.vue` | RJ 单新增「五、商品收发存定版」：汇总条（含负库存行红字提示）+ 14 列明细表（编码/名称/规格/仓/期初数量/收入数量金额/成本调整/发出数量金额/签收净额/毛利/期末数量金额），负库存行红字 |
| 前端接口封装 | `frontend/src/api/day-close.js` 加 `dayCloseGoodsDailyPage`，统一 apiClient |

权限盘点自动产物 `docs/perm-inventory.md` 随新端点刷新（端点 826→827，已挂注解 647→648）。

## 6. 验收证据（2026-09-14，全新隔离 H2）

方式：`mvn -o package`（JDK21）→ 删空 `backend/data/erp-smoke-dayclose2*` → 8081 启动（Redis 自动配置排除），Flyway 干净跑到 **v121** → `node development/06-testing/scripts/v1-biz-day-close-test.js` 全绿。

V121 新增断言（失败即中止，全过）：

1. ticket.goodsSummary（rowCount≥1）/ goodsDaily 数组与全字段；
2. SP001/总仓：期初 0、otherInQty=1、inQty=1、outQty=0、期末 1、收入金额/期末金额 10、qtyDiff=0、amountDiff=0、tieFlag=Y、negativeFlag=N；
3. Σin/out 金额 = ticket 主表 stockIn/OutAmount；
4. 档案快照：建 SP001 档案（瓶 / 500ml*24 / 条码 / 农夫山泉 / 饮用水 / 常温）后结账，定版行与滚算行冻结全部档案值（验证全新库无 ETL 时结账自刷维度）；
5. goods-daily/page：单日滚算行带 fromDate=toDate、数量正确、keyword 只回匹配商品；
6. 反结后商品台账 total=0（物理删除），重结恢复且 tieFlag=Y。

修过两处实现问题：①维度表依赖 ETL 时机（见 §4.1）；②ticket 明细查询漏八类收发列（定版表有值、打印/台账取空），补全 SELECT 列。

## 7. 遗留（手工验收 / 二期）

- 手工：采购/销售/调拨/盘盈盘亏多分类同跑各列归属、CGSH 不影响物账列、改名后历史快照名不变、跨 3 日滚算首日/末日取数、SQL 直改库存致恒等式不平结账被拒且无行落库、签收与实物在途差并列展示。
- 二期承诺不变：报表 #6/#7/#8 切换定版数据源 + 「定版 vs 实时重算」巡检；商品毛利日专表；定版 Excel 异步导出；批次级日终冻结（Q2 被否项，需重新立项）。

## 8. 改动文件清单

- 新增：`V121__biz_close_goods_daily.sql`、`BizGoodsCloseSnapshotService.java`、
  `docs/业务日结-商品日结补充方案.md`、本文件
- 修改：`BizDayCloseService.java`（注入/结账链/反结链/tieStock 期初/ticket 扩展/区间滚算入口）、
  `BizDayCloseController.java`（goods-daily/page）、
  `frontend/src/api/day-close.js`、`frontend/src/views/finance/DayClose.vue`、`frontend/src/views/finance/DayClosePrint.vue`、
  `docs/操作手册-业务日结.md`、`docs/perm-inventory.md`（自动生成）、
  `development/06-testing/scripts/v1-biz-day-close-test.js`
