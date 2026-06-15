# 后端模块规划

## 一、工程建议结构

```text
backend/
├─ src/main/java/com/company/erp
│  ├─ common
│  │  ├─ api        # 统一响应、分页、错误码
│  │  ├─ auth       # 登录认证、Token
│  │  ├─ exception  # 异常处理
│  │  ├─ log        # 操作日志切面
│  │  └─ utils
│  ├─ system
│  │  ├─ user
│  │  ├─ role
│  │  ├─ menu
│  │  ├─ param
│  │  ├─ billno
│  │  └─ oplog
│  ├─ base
│  │  ├─ goods
│  │  ├─ category
│  │  ├─ unit
│  │  ├─ brand
│  │  ├─ customer
│  │  ├─ supplier
│  │  ├─ warehouse
│  │  └─ price
│  ├─ inventory
│  ├─ purchase
│  ├─ sales
│  ├─ finance
│  └─ report
└─ resources
   ├─ mapper
   └─ application.yml
```

## 二、Sprint 1 后端任务

1. 工程初始化。
2. 统一响应结构。
3. 全局异常处理。
4. 登录认证。
5. 用户表、角色表、菜单表 CRUD。
6. 当前用户菜单树。
7. 系统参数 CRUD。
8. 单据编号规则 CRUD。
9. 操作日志记录。
10. 商品分类、单位、品牌、仓库基础 CRUD。

## 三、关键服务接口

| 服务 | 说明 |
|---|---|
| AuthService | 登录、退出、当前用户 |
| PermissionService | 菜单权限、按钮权限、字段权限 |
| BillNoService | 生成业务单号 |
| OperationLogService | 记录操作日志 |
| ImportExportService | 导入导出任务预留 |
