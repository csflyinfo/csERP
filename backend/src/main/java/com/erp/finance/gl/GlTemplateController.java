package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import com.erp.tms.TmsUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import com.erp.common.security.RequirePerm;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 凭证模板配置与试渲染。
 */
@RestController
@RequestMapping("/finance/gl/template")
public class GlTemplateController {

    private final GlTemplateService templateService;
    private final com.erp.common.security.PermissionService permissionService;

    public GlTemplateController(GlTemplateService templateService,
                                com.erp.common.security.PermissionService permissionService) {
        this.templateService = templateService;
        this.permissionService = permissionService;
    }

    @RequirePerm(value = "finance.gl.voucher_template.view", name = "查看")
    @PostMapping("/list")
    public ApiResponse<?> list() {
        return ApiResponse.ok(templateService.list());
    }

    @RequirePerm(value = "finance.gl.voucher_template.add", name = "新增")
    @PostMapping("/save")
    public ApiResponse<?> save(@RequestBody Map<String, Object> body) {
        // PRD-28：/save 新增/修改合一，带 id 时额外要求修改功能点
        if (!TmsUtil.str(body.get("id")).isEmpty() && !permissionService.hasFunc("finance.gl.voucher_template.edit"))
            throw new com.erp.common.security.PermissionDeniedException("无凭证模板修改权限");
        return ApiResponse.ok(templateService.save(body));
    }

    @RequirePerm(value = "finance.gl.voucher_template.edit", name = "启用/停用")
    @PostMapping("/toggle")
    public ApiResponse<?> toggle(@RequestBody Map<String, Object> body) {
        templateService.toggle(TmsUtil.str(body.get("id")), Boolean.TRUE.equals(body.get("enabled")));
        return ApiResponse.ok(true);
    }

    @RequirePerm(value = "finance.gl.voucher_template.delete", name = "删除")
    @PostMapping("/delete")
    public ApiResponse<?> delete(@RequestBody Map<String, Object> body) {
        templateService.delete(TmsUtil.str(body.get("id")));
        return ApiResponse.ok(true);
    }

    /** 试渲染：{eventCode, payload(Map 或 JSON 字符串), reverse} → 不落库返回分录预览 */
    @RequirePerm(value = "finance.gl.voucher_template.view", name = "试渲染")
    @PostMapping("/preview")
    public ApiResponse<?> preview(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(templateService.preview(body));
    }
}
