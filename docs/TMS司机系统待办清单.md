# TMS 司机系统待办清单

> 关联需求：PRD-25 司机APP配送签收结算重构
> 所属分支：`feat/tms-driver-app-redesign`（最近提交 2026-08-20，**5 天红线 2026-08-25 前须 `git merge main` 回灌**）
> 更新时间：2026-08-20
> 说明：PRD-25 主体功能（装车/发车配送点粒度、配送点详情页、签收与结算分离、挂账+多账户混合收款、交账审核联动应收核销）已全部完成并推送至 `ebe7b99`。本清单只登记剩余两项收尾工作。

---

## 总览

| 编号 | 待办 | 预计耗时 | 依赖 | 状态 |
|---|---|---:|---|---|
| TODO-1 | 门店结算页返回上一页二次确认 | ~50 min | 无 | 待开始 |
| TODO-2 | 全链路真机验证（含交账审核联动复验） | ~2.8 h | TODO-1 完成后再打包，避免装两次包 | 待开始 |
| | **合计** | **~3.7 h** | | |

**建议执行顺序**：TODO-1 → 打包 → TODO-2。若先做 TODO-2 会因 TODO-1 的改动需要重新打包安装，多花约 15 min。

---

## TODO-1 门店结算页返回上一页二次确认（~50 min）

### 需求

司机在门店结算页已勾选单据、录入实收/拒收/收款金额后，若误触返回（AppBar 箭头或 Android 物理返回键）直接退出，本地草稿虽仍在但已填的结算态会丢失。需在存在未提交结算的情况下弹出二次确认。

### 前置调研结论（已完成，无需重复）

| 事实 | 结论 |
|---|---|
| Flutter 版本 **3.44.9**（stable, 2026-08-05） | `WillPopScope` 已弃用，**必须用 `PopScope` + `onPopInvokedWithResult`**，否则 `dart analyze` 报弃用警告 |
| `tms_driver_app/lib/` 下 `PopScope\|WillPopScope\|onPopInvoked\|canPop\|maybePop` **零匹配** | 项目无先例，从零引入，需自行确定写法 |
| 拦截点有两处：`store_detail_page.dart` L161 push `StoreSettlePage`、L569 push `DeliverySignPage` | **只拦结算页**。签收页不拦——PRD-25 阶段 3-B 的设计就是允许司机随时退出签收页，草稿已落本地库 |
| 判定条件天然存在：`LocalDbService.getSignDrafts(customerCode)` 查 `settled = 0`；结算成功才走 `deleteSignDrafts` 再 `Navigator.pop` | 无需新增字段，用页面内已有的 `_bills` / `_checked` 即可判定 |

### 改动文件

仅 1 个：`tms_driver_app/lib/ui/delivery/store_settle_page.dart`

### 子步骤

| 步骤 | 内容 | 落点 | 耗时 |
|---|---|---|---:|
| 1.1 | `build()` 的 `Scaffold` 外包一层 `PopScope`，`canPop: false` + `onPopInvokedWithResult` 回调 | L261 `build` 方法 | 15 min |
| 1.2 | 新增 `_confirmLeave()`，照抄同文件 L825 `_confirmCredit()` 的 `showDialog<bool>` + `AlertDialog` 写法保持交互一致 | `_confirmCredit` 附近 | 15 min |
| 1.3 | 新增 `_submitted` 标志位，结算成功路径（L812 `deleteSignDrafts` 之后、L817 `Navigator.pop(context, true)` 之前）置 `true`，避免提交成功后返回仍弹确认 | L800-817 | 10 min |
| 1.4 | `_bills.isEmpty`（无可结算单据）时直接放行，不打扰司机 | `_confirmLeave` 入口判断 | 5 min |
| 1.5 | `dart analyze` 校验零问题 | — | 5 min |

### 校验方式

```powershell
cd e:\work\erp-wms-tms\tms_driver_app
Remove-Item an.log -EA SilentlyContinue
dart analyze --format=machine lib 2>&1 | Out-File -Encoding utf8 an.log
(Get-Item an.log -EA SilentlyContinue).Length
```

长度为 0 或 `PathNotFound` 即 0 问题。

### 待拍板设计点

**弹窗按钮左右顺序**：

倾向「**继续结算**」放右侧（默认焦点位）、「**确认返回**」放左侧。理由：Android 物理返回键连按两次时，右侧按钮更容易被误触，把「继续结算」放右可降低误触丢草稿的概率。与 `_confirmCredit()` 的「返回修改（左）/ 确认挂账（右）」布局逻辑一致——右侧始终是「留在当前流程」或「推进主流程」。

---

## TODO-2 全链路真机验证（~2.8 h）

### 目标

验证 PRD-25 全链路：装车 → 发车 → 到店打卡 → 签收 → 门店结算（三种资金场景）→ 交账 → 后台交账审核 → 收款单自动核销 → 应收结算。

### ⚠️ 阻塞项（开工前必须先处理）

| 阻塞项 | 现状 | 处理 |
|---|---|---|
| adb 设备 ID 变更 | 已从 `127.0.0.1:5555` 变为 **`emulator-5554`** | 所有硬编码 `-s 127.0.0.1:5555` 的命令失效，须先 `adb devices` 确认 |
| 宿主机 IP 可能失效 | 历史上已从 `192.168.3.16` 变到 `192.168.0.237` | 不用重新打包：走「我的」页面的隐藏配置项（`lib/ui/common/api_base_dialog.dart`）在 APP 内直接改 baseUrl |
| 后端进程 | pid=14876 仍在 8080 监听 | 复用，无需重启；如需回滚数据则必须先停 |
| 测试数据已被消耗 | JZ202608200001 已 `APPROVED`、SK202608200001/02 已核销、6 张 AR 已 `VERIFIED` | 造全新数据，或先停后端再跑 `.tmp/rollback_settle.sql` 回滚 |

### 子步骤

| 步骤 | 内容 | 耗时 | 依赖 |
|---|---|---:|---|
| 2.0 | 环境重连：`adb devices` 确认设备 ID、确认宿主机 IP、确认后端 8080 存活 | 20 min | — |
| 2.1 | 打最新 release 包并安装 | 15 min | **TODO-1** |
| 2.2 | 造测试数据：1 张配送单 + 1 张退货单（同一门店，用于验证冲抵） | 30 min | 2.0 |
| 2.3 | 场景 A：退货冲减送货（合并结算，验证一正一负资金流水与备注格式） | 20 min | 2.2 |
| 2.4 | 场景 B：纯挂账 `creditOnly`（验证不生成收款单、只生成应收） | 15 min | 2.2 |
| 2.5 | 场景 C：混合收款（多账户）+ 结算拍照必填校验 | 25 min | 2.2 |
| 2.6 | H2 直连对账：`fin_receipt_bill` / `fin_ar` / `fin_fund_ledger` / `tms_store_settlement*` 五表数据一致性 | 20 min | 2.3-2.5 |
| 2.7 | 交账审核联动复验：后台审核交账单 → 收款单自动核销 → 应收 `VERIFIED` | 25 min | 2.6 |

### 风险点

1. **打包大概率卡住**：`flutter build apk` 在本环境稳定失败。绕法是两步走——`flutter assemble` 生成产物后再 `gradlew.bat assembleRelease`，dart-defines 传 base64 `QUxMT1dfR0FMTEVSWV9GQUxMQkFDSz10cnVl`。雷电模拟器真实 ABI 是 `x86_64`，须用 `android-x64`。
2. **release 包读不到 APP 日志**：release 包无法 `run-as` 读私有目录，模拟器内也没有 curl。只能靠「APP 界面表现 + H2 库内数据」双向印证，无法看日志定位。
3. **测试数据一次性**：核销是不可逆写操作，一轮场景跑完数据即被消耗。跑第二轮必须重新造数或回滚。

### H2 直连定式（备查）

```powershell
# 必须先停 8080 占用进程
cd e:\work\erp-wms-tms\backend
java -cp h2-2.2.224.jar org.h2.tools.RunScript -url jdbc:h2:file:./data/erp-v1 -user sa -script <脚本路径> -showResults
```

结果行以 `--> ` 前缀输出，中文会乱码（不影响数字与编码字段核对）。
