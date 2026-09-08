package com.erp.finance.gl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 总账模块公共常量：系统参数键、辅助核算维度、状态值。
 * 参数值统一用 Y/N（SysParamService.getBool 只认 Y）。
 */
public final class GlConst {

    private GlConst() {}

    // ===== sys_param_runtime 参数键 =====
    public static final String PARAM_ENABLED = "fin.gl.enabled";             // 总账功能开关
    public static final String PARAM_INITIALIZED = "fin.gl.initialized";     // 总账是否已启用（期初已结账）
    public static final String PARAM_START_YEAR = "fin.gl.start_year";       // 启用年度
    public static final String PARAM_START_PERIOD = "fin.gl.start_period";   // 启用期间 yyyyMM
    public static final String PARAM_AUTO_EVENT = "fin.gl.auto_event";       // 业务审核自动丢会计事件
    public static final String PARAM_TAXPAYER_TYPE = "fin.gl.taxpayer_type"; // GENERAL/SMALL 纳税人类型
    public static final String PARAM_MAKER_CHECKER = "fin.gl.maker_checker_separate"; // 制审分离
    public static final String PARAM_EXPENSE_DEDUCTIBLE = "fin.gl.expense_deductible"; // 费用进项可抵扣
    public static final String PARAM_SURTAX_RATE = "fin.gl.surtax_rate";     // 附加税费合计率

    // ===== 辅助核算维度（7 维，顺序即分录列顺序）=====
    public static final String DIM_CUSTOMER = "customer";
    public static final String DIM_SUPPLIER = "supplier";
    public static final String DIM_DEPARTMENT = "department";
    public static final String DIM_EMPLOYEE = "employee";
    public static final String DIM_GOODS = "goods";
    public static final String DIM_PROJECT = "project";
    public static final String DIM_AREA = "area";

    public static final List<String> AUX_DIMS = List.of(
            DIM_CUSTOMER, DIM_SUPPLIER, DIM_DEPARTMENT, DIM_EMPLOYEE,
            DIM_GOODS, DIM_PROJECT, DIM_AREA);

    public static final Map<String, String> AUX_LABELS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(DIM_CUSTOMER, "客户");
        m.put(DIM_SUPPLIER, "供应商");
        m.put(DIM_DEPARTMENT, "部门");
        m.put(DIM_EMPLOYEE, "员工");
        m.put(DIM_GOODS, "商品");
        m.put(DIM_PROJECT, "项目");
        m.put(DIM_AREA, "片区");
        AUX_LABELS = Map.copyOf(m);
    }

    // ===== 科目状态 =====
    public static final String ACCOUNT_ENABLED = "启用";
    public static final String ACCOUNT_DISABLED = "停用";

    // ===== 凭证状态 =====
    public static final String V_DRAFT = "草稿";
    public static final String V_AUDITED = "已审核";
    public static final String V_POSTED = "已过账";
    public static final String V_VOID = "已作废";
    public static final String V_REVERSED = "已冲销";

    // ===== 会计事件状态 =====
    public static final String E_PENDING = "待生成";
    public static final String E_DONE = "已生成";
    public static final String E_IGNORED = "已忽略";
    public static final String E_FAILED = "生成失败";
    public static final String E_REVERSED = "已冲回";
    /** 正向事件已生成的凭证已被红字凭证冲销（反审核时凭证已过账）；重新审核可复用该行重新生成。 */
    public static final String E_WRITTEN_OFF = "已冲销";

    // ===== 会计期间状态 =====
    public static final String P_NOT_STARTED = "未开始";
    public static final String P_IN_PROGRESS = "进行中";
    public static final String P_CLOSED = "已结账";
    public static final String P_FROZEN = "已冻结";

    /**
     * 科目类别默认余额方向：资产/成本借；负债/权益贷；
     * 损益类 5001~5399（收入）贷，5400 起（成本费用）借。
     */
    public static String defaultDirection(String accountType, String accountCode) {
        return switch (accountType) {
            case "资产", "成本" -> "借";
            case "负债", "权益" -> "贷";
            case "损益" -> accountCode.compareTo("5400") < 0 ? "贷" : "借";
            default -> "借";
        };
    }

    /** 科目编码首位 → 科目类别。 */
    public static String typeOfCode(String accountCode) {
        if (accountCode == null || accountCode.isEmpty()) return "";
        return switch (accountCode.charAt(0)) {
            case '1' -> "资产";
            case '2' -> "负债";
            case '3' -> "权益";
            case '4' -> "成本";
            case '5' -> "损益";
            default -> "";
        };
    }
}
