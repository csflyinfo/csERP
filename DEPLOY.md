# ERP-WMS-TMS 部署指南

## 环境要求

- Docker Engine 20.10+
- Docker Compose 2.0+
- 端口占用：80 (Nginx)、8080 (后端)、3306 (MySQL)、6379 (Redis)

## 一键部署

```bash
# 1. 进入项目目录
cd erp-wms-tms

# 2. 确保前端已构建（dist 目录存在）
cd frontend && npm run build && cd ..

# 3. 启动全部服务（MySQL + Redis + Backend + Nginx）
docker-compose up -d --build

# 4. 查看启动日志
docker-compose logs -f backend

# 5. 停止服务
docker-compose down

# 6. 完全清理（包括数据库数据）
docker-compose down -v
```

## 访问地址

| 服务 | 地址 |
|------|------|
| 前端 ERP 系统 | http://localhost |
| 后端 API | http://localhost/api |
| MySQL | localhost:3306 (root/erp_root_2024) |
| Redis | localhost:6379 (密码: erp_redis_2024) |
| H2 Console (dev 模式) | http://localhost:8080/api/h2-console |

## 初始账号

- 用户名：`admin`
- 密码：`admin123`

## 服务架构

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Nginx     │────>│   前端静态   │     │  用户浏览器  │
│   :80       │     │   dist/     │<────│             │
└─────────────┘     └─────────────┘     └─────────────┘
       │
       │ /api/* 代理
       ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Spring Boot│────>│   MySQL 8   │     │   Redis 7   │
│   :8080     │     │   :3306     │     │   :6379     │
└─────────────┘     └─────────────┘     └─────────────┘
```

## 配置说明

### 数据库初始化（Flyway）

V1.0 使用 [Flyway](https://flywaydb.org/) 进行 schema 版本管理。应用启动时自动执行未应用的迁移脚本：

- `backend/src/main/resources/db/migration/V1__schema.sql` — 建表 DDL（49 张表 + 49 条索引，MySQL 8 兼容）
- `backend/src/main/resources/db/migration/V2__seed_data.sql` — 系统 seed 数据（管理员 / 角色 / 参数 / 单据编号规则 / 默认往来单位类型 / 默认资金账户，采用 `INSERT ... ON DUPLICATE KEY UPDATE` 语法）

**首次启动**：Flyway 会自动建表并写入 seed，无需手动操作。
**升级**：后续 schema 演进直接新增 `V3__xxx.sql` / `V4__xxx.sql`，Flyway 保证同一版本只执行一次。
**元数据表**：`flyway_schema_history` 记录已执行版本。

> 说明：docker-compose.yml 不再挂载 `db/migration` 到 `docker-entrypoint-initdb.d`，避免 MySQL 首次启动跑一遍脚本与 Flyway 冲突。

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | prod | Spring 环境 |
| `SPRING_DATASOURCE_URL` | jdbc:mysql://mysql:3306/erp_v1 | 数据库连接 |
| `SPRING_DATASOURCE_USERNAME` | erp_user | 数据库用户 |
| `SPRING_DATASOURCE_PASSWORD` | erp_pass_2024 | 数据库密码 |
| `SPRING_REDIS_HOST` | redis | Redis 主机 |
| `SPRING_REDIS_PASSWORD` | erp_redis_2024 | Redis 密码 |
| `JWT_SECRET` | erp_jwt_secret_key_2024 | JWT 密钥 |

## 常见问题

### 1. Flyway 迁移失败

启动时若 `flyway_schema_history` 表报 SQL 语法错误，检查后端日志：

```bash
docker-compose logs backend | grep -A 3 Flyway
```

常见原因：`db/migration/` 里的脚本手动改过导致 checksum 不一致。**已应用的迁移脚本不允许再修改**，如需修正请新增 `V{N+1}__xxx.sql`。修复历史 checksum 只能用 `flyway repair`（生产禁用）。

### 2. MySQL 初始化失败

如果 MySQL 容器无法启动，检查：

```bash
docker-compose logs mysql
```

### 3. 后端连接 MySQL 超时

backend 容器会等待 MySQL 健康检查通过后才启动。如果 MySQL 启动较慢，可以延长健康检查间隔：
```yaml
healthcheck:
  interval: 30s
  retries: 10
```

### 4. 前端页面刷新 404

Nginx 已配置 `try_files $uri $uri/ /index.html;`，支持 Vue Router 的 history 模式。

### 5. 密码安全

V1.0 采用严格 BCrypt 校验，`data.sql` seed 密码为 `admin123` 的 BCrypt 哈希。生产环境务必：

- 修改 `docker-compose.yml` 中所有默认密码
- 修改 `JWT_SECRET` 环境变量
- 首次登录后立刻在 UI 里修改 admin 密码
