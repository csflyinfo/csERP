package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 自动转账模板维护（总账 M9）。路由前缀 /finance/gl/transfer。
 * 期末预览/执行仍走 /finance/gl/period/transfer-preview|transfer-execute（GlPeriodService）。
 */
@RestController
@RequestMapping("/finance/gl/transfer")
public class GlTransferController {

    private final GlTransferService transferService;

    public GlTransferController(GlTransferService transferService) {
        this.transferService = transferService;
    }

    /** 模板列表（含分录）。 */
    @PostMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(transferService.list());
    }

    /** 新建/更新模板（系统模板可改不可删；金额/条件公式保存时试算校验）。 */
    @PostMapping("/save")
    public ApiResponse<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(transferService.save(body));
    }

    /** 启用/停用。body: {id, enabled} */
    @PostMapping("/toggle")
    public ApiResponse<Map<String, Object>> toggle(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(transferService.toggle(body));
    }

    /** 删除（系统预置模板拒绝）。body: {id} */
    @PostMapping("/delete")
    public ApiResponse<Void> delete(@RequestBody Map<String, Object> body) {
        transferService.delete(body);
        return ApiResponse.ok(null);
    }
}
