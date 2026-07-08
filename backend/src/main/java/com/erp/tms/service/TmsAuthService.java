package com.erp.tms.service;

import com.erp.common.util.JwtUtil;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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

    public TmsAuthService(JdbcTemplate jdbcTemplate, JwtUtil jwtUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
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

        return Map.of(
                "token", token,
                "driverId", driverId,
                "driverCode", TmsUtil.str(row.get("employeeCode")),
                "driverName", driverName,
                "mobile", TmsUtil.str(row.get("mobile")),
                "roleCode", "DRIVER"
        );
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
