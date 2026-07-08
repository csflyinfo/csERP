# 项目长期记忆

## 项目概要

- ERP-WMS-TMS：商贸经销商全链路管理系统，覆盖进销存+仓储+配送
- 成本算法：移动加权平均法（按仓库成本分组独立核算）
- 当前阶段：V1.0 Sprint 1 后期 + 阶段一优化完成（2026-07-16）

## 技术栈

- 后端：Spring Boot 3.3.6 + Java 21 + MyBatis-Plus + H2（MODE=MySQL 开发）/ MySQL 8.0（生产），Flyway 迁移
- 前端：Vue 3.5.38 + Vite 8.0.16 + Pinia 3.0.4 + 自研组件（依赖已锁定具体版本）
- 部署：Docker Compose（MySQL + Redis + Backend + Nginx）
- 实际采用模块化单体架构（非微服务）

## 已解决问题（阶段一优化，详见 `docs/优化记录-V1.0-阶段一.md`）

- ✅ 后端接口级权限：Spring Security 规则化授权 + JwtAuthFilter 挂链
- ✅ Token 键统一为 `erp-token`（注意：后端 JWT 实际用 `Authorization: Bearer` header）
- ✅ 前端 HTTP 客户端统一为 fetch（`api/client.js`），移除 axios
- ✅ 依赖版本全部锁定：vue 3.5.38 / vue-router 5.1.0 / pinia 3.0.4 / vite 8.0.16 / echarts 5.4.3 / pinyin-pro 3.28.1 / xlsx 0.18.5
- ✅ 密码严格 BCrypt，禁止明文回退
- ✅ Element Plus 已从 package.json 移除
- ✅ ECharts 改 npm 引入（不再 CDN）
- ✅ Vite / plugin-vue 移到 devDependencies
- ✅ H2 → MySQL 迁移 A–D 完成：Flyway 接入、MERGE/ADD COLUMN/CREATE INDEX 全部改写为 MySQL 兼容语法；E–H（真 MySQL 联调）待 Docker 环境
- ✅ 销售退货单 Bug 修复（2026-08-07）：退货单号字段映射 + queryForList 参数顺序
- ✅ H2 trace.db 权限问题：application.yml 添加 `TRACE_LEVEL_FILE=0` 禁用 trace 文件写入

## 未解决问题

- 🟡 前端无 TypeScript
- 🟡 飞单模块（flyOrder）：开发已完成。V35迁移+FlyOrderController（创建/更新/筛选分页/审核/反审核/作废/删除/批量操作/导出）+FlyOrderDrawer（双价格自动带出/金额反算/多单位/编辑模式）+GenericBusinessList增强（7项：金额反算/双击详情/多选批量/行操作/导出双sheet/列表列/筛选默认值）。后端编译通过，前端需手动 `npm run build`
- 🟡 module-config.js / module-api.js 单文件超千行未拆
- 🟡 远景架构文档与实际（模块化单体）不一致

## 核心文件位置

- 后端主类：`backend/src/main/java/com/erp/ErpApplication.java`
- 前端入口：`frontend/src/main.js`
- 数据库迁移：`backend/src/main/resources/db/migration/V1__schema.sql` + `V2__seed_data.sql`（Flyway 管理）
- 前端配置：`frontend/src/module-config.js` + `module-api.js`
- 启动脚本：`start.bat` / `stop.bat` / `reset-db.bat`
- Docker 部署：`docker-compose.yml`
- 阶段一优化交付：`docs/优化记录-V1.0-阶段一.md`
- 风险清单：`docs/关键问题与风险V1.0.md`

## 关键提醒

- 修改已应用的 Flyway 迁移脚本会导致 checksum 失败；schema 演进请新增 `V{N+1}__xxx.sql`
- H2 数据库文件位于 `backend/data/erp-v1.mv.db`，切换 MySQL 前记得备份
- admin 默认密码 admin123 由 seed 提供 BCrypt 哈希，重启会被覆盖（这是 V1.0 seed 行为，非 bug）
- H2 trace.db 文件可能有 Windows 权限问题导致后端无法启动；application.yml 已添加 `TRACE_LEVEL_FILE=0` 规避
- Git Bash 下 `mvn` 命令可能因路径格式问题失败（classworlds ClassNotFoundException）；需用 Windows 格式路径直接调用 Java 运行 Maven Launcher
