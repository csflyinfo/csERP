# WMS PDA 综合优化 — 交付说明文档

> 版本：V1.0（对应方案《WMS PDA 综合优化方案 V1.1》落地）
> 交付日期：2026-09-21
> 交付分支：`feat/wms-pda-optimization`
> 涉及端：Flutter PDA（`wms_pda_app`）+ Spring Boot 后端
> 文档对象：开发、测试、实施及业务负责人

---

## 一、交付概述

本次交付围绕仓库 PDA「扫码即作业、减少跳转、防止误触、视觉统一」四大目标，
在不引入任何新第三方依赖、不破坏既有 RBAC 权限体系的前提下，完成
**6 大功能模块**（含业务追加的数字输入与盘点建任务），共 9 次提交，
改动 24 个文件，约 **+3650 / −401** 行。

核心收益：

- 关键数量输入改为**大按钮数字键盘**，适配手套与扫码枪，不再弹系统软键盘；
- 盘点支持 **PDA 端建任务 + 待盘列表 + 任务详情**闭环；
- 任意页面可一键扫码，按条码**智能路由**直达目标单据；
- 底部 3 Tab 导航 + 任务聚合看板，待办一目了然；
- 关键操作两段式确认 + 提交去重，**降低误操作导致的数据错误**；
- 首页统一为 4 列网格，布局规整一致。

---

## 二、功能清单与优先级

| # | 模块 | 优先级 | 状态 |
|---|---|---|---|
| 1 | 大按钮数字键盘与多单位混合输入 | P0 | ✅ 已完成 |
| 2 | 盘点 PDA 端建任务与待盘列表 | P0 | ✅ 已完成 |
| 3 | 全局扫码入口 + 底部导航 | P0 | ✅ 已完成 |
| 4 | 扫码智能路由 + 侧边硬件键监听 | P0 | ✅ 已完成 |
| 5 | 任务聚合看板（轮询 / 优先级 / 最近完成） | P0 | ✅ 已完成 |
| 6 | 防误触（确认按钮 / 手套模式 / 二次确认 / 提交去重） | P1 | ✅ 已完成 |
| 7 | 统一 4 列网格布局 | P2 | ✅ 已完成 |

---

## 三、各模块详细说明

### 3.1 大按钮数字键盘与多单位输入（P0）

**解决问题**：原有数量输入调用系统软键盘，按键小、戴手套易误触，
且扫码枪扫入后不能自动累加；多单位商品缺乏换算对照。

**交付内容**：

- `widgets/qty_pad_sheet.dart`：底部弹出式大按钮数字键盘。
  - 4×4 布局，按键高度大、字号大；
  - 含 +1/−1 快捷键、清空、退格、小数点、确认；
  - 支持整数 / 小数与最小值约束。
- `widgets/multi_unit_qty_field.dart`：数量显示组件，点击弹键盘。
  - 当明细带 `convertQty / baseUnit` 时展示多单位换算；
  - 字段缺失时退化为纯数量输入器（向后兼容）。
- 已接入页面：**收货、盘点、拣货、移库、报损** 共 5 个数量输入位置。
- 拣货页以 `allocQty − pickedQty` 作为本次上限，防止超拣。

### 3.2 盘点 PDA 端建任务与待盘列表（P0）

**解决问题**：原盘点只能「输单号进明细」，PDA 无法主动建任务、
看不到自己的待盘任务。

**交付内容**：

- 后端在 PDA 端点新增 **list / detail / create** 三个接口，
  封装既有盘点能力，并按 `wms_pda.stocktake.*` 功能点鉴权。
- `ui/stocktake_create_page.dart`：建任务表单。
  - 盘点类型（动盘 / 循环盘 / 抽盘）、冻结库位、范围库位、备注；
- `ui/stocktake_page.dart` 重构为**列表态 + 详情态**：
  - 状态 Tab（盘点中 / 待审核 / 已审核 / 待盘）；
  - 任务卡片，点击进明细；
  - 保留「扫任务号直达详情」快捷路径；
  - FAB 新建任务（受 `stocktake.create` 权限控制）。

### 3.3 全局扫码入口与底部导航（P0）

**解决问题**：原所有页面无独立扫码入口，每次需先进功能页，多 2–3 步。

**交付内容**：

- `ui/home_page.dart` 重构为 `IndexedStack` + `BottomAppBar`：
  - **首页 / 任务 / 我的** 三个 Tab；
  - 中央凸起 FAB 扫码按钮（docked，任意 Tab 可弹）。
- `widgets/global_scan_sheet.dart`：扫码面板。
  - 顶部智能识别输入区；
  - 下方按权限裁剪的手动快捷入口。

### 3.4 扫码智能路由与硬件键（P0）

**交付内容**：

- 后端新增 `POST /wms/app/barcode/identify`：
  - 在**当前作业仓**范围内按 9 级优先级反查条码：
    **库位 → 容器 → 入库任务 → 波次 → 盘点 → 移库 → 补货 → 报损 → 商品**；
  - 返回 `{type, id, title, subtitle, route, params}`；
  - 未命中返回 `type=unknown`，由前端提示手动选择。
- 目标页支持接收路由参数直达：
  - 盘点 / 收货：`taskId` 直接进明细；
  - 拣货：`waveId` 自动匹配任务；
  - 库存查询：`keyword / binCode` 预填并查询。
- `services/hardware_scan_key.dart`：
  - 全局监听侧边扫码键（L1/R1/L2/R2/CAMERA），按下弹扫码面板；
  - 含防抖，面板打开时不重复触发；
  - 通过全局 `navigatorKey` 取上下文，任何页面都生效。

> 真机注意：不同 PDA 厂商按键映射存在差异。如某型号侧边键无响应，
> 提供该按键的 keyId 即可加入监听集合。

### 3.5 任务聚合看板（P0）

**交付内容**（`ui/task_board_page.dart`）：

- 顶部汇总 Chip：按权限展示各类型待办数量；
- Tab 切换：全部 / 各业务类型；
- **30 秒静默轮询**（不显示 loading、不打断操作），页面销毁自动停止；
- **优先级三级**：
  - `urgent` 加急（红）：加急标志为真；
  - `normal` 常规（黄）：已认领 / 进行中；
  - `low` 一般（绿）：无紧迫状态；
  - 卡片左侧色条 + 圆点标签，列表按优先级排序，加急置顶；
- 空态显示「最近完成」记录（每类最多 2 条，共 6 条）。

### 3.6 防误触机制（P1）

**交付内容**：

- `widgets/confirm_button.dart`：**两段式确认按钮**。
  首次点击变「再点确认」并震动，3 秒窗口内二次点击才执行，
  执行期间 loading 禁用；
- `widgets/confirm_dialog.dart`：**不可逆操作二次确认弹窗**，
  显示单号 / 商品 / 数量摘要；
- `services/submit_guard.dart`：**提交去重**，
  同指纹（接口 + 关键参数）3 秒内或在执行中一律拒绝；
- `config/glove_mode.dart`：**手套模式**（默认开启、持久化），
  开启后按钮热区 48→58、字号 14→16；
- 设置页新增手套模式开关。
- 已在报损提交链路完整落地（ConfirmButton + SubmitGuard）。

### 3.7 统一 4 列网格（P2）

**交付内容**（`widgets/function_card_grid.dart`）：

- 固定 4 列、卡片正方形、间距统一；
- 末行不足 4 个自动补**透明占位**，模块视觉对齐；
- 保留右上角角标；
- 首页各分区接入，删除旧的三列网格实现（净减少重复代码）。

---

## 四、后端接口变更

| 接口 | 方法 | 权限点 | 说明 |
|---|---|---|---|
| `/wms/app/stocktake/list` | POST | `wms_pda.stocktake.view` | 盘点任务列表 |
| `/wms/app/stocktake/detail` | POST | `wms_pda.stocktake.view` | 盘点主表 + bins |
| `/wms/app/stocktake/create` | POST | `wms_pda.stocktake.create` | PDA 建盘点任务 |
| `/wms/app/barcode/identify` | POST | `wms_pda.home.scan` | 条码智能识别路由 |

所有接口：

- 仅接受 `appType=WMS_PDA` 且选择作业仓的令牌（PdaAppGuard 拦截其余令牌）；
- 操作人只取当前登录自然人，不信任请求体 operator；
- 查询强制当前仓库隔离。

本次**无数据库结构变更（无 Flyway 迁移）**，全部基于既有表与盘点能力。

---

## 五、改动文件清单

### 后端（2 个文件）

- `backend/.../wms/WmsAppController.java`（+36）
- `backend/.../wms/WmsInternalService.java`（+230）

### Flutter PDA（22 个文件）

**新增（11 个）**

```
lib/config/glove_mode.dart
lib/services/hardware_scan_key.dart
lib/services/submit_guard.dart
lib/ui/stocktake_create_page.dart
lib/ui/task_board_page.dart
lib/widgets/confirm_button.dart
lib/widgets/confirm_dialog.dart
lib/widgets/function_card_grid.dart
lib/widgets/global_scan_sheet.dart
lib/widgets/multi_unit_qty_field.dart
lib/widgets/qty_pad_sheet.dart
```

**修改（11 个）**

```
lib/config/pda_perms.dart
lib/main.dart
lib/services/wms_app_service.dart
lib/ui/damage_page.dart
lib/ui/home_page.dart
lib/ui/move_page.dart
lib/ui/pick_page.dart
lib/ui/receive_page.dart
lib/ui/settings_page.dart
lib/ui/stock_query_page.dart
lib/ui/stocktake_page.dart
```

合计：24 文件，+3650 / −401 行。

---

## 六、提交记录

| Commit | 说明 |
|---|---|
| `6217c04` | PDA 大按钮数字键盘与多单位换算展示（P0-1） |
| `30869a6` | 数字键盘推广到盘点/拣货/移库/报损 |
| `0534fb4` | P0-2 盘点 PDA 端建任务与列表 |
| `c85afb3` | P0-3 全局扫码入口与底部导航 |
| `db05be2` | P0-4 任务聚合看板 |
| `6bc459e` | 扫码智能路由与侧边硬件键全局监听 |
| `b072faa` | 看板自动轮询、优先级标记与空态最近完成 |
| `fe9d8c9` | P1 防误触：确认按钮 / 手套模式 / 二次确认 / 提交去重 |
| `db1cce7` | P2 统一四列网格布局 |

---

## 七、验证情况

| 验证项 | 命令 | 结果 |
|---|---|---|
| Flutter 静态检查 | `flutter analyze wms_pda_app` | ✅ No issues found |
| 后端编译 | `mvn -DskipTests compile` | ✅ BUILD SUCCESS |

> 说明：本次以静态检查与编译验证为主。涉及真机的交互
> （侧边硬件键、扫码路由、两段确认震动等）需在设备上实测。

---

## 八、测试 / 验收建议

建议测试与实施按下列要点在真机或模拟器逐项验证：

**数量输入**

- [ ] 点击数量区弹出大键盘，不弹系统软键盘；
- [ ] 数字、小数点、+1/−1、清空、确认行为正确；
- [ ] 多单位商品展示换算；拣货不可超拣。

**盘点**

- [ ] 可新建盘点任务并出现在列表；
- [ ] 状态 Tab 过滤正确；点任务 / 扫任务号进明细；
- [ ] 无 `stocktake.create` 权限时不显示新建入口。

**扫码与导航**

- [ ] 中央扫码按钮各 Tab 可弹；
- [ ] 扫库位 / 容器 / 各类单号 / 商品条码跳转正确页面；
- [ ] 扫不存在条码提示手动选择；
- [ ] 侧边硬件键可触发（如不响应记录 keyId）。

**看板**

- [ ] 待办数量与类型正确；加急任务置顶、颜色正确；
- [ ] 约 30 秒自动刷新；空态显示最近完成。

**防误触**

- [ ] 报损等需二次点击确认；
- [ ] 快速重复提交被拦截；
- [ ] 手套模式开关生效（热区 / 字号变化）。

**网格**

- [ ] 各分区统一 4 列、卡片大小一致；不足 4 个有占位。

---

## 九、已知限制与后续建议

1. **优先级基于加急标志与状态推导**：当前任务表无可靠「截止时间」字段，
   方案原规则（<2h / <8h）暂以状态替代；补字段后只需改各类型判定，UI 不动。
2. **手套模式未做全局多点触控硬拦截**：已实现热区 / 字号放大并提供全局开关状态，
   未强制拦截多点触控以避免误伤列表滚动；如需可在具体手势上按开关判断。
3. **硬件键机型差异**：监听集合已兜底常见键值，特殊机型按需扩展。
4. **条码前缀规则仍在后端识别逻辑内**：未来若需仓库现场可配，
   可增加条码规则配置表（方案中已提及）。
5. **建议后续**：真机回归通过后，由具备权限的环境执行分支合并与推送，
   并视需要发布新的 release APK。

---

## 十、发布与回滚

- **合并**：测试通过后，将 `feat/wms-pda-optimization` squash / 常规合并至 `main`。
- **回滚**：本次无数据库迁移，回滚仅需还原代码版本，不涉及数据结构与数据修复。
- **推送**：当前环境因本机 Git 凭证限制未自动推送，需由有权限环境执行。

---

*本交付说明与代码提交一一对应，如功能与描述存在出入，以最终合并代码为准。*
