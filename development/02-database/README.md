# 数据库开发说明

## 目录

| 目录 | 说明 |
|---|---|
| `migrations/` | 数据库迁移脚本，按日期和序号命名 |

## 命名规范

- 表名：模块前缀 + 业务名，例如 `sys_user`、`base_goods`、`inv_stock_balance`
- 主键：业务名 + `_id`，例如 `user_id`、`goods_id`
- 单号：业务名 + `_no`，例如 `order_no`、`adjust_no`
- 审计字段：`created_by`、`created_at`、`updated_by`、`updated_at`
- 审核字段：`auditor_id`、`audit_time`
- 状态字段：`status`

## 迁移脚本顺序

1. `001_system_core.sql`：系统底座
2. `002_base_master.sql`：基础资料
3. `003_inventory_core.sql`：库存底层
4. `004_purchase_core.sql`：采购业务
5. `005_sales_core.sql`：销售业务
6. `006_finance_core.sql`：财务业务
7. `007_report_export.sql`：报表与导出中心
