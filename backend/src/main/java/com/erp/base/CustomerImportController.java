package com.erp.base;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.base.entity.BaseCustomer;
import com.erp.base.service.BaseCustomerService;
import com.erp.common.api.ApiResponse;
import com.erp.common.security.RequirePerm;
import com.erp.system.ImportTaskRecorder;
import com.erp.system.OperationAction;
import com.erp.system.OperationLogService;
import com.erp.system.OperationModule;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 客户档案导入三件套（仿商品）：
 * 导入新增 / 导入修改 / 模板下载。任务写入 sys_import_task_runtime，
 * 失败行生成真实 xlsx 落盘 data/import-failures，由【导入列表】下载。
 * 本期只导主表字段（含默认收货地址），不含多地址簿与任何余额字段。
 */
@RestController
@RequestMapping("/base/customer")
public class CustomerImportController {

    /** 导入新增 23 列：{中文表头, 驼峰键}，顺序即模板列序，须与前端 CUSTOMER_ADD_FIELDS 一致 */
    private static final String[][] ADD_FIELDS = {
            {"客户编码", "customerCode"}, {"客户名称", "customerName"}, {"渠道类型", "channelType"},
            {"联系人", "contactName"}, {"手机号", "mobile"}, {"片区", "territory"},
            {"线路", "routeLine"}, {"业务员", "salesman"}, {"客户等级", "customerLevel"},
            {"价格组编码", "priceGroupCode"}, {"信用额度", "creditLimit"}, {"发票抬头", "invoiceTitle"},
            {"税号", "taxNo"}, {"收货地址", "shippingAddress"}, {"经度", "longitude"},
            {"纬度", "latitude"}, {"结算方式", "settlementType"}, {"账期类型", "termType"},
            {"账期天数", "termDays"}, {"截账日", "cutoffDay"}, {"付款模式", "paymentMode"},
            {"账期月数", "termMonths"}, {"付款日", "paymentDay"},
    };

    /** 导入修改 24 列（含锁定的客户编码，末列状态） */
    private static final String[][] UPDATE_FIELDS = {
            {"客户编码", "customerCode"}, {"客户名称", "customerName"}, {"渠道类型", "channelType"},
            {"联系人", "contactName"}, {"手机号", "mobile"}, {"片区", "territory"},
            {"线路", "routeLine"}, {"业务员", "salesman"}, {"客户等级", "customerLevel"},
            {"价格组编码", "priceGroupCode"}, {"信用额度", "creditLimit"}, {"发票抬头", "invoiceTitle"},
            {"税号", "taxNo"}, {"收货地址", "shippingAddress"}, {"经度", "longitude"},
            {"纬度", "latitude"}, {"结算方式", "settlementType"}, {"账期类型", "termType"},
            {"账期天数", "termDays"}, {"截账日", "cutoffDay"}, {"付款模式", "paymentMode"},
            {"账期月数", "termMonths"}, {"付款日", "paymentDay"}, {"状态", "status"},
    };

    private static final Set<String> CHANNELS = Set.of("零售商超", "便利店", "餐饮店", "批发商", "电商平台");
    private static final Set<String> LEVELS = Set.of("金牌", "银牌", "铜牌", "普通");
    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern TAX_NO = Pattern.compile("^[0-9A-Z]{15,20}$");

    private final BaseCustomerService customerService;
    private final CustomerImportSupport support;
    private final OperationLogService opLog;
    private final ImportTaskRecorder taskRecorder;

    public CustomerImportController(BaseCustomerService customerService,
                                    CustomerImportSupport support,
                                    OperationLogService opLog,
                                    ImportTaskRecorder taskRecorder) {
        this.customerService = customerService;
        this.support = support;
        this.opLog = opLog;
        this.taskRecorder = taskRecorder;
    }

    // ============================ 模板下载 ============================

    @GetMapping("/import-template")
    @RequirePerm(value = "base.customer.import", name = "导入")
    public void downloadTemplate(HttpServletResponse response) throws Exception {
        // 打包成 jar 后 classpath 资源不是文件系统路径，必须走 InputStream
        try (var in = new ClassPathResource("templates/customer-import-template.xlsx").getInputStream()) {
            String fileName = URLEncoder.encode("客户档案_导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            response.setHeader("Content-Disposition", "attachment;filename=" + fileName);
            in.transferTo(response.getOutputStream());
        }
    }

    // ============================ 导入新增 ============================

    @PostMapping("/import")
    @RequirePerm(value = "base.customer.import", name = "导入")
    public ApiResponse<Map<String, Object>> importCustomers(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = asRows(body.get("rows"));
        String fileName = str(body.getOrDefault("fileName", "客户导入.xlsx"));

        int inserted = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        Set<String> batchCodes = new HashSet<>();

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2; // Excel 物理行号（含表头）
            Map<String, Object> r = rows.get(i);
            try {
                BaseCustomer entity = buildNewCustomer(r, batchCodes);
                customerService.save(entity);
                inserted++;
            } catch (IllegalArgumentException e) {
                failures.add(failure(rowNo, e.getMessage()));
            } catch (Exception e) {
                failures.add(failure(rowNo, "保存失败：" + safeMsg(e)));
            }
        }
        String taskNo = taskRecorder.record("customer", "客户导入新增", fileName, inserted, failures, ADD_FIELDS, rows);
        opLog.log(OperationModule.BASE_CUSTOMER, OperationAction.IMPORT, taskNo,
                "客户导入新增（文件 " + fileName + "）：成功 " + inserted + " 行，失败 " + failures.size() + " 行");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", inserted);
        result.put("updated", 0);
        result.put("failed", failures.size());
        result.put("skipped", failures.size());
        result.put("failures", failures);
        result.put("taskNo", taskNo);
        result.put("message", "导入完成：成功 " + inserted + " 条，失败 " + failures.size() + " 条，失败明细可在【导入列表】下载");
        return ApiResponse.ok(result);
    }

    /** 把一行 Excel 解析为客户实体；任何问题抛 IllegalArgumentException（即该行失败原因）。 */
    private BaseCustomer buildNewCustomer(Map<String, Object> r, Set<String> batchCodes) {
        String name = required(r, "customerName", "客户名称");
        String contactName = required(r, "contactName", "联系人");
        String mobile = required(r, "mobile", "手机号");
        if (!MOBILE.matcher(mobile).matches()) throw new IllegalArgumentException("手机号格式不正确：" + mobile + "（须为 11 位大陆手机号）");
        String salesman = required(r, "salesman", "业务员");
        if (!support.salesmanExists(salesman)) throw new IllegalArgumentException("业务员「" + salesman + "」不存在或非在职业务员，须先在人员信息中维护");
        String address = required(r, "shippingAddress", "收货地址");

        String channel = str(r.get("channelType"));
        if (!channel.isEmpty() && !CHANNELS.contains(channel)) throw new IllegalArgumentException("渠道类型「" + channel + "」无效，只允许：零售商超/便利店/餐饮店/批发商/电商平台");
        String level = str(r.get("customerLevel"));
        if (level.isEmpty()) level = "普通";
        else if (!LEVELS.contains(level)) throw new IllegalArgumentException("客户等级「" + level + "」无效，只允许：金牌/银牌/铜牌/普通");

        String territory = support.resolveTerritory(str(r.get("territory")));
        String routeLine = str(r.get("routeLine"));
        if (!routeLine.isEmpty() && !support.routeLineExists(routeLine)) throw new IllegalArgumentException("线路「" + routeLine + "」不存在，须先在线路资料中维护");
        String priceGroup = support.resolvePriceGroup(str(r.get("priceGroupCode")));
        BigDecimal creditLimit = nonNegativeDecimal(r.get("creditLimit"), "信用额度");
        String taxNo = str(r.get("taxNo")).toUpperCase(Locale.ROOT);
        if (!taxNo.isEmpty() && !TAX_NO.matcher(taxNo).matches()) throw new IllegalArgumentException("税号格式不正确：" + taxNo + "（须为 15-20 位字母或数字）");
        BigDecimal lng = parseLng(r.get("longitude"), "经度");
        BigDecimal lat = parseLat(r.get("latitude"), "纬度");
        if ((lng == null) != (lat == null)) throw new IllegalArgumentException("经度、纬度必须同时填写或同时不填");

        // 客户编码：空则自动 K+6 位流水；手工编码查重（库内 + 批内）
        String code = str(r.get("customerCode"));
        if (code.isEmpty()) {
            code = support.nextCustomerCode();
        } else if (batchCodes.contains(code)
                || customerService.getOne(new QueryWrapper<BaseCustomer>().eq("customer_code", code).last("LIMIT 1")) != null) {
            throw new IllegalArgumentException("客户编码「" + code + "」已存在");
        }
        batchCodes.add(code);

        BaseCustomer e = new BaseCustomer();
        e.setCustomerId("C" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        e.setCustomerCode(code);
        e.setCustomerName(name);
        e.setChannelType(channel);
        e.setContactName(contactName);
        e.setMobile(mobile);
        e.setTerritory(territory);
        e.setRouteLine(routeLine);
        e.setSalesman(salesman);
        e.setCustomerLevel(level);
        e.setPriceGroupCode(priceGroup);
        e.setCreditLimit(creditLimit);
        e.setInvoiceTitle(str(r.get("invoiceTitle")));
        e.setTaxNo(taxNo);
        e.setShippingAddress(address);
        e.setLongitude(lng);
        e.setLatitude(lat);
        e.setStatus("NORMAL");
        // 账期设置（严格依赖校验）
        TermValues term = parseTermForAdd(r);
        e.setSettlementType(term.settlementType);
        e.setTermType(term.termType);
        e.setTermDays(term.termDays);
        e.setCutoffDay(term.cutoffDay);
        e.setPaymentMode(term.paymentMode);
        e.setTermMonths(term.termMonths);
        e.setPaymentDay(term.paymentDay);
        return e;
    }

    // ============================ 导入修改 ============================

    @PostMapping("/import-update")
    @RequirePerm(value = "base.customer.import", name = "导入")
    public ApiResponse<Map<String, Object>> importUpdate(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = asRows(body.get("rows"));
        String fileName = str(body.getOrDefault("fileName", "客户导入修改.xlsx"));
        // 前端勾选的可修改字段白名单（客户编码为定位键恒包含）；未传时按全字段兼容
        Set<String> allowed = null;
        Object fieldsObj = body.get("fields");
        if (fieldsObj instanceof Collection<?> coll && !coll.isEmpty()) {
            allowed = new HashSet<>();
            for (Object o : coll) allowed.add(str(o));
            allowed.add("customerCode");
        }

        int updated = 0;
        List<Map<String, Object>> failures = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2;
            Map<String, Object> r = filterRowFields(rows.get(i), allowed);
            try {
                updateOneCustomer(r);
                updated++;
            } catch (IllegalArgumentException e) {
                failures.add(failure(rowNo, e.getMessage()));
            } catch (Exception e) {
                failures.add(failure(rowNo, "更新失败：" + safeMsg(e)));
            }
        }
        String taskNo = taskRecorder.record("customer", "客户导入修改", fileName, updated, failures, UPDATE_FIELDS, rows);
        opLog.log(OperationModule.BASE_CUSTOMER, OperationAction.IMPORT, taskNo,
                "客户导入修改（文件 " + fileName + "）：更新 " + updated + " 行，失败 " + failures.size() + " 行");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", 0);
        result.put("updated", updated);
        result.put("failed", failures.size());
        result.put("skipped", failures.size());
        result.put("failures", failures);
        result.put("taskNo", taskNo);
        result.put("message", "导入修改完成：更新 " + updated + " 条，失败 " + failures.size() + " 条，失败明细可在【导入列表】下载");
        return ApiResponse.ok(result);
    }

    /** 空单元格不更新；账期依赖在「现值 + 本次非空字段」合并后的完整客户上校验。 */
    private void updateOneCustomer(Map<String, Object> r) {
        String code = required(r, "customerCode", "客户编码");
        BaseCustomer e = customerService.getOne(new QueryWrapper<BaseCustomer>().eq("customer_code", code).last("LIMIT 1"));
        if (e == null) throw new IllegalArgumentException("客户编码「" + code + "」不存在，无法修改");

        if (notBlank(r, "customerName")) e.setCustomerName(str(r.get("customerName")));
        if (notBlank(r, "channelType")) {
            String channel = str(r.get("channelType"));
            if (!CHANNELS.contains(channel)) throw new IllegalArgumentException("渠道类型「" + channel + "」无效，只允许：零售商超/便利店/餐饮店/批发商/电商平台");
            e.setChannelType(channel);
        }
        if (notBlank(r, "contactName")) e.setContactName(str(r.get("contactName")));
        if (notBlank(r, "mobile")) {
            String mobile = str(r.get("mobile"));
            if (!MOBILE.matcher(mobile).matches()) throw new IllegalArgumentException("手机号格式不正确：" + mobile + "（须为 11 位大陆手机号）");
            e.setMobile(mobile);
        }
        if (notBlank(r, "territory")) e.setTerritory(support.resolveTerritory(str(r.get("territory"))));
        if (notBlank(r, "routeLine")) {
            String routeLine = str(r.get("routeLine"));
            if (!support.routeLineExists(routeLine)) throw new IllegalArgumentException("线路「" + routeLine + "」不存在，须先在线路资料中维护");
            e.setRouteLine(routeLine);
        }
        if (notBlank(r, "salesman")) {
            String salesman = str(r.get("salesman"));
            if (!support.salesmanExists(salesman)) throw new IllegalArgumentException("业务员「" + salesman + "」不存在或非在职业务员，须先在人员信息中维护");
            e.setSalesman(salesman);
        }
        if (notBlank(r, "customerLevel")) {
            String level = str(r.get("customerLevel"));
            if (!LEVELS.contains(level)) throw new IllegalArgumentException("客户等级「" + level + "」无效，只允许：金牌/银牌/铜牌/普通");
            e.setCustomerLevel(level);
        }
        if (r.containsKey("priceGroupCode")) {
            // 显式传空列不触发（空=不更新）；非空才解析
            String pg = str(r.get("priceGroupCode"));
            if (!pg.isEmpty()) e.setPriceGroupCode(support.resolvePriceGroup(pg));
        }
        if (notBlank(r, "creditLimit")) e.setCreditLimit(nonNegativeDecimal(r.get("creditLimit"), "信用额度"));
        if (notBlank(r, "invoiceTitle")) e.setInvoiceTitle(str(r.get("invoiceTitle")));
        if (notBlank(r, "taxNo")) {
            String taxNo = str(r.get("taxNo")).toUpperCase(Locale.ROOT);
            if (!TAX_NO.matcher(taxNo).matches()) throw new IllegalArgumentException("税号格式不正确：" + taxNo + "（须为 15-20 位字母或数字）");
            e.setTaxNo(taxNo);
        }
        if (notBlank(r, "shippingAddress")) e.setShippingAddress(str(r.get("shippingAddress")));
        if (notBlank(r, "longitude") || notBlank(r, "latitude")) {
            // 经纬度成对：本次只填一个时，另一个取现值，仍缺则失败
            BigDecimal lng = notBlank(r, "longitude") ? parseLng(r.get("longitude"), "经度") : e.getLongitude();
            BigDecimal lat = notBlank(r, "latitude") ? parseLat(r.get("latitude"), "纬度") : e.getLatitude();
            if ((lng == null) != (lat == null)) throw new IllegalArgumentException("经度、纬度必须同时填写或同时清空");
            e.setLongitude(lng);
            e.setLatitude(lat);
        }
        if (notBlank(r, "status")) e.setStatus(normalizeStatus(str(r.get("status"))));

        // 任一个账期相关列被勾选且填值 → 在现值基础上合并并严格校验；未涉及账期列时保留原账期设置
        boolean termTouched = TERM_KEYS.stream().anyMatch(k -> notBlank(r, k));
        if (termTouched) applyTermForUpdate(e, r);

        customerService.updateById(e);
        if (termTouched) {
            // updateById 默认 NOT_NULL 策略不写 null 列，账期结构切换时不适用列必须强制清空：
            // 7 个账期列按最终结构全量重写（含显式 set null）
            customerService.update(new LambdaUpdateWrapper<BaseCustomer>()
                    .eq(BaseCustomer::getCustomerId, e.getCustomerId())
                    .set(BaseCustomer::getSettlementType, e.getSettlementType())
                    .set(BaseCustomer::getTermType, e.getTermType())
                    .set(BaseCustomer::getTermDays, e.getTermDays())
                    .set(BaseCustomer::getCutoffDay, e.getCutoffDay())
                    .set(BaseCustomer::getPaymentMode, e.getPaymentMode())
                    .set(BaseCustomer::getTermMonths, e.getTermMonths())
                    .set(BaseCustomer::getPaymentDay, e.getPaymentDay()));
        }
    }

    // ============================ 账期依赖校验 ============================

    private static final List<String> TERM_KEYS = List.of(
            "settlementType", "termType", "termDays", "cutoffDay", "paymentMode", "termMonths", "paymentDay");

    /** 新增：结算方式不填默认预付；依赖不符直接失败。 */
    private TermValues parseTermForAdd(Map<String, Object> r) {
        TermValues t = new TermValues();
        t.settlementType = normalizeSettlement(r.get("settlementType"));
        if (t.settlementType.isEmpty()) t.settlementType = "PREPAY";
        t.termType = normalizeTermType(r.get("termType"));
        t.paymentMode = normalizePaymentMode(r.get("paymentMode"));
        t.termDays = blank(r.get("termDays")) ? null : parseIntRange(r.get("termDays"), "账期天数", 0, 365);
        t.cutoffDay = blank(r.get("cutoffDay")) ? null : parseDay(r.get("cutoffDay"), "截账日");
        t.termMonths = blank(r.get("termMonths")) ? null : parseNonNegativeInt(r.get("termMonths"), "账期月数");
        t.paymentDay = blank(r.get("paymentDay")) ? null : parseDay(r.get("paymentDay"), "付款日");
        validateTerm(t, r, true);
        return t;
    }

    /**
     * 修改：结构字段（结算方式/账期类型/付款模式）在本次给值时，按新结构重建账期设置——
     * 适用字段取「本次值优先、现值兜底」，不适用于新结构的现值列自动清空；
     * 本次填了与最终结构不匹配的列 → 失败（避免静默丢数据）。
     */
    private void applyTermForUpdate(BaseCustomer e, Map<String, Object> r) {
        String st = notBlank(r, "settlementType") ? normalizeSettlement(r.get("settlementType")) : nz(e.getSettlementType());
        if (st.isEmpty()) st = "PREPAY";

        if ("PREPAY".equals(st) || "COD".equals(st)) {
            if (TERM_KEYS.stream().skip(1).anyMatch(k -> notBlank(r, k))) {
                throw new IllegalArgumentException("结算方式为预付/货到付款时不可填写账期信息（账期类型/天数/截账日/付款模式/月数/付款日须留空）");
            }
            e.setSettlementType(st);
            e.setTermType(null);
            e.setTermDays(null);
            e.setCutoffDay(null);
            e.setPaymentMode(null);
            e.setTermMonths(null);
            e.setPaymentDay(null);
            return;
        }
        if (!"TERM".equals(st)) throw new IllegalArgumentException("结算方式「" + r.get("settlementType") + "」无效，只允许：预付/货到付款/账期");

        String tt = notBlank(r, "termType") ? normalizeTermType(r.get("termType")) : nz(e.getTermType());
        if (tt.isEmpty()) throw new IllegalArgumentException("结算方式为账期时，账期类型必填（固定账期天数/周结/半月结/月结）");

        TermValues t = new TermValues();
        t.settlementType = "TERM";
        t.termType = tt;
        t.termDays = pickInt(r, "termDays", e.getTermDays(), "账期天数", v -> parseIntRange(v, "账期天数", 0, 365));
        t.cutoffDay = pickStr(r, "cutoffDay", e.getCutoffDay(), "截账日", v -> parseDay(v, "截账日"));
        t.paymentMode = notBlank(r, "paymentMode") ? normalizePaymentMode(r.get("paymentMode")) : nz(e.getPaymentMode());
        t.termMonths = pickInt(r, "termMonths", e.getTermMonths(), "账期月数", v -> parseNonNegativeInt(v, "账期月数"));
        t.paymentDay = pickStr(r, "paymentDay", e.getPaymentDay(), "付款日", v -> parseDay(v, "付款日"));
        // 用合并后的值做依赖校验（present 以值非空判定）
        validateTerm(t, mergedPresence(t), false);

        e.setSettlementType("TERM");
        e.setTermType(tt);
        switch (tt) {
            case "FIXED", "WEEKLY", "SEMI_MONTH" -> {
                e.setTermDays(t.termDays);
                e.setCutoffDay(null);
                e.setPaymentMode(null);
                e.setTermMonths(null);
                e.setPaymentDay(null);
            }
            case "MONTHLY" -> {
                e.setCutoffDay(t.cutoffDay);
                e.setPaymentMode(t.paymentMode);
                if ("A".equals(t.paymentMode)) {
                    e.setTermDays(t.termDays);
                    e.setTermMonths(null);
                    e.setPaymentDay(null);
                } else {
                    e.setTermDays(null);
                    e.setTermMonths(t.termMonths);
                    e.setPaymentDay(t.paymentDay);
                }
            }
            default -> throw new IllegalArgumentException("账期类型「" + tt + "」无效");
        }
    }

    /**
     * 账期依赖校验。
     * 新增（strictColumns=true）：本次填了与结构不匹配的列即失败；
     * 修改：入参为现值+本次值合并结果，required 列缺失即失败。
     */
    private void validateTerm(TermValues t, Map<String, Object> present, boolean strictColumns) {
        String st = t.settlementType;
        if ("PREPAY".equals(st) || "COD".equals(st)) {
            if (anyPresent(present, "termType", "termDays", "cutoffDay", "paymentMode", "termMonths", "paymentDay")) {
                throw new IllegalArgumentException("结算方式为预付/货到付款时不可填写账期信息（账期类型/天数/截账日/付款模式/月数/付款日须留空）");
            }
            return;
        }
        if (!"TERM".equals(st)) throw new IllegalArgumentException("结算方式取值无效，只允许：预付/货到付款/账期");
        if (t.termType.isEmpty()) throw new IllegalArgumentException("结算方式为账期时，账期类型必填（固定账期天数/周结/半月结/月结）");

        switch (t.termType) {
            case "FIXED", "WEEKLY", "SEMI_MONTH" -> {
                if (strictColumns && anyPresent(present, "cutoffDay", "paymentMode", "termMonths", "paymentDay")) {
                    throw new IllegalArgumentException("账期类型为" + termTypeLabel(t.termType) + "时，截账日/付款模式/账期月数/付款日须留空（仅月结使用）");
                }
                if (t.termDays == null) throw new IllegalArgumentException("账期类型为" + termTypeLabel(t.termType) + "时，账期天数必填（0-365）");
            }
            case "MONTHLY" -> {
                if (t.cutoffDay == null) throw new IllegalArgumentException("月结客户截账日必填（1-31，31 表示月末）");
                if (t.paymentMode.isEmpty()) throw new IllegalArgumentException("月结客户付款模式必填（A=截账后 N 天，B=截账后 N 月第 M 天）");
                if ("A".equals(t.paymentMode)) {
                    if (strictColumns && anyPresent(present, "termMonths", "paymentDay")) {
                        throw new IllegalArgumentException("付款模式 A（截账后 N 天）时，账期月数/付款日须留空");
                    }
                    if (t.termDays == null) throw new IllegalArgumentException("付款模式 A 时，账期天数必填（0-365）");
                } else if ("B".equals(t.paymentMode)) {
                    if (strictColumns && anyPresent(present, "termDays")) {
                        throw new IllegalArgumentException("付款模式 B（截账后 N 月第 M 天）时，账期天数须留空");
                    }
                    if (t.termMonths == null) throw new IllegalArgumentException("付款模式 B 时，账期月数必填（≥0）");
                    if (t.paymentDay == null) throw new IllegalArgumentException("付款模式 B 时，付款日必填（1-31，31 表示月末）");
                } else {
                    throw new IllegalArgumentException("付款模式取值无效，只允许：A/B");
                }
            }
            default -> throw new IllegalArgumentException("账期类型取值无效，只允许：固定账期天数/周结/半月结/月结");
        }
    }

    private static String termTypeLabel(String code) {
        return switch (code) {
            case "FIXED" -> "固定账期天数";
            case "WEEKLY" -> "周结";
            case "SEMI_MONTH" -> "半月结";
            case "MONTHLY" -> "月结";
            default -> code;
        };
    }

    private static class TermValues {
        String settlementType = "";
        String termType = "";
        Integer termDays;
        String cutoffDay;
        String paymentMode = "";
        Integer termMonths;
        String paymentDay;
    }

    // ============================ 枚举/数值解析 ============================

    private static String normalizeSettlement(Object v) {
        String s = str(v);
        return switch (s) {
            case "" -> "";
            case "预付", "PREPAY" -> "PREPAY";
            case "货到付款", "COD" -> "COD";
            case "账期", "TERM" -> "TERM";
            default -> throw new IllegalArgumentException("结算方式「" + s + "」无效，只允许：预付/货到付款/账期");
        };
    }

    private static String normalizeTermType(Object v) {
        String s = str(v);
        return switch (s) {
            case "" -> "";
            case "固定账期天数", "FIXED" -> "FIXED";
            case "周结", "WEEKLY" -> "WEEKLY";
            case "半月结", "SEMI_MONTH" -> "SEMI_MONTH";
            case "月结", "MONTHLY" -> "MONTHLY";
            default -> throw new IllegalArgumentException("账期类型「" + s + "」无效，只允许：固定账期天数/周结/半月结/月结");
        };
    }

    private static String normalizePaymentMode(Object v) {
        String s = str(v).toUpperCase(Locale.ROOT);
        if (s.isEmpty() || "A".equals(s) || "B".equals(s)) return s;
        throw new IllegalArgumentException("付款模式「" + v + "」无效，只允许：A（截账后 N 天）/B（截账后 N 月第 M 天）");
    }

    private static String normalizeStatus(String s) {
        return switch (s) {
            case "正常", "NORMAL" -> "NORMAL";
            case "停用", "STOPPED" -> "STOPPED";
            default -> throw new IllegalArgumentException("状态「" + s + "」无效，只允许：正常/停用");
        };
    }

    private static Integer parseIntRange(Object v, String label, int min, int max) {
        int n = parseIntStrict(v, label);
        if (n < min || n > max) throw new IllegalArgumentException(label + "取值范围为 " + min + "-" + max + "：" + v);
        return n;
    }

    private static int parseNonNegativeInt(Object v, String label) {
        int n = parseIntStrict(v, label);
        if (n < 0) throw new IllegalArgumentException(label + "不能为负数：" + v);
        return n;
    }

    private static String parseDay(Object v, String label) {
        int n = parseIntStrict(v, label);
        if (n < 1 || n > 31) throw new IllegalArgumentException(label + "取值范围为 1-31（31 表示月末）：" + v);
        return String.valueOf(n);
    }

    private static int parseIntStrict(Object v, String label) {
        String s = str(v).replace(",", "");
        try {
            if (!s.matches("-?\\d+")) throw new NumberFormatException();
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "不是合法整数：" + v);
        }
    }

    private static BigDecimal parseLng(Object v, String label) {
        if (blank(v)) return null;
        BigDecimal d = decimalStrict(v, label);
        if (d.doubleValue() < -180 || d.doubleValue() > 180) throw new IllegalArgumentException(label + "取值范围为 -180~180：" + v);
        return d;
    }

    private static BigDecimal parseLat(Object v, String label) {
        if (blank(v)) return null;
        BigDecimal d = decimalStrict(v, label);
        if (d.doubleValue() < -90 || d.doubleValue() > 90) throw new IllegalArgumentException(label + "取值范围为 -90~90：" + v);
        return d;
    }

    private static BigDecimal nonNegativeDecimal(Object v, String label) {
        if (blank(v)) return BigDecimal.ZERO;
        BigDecimal d = decimalStrict(v, label);
        if (d.signum() < 0) throw new IllegalArgumentException(label + "不能为负数：" + v);
        return d;
    }

    private static BigDecimal decimalStrict(Object v, String label) {
        try {
            return new BigDecimal(str(v).replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "不是合法数字：" + v);
        }
    }

    // ============================ 小工具 ============================

    private interface IntParser { Integer apply(Object v); }
    private interface StrParser { String apply(Object v); }

    /** 修改合并：本次填值用本次解析值，否则取现值（原值为 null 视为无）。 */
    private Integer pickInt(Map<String, Object> r, String key, Integer existing, String label, IntParser parser) {
        if (notBlank(r, key)) return parser.apply(r.get(key));
        return existing;
    }

    private String pickStr(Map<String, Object> r, String key, String existing, String label, StrParser parser) {
        if (notBlank(r, key)) return parser.apply(r.get(key));
        return existing == null ? null : (existing.isEmpty() ? null : existing);
    }

    /** 用合并后的 TermValues 构造 presence 视图（值非空即视为存在）。 */
    private static Map<String, Object> mergedPresence(TermValues t) {
        Map<String, Object> m = new HashMap<>();
        if (!t.termType.isEmpty()) m.put("termType", "1");
        if (t.termDays != null) m.put("termDays", "1");
        if (t.cutoffDay != null && !t.cutoffDay.isEmpty()) m.put("cutoffDay", "1");
        if (!t.paymentMode.isEmpty()) m.put("paymentMode", "1");
        if (t.termMonths != null) m.put("termMonths", "1");
        if (t.paymentDay != null && !t.paymentDay.isEmpty()) m.put("paymentDay", "1");
        return m;
    }

    private static boolean anyPresent(Map<String, Object> m, String... keys) {
        for (String k : keys) if (m.get(k) != null && !String.valueOf(m.get(k)).isBlank()) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asRows(Object obj) {
        if (!(obj instanceof List<?> list)) throw new IllegalArgumentException("缺少 rows 数据");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private Map<String, Object> filterRowFields(Map<String, Object> row, Set<String> allowed) {
        if (allowed == null) return row;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (allowed.contains(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private Map<String, Object> failure(int rowNo, String reason) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("rowNo", rowNo);
        f.put("reason", reason);
        return f;
    }

    private String required(Map<String, Object> r, String key, String label) {
        String v = str(r.get(key));
        if (v.isEmpty()) throw new IllegalArgumentException(label + "必填");
        return v;
    }

    private static String nz(String v) { return v == null ? "" : v.trim(); }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static boolean blank(Object v) {
        return str(v).isEmpty();
    }

    private static boolean notBlank(Map<String, Object> r, String key) {
        return !blank(r.get(key));
    }

    private static String safeMsg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
