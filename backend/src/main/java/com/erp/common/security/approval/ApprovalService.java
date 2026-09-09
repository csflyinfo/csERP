package com.erp.common.security.approval;

import com.erp.common.security.CurrentUser;
import com.erp.common.security.PermissionService;
import com.erp.system.OperationLogService;
import com.erp.system.PasswordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感操作二次授权闸门（PRD-28 §6.2.1 三个审批型全局功能，卡片6）。
 *
 * <p>低价销售 / 超信用 / 负库存出库三类场景，业务校验命中红线时调用
 * {@link #requireApproval}：
 * <ol>
 *   <li>当前登录人本人拥有对应 {@code global.*_approval} 功能 → 直接放行（SELF）；</li>
 *   <li>否则请求体必须携带授权人账号密码；为空抛 {@link NeedApprovalException}，
 *       前端据 code=NEED_APPROVAL 弹授权框后合并字段重放原请求；</li>
 *   <li>校验授权人：账号存在/未停用/未锁定/密码正确/本人确实拥有该授权功能/不能给本人授权；
 *       全过写操作日志（授权人、操作员、单号、原因）后放行（APPROVER）。</li>
 * </ol>
 *
 * <p>授权失败同样写日志（安全审计，防暴力试密码），但日志失败永不影响业务。
 */
@Service
public class ApprovalService {

    public static final String SOURCE_SELF = "SELF";
    public static final String SOURCE_APPROVER = "APPROVER";

    private final JdbcTemplate jdbc;
    private final PasswordService passwordService;
    private final PermissionService permissionService;
    private final OperationLogService opLog;
    private final ObjectMapper objectMapper;

    public ApprovalService(JdbcTemplate jdbc,
                           PasswordService passwordService,
                           PermissionService permissionService,
                           OperationLogService opLog,
                           ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.passwordService = passwordService;
        this.permissionService = permissionService;
        this.opLog = opLog;
        this.objectMapper = objectMapper;
    }

    /**
     * 授权闸门。
     *
     * @param type              授权类型
     * @param moduleCode        业务模块（操作日志 module，如 sales.order）
     * @param bizNo             业务单号（可为空，如保存阶段尚未生成）
     * @param reason            触发原因（人话，进弹窗与日志，如"单价 8.00 低于最低售价 9.50"）
     * @param approverAccount   请求体携带的授权人账号（可空）
     * @param approverPassword  请求体携带的授权人密码（可空）
     * @return {@link #SOURCE_SELF} 或 {@link #SOURCE_APPROVER}
     */
    public String requireApproval(ApprovalType type, String moduleCode, String bizNo,
                                  String reason, String approverAccount, String approverPassword) {
        CurrentUser.Principal me = CurrentUser.get();
        if (me != null && me.userId() != null && permissionService.hasFunc(type.funcCode())) {
            return SOURCE_SELF;
        }
        if (isBlank(approverAccount) || isBlank(approverPassword)) {
            throw new NeedApprovalException(type, bizNo,
                    type.displayName() + "：" + reason + "。请输入拥有「" + type.displayName() + "」权限的授权账号和密码");
        }

        String account = approverAccount.trim();
        String action = type.name() + "_APPROVAL";
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT user_id, username, display_name, password, status, lock_time "
                            + "FROM sys_user_runtime WHERE username = ?",
                    account);
        } catch (Exception e) {
            throw new IllegalArgumentException("授权校验失败，请稍后重试");
        }
        if (rows.isEmpty()) {
            return deny(moduleCode, action, bizNo, account, "授权账号不存在");
        }
        Map<String, Object> u = rows.get(0);
        String userId = str(u.get("USER_ID"));
        String status = str(u.get("STATUS"));
        if (!"NORMAL".equals(status)) {
            return deny(moduleCode, action, bizNo, account, "授权账号已停用");
        }
        Timestamp lockTime = (Timestamp) u.get("LOCK_TIME");
        if (lockTime != null && lockTime.getTime() > System.currentTimeMillis()) {
            return deny(moduleCode, action, bizNo, account, "授权账号处于登录锁定期");
        }
        if (!passwordService.matches(approverPassword, str(u.get("PASSWORD")))) {
            return deny(moduleCode, action, bizNo, account, "授权密码不正确");
        }
        if (!permissionService.userIdHasFunc(userId, type.funcCode())) {
            return deny(moduleCode, action, bizNo, account,
                    "授权账号「" + account + "」无「" + type.displayName() + "」权限");
        }
        if (me != null && userId.equals(me.userId())) {
            return deny(moduleCode, action, bizNo, account, "不能使用本人账号进行授权");
        }

        // 通过：写授权留痕（detail 结构化 + content 人话）
        String approverName = str(u.get("DISPLAY_NAME"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("approvalType", type.name());
        detail.put("bizNo", bizNo);
        detail.put("reason", reason);
        detail.put("operatorAccount", me == null ? null : me.username());
        detail.put("operatorName", me == null ? null : me.displayName());
        detail.put("approverAccount", account);
        detail.put("approverName", approverName);
        String content = "授权通过：操作员「" + (me == null ? "未知" : me.username())
                + "」经「" + account + "/" + approverName + "」授权完成" + type.displayName()
                + (bizNo == null || bizNo.isBlank() ? "" : "（单据 " + bizNo + "）")
                + "，原因：" + reason;
        try {
            opLog.logContent(moduleCode, action, bizNo == null ? account : bizNo,
                    objectMapper.writeValueAsString(detail), content);
        } catch (Exception ignored) {
            // 日志失败不阻断业务
        }
        return SOURCE_APPROVER;
    }

    /** 授权失败：写失败日志后抛用户可见错误。 */
    private String deny(String moduleCode, String action, String bizNo, String approverAccount, String reason) {
        try {
            opLog.logFail(moduleCode, action, approverAccount,
                    "授权失败" + (bizNo == null || bizNo.isBlank() ? "" : "（单据 " + bizNo + "）") + "：" + reason);
        } catch (Exception ignored) {
            // 日志失败不阻断
        }
        throw new IllegalArgumentException(reason + "，授权未通过");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
