package com.erp.finance.gl;

import com.erp.common.api.ApiResponse;
import com.erp.common.api.PageRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import com.erp.common.security.RequirePerm;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会计事件池 / 待生成凭证工作台。
 */
@RestController
@RequestMapping("/finance/gl/event")
public class GlEventController {

    private final GlEventService eventService;

    public GlEventController(GlEventService eventService) {
        this.eventService = eventService;
    }

    /** 事件分页（结构化过滤在 SQL 完成，filters 必须为空 Map，见 PageResult 内存过滤坑）。 */
    @RequirePerm(value = "finance.gl.event.view", name = "查看")
    @PostMapping("/page")
    public ApiResponse<?> page(@RequestBody Map<String, Object> body) {
        int pageNo = body.get("pageNo") == null ? 1 : Integer.parseInt(String.valueOf(body.get("pageNo")));
        int pageSize = body.get("pageSize") == null ? 20 : Integer.parseInt(String.valueOf(body.get("pageSize")));
        PageRequest request = new PageRequest(pageNo, pageSize, null, null, new LinkedHashMap<>());
        return ApiResponse.ok(eventService.page(request, body));
    }

    /** 待处理角标数 */
    @RequirePerm(value = "finance.gl.event.view", name = "查看")
    @PostMapping("/pending-count")
    public ApiResponse<?> pendingCount() {
        return ApiResponse.ok(eventService.pendingCount());
    }

    /** 事件 payload 详情 */
    @RequirePerm(value = "finance.gl.event.view", name = "查看")
    @PostMapping("/payload")
    public ApiResponse<?> payload(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(eventService.payload(String.valueOf(body.get("id"))));
    }

    /** 手工补录事件（会计补丢单/冒烟测试用） */
    @RequirePerm(value = "finance.gl.event.add", name = "补录事件")
    @PostMapping("/emit")
    public ApiResponse<?> emit(@RequestBody Map<String, Object> body) throws Exception {
        return ApiResponse.ok(eventService.manualEmit(body));
    }

    /** 批量生成草稿凭证：{ids:[...]} 或 {allPending:true} */
    @RequirePerm(value = "finance.gl.event.generate", name = "生成凭证")
    @PostMapping("/generate")
    public ApiResponse<?> generate(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(eventService.generate(body));
    }

    /** 忽略事件 */
    @RequirePerm(value = "finance.gl.event.close", name = "忽略")
    @PostMapping("/ignore")
    public ApiResponse<?> ignore(@RequestBody Map<String, Object> body) {
        eventService.ignore(String.valueOf(body.get("id")));
        return ApiResponse.ok(true);
    }

    /** 取消忽略 */
    @RequirePerm(value = "finance.gl.event.edit", name = "取消忽略")
    @PostMapping("/unignore")
    public ApiResponse<?> unignore(@RequestBody Map<String, Object> body) {
        eventService.unignore(String.valueOf(body.get("id")));
        return ApiResponse.ok(true);
    }

    /** 重置为待生成（草稿凭证联动删除） */
    @RequirePerm(value = "finance.gl.event.edit", name = "重置")
    @PostMapping("/reset")
    public ApiResponse<?> reset(@RequestBody Map<String, Object> body) {
        eventService.reset(String.valueOf(body.get("id")));
        return ApiResponse.ok(true);
    }
}
