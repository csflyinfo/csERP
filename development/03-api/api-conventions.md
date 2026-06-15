# API 统一规范

## 一、基础约定

- 协议：HTTP/HTTPS
- 数据格式：JSON
- 字符集：UTF-8
- 时间格式：`yyyy-MM-dd HH:mm:ss`
- 日期格式：`yyyy-MM-dd`
- 金额：后端使用 Decimal，不用浮点数

## 二、统一响应结构

```json
{
  "code": "0",
  "message": "success",
  "data": {},
  "traceId": "202606141200000001"
}
```

## 三、分页响应结构

```json
{
  "code": "0",
  "message": "success",
  "data": {
    "records": [],
    "pageNo": 1,
    "pageSize": 20,
    "total": 100,
    "summary": {}
  }
}
```

## 四、常用错误码

| code | 说明 |
|---|---|
| 0 | 成功 |
| 400 | 参数错误 |
| 401 | 未登录或登录过期 |
| 403 | 无权限 |
| 404 | 数据不存在 |
| 409 | 业务冲突，例如重复、状态不允许 |
| 500 | 系统异常 |

## 五、通用请求头

| Header | 说明 |
|---|---|
| Authorization | Bearer Token |
| X-Tenant-Id | 租户/企业ID，V1.0可预留 |
| X-Trace-Id | 链路ID |

## 六、通用列表请求

```json
{
  "pageNo": 1,
  "pageSize": 20,
  "sortField": "created_at",
  "sortOrder": "desc",
  "filters": {}
}
```

## 七、审核接口约定

审核类接口统一接收：

```json
{
  "bizId": "单据ID",
  "remark": "审核备注"
}
```

审核必须在后端事务内完成，并写入操作日志。
