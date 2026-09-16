package com.erp.finance.init;

import com.erp.common.api.ApiResponse;
import com.erp.common.security.RequirePerm;
import com.erp.tms.TmsUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户期初预收初始化（PRD-35 M4）。路由前缀 /init/adv。
 */
@RestController
@RequestMapping("/init/adv")
public class AdvInitController {

    private final AdvInitService service;

    public AdvInitController(AdvInitService service) {
        this.service = service;
    }

    /** 建账状态与合计。 */
    @PostMapping("/status")
    @RequirePerm(value = "fin.init_adv.view", name = "查看")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(service.status());
    }

    /** 暂存行分页。 */
    @PostMapping("/line/page")
    @RequirePerm(value = "fin.init_adv.view", name = "查看")
    public ApiResponse<Map<String, Object>> linePage(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(service.linePage(req));
    }

    /** 手工新增一行。 */
    @PostMapping("/line/save")
    @RequirePerm(value = "fin.init_adv.edit", name = "新增")
    public ApiResponse<Void> save(@RequestBody Map<String, Object> req) {
        service.saveLine(req);
        return ApiResponse.ok(null);
    }

    /** 手工编辑一行（含修正错误行）。 */
    @PostMapping("/line/update")
    @RequirePerm(value = "fin.init_adv.edit", name = "编辑")
    public ApiResponse<Void> update(@RequestBody Map<String, Object> req) {
        service.updateLine(req);
        return ApiResponse.ok(null);
    }

    /** 删除一行。body: {lineId} */
    @PostMapping("/line/delete")
    @RequirePerm(value = "fin.init_adv.delete", name = "删除")
    public ApiResponse<Void> delete(@RequestBody Map<String, Object> req) {
        service.deleteLine(TmsUtil.str(req.get("lineId")));
        return ApiResponse.ok(null);
    }

    /** 清空未建账暂存行。body: {batchNo?} */
    @PostMapping("/clear")
    @RequirePerm(value = "fin.init_adv.delete", name = "清空")
    public ApiResponse<Map<String, Object>> clear(@RequestBody(required = false) Map<String, Object> req) {
        String batchNo = req == null ? "" : TmsUtil.str(req.get("batchNo"));
        int n = service.clear(batchNo);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deleted", n);
        return ApiResponse.ok(m);
    }

    /** Excel 导入（前端解析 xlsx 后传 rows）。body: {rows,fileName} */
    @PostMapping("/import")
    @RequirePerm(value = "fin.init_adv.import", name = "导入")
    public ApiResponse<Map<String, Object>> importRows(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.importRows(asRows(body.get("rows")),
                TmsUtil.str(body.getOrDefault("fileName", "客户期初预收导入.xlsx"))));
    }

    /** 期初建账（写 ADV_OPENING 流水）。 */
    @PostMapping("/post")
    @RequirePerm(value = "fin.init_adv.post", name = "期初建账")
    public ApiResponse<Map<String, Object>> post() {
        return ApiResponse.ok(service.post());
    }

    /** 反建账。body: {reason} */
    @PostMapping("/reverse")
    @RequirePerm(value = "fin.init_adv.reverse", name = "反建账")
    public ApiResponse<Void> reverse(@RequestBody Map<String, Object> req) {
        service.reverse(TmsUtil.str(req.get("reason")));
        return ApiResponse.ok(null);
    }

    /** 导入模板下载。 */
    @GetMapping("/import-template")
    @RequirePerm(value = "fin.init_adv.import", name = "导入")
    public void downloadTemplate(HttpServletResponse response) throws Exception {
        try (var in = new ClassPathResource("templates/init-adv-template.xlsx").getInputStream()) {
            String fileName = URLEncoder.encode("客户期初预收_导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
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
