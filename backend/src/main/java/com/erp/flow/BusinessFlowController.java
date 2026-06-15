package com.erp.flow;

import com.erp.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/flow")
public class BusinessFlowController {
    private final BusinessFlowService flowService;

    public BusinessFlowController(BusinessFlowService flowService) {
        this.flowService = flowService;
    }

    @PostMapping("/purchase-cycle/run")
    public ApiResponse<Map<String, Object>> runPurchaseCycle() {
        return ApiResponse.ok(flowService.runPurchaseCycle());
    }

    @PostMapping("/sales-cycle/run")
    public ApiResponse<Map<String, Object>> runSalesCycle() {
        return ApiResponse.ok(flowService.runSalesCycle());
    }

    @PostMapping("/ar/receive-and-verify")
    public ApiResponse<Map<String, Object>> receiveAndVerifyAr() {
        return ApiResponse.ok(flowService.receiveAndVerifyAr());
    }

    @PostMapping("/ap/pay-and-verify")
    public ApiResponse<Map<String, Object>> payAndVerifyAp() {
        return ApiResponse.ok(flowService.payAndVerifyAp());
    }

    @PostMapping("/customer-price/audit")
    public ApiResponse<Map<String, Object>> auditCustomerPriceAdjust() {
        return ApiResponse.ok(flowService.auditCustomerPriceAdjust());
    }

    @PostMapping("/v1-core/self-test")
    public ApiResponse<Map<String, Object>> runV1CoreSelfTest() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("purchaseCycle", flowService.runPurchaseCycle());
        result.put("salesCycle", flowService.runSalesCycle());
        result.put("arReceipt", flowService.receiveAndVerifyAr());
        result.put("apPayment", flowService.payAndVerifyAp());
        result.put("customerPrice", flowService.auditCustomerPriceAdjust());
        result.put("dashboard", flowService.dashboard());
        result.put("passed", true);
        return ApiResponse.ok(result);
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        return ApiResponse.ok(flowService.dashboard());
    }
}
