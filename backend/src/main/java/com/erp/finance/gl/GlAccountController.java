package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import com.erp.common.security.RequirePerm;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 总账——会计科目管理。路由前缀 /finance/gl/account。
 */
@RestController
@RequestMapping("/finance/gl/account")
public class GlAccountController {

    private final GlAccountService accountService;
    private final JdbcTemplate jdbc;

    public GlAccountController(GlAccountService accountService, JdbcTemplate jdbc) {
        this.accountService = accountService;
        this.jdbc = jdbc;
    }

    /** 科目树（嵌套 children）。 */
    @RequirePerm(value = "finance.gl.account.view", name = "查看")
    @PostMapping("/tree")
    public ApiResponse<List<Map<String, Object>>> tree() {
        return ApiResponse.ok(accountService.tree());
    }

    /** 平铺列表。 */
    @RequirePerm(value = "finance.gl.account.view", name = "查看")
    @PostMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(accountService.listAll());
    }

    @RequirePerm(value = "finance.gl.account.add", name = "新增")
    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(accountService.create(req));
    }

    @RequirePerm(value = "finance.gl.account.edit", name = "修改")
    @PostMapping("/update")
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> req) {
        accountService.update(req);
        return ApiResponse.ok(accountService.findById(TmsUtil.str(req.get("id"))));
    }

    /** 启用/停用切换。body: {id}。 */
    @RequirePerm(value = "finance.gl.account.edit", name = "停用/启用")
    @PostMapping("/toggle-status")
    public ApiResponse<Map<String, Object>> toggleStatus(@RequestBody Map<String, Object> req) {
        accountService.setStatus(TmsUtil.str(req.get("id")));
        return ApiResponse.ok(accountService.findById(TmsUtil.str(req.get("id"))));
    }

    /**
     * 末级启用科目下拉（凭证录入/档案映射用）。
     * body: {type, cashOnly, keyword}
     */
    @RequirePerm(value = "finance.gl.account.view", name = "查看")
    @PostMapping("/leaf-options")
    public ApiResponse<List<Map<String, Object>>> leafOptions(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(accountService.leafOptions(
                TmsUtil.str(req.get("type")),
                Boolean.TRUE.equals(req.get("cashOnly")),
                TmsUtil.str(req.get("keyword"))));
    }
}
