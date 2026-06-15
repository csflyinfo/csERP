# 前端模块规划

## 一、工程建议结构

```text
frontend/
├─ src
│  ├─ app              # 应用入口、路由、权限
│  ├─ assets
│  ├─ components
│  │  ├─ ProTable      # 通用表格
│  │  ├─ ProForm       # 通用表单
│  │  ├─ BillPage      # 单据页框架
│  │  ├─ FieldSetting  # 字段设置
│  │  └─ ImportExport  # 导入导出
│  ├─ layouts
│  │  └─ AdminLayout
│  ├─ modules
│  │  ├─ system
│  │  ├─ base
│  │  ├─ inventory
│  │  ├─ purchase
│  │  ├─ sales
│  │  ├─ finance
│  │  └─ report
│  ├─ services
│  ├─ stores
│  └─ utils
```

## 二、布局基线

以 `prototype/v1-erp-complete/index.html` 最新原型为基准：

- 左侧菜单顶部放模块快捷搜索。
- 当前模块标题放顶部导航栏。
- 查询条件紧凑展示，默认展示主要条件。
- 工具按钮紧凑排列。
- 表格优先展示更多记录。
- 简单资料用小弹窗，中等资料用抽屉，复杂单据用独立页面。

## 三、Sprint 1 前端任务

1. 初始化前端工程。
2. 登录页。
3. 主布局 AdminLayout。
4. 左侧菜单 + 快捷搜索定位菜单。
5. 顶部模块标题与操作按钮。
6. 通用列表组件：查询区、表格、分页、工具栏。
7. 通用小弹窗表单。
8. 通用抽屉表单。
9. 字段设置弹窗骨架。
10. 系统管理页面骨架。
11. 商品分类、单位、品牌、仓库页面骨架。

## 四、组件优先级

| 组件 | 优先级 | 说明 |
|---|---|---|
| AdminLayout | P0 | 主框架 |
| ProTable | P0 | 所有列表复用 |
| ProForm | P0 | 新增/编辑复用 |
| ModalForm | P0 | 简单资料 |
| DrawerForm | P0 | 中等资料 |
| BillPage | P1 | 业务单据 |
| ImportDialog | P1 | 导入 |
| FieldSetting | P1 | 字段个性化 |
