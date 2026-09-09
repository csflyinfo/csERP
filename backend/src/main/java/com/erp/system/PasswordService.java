package com.erp.system;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 密码策略服务（PRD-28 §8.4）。
 *
 * <ul>
 *   <li>长度 ≥ 8；大写、小写、数字、特殊字符四类至少满足 3 类；</li>
 *   <li>不能与最近 3 次历史密码相同（BCrypt 逐哈希比对，见 sys_pwd_history，V104）；</li>
 *   <li>BCrypt 强度 10；改密/重置成功后写历史并裁剪只留最近 3 条。</li>
 * </ul>
 * 校验失败统一抛 {@link IllegalArgumentException}（用户可见中文提示）。
 */
@Service
public class PasswordService {

    /** 历史密码校验条数（含当前密码）。 */
    public static final int HISTORY_KEEP = 3;
    private static final int MIN_LENGTH = 8;

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public PasswordService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        return rawPassword != null && encodedPassword != null
                && encodedPassword.startsWith("$2")
                && encoder.matches(rawPassword, encodedPassword);
    }

    /**
     * 强度校验（§8.4：四类字符至少 3 类 + 长度 ≥ 8）。
     */
    public void validateStrength(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("密码长度不能少于 8 位");
        }
        if (password.length() > 50) {
            throw new IllegalArgumentException("密码长度不能超过 50 位");
        }
        int kinds = 0;
        boolean lower = false, upper = false, digit = false, special = false;
        for (char c : password.toCharArray()) {
            if (Character.isLowerCase(c)) lower = true;
            else if (Character.isUpperCase(c)) upper = true;
            else if (Character.isDigit(c)) digit = true;
            else special = true;
        }
        if (lower) kinds++;
        if (upper) kinds++;
        if (digit) kinds++;
        if (special) kinds++;
        if (kinds < 3) {
            throw new IllegalArgumentException("密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 种");
        }
    }

    /**
     * 新密码不得与该用户最近 {@value #HISTORY_KEEP} 次历史密码（含当前密码）相同。
     */
    public void assertNotReused(String userId, String rawNewPassword) {
        List<String> recent = jdbc.queryForList(
                "SELECT password_hash FROM sys_pwd_history WHERE user_id = ? " +
                        "ORDER BY created_at DESC LIMIT " + HISTORY_KEEP,
                String.class, userId);
        for (String oldHash : recent) {
            if (encoder.matches(rawNewPassword, oldHash)) {
                throw new IllegalArgumentException("新密码不能与最近 " + HISTORY_KEEP + " 次使用过的密码相同");
            }
        }
    }

    /**
     * 落一条历史并裁剪到最近 {@value #HISTORY_KEEP} 条。与密码更新在同一事务内调用。
     */
    public void recordHistory(String userId, String encodedPassword) {
        jdbc.update("INSERT INTO sys_pwd_history(history_id, user_id, password_hash, created_at) " +
                        "VALUES ('PH' || SUBSTRING(REPLACE(CAST(RANDOM_UUID() AS VARCHAR),'-',''),1,14), ?, ?, CURRENT_TIMESTAMP)",
                userId, encodedPassword);
        jdbc.update("DELETE FROM sys_pwd_history WHERE user_id = ? AND history_id NOT IN (" +
                        "SELECT history_id FROM (SELECT history_id FROM sys_pwd_history WHERE user_id = ? " +
                        "ORDER BY created_at DESC LIMIT " + HISTORY_KEEP + ") t)",
                userId, userId);
    }
}
