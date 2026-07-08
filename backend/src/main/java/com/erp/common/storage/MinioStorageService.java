package com.erp.common.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO 对象存储实现（生产环境）。
 * <p>
 * 启动时自动创建 bucket，上传后返回 url-prefix + / + objectKey 的访问 URL。
 */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "minio")
public class MinioStorageService implements StorageService {

    private final MinioClient client;
    private final String bucket;
    private final String urlPrefix;

    public MinioStorageService(
            @Value("${storage.minio.endpoint}") String endpoint,
            @Value("${storage.minio.access-key}") String accessKey,
            @Value("${storage.minio.secret-key}") String secretKey,
            @Value("${storage.minio.bucket}") String bucket,
            @Value("${storage.minio.url-prefix}") String urlPrefix) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
        this.urlPrefix = urlPrefix;
    }

    @PostConstruct
    public void init() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new RuntimeException("MinIO bucket 初始化失败: " + bucket, e);
        }
    }

    @Override
    public String upload(byte[] data, String contentType, String objectKey) {
        try (InputStream is = new ByteArrayInputStream(data)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(is, data.length, -1)
                    .contentType(contentType)
                    .build());
            return urlPrefix + "/" + objectKey;
        } catch (Exception e) {
            throw new RuntimeException("MinIO 上传失败: " + objectKey, e);
        }
    }

    @Override
    public byte[] download(String objectKey) {
        try (InputStream is = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("MinIO 下载失败: " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("MinIO 删除失败: " + objectKey, e);
        }
    }
}
