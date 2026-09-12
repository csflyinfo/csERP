package com.erp.system.perm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 三端菜单/页面代码化声明（PRD-28 §6.4.1、§7.1）。
 *
 * <p>新增模块在这里加 {@code .page(...)} 即可，{@code PermissionRegistry} 启动时同步入 sys_menu_meta，
 * 不需要写 Flyway。菜单编码（menu_code）同时是功能点编码前缀（func_code = menu_code + "." + 动作）。
 *
 * <p>默认名称/上级/排序允许管理员在【模块菜单管理】调整（*_customized 标志保护）；
 * 路由/组件/类型/图标/admin_only 由代码强制同步。
 */
@Configuration
public class MenuConfig {

    @Bean
    public List<MenuNode> menuRoots() {
        return List.of(
                dashboardMenus(),
                baseMenus(),
                purchaseMenus(),
                salesMenus(),
                inventoryMenus(),
                financeMenus(),
                reportMenus(),
                systemMenus(),
                wmsMenus(),
                tmsMenus(),
                wmsPdaMenus(),
                driverMenus()
        );
    }

    private MenuNode dashboardMenus() {
        return MenuNode.rootDir("dashboard", "工作台", "Odometer", "ERP")
                .page("dashboard.overview", "经营概览", "/dashboard")
                .page("dashboard.todo", "待办中心", "/todo")
                .page("dashboard.notification", "消息通知", "/notification");
    }

    private MenuNode baseMenus() {
        return MenuNode.rootDir("base", "基础资料", "Files", "ERP")
                .page("base.goods", "商品档案", "/base/goods")
                .page("base.category", "商品分类", "/base/category")
                .page("base.brand", "品牌管理", "/base/brand")
                .page("base.unit", "单位管理", "/base/unit")
                .page("base.customer", "门店/客户资料", "/base/customer")
                .page("base.supplier", "供应商资料", "/base/supplier")
                .page("base.warehouse", "仓库资料", "/base/warehouse")
                .page("base.price_group", "价格组设置", "/base/price-group")
                .page("base.price_group_goods", "价格组商品查询", "/base/price-group-item")
                .page("base.price_adjust", "价格组调价单", "/base/price-adjust-order")
                .page("base.goods_price_adjust", "商品综合调价单", "/base/goods-price-adjust")
                .page("base.price_change_query", "商品变价查询", "/base/price-change-log")
                .page("base.customer_price", "客户价格调整单", "/base/customer-price-adjust")
                .page("base.customer_price_query", "客户价格查询", "/base/customer-price-query")
                .page("base.customer_price_change", "客户商品变价查询", "/base/customer-price-change")
                .page("base.region", "片区管理", "/base/territory")
                .page("base.route", "线路管理", "/base/route-line")
                .page("base.employee", "人员信息", "/base/employee")
                .page("base.department", "部门管理", "/base/department")
                .page("base.owner", "货主信息", "/base/owner")
                .page("base.fee_type", "费用类型", "/base/expense-type")
                .page("base.other_unit", "往来单位", "/base/counterparty")
                .page("base.fund_account", "资金账户", "/base/fund-account");
    }

    private MenuNode purchaseMenus() {
        return MenuNode.rootDir("purchase", "采购管理", "ShoppingCart", "ERP")
                .statePage("purchase.order", "采购订单", "/purchase/order")
                .statePage("purchase.inbound", "采购入库", "/purchase/inbound")
                .page("purchase.receipt", "采购收货单", "/purchase/receipt")
                .page("purchase.return_apply", "采购退货申请", "/purchase/return-apply")
                .page("purchase.return_outbound", "采购退货出库", "/purchase/return-outbound")
                .page("purchase.return_bill", "采购退货单", "/purchase/return")
                .page("purchase.fee", "采购费用单", "/purchase/expense")
                .page("purchase.invoice", "采购发票", "/purchase/invoice");
    }

    private MenuNode salesMenus() {
        return MenuNode.rootDir("sales", "销售管理", "Sell", "ERP")
                .page("sales.quick", "销售快速开单", "/sales/quick-order")
                .statePage("sales.order", "销售订单", "/sales/order")
                .statePage("sales.outbound", "销售出库", "/sales/outbound")
                .page("sales.receipt", "销售发货单", "/sales/receipt")
                .page("sales.reject_inbound", "拒收入库单", "/sales/reject-inbound")
                .statePage("sales.return", "销售退货单", "/sales/return")
                .page("sales.return_inbound", "销售退货入库", "/sales/return-inbound")
                .page("sales.invoice", "销售发票", "/sales/invoice")
                .page("sales.flying", "飞单", "/sales/fly-order")
                .page("sales.empty_adjust", "客户空退空出", "/sales/empty-adjust");
    }

    private MenuNode inventoryMenus() {
        return MenuNode.rootDir("inventory", "库存管理", "Box", "ERP")
                .page("inv.balance", "库存查询", "/inventory/balance")
                .page("inv.flow", "库存流水", "/inventory/ledger")
                .page("inv.warning", "库存预警", "/inventory/warning")
                .page("inv.transfer_apply", "调拨申请单", "/inventory/transfer-apply")
                .statePage("inv.transfer_out", "调拨出库单", "/inventory/transfer-out")
                .page("inv.transfer_in", "调拨入库单", "/inventory/transfer-in")
                .statePage("inv.damage", "报损单", "/inventory/damage")
                .page("inv.cost_adjust", "成本调整单", "/inventory/cost-adjust")
                .page("inv.adjust", "库存调整单", "/inventory/stock-adjust")
                .page("inv.other_in", "其他入库", "/inventory/other-inbound")
                .page("inv.other_out", "其他出库", "/inventory/other-outbound")
                .statePage("inv.count", "库存盘点", "/inventory/stock-take");
    }

    private MenuNode financeMenus() {
        MenuNode fin = MenuNode.rootDir("finance", "财务管理", "Money", "ERP")
                .page("fin.ar_detail", "客户应收明细", "/finance/ar")
                .page("fin.ap", "应付账款", "/finance/ap")
                .page("fin.receipt", "收款单", "/finance/receipt")
                .page("fin.payment", "付款单", "/finance/payment")
                .page("fin.receipt_writeoff", "收款核销流水", "/finance/reconcile-record")
                .page("fin.ar_settle", "应收结算", "/finance/ar-settlement")
                .page("fin.ap_settle", "应付结算", "/finance/ap-settlement")
                .page("fin.fee", "费用单", "/finance/expense")
                .page("fin.fund_flow", "资金流水", "/finance/fund-ledger")
                .page("fin.other_ar", "往来单位应收", "/finance/counterparty-ar")
                .page("fin.other_ap", "往来单位应付", "/finance/counterparty-ap")
                .page("fin.receipt_verify", "收款核销", "/finance/receipt-verify")
                .page("fin.payment_verify", "付款核销", "/finance/payment-verify")
                .page("fin.customer_recon", "客户对账", "/finance/customer-statement")
                .page("fin.supplier_recon", "供应商对账", "/finance/supplier-statement");
        // 三级示例：财务管理(L1) > 总账(L2 目录) > 凭证/账簿...(L3 页面)
        fin.dir("finance.gl", "总账", "Notebook")
                .page("finance.gl.account", "会计科目", "/gl/account")
                .page("finance.gl.init_balance", "总账初始化", "/gl/init-balance")
                .page("finance.gl.aux_project", "核算项目", "/gl/aux-project")
                .statePage("finance.gl.voucher", "凭证管理", "/gl/voucher")
                .page("finance.gl.event", "待生成凭证", "/gl/event")
                .page("finance.gl.voucher_template", "凭证模板", "/gl/voucher-template")
                .page("finance.gl.transfer_template", "转账模板", "/gl/transfer-template")
                .page("finance.gl.biz_subject_map", "业务类型映射", "/gl/biz-subject-map")
                .page("finance.gl.archive_mapping", "档案科目映射", "/gl/archive-mapping")
                .page("finance.gl.book", "账簿查询", "/gl/ledger")
                .page("finance.gl.asset_card", "资产卡片", "/gl/asset-card")
                .page("finance.gl.depreciation", "折旧计提", "/gl/depreciation")
                .page("finance.gl.asset_check", "资产盘点", "/gl/asset-check")
                .page("finance.gl.period_close", "期末处理", "/gl/period-close")
                .page("finance.gl.report", "总账报表", "/gl/report")
                .page("finance.gl.reconcile", "业财对账", "/gl/reconcile");
        return fin;
    }

    private MenuNode reportMenus() {
        // 报表中心一期：采购五表（统一元数据驱动，/report/center/{code} 入口）
        MenuNode node = MenuNode.rootDir("report", "报表中心", "TrendCharts", "ERP")
                .page("report.purchase_order_detail", "采购订单明细查询", "/report/purchase-order-detail")
                .page("report.purchase_move_detail", "采购明细查询", "/report/purchase-move")
                .page("report.purchase_goods_summary", "商品采购汇总表", "/report/purchase-goods-summary")
                .page("report.purchase_supplier_summary", "供应商商品采购汇总表", "/report/purchase-supplier-summary")
                .page("report.purchase_forecast", "商品采购预测分析", "/report/purchase-forecast")
                // 报表中心二期：库存两表 + 销售七表（统一元数据驱动）
                .page("report.inventory_roll", "商品进销存汇总表", "/report/inventory-roll")
                .page("report.stock_ledger", "商品库存台账", "/report/stock-ledger")
                .page("report.sales_goods_summary", "商品销售汇总表", "/report/sales-goods-summary")
                .page("report.customer_goods_summary", "客户商品销售汇总表", "/report/customer-goods-summary")
                .page("report.customer_summary", "客户销售汇总表", "/report/customer-summary")
                .page("report.salesman_summary", "业务员销售汇总表", "/report/salesman-summary")
                .page("report.salesman_goods_summary", "业务员商品销售汇总表", "/report/salesman-goods-summary")
                .page("report.sales_order_detail", "销售订单明细查询", "/report/sales-order-detail")
                .page("report.sales_move_detail", "商品销售明细表", "/report/sales-move")
                .page("report.chart", "图表报表", "/report/chart")
                .page("report.sales", "销售报表", "/report/sales")
                .page("report.purchase", "采购报表", "/report/purchase")
                .page("report.inventory", "库存报表", "/report/stock")
                .page("report.finance", "财务报表", "/report/finance")
                .page("report.invoice_track", "采购来票跟踪(按单据)", "/report/invoice-track")
                .page("report.invoice_track_goods", "采购来票跟踪(按商品)", "/report/invoice-track-goods")
                .page("report.invoice_supplier", "供应商来票统计", "/report/invoice-supplier")
                .page("report.invoice_unmatched", "未勾稽发票", "/report/invoice-unmatched")
                .page("report.invoice_diff", "勾稽差异明细", "/report/invoice-diff");
        // 报表运维（手工重算 DWS/快照）仅 SYS_ADMIN，不进角色授权树
        node.adminPage("report.admin", "报表运维", "/report/admin");
        return node;
    }

    private MenuNode systemMenus() {
        // 用户/角色/模块菜单管理三项仅 SYS_ADMIN 可见；system.menu 额外 admin_only，授权树永不出现
        return MenuNode.rootDir("system", "系统管理", "Setting", "ERP")
                .adminPage("system.user", "用户管理", "/system/user")
                .adminPage("system.role", "角色/权限组管理", "/system/role")
                .adminPage("system.menu", "模块菜单管理", "/system/menu")
                .page("system.param", "系统参数", "/system/param")
                .page("system.param_setting", "参数设置", "/system/param-setting")
                .page("system.bill_no_rule", "单据编号规则", "/system/bill-no-rule")
                .page("system.precision", "显示精度设置", "/system/precision")
                .page("system.dictionary", "用户数据字典", "/system/dictionary")
                .page("system.workflow", "审批流配置", "/system/workflow")
                .page("system.print_template", "打印模板设置", "/system/print-template")
                .page("system.import_list", "导入列表", "/system/import-list")
                .page("system.export_center", "导出中心", "/system/export-center")
                .page("system.log", "操作日志", "/system/operation-log")
                .page("system.login_log", "登录日志", "/system/login-log");
    }

    private MenuNode wmsMenus() {
        return MenuNode.rootDir("wms", "仓储作业(WMS)", "Warehouse", "ERP")
                .page("wms.dashboard", "作业看板", "/wms/dashboard")
                .statePage("wms.prealloc", "预分配/下放", "/wms/preallocation")
                .page("wms.wave", "波次管理", "/wms/wave")
                .statePage("wms.pick", "拣货任务", "/wms/pick")
                .page("wms.sort_instruction", "分拣指令查询", "/wms/sort-instruction")
                .page("wms.check", "复核/打包", "/wms/check")
                .page("wms.load", "装车/发运", "/wms/load")
                .statePage("wms.inbound", "入库任务", "/wms/inbound")
                .page("wms.putaway", "上架任务", "/wms/putaway")
                .page("wms.replenish", "补货管理", "/wms/replenish")
                .page("wms.move", "移库移位", "/wms/move")
                .page("wms.adjust", "库存调整", "/wms/adjust")
                .page("wms.damage", "报损管理", "/wms/damage")
                .page("wms.freeze", "库存冻结", "/wms/freeze")
                .page("wms.assembly", "组装拆卸", "/wms/assembly")
                .statePage("wms.count", "盘点管理", "/wms/stocktake")
                .page("wms.expiry", "效期管理", "/wms/expiry")
                .page("wms.zone", "库区管理", "/wms/zone")
                .page("wms.bin", "库位管理", "/wms/bin")
                .page("wms.collect_zone", "集货区管理", "/wms/collect-zone")
                .page("wms.pick_binding", "拣货位商品绑定", "/wms/pick-binding")
                .page("wms.bin_stock", "库位库存", "/wms/bin-stock")
                .page("wms.stock_query", "WMS库存查询", "/wms/stock-query")
                .page("wms.exception", "异常中心", "/wms/exception");
    }

    private MenuNode tmsMenus() {
        return MenuNode.rootDir("tms", "运输管理", "Van", "ERP")
                .page("tms.pool", "配送任务池", "/tms/dispatch-pool")
                .statePage("tms.dispatch", "调度单管理", "/tms/dispatch-list")
                .page("tms.return_dispatch", "退货单调度", "/tms/return-dispatch")
                .page("tms.monitor", "在途监控", "/tms/delivery-monitor")
                .page("tms.sign_verify", "签收核销", "/tms/sign-verify")
                .page("tms.driver_return", "司机退货单", "/tms/driver-return")
                .page("tms.reschedule", "改派返仓单", "/tms/reschedule-return")
                .page("tms.customer_reject", "客户拒收单", "/tms/customer-reject")
                .page("tms.exception", "异常上报处理", "/tms/exception-report")
                .page("tms.handover", "交账单管理", "/tms/settlement")
                .page("tms.store_location", "门店定位审核", "/tms/store-location")
                .page("tms.board", "调度看板", "/tms/dashboard");
    }

    private MenuNode wmsPdaMenus() {
        return MenuNode.rootDir("wms_pda", "仓库PDA", "Cellphone", "WMS_PDA")
                .page("wms_pda.home", "我的任务", "/home")
                .page("wms_pda.receive", "收货作业", "/receive")
                .page("wms_pda.receive_return", "退货/拒收收货", "/receive-return")
                .page("wms_pda.other_inbound", "其他入库", "/other-inbound")
                .page("wms_pda.putaway", "上架作业", "/putaway")
                .page("wms_pda.replenish", "补货", "/replenish")
                .page("wms_pda.pick", "拣货作业", "/pick")
                .page("wms_pda.check", "复核打包", "/check")
                .page("wms_pda.load", "装车确认", "/load")
                .page("wms_pda.stocktake", "盘点", "/stocktake")
                .page("wms_pda.move", "移库", "/move")
                .page("wms_pda.damage", "报损", "/damage")
                .page("wms_pda.stock_query", "库存查询", "/stock-query")
                .page("wms_pda.exception", "异常中心", "/exception")
                .page("wms_pda.task_assign", "任务分派", "/task-assign")
                .page("wms_pda.performance", "绩效", "/performance")
                .page("wms_pda.profile", "我的", "/profile");
    }

    private MenuNode driverMenus() {
        return MenuNode.rootDir("driver", "司机APP", "Cellphone", "DRIVER")
                .page("driver.home", "首页/当前任务", "/home")
                .page("driver.loading", "装车确认", "/loading")
                .page("driver.depart", "发车", "/depart")
                .page("driver.delivering", "配送中", "/delivering")
                .page("driver.arrive", "到店打卡", "/arrive")
                .page("driver.sign", "签收", "/sign")
                .page("driver.settlement", "门店结算", "/settlement")
                .page("driver.handover", "交账", "/handover")
                .page("driver.return", "退货回收", "/return")
                .page("driver.exception", "异常上报", "/exception")
                .page("driver.history", "配送历史", "/history")
                .page("driver.collect_records", "收款记录", "/collect-records")
                .page("driver.store_location", "门店定位", "/store-location")
                .page("driver.notification", "消息中心", "/notification")
                .page("driver.profile", "我的", "/profile");
    }
}
