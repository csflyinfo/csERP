package com.erp.finance.account;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
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
 * 厂家费用兑现单 DX（PRD-36 M3）。路由前缀 /finance/factory-settle。
 * 权限码 fin.factory_settle.* 由 MenuConfig 注册页面后自动派生。
 */
@RestController
@RequestMapping("/finance/factory-settle")
public class FactorySettleController {

    private final FactorySettleService service;

    public FactorySettleController(FactorySettleService service) {
        this.service = service;
    }

    @PostMapping("/page")
    @RequirePerm(value = "fin.factory_settle.view", name = "查看")
    public ApiResponse<Map<String, Object>> page(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.page(req));
    }

    @PostMapping("/detail")
    @RequirePerm(value = "fin.factory_settle.view", name = "查看")
    public ApiResponse<Map<String, Object>> detail(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.detail(req));
    }

    /** JF 候选（该供应商未全兑现的已审费用单，FIFO 日期升序）。 */
    @PostMapping("/jf-candidates")
    @RequirePerm(value = "fin.factory_settle.view", name = "查看")
    public ApiResponse<List<Map<String, Object>>> jfCandidates(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.jfCandidates(req));
    }

    /** AP 候选（该供应商未结清、非负应付行，到期日升序）。 */
    @PostMapping("/ap-candidates")
    @RequirePerm(value = "fin.factory_settle.view", name = "查看")
    public ApiResponse<List<Map<String, Object>>> apCandidates(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.apCandidates(req));
    }

    @PostMapping("/create")
    @RequirePerm(value = "fin.factory_settle.add", name = "新增")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.create(req, TmsUtil.currentUser()));
    }

    @PostMapping("/update")
    @RequirePerm(value = "fin.factory_settle.edit", name = "编辑")
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.update(req, TmsUtil.currentUser()));
    }

    @PostMapping("/delete")
    @RequirePerm(value = "fin.factory_settle.delete", name = "删除")
    public ApiResponse<Void> delete(@RequestBody Map<String, Object> req) {
        service.delete(TmsUtil.str(req.get("settleId")), TmsUtil.currentUser());
        return ApiResponse.ok(null);
    }

    @PostMapping("/audit")
    @RequirePerm(value = "fin.factory_settle.audit", name = "审核")
    public ApiResponse<Map<String, Object>> audit(@RequestBody Map<String, Object> req) {
        String id = TmsUtil.str(req.get("settleId"));
        service.audit(id, TmsUtil.currentUser());
        return ApiResponse.ok(Map.of("settleId", id, "status", "APPROVED"));
    }

    @PostMapping("/cancel-audit")
    @RequirePerm(value = "fin.factory_settle.unaudit", name = "反审核")
    public ApiResponse<Map<String, Object>> cancelAudit(@RequestBody Map<String, Object> req) {
        String id = TmsUtil.str(req.get("settleId"));
        service.cancelAudit(id, TmsUtil.currentUser());
        return ApiResponse.ok(Map.of("settleId", id, "status", "PENDING"));
    }

    /** 扣款通知单打印数据（前端渲染打印视图）。 */
    @PostMapping("/print-data")
    @RequirePerm(value = "fin.factory_settle.print", name = "打印")
    public ApiResponse<Map<String, Object>> printData(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.detail(req));
    }

    /** 导出扣款通知单 xlsx（三页签：概要 / 厂家费用明细 / 冲销明细）。 */
    @PostMapping("/export-notice")
    @RequirePerm(value = "fin.factory_settle.print", name = "打印导出扣款通知单")
    public void exportNotice(@RequestBody Map<String, Object> req, HttpServletResponse response) throws Exception {
        Map<String, Object> d = service.detail(req);
        String no = TmsUtil.str(d.get("settleNo"));
        String type = TmsUtil.str(d.get("settleType"));

        String encoded = URLEncoder.encode("扣款通知单_" + no + ".xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + encoded);

        List<List<String>> h0 = head("项目", "内容");
        List<List<Object>> summary = new ArrayList<>();
        summary.add(row("扣款通知单号", no));
        summary.add(row("供应商", TmsUtil.str(d.get("supplierName")) + "（" + TmsUtil.str(d.get("supplierCode")) + "）"));
        summary.add(row("兑现日期", String.valueOf(d.get("settleDate"))));
        summary.add(row("兑现方式", TmsUtil.str(d.get("settleTypeText"))));
        summary.add(row("金额合计", d.get("totalAmount")));
        if ("OTHER".equals(type)) {
            summary.add(row("对方科目", TmsUtil.str(d.get("contraSubjectCode")) + " "
                    + TmsUtil.str(d.get("contraSubjectName"))));
            summary.add(row("关联单据", TmsUtil.str(d.get("relatedBillNo"))));
        }
        summary.add(row("经手人", TmsUtil.str(d.get("handler"))));
        summary.add(row("备注", TmsUtil.str(d.get("remark"))));

        List<List<String>> h1 = head("厂家费用单号", "费用日期", "费用性质", "费用金额", "本次兑现");
        List<List<Object>> feeRows = new ArrayList<>();
        for (Object o : asList(d.get("jfDetails"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> x = (Map<String, Object>) o;
            feeRows.add(row(x.get("factoryExpenseNo"), String.valueOf(x.get("expenseDate")),
                    FactoryExpenseService.claimTypeText(TmsUtil.str(x.get("claimType"))),
                    x.get("expenseAmount"), x.get("settleAmount")));
        }

        List<List<Object>> detailRows = new ArrayList<>();
        List<List<String>> h2 = "CASH".equals(type)
                ? head("资金账户", "到账金额", "备注")
                : head("应付单号", "来源单据", "到期日", "应付金额", "本次冲销");
        if ("CASH".equals(type)) {
            for (Object o : asList(d.get("fundDetails"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> x = (Map<String, Object>) o;
                detailRows.add(row(x.get("fundAccount"), x.get("amount"), x.get("remark")));
            }
        } else if ("OFFSET".equals(type)) {
            for (Object o : asList(d.get("apDetails"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> x = (Map<String, Object>) o;
                detailRows.add(row(x.get("apNo"), x.get("sourceBill"), String.valueOf(x.get("dueDate")),
                        x.get("apAmount"), x.get("offsetAmount")));
            }
        }

        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            WriteSheet s0 = EasyExcel.writerSheet(0, "概要").head(h0).build();
            writer.write(summary, s0);
            WriteSheet s1 = EasyExcel.writerSheet(1, "厂家费用明细").head(h1).build();
            writer.write(feeRows, s1);
            WriteSheet s2 = EasyExcel.writerSheet(2, "CASH".equals(type) ? "资金到账" : "冲应付明细").head(h2).build();
            writer.write(detailRows, s2);
        }
    }

    private static List<List<String>> head(String... titles) {
        List<List<String>> h = new ArrayList<>();
        for (String t : titles) {
            h.add(Collections.singletonList(t));
        }
        return h;
    }

    private static List<Object> row(Object... values) {
        List<Object> r = new ArrayList<>();
        Collections.addAll(r, values);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object obj) {
        if (obj instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }
}
