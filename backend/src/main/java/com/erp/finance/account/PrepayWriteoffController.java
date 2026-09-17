package com.erp.finance.account;

import com.erp.common.api.ApiResponse;
import com.erp.common.security.RequirePerm;
import com.erp.tms.TmsUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 预付核销单（PRD-36 M2）。路由前缀 /finance/prepay-writeoff。
 *
 * <p>预付核销单把供应商预付余额冲抵应付：审核写核销真值（fin_reconcile_record,
 * business_type=PREPAY_WRITE_OFF），不产生资金流水，只做往来转账。
 * 结构镜像 PRD-35 预收核销单。
 */
@RestController
@RequestMapping("/finance/prepay-writeoff")
public class PrepayWriteoffController {

    private final PrepayWriteoffService service;

    public PrepayWriteoffController(PrepayWriteoffService service) {
        this.service = service;
    }

    /** 分页查询。 */
    @PostMapping("/page")
    @RequirePerm(value = "fin.prepay_writeoff.view", name = "查看")
    public ApiResponse<Map<String, Object>> page(@RequestBody(required = false) Map<String, Object> req) {
        return ApiResponse.ok(service.page(req == null ? Map.of() : req));
    }

    /** 详情（主单 + 明细）。 */
    @PostMapping("/detail")
    @RequirePerm(value = "fin.prepay_writeoff.view", name = "查看")
    public ApiResponse<Map<String, Object>> detail(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.detail(req == null ? Map.of() : req));
    }

    /** 新建（PENDING）。 */
    @PostMapping("/create")
    @RequirePerm(value = "fin.prepay_writeoff.add", name = "新建")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.create(req, TmsUtil.currentUser()));
    }

    /** 修改（仅待审核）。 */
    @PostMapping("/update")
    @RequirePerm(value = "fin.prepay_writeoff.edit", name = "编辑")
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.update(req, TmsUtil.currentUser()));
    }

    /** 删除（仅待审核）。 */
    @PostMapping("/delete")
    @RequirePerm(value = "fin.prepay_writeoff.delete", name = "删除")
    public ApiResponse<Map<String, Object>> delete(@RequestBody Map<String, Object> req) {
        service.delete(TmsUtil.str(req.get("writeoffId")), TmsUtil.currentUser());
        return ApiResponse.ok(Map.of("success", true));
    }

    /** 审核：核销生效，写真值/流水。 */
    @PostMapping("/audit")
    @RequirePerm(value = "fin.prepay_writeoff.audit", name = "审核")
    public ApiResponse<Map<String, Object>> audit(@RequestBody Map<String, Object> req) {
        service.audit(TmsUtil.str(req.get("writeoffId")), TmsUtil.currentUser());
        return ApiResponse.ok(Map.of("writeoffId", TmsUtil.str(req.get("writeoffId")), "status", "APPROVED"));
    }

    /** 反审核：单据回待审核，核销全部冲回。 */
    @PostMapping("/cancel-audit")
    @RequirePerm(value = "fin.prepay_writeoff.unaudit", name = "反审核")
    public ApiResponse<Map<String, Object>> cancelAudit(@RequestBody Map<String, Object> req) {
        service.cancelAudit(TmsUtil.str(req.get("writeoffId")), TmsUtil.currentUser());
        return ApiResponse.ok(Map.of("writeoffId", TmsUtil.str(req.get("writeoffId")), "status", "PENDING"));
    }
}
