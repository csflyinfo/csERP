package com.erp.wms;

import com.erp.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 仓库 PDA 登录入口（PRD-28 卡片9）。
 *
 * <p>工号（或用户名）+ 密码，两步选仓：首次请求 warehouseId 留空——绑定单仓自动签发，
 * 绑定多仓返回 needWarehouse+仓库列表，PDA 弹选仓后带 warehouseId 二次请求。
 * 签发的 JWT 带 appType=WMS_PDA 与 warehouseId，
 * 是访问 /wms/app/** 的唯一合法令牌类型（{@code PdaAppGuardInterceptor} 端隔离）。
 * 认证门禁与 ERP 登录共用 {@code AuthSupport}：停用/锁定/BCrypt/失败计数/登录日志。
 */
@RestController
@RequestMapping("/wms/app")
public class WmsPdaAuthController {

    private final WmsPdaAuthService pdaAuthService;

    public WmsPdaAuthController(WmsPdaAuthService pdaAuthService) {
        this.pdaAuthService = pdaAuthService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody PdaLoginRequest request,
                                                  HttpServletRequest httpReq) {
        return ApiResponse.ok(pdaAuthService.login(
                request.username(), request.password(), request.warehouseId(), httpReq));
    }

    /**
     * PDA 登录请求：username 可填用户名或工号（employee_code）。
     * warehouseId 首次请求留空触发两步选仓（单仓自动/多仓返回列表），二次请求必带。
     */
    public record PdaLoginRequest(
            @NotBlank(message = "请输入工号") String username,
            @NotBlank(message = "请输入密码") String password,
            String warehouseId
    ) {}
}
