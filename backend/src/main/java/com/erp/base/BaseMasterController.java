package com.erp.base;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.GenericResult;
import com.erp.common.api.PageRequest;
import com.erp.common.api.PageResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/base/master")
public class BaseMasterController {
    @PostMapping("/price-group/page")
    public ApiResponse<PageResult<Map<String, Object>>> priceGroupPage(@RequestBody PageRequest request) {
        return page(request, row("PRICE1", "批发价", "销售价格组", "默认客户等级价", "正常"));
    }

    @PostMapping("/customer/page")
    public ApiResponse<PageResult<Map<String, Object>>> customerPage(@RequestBody PageRequest request) {
        return page(request, row("C001", "华联超市", "KA客户", "月结30天", "正常"));
    }

    @PostMapping("/supplier/page")
    public ApiResponse<PageResult<Map<String, Object>>> supplierPage(@RequestBody PageRequest request) {
        return page(request, row("G001", "农夫山泉杭州经销", "饮料供应商", "到货5天", "正常"));
    }

    @PostMapping("/counterparty/page")
    public ApiResponse<PageResult<Map<String, Object>>> counterpartyPage(@RequestBody PageRequest request) {
        return page(request, row("WL001", "顺丰物流", "物流公司", "月结", "正常"));
    }

    @PostMapping("/fund-account/page")
    public ApiResponse<PageResult<Map<String, Object>>> fundAccountPage(@RequestBody PageRequest request) {
        return page(request, row("A001", "工行基本户", "银行账户", "50300.00", "正常"));
    }

    @PostMapping("/expense-type/page")
    public ApiResponse<PageResult<Map<String, Object>>> expenseTypePage(@RequestBody PageRequest request) {
        return page(request, row("FY001", "运费", "支出", "参与采购成本", "正常"));
    }

    @PostMapping("/territory/page")
    public ApiResponse<PageResult<Map<String, Object>>> territoryPage(@RequestBody PageRequest request) {
        return page(request, row("T001", "西湖区", "杭州", "客户230", "正常"));
    }

    @PostMapping("/route-line/page")
    public ApiResponse<PageResult<Map<String, Object>>> routeLinePage(@RequestBody PageRequest request) {
        return page(request, row("R001", "朝阳线", "王司机", "覆盖86客户", "正常"));
    }

    @PostMapping("/employee/page")
    public ApiResponse<PageResult<Map<String, Object>>> employeePage(@RequestBody PageRequest request) {
        return page(request, row("E001", "张三", "销售部", "业务员", "在职"));
    }

    @PostMapping("/department/page")
    public ApiResponse<PageResult<Map<String, Object>>> departmentPage(@RequestBody PageRequest request) {
        return page(request, row("D001", "销售部", "公司", "12人", "正常"));
    }

    @PostMapping("/owner/page")
    public ApiResponse<PageResult<Map<String, Object>>> ownerPage(@RequestBody PageRequest request) {
        return page(request, row("H001", "平台默认货主", "默认货主", "平台", "正常"));
    }

    @PostMapping("/save")
    public ApiResponse<Map<String, Object>> save(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(GenericResult.operation(String.valueOf(request.getOrDefault("moduleCode", "base.master")), "SAVE"));
    }

    @PostMapping("/stop")
    public ApiResponse<Map<String, Object>> stop(@RequestBody Map<String, Object> request) {
        return ApiResponse.ok(GenericResult.operation(String.valueOf(request.getOrDefault("moduleCode", "base.master")), "STOP"));
    }

    private ApiResponse<PageResult<Map<String, Object>>> page(PageRequest request, Map<String, Object> row) {
        return ApiResponse.ok(PageResult.of(List.of(row), request));
    }

    private Map<String, Object> row(String code, String name, String type, String remark, String status) {
        return GenericResult.row("code", code, "name", name, "type", type, "remark", remark, "status", status);
    }
}
