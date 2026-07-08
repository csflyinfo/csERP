package com.erp.tms;

import com.erp.common.api.ApiResponse;
import com.erp.common.storage.StorageService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 统一图片上传接口（P5-1 MinIO 集成）。
 * <p>
 * 司机 APP 拍照后通过 multipart/form-data 上传到服务器，
 * 服务器转存 StorageService（本地文件/MinIO），返回 URL 直接入库。
 * 替代之前 base64 直接存数据库 LONGTEXT 的方式。
 *
 * 接口：
 *   POST /tms/app/upload/image   上传单张图片，返回 {url, objectKey}
 */
@RestController
@RequestMapping("/tms/app")
public class TmsUploadController {

    private final StorageService storageService;

    private static final Set<String> ALLOWED_BIZ_TYPES = Set.of(
            "SIGN", "RETURN", "STORE", "SETTLEMENT", "SIGNATURE", "REJECT", "RESCHEDULE"
    );

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    public TmsUploadController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/upload/image")
    public ApiResponse<Map<String, String>> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "bizType", defaultValue = "SIGN") String bizType) {

        if (file.isEmpty()) {
            return ApiResponse.fail("400", "文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ApiResponse.fail("400", "文件大小不能超过 5MB");
        }

        String type = bizType.toUpperCase();
        if (!ALLOWED_BIZ_TYPES.contains(type)) {
            return ApiResponse.fail("400", "不支持的图片类型: " + bizType);
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ApiResponse.fail("400", "仅支持图片文件");
        }

        // 生成 objectKey: {bizType}/{yyyy-MM-dd}/{uuid}.{ext}
        String ext = extractExtension(file.getOriginalFilename(), contentType);
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String objectKey = type + "/" + datePart + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;

        try {
            byte[] data = file.getBytes();
            String url = storageService.upload(data, contentType, objectKey);
            return ApiResponse.ok(Map.of("url", url, "objectKey", objectKey));
        } catch (Exception e) {
            return ApiResponse.fail("500", "图片上传失败: " + e.getMessage());
        }
    }

    private String extractExtension(String filename, String contentType) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
            if (ext.matches("jpg|jpeg|png|gif|webp")) {
                return ext.equals("jpeg") ? "jpg" : ext;
            }
        }
        // 从 contentType 推断
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }
}
