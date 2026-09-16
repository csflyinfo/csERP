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
 * 客户账户（PRD-35）。路由前缀 /finance/customer-account。
 *
 * <p>M1：账户列表、往来流水查询（只读）、数据修复。
 * M2/M3 将追加预收收退款与预收核销端点。
 */
@RestController
@RequestMapping("/finance/customer-account")
public class CustomerAccountController {

    private final CustomerAccountService service;

    public CustomerAccountController(CustomerAccountService service) {
        this.service = service;
    }

    /** 客户账户分页（应收余额/预收余额）。 */
    @PostMapping("/page")
    @RequirePerm(value = "fin.customer_account.view", name = "查看")
    public ApiResponse<Map<String, Object>> page(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.pageAccounts(req == null ? Map.of() : req));
    }

    /** 往来流水分页（accountType=AR/ADVANCE，带此前余额）。 */
    @PostMapping("/flow/page")
    @RequirePerm(value = "fin.customer_account.view", name = "查看")
    public ApiResponse<Map<String, Object>> flowPage(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.pageFlow(req == null ? Map.of() : req));
    }

    /**
     * 查客户当前预收余额（PRD-35 M2，收款单抽屉选「预收退款」时提示用）。
     * 权限挂收款单查看，避免要求制单员另配客户账户查看权。
     */
    @PostMapping("/advance-balance")
    @RequirePerm(value = "fin.receipt.view", name = "查看")
    public ApiResponse<Map<String, Object>> advanceBalance(@RequestBody Map<String, Object> req) {
        String customerCode = req == null ? "" : TmsUtil.str(req.get("customerCode"));
        String customerName = req == null ? "" : TmsUtil.str(req.get("customerName"));
        // PRD-35 M3：应收结算勾选行只带客户名称，允许传 customerName 由后端解析编码
        String resolvedCode = customerCode;
        if (resolvedCode.isEmpty() && !customerName.isEmpty()) {
            resolvedCode = service.resolveCodeByName(customerName);
        }
        return ApiResponse.ok(Map.of("customerCode", resolvedCode == null ? "" : resolvedCode,
                "advanceBalance", service.getAdvanceBalanceByName(customerCode, customerName)));
    }

    /** 数据修复：重算余额、补缺流水、重排余额链。body: {customerCode?} */
    @PostMapping("/repair")
    @RequirePerm(value = "fin.customer_account.repair", name = "数据修复")
    public ApiResponse<Map<String, Object>> repair(@RequestBody(required = false) Map<String, Object> req) {
        String customerCode = req == null ? "" : TmsUtil.str(req.get("customerCode"));
        return ApiResponse.ok(service.repair(customerCode));
    }
}
