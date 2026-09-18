package com.erp.base;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 客户档案导入支撑：自动编码（K+6 位流水）、片区/线路/业务员/价格组主数据解析。
 * 所有解析失败抛 IllegalArgumentException（中文消息即导入失败行原因）。
 */
@Component
public class CustomerImportSupport {

    private final JdbcTemplate jdbcTemplate;

    public CustomerImportSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 生成「K + 6 位流水号」（K000001…）；synchronized 单机防并发，唯一约束兜底。 */
    public synchronized String nextCustomerCode() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT MAX(customer_code) AS max_code FROM base_customer WHERE customer_code LIKE 'K______'");
        String maxCode = rows.isEmpty() || rows.get(0).get("max_code") == null
                ? null : String.valueOf(rows.get(0).get("max_code"));
        int seq = 1;
        if (maxCode != null && maxCode.length() >= 7) {
            String tail = maxCode.substring(1);
            if (tail.matches("\\d{6}")) seq = Integer.parseInt(tail) + 1;
        }
        if (seq > 999999) {
            throw new IllegalArgumentException("客户编码流水号已达上限（K999999）");
        }
        return String.format("K%06d", seq);
    }

    /**
     * 解析片区：按名称匹配正常片区，必须是末级（无正常下级）。
     * 0 个 / 多个同名 / 非末级均失败。返回片区名称（客户表冗余存名称）。
     */
    public String resolveTerritory(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT territory_code, territory_name FROM base_territory "
                        + "WHERE territory_name = ? AND COALESCE(status,'NORMAL') = 'NORMAL'", n);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("片区「" + n + "」不存在，须先在片区资料中维护");
        }
        if (rows.size() > 1) {
            throw new IllegalArgumentException("存在多个同名片区「" + n + "」，请先整理片区档案");
        }
        String code = String.valueOf(rows.get(0).get("territory_code"));
        Integer children = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM base_territory WHERE parent_code = ? AND COALESCE(status,'NORMAL') = 'NORMAL'",
                Integer.class, code);
        if (children != null && children > 0) {
            throw new IllegalArgumentException("片区「" + n + "」不是末级片区，请填写其下具体片区");
        }
        return n;
    }

    /** 线路按名称匹配正常线路。 */
    public boolean routeLineExists(String name) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM base_route_line WHERE route_line_name = ? AND COALESCE(status,'NORMAL') = 'NORMAL'",
                Integer.class, name.trim());
        return n != null && n > 0;
    }

    /** 业务员按姓名匹配：须在职正常且具备业务员身份。 */
    public boolean salesmanExists(String name) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM base_employee WHERE employee_name = ? "
                        + "AND COALESCE(status,'NORMAL') = 'NORMAL' AND COALESCE(is_salesman,FALSE) = TRUE",
                Integer.class, name.trim());
        return n != null && n > 0;
    }

    /**
     * 解析价格组：优先按编码精确匹配；查不到再按名称唯一匹配。
     * 目标价格组必须启用且状态正常。空值 = 不使用价格组（取商品默认售价）。返回价格组编码。
     */
    public String resolvePriceGroup(String codeOrName) {
        String v = codeOrName == null ? "" : codeOrName.trim();
        if (v.isEmpty()) return "";
        List<Map<String, Object>> byCode = jdbcTemplate.queryForList(
                "SELECT price_group_code FROM base_price_group WHERE price_group_code = ?", v);
        if (!byCode.isEmpty()) {
            assertPriceGroupUsable(v, true);
            return v;
        }
        List<Map<String, Object>> byName = jdbcTemplate.queryForList(
                "SELECT price_group_code, enabled, status FROM base_price_group WHERE price_group_name = ?", v);
        if (byName.isEmpty()) {
            throw new IllegalArgumentException("价格组「" + v + "」不存在，须先在价格组中维护，或填写价格组编码");
        }
        if (byName.size() > 1) {
            throw new IllegalArgumentException("存在多个同名价格组「" + v + "」，请改填价格组编码");
        }
        Map<String, Object> row = byName.get(0);
        Object en = row.get("enabled");
        Object st = row.get("status");
        boolean enabled = en instanceof Boolean b ? b : (en instanceof Number nn && nn.intValue() > 0);
        if (!"NORMAL".equalsIgnoreCase(String.valueOf(st))) {
            throw new IllegalArgumentException("价格组「" + v + "」已停用，不允许引用");
        }
        if (!enabled) {
            throw new IllegalArgumentException("价格组「" + v + "」未启用，不允许引用");
        }
        return String.valueOf(row.get("price_group_code"));
    }

    private void assertPriceGroupUsable(String code, boolean exists) {
        if (!exists) return;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT enabled, status FROM base_price_group WHERE price_group_code = ?", code);
        Map<String, Object> row = rows.get(0);
        Object en = row.get("enabled");
        Object st = row.get("status");
        boolean enabled = en instanceof Boolean b ? b : (en instanceof Number n && n.intValue() > 0);
        if (!"NORMAL".equalsIgnoreCase(String.valueOf(st))) {
            throw new IllegalArgumentException("价格组「" + code + "」已停用，不允许引用");
        }
        if (!enabled) {
            throw new IllegalArgumentException("价格组「" + code + "」未启用，不允许引用");
        }
    }
}
