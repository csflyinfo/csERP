package com.erp.finance.gl;

import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 档案科目映射（总账 M4）：资金账户→资金类科目、费用类型→费用科目、商品分类→收入/成本科目。
 * 类型维护仍在各自基础档案页，映射列在 V95 加到档案表；本服务提供总账侧集中映射页与保存校验。
 */
@Service
public class GlArchiveMappingService {

    private final JdbcTemplate jdbc;

    public GlArchiveMappingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 三组档案 + 当前映射 + 末级科目下拉，一次取齐。 */
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        // 注意：别名必须用下划线形式，TmsUtil.camelizeKey 会整体转小写后再驼峰化，
        // 直接写驼峰别名（glAccountCode）会被折成 glaccountcode。
        out.put("fundAccounts", TmsUtil.queryCamel(jdbc,
                "SELECT fund_account_code code, fund_account_name name, account_type, gl_account_code, " +
                        "status, is_system FROM base_fund_account ORDER BY fund_account_code"));
        out.put("expenseTypes", TmsUtil.queryCamel(jdbc,
                "SELECT expense_type_code code, expense_type_name name, direction, " +
                        "gl_expense_account_code AS gl_account_code, status FROM base_expense_type ORDER BY expense_type_code"));
        out.put("categories", TmsUtil.queryCamel(jdbc,
                "SELECT category_code code, category_name name, gl_income_account_code, " +
                        "gl_cost_account_code, status FROM base_category ORDER BY category_code"));
        out.put("leafAccounts", TmsUtil.queryCamel(jdbc,
                "SELECT account_code code, account_name name, is_cash FROM fin_account " +
                        "WHERE is_leaf = TRUE AND status = '" + GlConst.ACCOUNT_ENABLED + "' ORDER BY account_code"));
        return out;
    }

    /**
     * 保存映射。type：fund 资金账户 / expense 费用类型 / category 商品分类（收入+成本两科目）。
     */
    public void save(Map<String, Object> body) {
        String type = TmsUtil.str(body.get("type"));
        String code = TmsUtil.str(body.get("code"));
        if (code.isEmpty()) throw new IllegalArgumentException("档案编码不能为空");
        switch (type) {
            case "fund" -> {
                String subject = TmsUtil.str(body.get("glAccountCode"));
                if (subject.isEmpty()) {
                    update("base_fund_account", "gl_account_code", null, "fund_account_code", code);
                    return;
                }
                Map<String, Object> acc = requireLeafEnabled(subject);
                if (!Boolean.TRUE.equals(acc.get("isCash")))
                    throw new IllegalArgumentException("资金账户只能映射到「库存现金/银行存款」等资金类科目（is_cash），科目「"
                            + subject + "」不是资金类科目");
                update("base_fund_account", "gl_account_code", subject, "fund_account_code", code);
            }
            case "expense" -> {
                String subject = TmsUtil.str(body.get("glAccountCode"));
                if (subject.isEmpty()) {
                    update("base_expense_type", "gl_expense_account_code", null, "expense_type_code", code);
                    return;
                }
                requireLeafEnabled(subject);
                update("base_expense_type", "gl_expense_account_code", subject, "expense_type_code", code);
            }
            case "category" -> {
                String income = TmsUtil.str(body.get("glIncomeAccountCode"));
                String cost = TmsUtil.str(body.get("glCostAccountCode"));
                if (!income.isEmpty()) requireLeafEnabled(income);
                if (!cost.isEmpty()) requireLeafEnabled(cost);
                int rc = jdbc.update("UPDATE base_category SET gl_income_account_code = ?, gl_cost_account_code = ? " +
                        "WHERE category_code = ?", emptyToNull(income), emptyToNull(cost), code);
                if (rc == 0) throw new IllegalArgumentException("商品分类「" + code + "」不存在");
            }
            default -> throw new IllegalArgumentException("未知映射类型：" + type);
        }
    }

    private Map<String, Object> requireLeafEnabled(String code) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT account_code, account_name, is_cash, status FROM fin_account " +
                        "WHERE account_code = ? AND is_leaf = TRUE", code);
        if (rows.isEmpty())
            throw new IllegalArgumentException("科目「" + code + "」不存在或非末级科目，请在科目档案中检查");
        Map<String, Object> acc = rows.get(0);
        if (!GlConst.ACCOUNT_ENABLED.equals(TmsUtil.str(acc.get("status"))))
            throw new IllegalArgumentException("科目「" + code + " " + TmsUtil.str(acc.get("accountName"))
                    + "」已停用，请先在科目档案中启用");
        return acc;
    }

    private void update(String table, String setCol, String value, String whereCol, String code) {
        int rc = jdbc.update("UPDATE " + table + " SET " + setCol + " = ? WHERE " + whereCol + " = ?", value, code);
        if (rc == 0) throw new IllegalArgumentException("档案「" + code + "」不存在");
    }

    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }
}
