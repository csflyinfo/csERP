package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 期末处理（总账 M5）：结账向导 8 步、自动转账、结转损益、结账/反结账。
 */
@RestController
@RequestMapping("/finance/gl/period")
public class GlPeriodController {

    private final GlPeriodService periodService;
    private final GlInitService initService;

    public GlPeriodController(GlPeriodService periodService, GlInitService initService) {
        this.periodService = periodService;
        this.initService = initService;
    }

    /** 会计期间列表（期间选择器用）。 */
    @PostMapping("/list")
    public ApiResponse<?> list() {
        return ApiResponse.ok(initService.periodList());
    }

    /** 结账向导总览（8 步状态 + 反结账信息）。body: {period?} */
    @PostMapping("/wizard")
    public ApiResponse<?> wizard(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(periodService.wizard(body));
    }

    /** 自动转账预览（每张模板适用性/分录金额，不写库）。 */
    @PostMapping("/transfer-preview")
    public ApiResponse<?> transferPreview(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(periodService.transferPreview(str(body.get("period"))));
    }

    /** 执行自动转账（零/不平/条件不满足跳过，ZZ 幂等）。body: {period?, transferNos?} */
    @PostMapping("/transfer-execute")
    public ApiResponse<?> transferExecute(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(periodService.transferExecute(body));
    }

    /** 结转损益预览（5xxx 净发生分组、收入/费用合计、净利润）。 */
    @PostMapping("/profit-preview")
    public ApiResponse<?> profitPreview(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(periodService.profitPreview(str(body.get("period"))));
    }

    /** 执行结转损益（生成"转"字草稿凭证 JZ{period}，幂等）。 */
    @PostMapping("/profit-carry")
    public ApiResponse<?> profitCarry(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(periodService.profitCarry(body));
    }

    /** 结账四项硬检查（只读）。 */
    @PostMapping("/checks")
    public ApiResponse<?> checks(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(periodService.checks(str(body.get("period"))));
    }

    /** 期末结账（年结 12 期冻结并自动建下年期间）。 */
    @PostMapping("/close")
    public ApiResponse<?> close(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(periodService.close(body));
    }

    /** 反结账（仅最后一个已结账期间、原因必填、JZ/ZZ 凭证回退草稿；冻结期间不可反）。 */
    @PostMapping("/reopen")
    public ApiResponse<?> reopen(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(periodService.reopen(body));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
