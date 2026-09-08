package com.erp.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求级上下文（ThreadLocal）。
 *
 * <p>由 {@code RequestContextFilter} 在请求进入 Controller 前填充、请求结束后清理，
 * 供 {@code OperationLogService} 读取操作人账号、客户端 IP、请求 URI、耗时等，
 * 避免每个写日志的地方都从 HttpServletRequest / SecurityContext 重复取数。
 *
 * <p>设计为永不持有跨请求状态：filter 在 finally 中 {@link #clear()}，防止线程复用串号。
 */
public final class RequestContext {

    /** 单次请求的上下文快照。 */
    public record Ctx(
            String account,      // 登录账号（JWT subject）
            String userId,       // 用户 ID（JWT claim，可能为空）
            String displayName,  // 用户姓名（JWT claim，可能为空）
            String ip,           // 客户端 IP
            String uri,          // 请求 URI（去 contextPath）
            String method,       // HTTP 方法
            String userAgent,    // User-Agent
            long startMillis     // 请求开始时间戳（用于算耗时）
    ) {}

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    private RequestContext() {}

    public static Ctx current() {
        return HOLDER.get();
    }

    public static void set(Ctx ctx) {
        HOLDER.set(ctx);
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 取客户端真实 IP：优先 X-Forwarded-For 第一个非 unknown 跳（nginx 反代场景），
     * 其次 X-Real-IP，最后回落 remoteAddr。
     */
    public static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            for (String part : xff.split(",")) {
                String hop = part.trim();
                if (!hop.isEmpty() && !"unknown".equalsIgnoreCase(hop)) {
                    return hop;
                }
            }
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank() && !"unknown".equalsIgnoreCase(real.trim())) {
            return real.trim();
        }
        return req.getRemoteAddr();
    }
}
