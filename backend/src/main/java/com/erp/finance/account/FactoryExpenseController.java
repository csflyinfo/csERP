package com.erp.finance.account;

import com.alibaba.excel.EasyExcel;
import com.erp.common.api.ApiResponse;
import com.erp.common.security.RequirePerm;
import com.erp.tms.TmsUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 厂家费用单 JF（PRD-36 M3）。路由前缀 /finance/factory-expense。
 * 权限码 fin.factory_expense.* 由 MenuConfig 注册页面后自动派生。
 */
@RestController
@RequestMapping("/finance/factory-expense")
public class FactoryExpenseController {

    private final FactoryExpenseService service;

    public FactoryExpenseController(FactoryExpenseService service) {
        this.service = service;
    }

    /** P0190 补录入口开关（仅返回显隐标志，不暴露参数管理能力）。 */
    @GetMapping("/backfill-flag")
    @RequirePerm(value = "fin.factory_expense.view", name = "查看")
    public ApiResponse<Map<String, Object>> backfillFlag() {
        return ApiResponse.ok(Map.of("enabled", service.isOpeningBackfillEnabled()));
    }

    @PostMapping("/page")
    @RequirePerm(value = "fin.factory_expense.view", name = "查看")
    public ApiResponse<Map<String, Object>> page(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.page(req));
    }

    @PostMapping("/detail")
    @RequirePerm(value = "fin.factory_expense.view", name = "查看")
    public ApiResponse<Map<String, Object>> detail(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.detail(req));
    }

    /** 行级可选客户费用单（代垫关联选择器）。 */
    @PostMapping("/customer-expense-candidates")
    @RequirePerm(value = "fin.factory_expense.view", name = "查看")
    public ApiResponse<List<Map<String, Object>>> customerExpenseCandidates(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.customerExpenseCandidates(req));
    }

    @PostMapping("/create")
    @RequirePerm(value = "fin.factory_expense.add", name = "新增")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.create(req, TmsUtil.currentUser()));
    }

    @PostMapping("/update")
    @RequirePerm(value = "fin.factory_expense.edit", name = "编辑")
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.update(req, TmsUtil.currentUser()));
    }

    @PostMapping("/delete")
    @RequirePerm(value = "fin.factory_expense.delete", name = "删除")
    public ApiResponse<Void> delete(@RequestBody Map<String, Object> req) {
        service.delete(TmsUtil.str(req.get("factoryExpenseId")), TmsUtil.currentUser());
        return ApiResponse.ok(null);
    }

    @PostMapping("/audit")
    @RequirePerm(value = "fin.factory_expense.audit", name = "审核")
    public ApiResponse<Map<String, Object>> audit(@RequestBody Map<String, Object> req) {
        service.audit(TmsUtil.str(req.get("factoryExpenseId")), TmsUtil.currentUser());
        return ApiResponse.ok(Map.of("factoryExpenseId", TmsUtil.str(req.get("factoryExpenseId")),
                "status", "APPROVED"));
    }

    @PostMapping("/cancel-audit")
    @RequirePerm(value = "fin.factory_expense.unaudit", name = "反审核")
    public ApiResponse<Map<String, Object>> cancelAudit(@RequestBody Map<String, Object> req) {
        service.cancelAudit(TmsUtil.str(req.get("factoryExpenseId")), TmsUtil.currentUser());
        return ApiResponse.ok(Map.of("factoryExpenseId", TmsUtil.str(req.get("factoryExpenseId")),
                "status", "PENDING"));
    }

    /** 由已审核原单开红字 JF（PENDING）。 */
    @PostMapping("/red-create")
    @RequirePerm(value = "fin.factory_expense.add", name = "红字冲减")
    public ApiResponse<Map<String, Object>> redCreate(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.redCreate(req, TmsUtil.currentUser()));
    }

    /** Excel 导入：前端解析 xlsx 后以 {rows,fileName} 提交，按供应商+日期+费用性质生 PENDING JF。 */
    @PostMapping("/import")
    @RequirePerm(value = "fin.factory_expense.import", name = "导入")
    public ApiResponse<Map<String, Object>> importRows(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.importRows(asRows(body.get("rows")),
                TmsUtil.str(body.getOrDefault("fileName", "厂家费用导入.xlsx")),
                TmsUtil.currentUser()));
    }

    /** 导入模板（动态生成 xlsx，无需维护二进制模板文件）。 */
    @GetMapping("/import-template")
    @RequirePerm(value = "fin.factory_expense.import", name = "导入")
    public void downloadTemplate(HttpServletResponse response) throws Exception {
        List<List<String>> head = new ArrayList<>();
        for (String[] f : FactoryExpenseService.IMPORT_FIELDS) {
            head.add(Collections.singletonList(f[0]));
        }
        List<List<Object>> sample = new ArrayList<>();
        sample.add(java.util.List.of("S001", "市场推广费", "代垫", "C001", "",
                "2026-09-20", "100.00", "HT-2026-001", "", "示例行，导入前删除"));
        writeExcel(response, "厂家费用单_导入模板.xlsx", "厂家费用", head, sample);
    }

    /**
     * 导出：按当前查询条件导出 JF 明细（一张 JF 每个明细行一行），最多 5000 行。
     * 列：单号/供应商/日期/费用性质/来源/费用类型/客户/关联FE/金额/贷方科目/状态/兑现状态/备注。
     */
    @PostMapping("/export")
    @RequirePerm(value = "fin.factory_expense.export", name = "导出")
    public void export(@RequestBody Map<String, Object> req, HttpServletResponse response) throws Exception {
        req.put("pageNo", 1);
        req.put("pageSize", 200);
        List<Map<String, Object>> bills = new ArrayList<>();
        for (int pageNo = 1; pageNo <= 25; pageNo++) {
            req.put("pageNo", pageNo);
            Map<String, Object> page = service.page(req);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> records = (List<Map<String, Object>>) page.get("records");
            if (records == null || records.isEmpty()) {
                break;
            }
            bills.addAll(records);
            if (records.size() < 200 || bills.size() >= 5000) {
                break;
            }
        }
        List<List<String>> head = new ArrayList<>();
        for (String title : new String[]{"厂家费用单号", "供应商", "费用日期", "费用性质", "来源方式",
                "费用类型", "垫付客户", "关联客户费用单", "行金额", "贷方科目", "单据状态",
                "兑现状态", "红字", "原单号", "备注"}) {
            head.add(Collections.singletonList(title));
        }
        List<List<Object>> body = new ArrayList<>();
        for (Map<String, Object> bill : bills) {
            String jfId = TmsUtil.str(bill.get("factoryExpenseId"));
            Map<String, Object> d = service.detail(Map.of("factoryExpenseId", jfId));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> details = (List<Map<String, Object>>) d.get("details");
            if (details == null || details.isEmpty()) {
                continue;
            }
            for (Map<String, Object> line : details) {
                List<Object> row = new ArrayList<>();
                row.add(TmsUtil.str(bill.get("factoryExpenseNo")));
                row.add(TmsUtil.str(bill.get("supplierName")));
                row.add(String.valueOf(bill.get("expenseDate")));
                row.add(TmsUtil.str(bill.get("claimTypeText")));
                row.add(TmsUtil.str(bill.get("sourceModeText")));
                row.add(TmsUtil.str(line.get("expenseTypeName")));
                row.add(TmsUtil.str(line.get("customerName")));
                row.add(TmsUtil.str(line.get("customerExpenseNo")));
                row.add(line.get("amount"));
                row.add(TmsUtil.str(line.get("glCreditSubject")));
                row.add(TmsUtil.str(bill.get("statusText")));
                row.add(TmsUtil.str(bill.get("settleStatusText")));
                row.add("Y".equals(TmsUtil.str(bill.get("isRed"))) ? "红字" : "");
                row.add(TmsUtil.str(bill.get("redSourceNo")));
                row.add(TmsUtil.str(line.get("remark")));
                body.add(row);
            }
            if (body.size() >= 5000) {
                break;
            }
        }
        writeExcel(response, "厂家费用单明细.xlsx", "厂家费用明细", head, body);
    }

    static void writeExcel(HttpServletResponse response, String fileName, String sheet,
                           List<List<String>> head, List<List<Object>> body) throws java.io.IOException {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + encoded);
        EasyExcel.write(response.getOutputStream()).sheet(sheet).head(head).doWrite(body);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asRows(Object obj) {
        if (!(obj instanceof List<?> list)) {
            throw new IllegalArgumentException("缺少 rows 数据");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }
}
