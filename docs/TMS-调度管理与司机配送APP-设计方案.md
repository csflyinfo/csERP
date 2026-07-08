# TMS 调度管理与司机配送 APP 设计方案

> **版本**：V1.0  
> **日期**：2026-08-10  
> **适用系统**：ERP-WMS-TMS 商贸经销商管理系统  
> **目标读者**：产品经理、系统架构师、开发负责人  
> **关联文档**：销售退货全链路设计方案 V1.0、销售退货-开发方案-无司机流程 V1.0、B2B-ERP-WMS 三系统架构设计 V5.0  
> **现有参考**：`prototype/tms-driver/index.html`（TMS 司机 APP 原型）、`docs/architecture/01-系统架构设计.md`（TMS 数据模型规划）  
> **实现参考**：`TransferController` + `V45__transfer_flow.sql`（多阶段单据流转的最接近参考实现）

---

## 目录

1. [业务背景与设计目标](#1-业务背景与设计目标)
2. [系统架构总览](#2-系统架构总览)
3. [模块一：ERP 端 - 调度管理](#3-模块一erp-端---调度管理)（含改派返仓、客户拒收单）
4. [模块二：Flutter 司机配送 APP](#4-模块二flutter-司机配送-app)
5. [模块三：司机退货回收全流程](#5-模块三司机退货回收全流程)
6. [模块四：交账与结算](#6-模块四交账与结算)
7. [模块五：门店定位管理](#7-模块五门店定位管理)
8. [数据库设计](#8-数据库设计)
9. [API 接口设计](#9-api-接口设计)
10. [性能设计方案](#10-性能设计方案)
11. [安全设计](#11-安全设计)
12. [开发排期建议](#12-开发排期建议)

---

## 1. 业务背景与设计目标

### 1.1 业务背景

商贸经销商的日常配送业务中，典型的作业流程为：

```
仓库拣货 → 按线路装车 → 司机发车 → 逐店配送 → 门店签收 → 结算交账
```

当前系统已具备：
- 销售订单 → 销售出库单 → **销售发货单**（`sales_receipt`，出库审核后自动 1:1 生成）——这是司机配送的**直接来源单据**
- 基础资料：片区（`base_territory`）、线路（`base_route_line`，已含 `driver` 字段）、员工（`base_employee.is_deliveryman` 标记）
- 销售退货无司机流程（`sales_return_apply` → `sales_return_inbound` → `sales_return`）

**缺失能力**：
- ERP 端：调度排线、装车确认、发车管理、在途跟踪、签收核销
- 移动端：司机配送 APP（装车确认、门店签收、拍照、退货、交账）
- **异常流程**：客户不在/地址错误等导致的改派返仓、客户拒收导致的拒收入库
- **司机退货回收**：销售退货单通过司机配送系统上门回收的完整闭环

### 1.2 设计目标

| 目标 | 说明 |
|------|------|
| **调度可视化** | ERP 端支持按片区/线路排线，将**销售发货单**分配到车辆/司机，生成配送任务 |
| **全链路追踪** | 装车→发车→配送→签收→交账，每个节点可追溯、可拍照留证 |
| **司机 APP 轻量化** | Flutter 跨平台开发，兼容 Android/iOS，操作简单，离线可用 |
| **退货回收闭环** | 销售退货单通过司机配送系统上门回收 → 随车返仓 → 仓库验收 → 入库入账，全链路闭环 |
| **异常流程闭环** | 客户不在/地址错误改派返仓、客户拒收生成拒收入库单，均纳入系统管控，货物不丢失 |
| **高性能** | 支持日均万单级配送量，图片增量同步，接口响应 < 200ms（P99） |

---

## 2. 系统架构总览

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                      ERP 后台 (Vue 3)                            │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐  │
│  │ 调度排线  │  │ 装车发车  │  │ 在途监控  │  │ 签收核销/交账 │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └───────┬───────┘  │
│       │             │             │                  │           │
├───────┴─────────────┴─────────────┴──────────────────┴───────────┤
│                    REST API (Spring Boot)                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐  │
│  │ tms/     │  │ inventory│  │ sales/   │  │ finance/      │  │
│  │ dispatch │  │ outbound │  │ receipt  │  │ settlement    │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └───────┬───────┘  │
│       │             │             │                  │           │
│  ┌────┴─────────────┴─────────────┴──────────────────┴───────┐  │
│  │                   H2 / MySQL 数据库                        │  │
│  │  tms_dispatch | tms_dispatch_detail | tms_delivery_trip   │  │
│  │  tms_sign_photo | tms_settlement | tms_driver_return     │  │
│  │  tms_store_location | tms_driver_location                │  │
│  └──────────────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                    MinIO / OSS 图片存储                          │
│  签收照片 | 结算照片 | 退货照片 | 门店照片                       │
├─────────────────────────────────────────────────────────────────┤
│              司机配送 APP (Flutter 3.x)                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐  │
│  │ 任务列表  │  │ 装车确认  │  │ 门店签收  │  │ 退货/交账     │  │
│  └──────────┘  └──────────┘  └────┬─────┘  └───────────────┘  │
│                           ┌───────┴───────┐                    │
│                           │ 拍照 (Camera) │                    │
│                           │ 定位 (GPS)    │                    │
│                           │ 离线缓存(SQL) │                    │
│                           └───────────────┘                    │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 技术选型

| 层次 | 技术 | 说明 |
|------|------|------|
| ERP 前端 | Vue 3 + Element Plus | 复用现有架构，新增 TMS 模块视图 |
| 后端 API | Spring Boot + JdbcTemplate | 复用现有架构，新增 `com.erp.tms` 包 |
| 数据库 | H2（开发）/ MySQL（生产） | 与现有保持一致 |
| 图片存储 | MinIO（自建）/ 阿里云 OSS | 签收照、结算照、退货照 |
| 司机 APP | Flutter 3.x + Dart | 跨平台 Android/iOS |
| APP 状态管理 | Riverpod 2.x | Flutter 社区推荐的状态管理方案 |
| APP 本地存储 | drift (SQLite) | 离线缓存配送任务与照片 |
| APP 网络层 | dio + retrofit | HTTP 请求与图片上传 |
| APP 地图 | amap_flutter / google_maps_flutter | 门店定位与导航 |
| APP 相机 | camera + image_picker | 签收/结算/退货拍照 |
| 消息推送 | 极光推送 / Firebase Cloud Messaging | 新任务通知司机 |

---

## 3. 模块一：ERP 端 - 调度管理

### 3.1 功能清单

```
调度管理
├── 3.1.1 配送任务池（待调度发货单列表）
├── 3.1.2 排线调度（发货单分配到车辆/司机）
├── 3.1.3 装车确认（ERP 端查看/确认装车状态）
├── 3.1.4 发车管理
├── 3.1.5 在途监控（GPS 轨迹 + 配送进度）
├── 3.1.6 签收核销
├── 3.1.7 改派返仓管理
├── 3.1.8 客户拒收单管理
└── 3.1.9 调度看板（可视化大屏）
```

### 3.1.1 配送任务池

**入口**：ERP 左侧菜单 → 配送管理 → 配送任务池

**数据来源**：`sales_receipt`（销售发货单），出库单审核后自动 1:1 生成。发货单是司机配送的**直接依据**——它记录了每个客户应收到的商品明细、金额，以及当前的收款状态。

> **为什么是发货单而非出库单？**
> 出库单代表仓库已拣货出库，发货单代表「这批货要送到哪个客户手上」。配送调度的本质是「把发货单分配给司机去送达」，因此以发货单为调度粒度是正确的。一个出库单对应一个发货单（1:1），关系明确。

**功能**：
- 展示所有「已审核（APPROVED）、`receive_status` = 未收款/部分收款、未分配调度」的销售发货单
- 支持按片区（`territory`）、线路（`route_line`）、客户、发货日期等筛选
- 支持批量选择后一键生成调度单
- 列表展示：发货单号、来源出库单号、客户名称、客户地址、片区、线路、SKU数、件数、应收金额、发货日期

**操作**：
- `生成调度单`：勾选发货单 → 选择车辆/司机 → 确认生成
- `查看详情`：弹出关联出库单 + 发货单详情（商品清单、客户信息）
- `暂缓配送`：标记为暂缓，移出任务池（不改变发货单状态，仅从任务池过滤）

### 3.1.2 排线调度

**入口**：配送管理 → 排线调度

**核心逻辑**：

```
1. 左侧：待调度发货单池（树形：按片区→线路→客户分组）
2. 右侧：车辆/司机资源池（按线路分组，显示司机姓名、车辆牌号、已分配件数/订单数）
3. 中间：拖拽或勾选模式，将发货单分配到车辆

生成调度单时：
- 一个调度单 = 一车一次 = 一个 tms_dispatch 记录
- 调度单包含多条 tms_dispatch_detail（每条对应一个 sales_receipt 发货单）
- 调度单自动生成配送顺序（可按客户地址手动调整）
```

**调度单状态机**：

```
DRAFT（草稿）
  → DISPATCHED（已调度/待装车）：分配司机后
  → LOADING（装车中）：司机 APP 开始装车
  → LOADED（已装车）：司机 APP 确认装车完成
  → DEPARTED（已发车）：司机 APP 点击发车
  → DELIVERING（配送中）：有门店签收
  → COMPLETED（已完成）：全部签收 + 交账完成
  → CANCELLED（已取消）：调度取消（需回退发货单调度状态）

异常状态：
  → PARTIAL（部分完成）：部分签收 + 部分拒收/退货 + 部分改派返仓
  → RESCHEDULED（改派返仓）：客户不在，生成改派返仓单，验收后回调度池重新派送
  → REJECTED（客户拒收）：客户拒收，生成客户拒收单，仓库收货生成拒收入库单

未配送子状态（针对单个发货单/行程）：
  → UNDELIVERABLE（无法配送）：地址错误/联系不上 → 走改派返仓流程
  → RESCHEDULED（改派返仓中）：改派返仓单已生成，货物随车待返仓验收
  → RESCHEDULED_CHECKED（改派返仓已验收）：仓库验收完成，发货单回调度池待重新派送（不反审核出库单）
  → REJECTED（客户拒收）：客户拒收，走客户拒收单流程 → 仓库收货生成拒收入库单 → 库存 +N
```

**调度单与已有单据的关系**：

```
销售订单 (sales_order)
  └→ 销售出库单 (sales_outbound)  ← 仓库拣货出库
       └→ 销售发货单 (sales_receipt)  ← 出库审核后自动生成（1:1），TMS 调度的直接来源
            └→ 调度明细 (tms_dispatch_detail)  ← 发货单被分配到调度单
                 └→ 配送行程 (tms_delivery_trip)  ← 司机逐店配送记录
                      ├→ 签收记录 (tms_sign_record)  ← 门店签收确认
                      │    └→ 发货单 receive_status 更新（未收款→部分收款→已收款）
                      ├→ 司机退货 (tms_driver_return)  ← 门店现场退货
                      │    └→ sales_return_apply  ← 自动联动生成退货申请
                      ├→ 客户拒收 (tms_customer_reject)  ← 客户拒收，仓库收货生成拒收入库单
                      └→ 改派返仓 (tms_reschedule_return)  ← 客户不在/地址错误（不反审核出库单，验收后回调度池）
```

### 3.1.3 装车确认（ERP 视角）

ERP 端可查看司机 APP 上报的装车状态：
- 装车开始时间、装车完成时间
- 装车照片（可选，司机拍车辆满载照）
- 实际装车件数 vs 调度计划件数（差异告警）
- 支持 ERP 端远程确认装车（司机无 APP 时的降级方案）

### 3.1.4 发车管理

- 司机 APP 点击「发车」后，ERP 端实时更新状态
- 发车时记录：发车时间、起始里程数（如有）、车辆照片
- ERP 可查看今日所有发车车辆列表

### 3.1.5 在途监控

**GPS 轨迹**：
- 司机 APP 每 30 秒上报一次位置（后台静默上报）
- ERP 端地图实时显示车辆位置
- 历史轨迹回放（可选，按需存储）

**配送进度**：
- 可视化的门店签收进度条：`已签收 8/12 店`
- 每个门店的状态标记：待配送 / 配送中 / 已签收 / 异常
- 预计完成时间（基于历史配送速度估算）

### 3.1.6 签收核销

- 司机 APP 签收后，更新 `sales_receipt.receive_status`（未收款 → 部分收款 → 已收款）
- 签收数量 vs 发货数量差异处理：
  - 全部签收 → 发货单 `receive_status` 更新，后续收款流程复用现有 `fin_ar` 核销
  - 部分签收 → 标记差异，剩余数量可选择改日配送或返仓
  - 全部拒收 → 整单进入客户拒收单流程（仓库收货生成拒收入库单）
- 签收照片在 ERP 端可查看/下载

### 3.1.7 改派返仓管理

**触发场景**（客户不在/需改天再送，货物需重新配送）：
- 客户不在（外出/放假/门店关门无人收货）
- 地址错误（客户搬迁、导航定位偏差找不到门店）
- 联系不上（电话无人接听）
- 客户要求改天再送

**核心原则**：改派返仓**不反审核出库单**，库存不变（出库单保持已审核，货物暂存待重新派送）。

**流程**：

```
司机 APP 端：
1. 在配送行程中对该门店选择「改派返仓」
2. 选择原因：客户不在 / 地址错误 / 联系不上 / 客户要求改期 / 其他
3. 拍照留证（门头照/关门店照）
4. 填写备注 + 期望改送日期（默认次日）
5. 确认 → 生成【改派返仓单】（GPRC前缀），行程状态变为 RESCHEDULED
6. 货物随车继续配送其他门店，最终随车返回仓库

仓库验收（ERP + APP）：
7. 司机回到仓库 → APP 点击「返仓交接」
8. 仓库人员清点改派返仓货物（按原发货单逐品核对）
9. 确认实收数量 → 改派返仓单状态变为 CHECKED
10. 系统自动处理（验收后，不反审核出库单、不生成入库单）：
    a. 更新原 sales_receipt 配送状态为「待改派」
    b. 原发货单进入调度池，可重新安排派送
    c. 单据标记【返仓改派】
    d. 记录改派历史（支持多次改派，防无限延期）
```

**关键点**：
- 改派返仓**不涉及库存变动**（出库单保持已审核，库存扣减状态不变）
- 仓库验收仅确认货物数量完好，不生成入库单
- 验收后系统层面发货单回到调度池，可重新排线派送
- 原"改日配送"流程并入本节：改派返仓验收后即等同改日配送，回调度池等待重新派送

### 3.1.8 客户拒收单管理

**触发场景**（客户收货时拒收，货物退回仓库入库）：
- 客户明确拒收（"这批货我昨天已经退订了"）
- 货物破损无法签收
- 品种/数量不符客户拒收

**核心原则**：客户拒收的货物回仓入库，库存增加。

**流程**：

```
司机 APP 端：
1. 在配送签收环节选择「客户拒收」
2. 选择拒收原因：客户拒收 / 货物破损 / 品种不符 / 数量不符 / 其他
3. 拍照留证（拒收商品照/破损照）
4. 逐品登记拒收数量
5. 确认 → 生成【客户拒收单】（KHJS前缀），行程状态变为 REJECTED
6. 货物随车返回仓库

仓库收货（ERP + APP）：
7. 司机回到仓库 → APP 点击「返仓交接」
8. 仓库人员清点拒收货物（按拒收单逐品核对）
9. 确认实收数量 → 生成【拒收入库单】（JHRK前缀）
10. 系统自动处理（仓库收货后）：
    a. 拒收入库单审核 → 库存增加（库存 +N）
    b. 标记原 sales_receipt 对应明细为拒收
    c. 撤销对应应收（若已生成）
    d. 写审计日志
```

**关键点**：
- 客户拒收单仓库收货后生成拒收入库单，库存增加
- 拒收入库单复用现有入库流程机制
- 与司机退货回收（模块三）区别：拒收是未签收商品退回，退货回收是已签收商品退货

> **流程拆分说明**：原"未配送返仓"统一三选一处理（返仓入库/改日/作废）拆分为上述两类独立单据——改派返仓不动库存、验收后回调度池；客户拒收单仓库收货生成拒收入库单、库存增加。

可视化大屏，展示当日配送全局视图：
- 今日配送总览：总单数、总件数、已完成、配送中、待配送
- 车辆实时位置地图
- 司机配送效率排行
- 签收率趋势图
- 异常告警（超时未签收、GPS 离线等）

---

## 4. 模块二：Flutter 司机配送 APP

### 4.1 APP 功能架构

```
司机配送 APP
├── 登录/认证
├── 首页（今日任务概览）
├── 配送任务列表
│   ├── 📦 送货任务（按发货单逐店配送）
│   └── 🔄 回收任务（ERP 预开的退货申请）
├── 装车确认
│   ├── 查看装车清单
│   ├── 扫码核对商品
│   ├── 差异上报
│   └── 确认装车 + 拍照
├── 门店配送
│   ├── 导航到店（地图导航）
│   ├── 查看门店配送清单
│   ├── 扫码/手动签收
│   ├── 签收拍照（签名板 + 照片）
│   ├── 修改门店定位（纠偏）
│   ├── 门店退货（跳转退货回收流程）
│   ├── 无法配送（跳转改派返仓流程）
│   └── 改日配送（选择改送日期）
├── 退货回收
│   ├── 查看预开退货申请（回收任务）
│   ├── 现场新增退货
│   ├── 逐品核对回收数量
│   ├── 拍照留证（商品+门店+破损特写）
│   ├── 记录垫付退款金额
│   ├── 门店签名确认
│   └── 提交回收记录
├── 改派返仓
│   ├── 标记无法配送原因
│   ├── 拍照留证
│   └── 返仓交接确认
├── 返仓交接
│   ├── 退货回收品交仓
│   ├── 未配送品交仓
│   └── 仓库扫码验收确认
├── 结算拍照
│   ├── 现金/扫码收款凭证拍照
│   └── 结算单确认
├── 交账
│   ├── 本日收款汇总
│   ├── 本日退货汇总（含垫付退款）
│   ├── 改派返仓汇总
│   ├── 现金上交拍照
│   └── 提交交账
├── 我的
│   ├── 历史配送记录
│   ├── 个人业绩统计
│   └── 设置（离线缓存清理等）
└── 消息通知（新任务推送、退货回收任务提醒）
```

### 4.2 Flutter 项目结构

```
tms_driver_app/
├── lib/
│   ├── main.dart                    # 入口 + 路由配置
│   ├── app.dart                     # MaterialApp 配置
│   ├── config/
│   │   ├── app_config.dart          # API 地址、超时等配置
│   │   ├── theme.dart               # 主题定义
│   │   └── constants.dart           # 常量
│   ├── models/                      # 数据模型
│   │   ├── dispatch.dart            # 调度单模型
│   │   ├── delivery_trip.dart       # 配送行程模型
│   │   ├── sign_record.dart         # 签收记录模型
│   │   ├── driver_return.dart       # 司机退货模型
│   │   ├── settlement.dart          # 交账模型
│   │   ├── store.dart               # 门店模型（含定位）
│   │   └── user.dart                # 司机用户模型
│   ├── providers/                   # Riverpod 状态管理
│   │   ├── auth_provider.dart       # 认证状态
│   │   ├── task_provider.dart       # 任务列表状态
│   │   ├── delivery_provider.dart   # 配送流程状态
│   │   ├── camera_provider.dart     # 拍照状态
│   │   ├── location_provider.dart   # 定位状态
│   │   └── sync_provider.dart       # 离线同步状态
│   ├── services/                    # 业务服务层
│   │   ├── api_service.dart         # HTTP API 封装
│   │   ├── auth_service.dart        # 认证服务
│   │   ├── task_service.dart        # 任务服务
│   │   ├── delivery_service.dart    # 配送服务
│   │   ├── photo_service.dart       # 照片上传服务
│   │   ├── location_service.dart    # 定位服务
│   │   ├── sync_service.dart        # 离线同步服务
│   │   └── local_db_service.dart    # 本地 SQLite 服务
│   ├── ui/                          # 页面
│   │   ├── login/
│   │   │   └── login_page.dart
│   │   ├── home/
│   │   │   └── home_page.dart       # 首页仪表盘
│   │   ├── tasks/
│   │   │   ├── task_list_page.dart   # 任务列表
│   │   │   └── task_detail_page.dart # 任务详情
│   │   ├── loading/
│   │   │   ├── loading_scan_page.dart    # 装车扫码
│   │   │   └── loading_confirm_page.dart # 装车确认
│   │   ├── delivery/
│   │   │   ├── delivery_list_page.dart   # 门店配送列表
│   │   │   ├── delivery_sign_page.dart   # 签收页面
│   │   │   └── delivery_map_page.dart    # 导航地图
│   │   ├── sign/
│   │   │   ├── sign_photo_page.dart      # 签收拍照
│   │   │   └── sign_confirm_page.dart    # 签收确认
│   │   ├── return/
│   │   │   ├── return_create_page.dart   # 创建退货
│   │   │   ├── return_photo_page.dart    # 退货拍照
│   │   │   └── return_list_page.dart     # 退货列表
│   │   ├── settlement/
│   │   │   ├── settlement_photo_page.dart # 结算拍照
│   │   │   └── settlement_submit_page.dart # 交账提交
│   │   └── profile/
│   │       └── profile_page.dart
│   ├── widgets/                     # 通用组件
│   │   ├── photo_capture.dart       # 拍照组件
│   │   ├── signature_pad.dart       # 签名板
│   │   ├── status_badge.dart        # 状态标签
│   │   ├── progress_timeline.dart   # 配送进度时间线
│   │   └── offline_banner.dart      # 离线提示条
│   └── utils/                       # 工具函数
│       ├── date_utils.dart
│       ├── image_compress.dart      # 图片压缩
│       └── logger.dart
├── assets/
│   ├── images/
│   └── fonts/
├── pubspec.yaml
└── analysis_options.yaml
```

### 4.3 核心页面设计

#### 4.3.1 首页

```
┌──────────────────────────────┐
│  🚚 司机配送            👤   │
│  张三 | 京A·12345            │
├──────────────────────────────┤
│  ┌─────────┐ ┌─────────┐   │
│  │ 待配送   │ │ 配送中   │   │
│  │   12    │ │   3     │   │
│  │  门店   │ │  门店   │   │
│  └─────────┘ └─────────┘   │
│  ┌─────────┐ ┌─────────┐   │
│  │ 已签收   │ │ 待交账   │   │
│  │   8     │ │ ¥12,580 │   │
│  │  门店   │ │ 待上交   │   │
│  └─────────┘ └─────────┘   │
├──────────────────────────────┤
│  今日配送调度单                │
│  ┌──────────────────────┐    │
│  │ D20260810-001        │    │
│  │ 线路：城北线           │    │
│  │ 12店 | 86件 | 已签8   │    │
│  │ [=====>    ] 66%     │    │
│  │ 📍 配送中 → 继续配送   │    │
│  └──────────────────────┘    │
├──────────────────────────────┤
│  🏠 任务  📷 拍照  💰 交账  👤 │
└──────────────────────────────┘
```

#### 4.3.2 装车确认

```
┌──────────────────────────────┐
│  ← 装车确认                  │
├──────────────────────────────┤
│  调度单：D20260810-001        │
│  线路：城北线                 │
│  车辆：京A·12345 | 张三       │
│                              │
│  装车清单（12店 / 86件）       │
│  ┌──────────────────────┐    │
│  │ ☑ 好又多超市(建设路)    │    │
│  │   矿泉水500ml×24  2箱  │    │
│  │   可乐330ml×6    3箱  │    │
│  │   饼干礼盒        1箱  │    │
│  │   共3品 6件            │    │
│  ├──────────────────────┤    │
│  │ ☑ 美佳便利店(解放路)    │    │
│  │   ...                 │    │
│  └──────────────────────┘    │
│                              │
│  [📷 拍照装车]  [差异上报]     │
│                              │
│  ┌──────────────────────┐    │
│  │    确认装车完成        │    │
│  └──────────────────────┘    │
└──────────────────────────────┘
```

#### 4.3.3 门店签收

```
┌──────────────────────────────┐
│  ← 门店签收                  │
├──────────────────────────────┤
│  好又多超市(建设路)            │
│  地址：建设路128号            │
│  📍 距离您 1.2km  [导航]     │
│                              │
│  配送清单                     │
│  ┌──────────────────────┐    │
│  │ 矿泉水500ml×24  2箱  ✓│    │
│  │ 可乐330ml×6    3箱  ✓│    │
│  │ 饼干礼盒        1箱  ✓│    │
│  │ 共3品 6件 全部签收    │    │
│  └──────────────────────┘    │
│                              │
│  签收方式                     │
│  ○ 全部签收  ○ 部分签收  ○ 拒收│
│                              │
│  [📷 签收拍照]  [✍️ 电子签名]  │
│                              │
│  ┌──────────────────────┐    │
│  │  📍 修改门店定位       │    │
│  └──────────────────────┘    │
│                              │
│  ┌──────────────────────┐    │
│  │    确认签收            │    │
│  └──────────────────────┘    │
└──────────────────────────────┘
```

### 4.4 离线能力设计

司机配送环境可能网络不稳定（地下室、偏远地区），APP 需支持离线操作：

| 场景 | 离线策略 |
|------|---------|
| **任务列表** | 司机登录后一次性拉取当天所有任务，缓存到本地 SQLite |
| **装车确认** | 本地先记录装车状态，网络恢复后自动提交 |
| **签收操作** | 本地记录签收数据 + 照片暂存，后台队列排队上传 |
| **定位上报** | 本地缓存位置轨迹，网络恢复后批量补传 |
| **照片上传** | 先压缩到 200-500KB，本地暂存，按优先级队列上传 |

**同步策略**：
- 使用 `pending_actions` 本地队列表
- 网络恢复后按 FIFO + 优先级（装车/发车 > 签收 > 照片 > 定位）上传
- 上传成功后从本地队列删除
- 冲突处理：以后端数据为准，APP 端拉取最新状态覆盖本地

---

## 5. 模块三：司机退货回收全流程

### 5.1 业务场景

商贸配送中，退货回收是高频刚需场景：

- **门店现场退货**：司机送货到店时，门店提出退货（破损、临期、错发、滞销等），司机当场回收商品
- **ERP 预开退货单**：客服在 ERP 中提前创建好 `sales_return_apply`（退货申请），指定由某司机上门回收
- **司机主动发起**：门店没有提前通知，司机到店后门店临时提出退货，司机现场创建退货记录

### 5.2 完整回收流程

```
┌──────────────────────────────────────────────────────────────────┐
│                    司机退货回收全链路                               │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ERP 端（出发前）                                                 │
│  ┌─────────────────────────────────────────────────────┐         │
│  │ 1. 客服创建 sales_return_apply（退货申请）              │         │
│  │    - return_type = 'DRIVER'（司机回收）               │         │
│  │    - 指定回收司机 / 不指定（由调度分配）                │         │
│  │    - 状态：PENDING → 待司机上门回收                    │         │
│  │                                                      │         │
│  │ 2. 退货申请自动进入「司机回收任务池」                    │         │
│  │    - 调度员将退货回收任务挂到对应线路的调度单上          │         │
│  │    - 司机 APP 在配送任务中可见「回收任务」标签           │         │
│  └─────────────────────────────────────────────────────┘         │
│                           │                                      │
│                           ▼                                      │
│  司机 APP 端（到店配送时）                                        │
│  ┌─────────────────────────────────────────────────────┐         │
│  │ 3. 司机到达门店，配送清单中显示：                        │         │
│  │    📦 送货任务：矿泉水×2箱、可乐×3箱...                │         │
│  │    🔄 回收任务：退货申请单 XTSQ20260810-001           │         │
│  │        （如果无预开申请，司机可点击「新增退货」）        │         │
│  │                                                      │         │
│  │ 4. 司机执行回收：                                      │         │
│  │    a. 选择「回收任务」→ 查看待回收商品清单             │         │
│  │    b. 逐品核对实物 → 确认回收数量                     │         │
│  │    c. 若无预开申请，扫描商品条码 → 填写数量/原因      │         │
│  │    d. 📷 拍照留证：                                   │         │
│  │       - 商品整体照（必需）                             │         │
│  │       - 破损/瑕疵特写照（如有）                        │         │
│  │       - 门店场景照（证明在门店现场回收）               │         │
│  │    e. ✍️ 门店负责人电子签名（可选）                    │         │
│  │    f. 确认回收 → 提交                                 │         │
│  │                                                      │         │
│  │ 5. 提交后的即时效果：                                   │         │
│  │    - 司机 APP 配送行程增加「已回收 X 件退货」标记      │         │
│  │    - 若司机垫付了退款 → 记录垫付金额                   │         │
│  │    - 退货商品随车继续配送，最终带回仓库                │         │
│  └─────────────────────────────────────────────────────┘         │
│                           │                                      │
│                           ▼                                      │
│  ERP 后台（司机提交后 → 返仓前）                                   │
│  ┌─────────────────────────────────────────────────────┐         │
│  │ 6. 系统自动处理：                                      │         │
│  │    - 生成/更新 tms_driver_return（司机退货记录）      │         │
│  │    - 关联/更新 sales_return_apply（退货申请）         │         │
│  │    - 退货申请状态 → PENDING_RECYCLE（待回收）         │         │
│  │    - 退货商品标记为「在途回收中」                      │         │
│  │                                                      │         │
│  │ 7. 调度员/仓库可在 ERP 查看：                          │         │
│  │    - 各司机当前携带的待返仓退货清单                    │         │
│  │    - 退货照片预览                                     │         │
│  │    - 预计返仓时间                                     │         │
│  └─────────────────────────────────────────────────────┘         │
│                           │                                      │
│                           ▼                                      │
│  司机返仓交接（司机回到仓库时）                                    │
│  ┌─────────────────────────────────────────────────────┐         │
│  │ 8. 司机回到仓库 → APP 点击「返仓交接」                  │         │
│  │    - 列出本趟所有需返仓的货物：                         │         │
│  │      退货回收品 + 改派返仓品 + 客户拒收品            │         │
│  │                                                      │         │
│  │ 9. 仓库人员验收（ERP端或PDA端）：                       │         │
│  │    a. 按司机逐品清点实物                               │         │
│  │    b. 确认实收数量 vs 司机上报数量                    │         │
│  │    c. 质检分流：                                       │         │
│  │       - 合格品 → 入正常库位，恢复可售库存              │         │
│  │       - 残次品 → 入残次区，标记不可售                  │         │
│  │       - 差异（司机报了但没收到）→ 标记差异待处理       │         │
│  │    d. 确认返仓完成                                    │         │
│  │                                                      │         │
│  │ 10. 系统自动处理（返仓确认后）：                        │         │
│  │     a. 生成 sales_return_inbound（退货入库单）        │         │
│  │     b. 审核入库单 → 增加库存 + 写库存流水              │         │
│  │     c. 生成 sales_return（退货单/结算依据）            │         │
│  │     d. 审核退货单 → 写负向 fin_ar（冲减应收）          │         │
│  │     e. 回写退货申请状态 → COMPLETED                   │         │
│  │     f. 回写司机退货记录状态 → COMPLETED               │         │
│  │     g. 若司机垫付了退款 → 纳入交账汇总                 │         │
│  └─────────────────────────────────────────────────────┘         │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### 5.3 与现有销售退货系统的对接关系

当前系统已有销售退货无司机流程（`sales_return_apply` → `sales_return_inbound` → `sales_return`），TMS 司机退货**不是替代而是扩展**：

```
                    sales_return_apply（退货申请）
                           │
          ┌────────────────┼────────────────┐
          │                 │                │
    return_type=     return_type=      return_type=
    'WAREHOUSE'      'DRIVER'          'DIRECT'
    （无司机流程）     （司机回收）       （客户直退）
    ┌────┴────┐     ┌────┴────────┐     ┌────┴────┐
    │ 客户自行  │     │ 司机上门回收  │     │ 客户直接  │
    │ 退回仓库  │     │ 随车返仓     │     │ 寄回仓库  │
    └─────────┘     └─────────────┘     └─────────┘
```

**TMS 与现有系统的耦合点（最小侵入）**：

1. `tms_driver_return` 创建后 → 自动调用 `SalesReturnController.createApply()` 创建 `sales_return_apply`，`return_type = 'DRIVER'`
2. `tms_driver_return.driver_return_id` ↔ `sales_return_apply.source_return_id`（双向关联）
3. 司机返仓验收 → 自动调用 `SalesReturnController.createInbound()` → `auditInbound()`，复用现有入库 + 财务逻辑
4. **不需要修改现有三表的结构**，只需要在 `sales_return_apply` 增加字段：
   - `return_type VARCHAR(20) DEFAULT 'WAREHOUSE'` — WAREHOUSE / DRIVER / DIRECT
   - `source_return_id VARCHAR(32)` — 关联 TMS 司机退货ID
   - `driver_id VARCHAR(32)` — 回收司机

### 5.4 司机退货 vs 无司机退货 vs 改派返仓 vs 客户拒收单

| 维度 | 无司机退货 | 司机退货（回收） | 改派返仓 | 客户拒收单 |
|------|-----------|-----------------|---------|-----------|
| 触发方 | ERP 客服/仓库 | 司机 APP / ERP 预开 | 司机 APP | 司机 APP |
| 货物来源 | 客户自行退回仓库 | 司机上门从门店回收 | 司机从未送达门店带回 | 客户拒收的商品 |
| 货物状态 | 客户已退到仓 | 司机在途携带 | 从未离开车辆 | 从门店带回 |
| 是否原配送商品 | 是（已签收后退货） | 是或否（任何退货） | 是（未签收的原配送商品） | 是（未签收的原配送商品） |
| 拍照要求 | 可选 | 必须 | 必须 | 必须 |
| 仓库环节 | 仓库直接验收 | 司机交回→仓库验收 | 司机交回→仓库验收（仅清点） | 司机交回→仓库收货 |
| 库存影响 | 入库增加 | 入库增加 | **不变**（不反审核出库单） | 入库增加（生成拒收入库单） |
| 财务影响 | 冲减应收 | 冲减应收+可能司机垫付 | 不变（待重新派送） | 撤销应收 |
| 验收后去向 | 入库入账 | 入库入账 | 回调度池重新派送 | 入库入账 |
| 关联配送 | 无 | 关联具体配送行程 | 关联具体配送行程 | 关联具体配送行程 |

### 5.5 退货单物流状态机与调度融入（V1.2 增强）

> **需求背景**：销售退货单（`sales_return_apply`，`return_type='DRIVER'`）需像发货单一样参与 TMS 调度排线——可由调度员主动指派给司机上门回收，也可在指派发货单时自动把同客户的退货单带给该司机。司机在 APP 退货签收后回流更新退货单物流状态与签收数量。

#### 5.5.1 退货单物流状态机

```
 未安排 ──[ERP:安排调度]──> 已安排调度 ──[调度指派司机]──> 已调度 ──[司机APP:退货签收]──> 司机已回收
                                │                │                       │
                          进入调度池        回写司机/调度单/行程      回写签收数量=司机提交
```

| 物流状态 | 触发动作 | 系统处理 |
|---------|---------|---------|
| 未安排 | 退货单创建（`return_type='DRIVER'`，审核后） | 默认状态，不进入调度 |
| 已安排调度 | ERP 退货单列表点【安排调度】 | `logistics_status='已安排调度'`，进入调度池可参与排线 |
| 已调度 | 排线调度指派司机 / 指派发货单时自动带上 | 回写 `driver_id`、`dispatch_id`、`trip_id`，`logistics_status='已调度'` |
| 司机已回收 | 司机 APP 退货签收提交 | `logistics_status='司机已回收'`，`signed_qty=司机提交数量` |

> 说明：物流状态独立于 `sales_return_apply.status`（单据状态 DRAFT/PENDING/APPROVED/...）。物流状态只对 `return_type='DRIVER'` 的退货单有意义；`WAREHOUSE`/`DIRECT` 类型物流状态恒为"未安排"且不参与调度。

#### 5.5.2 调度池与排线融入

- **配送任务池**（`dispatch-pool`）：待调度区新增"已安排调度退货单"展示，与发货单混合列表，用类型标记区分（📦 发货 / 🔄 退货回收）。退货单不参与应收金额、件数统计（逆向物流），单独显示"待回收退货单 N 张"。
- **排线调度**（`dispatch-schedule`）：左侧待调度池显示退货单（取货任务卡片，标注🔄），可挂到车辆行程的配送顺序中。退货单作为"取货"节点，不占用车辆载重（逆向），但参与配送顺序排列（到店时一并取退）。
- **调度单明细**：`tms_dispatch_detail` 通过 `bill_type` 区分发货单（`RECEIPT`）/退货单（`RETURN`），同一调度单可同时包含发货与取退任务。

#### 5.5.3 自动指派规则

调度员在排线调度指派发货单给司机时，系统自动匹配该客户是否存在"已安排调度"状态的退货单：

1. **命中**：弹出提示"该客户有退货单 XTSQ20260810-001 待回收（3 件），是否一并指派给本司机？"
2. **确认**：退货单自动加入该司机行程，`logistics_status='已调度'`，回写 `driver_id`/`dispatch_id`/`trip_id`
3. **取消**：不带入，退货单留在调度池等待单独指派
4. **批量**：生成调度单时勾选"自动带退货单"，按客户维度批量匹配同线路已安排调度退货单

> 匹配键：`sales_return_apply.customer_code` = `sales_receipt.customer_code`，且退货单 `logistics_status='已安排调度'`。

#### 5.5.4 APP 退货签收回流

- 司机 APP 配送行程中，若该行程含退货回收任务，配送清单显示"🔄 回收任务：XTSQ... N 件"
- 司机点退货任务 → 逐品核对实物 → 填写实际回收数量 → 拍照留证 → 提交
- 系统回流：`sales_return_apply.logistics_status='司机已回收'`，`signed_qty=Σ 司机提交明细数量`
- 退货商品随车返仓，后续走 5.2 步骤 8-10（返仓交接 → 仓库验收 → 入库入账 → 冲减应收）
- 仓库验收时以 `signed_qty` 为司机上报数，实收数与 `signed_qty` 差异走差异处理

---

## 6. 模块四：交账与结算

### 6.1 交账流程

```
司机一天配送结束后：

1. 查看「本日汇总」
   - 配送门店数、签收门店数
   - 应收金额合计
   - 实收现金金额
   - 线上收款金额（微信/支付宝）
   - 退货金额/件数
   - 应交回现金 = 实收现金 - 司机垫付退款

2. 结算拍照
   - 手机收款记录截图
   - 现金清点照片
   - POS 签购单照片

3. 提交交账
   - 确认金额无误
   - 电子签名
   - 提交 → ERP 端生成交账记录

4. ERP 端财务审核
   - 核对司机交账金额 vs 系统应收
   - 差异处理（长款/短款）
   - 审核通过 → 更新司机交账状态
```

### 6.2 交账数据模型

```
tms_settlement（交账单）
├── settlement_id      主键
├── dispatch_id        关联调度单
├── driver_id          司机ID
├── total_stores       总门店数
├── signed_stores      已签收门店数
├── total_amount       应收总金额
├── cash_amount        实收现金
├── online_amount      线上收款
├── return_amount      退货金额
├── submit_amount      应交回金额
├── actual_submit      实际交回金额
├── diff_amount        差异金额
├── diff_reason        差异原因
├── photos             结算照片（JSON数组URL）
├── status             PENDING → APPROVED / DISPUTED
├── created_at / audited_at
└── remark
```

---

## 7. 模块五：门店定位管理

### 7.1 功能说明

配送司机到达门店后发现系统记录的定位不准（GPS偏移、地址变更、新开门店等），可在 APP 端修正门店坐标。

### 7.2 流程

```
司机 APP:
1. 在签收页面点击「修改门店定位」
2. 地图自动定位当前位置
3. 手动拖拽微调图钉位置
4. 拍照门店门头照（用于验证）
5. 提交修正申请

ERP 后台:
6. 审核门店定位修改申请
7. 审核通过 → 更新 customer.address_lat / address_lng
8. 审核不通过 → 保留原定位
```

### 7.3 数据扩展

在 `base_customer` 表扩展字段：

```sql
ALTER TABLE base_customer ADD COLUMN address_lat DECIMAL(10,7);   -- 纬度
ALTER TABLE base_customer ADD COLUMN address_lng DECIMAL(10,7);   -- 经度
ALTER TABLE base_customer ADD COLUMN address_geo_updated_at TIMESTAMP;  -- 定位更新时间
ALTER TABLE base_customer ADD COLUMN address_geo_source VARCHAR(20);    -- 定位来源：SYSTEM/DRIVER/ADMIN
```

新增门店定位修正记录表 `tms_store_location_log`：

```sql
CREATE TABLE tms_store_location_log (
  log_id          VARCHAR(32) PRIMARY KEY,
  customer_id     VARCHAR(32) NOT NULL,
  old_lat         DECIMAL(10,7),
  old_lng         DECIMAL(10,7),
  new_lat         DECIMAL(10,7) NOT NULL,
  new_lng         DECIMAL(10,7) NOT NULL,
  store_photo     VARCHAR(500),         -- 门头照URL
  driver_id       VARCHAR(32),
  source          VARCHAR(20) DEFAULT 'DRIVER',  -- DRIVER/ADMIN
  status          VARCHAR(20) DEFAULT 'PENDING',  -- PENDING/APPROVED/REJECTED
  reviewer_id     VARCHAR(32),
  review_remark   VARCHAR(500),
  created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  reviewed_at     TIMESTAMP
);
```

---

## 8. 数据库设计

### 8.1 新增表清单

| 表名 | 说明 | 关联 |
|------|------|------|
| `tms_dispatch` | 调度单（一车一次） | → base_route_line, base_employee |
| `tms_dispatch_detail` | 调度明细（**发货单**→调度单） | → tms_dispatch, **sales_receipt** |
| `tms_delivery_trip` | 配送行程（每店一条） | → tms_dispatch_detail, base_customer |
| `tms_sign_record` | 签收记录 | → tms_delivery_trip |
| `tms_sign_photo` | 签收照片 | → tms_sign_record |
| `tms_driver_return` | 司机退货回收记录 | → tms_delivery_trip, sales_return_apply |
| `tms_driver_return_detail` | 司机退货回收明细 | → tms_driver_return |
| `tms_reschedule_return` | 改派返仓记录 | → tms_delivery_trip, sales_receipt |
| `tms_reschedule_return_detail` | 改派返仓明细 | → tms_reschedule_return |
| `tms_customer_reject` | 客户拒收单 | → tms_delivery_trip, sales_receipt |
| `tms_customer_reject_detail` | 客户拒收单明细 | → tms_customer_reject |
| `tms_settlement` | 交账单 | → tms_dispatch |
| `tms_settlement_photo` | 交账照片 | → tms_settlement |
| `tms_driver_location` | 司机位置轨迹 | → tms_dispatch |
| `tms_store_location_log` | 门店定位修正记录 | → base_customer |
| `tms_loading_check` | 装车核对记录 | → tms_dispatch |
| `tms_sync_queue` | APP离线同步队列（后端记录） | — |

### 8.2 核心表 DDL

```sql
-- ============================================================
-- TMS 调度单
-- ============================================================
CREATE TABLE tms_dispatch (
  dispatch_id       VARCHAR(32) PRIMARY KEY,
  dispatch_no       VARCHAR(50) NOT NULL UNIQUE,     -- 调度单号 D20260810-001
  route_line_id     VARCHAR(32),
  vehicle_plate     VARCHAR(20),                     -- 车牌号
  driver_id         VARCHAR(32),                     -- 司机员工ID
  driver_name       VARCHAR(100),                    -- 冗余司机姓名
  dispatch_date     DATE NOT NULL,                   -- 调度日期
  store_count       INT DEFAULT 0,                   -- 配送门店数
  total_pieces      INT DEFAULT 0,                   -- 总件数
  total_sku_count   INT DEFAULT 0,                   -- 总SKU数
  total_amount      DECIMAL(12,2) DEFAULT 0,         -- 总金额
  status            VARCHAR(20) DEFAULT 'DRAFT',     -- DRAFT/DISPATCHED/LOADING/LOADED/DEPARTED/DELIVERING/COMPLETED/CANCELLED
  loaded_at         TIMESTAMP,                       -- 装车完成时间
  departed_at       TIMESTAMP,                       -- 发车时间
  completed_at      TIMESTAMP,                       -- 完成时间
  created_by        VARCHAR(100),
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark            VARCHAR(500)
);

CREATE INDEX idx_dispatch_driver ON tms_dispatch(driver_id, dispatch_date);
CREATE INDEX idx_dispatch_status ON tms_dispatch(status, dispatch_date);
CREATE INDEX idx_dispatch_route ON tms_dispatch(route_line_id, dispatch_date);

-- ============================================================
-- TMS 调度明细（发货单 → 调度单 的关联）
-- 核心：driver dispatch based on sales_receipt (销售发货单)
-- ============================================================
CREATE TABLE tms_dispatch_detail (
  detail_id         VARCHAR(32) PRIMARY KEY,
  dispatch_id       VARCHAR(32) NOT NULL,
  receipt_id        VARCHAR(32) NOT NULL,            -- 关联 sales_receipt（发货单）
  receipt_no        VARCHAR(50),                     -- 冗余发货单号
  outbound_no       VARCHAR(50),                     -- 冗余出库单号（通过receipt.source_outbound_no获取）
  customer_id       VARCHAR(32),                     -- 冗余客户
  customer_name     VARCHAR(200),                    -- 冗余客户名
  customer_address  VARCHAR(500),                    -- 冗余地址
  sort_order        INT DEFAULT 0,                   -- 配送顺序
  status            VARCHAR(20) DEFAULT 'PENDING',   -- PENDING/DELIVERING/SIGNED/RETURNED/RESCHEDULED/UNDELIVERABLE/RETURNED
  signed_at         TIMESTAMP,
  delivery_result   VARCHAR(20),                     -- 配送结果：SIGNED/UNDELIVERABLE/RESCHEDULED
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_dispatch_det_dispatch ON tms_dispatch_detail(dispatch_id);
CREATE INDEX idx_dispatch_det_receipt ON tms_dispatch_detail(receipt_id);

-- ============================================================
-- 配送行程（每店一条配送记录）
-- ============================================================
CREATE TABLE tms_delivery_trip (
  trip_id           VARCHAR(32) PRIMARY KEY,
  dispatch_id       VARCHAR(32) NOT NULL,
  detail_id         VARCHAR(32) NOT NULL,
  customer_id       VARCHAR(32) NOT NULL,
  customer_name     VARCHAR(200),
  customer_address  VARCHAR(500),
  sort_order        INT DEFAULT 0,
  status            VARCHAR(20) DEFAULT 'PENDING',   -- PENDING/ARRIVED/SIGNED/REJECTED/RETURNED
  arrived_at        TIMESTAMP,                       -- 到达时间（司机点击"到店"）
  signed_at         TIMESTAMP,                       -- 签收时间
  sign_type         VARCHAR(20),                     -- ALL/PARTIAL/REJECT
  sign_lat          DECIMAL(10,7),                   -- 签收时GPS纬度
  sign_lng          DECIMAL(10,7),                   -- 签收时GPS经度
  remark            VARCHAR(500),
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_trip_dispatch ON tms_delivery_trip(dispatch_id);
CREATE INDEX idx_trip_customer_status ON tms_delivery_trip(customer_id, status);

-- ============================================================
-- 签收记录（商品级签收明细）
-- ============================================================
CREATE TABLE tms_sign_record (
  sign_id           VARCHAR(32) PRIMARY KEY,
  trip_id           VARCHAR(32) NOT NULL,
  outbound_detail_id VARCHAR(32),                    -- 关联出库单明细
  goods_code        VARCHAR(50),
  goods_name        VARCHAR(200),
  planned_qty       DECIMAL(12,2),                   -- 计划配送数量
  signed_qty        DECIMAL(12,2),                   -- 实际签收数量
  rejected_qty      DECIMAL(12,2) DEFAULT 0,         -- 拒收数量
  reject_reason     VARCHAR(200),                    -- 拒收原因
  unit              VARCHAR(20),
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 签收照片
-- ============================================================
CREATE TABLE tms_sign_photo (
  photo_id          VARCHAR(32) PRIMARY KEY,
  trip_id           VARCHAR(32) NOT NULL,
  photo_type        VARCHAR(20) NOT NULL,            -- SIGN/STORE/GOODS/DAMAGE
  photo_url         VARCHAR(500) NOT NULL,
  thumbnail_url     VARCHAR(500),                    -- 缩略图
  taken_at          TIMESTAMP,
  upload_status     VARCHAR(20) DEFAULT 'UPLOADED',  -- LOCAL/UPLOADED
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 司机退货单
-- ============================================================
CREATE TABLE tms_driver_return (
  driver_return_id  VARCHAR(32) PRIMARY KEY,
  return_no         VARCHAR(50) NOT NULL UNIQUE,     -- 退货单号（复用系统现有退货单规则：XTSQ退货申请/THRK退货入库/XSTH退货单 + yyyyMMdd + 4位流水）
  trip_id           VARCHAR(32) NOT NULL,            -- 关联配送行程
  dispatch_id       VARCHAR(32),
  customer_id       VARCHAR(32),
  customer_name     VARCHAR(200),
  driver_id         VARCHAR(32),
  total_qty         DECIMAL(12,2) DEFAULT 0,
  total_amount      DECIMAL(12,2) DEFAULT 0,
  return_reason     VARCHAR(500),
  photos            VARCHAR(2000),                   -- 照片URL JSON数组
  return_apply_id   VARCHAR(32),                     -- 关联 sales_return_apply
  status            VARCHAR(20) DEFAULT 'PENDING',   -- PENDING/APPROVED/INBOUNDED/COMPLETED/REJECTED
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  audited_at        TIMESTAMP,
  remark            VARCHAR(500)
);

-- ============================================================
-- 司机退货明细
-- ============================================================
CREATE TABLE tms_driver_return_detail (
  detail_id         VARCHAR(32) PRIMARY KEY,
  driver_return_id  VARCHAR(32) NOT NULL,
  goods_code        VARCHAR(50),
  goods_name        VARCHAR(200),
  qty               DECIMAL(12,2),
  unit              VARCHAR(20),
  batch_no          VARCHAR(50),
  price             DECIMAL(12,4),
  amount            DECIMAL(12,2),
  reason            VARCHAR(200),
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 改派返仓单（客户不在/地址错误，需改天再送，不反审核出库单）
-- ============================================================
CREATE TABLE tms_reschedule_return (
  return_id         VARCHAR(32) PRIMARY KEY,
  return_no         VARCHAR(50) NOT NULL UNIQUE,     -- 改派返仓单号 GPRC + yyyyMMdd + 4位流水
  trip_id           VARCHAR(32) NOT NULL,            -- 关联配送行程
  dispatch_id       VARCHAR(32),
  receipt_id        VARCHAR(32),                     -- 关联发货单
  outbound_no       VARCHAR(50),                     -- 关联出库单号（保持已审核，不反审核）
  customer_id       VARCHAR(32),
  customer_name     VARCHAR(200),
  reason            VARCHAR(20) NOT NULL,            -- CUSTOMER_ABSENT/ADDRESS_ERROR/UNREACHABLE/CUSTOMER_REQUEST/OTHER
  reason_detail     VARCHAR(500),                    -- 详细说明
  total_qty         DECIMAL(12,2) DEFAULT 0,         -- 改派返仓总数量
  photos            VARCHAR(2000),                   -- 留证照片JSON
  reschedule_date   DATE,                            -- 期望改送日期（默认次日）
  reschedule_count  INT DEFAULT 1,                   -- 第几次改派（防无限延期）
  warehouse_status  VARCHAR(20) DEFAULT 'IN_TRANSIT', -- IN_TRANSIT（在途）/RETURNED（已返仓）/CHECKED（已验收）
  returned_at       TIMESTAMP,                       -- 司机返仓时间
  checked_at        TIMESTAMP,                       -- 仓库验收时间
  checker_id        VARCHAR(32),                     -- 验收人
  driver_id         VARCHAR(32),
  status            VARCHAR(20) DEFAULT 'PENDING',   -- PENDING/RETURNED/CHECKED/REDISPATCHED（已重新派送）
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark            VARCHAR(500)
);

CREATE INDEX idx_reschedule_return_trip ON tms_reschedule_return(trip_id);
CREATE INDEX idx_reschedule_return_receipt ON tms_reschedule_return(receipt_id);

-- ============================================================
-- 改派返仓明细
-- ============================================================
CREATE TABLE tms_reschedule_return_detail (
  detail_id         VARCHAR(32) PRIMARY KEY,
  return_id         VARCHAR(32) NOT NULL,
  goods_code        VARCHAR(50),
  goods_name        VARCHAR(200),
  planned_qty       DECIMAL(12,2),                   -- 计划配送数量
  undelivered_qty   DECIMAL(12,2),                   -- 未配送数量
  actual_return_qty DECIMAL(12,2),                   -- 实际返仓数量
  diff_qty          DECIMAL(12,2) DEFAULT 0,         -- 差异（仓库实收 - 司机上报）
  unit              VARCHAR(20),
  batch_no          VARCHAR(50),
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 客户拒收单（客户收货拒收，仓库收货生成拒收入库单）
-- ============================================================
CREATE TABLE tms_customer_reject (
  reject_id         VARCHAR(32) PRIMARY KEY,
  reject_no         VARCHAR(50) NOT NULL UNIQUE,     -- 客户拒收单号 KHJS + yyyyMMdd + 4位流水
  trip_id           VARCHAR(32) NOT NULL,            -- 关联配送行程
  dispatch_id       VARCHAR(32),
  receipt_id        VARCHAR(32),                     -- 关联发货单
  outbound_no       VARCHAR(50),                     -- 关联出库单号
  customer_id       VARCHAR(32),
  customer_name     VARCHAR(200),
  reject_reason     VARCHAR(20) NOT NULL,            -- CUSTOMER_REJECT/GOODS_DAMAGED/SPEC_MISMATCH/QTY_MISMATCH/OTHER
  reason_detail     VARCHAR(500),                    -- 详细说明
  total_qty         DECIMAL(12,2) DEFAULT 0,         -- 拒收总数量
  total_amount      DECIMAL(12,2) DEFAULT 0,         -- 拒收总金额
  photos            VARCHAR(2000),                   -- 留证照片JSON
  reject_inbound_no VARCHAR(50),                     -- 关联拒收入库单号（仓库收货后生成）
  warehouse_status  VARCHAR(20) DEFAULT 'IN_TRANSIT', -- IN_TRANSIT/RETURNED/RECEIVED（已收货入库）
  returned_at       TIMESTAMP,                       -- 司机返仓时间
  received_at       TIMESTAMP,                       -- 仓库收货时间
  receiver_id       VARCHAR(32),                     -- 收货人
  driver_id         VARCHAR(32),
  status            VARCHAR(20) DEFAULT 'PENDING',   -- PENDING/RETURNED/RECEIVED/COMPLETED
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  remark            VARCHAR(500)
);

CREATE INDEX idx_customer_reject_trip ON tms_customer_reject(trip_id);
CREATE INDEX idx_customer_reject_receipt ON tms_customer_reject(receipt_id);

-- ============================================================
-- 客户拒收单明细
-- ============================================================
CREATE TABLE tms_customer_reject_detail (
  detail_id         VARCHAR(32) PRIMARY KEY,
  reject_id         VARCHAR(32) NOT NULL,
  goods_code        VARCHAR(50),
  goods_name        VARCHAR(200),
  reject_qty        DECIMAL(12,2),                   -- 拒收数量
  actual_receive_qty DECIMAL(12,2),                  -- 仓库实收数量
  diff_qty          DECIMAL(12,2) DEFAULT 0,         -- 差异
  unit              VARCHAR(20),
  batch_no          VARCHAR(50),
  price             DECIMAL(12,4),
  amount            DECIMAL(12,2),
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 交账单
-- ============================================================
CREATE TABLE tms_settlement (
  settlement_id     VARCHAR(32) PRIMARY KEY,
  settlement_no     VARCHAR(50) NOT NULL UNIQUE,     -- 交账单号 ST20260810-001
  dispatch_id       VARCHAR(32) NOT NULL,
  driver_id         VARCHAR(32) NOT NULL,
  driver_name       VARCHAR(100),
  total_stores      INT DEFAULT 0,
  signed_stores     INT DEFAULT 0,
  total_amount      DECIMAL(12,2) DEFAULT 0,         -- 应收总额
  cash_amount       DECIMAL(12,2) DEFAULT 0,         -- 实收现金
  online_amount     DECIMAL(12,2) DEFAULT 0,         -- 线上收款
  return_amount     DECIMAL(12,2) DEFAULT 0,         -- 退货金额
  submit_amount     DECIMAL(12,2) DEFAULT 0,         -- 应交回金额
  actual_submit     DECIMAL(12,2) DEFAULT 0,         -- 实际交回
  diff_amount       DECIMAL(12,2) DEFAULT 0,         -- 差异
  diff_reason       VARCHAR(500),
  photos            VARCHAR(2000),                   -- 照片URL JSON数组
  status            VARCHAR(20) DEFAULT 'PENDING',   -- PENDING/APPROVED/DISPUTED
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  audited_at        TIMESTAMP,
  auditor_id        VARCHAR(32),
  remark            VARCHAR(500)
);

-- ============================================================
-- 司机位置轨迹
-- ============================================================
CREATE TABLE tms_driver_location (
  loc_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  dispatch_id       VARCHAR(32) NOT NULL,
  driver_id         VARCHAR(32) NOT NULL,
  lat               DECIMAL(10,7) NOT NULL,
  lng               DECIMAL(10,7) NOT NULL,
  accuracy          DECIMAL(8,2),                    -- GPS精度（米）
  speed             DECIMAL(8,2),                    -- 速度（km/h）
  recorded_at       TIMESTAMP NOT NULL,
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_location_dispatch_time ON tms_driver_location(dispatch_id, recorded_at);

-- ============================================================
-- 装车核对记录
-- ============================================================
CREATE TABLE tms_loading_check (
  check_id          VARCHAR(32) PRIMARY KEY,
  dispatch_id       VARCHAR(32) NOT NULL,
  check_type        VARCHAR(20) NOT NULL,            -- SCAN/MANUAL/PHOTO
  total_planned     INT DEFAULT 0,                   -- 计划件数
  total_actual      INT DEFAULT 0,                   -- 实际件数
  diff_count        INT DEFAULT 0,                   -- 差异件数
  diff_detail       VARCHAR(1000),                   -- 差异明细JSON
  photos            VARCHAR(2000),                   -- 装车照片
  status            VARCHAR(20) DEFAULT 'CHECKED',   -- CHECKING/CHECKED/CONFIRMED
  checked_by        VARCHAR(32),
  checked_at        TIMESTAMP,
  confirmed_at      TIMESTAMP,
  created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 8.3 对现有表的扩展

```sql
-- base_customer：增加门店定位字段
ALTER TABLE base_customer ADD COLUMN IF NOT EXISTS address_lat DECIMAL(10,7);
ALTER TABLE base_customer ADD COLUMN IF NOT EXISTS address_lng DECIMAL(10,7);
ALTER TABLE base_customer ADD COLUMN IF NOT EXISTS address_geo_source VARCHAR(20) DEFAULT 'SYSTEM';
ALTER TABLE base_customer ADD COLUMN IF NOT EXISTS address_geo_updated_at TIMESTAMP;

-- base_route_line：增加车辆信息
ALTER TABLE base_route_line ADD COLUMN IF NOT EXISTS vehicle_plate VARCHAR(20);
ALTER TABLE base_route_line ADD COLUMN IF NOT EXISTS vehicle_type VARCHAR(50);
ALTER TABLE base_route_line ADD COLUMN IF NOT EXISTS max_capacity INT DEFAULT 0;

-- sales_receipt（销售发货单）：增加调度与配送状态
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS dispatch_status VARCHAR(20) DEFAULT 'UNDISPATCHED';
-- UNDISPATCHED（未调度）/ DISPATCHED（已调度）/ DELIVERING（配送中）/ SIGNED（已签收）/ UNDELIVERABLE（无法配送）/ RESCHEDULED（改日配送）/ RETURNED（已返仓）
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS dispatch_id VARCHAR(32);
ALTER TABLE sales_receipt ADD COLUMN IF NOT EXISTS trip_id VARCHAR(32);

-- sales_return_apply（退货申请）：增加司机回收相关字段
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS return_type VARCHAR(20) DEFAULT 'WAREHOUSE';
-- WAREHOUSE（无司机，客户直退仓库）/ DRIVER（司机上门回收）/ DIRECT（客户直接寄回）
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS source_return_id VARCHAR(32);  -- 关联 tms_driver_return
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS driver_id VARCHAR(32);          -- 回收司机
-- V1.2 退货单调度物流状态增强
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS logistics_status VARCHAR(20) DEFAULT '未安排';
-- 未安排 / 已安排调度 / 已调度 / 司机已回收（仅 return_type='DRIVER' 有意义）
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS signed_qty DECIMAL(18,4) DEFAULT 0;   -- 司机签收回收数量
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS dispatch_id VARCHAR(32);              -- 关联调度单
ALTER TABLE sales_return_apply ADD COLUMN IF NOT EXISTS trip_id VARCHAR(32);                  -- 关联配送行程
CREATE INDEX IF NOT EXISTS idx_sales_return_apply_logistics ON sales_return_apply(logistics_status);
```

---

## 9. API 接口设计

### 9.1 接口规范

沿用现有项目规范：
- 统一前缀：`/tms`
- 统一响应：`ApiResponse<T>`
- 分页请求：`PageRequest`（page, pageSize, filters, sortBy）
- 分页响应：`PageResult<T>`
- 鉴权：`Authorization: Bearer demo-token`（APP 端使用 JWT token）
- 日期格式：`YYYY-MM-DD`，时间格式：`YYYY-MM-DD HH:MM:SS`

### 9.2 接口清单

#### 调度管理（ERP 端）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/dispatch/pool` | 查询待调度发货单池（分页） |
| POST | `/tms/dispatch/create` | 创建调度单（基于勾选的发货单） |
| PUT  | `/tms/dispatch/{id}/assign` | 分配司机/车辆 |
| PUT  | `/tms/dispatch/{id}/cancel` | 取消调度 |
| POST | `/tms/dispatch/page` | 调度单列表（分页） |
| GET  | `/tms/dispatch/{id}/detail` | 调度单详情（含明细+行程） |
| PUT  | `/tms/dispatch/{id}/sort` | 调整配送顺序 |
| GET | `/tms/dispatch/today-summary` | 今日调度总览 |

#### 退货单调度（ERP 端，V1.2 新增）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/return-dispatch/arrange` | 退货单安排调度（`logistics_status` → 已安排调度，进调度池） |
| POST | `/tms/return-dispatch/page` | 已安排调度退货单列表（调度池取货任务） |
| POST | `/tms/return-dispatch/assign` | 退货单指派司机（`logistics_status` → 已调度，回写 driver/dispatch/trip） |
| POST | `/tms/return-dispatch/auto-match` | 指派发货单时按客户自动匹配同客户已安排调度退货单 |
| POST | `/tms/return-dispatch/cancel-arrange` | 取消安排调度（回退 `logistics_status` → 未安排） |

#### 改派返仓（ERP 端）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/reschedule-return/page` | 改派返仓单列表 |
| GET  | `/tms/reschedule-return/{id}` | 改派返仓单详情（含照片） |
| POST | `/tms/reschedule-return/{id}/check` | 仓库验收（不反审核出库单，原发货单回调度池，标记【返仓改派】） |

#### 客户拒收单（ERP 端）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/customer-reject/page` | 客户拒收单列表 |
| GET  | `/tms/customer-reject/{id}` | 拒收单详情 |
| POST | `/tms/customer-reject/{id}/receive` | 仓库收货 → 生成拒收入库单（JHRK） |
| POST | `/tms/customer-reject/{id}/inbound-audit` | 拒收入库单审核（库存+N、撤销应收） |

#### 改日配送（ERP 端）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/reschedule/pool` | 改日配送池（按改送日期查询） |
| POST | `/tms/reschedule/{id}/redispatch` | 将改期发货单重新纳入调度 |

#### 配送行程

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/trip/page` | 配送行程列表 |
| GET  | `/tms/trip/{id}/detail` | 行程详情（含签收+退货+返仓子记录） |
| GET  | `/tms/trip/{id}/sign-records` | 签收记录列表 |

#### 司机 APP 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/app/login` | 司机登录（返回JWT） |
| GET  | `/tms/app/today-tasks` | 获取今日配送任务（含回收任务） |
| POST | `/tms/app/loading/start` | 开始装车 |
| POST | `/tms/app/loading/scan` | 装车扫码核对 |
| POST | `/tms/app/loading/confirm` | 确认装车完成 |
| POST | `/tms/app/depart` | 确认发车 |
| POST | `/tms/app/arrive` | 到达门店 |
| POST | `/tms/app/sign` | 门店签收 |
| POST | `/tms/app/sign/upload-photo` | 上传签收照片 |
| POST | `/tms/app/reschedule-return/create` | 生成改派返仓单（客户不在/地址错误） |
| POST | `/tms/app/reschedule-return/upload-photo` | 上传输证照片 |
| POST | `/tms/app/customer-reject/create` | 生成客户拒收单（客户拒收） |
| POST | `/tms/app/customer-reject/upload-photo` | 上传拒收照片 |
| POST | `/tms/app/return/create` | 创建司机退货回收 |
| POST | `/tms/app/return/upload-photo` | 上传退货回收照片 |
| POST | `/tms/app/return/list` | 查看预开退货申请（回收任务列表） |
| POST | `/tms/app/return/sign` | 司机退货签收（`logistics_status` → 司机已回收，回写 `signed_qty`） |
| POST | `/tms/app/warehouse-return/confirm` | 司机返仓交接确认 |
| POST | `/tms/app/location/report` | 上报GPS位置 |
| POST | `/tms/app/location/batch-report` | 批量上报位置（离线补传） |
| POST | `/tms/app/settlement/submit` | 提交交账 |
| POST | `/tms/app/settlement/upload-photo` | 上传结算照片 |
| POST | `/tms/app/store/update-location` | 提交门店定位修正 |

#### 签收核销（ERP 端）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/sign/verify` | 审核签收记录 |
| POST | `/tms/sign/batch-verify` | 批量核销签收 |

#### 交账管理（ERP 端）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/settlement/page` | 交账单列表 |
| GET  | `/tms/settlement/{id}` | 交账单详情（含退款垫付明细） |
| POST | `/tms/settlement/{id}/audit` | 审核交账单 |
| POST | `/tms/settlement/{id}/dispute` | 标记差异争议 |

#### 门店定位（ERP 端）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/store-location/page` | 定位修正申请列表 |
| POST | `/tms/store-location/{id}/approve` | 批准定位修正 |
| POST | `/tms/store-location/{id}/reject` | 驳回定位修正 |

### 9.3 关键接口示例

#### 司机登录

```
POST /tms/app/login
Request:  { "phone": "13800138000", "password": "xxx" }
Response: { "code": 200, "data": { "token": "eyJ...", "driver": { "id": "...", "name": "张三", "plate": "京A·12345" } } }
```

#### 获取今日任务

```
GET /tms/app/today-tasks
Response: {
  "code": 200,
  "data": {
    "dispatches": [{
      "dispatchId": "...",
      "dispatchNo": "D20260810-001",
      "status": "DISPATCHED",
      "storeCount": 12,
      "totalPieces": 86,
      "stores": [
        { "tripId": "...", "customerName": "好又多超市", "address": "建设路128号",
          "lat": 39.9042, "lng": 116.4074, "sortOrder": 1, "status": "PENDING",
          "items": [{ "goodsCode": "SP001", "goodsName": "矿泉水500ml×24", "qty": 2, "unit": "箱" }] }
      ]
    }]
  }
}
```

#### 签收提交

```
POST /tms/app/sign
Request: {
  "tripId": "...",
  "signType": "ALL",           // ALL/PARTIAL/REJECT
  "signLat": 39.9150,
  "signLng": 116.4040,
  "items": [
    { "outboundDetailId": "...", "plannedQty": 2, "signedQty": 2, "rejectedQty": 0 }
  ],
  "signature": "data:image/png;base64,..."  // 电子签名
}
Response: { "code": 200, "data": { "signId": "..." } }
```

#### GPS 位置上报

```
POST /tms/app/location/report
Request: {
  "dispatchId": "...",
  "lat": 39.9150,
  "lng": 116.4040,
  "accuracy": 15.2,
  "speed": 32.5,
  "recordedAt": "2026-08-10 09:30:15"
}
Response: { "code": 200 }
```

#### 批量上报（离线补传）

```
POST /tms/app/location/batch-report
Request: {
  "locations": [
    { "dispatchId": "...", "lat": 39.9150, "lng": 116.4040, "recordedAt": "2026-08-10 09:30:15" },
    { "dispatchId": "...", "lat": 39.9155, "lng": 116.4045, "recordedAt": "2026-08-10 09:31:00" }
  ]
}
Response: { "code": 200, "data": { "accepted": 45 } }
```

---

## 10. 性能设计方案

### 10.1 性能目标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| API 响应时间 (P50) | < 100ms | 常规查询接口 |
| API 响应时间 (P99) | < 500ms | 含复杂关联查询 |
| APP 启动时间 | < 2s | 冷启动到任务列表可见 |
| 签收提交响应 | < 3s | 含照片上传 |
| GPS 上报吞吐 | 10,000 req/s | 1000司机×10s/次 |
| 调度看板刷新 | < 1s | Dashboard 数据聚合 |
| 照片上传并发 | 500 req/s | 高峰期司机集中签收 |

### 10.2 后端性能优化策略

#### 10.2.1 数据库查询优化

```sql
-- 1. 关键字段建索引（见 DDL 中的 CREATE INDEX）

-- 2. 分页查询使用 PageHelper + 覆盖索引
-- 调度单列表：避免 SELECT *，只查展示需要的字段

-- 3. GPS 轨迹表分区（按月分区，自动清理6个月以前数据）
-- H2 不支持分区，生产 MySQL 环境下：
-- ALTER TABLE tms_driver_location PARTITION BY RANGE (TO_DAYS(recorded_at)) (...);

-- 4. 聚合查询使用缓存
-- 调度看板数据 60s 缓存，今日汇总 30s 缓存
```

#### 10.2.2 接口优化

```java
// 1. 任务池查询：限制返回行数 + 按时间倒序
// POST /tms/dispatch/pool
// 默认只查最近7天未调度出库单，最多返回200条

// 2. GPS 批量上报：使用 batchUpdate
jdbcTemplate.batchUpdate(
    "INSERT INTO tms_driver_location (...) VALUES (?,?,?,?,?)",
    locations,
    500,  // batchSize
    (ps, loc) -> { ... }
);

// 3. 今日任务接口：一次性返回完整数据，APP 端缓存
// 减少 APP 多次请求，一次拉取调度单+门店+商品明细

// 4. 照片上传：异步处理
// 接收 → 返回 uploadId → 后台异步压缩+转存MinIO → WebSocket/推送通知上传结果
```

#### 10.2.3 缓存策略

```
┌────────────────────────────────────────────────┐
│                  缓存层次                        │
├────────────────────────────────────────────────┤
│ L1: 浏览器/APP 本地缓存                         │
│     - 任务列表（当天有效）                       │
│     - 基础数据（门店列表、商品列表，24h有效）     │
├────────────────────────────────────────────────┤
│ L2: 后端内存缓存 (Caffeine)                     │
│     - 字典数据（线路、片区）                     │
│     - 调度看板聚合数据（60s TTL）                │
│     - 司机当日任务汇总（30s TTL）                │
├────────────────────────────────────────────────┤
│ L3: Redis（生产环境，当前开发期可不用）           │
│     - 司机在线状态                               │
│     - GPS 最新位置（ZSET，driver_id→坐标+时间）   │
│     - 分布式锁（防止重复调度）                    │
└────────────────────────────────────────────────┘
```

### 10.3 APP 性能优化策略

#### 10.3.1 首屏加载

```dart
// 1. 启动时只加载骨架屏，异步加载数据
// 2. 任务列表分页加载（首页20条，下滑加载更多）
// 3. 图片懒加载 + 缩略图模式（列表用小图，详情才加载原图）
// 4. 使用 const 构造函数减少 Widget 重建
```

#### 10.3.2 图片处理

```dart
// 1. 拍照后立即压缩（目标 200-500KB）
//    - 分辨率限制最大 1920px
//    - JPEG 质量 75%
// 2. 上传使用分片上传（大图场景）
// 3. 上传队列 + 失败重试（最多3次，指数退避）
// 4. 本地缓存最近7天照片缩略图
```

#### 10.3.3 网络优化

```dart
// 1. API 请求合并：批量操作使用 batch 接口
// 2. 请求去重：防止重复提交（签收按钮防抖+请求ID去重）
// 3. 请求超时：connectTimeout 10s, receiveTimeout 30s
// 4. 离线队列：使用 drift (SQLite) 存储待发送操作
```

#### 10.3.4 内存管理

```dart
// 1. 图片缓存上限 100MB（使用 cached_network_image）
// 2. 及时释放 Camera 资源
// 3. 限制本地 SQLite 缓存条数（任务缓存保留最近30天）
// 4. 后台定时清理过期缓存
```

### 10.4 数据库优化建议（生产环境）

```sql
-- MySQL 生产环境建议：

-- 1. tms_driver_location 表按月分区
ALTER TABLE tms_driver_location 
PARTITION BY RANGE (TO_DAYS(recorded_at)) (
    PARTITION p202608 VALUES LESS THAN (TO_DAYS('2026-09-01')),
    PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
    ...
);

-- 2. 定期归档/清理历史GPS数据（保留6个月）
-- 定时任务：每天凌晨3点清理6个月前的分区

-- 3. 读写分离：GPS上报走写库，查询走读库
-- 使用 Spring AbstractRoutingDataSource

-- 4. 照片存储分离：MinIO/OSS 独立于数据库
```

---

## 11. 安全设计

### 11.1 APP 端安全

| 措施 | 说明 |
|------|------|
| **Token 认证** | 司机登录后获取 JWT，每次请求携带 `Authorization: Bearer <jwt>` |
| **Token 刷新** | Access Token 2h 过期 + Refresh Token 7d，静默刷新 |
| **设备绑定** | 首次登录绑定设备ID，更换设备需验证码验证 |
| **数据隔离** | 司机只能查看自己被分配的任务，API 层根据 `driver_id` 过滤 |
| **传输加密** | 全量 HTTPS，证书固定（Certificate Pinning） |
| **本地存储加密** | SQLite 数据库使用 SQLCipher 加密 |
| **敏感操作确认** | 签收、交账等操作需二次确认（手势/密码） |
| **APK 防篡改** | ProGuard 混淆 + 签名校验 |

### 11.2 后端安全

| 措施 | 说明 |
|------|------|
| **参数校验** | 所有接口入参使用 `@Valid` + Bean Validation |
| **SQL 注入防护** | 使用 JdbcTemplate 参数化查询，禁止拼接 SQL |
| **操作日志** | TMS 相关操作写入 `sys_operation_log_runtime` |
| **频率限制** | GPS 上报接口限制 1次/10秒/司机（防恶意刷接口） |
| **照片审核** | 上传照片检测文件类型（Magic Number），防文件伪装 |

---

## 12. 开发排期建议

### 12.1 阶段划分

```
Phase 1 (2周)：基础数据 + 调度管理
├── 后端：TMS 表结构 + 基础 CRUD 接口
├── 前端：调度管理页面（任务池、排线、调度单列表）
└── APP：项目初始化 + 登录 + 任务列表

Phase 2 (3周)：配送核心流程
├── 后端：装车/发车/签收/在途定位接口
├── 前端：在途监控页面
└── APP：装车确认 + 门店签收 + GPS 上报 + 拍照

Phase 3 (2周)：退货 + 交账
├── 后端：司机退货接口（联动 sales_return_apply）
├── 后端：交账接口 + 审核流程
├── 前端：退货审核 + 交账管理页面
└── APP：退货创建 + 结算拍照 + 交账提交

Phase 4 (1周)：定位管理 + 调度看板
├── 后端：门店定位修正接口
├── 前端：调度看板大屏 + 定位审核
└── APP：门店定位修改

Phase 5 (1周)：离线能力 + 性能优化
├── APP：离线队列 + 智能同步
├── 后端：批量接口优化 + 缓存
└── 联调测试 + 性能压测

总计：约 9 周
```

### 12.2 技术依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Flutter SDK | 3.27+ | APP 开发框架 |
| riverpod | 2.6+ | 状态管理 |
| drift | 2.21+ | 本地 SQLite |
| dio | 5.7+ | HTTP 网络请求 |
| go_router | 14.6+ | 路由管理 |
| camera | 0.11+ | 拍照 |
| geolocator | 13.0+ | GPS 定位 |
| image_picker | 1.1+ | 图片选择 |
| flutter_map | 7.0+ | 地图（OSM/高德） |
| MinIO Java SDK | 8.5+ | 后端图片存储 |
| jjwt | 0.12+ | JWT Token（APP 鉴权） |

### 12.3 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| Flutter 团队学习成本 | 开发效率 | Phase 1 前期安排 2 天 Flutter 培训/熟悉 |
| GPS 定位精度不足 | 在途监控不可靠 | 支持手动修正 + 多种定位源（GPS/基站/WiFi） |
| 离线同步数据冲突 | 数据不一致 | 后端优先策略 + 冲突日志审计 |
| 照片存储成本 | 运营成本 | 图片压缩 + 缩略图 + 定期归档（如仅保留3个月） |
| Flutter 地图 SDK 兼容性 | APP 地图不可用 | 封装抽象层，支持切换高德/Google Maps/OSM |
| 与现有销售退货流程对接 | 数据耦合风险 | 明确定义边界：TMS 退货 = 退货申请的入口之一，入库/财务复用现有逻辑 |

---

## 附录 A：与现有模块的关系图

```
                    ┌──────────────────┐
                    │   base_territory │ 片区
                    │  base_route_line │ 线路（含driver/车辆）
                    │  base_employee   │ 员工（is_deliveryman）
                    │  base_customer   │ 客户（新增GPS定位）
                    └────────┬─────────┘
                             │
         ┌───────────────────┼───────────────────┐
         │                   │                   │
  ┌──────▼──────┐   ┌───────▼───────┐   ┌───────▼──────┐
  │ sales_order │ → │ sales_outbound│   │ tms_dispatch │ TMS 调度
  │  销售订单    │   │   销售出库单   │   │   调度单      │
  └──────┬───────┘   └───────┬───────┘   └───────┬──────┘
         │                   │                   │
         │            ┌──────▼───────┐   ┌───────▼──────────┐
         │            │sales_receipt │←──│tms_dispatch_detail│
         │            │  销售发货单   │   │ 调度明细(receipt) │
         │            │(TMS调度来源)  │   └──────────────────┘
         │            └──────┬───────┘            │
         │                   │                   │
         │                   │           ┌───────▼──────────┐
         │                   │           │ tms_delivery_trip│ 逐店行程
         │                   │           └───────┬──────────┘
         │                   │                   │
         │                   │     ┌─────────────┼──────────────┐
         │                   │     │             │              │
         │                   │  ┌──▼──────┐ ┌───▼───────┐ ┌───▼─────────────┐
         │                   │  │tms_sign │ │tms_driver │ │tms_reschedule   │
         │                   │  │_record  │ │_return    │ │_return+reject   │
         │                   │  │签收记录  │ │司机退货回收│ │改派返仓+客户拒收│
         │                   │  └──┬─────┘ └───┬───────┘ └───┬─────────────┘
         │                   │     │           │             │
         │                   │     │    ┌──────▼──────┐      │
         │                   │     │    │sales_return │      │
         │                   │     │    │_apply(复用) │      │
         │                   │     │    │return_type= │      │
         │                   │     │    │'DRIVER'     │      │
         │                   │     │    └──────┬──────┘      │
         │                   │     │           │             │
         │                   │     │    ┌──────▼──────┐      │
         │                   │     │    │sales_return │      │
         │                   │     │    │_inbound(复用)│      │
         │                   │     │    └──────┬──────┘      │
         │                   │     │           │             │
         │                   │     │    ┌──────▼──────┐      │
         │                   │     │    │ fin_ar(-)   │      │
         │                   │     │    │应收账款冲减  │      │
         │                   │     │    └─────────────┘      │
         │                   │     │                         │
         │            ┌──────▼─────▼─┐              ┌───────▼──────┐
         │            │sales_receipt │              │sales_outbound│
         │            │receive_status│              │反审核回退库存 │
         │            │更新(已签收等) │              └──────────────┘
         │            └──────┬───────┘
         │                   │
         │            ┌──────▼───────┐
         └───────────→│   fin_ar     │
                      │   应收账款    │
                      └──────────────┘
```

---

## 附录 B：APP 与 ERP 交互序列图（以签收为例）

```
司机 APP                    Spring Boot API              MinIO/OSS
   │                              │                         │
   │ 1. POST /tms/app/sign       │                         │
   │    {tripId, items, ...}     │                         │
   │ ──────────────────────────> │                         │
   │                              │ 2. 校验(司机/状态/数量)   │
   │                              │ 3. 写入 tms_sign_record  │
   │                              │ 4. 更新 tms_delivery_trip│
   │                              │ 5. 更新 tms_dispatch_detail│
   │ 6. 返回 signId              │                         │
   │ <────────────────────────── │                         │
   │                              │                         │
   │ 7. POST /tms/app/sign/      │                         │
   │    upload-photo (multipart) │                         │
   │ ──────────────────────────> │                         │
   │                              │ 8. 上传照片 ──────────> │
   │                              │ 9. 返回 photoUrl        │
   │                              │ <──────────             │
   │                              │ 10. 写入 tms_sign_photo │
   │ 11. 返回 photoId + URL      │                         │
   │ <────────────────────────── │                         │
   │                              │                         │
   │ 12. (后台异步)               │                         │
   │     签收信息 → 关联          │                         │
   │     sales_receipt 核销      │                         │
   │                              │                         │
```

---

> **文档维护**：本方案实施过程中如遇架构调整，请同步更新本文档。
> **审阅记录**：待评审
