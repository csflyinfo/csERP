package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import com.erp.common.security.RequirePerm;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 总账——账簿查询。路由前缀 /finance/gl/ledger。
 * 总账/余额表/明细账/序时账均实时聚合（期初 + 已过账凭证），不物化余额。
 */
@RestController
@RequestMapping("/finance/gl/ledger")
public class GlLedgerController {

    private final GlLedgerService ledgerService;

    public GlLedgerController(GlLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** 总账（按年逐月）。body: {year, keyword?, accountCode?} */
    @RequirePerm(value = "finance.gl.book.view", name = "查看")
    @PostMapping("/general")
    public ApiResponse<List<Map<String, Object>>> general(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(ledgerService.generalLedger(body));
    }

    /** 发生额及余额表。body: {periodFrom, periodTo} */
    @RequirePerm(value = "finance.gl.book.view", name = "查看")
    @PostMapping("/balance-table")
    public ApiResponse<List<Map<String, Object>>> balanceTable(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(ledgerService.balanceTable(body));
    }

    /** 明细账。body: {accountCode, dateFrom, dateTo, 辅助核算过滤...} */
    @RequirePerm(value = "finance.gl.book.view", name = "查看")
    @PostMapping("/subsidiary")
    public ApiResponse<Map<String, Object>> subsidiary(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(ledgerService.subsidiaryLedger(body));
    }

    /** 序时账。body: {dateFrom, dateTo, status?, keyword?} */
    @RequirePerm(value = "finance.gl.book.view", name = "查看")
    @PostMapping("/journal")
    public ApiResponse<List<Map<String, Object>>> journal(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(ledgerService.journal(body));
    }

    /** 多栏账（上级科目末级子科目摊列）。body: {accountCode, periodFrom, periodTo} */
    @RequirePerm(value = "finance.gl.book.view", name = "查看")
    @PostMapping("/multi-column")
    public ApiResponse<Map<String, Object>> multiColumn(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(ledgerService.multiColumnLedger(body));
    }

    /** 辅助核算余额表。body: {dimension, periodFrom, periodTo, accountCode?} */
    @RequirePerm(value = "finance.gl.book.view", name = "查看")
    @PostMapping("/aux-balance")
    public ApiResponse<Map<String, Object>> auxBalance(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(ledgerService.auxBalance(body));
    }

    /** 辅助核算明细账。body: {dimension, auxCode, dateFrom, dateTo, accountCode?} */
    @RequirePerm(value = "finance.gl.book.view", name = "查看")
    @PostMapping("/aux-subsidiary")
    public ApiResponse<Map<String, Object>> auxSubsidiary(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(ledgerService.auxSubsidiary(body));
    }
}
