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
 * 总账——核算项目（项目档案）。路由前缀 /finance/gl/aux-project。
 */
@RestController
@RequestMapping("/finance/gl/aux-project")
public class GlAuxProjectController {

    private final GlAuxProjectService projectService;

    public GlAuxProjectController(GlAuxProjectService projectService) {
        this.projectService = projectService;
    }

    /** 列表。body: {keyword, status} */
    @RequirePerm(value = "finance.gl.aux_project.view", name = "查看")
    @PostMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> q = req == null ? Map.of() : req;
        return ApiResponse.ok(projectService.list(TmsUtil.str(q.get("keyword")), TmsUtil.str(q.get("status"))));
    }

    @RequirePerm(value = "finance.gl.aux_project.add", name = "新增")
    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(projectService.create(req));
    }

    @RequirePerm(value = "finance.gl.aux_project.edit", name = "修改")
    @PostMapping("/update")
    public ApiResponse<Void> update(@RequestBody Map<String, Object> req) {
        projectService.update(req);
        return ApiResponse.ok(null);
    }

    /** 项目下拉。body: {keyword} */
    @RequirePerm(value = "finance.gl.aux_project.view", name = "查看")
    @PostMapping("/options")
    public ApiResponse<List<Map<String, Object>>> options(@RequestBody(required = false) Map<String, Object> req) {
        String kw = req == null ? "" : TmsUtil.str(req.get("keyword"));
        return ApiResponse.ok(projectService.options(kw));
    }
}
