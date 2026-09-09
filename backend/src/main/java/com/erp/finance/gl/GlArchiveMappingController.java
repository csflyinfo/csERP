package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import com.erp.common.security.RequirePerm;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 总账档案科目映射：资金账户 / 费用类型 / 商品分类 → 总账科目。
 */
@RestController
@RequestMapping("/finance/gl/archive-mapping")
public class GlArchiveMappingController {

    private final GlArchiveMappingService service;

    public GlArchiveMappingController(GlArchiveMappingService service) {
        this.service = service;
    }

    @RequirePerm(value = "finance.gl.archive_mapping.view", name = "查看")
    @PostMapping("/list")
    public ApiResponse<Map<String, Object>> list() {
        return ApiResponse.ok(service.list());
    }

    @RequirePerm(value = "finance.gl.archive_mapping.edit", name = "保存")
    @PostMapping("/save")
    public ApiResponse<Boolean> save(@RequestBody Map<String, Object> body) {
        service.save(body);
        return ApiResponse.ok(true);
    }
}
