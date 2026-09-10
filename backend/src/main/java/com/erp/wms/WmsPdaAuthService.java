package com.erp.wms;

import com.erp.auth.AuthSupport;
import com.erp.common.security.CurrentUser;
import com.erp.common.util.JwtUtil;
import com.erp.system.SysParamService;
import com.erp.system.perm.MenuMetaService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仓库 PDA 登录鉴权（PRD-28 卡片9，方案 §7.2 / §10.2）。
 *
 * <p>与 ERP 登录共用 {@link AuthSupport} 的停用/锁定/BCrypt/失败计数门禁，区别：
 * <ul>
 *   <li>登录名支持工号（username 或 base_employee.employee_code）；</li>
 *   <li>必须至少拥有一个 app_type=WMS_PDA/ALL 的启用角色，否则拒绝；</li>
 *   <li>必须选择本人绑定仓库（sys_user_warehouse，SYS_ADMIN 可选任意在用仓库）；</li>
 *   <li>签发 appType=WMS_PDA、带 warehouseId 的 JWT；/wms/app/** 由端类型拦截器只认这种令牌；</li>
 *   <li>登录响应一次性下发 menus（WMS_PDA 菜单树）、funcs/fields（按钮裁剪）、参数快照。</li>
 * </ul>
 */
@Service
public class WmsPdaAuthService {

    public static final String APP_TYPE = "WMS_PDA";

    /** 登录响应下发的 PDA 交互参数（键缺失时 PDA 用本地默认值）。 */
    private static final String[] PARAM_KEYS = {
            "WMS_PDA_CLICK_PICK", "WMS_PDA_VIEW", "WMS_CHECK_ENABLED", "WMS_CROSS_ZONE_MODE",
            "WMS_PICK_BATCH_CHANGE_ALLOWED", "WMS_RECEIVE_WITHOUT_ORDER", "WMS_PO_MULTI_RECEIVE",
            "WMS_BLINDED_RECEIVE", "WMS_OVER_RECEIVE_TOLERANCE", "WMS_RECHECK_ENABLED",
            "WMS_RECHECK_MODE", "WMS_RECHECK_SELF_NG", "WMS_RECHECK_AMOUNT_THRESHOLD",
            "WMS_RECHECK_RATIO", "WMS_PUTAWAY_FREE_BIN", "WMS_DAMAGE_NEED_PHOTO",
            "WMS_CHECK_SHORT_ALLOWED", "WMS_COUNT_MODE_DEFAULT", "WMS_COUNT_FREEZE_BIN",
            "WMS_EXCEPTION_AUTO_SUSPEND"
    };

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final AuthSupport authSupport;
    private final MenuMetaService menuMetaService;
    private final SysParamService params;

    public WmsPdaAuthService(JdbcTemplate jdbc, JwtUtil jwtUtil, AuthSupport authSupport,
                             MenuMetaService menuMetaService, SysParamService params) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.authSupport = authSupport;
        this.menuMetaService = menuMetaService;
        this.params = params;
    }

    public Map<String, Object> login(String loginName, String rawPassword, String warehouseId,
                                     jakarta.servlet.http.HttpServletRequest httpReq) {
        String account = loginName == null ? "" : loginName.trim();
        if (account.isEmpty()) throw new IllegalArgumentException("请输入工号");
        if (rawPassword == null || rawPassword.isEmpty()) throw new IllegalArgumentException("请输入密码");

        Map<String, Object> user = authSupport.findLoginUser(account);
        if (user == null) {
            authSupport.writeLoginLog(null, account, "FAIL", "账号不存在", httpReq, APP_TYPE);
            throw new IllegalArgumentException("账号或密码错误");
        }
        authSupport.verifyLoginOrThrow(user, account, rawPassword, httpReq, APP_TYPE);

        String userId = str(user, "userId");
        // 只取 PDA 端可用角色（WMS_PDA 或 ALL），避免把 ERP 角色塞进 PDA 令牌
        List<Map<String, Object>> allRoles = authSupport.loadRoles(userId);
        List<Map<String, Object>> pdaRoles = new ArrayList<>();
        boolean superAdmin = false;
        for (Map<String, Object> r : allRoles) {
            String code = str(r, "roleCode");
            String appType = str(r, "appType");
            if ("SYS_ADMIN".equals(code)) superAdmin = true;
            if ("SYS_ADMIN".equals(code) || APP_TYPE.equals(appType) || "ALL".equals(appType)) {
                pdaRoles.add(r);
            }
        }
        if (!superAdmin && pdaRoles.isEmpty()) {
            authSupport.writeLoginLog(userId, account, "FAIL", "账号无PDA端角色", httpReq, APP_TYPE);
            throw new IllegalArgumentException("账号未分配 PDA 角色，请联系管理员");
        }

        // 选仓：必须在本人绑定仓库内；SYS_ADMIN 可选任意在用仓库
        List<Map<String, Object>> warehouses = superAdmin ? listAllWarehouses() : authSupport.warehouses(userId);
        if (warehouses.isEmpty()) {
            authSupport.writeLoginLog(userId, account, "FAIL", "未绑定仓库", httpReq, APP_TYPE);
            throw new IllegalArgumentException("该账号未绑定作业仓库，请联系管理员");
        }
        // 两步登录：未带 warehouseId 时，单仓自动选中直接签发；多仓返回仓库列表让 PDA 弹选仓，不签发令牌
        Map<String, Object> wh;
        if (warehouseId == null || warehouseId.isBlank()) {
            if (warehouses.size() > 1) {
                Map<String, Object> pick = new LinkedHashMap<>();
                pick.put("needWarehouse", true);
                pick.put("warehouses", warehouses);
                return pick;
            }
            wh = warehouses.get(0);
        } else {
            Map<String, Object> selected = null;
            for (Map<String, Object> w : warehouses) {
                if (warehouseId.equals(str(w, "warehouseId"))) { selected = w; break; }
            }
            if (selected == null) {
                authSupport.writeLoginLog(userId, account, "FAIL", "仓库未绑定", httpReq, APP_TYPE);
                throw new IllegalArgumentException("该账号未绑定所选仓库，请联系管理员");
            }
            wh = selected;
        }
        return issueLogin(user, userId, account, pdaRoles, warehouses, wh, httpReq);
    }

    /** 选中仓库后的正式签发：清失败计数 → 签 JWT（带 appType/warehouseId）→ 组装菜单/功能点/参数快照。 */
    private Map<String, Object> issueLogin(Map<String, Object> user, String userId, String account,
                                           List<Map<String, Object>> pdaRoles,
                                           List<Map<String, Object>> warehouses,
                                           Map<String, Object> wh,
                                           jakarta.servlet.http.HttpServletRequest httpReq) {
        String warehouseId = str(wh, "warehouseId");
        authSupport.afterLoginSuccess(user, httpReq);

        List<String> roleCodes = pdaRoles.stream().map(r -> str(r, "roleCode")).toList();
        String primaryRoleCode = authSupport.resolvePrimaryRoleCode(user, pdaRoles);
        String token = jwtUtil.generateToken(
                userId, str(user, "username"), str(user, "displayName"), roleCodes,
                APP_TYPE, warehouseId, blankToNull(str(user, "employeeId")));
        authSupport.writeLoginLog(userId, account, "SUCCESS", null, httpReq, APP_TYPE);

        // menus / funcs / fields 的查询走 CurrentUser，登录端点本身无令牌，临时构造 PDA 主体
        Set<String> roleSet = new LinkedHashSet<>(roleCodes);
        CurrentUser.Principal principal = CurrentUser.of(userId, str(user, "username"),
                str(user, "displayName"), blankToNull(str(user, "employeeId")),
                roleSet, primaryRoleCode, APP_TYPE, warehouseId);
        List<Map<String, Object>> menus;
        Map<String, Object> mine;
        CurrentUser.set(principal);
        try {
            menus = menuMetaService.userTree(APP_TYPE);
            mine = menuMetaService.mine();
        } finally {
            CurrentUser.clear();
        }

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("userId", userId);
        userInfo.put("username", str(user, "username"));
        userInfo.put("displayName", str(user, "displayName"));
        userInfo.put("employeeId", blankToNull(str(user, "employeeId")));
        userInfo.put("roles", pdaRoles);
        userInfo.put("roleCodes", roleCodes);
        userInfo.put("primaryRoleCode", primaryRoleCode);
        userInfo.put("roleCode", primaryRoleCode); // 兼容旧字段
        userInfo.put("warehouseId", warehouseId);
        userInfo.put("warehouseCode", str(wh, "warehouseCode"));
        userInfo.put("warehouseName", str(wh, "warehouseName"));
        userInfo.put("mustChangePwd", authSupport.effectiveMustChange(user));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("user", userInfo);
        result.put("warehouses", warehouses);
        result.put("menus", menus);
        result.put("funcs", mine.get("funcs"));
        result.put("fields", mine.get("fields"));
        result.put("superAdmin", Boolean.TRUE.equals(mine.get("superAdmin")));
        result.put("params", paramSnapshot());
        return result;
    }

    /** SYS_ADMIN 不做绑仓限制，列出全部在用仓库供选仓。 */
    private List<Map<String, Object>> listAllWarehouses() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT warehouse_id, warehouse_code, warehouse_name FROM base_warehouse
                WHERE status = 'NORMAL' ORDER BY warehouse_code
                """);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("warehouseId", r.get("warehouse_id"));
            w.put("warehouseCode", r.get("warehouse_code"));
            w.put("warehouseName", r.get("warehouse_name"));
            out.add(w);
        }
        return out;
    }

    /** PDA 交互参数快照：先取运行时表现值，缺失键回落到 SysParamService 默认值。 */
    private Map<String, Object> paramSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String key : PARAM_KEYS) {
            snapshot.put(key, params.get(key, ""));
        }
        return snapshot;
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
