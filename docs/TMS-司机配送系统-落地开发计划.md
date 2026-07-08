# TMS 司机配送系统 — 落地开发计划

> **版本**：V1.0
> **日期**：2026-08-13
> **依据**：[TMS-调度管理与司机配送APP-设计方案.md](./TMS-调度管理与司机配送APP-设计方案.md)（下称"方案文档"）
> **适用工程**：`e:\我的工作项目\erp-wms-tms`
> **定位**：把方案文档第 12 章的 5 阶段排期，拆成与当前工程编号、编码范式、依赖现状对齐的可执行任务清单。

---

## 0. 计划总览

### 0.1 总体策略

1. **不改动 V1.0 已交付模块**，TMS 作为独立增量模块叠加。后端新建 `com.erp.tms` 包，前端新建 `views/tms/` 目录，APP 新建独立 Flutter 工程 `tms_driver_app/`。
2. **复用现有范式**：后端沿用 `JdbcTemplate + 单 Controller 多阶段流转`（参考 [TransferController](file:///e:/我的工作项目/erp-wms-tms/backend/src/main/java/com/erp/transfer/TransferController.java)）；前端沿用 `ProTable + Drawer + module-config` 模式。
3. **最小侵入对接**：退货回收通过给 `sales_return_apply` 加 3 个字段对接现有退货三表，不改动其结构；**改派返仓不反审核出库单、库存不变**，仅更新原 `sales_receipt` 配送状态回调度池；**客户拒收单**仓库收货后生成拒收入库单（`JHRK`）走入库流程增加库存。
4. **数据库迁移续编号**：当前 Flyway 最新 `V49`，TMS 从 `V50` 起。
5. **H2/MySQL 双兼容**：开发用 H2（MODE=MySQL），生产用 MySQL。GPS 分区等 MySQL 专属语法单独放生产环境脚本，不进 Flyway 迁移链。
6. **原型驱动**：APP 端以 [prototype/tms-driver/index.html](file:///e:/我的工作项目/erp-wms-tms/prototype/tms-driver/index.html) 为视觉基线（已有 8 屏）；返仓验收复用 [prototype/wms-pda/index.html](file:///e:/我的工作项目/erp-wms-tms/prototype/wms-pda/index.html) 的 PDA 形态；ERP 端调度管理原型缺失，按 [prototype/v1-erp-complete/index.html](file:///e:/我的工作项目/erp-wms-tms/prototype/v1-erp-complete/index.html) 现有页面风格新建（详见附录 C）。

### 0.2 阶段总览（沿用方案文档 12.1，9 周）

| Phase | 名称 | 周期 | 目标 | 关键里程碑 |
|-------|------|------|------|-----------|
| P1 | 基础数据 + 调度管理 | 2 周 | TMS 表结构、调度单全流程、APP 骨架 | M1 调度单可生成、APP 可登录看任务 |
| P2 | 配送核心流程 | 3 周 | 装车/发车/签收/在途定位/拍照 | M2 司机可完成一次完整签收 |
| P3 | 退货 + 交账 | 2 周 | 司机退货回收闭环 + 交账结算 | M3 退货回仓入账、交账可审核 |
| P4 | 定位管理 + 调度看板 | 1 周 | 门店定位修正 + 可视化大屏 | M4 看板上线、定位可纠偏 |
| P5 | 离线能力 + 性能优化 | 1 周 | APP 离线队列、批量接口、压测 | M5 离线可签收、P99 < 500ms |

### 0.3 项目现状核对（开发前基线）

| 依赖项 | 状态 | 说明 |
|--------|------|------|
| `base_territory` / `base_route_line`（含 `driver`） | ✅ 已就绪 | [V1__schema.sql#L133](file:///e:/我的工作项目/erp-wms-tms/backend/src/main/resources/db/migration/V1__schema.sql#L133) |
| `base_employee.is_deliveryman` | ✅ 已就绪 | [V1__schema.sql#L203](file:///e:/我的工作项目/erp-wms-tms/backend/src/main/resources/db/migration/V1__schema.sql#L203) |
| `sales_receipt`（调度来源单据） | ✅ 已就绪 | [V17__sales_close_loop.sql](file:///e:/我的工作项目/erp-wms-tms/backend/src/main/resources/db/migration/V17__sales_close_loop.sql) |
| `sales_return_apply` → `sales_return_inbound` → `sales_return` | ✅ 已就绪 | 退货三表，TMS 退货复用 |
| 出库单反审核 / 库存回退 | ✅ 已就绪 | 复用 `InventoryCostService` |
| Flyway 迁移机制 | ✅ 已就绪 | 最新 `V49`，TMS 从 `V50` 起 |
| JWT 鉴权 | ✅ 已就绪 | 需在 `SecurityConfig` 放开 `/tms/app/login` |
| 后端 TMS 代码 | ❌ 0 行 | 全新建 |
| 前端 TMS 页面 | ❌ 0 个 | 全新建 |
| Flutter APP 工程 | ❌ 不存在 | 全新建 |
| MinIO/OSS 图片存储 | ❌ 未接入 | P1 末期接入，P2 正式使用 |
| Redis（生产） | ✅ 已配置 | P5 用于 GPS ZSET / 分布式锁 |

---

## 1. 技术基线（对齐当前工程）

### 1.1 后端

| 项 | 约定 |
|----|------|
| 包结构 | `com.erp.tms`（controller 直接用 JdbcTemplate，复杂逻辑抽 `service/`） |
| 数据访问 | `JdbcTemplate` + `Map<String,Object>` + `queryCamel` 驼峰转换（同 TransferController） |
| 返回体 | `ApiResponse<T>` / `PageResult<T>` / `PageRequest` |
| 单据号 | 复用 `BillNoGenerator`，前缀：调度单 `D`、司机退货 `DR`、返仓单 `UR`、交账单 `ST` |
| 事务 | `@Transactional`，多表联动放同一事务 |
| 鉴权 | ERP 端接口走现有 JWT；APP 端 `/tms/app/**` 用独立司机 JWT（P1 实现） |
| 接口前缀 | ERP 端 `/tms/**`；APP 端 `/tms/app/**` |

### 1.2 前端（Vue 3）

| 项 | 约定 |
|----|------|
| 目录 | `frontend/src/views/tms/` |
| 组件 | 复用 `ProTable.vue` / `QueryBar.vue` / `BillDrawer.vue` |
| 菜单注册 | `frontend/src/module-config.js` 新增 `tms` 模块及其页面 |
| API 客户端 | 复用 `frontend/src/api/client.js` |

### 1.3 APP（Flutter）

| 项 | 约定 |
|----|------|
| 工程路径 | `tms_driver_app/`（与 `backend/` `frontend/` 同级） |
| 状态管理 | Riverpod 2.x |
| 本地存储 | drift (SQLite) + SQLCipher 加密 |
| 网络 | dio + retrofit，超时 10s/30s |
| 地图 | flutter_map（OSM/高德可切换，封装抽象层） |
| 最低 SDK | Android 5.0 / iOS 12 |

### 1.4 数据库迁移编号规划

| 版本 | 内容 | 阶段 |
|------|------|------|
| `V50__tms_dispatch_core.sql` | 调度核心 6 表 + 现有表扩展（customer 定位、route_line 车辆、receipt 调度状态） | P1 |
| `V51__tms_return_reschedule_reject.sql` | 司机退货 2 表 + 改派返仓 2 表（`tms_reschedule_return`）+ 客户拒收单 2 表（`tms_customer_reject`）+ `sales_return_apply` 3 字段 | P3 |
| `V52__tms_settlement_location.sql` | 交账单 + 交账照片 + 门店定位日志 + 司机位置轨迹 | P2/P4 |
| `V53__tms_sync_queue.sql` | APP 离线同步队列表（后端记录） | P5 |
| 生产脚本 `db/prod/tms_driver_location_partition.sql` | MySQL 按月分区（不进 Flyway，DBA 手动执行） | P5 |

---

## 2. Phase 1 — 基础数据 + 调度管理（2 周）

### 2.1 目标

- TMS 数据库骨架落地
- ERP 端可从 `sales_receipt` 生成调度单，分配司机/车辆
- APP 工程跑通，司机可登录、看到今日任务列表

### 2.2 交付物清单

#### 2.2.1 后端

**迁移脚本** `V50__tms_dispatch_core.sql`（按方案 8.2 节 DDL）：
- `tms_dispatch`（调度单）
- `tms_dispatch_detail`（调度明细，关联 `sales_receipt`）
- `tms_delivery_trip`（配送行程）
- `tms_sign_record`（签收记录，P1 建表 P2 用）
- `tms_sign_photo`（签收照片，P1 建表 P2 用）
- `tms_loading_check`（装车核对）
- 扩展：`base_customer` 加 4 个定位字段、`base_route_line` 加 3 个车辆字段、`sales_receipt` 加 `dispatch_status`/`dispatch_id`/`trip_id`

**Java 文件**（`com.erp.tms`）：

| 文件 | 职责 |
|------|------|
| `TmsDispatchController.java` | 调度管理（任务池、创建、分配、取消、列表、详情、排序、今日总览） |
| `TmsAppController.java` | APP 入口（登录、今日任务） |
| `service/TmsDispatchService.java` | 调度单生成逻辑（事务、冗余字段回填、`sales_receipt.dispatch_status` 更新） |
| `service/TmsAuthService.java` | 司机 JWT 签发（独立 secret 或复用 `JwtUtil`，claim 带 `driverId`） |

**接口清单**（方案 9.2）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/dispatch/pool` | 待调度发货单池 |
| POST | `/tms/dispatch/create` | 创建调度单 |
| PUT  | `/tms/dispatch/{id}/assign` | 分配司机/车辆 |
| PUT  | `/tms/dispatch/{id}/cancel` | 取消调度 |
| POST | `/tms/dispatch/page` | 调度单列表 |
| GET  | `/tms/dispatch/{id}/detail` | 调度单详情 |
| PUT  | `/tms/dispatch/{id}/sort` | 调整配送顺序 |
| GET  | `/tms/dispatch/today-summary` | 今日总览 |
| POST | `/tms/app/login` | 司机登录 |
| GET  | `/tms/app/today-tasks` | 今日任务 |

**配置改动**：
- [SecurityConfig.java](file:///e:/我的工作项目/erp-wms-tms/backend/src/main/java/com/erp/common/config/SecurityConfig.java)：`.requestMatchers("/tms/app/login").permitAll()`
- `application.yml`：新增 `tms.jwt.secret` / `tms.jwt.expiration`（APP token 独立配置）

#### 2.2.2 前端（Vue）

**页面**（`frontend/src/views/tms/`）：

| 文件 | 对应方案章节 |
|------|-------------|
| `DispatchPool.vue` | 3.1.1 配送任务池 |
| `DispatchSchedule.vue` | 3.1.2 排线调度（左树右池 + 拖拽/勾选） |
| `DispatchList.vue` | 调度单列表 |
| `DispatchDetail.vue` | 调度单详情（含明细 + 行程） |

**菜单注册**：`module-config.js` 新增 `tms` 顶级模块，下挂「配送管理」分组。

#### 2.2.3 APP（Flutter）

**工程初始化**：
- `flutter create --org com.erp tms_driver_app`
- `pubspec.yaml` 引入：riverpod、dio、go_router、drift、geolocator、camera、image_picker、flutter_map
- 按方案 4.2 节搭好 `lib/{config,models,providers,services,ui,widgets,utils}` 目录

**P1 实现页面**（视觉对齐 [tms-driver 原型](file:///e:/我的工作项目/erp-wms-tms/prototype/tms-driver/index.html) `screen-login`/`screen-home`/`screen-tasks`，详见附录 C.2）：
- `ui/login/login_page.dart`
- `ui/home/home_page.dart`（首页仪表盘骨架，数据先 mock）
- `ui/tasks/task_list_page.dart`（今日任务列表，对接 `/tms/app/today-tasks`）
- `ui/tasks/task_detail_page.dart`

**P1 实现服务**：
- `services/api_service.dart`（dio 封装 + 拦截器注入 token）
- `services/auth_service.dart`（登录、token 持久化）
- `providers/auth_provider.dart`、`providers/task_provider.dart`

### 2.3 验收标准

- [ ] 出库审核后的 `sales_receipt` 能在「配送任务池」出现，按片区/线路筛选
- [ ] 勾选发货单 + 选司机/车辆 → 生成 `tms_dispatch` + `tms_dispatch_detail` + `tms_delivery_trip`
- [ ] `sales_receipt.dispatch_status` 从 `UNDISPATCHED` → `DISPATCHED`
- [ ] 调度单详情可查看明细 + 行程
- [ ] APP 登录返回 JWT，`/tms/app/today-tasks` 返回该司机当日调度单及门店
- [ ] 取消调度单后回退 `sales_receipt.dispatch_status`

### 2.4 依赖与风险

- **风险**：排线调度的拖拽交互在 Element Plus 下需自研或引入 `vuedraggable`。**对策**：P1 先做勾选模式，拖拽作为 P1 末期增强。
- **依赖**：MinIO 未接入前，P1 不涉及图片，纯数据流。

---

## 3. Phase 2 — 配送核心流程（3 周）

### 3.1 目标

- 司机 APP 可完成：装车确认 → 发车 → 到店 → 签收 → 拍照
- ERP 端可看在途监控、签收核销
- 司机位置实时上报

### 3.2 交付物清单

#### 3.2.1 后端

**迁移脚本** `V52__tms_settlement_location.sql`（部分，位置轨迹表先建）：
- `tms_driver_location`（司机位置轨迹）

**接口**（APP 端）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/app/loading/start` | 开始装车 |
| POST | `/tms/app/loading/scan` | 装车扫码核对 |
| POST | `/tms/app/loading/confirm` | 确认装车完成 |
| POST | `/tms/app/depart` | 确认发车 |
| POST | `/tms/app/arrive` | 到达门店 |
| POST | `/tms/app/sign` | 门店签收 |
| POST | `/tms/app/sign/upload-photo` | 上传签收照片 |
| POST | `/tms/app/location/report` | GPS 单点上报 |
| POST | `/tms/app/location/batch-report` | GPS 批量上报（离线补传） |

**接口**（ERP 端）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/trip/page` | 配送行程列表 |
| GET  | `/tms/trip/{id}/detail` | 行程详情 |
| GET  | `/tms/trip/{id}/sign-records` | 签收记录 |
| POST | `/tms/sign/verify` | 审核签收 |
| POST | `/tms/sign/batch-verify` | 批量核销 |
| GET  | `/tms/dispatch/{id}/track` | 调度单在途轨迹 |

**核心服务**：
- `TmsAppController` 扩展装车/发车/签收/定位方法
- `service/TmsSignService.java`：签收事务（写 `tms_sign_record` + 更新 `tms_delivery_trip` + 更新 `tms_dispatch_detail.status` + 更新 `sales_receipt.receive_status`）
- `service/TmsLocationService.java`：GPS 写入，批量上报用 `jdbcTemplate.batchUpdate`
- `service/TmsPhotoService.java`：对接 MinIO，返回 URL

**MinIO 接入**：
- `pom.xml` 加 `minio` 依赖
- `common/config/MinioConfig.java`：读取 `minio.endpoint/accessKey/secretKey/bucket`
- `application.yml` 增 `minio:` 配置段
- 上传接口统一：接收 multipart → 存 MinIO → 写 `tms_sign_photo`

**调度单状态机推进**（方案 3.1.2）：
- 装车确认 → `LOADED`
- 发车 → `DEPARTED`
- 首店签收 → `DELIVERING`
- 全部签收 + 交账 → `COMPLETED`（交账在 P3）

#### 3.2.2 前端（Vue）

| 文件 | 说明 |
|------|------|
| `DeliveryMonitor.vue` | 在途监控（地图 + 进度条 + 门店状态） |
| `DispatchTrack.vue` | 单调度单轨迹回放 |
| `SignVerify.vue` | 签收核销（含照片预览、差异处理） |

地图组件：引入 `vue-amap` 或 `@vuemap/vue3-amap`（高德），P2 末期再做。

#### 3.2.3 APP（Flutter）

**新增页面**：
- `ui/loading/loading_scan_page.dart`（装车扫码，用 `mobile_scanner`）
- `ui/loading/loading_confirm_page.dart`
- `ui/delivery/delivery_list_page.dart`（门店配送列表）
- `ui/delivery/delivery_sign_page.dart`（签收：全部/部分/拒收）
- `ui/delivery/delivery_map_page.dart`（导航，flutter_map）
- `ui/sign/sign_photo_page.dart`（签收拍照 + 电子签名）
- `ui/sign/sign_confirm_page.dart`

**新增服务/Provider**：
- `services/delivery_service.dart`
- `services/photo_service.dart`（压缩到 200-500KB → 上传）
- `services/location_service.dart`（后台 30s 上报，用 `workmanager` 或 `background_locator`）
- `providers/delivery_provider.dart`、`providers/camera_provider.dart`、`providers/location_provider.dart`

**新增组件**：
- `widgets/photo_capture.dart`、`widgets/signature_pad.dart`、`widgets/status_badge.dart`、`widgets/progress_timeline.dart`

### 3.3 验收标准

- [ ] 司机 APP 装车扫码 → 确认装车，ERP 端状态实时变 `LOADED`
- [ ] 发车后 ERP 在途监控可见车辆位置（30s 刷新）
- [ ] 到店 → 签收（全部/部分/拒收）→ 拍照上传成功
- [ ] 签收后 `sales_receipt.receive_status` 正确推进（未收款 → 部分收款 → 已收款）
- [ ] 部分签收剩余数量可标记改派返仓或客户拒收（流程在 P3 完整闭环，P2 先留入口）
- [ ] GPS 批量上报接口吞吐 ≥ 1000 req/s（P5 压测验证）
- [ ] 照片在 MinIO 可访问，ERP 端可预览

### 3.4 依赖与风险

- **风险**：Flutter 后台定位在 iOS 权限严格。**对策**：P2 先做前台定位，iOS 后台定位放 P5。
- **风险**：高德地图 key 申请。**对策**：P2 第 1 周完成 key 申请，否则先用 OSM 兜底。
- **依赖**：MinIO 必须在 P2 第 1 周末就绪。

---

## 4. Phase 3 — 退货回收 + 改派返仓/客户拒收 + 交账（2 周）

### 4.1 目标

- 司机上门退货回收全链路闭环：现场回收 → 随车返仓 → 仓库验收 → 入库入账 → 冲减应收
- **退货单调度闭环（V1.2 新增）**：销售退货单【安排调度】→ 物流状态「已安排调度」进调度池 → 排线指派司机（或指派发货单时自动带同客户退货单）→「已调度」→ 司机 APP 退货签收 →「司机已回收」+ 回写签收数量
- **改派返仓闭环**：客户不在 → APP 生成改派返仓单 → 仓库验收（不反审核出库单）→ 原发货单回调度池重新派送，标记【返仓改派】
- **客户拒收单闭环**：客户拒收 → APP 生成客户拒收单 → 仓库收货 → 生成拒收入库单 → 库存增加
- 司机交账单提交 + 财务审核

> **流程拆分说明（V1.1 修订）**：原"未配送返仓"统一三选一处理拆为两类独立单据——改派返仓（不动库存、验收后回调度池）、客户拒收单（仓库收货生成拒收入库单、库存增加）。详见方案 3.1.7/3.1.8。

> **退货单调度说明（V1.2 新增）**：销售退货单（`return_type='DRIVER'`）增加物流状态机（未安排/已安排调度/已调度/司机已回收），融入 P1 调度池与排线调度，支持主动指派与指派发货单时自动带退货单。详见方案 5.5。

### 4.2 交付物清单

#### 4.2.1 后端

**迁移脚本** `V51__tms_return_reschedule_reject.sql`：
- `tms_driver_return` / `tms_driver_return_detail`（退货单号复用系统现有规则 XTSQ/THRK/XSTH）
- `tms_reschedule_return` / `tms_reschedule_return_detail`（改派返仓，GPRC 前缀）
- `tms_customer_reject` / `tms_customer_reject_detail`（客户拒收单，KHJS 前缀）
- 扩展 `sales_return_apply`：`return_type` / `source_return_id` / `driver_id`（方案 8.3）+ **V1.2 物流状态** `logistics_status` / `signed_qty` / `dispatch_id` / `trip_id`（方案 5.5/8.3）
- 扩展 `tms_dispatch_detail`：`bill_type`（`RECEIPT`/`RETURN`）区分发货单与退货单取货任务（P1 已建表，P3 启用 RETURN 分支）

**迁移脚本** `V52__tms_settlement_location.sql`（补齐）：
- `tms_settlement` / `tms_settlement_photo`

**单据号规则**（复用/扩展 `BillNoGenerator`，对齐 [BillNoGenerator.java#L41-43](file:///e:/我的工作项目/erp-wms-tms/backend/src/main/java/com/erp/common/util/BillNoGenerator.java#L41-L43)）：

| 单据 | 前缀 | 说明 |
|------|------|------|
| 司机退货申请 | `XTSQ` | 复用 `SALES_RETURN_REQ`，对接 `sales_return_apply` |
| 司机退货入库 | `THRK` | 复用 `SALES_RETURN_IN`，对接 `sales_return_inbound` |
| 司机退货单 | `XSTH` | 复用 `SALES_RETURN`，对接 `sales_return` |
| 改派返仓单 | `GPRC` | 新增 `BillType.RESCHEDULE_RETURN` |
| 客户拒收单 | `KHJS` | 新增 `BillType.CUSTOMER_REJECT` |
| 拒收入库单 | `JHRK` | 新增 `BillType.REJECT_INBOUND` |

**接口**（APP 端）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/app/return/list` | 预开退货申请（回收任务）列表 |
| POST | `/tms/app/return/create` | 创建司机退货回收 |
| POST | `/tms/app/return/upload-photo` | 上传退货照片 |
| POST | `/tms/app/reschedule-return/create` | 生成改派返仓单（客户不在/地址错误） |
| POST | `/tms/app/reschedule-return/upload-photo` | 上传输证照片 |
| POST | `/tms/app/customer-reject/create` | 生成客户拒收单（客户拒收） |
| POST | `/tms/app/customer-reject/upload-photo` | 上传拒收照片 |
| POST | `/tms/app/warehouse-return/confirm` | 司机返仓交接确认（改派返仓品+拒收品+退货品） |
| POST | `/tms/app/settlement/submit` | 提交交账 |
| POST | `/tms/app/settlement/upload-photo` | 上传结算照片 |

**接口**（ERP 端）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/return-dispatch/arrange` | 退货单安排调度（V1.2：`logistics_status`→已安排调度，进调度池） |
| POST | `/tms/return-dispatch/page` | 已安排调度退货单列表（调度池取货任务） |
| POST | `/tms/return-dispatch/assign` | 退货单指派司机（`logistics_status`→已调度，回写 driver/dispatch/trip） |
| POST | `/tms/return-dispatch/auto-match` | 指派发货单时按客户自动匹配同客户已安排调度退货单 |
| POST | `/tms/return-dispatch/cancel-arrange` | 取消安排调度（回退→未安排） |
| POST | `/tms/reschedule-return/page` | 改派返仓单列表 |
| GET  | `/tms/reschedule-return/{id}` | 改派返仓单详情 |
| POST | `/tms/reschedule-return/{id}/check` | 仓库验收（不反审核出库单，验收后回调度池） |
| POST | `/tms/reschedule-return/pool` | 返仓改派池（待重新派送的发货单） |
| POST | `/tms/reschedule-return/{id}/redispatch` | 重新纳入调度 |
| POST | `/tms/customer-reject/page` | 客户拒收单列表 |
| GET  | `/tms/customer-reject/{id}` | 拒收单详情 |
| POST | `/tms/customer-reject/{id}/receive` | 仓库收货 → 生成拒收入库单 |
| POST | `/tms/customer-reject/{id}/inbound-audit` | 拒收入库单审核（库存 +N） |
| POST | `/tms/settlement/page` | 交账单列表 |
| GET  | `/tms/settlement/{id}` | 交账详情 |
| POST | `/tms/settlement/{id}/audit` | 审核交账 |
| POST | `/tms/settlement/{id}/dispute` | 标记差异争议 |

**核心服务**：
- `service/TmsReturnService.java`：司机退货创建 → 自动调 `SalesReturnController.createApply()` 生成 `sales_return_apply`（`return_type='DRIVER'`），退货单号复用 `XTSQ/THRK/XSTH` 规则，双向关联 ID
- `service/TmsReturnDispatchService.java`（V1.2 新增）：退货单安排调度 / 指派司机 / 自动匹配 / 取消安排；维护 `sales_return_apply.logistics_status` 状态机；向 `tms_dispatch_detail` 写入 `bill_type='RETURN'` 取货任务；APP 签收回流写 `signed_qty`
- `service/TmsRescheduleReturnService.java`：改派返仓单生成 + 仓库验收。**验收时不反审核出库单、不生成入库单**，仅更新原 `sales_receipt` 配送状态为「待改派」→ 进入调度池，标记【返仓改派】，记录改派次数
- `service/TmsCustomerRejectService.java`：客户拒收单生成 + 仓库收货 → 生成拒收入库单（`JHRK`）→ 审核后库存 +N，撤销对应应收
- `service/TmsSettlementService.java`：交账汇总（应收/实收/退货/垫付/差异）+ 审核

**对接点**（方案 5.3，最小侵入）：
1. `tms_driver_return` 创建 → 调 `SalesReturnController.createApply()`，退货单号复用系统现有退货单规则
2. `tms_driver_return.driver_return_id` ↔ `sales_return_apply.source_return_id`
3. 司机退货返仓验收 → 调 `SalesReturnController.createInbound()` → `auditInbound()`
4. **改派返仓不触碰现有退货/出库流程**，仅更新 `sales_receipt` 配送状态
5. **客户拒收单**仓库收货生成拒收入库单，走入库流程增加库存，**不改现有退货三表结构**
6. 仅在 `sales_return_apply` 加 3 字段

#### 4.2.2 前端（Vue）

| 文件 | 说明 |
|------|------|
| `RescheduleReturn.vue` | 改派返仓管理（列表 + 仓库验收，验收后回调度池，标记返仓改派） |
| `CustomerReject.vue` | 客户拒收单管理（列表 + 仓库收货 + 拒收入库单审核） |
| `SettlementList.vue` | 交账单列表 |
| `SettlementAudit.vue` | 交账审核（含照片、差异处理） |
| `DriverReturnReview.vue` | 司机退货回收查看（照片、明细） |
| `SalesReturnDispatch.vue`（V1.2） | 销售退货单调度：列表 +【安排调度】+ 物流状态列/筛选 + 指派司机信息 |
| 调度池/排线扩展（V1.2） | `DispatchPool.vue`/`DispatchSchedule.vue` 增加退货单取货任务展示 + 指派发货单时自动匹配同客户退货单提示 |

#### 4.2.3 APP（Flutter）

**新增页面**：
- `ui/return/return_list_page.dart`（预开回收任务）
- `ui/return/return_create_page.dart`（现场新增退货，扫码/手填）
- `ui/return/return_sign_page.dart`（V1.2：退货签收，逐品核对回收数量+拍照，提交回写 `signed_qty`）
- `ui/return/return_photo_page.dart`
- `ui/reschedule/reschedule_return_page.dart`（改派返仓：客户不在/地址错误，登记+拍照+选改送日期）
- `ui/reject/customer_reject_page.dart`（客户拒收：逐品登记拒收数量+拍照）
- `ui/warehouse/warehouse_return_page.dart`（返仓交接：改派返仓品 + 拒收品 + 退货品分类交接）
- `ui/settlement/settlement_photo_page.dart`
- `ui/settlement/settlement_submit_page.dart`

**新增组件**：`widgets/offline_banner.dart`

### 4.3 验收标准

- [ ] **退货单调度（V1.2）**：ERP 退货单点【安排调度】→ `logistics_status=已安排调度` 且出现在调度池
- [ ] 退货单调度：排线调度指派司机 → `logistics_status=已调度`，回写 `driver_id`/`dispatch_id`/`trip_id`
- [ ] 退货单调度：指派发货单时，同客户存在"已安排调度"退货单 → 弹窗提示并可一并指派
- [ ] 退货单调度：司机 APP 退货签收提交 → `logistics_status=司机已回收`，`signed_qty=司机提交数量`
- [ ] ERP 预开 `sales_return_apply`（`return_type=DRIVER`）→ APP 可见回收任务
- [ ] 司机现场退货 → 生成 `tms_driver_return`（单号 `XTSQ...`）+ 联动 `sales_return_apply`
- [ ] 退货返仓交接 → 仓库验收 → 自动生成 `sales_return_inbound`（`THRK...`）→ 审核 → 库存增加 + `fin_ar` 冲减
- [ ] **改派返仓**：司机 APP 生成改派返仓单（`GPRC...`）→ 仓库验收 → **出库单未反审核、库存不变** → 原发货单进调度池可重新派送，标记【返仓改派】
- [ ] 改派返仓：到改送日期回任务池，可重新调度，记录改派次数
- [ ] **客户拒收单**：司机 APP 生成拒收单（`KHJS...`）→ 仓库收货 → 生成拒收入库单（`JHRK...`）→ 审核 → 库存 +N、撤销应收
- [ ] 司机交账：汇总金额正确（应交回 = 实收现金 - 垫付退款），财务可审核、标记差异

### 4.4 依赖与风险

- **风险**：与现有退货流程耦合。**对策**：严格按方案 5.3 边界，TMS 司机退货仅作入口，入库/财务全复用；联调时先跑无司机退货回归用例确保未破坏。
- **风险**：改派返仓不反审核出库单，可能导致库存账实不一致。**对策**：改派返仓货物物理上暂存仓库或随下次车，系统层面发货单状态为「待改派」，仓库需有暂存区管理；改派重新派送时不重复出库（复用原出库单）。
- **风险**：交账金额与 `fin_ar` 核销口径不一致。**对策**：以 `fin_ar` 为准，交账仅作司机对账凭证，不直接改应收。

---

## 5. Phase 4 — 门店定位 + 调度看板（1 周）

### 5.1 目标

- 司机可纠偏门店定位，ERP 审核后生效
- 调度看板大屏上线

### 5.2 交付物清单

#### 5.2.1 后端

**迁移脚本** `V52` 补齐 `tms_store_location_log`（方案 7.3）。

**接口**：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tms/app/store/update-location` | 提交定位修正（APP） |
| POST | `/tms/store-location/page` | 修正申请列表（ERP） |
| POST | `/tms/store-location/{id}/approve` | 批准 |
| POST | `/tms/store-location/{id}/reject` | 驳回 |
| GET  | `/tms/dashboard/dispatch` | 看板聚合数据 |

**服务**：`service/TmsStoreLocationService.java`（审核通过更新 `base_customer.address_lat/lng` + `address_geo_source='DRIVER'`）

#### 5.2.2 前端（Vue）

| 文件 | 说明 |
|------|------|
| `StoreLocationReview.vue` | 定位修正审核（地图对比新旧坐标 + 门头照） |
| `DispatchDashboard.vue` | 调度看板大屏（总览卡片 + 地图 + 排行 + 趋势 + 告警） |

看板组件：ECharts（趋势图）+ 高德地图（车辆实时位置），数据 60s 轮询。

#### 5.2.3 APP（Flutter）

- `ui/delivery/store_location_edit_page.dart`（地图拖拽图钉 + 拍门头照 + 提交）

### 5.3 验收标准

- [ ] 司机在签收页纠偏定位 → 生成 `tms_store_location_log`（PENDING）
- [ ] ERP 审核通过 → `base_customer` 坐标更新，APP 下次拉取为新坐标
- [ ] 看板显示：今日总单数/件数/已完成/配送中/待配送、车辆实时位置、签收率趋势、超时告警

---

## 6. Phase 5 — 离线能力 + 性能优化（1 周）

### 6.1 目标

- APP 弱网/断网可继续签收、拍照，网络恢复自动补传
- 后端批量接口 + 缓存，P99 < 500ms

### 6.2 交付物清单

#### 6.2.1 后端

**迁移脚本** `V53__tms_sync_queue.sql`：`tms_sync_queue`（APP 离线同步队列，后端记录冲突日志）。

**优化项**：
- GPS 批量上报：`jdbcTemplate.batchUpdate`（batchSize 500）
- 调度看板聚合：Caffeine 缓存 60s
- 今日任务接口：一次性返回调度单 + 门店 + 商品明细，减少 APP 往返
- 照片上传：异步压缩转存（接收 → 返 uploadId → 后台处理 → 推送结果）
- 频率限制：GPS 上报 1 次/10s/司机（`RateLimiter` 或 Redis 计数）
- 生产 MySQL：`tms_driver_location` 按月分区（脚本 `db/prod/tms_driver_location_partition.sql`，DBA 执行，定时清理 6 个月前数据）

#### 6.2.2 APP（Flutter）

- `services/local_db_service.dart`（drift 建表：`tasks`、`pending_actions`、`photos`、`locations`）
- `services/sync_service.dart`：网络监听 → FIFO + 优先级队列（装车/发车 > 签收 > 照片 > 定位）→ 上传成功删除 → 冲突以后端为准
- 图片压缩 `utils/image_compress.dart`（max 1920px，JPEG 75%）
- 离线提示条 `widgets/offline_banner.dart` 接入

### 6.3 验收标准

- [ ] 断网下完成 1 次签收 + 3 张照片，网络恢复后自动补传成功，ERP 端数据一致
- [ ] 离线 GPS 缓存批量补传，无丢点
- [ ] 压测：1000 司机并发，GPS 上报吞吐 ≥ 10000 req/s，P99 < 500ms
- [ ] 签收提交（含照片）< 3s

---

## 7. 交付物总矩阵

### 7.1 数据库迁移

| 脚本 | 表/改动 | 阶段 |
|------|---------|------|
| `V50__tms_dispatch_core.sql` | dispatch / dispatch_detail / delivery_trip / sign_record / sign_photo / loading_check + customer/route_line/receipt 扩展 | P1 |
| `V51__tms_return_reschedule_reject.sql` | driver_return(+detail) / reschedule_return(+detail) / customer_reject(+detail) + sales_return_apply 3 字段 | P3 |
| `V52__tms_settlement_location.sql` | settlement(+photo) / store_location_log / driver_location | P2/P4 |
| `V53__tms_sync_queue.sql` | tms_sync_queue | P5 |
| `db/prod/tms_driver_location_partition.sql` | MySQL 分区（不进 Flyway） | P5 |

### 7.2 后端文件（`com.erp.tms`）

| 文件 | 阶段 |
|------|------|
| `TmsDispatchController.java` | P1 |
| `TmsAppController.java` | P1（登录/任务）→ P2（装车/发车/签收/定位）→ P3（退货/改派返仓/客户拒收/交账） |
| `TmsSignController.java` | P2 |
| `TmsRescheduleReturnController.java` | P3（改派返仓） |
| `TmsReturnDispatchController.java` | P3（V1.2 退货单调度：安排调度/指派/自动匹配） |
| `TmsCustomerRejectController.java` | P3（客户拒收单） |
| `TmsSettlementController.java` | P3 |
| `TmsStoreLocationController.java` | P4 |
| `TmsDashboardController.java` | P4 |
| `service/TmsDispatchService.java` | P1 |
| `service/TmsAuthService.java` | P1 |
| `service/TmsSignService.java` | P2 |
| `service/TmsLocationService.java` | P2 |
| `service/TmsPhotoService.java` | P2 |
| `service/TmsReturnService.java` | P3 |
| `service/TmsReturnDispatchService.java` | P3（V1.2 退货单调度物流状态机+自动匹配） |
| `service/TmsRescheduleReturnService.java` | P3（改派返仓，不反审核出库单） |
| `service/TmsCustomerRejectService.java` | P3（客户拒收单→拒收入库单） |
| `service/TmsSettlementService.java` | P3 |
| `service/TmsStoreLocationService.java` | P4 |
| `common/config/MinioConfig.java` | P2 |

### 7.3 前端页面（`frontend/src/views/tms/`）

| 文件 | 阶段 |
|------|------|
| `DispatchPool.vue` / `DispatchSchedule.vue` / `DispatchList.vue` / `DispatchDetail.vue` | P1 |
| `DeliveryMonitor.vue` / `DispatchTrack.vue` / `SignVerify.vue` | P2 |
| `RescheduleReturn.vue` / `CustomerReject.vue` / `SettlementList.vue` / `SettlementAudit.vue` / `DriverReturnReview.vue` | P3 |
| `StoreLocationReview.vue` / `DispatchDashboard.vue` | P4 |

### 7.4 APP 页面（`tms_driver_app/lib/ui/`）

| 目录 | 页面 | 阶段 |
|------|------|------|
| `login/` | login_page | P1 |
| `home/` | home_page | P1 |
| `tasks/` | task_list / task_detail | P1 |
| `loading/` | loading_scan / loading_confirm | P2 |
| `delivery/` | delivery_list / delivery_sign / delivery_map / store_location_edit | P2/P4 |
| `sign/` | sign_photo / sign_confirm | P2 |
| `return/` | return_list / return_create / return_photo | P3 |
| `reschedule/` | reschedule_return | P3 |
| `reject/` | customer_reject | P3 |
| `warehouse/` | warehouse_return | P3 |
| `settlement/` | settlement_photo / settlement_submit | P3 |
| `profile/` | profile | P3 |

---

## 8. 里程碑与验收

| 里程碑 | 时点 | 验收内容 |
|--------|------|---------|
| M1 调度可生成 | P1 末 | ERP 生成调度单、APP 登录看任务 |
| M2 全链路签收 | P2 末 | 装车→发车→签收→拍照→在途监控全通 |
| M3 退货交账闭环 | P3 末 | 退货回仓入账、改派返仓回调度池、客户拒收入库、交账可审核 |
| M4 看板+定位 | P4 末 | 看板上线、定位可纠偏 |
| M5 离线+性能 | P5 末 | 离线可签收、P99 < 500ms |

---

## 9. 风险与对策（在方案 12.3 基础上补充工程风险）

| 风险 | 影响 | 对策 |
|------|------|------|
| Flutter 团队学习成本 | 进度 | P1 前 2 天 Flutter 培训；P1 APP 任务从简 |
| 排线拖拽交互 | P1 进度 | 先勾选模式，拖拽 P1 末期增强 |
| MinIO 未接入 | P2 阻塞 | P1 末期完成 MinIO 部署 + 配置 |
| 高德 key 申请 | P2 地图 | P2 第 1 周申请，OSM 兜底 |
| iOS 后台定位权限 | P2 体验 | P2 前台定位，iOS 后台定位放 P5 |
| 退货与现有流程耦合 | P3 回归风险 | 严格边界（方案 5.3），先跑无司机退货回归 |
| 交账与 fin_ar 口径 | P3 对账 | 以 fin_ar 为准，交账仅作司机对账 |
| H2 不支持分区 | P5 生产 | 分区脚本独立，不进 Flyway |
| GPS 写入压力大 | P5 性能 | 批量写 + 分区 + 6 个月归档 |

---

## 10. 执行顺序建议（P1 第 1 周）

为快速启动，建议 P1 第 1 周按以下顺序并行：

1. **后端**：写 `V50` 迁移 → 搭 `com.erp.tms` 包骨架 → `TmsDispatchController` 任务池 + 创建调度单 → 改 `SecurityConfig`
2. **前端**：先产出 ERP 端调度管理原型（附录 C.5，`prototype/v1-erp-complete/07-tms/`）→ `module-config.js` 注册 tms 模块 → `DispatchPool.vue` + `DispatchList.vue` 骨架
3. **APP**：`flutter create` → 引依赖 → 对齐 `tms-driver` 原型 → `api_service` + `auth_service` + login_page
4. **基建**：MinIO 部署（P1 末期）

> 本计划为落地任务清单，实施中如架构调整，同步回写方案文档与本计划。

---

## 附录 C：原型对齐与视觉基线

> 本附录把 work 目录下已输出的 APP/PC 原型，逐屏映射到本计划的开发任务，作为前端/APP 实现的视觉与交互基线。

### C.1 原型资产盘点

| 原型文件 | 端 | 状态 | 用途 |
|---------|----|----|------|
| [prototype/tms-driver/index.html](file:///e:/我的工作项目/erp-wms-tms/prototype/tms-driver/index.html) | 司机 APP | ✅ 已有 8 屏 | APP 视觉基线 |
| [prototype/wms-pda/index.html](file:///e:/我的工作项目/erp-wms-tms/prototype/wms-pda/index.html) | 仓库 PDA | ✅ 已有（含拒收入库） | 返仓验收复用 |
| [prototype/v1-erp-complete/index.html](file:///e:/我的工作项目/erp-wms-tms/prototype/v1-erp-complete/index.html) | ERP PC | ✅ 已有（无 TMS 模块） | 调度管理页风格参考 |
| ERP 端 TMS 调度管理原型 | ERP PC | ❌ 缺失 | 需新建（按 v1-erp-complete 风格） |

### C.2 司机 APP 原型 → Flutter 页面映射

原型 `tms-driver/index.html` 已实现的 8 个 screen，与本计划 APP 页面对齐如下：

| 原型 screen（id） | 原型内容 | 本计划 Flutter 页面 | 阶段 | 原型差异/补齐说明 |
|------------------|---------|---------------------|------|------------------|
| `screen-login` | 司机登录 | `ui/login/login_page.dart` | P1 | 对齐，直接还原 |
| `screen-home` | 首页：今日任务概览卡 + 退货回收任务入口 + 历史签收预览 | `ui/home/home_page.dart` | P1 | 对齐；数据从 mock 换真实接口 |
| `screen-tasks` | 今日配送任务列表（按门店序） | `ui/tasks/task_list_page.dart` | P1 | 对齐 |
| `screen-delivery` | 配送/签收页：导航前往 + 确认签收 + 签收弹窗(拍照) | `ui/delivery/delivery_sign_page.dart` + `ui/sign/sign_photo_page.dart` | P2 | 原型签收弹窗含拍照，对齐；导航地图 P2 补 |
| `screen-return` | 退货回收：退货申请单 + 商品列表 + 确认回收装车 | `ui/return/return_list_page.dart` + `return_create_page.dart` | P3 | 原型较简，按方案补逐品核对/拍照/签名 |
| `screen-reject` | 拒收/无法配送：原因 + 确认拒收(随车带回) | `ui/reschedule/reschedule_return_page.dart`（改派返仓）+ `ui/reject/customer_reject_page.dart`（客户拒收） | P2/P3 | 原型合并了拒收与无法配送，按方案拆为两类：客户不在→改派返仓单；客户拒收→客户拒收单 |
| `screen-history` | 配送历史记录 | `ui/profile/` 下历史页 | P3 | 对齐 |
| `screen-my` | 我的（菜单入口） | `ui/profile/profile_page.dart` | P3 | 对齐 |

**原型未覆盖、需按方案补齐的 APP 流程**（开发时新增页面）：

| 方案流程 | 新增 Flutter 页面 | 阶段 | 说明 |
|---------|-------------------|------|------|
| 装车确认 | `ui/loading/loading_scan_page.dart` / `loading_confirm_page.dart` | P2 | 原型无装车屏，按方案 4.3.2 补 |
| 返仓交接 | `ui/warehouse/warehouse_return_page.dart` | P3 | 原型仅 toast 提示带回，按方案补交接清单 |
| 交账结算 | `ui/settlement/settlement_submit_page.dart` / `settlement_photo_page.dart` | P3 | 原型无交账屏，按方案 6.1 补 |
| 门店定位纠偏 | `ui/delivery/store_location_edit_page.dart` | P4 | 原型无，按方案 7 补 |

### C.3 原型与方案的口径差异（以方案为准）

| 差异点 | 原型 | 方案/本计划 | 处理 |
|--------|------|------------|------|
| 退货单号前缀 | `RT20260610001` | 复用系统现有退货单规则 `XTSQ/THRK/XSTH` + yyyyMMdd + 4位流水 | 以系统现有规则为准（`BillNoGenerator.SALES_RETURN_REQ/IN/RETURN`） |
| 退货流程深度 | 仅「确认回收装车」toast | 逐品核对 + 拍照 + 签名 + 返仓验收 + 入库入账 | 按方案 5.2 全链路实现 |
| 拒收/无法配送 | toast「随车带回仓库入库」 | 拆为两类独立单据：改派返仓单（客户不在，不反审核出库单，验收后回调度池）+ 客户拒收单（仓库收货生成拒收入库单，库存+N） | 按方案 3.1.7/3.1.8 实现 |
| 装车/发车 | 无 | 装车扫码确认 + 发车 | 按方案 4.3.2 实现 |
| 交账 | 无 | 交账单提交 + 财务审核 | 按方案 6 实现 |

### C.4 仓库 PDA 原型复用（返仓验收）

[prototype/wms-pda/index.html](file:///e:/我的工作项目/erp-wms-tms/prototype/wms-pda/index.html) 已含「拒收入库 / 司机拒收商品入库操作」屏，与本计划 P3 返仓验收对接关系：

```
司机 APP（返仓交接确认：改派返仓品 + 客户拒收品 + 退货回收品）
      ↓ 上报返仓清单
ERP 后端（tms_reschedule_return / tms_customer_reject / tms_driver_return）
      ↓ 生成验收/收货任务
仓库 PDA（复用 wms-pda 拒收入库屏，扫码逐品验收）
      ↓ 验收确认
ERP 后端：
  · 改派返仓 → 不反审核出库单、库存不变，原发货单回调度池标记【返仓改派】
  · 客户拒收 → 生成拒收入库单（JHRK），审核后库存 +N、撤销应收
  · 司机退货 → 退货入库 → fin_ar 冲减
```

**结论**：返仓验收不另起 PDA 工程，复用现有 `wms-pda` 原型与未来 PDA 工程，TMS 仅在后端提供验收接口（改派返仓 `/tms/reschedule-return/{id}/check`、客户拒收 `/tms/customer-reject/{id}/receive`），PDA 端作为 wms-pda 的扩展任务类型接入。

### C.5 ERP 端调度管理原型（新建）

ERP 端 [v1-erp-complete/index.html](file:///e:/我的工作项目/erp-wms-tms/prototype/v1-erp-complete/index.html) 的导航（`nav` 对象）现有 8 个分组，**无 TMS/配送管理**。本计划需：

1. **原型先行**：在 `prototype/v1-erp-complete/` 下新增 `07-tms/` 目录，产出调度管理高保真原型页（参考现有 `02-sales/`、`04-inventory/` 的页面结构），建议页面：
   - `dispatch-pool.html`（配送任务池）
   - `dispatch-schedule.html`（排线调度）
   - `dispatch-list.html`（调度单列表）
   - `delivery-monitor.html`（在途监控）
   - `reschedule-return.html`（改派返仓：客户不在/地址错误等，验收后回调度池，标记【返仓改派】）
   - `customer-reject.html`（客户拒收单：仓库收货生成拒收入库单，库存+N）
   - `settlement.html`（交账管理）
   - `dispatch-dashboard.html`（调度看板）
2. **导航注册**：在 `v1-erp-complete/index.html` 的 `nav` 对象新增 `配送管理` 分组。
3. **前端实现**：原型评审通过后，按本计划 7.3 节页面清单在 `frontend/src/views/tms/` 落地 Vue 页面，复用 `ProTable` + `BillDrawer` 组件范式。

> 建议在 P1 启动前（或 P1 第 1 周内）先产出 ERP 端调度管理原型，避免前端开发与原型脱节。APP 原型已就绪，可直接进入 Flutter 开发。
