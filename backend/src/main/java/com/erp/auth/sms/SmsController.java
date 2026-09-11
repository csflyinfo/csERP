package com.erp.auth.sms;

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
 * 短信验证码入口（PRD-28 卡片10，方案 §8.3）。
 *
 * <p>匿名端点（SecurityConfig/JwtAuthFilter 白名单）：司机登录前请求验证码。
 * 风控与通道全部在 {@link SmsCodeService} 内；本控制器不返回验证码本身，
 * 也不区分"手机号是否为在职司机"，避免接口变成司机名册探测口。
 */
@RestController
@RequestMapping("/auth/sms")
public class SmsController {

    private final SmsCodeService smsCodeService;

    public SmsController(SmsCodeService smsCodeService) {
        this.smsCodeService = smsCodeService;
    }

    @PostMapping("/send")
    public ApiResponse<Map<String, Object>> send(@Valid @RequestBody SendSmsRequest request,
                                                 HttpServletRequest httpReq) {
        // 当前仅登录业务；后续如扩展（改手机号等）在此分派不同 bizType
        if (request.bizType() != null && !request.bizType().isBlank()
                && !SmsCodeService.BIZ_LOGIN.equals(request.bizType())) {
            throw new IllegalArgumentException("不支持的验证码业务类型：" + request.bizType());
        }
        smsCodeService.sendLoginCode(request.mobile(), httpReq);
        return ApiResponse.ok(Map.of("sent", true, "expireSeconds", 300));
    }

    public record SendSmsRequest(
            @NotBlank(message = "请输入手机号") String mobile,
            String bizType
    ) {}
}
