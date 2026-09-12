package com.erp.report.meta;

/**
 * 报表列元数据（统一语义层的一部分）：列定义只写一次，列表渲染、合计、导出表头/取数、
 * 钻取协议全部复用，禁止各接口各写一套列。
 *
 * @param field          驼峰输出字段（SQL 别名对应）
 * @param title          中文列名（前端表头 / 导出抬头下的列名）
 * @param sensitivePerm  敏感字段权限码（VIEW_PURCHASE_PRICE 等），非敏感为 null
 * @param measure        true=度量列（参与总计）；false=维度列
 * @param defaultVisible 默认是否显示（false=列设置里默认隐藏，如预计到货日期）
 */
public record ReportColumnDef(String field, String title, String sensitivePerm,
                              boolean measure, boolean defaultVisible) {

    public static ReportColumnDef dim(String field, String title) {
        return new ReportColumnDef(field, title, null, false, true);
    }

    public static ReportColumnDef dim(String field, String title, boolean defaultVisible) {
        return new ReportColumnDef(field, title, null, false, defaultVisible);
    }

    public static ReportColumnDef measure(String field, String title, String sensitivePerm) {
        return new ReportColumnDef(field, title, sensitivePerm, true, true);
    }
}
