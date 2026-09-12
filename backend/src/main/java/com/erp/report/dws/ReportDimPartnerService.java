package com.erp.report.dws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 往来单位维度快照 rpt_dim_partner 全量刷新（随商品维度同一节拍：30 分钟 + 启动）。
 *
 * <p>客户/供应商一套表（partner_type 区分），供往来类 DWS/报表复用统一元数据：
 * 客户默认业务员取 base_customer.salesman，供应商默认采购员取 base_supplier.default_buyer。
 */
@Service
public class ReportDimPartnerService {

    private static final Logger log = LoggerFactory.getLogger(ReportDimPartnerService.class);

    private final JdbcTemplate jdbc;

    public ReportDimPartnerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全量重建（DELETE+INSERT 一个事务）。返回写入行数。 */
    @Transactional
    public int refreshAll() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT 'CUSTOMER' AS partner_type, customer_code AS partner_code,
                       customer_name AS partner_name, salesman AS default_owner, status
                FROM base_customer
                UNION ALL
                SELECT 'SUPPLIER', supplier_code, supplier_name, default_buyer, status
                FROM base_supplier
                """);
        jdbc.update("DELETE FROM rpt_dim_partner");
        for (Map<String, Object> r : rows) {
            jdbc.update("""
                    INSERT INTO rpt_dim_partner(partner_type, partner_code, partner_name,
                        default_owner, status, updated_at)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    str(r.get("PARTNER_TYPE")), str(r.get("PARTNER_CODE")),
                    str(r.get("PARTNER_NAME")), emptyToNull(str(r.get("DEFAULT_OWNER"))),
                    emptyToNull(str(r.get("STATUS"))));
        }
        log.info("rpt_dim_partner 刷新完成：{} 个往来单位", rows.size());
        return rows.size();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
