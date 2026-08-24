package com.erp.common.exception;

import com.erp.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("参数错误");
        return ApiResponse.fail("400", message);
    }

    /** IllegalArgumentException 用于业务校验（如「库存不足」），消息本身就是给用户看的，可直接回传。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.fail("400", ex.getMessage());
    }

    /** 唯一键冲突：给用户一句通用提示，不回显 SQL/约束名（可能泄露表结构）。 */
    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleDuplicate(DuplicateKeyException ex) {
        log.warn("唯一键冲突", ex.getMostSpecificCause() != null ? ex.getMostSpecificCause() : ex);
        return ApiResponse.fail("400", "数据已存在或编码重复，请刷新后重试");
    }

    /** 其它数据完整性异常（外键、非空、超长等）：同样不回显原始 SQL 状态。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("数据完整性异常", ex.getMostSpecificCause() != null ? ex.getMostSpecificCause() : ex);
        return ApiResponse.fail("400", "数据校验未通过，请检查输入");
    }

    /**
     * 兜底：原始异常/SQL 文本可能含表结构、约束名、堆栈路径，不得回传客户端。
     * 详情写服务端日志，客户端只拿到一句通用提示。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleException(Exception ex) {
        log.error("系统异常", ex);
        return ApiResponse.fail("500", "系统繁忙，请稍后重试");
    }
}

