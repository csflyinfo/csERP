package com.erp.tms.service;

import com.erp.common.util.JwtUtil;
import com.erp.system.SysParamService;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 司机端鉴权：手机号 + 验证码登录（开发期验证码固定 888888），签发 JWT。
 *
 * 复用现有 JwtUtil（secret/expiration 走 jwt.* 配置）。
 * token claim：subject=driverId（employee_id），displayName=司机姓名，roleCode=DRIVER。
 * Controller 通过 SecurityContext.getName() 拿到 driverId。
 */
@Service
public class TmsAuthService {

    private static final String DEV_VERIFY_CODE = "888888";

    private final JdbcTemplate jdbcTemplate;
    private final JwtUtil jwtUtil;
    private final SysParamService sysParamService;

    public TmsAuthService(JdbcTemplate jdbcTemplate, JwtUtil jwtUtil, SysParamService sysParamService) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
        this.sysParamService = sysParamService;
    }

    /**
     * 司机登录。
     * @param mobile    手机号
     * @param verifyCode 验证码（开发期固定 888888）
     * @return 含 token 与司机基础信息
     */
    public Map<String, Object> login(String mobile, String verifyCode) {
        if (mobile == null || mobile.isBlank()) {
            throw new IllegalArgumentException("请输入手机号");
        }
        if (!DEV_VERIFY_CODE.equals(verifyCode)) {
            throw new IllegalArgumentException("验证码错误");
        }
        // 查 base_employee，is_deliveryman=TRUE
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT employee_id, employee_code, employee_name, mobile, department, position
                FROM base_employee
                WHERE is_deliveryman = TRUE AND status = 'NORMAL'
                  AND (mobile = ? OR employee_code = ?)
                """, mobile, mobile);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未找到在职司机：" + mobile);
        }
        Map<String, Object> row = TmsUtil.camelize(rows.get(0));
        String driverId = TmsUtil.str(row.get("employeeId"));
        String driverName = TmsUtil.str(row.get("employeeName"));

        String token = jwtUtil.generateToken(driverId, driverId, driverName, "DRIVER");

        // 用 LinkedHashMap 而非 Map.of：参数快照是嵌套结构且后续可能扩展可空字段，
        // Map.of 不可变且不接受 null，加字段会直接 NPE
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("driverId", driverId);
        result.put("driverCode", TmsUtil.str(row.get("employeeCode")));
        result.put("driverName", driverName);
        result.put("mobile", TmsUtil.str(row.get("mobile")));
        result.put("roleCode", "DRIVER");
        // 登录即下发参数快照（PRD-26 §5.5）：APP 不直连参数表，避免每个页面单独请求
        result.put("params", appParamSnapshot());
        return result;
    }

    /**
     * APP 端参数快照（PRD-26 §5.5）。
     *
     * <p>为什么集中在这里构造：签收页、退货页、结算页、门店详情页都要读参数，
     * 若各接口各自拼装，key 名和默认值会漂移。这里是唯一事实来源，
     * 登录响应与 {@code POST /tms/app/params} 共用。
     *
     * <p>默认值与 V75 迁移脚本、PRD §3.2 严格一致。查库失败时 SysParamService
     * 回落这里传入的 fallback，保证降级后行为与 PRD-25 存量一致。
     */
    public Map<String, Object> appParamSnapshot() {
        Map<String, Object> p = new LinkedHashMap<>();
        // 照片张数：0 表示不校验，钳制 [0,5]
        p.put("signPhotoCount", sysParamService.getInt("TMS_SIGN_PHOTO_COUNT", 2, 0, 5));
        p.put("returnPhotoCount", sysParamService.getInt("TMS_RETURN_PHOTO_COUNT", 2, 0, 5));
        // 结算拍照：默认 N（PRD-26 唯一的行为变更项，把 PRD-25 的硬编码必填放开）
        p.put("settlePhotoRequired", sysParamService.getBool("TMS_SETTLE_PHOTO_REQUIRED", false));
        // 以下默认 Y，查库失败时回落 true，保证降级不影响存量业务
        p.put("onsiteReturnEnabled", sysParamService.getBool("TMS_ONSITE_RETURN_ENABLED", true));
        p.put("returnMergeSettle", sysParamService.getBool("TMS_RETURN_MERGE_SETTLE", true));
        p.put("appendAfterDepart", sysParamService.getBool("TMS_APPEND_AFTER_DEPART", true));
        p.put("driverFlowEnabled", sysParamService.getBool("TMS_DRIVER_FLOW_ENABLED", true));
        // 电子签名：默认 N，即不展示签名区也不校验。
        // 这两项在 PRD-25 里是 APP 端硬编码必签（交账页、退货签收页），
        // 现改为由参数驱动，默认值按需求原文取【否】——存量行为会随之放开，
        // 想保持必签的租户需在参数设置页显式改成【是】。
        p.put("signEsignRequired", sysParamService.getBool("TMS_SIGN_ESIGN_REQUIRED", false));
        p.put("handoverEsignRequired", sysParamService.getBool("TMS_HANDOVER_ESIGN_REQUIRED", false));
        // 发车留痕（V77，PRD-27）：默认 Y，确认发车必须填发车公里数并拍 1 张里程照片
        p.put("departMileageRequired", sysParamService.getBool("TMS_DEPART_MILEAGE_REQUIRED", true));
        return p;
    }

    /** 根据 driverId 查司机信息（含所属线路）。 */
    public Map<String, Object> getDriverInfo(String driverId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT e.employee_id, e.employee_code, e.employee_name, e.mobile,
                       e.department, e.position,
                       r.route_line_code, r.route_line_name, r.vehicle_plate, r.vehicle_type, r.load_capacity
                FROM base_employee e
                LEFT JOIN base_route_line r ON r.driver = e.employee_name AND r.status = 'NORMAL'
                WHERE e.employee_id = ?
                """, driverId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("司机不存在：" + driverId);
        }
        return TmsUtil.camelize(rows.get(0));
    }
}
