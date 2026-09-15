package com.erp.inventory.init;

import com.erp.common.api.ApiResponse;
import com.erp.common.security.RequirePerm;
import com.erp.tms.TmsUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存期初初始化（PRD-34）。路由前缀 /init/stock。
 *
 * <p>两个流程：按批次（未启用 WMS）/ 按库位（启用 WMS，过账同时写 wms_bin_stock）。
 * 同一商品+仓库+批次不能混用两种流程。
 */
@RestController
@RequestMapping("/init/stock")
public class StockInitController {

    private final StockInitService service;

    public StockInitController(StockInitService service) {
        this.service = service;
    }

    /** 建账状态与合计。 */
    @PostMapping("/status")
    @RequirePerm(value = "inv.init_stock.view", name = "查看")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(service.status());
    }

    /** 暂存行分页。 */
    @PostMapping("/line/page")
    @RequirePerm(value = "inv.init_stock.view", name = "查看")
    public ApiResponse<Map<String, Object>> linePage(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.linePage(req));
    }

    /** 手工新增一行。body 带 initMode=BATCH|BIN。 */
    @PostMapping("/line/save")
    @RequirePerm(value = "inv.init_stock.edit", name = "新增")
    public ApiResponse<Void> save(@RequestBody Map<String, Object> req) {
        service.saveLine(req);
        return ApiResponse.ok(null);
    }

    /** 手工编辑一行（含修正错误行）。 */
    @PostMapping("/line/update")
    @RequirePerm(value = "inv.init_stock.edit", name = "编辑")
    public ApiResponse<Void> update(@RequestBody Map<String, Object> req) {
        service.updateLine(req);
        return ApiResponse.ok(null);
    }

    /** 删除一行。body: {lineId} */
    @PostMapping("/line/delete")
    @RequirePerm(value = "inv.init_stock.delete", name = "删除")
    public ApiResponse<Void> delete(@RequestBody Map<String, Object> req) {
        service.deleteLine(TmsUtil.str(req.get("lineId")));
        return ApiResponse.ok(null);
    }

    /** 清空未建账暂存行。body: {batchNo?} */
    @PostMapping("/clear")
    @RequirePerm(value = "inv.init_stock.delete", name = "清空")
    public ApiResponse<Map<String, Object>> clear(@RequestBody(required = false) Map<String, Object> req) {
        String batchNo = req == null ? null : TmsUtil.str(req.get("batchNo"));
        int n = service.clear(batchNo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deleted", n);
        return ApiResponse.ok(m);
    }

    /** Excel 导入（前端解析 xlsx 后传 rows）。body: {mode:BATCH|BIN, rows, fileName} */
    @PostMapping("/import")
    @RequirePerm(value = "inv.init_stock.import", name = "导入")
    public ApiResponse<Map<String, Object>> importRows(@RequestBody Map<String, Object> body) {
        String mode = TmsUtil.str(body.get("mode"));
        String defaultName = "BIN".equalsIgnoreCase(mode) ? "库存期初导入（按库位）.xlsx" : "库存期初导入（按批次）.xlsx";
        return ApiResponse.ok(service.importRows(mode, asRows(body.get("rows")),
                TmsUtil.str(body.getOrDefault("fileName", defaultName))));
    }

    /** 期初建账（写三层库存账；BIN 行同时写库位账）。 */
    @PostMapping("/post")
    @RequirePerm(value = "inv.init_stock.post", name = "期初建账")
    public ApiResponse<Map<String, Object>> post() {
        return ApiResponse.ok(service.post());
    }

    /** 反建账。body: {reason} */
    @PostMapping("/reverse")
    @RequirePerm(value = "inv.init_stock.reverse", name = "反建账")
    public ApiResponse<Void> reverse(@RequestBody Map<String, Object> req) {
        service.reverse(TmsUtil.str(req.get("reason")));
        return ApiResponse.ok(null);
    }

    /** 导入模板下载。?type=batch（默认）| bin。 */
    @GetMapping("/import-template")
    @RequirePerm(value = "inv.init_stock.import", name = "导入")
    public void downloadTemplate(@RequestParam(value = "type", required = false, defaultValue = "batch") String type,
                                 HttpServletResponse response) throws Exception {
        boolean bin = "bin".equalsIgnoreCase(type);
        String resource = bin ? "templates/init-stock-bin-template.xlsx" : "templates/init-stock-batch-template.xlsx";
        String downloadName = bin ? "库存期初_导入模板（按库位）.xlsx" : "库存期初_导入模板（按批次）.xlsx";
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            String fileName = URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            response.setHeader("Content-Disposition", "attachment;filename=" + fileName);
            in.transferTo(response.getOutputStream());
        }
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
}
