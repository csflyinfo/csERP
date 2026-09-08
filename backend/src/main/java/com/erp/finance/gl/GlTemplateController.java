package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import com.erp.tms.TmsUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 凭证模板配置与试渲染。
 */
@RestController
@RequestMapping("/finance/gl/template")
public class GlTemplateController {

    private final GlTemplateService templateService;

    public GlTemplateController(GlTemplateService templateService) {
        this.templateService = templateService;
    }

    @PostMapping("/list")
    public ApiResponse<?> list() {
        return ApiResponse.ok(templateService.list());
    }

    @PostMapping("/save")
    public ApiResponse<?> save(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(templateService.save(body));
    }

    @PostMapping("/toggle")
    public ApiResponse<?> toggle(@RequestBody Map<String, Object> body) {
        templateService.toggle(TmsUtil.str(body.get("id")), Boolean.TRUE.equals(body.get("enabled")));
        return ApiResponse.ok(true);
    }

    @PostMapping("/delete")
    public ApiResponse<?> delete(@RequestBody Map<String, Object> body) {
        templateService.delete(TmsUtil.str(body.get("id")));
        return ApiResponse.ok(true);
    }

    /** 试渲染：{eventCode, payload(Map 或 JSON 字符串), reverse} → 不落库返回分录预览 */
    @PostMapping("/preview")
    public ApiResponse<?> preview(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(templateService.preview(body));
    }
}
