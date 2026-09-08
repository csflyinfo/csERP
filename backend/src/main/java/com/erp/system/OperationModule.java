package com.erp.system;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作日志「模块」码（点分）与中文名。
 *
 * <p>模块码标识功能归属（如采购订单 {@code purchase.order}），用于日志按模块过滤；
 * 业务对象类型（biz_type，如 {@code purchase_order}）另见 {@link KeyFields} 的 BIZ_TYPE 常量，
 * 用于按单据聚合时间线。历史数据里出现过的大写下划线码（SALES_ORDER 等）在查询侧兼容显示。
 */
public final class OperationModule {

    // 系统
    public static final String SYSTEM        = "system";
    public static final String AUTH          = "system.auth";
    public static final String OP_LOG        = "system.log";
    public static final String PARAM         = "system.param";

    // 基础资料
    public static final String BASE_GOODS    = "base.goods";
    public static final String BASE_CUSTOMER = "base.customer";
    public static final String BASE_SUPPLIER = "base.supplier";
    public static final String BASE_WAREHOUSE= "base.warehouse";

    // 采购
    public static final String PURCHASE_ORDER  = "purchase.order";
    public static final String PURCHASE_INBOUND= "purchase.inbound";
    public static final String PURCHASE_RETURN = "purchase.return";

    // 销售
    public static final String SALES_ORDER    = "sales.order";
    public static final String SALES_OUTBOUND = "sales.outbound";
    public static final String SALES_RETURN   = "sales.return";
    public static final String SALES_RECEIPT  = "sales.receipt";
    public static final String SALES_REJECT   = "sales.reject";

    // 财务补充
    public static final String FIN_EXPENSE   = "finance.expense";

    // 库存
    public static final String INV_OTHER_IN  = "inventory.other-in";
    public static final String INV_OTHER_OUT = "inventory.other-out";
    public static final String INV_TRANSFER  = "inventory.transfer";
    public static final String INV_DAMAGE    = "inventory.damage";
    public static final String INV_STOCK_TAKE= "inventory.stock-take";
    public static final String INV_COST_ADJUST = "inventory.cost-adjust";

    // 财务
    public static final String FIN_RECEIPT  = "finance.receipt";
    public static final String FIN_PAYMENT  = "finance.payment";

    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put(SYSTEM, "系统管理");
        NAMES.put(AUTH, "登录认证");
        NAMES.put(OP_LOG, "操作日志");
        NAMES.put(PARAM, "参数设置");
        NAMES.put(BASE_GOODS, "商品档案");
        NAMES.put(BASE_CUSTOMER, "客户档案");
        NAMES.put(BASE_SUPPLIER, "供应商档案");
        NAMES.put(BASE_WAREHOUSE, "仓库档案");
        NAMES.put(PURCHASE_ORDER, "采购订单");
        NAMES.put(PURCHASE_INBOUND, "采购入库");
        NAMES.put(PURCHASE_RETURN, "采购退货");
        NAMES.put(SALES_ORDER, "销售订单");
        NAMES.put(SALES_OUTBOUND, "销售出库");
        NAMES.put(SALES_RETURN, "销售退货");
        NAMES.put(SALES_RECEIPT, "销售签收");
        NAMES.put(SALES_REJECT, "拒收入库");
        NAMES.put(FIN_EXPENSE, "费用单");
        NAMES.put(INV_OTHER_IN, "其他入库");
        NAMES.put(INV_OTHER_OUT, "其他出库");
        NAMES.put(INV_TRANSFER, "库存调拨");
        NAMES.put(INV_DAMAGE, "报损报溢");
        NAMES.put(INV_STOCK_TAKE, "库存盘点");
        NAMES.put(INV_COST_ADJUST, "成本调整");
        NAMES.put(FIN_RECEIPT, "收款单");
        NAMES.put(FIN_PAYMENT, "付款单");

        // 历史模块码兼容（各控制器旧 log() 里沿用的字符串，统一给中文名）
        NAMES.put("inventory.otherInbound", "其他入库");
        NAMES.put("inventory.otherOutbound", "其他出库");
        NAMES.put("inventory.damage", "报损报溢");
        NAMES.put("transfer.apply", "库存调拨");
        NAMES.put("transfer.inbound", "调拨入库");
        NAMES.put("transfer.outbound", "调拨出库");
        NAMES.put("purchase.inbound", "采购入库");
        NAMES.put("purchase.receipt", "采购收货");
        NAMES.put("purchase.return.apply", "采购退货申请");
        NAMES.put("purchase.return.outbound", "采购退货出库");
        NAMES.put("sales.rejectInbound", "拒收入库");
        NAMES.put("sales.return.inbound", "销售退货入库");
        NAMES.put("sales.return.order", "销售退货");
        NAMES.put("base.counterparty", "往来单位");
        NAMES.put("base.priceGroup", "价格组");
        NAMES.put("base.employee", "人员档案");
        NAMES.put("base.department", "部门档案");
        NAMES.put("base.territory", "片区档案");
        NAMES.put("base.routeLine", "线路档案");
        NAMES.put("base.expenseType", "费用类型");
        NAMES.put("base.fundAccount", "资金账户");
        NAMES.put("base.owner", "货主档案");
        NAMES.put("report.export", "报表中心");
        NAMES.put("system.excel", "导入导出");
        NAMES.put("sales.flyOrder", "快速开单");

        // TMS 调度/司机配送（PRD-31：TMS/WMS 日志委托统一服务后补中文名）
        NAMES.put("tms.dispatch", "调度派车");
        NAMES.put("tms.app.delivery", "司机配送");
        NAMES.put("tms.app.arrive", "司机到店");
        NAMES.put("tms.sign", "签收核验");
        NAMES.put("tms.customer-reject", "客户拒收");
        NAMES.put("tms.app.customer-reject", "司机客户拒收");
        NAMES.put("tms.exception", "异常上报");
        NAMES.put("tms.app.exception", "司机异常上报");
        NAMES.put("tms.reschedule-return", "改约退货");
        NAMES.put("tms.app.reschedule-return", "司机改约退货");
        NAMES.put("tms.app.return", "司机退货");
        NAMES.put("tms.app.warehouse-return", "仓库退货确认");
        NAMES.put("tms.return-dispatch", "退货调度");
        NAMES.put("tms.settlement", "司机交账");
        NAMES.put("tms.app.settlement", "司机交账");
        NAMES.put("tms.store-location", "门店定位");
        NAMES.put("tms.app.store-location", "门店定位上报");
        NAMES.put("tms.driverFundAccount", "司机资金账户");
        NAMES.put("tms.storeSettle", "门店结算");

        // WMS 仓储作业
        NAMES.put("wms.container", "容器管理");
        NAMES.put("wms.base", "仓储基础资料");
        NAMES.put("wms.inbound", "入库作业");
        NAMES.put("wms.move", "移库作业");
        NAMES.put("wms.freeze", "库存冻结");
        NAMES.put("wms.adjust", "库存调整");
        NAMES.put("wms.damage", "仓储报损");
        NAMES.put("wms.stocktake", "仓储盘点");
    }

    private OperationModule() {}

    /** 模块码 → 中文名；未知码原样返回。 */
    public static String name(String code) {
        if (code == null) return "";
        String n = NAMES.get(code);
        if (n != null) return n;
        // 兼容历史大写下划线模块码（如 SALES_ORDER）
        String dotted = code.toLowerCase().replace('_', '.');
        return NAMES.getOrDefault(dotted, code);
    }
}
