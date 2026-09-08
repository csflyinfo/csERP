package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 业务类型科目映射（其他出/入库类型 → 对方科目；类型不剥离字典）。
 */
@RestController
@RequestMapping("/finance/gl/biz-subject-map")
public class GlBizSubjectMapController {

    private final GlBizSubjectMapService service;

    public GlBizSubjectMapController(GlBizSubjectMapService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public ApiResponse<?> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping("/save")
    public ApiResponse<?> save(@RequestBody Map<String, Object> body) {
        service.save(body);
        return ApiResponse.ok(true);
    }
}
