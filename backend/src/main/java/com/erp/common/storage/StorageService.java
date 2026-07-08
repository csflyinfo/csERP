package com.erp.common.storage;

/**
 * 统一存储服务接口（开发环境本地文件 / 生产环境 MinIO）。
 * <p>
 * 所有图片上传（签收照、退货照、门店照、交账照、电子签名）统一走此接口，
 * 返回的 URL 直接入库，替代之前的 base64 LONGTEXT 存储。
 */
public interface StorageService {

    /**
     * 上传文件，返回可访问的 URL。
     *
     * @param data        文件二进制内容
     * @param contentType MIME 类型，如 image/jpeg
     * @param objectKey   存储对象路径，如 sign/{signId}/{photoId}.jpg
     * @return 可访问 URL（如 /uploads/sign/xxx.jpg 或 http://minio:9000/tms-images/sign/xxx.jpg）
     */
    String upload(byte[] data, String contentType, String objectKey);

    /**
     * 下载文件（ERP 端预览/审核时使用）。
     */
    byte[] download(String objectKey);

    /**
     * 删除文件。
     */
    void delete(String objectKey);
}
