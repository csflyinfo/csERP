package com.erp.finance.gl;

import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会计科目服务：科目树、新增/修改/停用、末级科目下拉。
 * 规则（落地方案 §4）：
 *  - 编码 4-2-2-2，下级编码 = 上级编码 + 两位序号；末级才能录凭证；
 *  - 系统预置科目（is_system=TRUE）不可删、不可改编码/类别/方向，可停用；
 *  - 余额方向由科目类别决定；2221 应交税费下所有明细方向一律"贷"；
 *  - 停用前必须先停用下级；已有凭证分录引用的科目不可停用（M2 起校验）。
 */
@Service
public class GlAccountService {

    private final JdbcTemplate jdbc;

    public GlAccountService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全量科目（平铺，按编码排序）。 */
    public List<Map<String, Object>> listAll() {
        return TmsUtil.queryCamel(jdbc,
                "SELECT id, account_code, account_name, parent_code, account_level, account_type, " +
                "balance_direction, aux_dimensions, is_qty, is_cash, is_leaf, is_system, status, " +
                "sort_order, remark FROM fin_account ORDER BY account_code");
    }

    /** 科目树（嵌套 children）。 */
    public List<Map<String, Object>> tree() {
        List<Map<String, Object>> flat = listAll();
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : flat) {
            row.put("children", new ArrayList<Map<String, Object>>());
            byCode.put(TmsUtil.str(row.get("accountCode")), row);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> row : flat) {
            String parent = TmsUtil.str(row.get("parentCode"));
            if (parent.isEmpty() || !byCode.containsKey(parent)) {
                roots.add(row);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) byCode.get(parent).get("children");
                children.add(row);
            }
        }
        return roots;
    }

    public Map<String, Object> findById(String id) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_account WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findByCode(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM fin_account WHERE account_code = ?", code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新增科目。req: accountCode(可空=自动), accountName, parentCode(可空), auxDimensions, isQty, isCash, remark。 */
    public Map<String, Object> create(Map<String, Object> req) {
        String name = TmsUtil.str(req.get("accountName"));
        if (name.isEmpty()) throw new IllegalArgumentException("科目名称不能为空");
        String parentCode = TmsUtil.str(req.get("parentCode"));
        String code = TmsUtil.str(req.get("accountCode"));

        String type;
        String direction;
        int level;
        if (parentCode.isEmpty()) {
            // 顶级科目：编码必填，4 位
            if (code.isEmpty()) throw new IllegalArgumentException("顶级科目编码不能为空");
            if (code.length() != 4) throw new IllegalArgumentException("顶级科目编码必须为 4 位数字");
            type = GlConst.typeOfCode(code);
            if (type.isEmpty()) throw new IllegalArgumentException("科目编码必须以 1~5 开头（资产/负债/权益/成本/损益）");
            level = 1;
            direction = GlConst.defaultDirection(type, code);
        } else {
            Map<String, Object> parent = findByCode(parentCode);
            if (parent == null) throw new IllegalArgumentException("上级科目不存在：" + parentCode);
            if (TmsUtil.str(parent.get("status")).equals(GlConst.ACCOUNT_DISABLED))
                throw new IllegalArgumentException("上级科目已停用，不能在其下新增科目");
            int parentLevel = TmsUtil.toInt(parent.get("accountLevel"));
            if (parentLevel >= 4) throw new IllegalArgumentException("科目最多 4 级，不能再往下增设");
            type = TmsUtil.str(parent.get("accountType"));
            direction = TmsUtil.str(parent.get("balanceDirection"));
            level = parentLevel + 1;
            int expectLen = parentCode.length() + 2;
            if (code.isEmpty()) {
                code = nextChildCode(parentCode);
            } else {
                if (code.length() != expectLen || !code.startsWith(parentCode))
                    throw new IllegalArgumentException("下级科目编码必须为 " + expectLen + " 位且以 " + parentCode + " 开头");
            }
        }
        if (!code.matches("\\d+")) throw new IllegalArgumentException("科目编码只能是数字");
        if (findByCode(code) != null) throw new IllegalArgumentException("科目编码已存在：" + code);

        String auxDim = normalizeAuxDim(TmsUtil.str(req.get("auxDimensions")));
        boolean isQty = Boolean.TRUE.equals(req.get("isQty"));
        boolean isCash = Boolean.TRUE.equals(req.get("isCash"));
        String id = TmsUtil.uuid("FA");

        jdbc.update("INSERT INTO fin_account(id, account_code, account_name, parent_code, account_level, " +
                "account_type, balance_direction, aux_dimensions, is_qty, is_cash, is_leaf, is_system, status, sort_order, remark, creator_name) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?," +
                "TRUE, FALSE, ?, ?, ?, ?)",
                id, code, name, parentCode.isEmpty() ? null : parentCode, level,
                type, direction, auxDim.isEmpty() ? null : auxDim, isQty, isCash,
                GlConst.ACCOUNT_ENABLED, 0, TmsUtil.str(req.get("remark")), TmsUtil.currentUser());

        // 上级由末级变为非末级
        if (!parentCode.isEmpty()) {
            jdbc.update("UPDATE fin_account SET is_leaf = FALSE WHERE account_code = ? AND is_leaf = TRUE", parentCode);
        }
        TmsUtil.log(jdbc, "finance.gl.account", "CREATE", code, "新增科目 " + code + " " + name);
        return findById(id);
    }

    /** 修改科目：名称/辅助核算/备注/排序可改；编码、类别、方向、上级不可改。 */
    public void update(Map<String, Object> req) {
        String id = TmsUtil.str(req.get("id"));
        Map<String, Object> acc = findById(id);
        if (acc == null) throw new IllegalArgumentException("科目不存在");
        String name = TmsUtil.str(req.get("accountName"));
        if (name.isEmpty()) throw new IllegalArgumentException("科目名称不能为空");
        String auxDim = normalizeAuxDim(TmsUtil.str(req.get("auxDimensions")));
        jdbc.update("UPDATE fin_account SET account_name = ?, aux_dimensions = ?, is_qty = ?, is_cash = ?, " +
                "remark = ?, sort_order = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                name, auxDim.isEmpty() ? null : auxDim,
                Boolean.TRUE.equals(req.get("isQty")), Boolean.TRUE.equals(req.get("isCash")),
                TmsUtil.str(req.get("remark")), TmsUtil.toInt(req.get("sortOrder")), id);
        TmsUtil.log(jdbc, "finance.gl.account", "UPDATE", TmsUtil.str(acc.get("accountCode")), "修改科目 " + name);
    }

    /** 启用/停用。 */
    public void setStatus(String id) {
        Map<String, Object> acc = findById(id);
        if (acc == null) throw new IllegalArgumentException("科目不存在");
        String code = TmsUtil.str(acc.get("accountCode"));
        String current = TmsUtil.str(acc.get("status"));
        if (GlConst.ACCOUNT_ENABLED.equals(current)) {
            // 停用前校验：下级必须都已停用
            Integer childCnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fin_account WHERE parent_code = ? AND status = ?",
                    Integer.class, code, GlConst.ACCOUNT_ENABLED);
            if (childCnt != null && childCnt > 0)
                throw new IllegalArgumentException("存在 " + childCnt + " 个启用中的下级科目，请先停用下级");
            // 已有凭证引用不可停用（fin_voucher_entry M2 建表，表存在才校验）
            if (tableExists("fin_voucher_entry")) {
                Integer refCnt = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fin_voucher_entry e JOIN fin_voucher v ON v.id = e.voucher_id " +
                        "WHERE e.account_code = ? AND v.status NOT IN (?,?)",
                        Integer.class, code, GlConst.V_VOID, GlConst.V_REVERSED);
                if (refCnt != null && refCnt > 0)
                    throw new IllegalArgumentException("该科目已有 " + refCnt + " 条有效凭证分录引用，不能停用");
            }
            jdbc.update("UPDATE fin_account SET status = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                    GlConst.ACCOUNT_DISABLED, id);
            TmsUtil.log(jdbc, "finance.gl.account", "STOP", code, "停用科目 " + code);
        } else {
            // 启用：上级必须启用
            String parentCode = TmsUtil.str(acc.get("parentCode"));
            if (!parentCode.isEmpty()) {
                Map<String, Object> parent = findByCode(parentCode);
                if (parent != null && GlConst.ACCOUNT_DISABLED.equals(TmsUtil.str(parent.get("status"))))
                    throw new IllegalArgumentException("上级科目已停用，请先启用上级科目");
            }
            jdbc.update("UPDATE fin_account SET status = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                    GlConst.ACCOUNT_ENABLED, id);
            TmsUtil.log(jdbc, "finance.gl.account", "ENABLE", code, "启用科目 " + code);
        }
    }

    /**
     * 末级启用科目下拉。
     * @param type     科目类别过滤（资产/负债/...，可空）
     * @param cashOnly 只返回现金类科目
     */
    public List<Map<String, Object>> leafOptions(String type, boolean cashOnly, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT account_code, account_name, account_type, balance_direction, " +
                "aux_dimensions, is_qty, is_cash " +
                "FROM fin_account WHERE is_leaf = TRUE AND status = '" + GlConst.ACCOUNT_ENABLED + "'");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.isEmpty()) { sql.append(" AND account_type = ?"); args.add(type); }
        if (cashOnly) sql.append(" AND is_cash = TRUE");
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (account_code LIKE ? OR account_name LIKE ?)");
            args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY account_code");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    /** 生成下级编码：父编码 + 两位序号（现有最大 +1）。 */
    private String nextChildCode(String parentCode) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT account_code FROM fin_account WHERE parent_code = ? ORDER BY account_code DESC", parentCode);
        int max = 0;
        for (Map<String, Object> r : rows) {
            String c = TmsUtil.str(r.get("accountCode"));
            String tail = c.substring(parentCode.length());
            try { max = Math.max(max, Integer.parseInt(tail)); } catch (Exception ignored) {}
        }
        if (max >= 99) throw new IllegalArgumentException("科目 " + parentCode + " 下级数量已达 99 个上限，请改用其他上级科目");
        return parentCode + String.format("%02d", max + 1);
    }

    /** 校验辅助核算维度字符串，返回规范化逗号分隔串；非法维度抛异常。 */
    public static String normalizeAuxDim(String auxDim) {
        if (auxDim == null || auxDim.isBlank()) return "";
        String[] parts = auxDim.split("[,，]");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String dim = p.trim();
            if (dim.isEmpty()) continue;
            if (!GlConst.AUX_DIMS.contains(dim))
                throw new IllegalArgumentException("非法辅助核算维度：" + dim + "，允许：" + GlConst.AUX_DIMS);
            if (!out.contains(dim)) out.add(dim);
        }
        // 按固定顺序输出，便于比较
        out.sort(java.util.Comparator.comparingInt(GlConst.AUX_DIMS::indexOf));
        return String.join(",", out);
    }

    private boolean tableExists(String table) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, table.toUpperCase());
        return cnt != null && cnt > 0;
    }
}
