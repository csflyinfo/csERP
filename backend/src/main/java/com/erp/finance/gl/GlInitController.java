package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import com.erp.tms.TmsUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import com.erp.common.security.RequirePerm;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 总账——初始化：会计期间、期初余额、试算平衡、业务期初引入、启用总账。
 * 路由前缀 /finance/gl/init。
 */
@RestController
@RequestMapping("/finance/gl/init")
public class GlInitController {

    private final GlInitService initService;

    public GlInitController(GlInitService initService) {
        this.initService = initService;
    }

    /** 初始化状态（是否已启用、启用期间、当前期间）。 */
    @RequirePerm(value = "finance.gl.init_balance.view", name = "查看")
    @PostMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(initService.status());
    }

    /** 会计期间列表。 */
    @RequirePerm(value = "finance.gl.init_balance.view", name = "查看")
    @PostMapping("/period-list")
    public ApiResponse<List<Map<String, Object>>> periodList() {
        return ApiResponse.ok(initService.periodList());
    }

    /** 生成指定年度期间（启用前可提前生成预览）。body: {year} */
    @RequirePerm(value = "finance.gl.init_balance.edit", name = "生成期间")
    @PostMapping("/period-generate")
    public ApiResponse<Void> periodGenerate(@RequestBody Map<String, Object> req) {
        int year = TmsUtil.toInt(req.get("year"));
        if (year < 2000 || year > 2100) throw new IllegalArgumentException("年度不合法：" + year);
        initService.generatePeriods(year);
        return ApiResponse.ok(null);
    }

    /** 期初余额主科目列表（末级科目全量，零值也出场）。 */
    @RequirePerm(value = "finance.gl.init_balance.view", name = "查看")
    @PostMapping("/balance-list")
    public ApiResponse<List<Map<String, Object>>> balanceList() {
        return ApiResponse.ok(initService.mainBalanceList());
    }

    /** 某科目的辅助核算明细期初。body: {accountCode} */
    @RequirePerm(value = "finance.gl.init_balance.view", name = "查看")
    @PostMapping("/aux-balance-list")
    public ApiResponse<List<Map<String, Object>>> auxBalanceList(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(initService.auxBalanceList(TmsUtil.str(req.get("accountCode"))));
    }

    /** 批量保存期初余额。body: {rows: [...]} */
    @SuppressWarnings("unchecked")
    @RequirePerm(value = "finance.gl.init_balance.edit", name = "保存期初")
    @PostMapping("/balance-save")
    public ApiResponse<Void> balanceSave(@RequestBody Map<String, Object> req) {
        Object rows = req.get("rows");
        if (!(rows instanceof List)) throw new IllegalArgumentException("rows 必须是数组");
        initService.saveBalances((List<Map<String, Object>>) rows);
        return ApiResponse.ok(null);
    }

    /** 试算平衡。 */
    @RequirePerm(value = "finance.gl.init_balance.view", name = "查看")
    @PostMapping("/trial-balance")
    public ApiResponse<Map<String, Object>> trialBalance() {
        return ApiResponse.ok(initService.trialBalance());
    }

    /** 一键引入业务期初（应收/应付/库存）。 */
    @RequirePerm(value = "finance.gl.init_balance.import", name = "引入业务期初")
    @PostMapping("/business-import")
    public ApiResponse<Map<String, Object>> businessImport() {
        return ApiResponse.ok(initService.importBusiness());
    }

    /** 启用总账。body: {startPeriod: 'yyyyMM'} */
    @RequirePerm(value = "finance.gl.init_balance.edit", name = "启用总账")
    @PostMapping("/enable")
    public ApiResponse<Void> enable(@RequestBody Map<String, Object> req) {
        initService.enable(TmsUtil.str(req.get("startPeriod")));
        return ApiResponse.ok(null);
    }
}
