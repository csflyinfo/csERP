package com.erp.tms.service;

import com.erp.auth.AuthSupport;
import com.erp.auth.sms.SmsCodeService;
import com.erp.common.security.CurrentUser;
import com.erp.common.util.JwtUtil;
import com.erp.system.PasswordService;
import com.erp.system.perm.MenuMetaService;
import com.erp.tms.TmsUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 司机端登录鉴权（PRD-28 卡片10，方案 §5.4 / §8.3）。
 *
 * <p>两种登录方式：
 * <ul>
 *   <li><b>手机号 + 短信验证码</b>：主路径。人员档案中 is_deliveryman=TRUE 的在职司机
 *       首次登录自动开通 sys_user_runtime（工号=username，随机 16 位密码，must_change_pwd=TRUE）
 *       并绑定内置角色 R_TMS_DRIVER；已开通则直接签发；离职/取消配送员标记一律拒绝；</li>
 *   <li><b>工号 + 密码</b>：备选路径（装车员等非配送员账号由管理员预先建户授权）。</li>
 * </ul>
 *
 * <p>签发的 JWT 固定 appType=DRIVER、带 employeeId（=司机 driverId），是访问
 * /tms/app/** 的唯一合法令牌类型（DriverAppGuardInterceptor 端隔离）。登录响应一次性
 * 下发 menus（DRIVER 端菜单树）/funcs/fields（按钮裁剪）/params（TMS 参数快照）。
 */
@Service
public class TmsDriverAuthService {

    public static final String APP_TYPE = "DRIVER";
    public static final String DRIVER_ROLE_ID = "R_TMS_DRIVER";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PWD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%";

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final AuthSupport authSupport;
    private final SmsCodeService smsCodeService;
    private final PasswordService passwordService;
    private final MenuMetaService menuMetaService;
    private final TmsAuthService legacyAuthService;

    public TmsDriverAuthService(JdbcTemplate jdbc, JwtUtil jwtUtil, AuthSupport authSupport,
                                SmsCodeService smsCodeService, PasswordService passwordService,
                                MenuMetaService menuMetaService, TmsAuthService legacyAuthService) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.authSupport = authSupport;
        this.smsCodeService = smsCodeService;
        this.passwordService = passwordService;
        this.menuMetaService = menuMetaService;
        this.legacyAuthService = legacyAuthService;
    }

    /**
     * 司机登录。
     *
     * @param mobile     手机号（短信方式，也兼容填工号）
     * @param smsCode    短信验证码（非空走短信方式）
     * @param employeeCode 工号（密码方式）
     * @param password   密码（密码方式）
     */
    public Map<String, Object> login(String mobile, String smsCode, String employeeCode, String password,
                                     HttpServletRequest httpReq) {
        boolean bySms = smsCode != null && !smsCode.isBlank();
        boolean byPassword = password != null && !password.isBlank();
        if (!bySms && !byPassword) {
            throw new IllegalArgumentException("请输入验证码或密码");
        }

        Map<String, Object> user;
        String employeeId;
        String account;
        if (bySms) {
            String phone = mobile == null ? "" : mobile.trim();
            if (phone.isEmpty()) throw new IllegalArgumentException("请输入手机号");
            // 先校验验证码再查人：不提供"账号是否存在"的探测侧信道
            smsCodeService.verifyLoginCode(phone, smsCode);
            Map<String, Object> employee = findActiveDeliveryman(phone);
            employeeId = str(employee, "employeeId");
            account = employeeId;
            user = findOrCreateUser(employee, phone, httpReq);
        } else {
            String code = employeeCode == null ? "" : employeeCode.trim();
            if (code.isEmpty()) throw new IllegalArgumentException("请输入工号");
            user = authSupport.findLoginUser(code);
            if (user == null) {
                authSupport.writeLoginLog(null, code, "FAIL", "账号不存在", httpReq, APP_TYPE);
                throw new IllegalArgumentException("账号或密码错误");
            }
            authSupport.verifyLoginOrThrow(user, code, password, httpReq, APP_TYPE);
            employeeId = blankToNull(str(user, "employeeId"));
            account = code;
        }

        String userId = str(user, "userId");
        List<Map<String, Object>> allRoles = authSupport.loadRoles(userId);
        List<Map<String, Object>> driverRoles = new ArrayList<>();
        boolean superAdmin = false;
        for (Map<String, Object> r : allRoles) {
            String code = str(r, "roleCode");
            String appType = str(r, "appType");
            if ("SYS_ADMIN".equals(code)) superAdmin = true;
            if ("SYS_ADMIN".equals(code) || APP_TYPE.equals(appType) || "ALL".equals(appType)) {
                driverRoles.add(r);
            }
        }
        if (!superAdmin && driverRoles.isEmpty()) {
            authSupport.writeLoginLog(userId, account, "FAIL", "账号无司机端角色", httpReq, APP_TYPE);
            throw new IllegalArgumentException("账号未分配司机端角色，请联系管理员");
        }
        return issueLogin(user, employeeId, driverRoles, httpReq);
    }

    /** 查在职配送员：按手机号或工号；离职/取消配送员标记直接拒绝（不区分提示以防枚举）。 */
    private Map<String, Object> findActiveDeliveryman(String mobileOrCode) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT employee_id employeeId, employee_code employeeCode, employee_name employeeName,
                       mobile, department, position, status, is_deliveryman isDeliveryman
                FROM base_employee
                WHERE (mobile = ? OR employee_code = ?)
                LIMIT 1
                """, mobileOrCode, mobileOrCode);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未找到在职司机，请联系管理员");
        }
        Map<String, Object> emp = rows.get(0);
        if (!"NORMAL".equals(str(emp, "status"))) {
            throw new IllegalArgumentException("该司机已离职，禁止登录");
        }
        if (!Boolean.TRUE.equals(emp.get("isDeliveryman"))) {
            throw new IllegalArgumentException("该人员未标记为配送员，禁止司机端登录");
        }
        return emp;
    }

    /** 已开通直接返回；首次登录按方案 §5.4 自动建户并绑 R_TMS_DRIVER。 */
    @Transactional
    public Map<String, Object> findOrCreateUser(Map<String, Object> employee, String loginMobile,
                                                HttpServletRequest httpReq) {
        String employeeId = str(employee, "employeeId");
        String employeeCode = str(employee, "employeeCode");
        List<Map<String, Object>> exist = jdbc.queryForList("""
                SELECT user_id userId, username, display_name displayName, password, status,
                       mobile, email, employee_id employeeId, primary_role_id primaryRoleId,
                       fail_count failCount, lock_time lockTime, must_change_pwd mustChangePwd,
                       pwd_update_time pwdUpdateTime
                FROM sys_user_runtime
                WHERE username = ? OR (mobile IS NOT NULL AND mobile = ?)
                LIMIT 1
                """, employeeCode, loginMobile);
        if (!exist.isEmpty()) {
            Map<String, Object> user = exist.get(0);
            if (!"NORMAL".equals(str(user, "status"))) {
                authSupport.writeLoginLog(str(user, "userId"), employeeId, "FAIL", "账号已停用", httpReq, APP_TYPE);
                throw new IllegalArgumentException("账号已停用，请联系管理员");
            }
            return user;
        }

        String userId = "U" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String rawPassword = randomPassword();
        jdbc.update("""
                INSERT INTO sys_user_runtime(user_id, username, display_name, password, mobile, status,
                    role_name, employee_id, primary_role_id, must_change_pwd, is_system, created_by,
                    pwd_update_time, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'NORMAL', '司机', ?, ?, TRUE, FALSE, '系统自动开通',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId, employeeCode, str(employee, "employeeName"),
                passwordService.encode(rawPassword), str(employee, "mobile"),
                employeeId, DRIVER_ROLE_ID);
        jdbc.update("""
                INSERT INTO sys_user_role_rel(id, user_id, role_id, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, "UR" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                userId, DRIVER_ROLE_ID);

        // 重新按登录口径取回（含 fail_count/lock_time 等门禁字段）
        Map<String, Object> created = authSupport.findLoginUser(employeeCode);
        if (created == null) {
            throw new IllegalStateException("司机账号自动开通后查询失败：" + employeeCode);
        }
        return created;
    }

    private Map<String, Object> issueLogin(Map<String, Object> user, String employeeId,
                                           List<Map<String, Object>> driverRoles,
                                           HttpServletRequest httpReq) {
        String userId = str(user, "userId");
        String username = str(user, "username");
        String displayName = str(user, "displayName");
        authSupport.afterLoginSuccess(user, httpReq);

        List<String> roleCodes = driverRoles.stream().map(r -> str(r, "roleCode")).toList();
        String primaryRoleCode = authSupport.resolvePrimaryRoleCode(user, driverRoles);
        String token = jwtUtil.generateToken(userId, username, displayName, roleCodes,
                APP_TYPE, null, employeeId);
        authSupport.writeLoginLog(userId, username, "SUCCESS", null, httpReq, APP_TYPE);

        Set<String> roleSet = new LinkedHashSet<>(roleCodes);
        CurrentUser.Principal principal = CurrentUser.of(userId, username, displayName, employeeId,
                roleSet, primaryRoleCode, APP_TYPE, null);
        List<Map<String, Object>> menus;
        Map<String, Object> mine;
        CurrentUser.set(principal);
        try {
            menus = menuMetaService.userTree(APP_TYPE);
            mine = menuMetaService.mine();
        } finally {
            CurrentUser.clear();
        }

        // 司机档案（工号/手机号/线路）——driverId 仍为 employee_id，保持 TMS 表关联口径不变
        Map<String, Object> driverInfo = employeeId == null ? Map.of()
                : legacyAuthService.getDriverInfo(employeeId);

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("userId", userId);
        userInfo.put("username", username);
        userInfo.put("displayName", displayName);
        userInfo.put("employeeId", employeeId);
        userInfo.put("roles", driverRoles);
        userInfo.put("roleCodes", roleCodes);
        userInfo.put("primaryRoleCode", primaryRoleCode);
        userInfo.put("mustChangePwd", authSupport.effectiveMustChange(user));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("user", userInfo);
        result.put("menus", menus);
        result.put("funcs", mine.get("funcs"));
        result.put("fields", mine.get("fields"));
        result.put("superAdmin", Boolean.TRUE.equals(mine.get("superAdmin")));
        result.put("params", legacyAuthService.appParamSnapshot());
        // 兼容旧版 APP 字段（driverId/driverCode/driverName/mobile/roleCode）
        result.put("driverId", employeeId);
        result.put("driverCode", TmsUtil.str(driverInfo.get("employeeCode")));
        result.put("driverName", displayName);
        result.put("mobile", TmsUtil.str(driverInfo.get("mobile")));
        result.put("roleCode", primaryRoleCode);
        return result;
    }

    /** 16 位随机密码：4 类字符各取至少 1 位，打乱顺序，满足密码强度策略。 */
    private String randomPassword() {
        String mandatory = "A" + "a" + "2" + "!";
        StringBuilder sb = new StringBuilder(mandatory);
        for (int i = 0; i < 12; i++) {
            sb.append(PWD_ALPHABET.charAt(RANDOM.nextInt(PWD_ALPHABET.length())));
        }
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char t = chars[i];
            chars[i] = chars[j];
            chars[j] = t;
        }
        return new String(chars);
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return (t.isEmpty() || "null".equalsIgnoreCase(t)) ? null : t;
    }
}
