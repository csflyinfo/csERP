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
 * 供应商账户（PRD-36）。路由前缀 /finance/supplier-account。
 *
 * <p>M1：账户列表、往来流水查询（只读）、数据修复。
 * M2/M3 将追加预付余额、预付核销、厂家费用等端点。
 */
@RestController
@RequestMapping("/finance/supplier-account")
public class SupplierAccountController {

    private final SupplierAccountService service;

    public SupplierAccountController(SupplierAccountService service) {
        this.service = service;
    }

    /** 供应商账户分页（应付余额/预付余额/费用余额）。 */
    @PostMapping("/page")
    @RequirePerm(value = "fin.supplier_account.view", name = "查看")
    public ApiResponse<Map<String, Object>> page(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.pageAccounts(req == null ? Map.of() : req));
    }

    /** 往来流水分页（accountType=AP/PREPAY/EXPENSE，带此前余额）。 */
    @PostMapping("/flow/page")
    @RequirePerm(value = "fin.supplier_account.view", name = "查看")
    public ApiResponse<Map<String, Object>> flowPage(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.pageFlow(req == null ? Map.of() : req));
    }

    /**
     * 查供应商当前预付余额（PRD-36 M2，付款单抽屉选「预付退款」、对账单结算勾选「使用预付」时提示用）。
     * 权限挂付款单查看，避免要求制单员另配供应商账户查看权。
     */
    @PostMapping("/prepay-balance")
    @RequirePerm(value = "fin.payment.view", name = "查看")
    public ApiResponse<Map<String, Object>> prepayBalance(@RequestBody Map<String, Object> req) {
        String supplierCode = req == null ? "" : TmsUtil.str(req.get("supplierCode"));
        String supplierName = req == null ? "" : TmsUtil.str(req.get("supplierName"));
        // 应付/对账单结算勾选行只带供应商名称，允许传 supplierName 由后端解析编码
        String resolvedCode = supplierCode;
        if (resolvedCode.isEmpty() && !supplierName.isEmpty()) {
            resolvedCode = service.resolveCodeByName(supplierName);
        }
        return ApiResponse.ok(Map.of("supplierCode", resolvedCode == null ? "" : resolvedCode,
                "prepayBalance", service.getPrepayBalanceByName(supplierCode, supplierName)));
    }

    /** 数据修复：重算余额、补缺流水、重排余额链。body: {supplierCode?} */
    @PostMapping("/repair")
    @RequirePerm(value = "fin.supplier_account.repair", name = "数据修复")
    public ApiResponse<Map<String, Object>> repair(@RequestBody(required = false) Map<String, Object> req) {
        String supplierCode = req == null ? "" : TmsUtil.str(req.get("supplierCode"));
        return ApiResponse.ok(service.repair(supplierCode));
    }
}
