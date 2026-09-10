package com.erp.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级功能权限声明（PRD-28 RBAC）。
 *
 * <p>双重职责：
 * <ol>
 *   <li>运行时由 {@code RequirePermAspect} 校验当前用户是否拥有该功能点（无权限抛 403）；</li>
 *   <li>启动时由 {@code PermissionRegistry} 扫描，自动 upsert 进 sys_func_meta，无需手写 SQL。</li>
 * </ol>
 *
 * <p>编码规范见方案 §6：模块功能 {@code <menu_code>.<action>}（如 sales.order.audit）；
 * 全局功能 {@code global.xxx}（如 global.export）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    /** 功能点编码，全局唯一，如 sales.order.view、global.export。 */
    String value();

    /** 功能点名称（同步到 sys_func_meta 作功能名展示），如"审核"。 */
    String name() default "";

    /** true=全局功能（func_scope=GLOBAL），挂 M_GLOBAL_FUNC；false=模块功能。 */
    boolean global() default false;

    /** 功能类型：BUTTON / ACTION / API，默认 BUTTON。 */
    String type() default "BUTTON";

    /**
     * 同端点「按载荷分派」的额外功能码（PRD-28 卡片9，PDA 多动作共用端点场景）。
     *
     * <p>这些功能码只注册进 sys_func_meta（可在角色配置页授权、按钮可裁剪），
     * <b>不被拦截器自动校验</b>；方法体内必须用 {@link PermissionService#hasFunc(String)}
     * 按请求参数逐分支裁决（无权限抛 {@link PermissionDeniedException}）。
     * 例如 /wms/app/inbound/receive 按入库类型分派 receive.scan / receive_return.scan，
     * 短拣/超收/改放库位等子动作同端点按参数裁决，均在此声明。
     */
    String[] alsoRegister() default {};
}
