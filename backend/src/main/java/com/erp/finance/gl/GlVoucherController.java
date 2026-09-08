package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 总账——会计凭证。路由前缀 /finance/gl/voucher。
 * 状态机：草稿 →（审核）→ 已审核 →（过账）→ 已过账；草稿/已审核可作废；已过账红冲。
 */
@RestController
@RequestMapping("/finance/gl/voucher")
public class GlVoucherController {

    private final GlVoucherService voucherService;
    private final JdbcTemplate jdbc;

    public GlVoucherController(GlVoucherService voucherService, JdbcTemplate jdbc) {
        this.voucherService = voucherService;
        this.jdbc = jdbc;
    }

    /** 分页查询。body: {pageNo,pageSize,period,voucherWord,status,source,dateFrom,dateTo,keyword} */
    @PostMapping("/page")
    public ApiResponse<PageResult<Map<String, Object>>> page(@RequestBody Map<String, Object> body) {
        // 过滤条件已在 SQL 中完成；PageRequest.filters 必须为空，
        // 否则 PageResult.of 的内存模糊匹配会把 pageNo/dateFrom 等当成检索词。
        PageRequest request = new PageRequest(
                TmsUtil.toInt(body.get("pageNo") == null ? 1 : body.get("pageNo")),
                TmsUtil.toInt(body.get("pageSize") == null ? 20 : body.get("pageSize")),
                null, null, Map.of());
        return ApiResponse.ok(voucherService.page(request, body));
    }

    /** 凭证详情（含分录）。body: {id} */
    @PostMapping("/detail")
    public ApiResponse<Map<String, Object>> detail(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(voucherService.detail(TmsUtil.str(body.get("id"))));
    }

    /** 凭证打印数据（头 + 分录 + 合计 + 金额大写）。body: {id} */
    @PostMapping("/print-data")
    public ApiResponse<Map<String, Object>> printData(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(voucherService.printData(TmsUtil.str(body.get("id"))));
    }

    /** 保存/更新草稿（含全部校验）。body: 凭证头 + entries[]。 */
    @PostMapping("/save")
    public ApiResponse<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(voucherService.saveDraft(body));
    }

    @PostMapping("/audit")
    public ApiResponse<Void> audit(@RequestBody Map<String, Object> body) {
        voucherService.audit(TmsUtil.str(body.get("id")));
        return ApiResponse.ok(null);
    }

    @PostMapping("/unaudit")
    public ApiResponse<Void> unaudit(@RequestBody Map<String, Object> body) {
        voucherService.unaudit(TmsUtil.str(body.get("id")));
        return ApiResponse.ok(null);
    }

    @PostMapping("/post")
    public ApiResponse<Void> post(@RequestBody Map<String, Object> body) {
        voucherService.post(TmsUtil.str(body.get("id")));
        return ApiResponse.ok(null);
    }

    /** 作废。body: {id, reason} */
    @PostMapping("/void")
    public ApiResponse<Void> voidVoucher(@RequestBody Map<String, Object> body) {
        voucherService.voidVoucher(TmsUtil.str(body.get("id")), TmsUtil.str(body.get("reason")));
        return ApiResponse.ok(null);
    }

    /** 红冲（已过账凭证）。body: {id} → 返回红字凭证号。 */
    @PostMapping("/red-reverse")
    public ApiResponse<Map<String, Object>> redReverse(@RequestBody Map<String, Object> body) {
        String no = voucherService.redReverse(TmsUtil.str(body.get("id")));
        return ApiResponse.ok(Map.of("voucherNo", no));
    }

    /**
     * 单据联查凭证：按来源单据类型+单号查凭证（业务单据列表"凭证"列用）。
     */
    @PostMapping("/by-bill")
    public ApiResponse<List<Map<String, Object>>> byBill(@RequestBody Map<String, Object> body) {
        String billType = TmsUtil.str(body.get("billType"));
        String billNo = TmsUtil.str(body.get("billNo"));
        if (billNo.isEmpty()) throw new IllegalArgumentException("单据号不能为空");
        String sql = "SELECT id, voucher_no, voucher_word, voucher_date, status, is_red, source, source_bill_type, " +
                "source_bill_no, summary, export_flag FROM fin_voucher WHERE source_bill_no = ? ";
        List<Object> args = new java.util.ArrayList<>();
        args.add(billNo);
        if (!billType.isEmpty()) { sql += "AND source_bill_type = ? "; args.add(billType); }
        sql += "ORDER BY make_time DESC";
        return ApiResponse.ok(TmsUtil.queryCamel(jdbc, sql, args.toArray()));
    }

    /**
     * 凭证录入辅助下拉：客户/供应商/部门/员工/商品/项目/现金流量项目，一次取齐。
     * 基础档案表存在才查（防御缺表环境），查不到返回空数组。
     */
    @PostMapping("/aux-options")
    public ApiResponse<Map<String, Object>> auxOptions() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customers", safeQuery(
                "SELECT customer_code code, customer_name name FROM base_customer ORDER BY customer_code"));
        result.put("suppliers", safeQuery(
                "SELECT supplier_code code, supplier_name name FROM base_supplier ORDER BY supplier_code"));
        result.put("departments", safeQuery(
                "SELECT department_code code, department_name name FROM base_department ORDER BY department_code"));
        result.put("employees", safeQuery(
                "SELECT employee_code code, employee_name name FROM base_employee ORDER BY employee_code"));
        result.put("goods", safeQuery(
                "SELECT goods_code code, goods_name name FROM base_goods ORDER BY goods_code"));
        result.put("projects", TmsUtil.queryCamel(jdbc,
                "SELECT project_code code, project_name name FROM fin_aux_project " +
                "WHERE status <> '停用' ORDER BY project_code"));
        result.put("cashFlowItems", TmsUtil.queryCamel(jdbc,
                "SELECT item_code code, item_name name, category, direction FROM fin_cash_flow_item ORDER BY item_code"));
        return ApiResponse.ok(result);
    }

    private List<Map<String, Object>> safeQuery(String sql) {
        try {
            return TmsUtil.queryCamel(jdbc, sql);
        } catch (Exception e) {
            return List.of();
        }
    }
}
