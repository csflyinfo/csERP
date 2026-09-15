# 验收证据：PRD-34 业务期初初始化浏览器真实交互

由 [scripts/init-m7-ui-verify.js](../scripts/init-m7-ui-verify.js)（Edge headless + CDP 驱动）在隔离环境（8081 全新冒烟库 + vite 5174 代理）下真实点击产出，2026-09-15。

| 截图 | 页面 / 动作 |
|---|---|
| 01-stock-import-dialog.png | 库存期初 · 真实模板导入弹窗（双页签模板入口） |
| 02-stock-posted.png | 库存期初建账后：批号 QC202609150001、2 行 / 18 个 / ¥138.00、库位账 ID 回写 |
| 03-stock-reverse-dialog.png | 反建账弹窗：原因必填留痕、守卫说明 |
| 04-stock-reversed.png | 反建账后恢复可编辑 |
| 05-ar-line.png | 应收期初手工行（CUSINIT / UIAR1 / 500.00） |
| 06-ar-posted.png | 应收建账后：批号 QC202609150002、正式账单号 AR202609150001 回显 |
| 07-ap-line.png | 应付期初手工行（SUPINIT / UIAP1 / 300.00） |
| 08-ap-posted.png | 应付建账后：批号 QC202609150003、正式账单号回显 |

覆盖交互：菜单可见 / 登录、SearchSelect 拼音下拉与库位按仓库级联、手工新增行、编辑改数量、导入弹窗、建账 confirm、反建账原因弹窗。接口级断言见 m1~m6 脚本。
