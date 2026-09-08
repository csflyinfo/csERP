package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import com.erp.tms.TmsUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 总账凭证导出：金蝶 KIS / 用友 T+ CSV 文件流（UTF-8 BOM），导出日志查询。
 */
@RestController
@RequestMapping("/finance/gl/export")
public class GlExportController {

    private final GlExportService exportService;

    public GlExportController(GlExportService exportService) {
        this.exportService = exportService;
    }

    /** 导出 CSV：直接写文件流；写导出日志并给凭证打 export_flag。 */
    @PostMapping("/csv")
    public void exportCsv(@RequestBody Map<String, Object> body, HttpServletResponse response) throws IOException {
        String format = TmsUtil.str(body.get("format"));
        String periodFrom = TmsUtil.str(body.get("periodFrom"));
        String periodTo = TmsUtil.str(body.get("periodTo"));
        String voucherWord = TmsUtil.str(body.get("voucherWord"));

        GlExportService.ExportResult r = exportService.export(format, periodFrom, periodTo, voucherWord);

        String fileName = URLEncoder.encode(r.fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + fileName);
        response.getOutputStream().write(r.csv.getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    /** 导出日志列表。 */
    @PostMapping("/logs")
    public ApiResponse<List<Map<String, Object>>> logs() {
        return ApiResponse.ok(exportService.logs());
    }
}
