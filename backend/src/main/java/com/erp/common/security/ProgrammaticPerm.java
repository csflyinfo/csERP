package com.erp.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 编程式鉴权标记（PRD-28 卡片7）。
 *
 * <p>一个 HTTP 端点按请求体内容分派到多张菜单（如通用主档 /base/master/save 按 moduleCode
 * 分派到 base.region/base.department/... 各自的 add/edit 功能点），无法用单一
 * {@link RequirePerm} 表达权限要求时，在方法上标注本注解，并在方法体内第一时间用
 * {@link PermissionService#hasFunc(String)} 逐请求裁决，失败抛 {@link PermissionDeniedException}。
 *
 * <p>本注解<b>不被拦截器执行</b>，仅向权限盘点扫描器（/perm/health）声明「此写端点已有
 * 编程式鉴权，不计入未鉴权清单」。因此：
 * <ul>
 *   <li>只允许用于「端点内确实存在等价的 hasFunc 逐分支校验」的场合，禁止当成豁免开关；</li>
 *   <li>{@link #value()} 必须写清分派维度与功能点映射，便于审计。</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProgrammaticPerm {

    /** 鉴权口径说明：分派字段 → 功能点映射（审计用）。 */
    String value();
}
