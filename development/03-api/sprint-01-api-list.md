# Sprint 1 API 清单

## 一、认证

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/auth/login` | POST | 登录 |
| `/api/auth/logout` | POST | 退出 |
| `/api/auth/current-user` | GET | 当前用户信息 |
| `/api/auth/refresh-token` | POST | 刷新Token |

## 二、菜单与权限

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/system/menu/user-tree` | GET | 当前用户菜单树 |
| `/api/system/permission/buttons` | GET | 当前页面按钮权限 |
| `/api/system/permission/fields` | GET | 当前页面字段权限 |

## 三、用户管理

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/system/user/page` | POST | 用户分页 |
| `/api/system/user/detail` | GET | 用户详情 |
| `/api/system/user/create` | POST | 新建用户 |
| `/api/system/user/update` | POST | 修改用户 |
| `/api/system/user/stop` | POST | 停用用户 |
| `/api/system/user/enable` | POST | 启用用户 |
| `/api/system/user/reset-password` | POST | 重置密码 |

## 四、权限组

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/system/role/page` | POST | 权限组分页 |
| `/api/system/role/detail` | GET | 权限组详情 |
| `/api/system/role/create` | POST | 新建权限组 |
| `/api/system/role/update` | POST | 修改权限组 |
| `/api/system/role/save-permissions` | POST | 保存权限配置 |

## 五、系统配置

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/system/param/page` | POST | 系统参数分页 |
| `/api/system/param/update` | POST | 修改参数 |
| `/api/system/bill-no-rule/page` | POST | 单据编号规则分页 |
| `/api/system/bill-no-rule/update` | POST | 修改编号规则 |
| `/api/system/operation-log/page` | POST | 操作日志分页 |

## 六、基础资料骨架

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/base/category/page` | POST | 商品分类分页 |
| `/api/base/category/create` | POST | 新建商品分类 |
| `/api/base/category/update` | POST | 修改商品分类 |
| `/api/base/unit/page` | POST | 单位分页 |
| `/api/base/unit/create` | POST | 新建单位 |
| `/api/base/brand/page` | POST | 品牌分页 |
| `/api/base/brand/create` | POST | 新建品牌 |
| `/api/base/warehouse/page` | POST | 仓库分页 |
| `/api/base/warehouse/create` | POST | 新建仓库 |
