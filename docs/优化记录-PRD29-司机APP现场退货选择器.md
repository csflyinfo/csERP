# 优化记录 — PRD-29 司机APP现场退货客户/仓库/商品选择器

- **PRD 编号**：PRD-29
- **需求文档**：[29-V2.0-司机APP现场退货客户仓库商品选择器PRD.md](./PRD-版本化产品需求/V2.0-仓配一体版/29-V2.0-司机APP现场退货客户仓库商品选择器PRD.md)
- **Flyway 区段**：无（纯接口与前端，复用现有表结构）
- **日期**：2026-09-01
- **分支**：`feat/tms-return-picker`（从 main 拉出，squash 合回后删除）
- **状态**：已完成，隔离库 28 项接口断言全部通过

## 1. 背景

司机「现场退货」页（`DriverReturnCreatePage`）四个实际问题：

1. 客户名称靠手输，还要手填「客户编号」——司机记不住编号，名称随手写与档案对不上；
2. 收货仓库靠手输，仓库名写错后退货单无法匹配实物仓；
3. **商品关键字搜索直接报错**（用户现场反馈「现在查询商品报错」），功能全堵；
4. 退货商品不支持条码，PDA 扫枪无用武之地。

## 2. 根因分析（商品搜索 500）

`TmsReturnController.goodsSearch` 旧实现用 **Java 文本块（`"""`）拼接 SQL 片段**。文本块会按定界符缩进裁掉每行开头空白，**同时裁掉行尾尾随空白**。跨文本块拼接时：

```java
"... FROM inv_stock_balance s " + cond + """
        ON ...
        WHERE ...
        """
```

`cond` 片段与后续文本块之间的换行/空格被整体裁掉，SQL 被粘成
`...inv_stock_balance sON ...g.goods_codeWHERE...`，H2 抛 `JdbcSQLSyntaxErrorException`，
经 `GlobalExceptionHandler` 包装为 code=500「系统繁忙，请稍后重试」。

**取证方式**：对旧编译产物 `.class` 二进制 grep 到粘连字符串 `sON` / `codeWHERE`；
将同样的 SQL 补上空格后在 H2 Shell 手工执行成功，确认为拼接问题而非表结构问题。

> ⚠️ 教训沉淀：**SQL 字符串一律用普通 `"..."` 拼接并显式写空格，禁止用文本块拼 SQL 片段**。
> 文本块只适合整块、完整、不与外部片段拼接的文本。

## 3. 变更内容

### 3.1 后端 `TmsReturnController.java`

| 接口 | 变更 |
|---|---|
| `POST /tms/app/return/goods-search` | **修复 500**：SQL 改普通字符串拼接（空格显式写出）；去掉 `inv_stock_balance` JOIN（客户门口退货无「本店库存」语义，且按批次 JOIN 把同一商品扇出多行）；过滤 `status='NORMAL'` 且 `can_return=TRUE`；模糊匹配增加 `simple_code`（拼音码）；`ORDER BY CASE WHEN barcode=? THEN 0 WHEN goods_code=? THEN 1 ELSE 2 END` 让条码/编码精确命中排最前，供扫码自动选中。返回 `[{goodsCode, goodsName, spec, unitName, barcode, price}]`。 |
| `POST /tms/app/return/customer-search`（新增） | 入参 `keyword?/longitude?/latitude?`。有关键字：名称/编码/收货地址/电话模糊，≤30 条；无关键字：取档案（上限 500）在 Java 侧按 Haversine 球面距离排序返回最近 10 个，无坐标客户排最后、按名称兜底。地址/坐标优先取 `base_customer_address` 默认地址子表（`is_default=1`），回落 `base_customer` 冗余列。返回 `[{customerCode, customerName, address, distanceKm?}]`（distanceKm 保留 1 位小数）。 |
| `POST /tms/app/return/warehouse-list`（新增） | `WHERE COALESCE(status,'NORMAL')='NORMAL' AND COALESCE(warehouse_type,'实物仓') <> '虚拟仓'`；历史 NULL /「正常仓」按实物仓处理。返回 `[{warehouseCode, warehouseName, warehouseType}]`。 |

无 Flyway 迁移：`base_goods(barcode/simple_code/can_return)`、`base_customer(longitude/latitude/shipping_address)`、`base_customer_address`、`base_warehouse(warehouse_type/status)` 列均已存在。

### 3.2 Flutter 司机 APP（`tms_driver_app/`）

- `models/driver_return.dart`：`GoodsSearchResult` 增 `barcode`；新增 `CustomerSearchResult`、`WarehouseOption` 模型。
- `providers/driver_return_provider.dart`：新增 `returnCustomerSearchProvider`（family 参数 `CustomerSearchArgs{keyword, longitude?, latitude?}`，实现 `==`/hashCode 保证 Riverpod 缓存正确）、`returnWarehouseListProvider`。
- `ui/return/driver_return_create_page.dart` 重写：
  - **删除「客户编号」输入框**；客户名称改为 `_PickerTile` 下拉控件，弹 `_CustomerPickerSheet`：打开即静默定位（Geolocator medium 精度 8s 超时，权限拒绝/无 GPS 全部静默降级），无关键字展示最近 10 个客户（名称 + 橙色 `x.xkm` 距离徽标 + 单行省略地址），搜索框 350ms 防抖走模糊查询；`customerCode` 仅存状态用于建单，界面不再展示；
  - 收货仓库同为下拉，弹 `_WarehousePickerSheet` 只列实物仓；
  - 商品搜索框占位「扫码 / 输入名称·编码·条码」，下拉行显示**商品名称 + 规格 · 单位**（不再显示编码与价格）；右侧【扫码】按钮弹条码录入框（autofocus）：**PDA 硬件扫枪以键盘楔入方式把条码打进焦点框并回车**，`onSubmitted` 即一次扫码；搜索框本身回车也直扫。条码/编码精确命中 → 直接加入清单并 toast「✅ 已添加：xxx」（已在清单则数量 +1）；未精确命中但有模糊结果 → 回填关键字列出相似商品；完全无结果 → 明确提示。

### 3.3 为什么不引相机扫码

项目规范本阶段不引入新外部依赖。PDA 安卓手持终端的硬件扫头普遍以**键盘楔入（keyboard wedge）**模式工作（扫码 = 往焦点框输入条码 + 回车），条码录入框 + 回车直查即复用全部现有能力；普通手机可手动输入条码。相机图像扫码留待后续统一引入扫码组件时覆盖。

## 4. 影响范围

- 后端：仅 `TmsReturnController.java`（现场退货三个接口）；无表结构变更、无迁移；其它模块零影响。
- APP：仅现场退货创建页及其 model/provider；登录/配送/签收等流程不动。
- 建单提交口径不变：仍提交 `customerCode`（内部状态）与 `warehouseName`，与库存/退货单仓库口径一致。

## 5. 验证方式与结果

隔离验证（不碰开发库与生产数据）：

1. 拷贝受控种子库到 `E:/work/.tmp-verify/v90db`，Flyway 69 个迁移正常应用至 v89；
2. 用 UTF-8 JDBC seeder（`V90Seeder.java`，绕开 H2 Shell 在 Windows 按 GBK 读 stdin 的坑）灌入种子：1 个司机、5 个客户（3 个有坐标由近到远 + 2 个无坐标）、4 个商品（带条码/无条码/停用/不可退）、4 个仓库（实物仓/正常仓/虚拟仓/停用）；
3. 后端以 `--server.port=8081` + 显式临时库 JDBC URL 启动；
4. `node v90-verify.js` 跑 **28 项断言全部通过**，覆盖：
   - 商品搜索不再 500；名称/条码/编码命中与排序（条码精确排首位）；停用商品、`can_return=FALSE` 商品及其条码均不出现；无重复行；空关键字安全返回空数组；
   - 客户最近列表：有坐标按距离升序（0.2km → 146.8km）、无坐标排后、地址优先取默认地址子表、无坐标客户无 distanceKm；无 GPS 按名称兜底；名称/地址/编码模糊命中；
   - 仓库下拉：实物仓与历史「正常仓」列出，虚拟仓、停用仓不出现，行含编码与名称。
5. `flutter analyze`：**No issues found!**；后端 `mvn -o compile` EXIT=0。

### 验证中发现并修复的二次缺陷

首版修复 SQL 中 `ORDER BY CASE WHEN barcode=? THEN 0 WHEN goods_code=? ELSE 2 END` **漏写第二个 `THEN 1`**，H2 在 `?` 后期望 `THEN` 却遇到 `ELSE` 报 42001 语法错误（8081 日志堆栈定位）。补上 `THEN 1` 后重编译重启，28 项断言全过。

## 6. 风险与回滚

- 无 Flyway、无表结构变更，回滚只需回退代码提交；
- 商品搜索去掉了库存 JOIN：退货选择商品本就不应受「当前库存」限制（现场退的是客户手上的货），行为更符合业务；
- 客户坐标大量为空时下拉不会空白：无坐标客户按名称兜底排在后面；
- 定位权限拒绝/无 GPS 不阻塞：静默降级为名称列表。
