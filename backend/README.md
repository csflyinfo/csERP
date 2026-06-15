# ERP-WMS-TMS Backend

Spring Boot 模块化单体后端工程骨架。

## 环境要求

- JDK 17+
- Maven 3.9+

## 启动

### Spring Boot 启动

```bash
mvn spring-boot:run
```

默认地址：

```text
http://localhost:8080/api
```

### 当前环境临时 Mock API 启动

如果本机暂未安装 JDK/Maven，可先用 Node.js 启动 Mock API，便于前端联调：

```bash
node mock-server.js
```

Mock API 默认同样监听：

```text
http://localhost:8080/api
```

## Demo 登录

```json
{
  "username": "admin",
  "password": "admin123"
}
```

## 已包含

- 统一响应 `ApiResponse`
- 分页请求/响应结构
- 全局异常处理
- 登录/退出/当前用户 Demo 接口
- 当前用户菜单 Demo 接口
- 用户/系统参数/单据编号/操作日志分页 Demo 接口
- 商品分类/单位/品牌/仓库分页 Demo 接口
- 商品分类新建校验：分类编号必须为两位数字

## 下一步

1. 接入真实数据库。
2. 引入 ORM / MyBatis。
3. 实现用户、权限、菜单真实 CRUD。
4. 实现基础资料真实 CRUD。
5. 增加接口测试。
