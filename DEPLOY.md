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

### MySQL 初始化

数据库表结构和种子数据在容器首次启动时自动初始化：
- `backend/src/main/resources/db/migration/V1__schema.sql` — 建表语句
- `backend/src/main/resources/db/migration/V2__seed_data.sql` — 初始数据

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

### 1. MySQL 初始化失败

如果 MySQL 容器报错初始化脚本执行失败，检查：
```bash
docker-compose logs mysql
```

确保 `db/migration/` 目录下没有语法错误的 SQL。

### 2. 后端连接 MySQL 超时

backend 容器会等待 MySQL 健康检查通过后才启动。如果 MySQL 启动较慢，可以延长健康检查间隔：
```yaml
healthcheck:
  interval: 30s
  retries: 10
```

### 3. 前端页面刷新 404

Nginx 已配置 `try_files $uri $uri/ /index.html;`，支持 Vue Router 的 history 模式。

### 4. 密码安全

首次登录后，admin 的明文密码会自动升级为 BCrypt 加密。生产环境请修改：
- `docker-compose.yml` 中所有默认密码
- `JWT_SECRET` 环境变量
