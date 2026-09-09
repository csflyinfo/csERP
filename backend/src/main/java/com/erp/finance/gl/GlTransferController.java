package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import com.erp.tms.TmsUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import com.erp.common.security.RequirePerm;
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
    private final com.erp.common.security.PermissionService permissionService;

    public GlTransferController(GlTransferService transferService,
                                com.erp.common.security.PermissionService permissionService) {
        this.transferService = transferService;
        this.permissionService = permissionService;
    }

    /** 模板列表（含分录）。 */
    @RequirePerm(value = "finance.gl.transfer_template.view", name = "查看")
    @PostMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(transferService.list());
    }

    /** 新建/更新模板（系统模板可改不可删；金额/条件公式保存时试算校验）。 */
    @RequirePerm(value = "finance.gl.transfer_template.add", name = "新增")
    @PostMapping("/save")
    public ApiResponse<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        // PRD-28：/save 新增/修改合一，带 id 时额外要求修改功能点
        if (!TmsUtil.str(body.get("id")).isEmpty() && !permissionService.hasFunc("finance.gl.transfer_template.edit"))
            throw new com.erp.common.security.PermissionDeniedException("无自动转账模板修改权限");
        return ApiResponse.ok(transferService.save(body));
    }

    /** 启用/停用。body: {id, enabled} */
    @RequirePerm(value = "finance.gl.transfer_template.edit", name = "启用/停用")
    @PostMapping("/toggle")
    public ApiResponse<Map<String, Object>> toggle(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(transferService.toggle(body));
    }

    /** 删除（系统预置模板拒绝）。body: {id} */
    @RequirePerm(value = "finance.gl.transfer_template.delete", name = "删除")
    @PostMapping("/delete")
    public ApiResponse<Void> delete(@RequestBody Map<String, Object> body) {
        transferService.delete(body);
        return ApiResponse.ok(null);
    }
}
