package com.erp.base;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.erp.base.entity.BaseCategory;
import com.erp.base.entity.BaseGoods;
import com.erp.base.service.BaseGoodsService;
import com.erp.common.api.ApiResponse;
import com.erp.common.security.RequirePerm;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 商品档案导入三件套（V122 商品档案优化）：
 * 导入新增 / 导入修改 / 模板下载。导入任务写入 sys_import_task_runtime，
 * 失败行生成真实 xlsx 落盘 data/import-failures，由【导入列表】下载。
 */
@RestController
@RequestMapping("/base/goods")
public class GoodsImportController {

    /** 导入新增模板 48 列：{中文表头, 驼峰键}，顺序即模板列序 */
    private static final String[][] ADD_FIELDS = {
            {"商品编码", "goodsCode"}, {"商品名称", "goodsName"}, {"规格", "spec"},
            {"商品分类编号", "categoryCode"}, {"品牌名称", "brandName"}, {"基本单位", "baseUnit"},
            {"基本条码", "barcode"}, {"默认供应商", "defaultSupplier"}, {"默认仓库", "defaultWarehouse"},
            {"商品类型", "goodsType"}, {"税率", "taxRate"}, {"启用价格联动", "priceLinked"},
            {"是否生鲜", "isFresh"}, {"标准售价", "standardPrice"}, {"参考进价", "latestPurchasePrice"},
            {"最低售价", "minSalePrice"}, {"商品负责人", "goodsManager"}, {"保质期(天)", "shelfLifeDays"},
            {"存储属性", "storageProperty"}, {"建议零售价", "suggestedRetailPrice"},
            {"库存上限", "stockUpperLimit"}, {"库存下限", "stockLowerLimit"},
            {"是否预售品", "isPresale"}, {"是否可退", "canReturn"}, {"是否称重", "isWeighted"},
            {"商品等级", "goodsLevel"}, {"产地", "origin"}, {"采购起订量", "minOrderQty"},
            {"临期预警天数", "warningDays"},
            {"大单位", "largeUnit"}, {"大单位换算数量", "largeConvertQty"}, {"大单位条码", "largeBarcode"},
            {"大单位标价", "largeStandardPrice"}, {"大单位参考进价", "largePurchasePrice"}, {"大单位最低价", "largeMinPrice"},
            {"中单位", "middleUnit"}, {"中单位换算数量", "middleConvertQty"}, {"中单位条码", "middleBarcode"},
            {"中单位标价", "middleStandardPrice"}, {"中单位参考进价", "middlePurchasePrice"}, {"中单位最低价", "middleMinPrice"},
            {"小单位重量", "smallWeight"}, {"大单位重量", "largeWeight"}, {"中单位重量", "middleWeight"},
            {"小单位体积", "smallVolume"}, {"大单位体积", "largeVolume"}, {"中单位体积", "middleVolume"},
            {"备注", "remark"},
    };

    /** 导入修改 41 列（含锁定的商品编码）；顺序即勾选区展示与失败文件列序 */
    private static final String[][] UPDATE_FIELDS = {
            {"商品编码", "goodsCode"}, {"商品名称", "goodsName"}, {"规格", "spec"},
            {"商品分类编号", "categoryCode"}, {"品牌名称", "brandName"}, {"基本单位", "baseUnit"},
            {"默认采购单位", "defaultPurchaseUnit"},
            {"基本条码", "barcode"}, {"状态", "status"}, {"默认供应商", "defaultSupplier"},
            {"税率", "taxRate"}, {"默认仓库", "defaultWarehouse"}, {"商品类型", "goodsType"},
            {"启用价格联动", "priceLinked"}, {"是否生鲜", "isFresh"}, {"商品负责人", "goodsManager"},
            {"保质期(天)", "shelfLifeDays"}, {"存储属性", "storageProperty"}, {"建议零售价", "suggestedRetailPrice"},
            {"库存上限", "stockUpperLimit"}, {"库存下限", "stockLowerLimit"},
            {"是否预售品", "isPresale"}, {"是否可退", "canReturn"}, {"是否称重", "isWeighted"},
            {"商品等级", "goodsLevel"}, {"产地", "origin"}, {"采购起订量", "minOrderQty"},
            {"临期预警天数", "warningDays"},
            {"大单位", "largeUnit"}, {"大单位换算数量", "largeConvertQty"}, {"大单位条码", "largeBarcode"},
            {"中单位", "middleUnit"}, {"中单位换算数量", "middleConvertQty"}, {"中单位条码", "middleBarcode"},
            {"小单位重量", "smallWeight"}, {"大单位重量", "largeWeight"}, {"中单位重量", "middleWeight"},
            {"小单位体积", "smallVolume"}, {"大单位体积", "largeVolume"}, {"中单位体积", "middleVolume"},
            {"备注", "remark"},
    };

    private static final File FAILURE_DIR = new File("data/import-failures");

    private final BaseGoodsService goodsService;
    private final GoodsImportSupport support;
    private final JdbcTemplate jdbcTemplate;
    private final com.erp.system.OperationLogService opLog;
    private final ObjectMapper mapper = new ObjectMapper();

    public GoodsImportController(BaseGoodsService goodsService,
                                 GoodsImportSupport support,
                                 JdbcTemplate jdbcTemplate,
                                 com.erp.system.OperationLogService opLog) {
        this.goodsService = goodsService;
        this.support = support;
        this.jdbcTemplate = jdbcTemplate;
        this.opLog = opLog;
    }

    // ============================ 模板下载 ============================

    /** 商品导入模板（双页签：goods + 导入说明；无示例数据行），与桌面文件一致 */
    @GetMapping("/import-template")
    @RequirePerm(value = "base.goods.import", name = "导入")
    public void downloadTemplate(HttpServletResponse response) throws Exception {
        // 打包成 jar 后 classpath 资源不是文件系统路径，必须走 InputStream
        try (var in = new ClassPathResource("templates/goods-import-template.xlsx").getInputStream()) {
            String fileName = URLEncoder.encode("商品档案_导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            response.setHeader("Content-Disposition", "attachment;filename=" + fileName);
            in.transferTo(response.getOutputStream());
        }
    }

    // ============================ 导入新增 ============================

    @PostMapping("/import")
    @RequirePerm(value = "base.goods.import", name = "导入")
    public ApiResponse<Map<String, Object>> importGoods(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = asRows(body.get("rows"));
        String fileName = str(body.getOrDefault("fileName", "商品导入.xlsx"));

        int inserted = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        Set<String> batchCodes = new HashSet<>();

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2; // Excel 物理行号（含表头）
            Map<String, Object> r = rows.get(i);
            try {
                Map<String, Object> req = buildNewGoodsRequest(r, batchCodes);
                BaseGoods entity = new BaseGoods();
                entity.setGoodsId("G" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
                fillEntity(entity, req);
                entity.setCurrentStock(BigDecimal.ZERO);
                goodsService.save(entity);
                inserted++;
            } catch (IllegalArgumentException e) {
                failures.add(failure(rowNo, e.getMessage()));
            } catch (Exception e) {
                failures.add(failure(rowNo, "保存失败：" + safeMsg(e)));
            }
        }
        String taskNo = recordTask("商品导入新增", fileName, inserted, failures, ADD_FIELDS, rows);
        opLog.log("base.goods", com.erp.system.OperationAction.IMPORT, taskNo,
                "商品导入新增（文件 " + fileName + "）：成功 " + inserted + " 行，失败 " + failures.size() + " 行");

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

    /** 把一行 Excel（驼峰键）解析为可直接喂给 fillGoodsEntity 的请求 Map；任何问题抛 IllegalArgumentException（即该行失败原因）。 */
    private Map<String, Object> buildNewGoodsRequest(Map<String, Object> r, Set<String> batchCodes) {
        String goodsName = required(r, "goodsName", "商品名称");
        String spec = required(r, "spec", "规格");
        // 分类：编号优先，值也可能是分类名称（同名唯一兼容）
        BaseCategory category = support.resolveCategory(str(r.get("categoryCode")));

        String baseUnit = required(r, "baseUnit", "基本单位");
        if (!support.unitExists(baseUnit)) throw new IllegalArgumentException("基本单位「" + baseUnit + "」不存在，须先在单位资料中维护");
        String supplier = required(r, "defaultSupplier", "默认供应商");
        if (!support.supplierExists(supplier)) throw new IllegalArgumentException("默认供应商「" + supplier + "」不存在，须先在供应商资料中维护");
        String warehouse = required(r, "defaultWarehouse", "默认仓库");
        if (!support.warehouseExists(warehouse)) throw new IllegalArgumentException("默认仓库「" + warehouse + "」不存在，须先在仓库资料中维护");
        String manager = str(r.get("goodsManager"));
        if (!manager.isEmpty() && !support.employeeExists(manager)) throw new IllegalArgumentException("商品负责人「" + manager + "」不存在，须先在人员信息中维护");
        String brandName = support.ensureBrandByName(str(r.get("brandName")));

        String goodsType = GoodsBizPolicy.normalizeType(r.get("goodsType"));
        String taxRate = GoodsBizPolicy.normalizeTaxRate(r.get("taxRate"));
        boolean priceLinked = boolOrDefault(r.get("priceLinked"), true);
        boolean canReturn = r.get("canReturn") == null || str(r.get("canReturn")).isEmpty()
                ? (GoodsBizPolicy.TYPE_NORMAL.equals(goodsType) || GoodsBizPolicy.TYPE_EXCHANGE.equals(goodsType))
                : parseBoolStrict(r.get("canReturn"), "是否可退");

        // 商品编码：空则按大类自动生成；手工编码查重（库内 + 批内）
        String goodsCode = str(r.get("goodsCode"));
        if (goodsCode.isEmpty()) {
            goodsCode = support.nextCodeByCategory(category);
        } else if (batchCodes.contains(goodsCode)
                || goodsService.getOne(new QueryWrapper<BaseGoods>().eq("goods_code", goodsCode).last("LIMIT 1")) != null) {
            throw new IllegalArgumentException("商品编码「" + goodsCode + "」已存在");
        }
        batchCodes.add(goodsCode);
        String barcode = str(r.get("barcode"));
        if (barcode.isEmpty()) barcode = goodsCode;

        // 单位矩阵（index 0 小 / 1 中 / 2 大）
        List<Map<String, Object>> units = buildUnits(r, priceLinked);
        Map<String, Object> small = units.get(0);
        small.put("unitName", baseUnit);
        small.put("barcode", barcode);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("goodsCode", goodsCode);
        req.put("goodsName", goodsName);
        req.put("spec", spec);
        req.put("categoryName", category.getCategoryName());
        req.put("brandName", brandName);
        req.put("goodsType", goodsType);
        req.put("taxRate", taxRate);
        req.put("baseUnit", baseUnit);
        req.put("barcode", barcode);
        req.put("defaultSupplier", supplier);
        req.put("defaultWarehouse", warehouse);
        req.put("goodsManager", manager);
        req.put("storageProperty", defaultStr(r.get("storageProperty"), "常温"));
        req.put("goodsLevel", str(r.get("goodsLevel")));
        req.put("origin", str(r.get("origin")));
        req.put("remark", str(r.get("remark")));
        req.put("canReturn", canReturn);
        req.put("isFresh", parseBoolStrictOr(r.get("isFresh"), false, "是否生鲜"));
        req.put("isPresale", parseBoolStrictOr(r.get("isPresale"), false, "是否预售品"));
        req.put("isWeighted", parseBoolStrictOr(r.get("isWeighted"), false, "是否称重"));
        req.put("shelfLifeDays", intOrDefault(r.get("shelfLifeDays"), 0, "保质期(天)"));
        req.put("warningDays", intOrDefault(r.get("warningDays"), 0, "临期预警天数"));
        req.put("stockUpperLimit", decimalOr(r.get("stockUpperLimit"), BigDecimal.ZERO, "库存上限"));
        req.put("stockLowerLimit", decimalOr(r.get("stockLowerLimit"), BigDecimal.ZERO, "库存下限"));
        req.put("minOrderQty", decimalOr(r.get("minOrderQty"), BigDecimal.ZERO, "采购起订量"));
        req.put("standardPrice", small.get("standardPrice"));
        req.put("latestPurchasePrice", small.get("purchasePrice"));
        req.put("minSalePrice", small.get("minPrice"));
        req.put("suggestedRetailPrice", small.get("suggestRetailPrice"));
        req.put("baseWeight", small.get("weight"));
        req.put("baseVolume", small.get("volume"));
        req.put("units", units);
        return req;
    }

    // ============================ 导入修改 ============================

    @PostMapping("/import-update")
    @RequirePerm(value = "base.goods.import", name = "导入")
    public ApiResponse<Map<String, Object>> importUpdate(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = asRows(body.get("rows"));
        String fileName = str(body.getOrDefault("fileName", "商品导入修改.xlsx"));
        // 前端勾选的可修改字段白名单（商品编码为定位键恒包含）；未传时按全字段兼容
        Set<String> allowed = null;
        Object fieldsObj = body.get("fields");
        if (fieldsObj instanceof Collection<?> coll && !coll.isEmpty()) {
            allowed = new HashSet<>();
            for (Object o : coll) allowed.add(str(o));
            allowed.add("goodsCode");
        }

        int updated = 0;
        List<Map<String, Object>> failures = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 2;
            Map<String, Object> r = filterRowFields(rows.get(i), allowed);
            try {
                updateOneGoods(r);
                updated++;
            } catch (IllegalArgumentException e) {
                failures.add(failure(rowNo, e.getMessage()));
            } catch (Exception e) {
                failures.add(failure(rowNo, "更新失败：" + safeMsg(e)));
            }
        }
        String taskNo = recordTask("商品导入修改", fileName, updated, failures, UPDATE_FIELDS, rows);
        opLog.log("base.goods", com.erp.system.OperationAction.IMPORT, taskNo,
                "商品导入修改（文件 " + fileName + "）：更新 " + updated + " 行，失败 " + failures.size() + " 行");

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

    private void updateOneGoods(Map<String, Object> r) throws Exception {
        String goodsCode = required(r, "goodsCode", "商品编码");
        BaseGoods entity = goodsService.getOne(
                new QueryWrapper<BaseGoods>().eq("goods_code", goodsCode).last("LIMIT 1"));
        if (entity == null) throw new IllegalArgumentException("商品编码「" + goodsCode + "」不存在");
        if ("DELETED".equals(entity.getStatus())) throw new IllegalArgumentException("商品「" + goodsCode + "」已删除，不可修改");

        // 以实体当前值为底（fillGoodsEntity 全量覆盖语义），仅用非空导入值覆盖
        @SuppressWarnings("unchecked")
        Map<String, Object> req = mapper.convertValue(entity, Map.class);
        // 类型列本次未导入时，fillEntity 回退实体原值（保留组合商品/服务商品等旧中文值）
        if (!notBlank(r, "goodsType")) req.remove("goodsType");

        if (notBlank(r, "goodsName")) req.put("goodsName", required(r, "goodsName", "商品名称"));
        if (notBlank(r, "spec")) req.put("spec", str(r.get("spec")));
        if (notBlank(r, "categoryCode")) {
            BaseCategory category = support.resolveCategory(str(r.get("categoryCode")));
            req.put("categoryName", category.getCategoryName());
        }
        if (notBlank(r, "brandName")) req.put("brandName", support.ensureBrandByName(str(r.get("brandName"))));
        if (notBlank(r, "baseUnit")) {
            String baseUnit = str(r.get("baseUnit"));
            if (!support.unitExists(baseUnit)) throw new IllegalArgumentException("基本单位「" + baseUnit + "」不存在，须先维护");
            req.put("baseUnit", baseUnit);
        }
        if (notBlank(r, "defaultPurchaseUnit")) {
            String purchaseUnit = str(r.get("defaultPurchaseUnit"));
            if (!support.unitExists(purchaseUnit)) throw new IllegalArgumentException("默认采购单位「" + purchaseUnit + "」不存在，须先维护");
            req.put("defaultPurchaseUnit", purchaseUnit);
        }
        if (notBlank(r, "barcode")) req.put("barcode", str(r.get("barcode")));
        if (notBlank(r, "status")) {
            String status = str(r.get("status"));
            if ("正常".equals(status)) req.put("status", "NORMAL");
            else if ("停用".equals(status)) req.put("status", "STOPPED");
            else throw new IllegalArgumentException("状态取值无效：" + status + "（只允许 正常/停用）");
        }
        if (notBlank(r, "defaultSupplier")) {
            String supplier = str(r.get("defaultSupplier"));
            if (!support.supplierExists(supplier)) throw new IllegalArgumentException("默认供应商「" + supplier + "」不存在");
            req.put("defaultSupplier", supplier);
        }
        if (notBlank(r, "defaultWarehouse")) {
            String warehouse = str(r.get("defaultWarehouse"));
            if (!support.warehouseExists(warehouse)) throw new IllegalArgumentException("默认仓库「" + warehouse + "」不存在");
            req.put("defaultWarehouse", warehouse);
        }
        if (notBlank(r, "goodsManager")) {
            String manager = str(r.get("goodsManager"));
            if (!support.employeeExists(manager)) throw new IllegalArgumentException("商品负责人「" + manager + "」不存在");
            req.put("goodsManager", manager);
        }
        if (notBlank(r, "goodsType")) req.put("goodsType", GoodsBizPolicy.normalizeType(r.get("goodsType")));
        if (notBlank(r, "taxRate")) req.put("taxRate", GoodsBizPolicy.normalizeTaxRate(r.get("taxRate")));
        if (notBlank(r, "storageProperty")) req.put("storageProperty", str(r.get("storageProperty")));
        if (notBlank(r, "goodsLevel")) req.put("goodsLevel", str(r.get("goodsLevel")));
        if (notBlank(r, "origin")) req.put("origin", str(r.get("origin")));
        if (notBlank(r, "remark")) req.put("remark", str(r.get("remark")));
        if (notBlank(r, "canReturn")) req.put("canReturn", parseBoolStrict(r.get("canReturn"), "是否可退"));
        if (notBlank(r, "isFresh")) req.put("isFresh", parseBoolStrict(r.get("isFresh"), "是否生鲜"));
        if (notBlank(r, "isPresale")) req.put("isPresale", parseBoolStrict(r.get("isPresale"), "是否预售品"));
        if (notBlank(r, "isWeighted")) req.put("isWeighted", parseBoolStrict(r.get("isWeighted"), "是否称重"));
        if (notBlank(r, "shelfLifeDays")) req.put("shelfLifeDays", intStrict(r.get("shelfLifeDays"), "保质期(天)"));
        if (notBlank(r, "warningDays")) req.put("warningDays", intStrict(r.get("warningDays"), "临期预警天数"));
        if (notBlank(r, "stockUpperLimit")) req.put("stockUpperLimit", decimalStrict(r.get("stockUpperLimit"), "库存上限"));
        if (notBlank(r, "stockLowerLimit")) req.put("stockLowerLimit", decimalStrict(r.get("stockLowerLimit"), "库存下限"));
        if (notBlank(r, "minOrderQty")) req.put("minOrderQty", decimalStrict(r.get("minOrderQty"), "采购起订量"));

        // 单位矩阵：在现有 unit_config 上按非空字段覆盖
        List<Map<String, Object>> units = parseUnits(entity.getUnitConfig(), req);
        boolean priceLinked = parseBoolStrictOr(r.get("priceLinked"), true, "启用价格联动");
        applyUnitUpdates(units, r, priceLinked);
        req.put("units", units);

        fillEntity(entity, req);
        // fillEntity 不识别的顶层派生值（与前端 doSave 同口径）
        entity.setBaseUnit(str(req.get("baseUnit")).isEmpty() ? entity.getBaseUnit() : str(req.get("baseUnit")));
        entity.setBarcode(str(req.get("barcode")).isEmpty() ? entity.getBarcode() : str(req.get("barcode")));
        entity.setUnitConfig(mapper.writeValueAsString(units));
        entity.setBaseWeight(decimal(units.get(0).get("weight")));
        entity.setBaseVolume(decimal(units.get(0).get("volume")));
        if (notBlank(r, "suggestedRetailPrice")) entity.setSuggestedRetailPrice(decimalStrict(r.get("suggestedRetailPrice"), "建议零售价"));
        goodsService.updateById(entity);
    }

    // ============================ 单位矩阵构造 ============================

    private List<Map<String, Object>> emptyUnits() {
        List<Map<String, Object>> units = new ArrayList<>();
        units.add(unitRow("小单位", "", "", BigDecimal.ONE));
        units.add(unitRow("中单位", "", "", BigDecimal.valueOf(12)));
        units.add(unitRow("大单位", "", "", BigDecimal.valueOf(24)));
        return units;
    }

    private Map<String, Object> unitRow(String unitType, String name, String barcode, BigDecimal ratio) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("unitType", unitType);
        u.put("unitName", name);
        u.put("barcode", barcode);
        u.put("convertQty", ratio);
        u.put("standardPrice", BigDecimal.ZERO);
        u.put("purchasePrice", BigDecimal.ZERO);
        u.put("minPrice", BigDecimal.ZERO);
        u.put("suggestRetailPrice", BigDecimal.ZERO);
        u.put("weight", BigDecimal.ZERO);
        u.put("volume", BigDecimal.ZERO);
        u.put("minOrderQty", BigDecimal.ZERO);
        u.put("isSaleUnit", true);
        u.put("isPurchaseUnit", true);
        u.put("enabled", "小单位".equals(unitType));
        return u;
    }

    /**
     * 导入新增：按行数据构造三行单位矩阵。
     * 大单位填了换算数量必填；中单位必须先有大单位；价格联动时空价格=小单位价×换算；
     * 重量/体积可填任意启用单位，其余按换算自动补。
     */
    private List<Map<String, Object>> buildUnits(Map<String, Object> r, boolean priceLinked) {
        List<Map<String, Object>> units = emptyUnits();

        String largeName = str(r.get("largeUnit"));
        String middleName = str(r.get("middleUnit"));
        Map<String, Object> large = units.get(2);
        Map<String, Object> middle = units.get(1);

        if (!largeName.isEmpty()) {
            if (!support.unitExists(largeName)) throw new IllegalArgumentException("大单位「" + largeName + "」不存在，须先维护");
            large.put("unitName", largeName);
            large.put("enabled", true);
            BigDecimal ratio = positiveDecimal(r.get("largeConvertQty"), "大单位换算数量");
            large.put("convertQty", ratio);
            large.put("barcode", str(r.get("largeBarcode")));
        }
        if (!middleName.isEmpty()) {
            if (largeName.isEmpty()) throw new IllegalArgumentException("填写了中单位但未填写大单位，不可跨过大单位直接填写中单位");
            if (!support.unitExists(middleName)) throw new IllegalArgumentException("中单位「" + middleName + "」不存在，须先维护");
            middle.put("unitName", middleName);
            middle.put("enabled", true);
            BigDecimal ratio = positiveDecimal(r.get("middleConvertQty"), "中单位换算数量");
            middle.put("convertQty", ratio);
            middle.put("barcode", str(r.get("middleBarcode")));
        }

        Map<String, Object> small = units.get(0);
        small.put("standardPrice", decimalOr(r.get("standardPrice"), BigDecimal.ZERO, "标准售价"));
        small.put("purchasePrice", decimalOr(r.get("latestPurchasePrice"), BigDecimal.ZERO, "参考进价"));
        small.put("minPrice", decimalOr(r.get("minSalePrice"), BigDecimal.ZERO, "最低售价"));
        small.put("suggestRetailPrice", decimalOr(r.get("suggestedRetailPrice"), BigDecimal.ZERO, "建议零售价"));
        small.put("minOrderQty", decimalOr(r.get("minOrderQty"), BigDecimal.ZERO, "采购起订量"));

        // 大/中价格：显式值优先；启用价格联动且留空时按小单位价 × 换算数量
        fillLinkedPrice(large, r, "largeStandardPrice", "largePurchasePrice", "largeMinPrice", priceLinked, small);
        fillLinkedPrice(middle, r, "middleStandardPrice", "middlePurchasePrice", "middleMinPrice", priceLinked, small);

        // 重量/体积：任填一个启用单位即可反推小单位，其余空缺自动补齐
        fillWeightVolume(units, r);
        return units;
    }

    private void fillLinkedPrice(Map<String, Object> unit, Map<String, Object> r,
                                 String stdKey, String purKey, String minKey,
                                 boolean linked, Map<String, Object> small) {
        if (!Boolean.TRUE.equals(unit.get("enabled"))) return;
        BigDecimal ratio = decimal(unit.get("convertQty"));
        unit.put("standardPrice", pickPrice(r.get(stdKey), linked, decimal(small.get("standardPrice")), ratio, 2, stdKey));
        unit.put("purchasePrice", pickPrice(r.get(purKey), linked, decimal(small.get("purchasePrice")), ratio, 4, purKey));
        unit.put("minPrice", pickPrice(r.get(minKey), linked, decimal(small.get("minPrice")), ratio, 2, minKey));
        if (linked) {
            unit.put("suggestRetailPrice", decimal(small.get("suggestRetailPrice")).multiply(ratio).setScale(2, RoundingMode.HALF_UP));
        }
    }

    private BigDecimal pickPrice(Object raw, boolean linked, BigDecimal smallPrice, BigDecimal ratio, int scale, String label) {
        if (raw != null && !str(raw).isEmpty()) return decimalStrict(raw, label).setScale(scale, RoundingMode.HALF_UP);
        if (linked) return smallPrice.multiply(ratio).setScale(scale, RoundingMode.HALF_UP);
        return BigDecimal.ZERO;
    }

    private void fillWeightVolume(List<Map<String, Object>> units, Map<String, Object> r) {
        fillMeasure(units, r, "smallWeight", "largeWeight", "middleWeight", "weight", 3);
        fillMeasure(units, r, "smallVolume", "largeVolume", "middleVolume", "volume", 6);
    }

    /** 重量/体积：以小→大→中优先级取一个已知值反推小单位，再补各启用单位空缺；非空显式值保留。 */
    private void fillMeasure(List<Map<String, Object>> units, Map<String, Object> r,
                             String smallKey, String largeKey, String middleKey, String unitKey, int scale) {
        Map<String, Object> small = units.get(0), large = units.get(2), middle = units.get(1);
        BigDecimal smallVal = blank(r.get(smallKey)) ? null : nonNegativeDecimal(r.get(smallKey), labelOf(smallKey));
        BigDecimal largeVal = blank(r.get(largeKey)) ? null : nonNegativeDecimal(r.get(largeKey), labelOf(largeKey));
        BigDecimal midVal = blank(r.get(middleKey)) ? null : nonNegativeDecimal(r.get(middleKey), labelOf(middleKey));

        BigDecimal base;
        if (smallVal != null) base = smallVal;
        else if (largeVal != null) base = largeVal.divide(decimal(large.get("convertQty")), scale, RoundingMode.HALF_UP);
        else if (midVal != null) base = midVal.divide(decimal(middle.get("convertQty")), scale, RoundingMode.HALF_UP);
        else base = BigDecimal.ZERO;

        small.put(unitKey, (smallVal != null ? smallVal : base).setScale(scale, RoundingMode.HALF_UP));
        if (Boolean.TRUE.equals(large.get("enabled"))) {
            large.put(unitKey, largeVal != null ? largeVal.setScale(scale, RoundingMode.HALF_UP)
                    : base.multiply(decimal(large.get("convertQty")).setScale(scale, RoundingMode.HALF_UP)));
        }
        if (Boolean.TRUE.equals(middle.get("enabled"))) {
            middle.put(unitKey, midVal != null ? midVal.setScale(scale, RoundingMode.HALF_UP)
                    : base.multiply(decimal(middle.get("convertQty")).setScale(scale, RoundingMode.HALF_UP)));
        }
    }

    private String labelOf(String key) {
        for (String[] f : ADD_FIELDS) if (f[1].equals(key)) return f[0];
        return key;
    }

    /** 从实体现有 unit_config 反序列化三行矩阵；缺失时按实体顶层字段构造。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseUnits(String unitConfig, Map<String, Object> req) {
        if (unitConfig != null && !unitConfig.isBlank()) {
            try {
                List<Map<String, Object>> parsed = mapper.readValue(unitConfig, List.class);
                if (parsed.size() >= 3) {
                    // 补齐默认键，避免历史 JSON 缺字段
                    List<Map<String, Object>> def = emptyUnits();
                    for (int i = 0; i < 3; i++) {
                        Map<String, Object> row = parsed.get(i);
                        for (String k : def.get(0).keySet()) row.putIfAbsent(k, def.get(i).get(k));
                    }
                    return parsed;
                }
            } catch (Exception ignore) {
                // 落到默认构造
            }
        }
        List<Map<String, Object>> units = emptyUnits();
        Map<String, Object> small = units.get(0);
        small.put("unitName", str(req.get("baseUnit")));
        small.put("barcode", str(req.get("barcode")));
        small.put("standardPrice", decimal(req.get("standardPrice")));
        small.put("purchasePrice", decimal(req.get("latestPurchasePrice")));
        small.put("minPrice", decimal(req.get("minSalePrice")));
        small.put("suggestRetailPrice", decimal(req.get("suggestedRetailPrice")));
        small.put("weight", decimal(req.get("baseWeight")));
        small.put("volume", decimal(req.get("baseVolume")));
        return units;
    }

    /** 导入修改：把非空的单位字段覆盖到现有矩阵，并复跑重量体积联动与校验。 */
    private void applyUnitUpdates(List<Map<String, Object>> units, Map<String, Object> r, boolean priceLinked) {
        Map<String, Object> large = units.get(2), middle = units.get(1), small = units.get(0);

        if (notBlank(r, "largeUnit")) {
            String name = str(r.get("largeUnit"));
            if (!support.unitExists(name)) throw new IllegalArgumentException("大单位「" + name + "」不存在，须先维护");
            boolean wasEnabled = Boolean.TRUE.equals(large.get("enabled"));
            large.put("unitName", name);
            large.put("enabled", true);
            if (!wasEnabled && blank(r.get("largeConvertQty"))) {
                throw new IllegalArgumentException("填写了大单位，大单位换算数量必填");
            }
        }
        if (notBlank(r, "middleUnit")) {
            String name = str(r.get("middleUnit"));
            if (!Boolean.TRUE.equals(large.get("enabled"))) {
                throw new IllegalArgumentException("填写了中单位但大单位未启用，不可跨过大单位直接填写中单位");
            }
            if (!support.unitExists(name)) throw new IllegalArgumentException("中单位「" + name + "」不存在，须先维护");
            boolean wasEnabled = Boolean.TRUE.equals(middle.get("enabled"));
            middle.put("unitName", name);
            middle.put("enabled", true);
            if (!wasEnabled && blank(r.get("middleConvertQty"))) {
                throw new IllegalArgumentException("填写了中单位，中单位换算数量必填");
            }
        }
        if (notBlank(r, "largeConvertQty")) large.put("convertQty", positiveDecimal(r.get("largeConvertQty"), "大单位换算数量"));
        if (notBlank(r, "middleConvertQty")) middle.put("convertQty", positiveDecimal(r.get("middleConvertQty"), "中单位换算数量"));
        if (notBlank(r, "largeBarcode")) large.put("barcode", str(r.get("largeBarcode")));
        if (notBlank(r, "middleBarcode")) middle.put("barcode", str(r.get("middleBarcode")));
        if (Boolean.TRUE.equals(middle.get("enabled")) && !Boolean.TRUE.equals(large.get("enabled"))) {
            throw new IllegalArgumentException("中单位必须在大单位启用后才能使用，不可跨过大单位");
        }
        if (notBlank(r, "baseUnit")) small.put("unitName", str(r.get("baseUnit")));
        if (notBlank(r, "barcode")) small.put("barcode", str(r.get("barcode")));
        // 建议零售价（价格联动时向启用单位传播）
        if (notBlank(r, "suggestedRetailPrice") && priceLinked) {
            BigDecimal price = decimalStrict(r.get("suggestedRetailPrice"), "建议零售价");
            small.put("suggestRetailPrice", price);
            for (int idx : new int[]{1, 2}) {
                Map<String, Object> u = units.get(idx);
                if (Boolean.TRUE.equals(u.get("enabled"))) {
                    u.put("suggestRetailPrice", price.multiply(decimal(u.get("convertQty"))).setScale(2, RoundingMode.HALF_UP));
                }
            }
        }
        // 六个重量/体积列：非空覆盖到对应单位，再统一联动补空缺
        applyMeasureOverrides(units, r);
    }

    private void applyMeasureOverrides(List<Map<String, Object>> units, Map<String, Object> r) {
        String[][] triples = {
                {"smallWeight", "largeWeight", "middleWeight", "weight", "3"},
                {"smallVolume", "largeVolume", "middleVolume", "volume", "6"},
        };
        for (String[] t : triples) {
            int scale = Integer.parseInt(t[4]);
            Map<String, Object> small = units.get(0), large = units.get(2), middle = units.get(1);
            if (notBlank(r, t[0])) small.put(t[3], nonNegativeDecimal(r.get(t[0]), labelOf(t[0])).setScale(scale, RoundingMode.HALF_UP));
            if (notBlank(r, t[1]) && Boolean.TRUE.equals(large.get("enabled")))
                large.put(t[3], nonNegativeDecimal(r.get(t[1]), labelOf(t[1])).setScale(scale, RoundingMode.HALF_UP));
            if (notBlank(r, t[2]) && Boolean.TRUE.equals(middle.get("enabled")))
                middle.put(t[3], nonNegativeDecimal(r.get(t[2]), labelOf(t[2])).setScale(scale, RoundingMode.HALF_UP));
            // 以小单位为基准补齐仍为 0/空 的启用单位
            BigDecimal base = decimal(small.get(t[3]));
            for (int idx : new int[]{2, 1}) {
                Map<String, Object> u = units.get(idx);
                if (Boolean.TRUE.equals(u.get("enabled")) && decimal(u.get(t[3])).signum() == 0 && base.signum() > 0) {
                    u.put(t[3], base.multiply(decimal(u.get("convertQty"))).setScale(scale, RoundingMode.HALF_UP));
                }
            }
        }
    }

    // ============================ 任务留痕与失败文件 ============================

    private String recordTask(String taskName, String fileName, int success,
                              List<Map<String, Object>> failures, String[][] fields,
                              List<Map<String, Object>> rawRows) {
        String taskNo = "IMP" + System.currentTimeMillis()
                + String.format("%03d", new Random().nextInt(1000));
        String failureFile = null;
        if (!failures.isEmpty()) {
            try {
                if (!FAILURE_DIR.exists() && !FAILURE_DIR.mkdirs()) {
                    throw new IllegalStateException("无法创建失败文件目录：" + FAILURE_DIR.getAbsolutePath());
                }
                File out = new File(FAILURE_DIR, taskNo + ".xlsx");
                List<List<String>> head = new ArrayList<>();
                head.add(Collections.singletonList("Excel行号"));
                for (String[] f : fields) head.add(Collections.singletonList(f[0]));
                head.add(Collections.singletonList("失败原因"));
                List<List<Object>> data = new ArrayList<>();
                for (Map<String, Object> fail : failures) {
                    int rowNo = ((Number) fail.get("rowNo")).intValue();
                    Map<String, Object> raw = rawRows.get(rowNo - 2);
                    List<Object> line = new ArrayList<>();
                    line.add(rowNo);
                    for (String[] f : fields) line.add(raw == null ? "" : str(raw.get(f[1])));
                    line.add(fail.get("reason"));
                    data.add(line);
                }
                EasyExcel.write(out).sheet("失败明细").head(head).doWrite(data);
                failureFile = "import-failures/" + taskNo + ".xlsx";
            } catch (Exception e) {
                // 失败文件落盘失败不阻断导入结果，任务仍写库（仅无文件可下）
                failureFile = null;
            }
        }
        jdbcTemplate.update(
                "INSERT INTO sys_import_task_runtime "
                        + "(task_id, task_no, module_code, task_name, file_name, success_rows, failed_rows, status, result_text, failure_file, created_at, finished_at) "
                        + "VALUES (?, ?, 'goods', ?, ?, ?, ?, 'FINISHED', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "IMP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                taskNo, taskName, fileName, success, failures.size(),
                failures.isEmpty() ? "导入完成，成功 " + success + " 条"
                        : "成功 " + success + " 条，失败 " + failures.size() + " 条（可下载失败文件）",
                failureFile);
        return taskNo;
    }

    // BaseController.fillGoodsEntity 为私有，这里保持同一套落库口径
    private void fillEntity(BaseGoods entity, Map<String, Object> req) {
        entity.setGoodsCode(str(req.getOrDefault("goodsCode", entity.getGoodsId())));
        entity.setGoodsName(str(req.getOrDefault("goodsName", "新商品")));
        Object gtRaw = req.get("goodsType");
        String type;
        if (gtRaw == null && entity.getGoodsType() != null) type = entity.getGoodsType();
        else type = GoodsBizPolicy.normalizeType(gtRaw);
        entity.setGoodsType(type);
        entity.setCanSale(GoodsBizPolicy.canSale(type));
        entity.setCanPurchase(GoodsBizPolicy.canPurchase(type));
        entity.setSpec(str(req.get("spec")));
        entity.setCategoryName(str(req.get("categoryName")));
        entity.setBrandName(str(req.get("brandName")));
        entity.setBaseUnit(str(req.get("baseUnit")));
        entity.setDefaultPurchaseUnit(str(req.get("defaultPurchaseUnit")));
        entity.setBarcode(str(req.get("barcode")));
        entity.setStandardPrice(decimal(req.get("standardPrice")));
        entity.setLatestPurchasePrice(decimal(req.get("latestPurchasePrice")));
        entity.setMinSalePrice(decimal(req.get("minSalePrice")));
        entity.setSuggestedRetailPrice(decimal(req.get("suggestedRetailPrice")));
        entity.setStockUpperLimit(decimal(req.get("stockUpperLimit")));
        entity.setStockLowerLimit(decimal(req.get("stockLowerLimit")));
        Integer shelf = intOrNull(req.get("shelfLifeDays"));
        entity.setShelfLifeDays(shelf == null ? 0 : shelf);
        entity.setStorageProperty(defaultStr(req.get("storageProperty"), "常温"));
        entity.setDefaultSupplier(str(req.get("defaultSupplier")));
        entity.setDefaultWarehouse(str(req.get("defaultWarehouse")));
        entity.setCanReturn(bool(req.get("canReturn"), true));
        entity.setSimpleCode(str(req.get("simpleCode")));
        entity.setGoodsLevel(str(req.get("goodsLevel")));
        if (req.containsKey("taxRate") || entity.getTaxRate() == null) {
            entity.setTaxRate(GoodsBizPolicy.normalizeTaxRate(req.get("taxRate")));
        }
        entity.setGoodsManager(str(req.get("goodsManager")));
        entity.setIsWeighted(bool(req.get("isWeighted"), false));
        entity.setIsPresale(bool(req.get("isPresale"), false));
        entity.setIsFresh(bool(req.get("isFresh"), false));
        entity.setOrigin(str(req.get("origin")));
        Integer warning = intOrNull(req.get("warningDays"));
        entity.setWarningDays(warning == null ? 0 : warning);
        entity.setMinOrderQty(decimal(req.get("minOrderQty")));
        entity.setBaseWeight(decimal(req.get("baseWeight")));
        entity.setBaseVolume(decimal(req.get("baseVolume")));
        entity.setRemark(str(req.get("remark")));
        Object units = req.get("units");
        if (units != null) {
            try {
                entity.setUnitConfig(mapper.writeValueAsString(units));
            } catch (Exception ignore) {
                // 与 BaseController 同口径：序列化失败保留原值
            }
        }
        String status = str(req.getOrDefault("status", "NORMAL"));
        if ("正常".equals(status)) status = "NORMAL";
        else if ("停用".equals(status)) status = "STOPPED";
        entity.setStatus(status);
    }

    // ============================ 小工具 ============================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asRows(Object obj) {
        if (!(obj instanceof List<?> list)) throw new IllegalArgumentException("缺少 rows 数据");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    /** 按导入修改勾选的字段白名单裁剪一行；allowed 为 null 时原样返回（兼容直连调用）。 */
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

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String defaultStr(Object v, String dft) {
        String s = str(v);
        return s.isEmpty() ? dft : s;
    }

    private static boolean blank(Object v) {
        return str(v).isEmpty();
    }

    private static boolean notBlank(Map<String, Object> r, String key) {
        return !blank(r.get(key));
    }

    private String required(Map<String, Object> r, String key, String label) {
        String v = str(r.get(key));
        if (v.isEmpty()) throw new IllegalArgumentException(label + "必填");
        return v;
    }

    private boolean parseBoolStrict(Object v, String label) {
        String s = str(v).toLowerCase();
        if ("是".equals(s) || "true".equals(s) || "1".equals(s) || "y".equals(s)) return true;
        if ("否".equals(s) || "false".equals(s) || "0".equals(s) || "n".equals(s)) return false;
        throw new IllegalArgumentException(label + "取值无效：" + v + "（只允许 是/否）");
    }

    private boolean parseBoolStrictOr(Object v, boolean dft, String label) {
        if (blank(v)) return dft;
        return parseBoolStrict(v, label);
    }

    private boolean boolOrDefault(Object v, boolean dft) {
        if (blank(v)) return dft;
        String s = str(v).toLowerCase();
        if ("是".equals(s) || "true".equals(s) || "1".equals(s)) return true;
        if ("否".equals(s) || "false".equals(s) || "0".equals(s)) return false;
        return dft;
    }

    private boolean bool(Object v, boolean dft) {
        if (v instanceof Boolean b) return b;
        if (v == null) return dft;
        String s = str(v).toLowerCase();
        if ("是".equals(s) || "true".equals(s) || "1".equals(s)) return true;
        if ("否".equals(s) || "false".equals(s) || "0".equals(s)) return false;
        return dft;
    }

    private BigDecimal decimal(Object v) {
        if (v == null || str(v).isEmpty()) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(str(v).replace(",", ""));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal decimalStrict(Object v, String label) {
        if (blank(v)) return BigDecimal.ZERO;
        try {
            BigDecimal d = new BigDecimal(str(v).replace(",", ""));
            if (d.signum() < 0) throw new IllegalArgumentException(label + "不能为负数");
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "不是合法数字：" + v);
        }
    }

    private BigDecimal decimalOr(Object v, BigDecimal dft, String label) {
        return blank(v) ? dft : decimalStrict(v, label);
    }

    private BigDecimal nonNegativeDecimal(Object v, String label) {
        return decimalStrict(v, label);
    }

    private BigDecimal positiveDecimal(Object v, String label) {
        BigDecimal d = decimalStrict(v, label);
        if (d.signum() <= 0) throw new IllegalArgumentException(label + "必须大于 0");
        return d;
    }

    private Integer intOrNull(Object v) {
        if (v == null || str(v).isEmpty()) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return new BigDecimal(str(v).replace(",", "")).intValue();
        } catch (Exception e) {
            return null;
        }
    }

    private int intStrict(Object v, String label) {
        if (blank(v)) throw new IllegalArgumentException(label + "不能为空");
        try {
            int n = new BigDecimal(str(v).replace(",", "")).intValue();
            if (n < 0) throw new IllegalArgumentException(label + "不能为负数");
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "不是合法整数：" + v);
        }
    }

    private int intOrDefault(Object v, int dft, String label) {
        if (blank(v)) return dft;
        return intStrict(v, label);
    }

    private String safeMsg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
