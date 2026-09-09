package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import com.erp.tms.TmsUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import com.erp.common.security.RequirePerm;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 固定资产接口（总账 M7）。
 */
@RestController
@RequestMapping("/finance/gl/asset")
public class GlAssetController {

    private final GlAssetService assetService;

    public GlAssetController(GlAssetService assetService) {
        this.assetService = assetService;
    }

    /** 资产类别（含默认折旧方法/年限/残值率/费用科目）。无 body 也可调。 */
    @RequirePerm(value = "finance.gl.asset_card.view", name = "查看")
    @PostMapping("/categories")
    public ApiResponse<?> categories() {
        return ApiResponse.ok(assetService.categories());
    }

    /** 资产台账列表。body: {status?} */
    @RequirePerm(value = "finance.gl.asset_card.view", name = "查看")
    @PostMapping("/cards")
    public ApiResponse<?> cards(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(assetService.cards(body == null ? "" : TmsUtil.str(body.get("status"))));
    }

    /** 建卡。body: 见 GlAssetService.createCard。 */
    @RequirePerm(value = "finance.gl.asset_card.add", name = "建卡")
    @PostMapping("/card-save")
    public ApiResponse<?> save(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(assetService.createCard(body));
    }

    /** 停用/启用。body: {cardId, status} */
    @RequirePerm(value = "finance.gl.asset_card.edit", name = "停用/启用")
    @PostMapping("/card-toggle")
    public ApiResponse<?> toggle(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(assetService.toggleStatus(TmsUtil.str(body.get("cardId")), TmsUtil.str(body.get("status"))));
    }

    /** 月度折旧预览。body: {period?, workloads? {cardNo: 工作量}} */
    @RequirePerm(value = "finance.gl.depreciation.view", name = "查看")
    @PostMapping("/dep-preview")
    public ApiResponse<?> depPreview(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? new LinkedHashMap<>() : body;
        return ApiResponse.ok(assetService.depPreview(TmsUtil.str(b.get("period")), readWorkloads(b.get("workloads"))));
    }

    /** 执行折旧（生成明细+卡片更新+转字凭证草稿）。body: {period?, workloads?} */
    @RequirePerm(value = "finance.gl.depreciation.audit", name = "执行折旧")
    @PostMapping("/dep-execute")
    public ApiResponse<?> depExecute(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(assetService.depExecute(body));
    }

    /** 期末向导第 ③ 步状态。body: {period?} */
    @RequirePerm(value = "finance.gl.depreciation.view", name = "查看")
    @PostMapping("/wizard-status")
    public ApiResponse<?> wizardStatus(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(assetService.wizardStatus(body == null ? "" : TmsUtil.str(body.get("period"))));
    }

    // ==================== M8：变更 / 拆分 / 合并 / 清理 / 盘点 / 台账 ====================

    /** 信息变更（原值禁改）。body: {cardId, departmentCode?, expenseAccount?, depreciationMethod?, lifeMonths?/lifeYears?, totalWorkload?, workloadUnit?, reason?} */
    @RequirePerm(value = "finance.gl.asset_card.edit", name = "变更")
    @PostMapping("/change")
    public ApiResponse<?> change(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(assetService.change(body));
    }

    /** 拆分。body: {cardId, parts:[{assetName, ratio}]}（比例和=1，末张吃尾差，不生凭证） */
    @RequirePerm(value = "finance.gl.asset_card.edit", name = "拆分")
    @PostMapping("/split")
    public ApiResponse<?> split(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(assetService.split(body));
    }

    /** 合并。body: {cardIds:[], assetName}（同类/同部门/同方法/同费用科目，不生凭证） */
    @RequirePerm(value = "finance.gl.asset_card.edit", name = "合并")
    @PostMapping("/merge")
    public ApiResponse<?> merge(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(assetService.merge(body));
    }

    /** 清理预览。body: {cardId, incomeAmount?, expenseAmount?, cashAccount?} */
    @RequirePerm(value = "finance.gl.asset_card.view", name = "查看")
    @PostMapping("/disposal-preview")
    public ApiResponse<?> disposalPreview(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(assetService.disposalPreview(body));
    }

    /** 清理执行（QL 转字凭证草稿 + 卡片置已清理）。 */
    @RequirePerm(value = "finance.gl.asset_card.audit", name = "清理执行")
    @PostMapping("/disposal-execute")
    public ApiResponse<?> disposalExecute(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(assetService.disposalExecute(body));
    }

    /** 盘点单保存（盘亏/盘盈只留痕）。body: {period?, departmentCode?, rows:[{cardId, checkResult, remark}]} */
    @RequirePerm(value = "finance.gl.asset_check.edit", name = "保存盘点单")
    @PostMapping("/check-save")
    public ApiResponse<?> checkSave(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(assetService.checkSave(body));
    }

    /** 盘点单列表。 */
    @RequirePerm(value = "finance.gl.asset_check.view", name = "查看")
    @PostMapping("/check-list")
    public ApiResponse<?> checkList() {
        return ApiResponse.ok(assetService.checkList());
    }

    /** 盘点单明细。body: {checkId} */
    @RequirePerm(value = "finance.gl.asset_check.view", name = "查看")
    @PostMapping("/check-detail")
    public ApiResponse<?> checkDetail(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(assetService.checkDetail(TmsUtil.str(body.get("checkId"))));
    }

    /** 资产台账滚动表（期末=期初+增-减 + GL 对照）。body: {period?} */
    @RequirePerm(value = "finance.gl.asset_card.view", name = "查看")
    @PostMapping("/ledger")
    public ApiResponse<?> ledger(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(assetService.ledger(body == null ? "" : TmsUtil.str(body.get("period"))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> readWorkloads(Object o) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> e : raw.entrySet())
                map.put(String.valueOf(e.getKey()), TmsUtil.toBd(e.getValue()));
        }
        return map;
    }
}
