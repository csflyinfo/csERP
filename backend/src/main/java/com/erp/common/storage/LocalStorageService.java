package com.erp.common.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地文件存储实现（开发环境）。
 * <p>
 * 文件存储到 storage.local.base-dir 目录下，
 * 通过 WebConfig 映射 /uploads/** 到该目录提供 HTTP 访问。
 */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final String baseDir;
    private final String urlPrefix;

    public LocalStorageService(
            @Value("${storage.local.base-dir:./data/uploads}") String baseDir,
            @Value("${storage.local.url-prefix:/uploads}") String urlPrefix) {
        this.baseDir = baseDir.replace("\\", "/");
        this.urlPrefix = urlPrefix;
    }

    @Override
    public String upload(byte[] data, String contentType, String objectKey) {
        try {
            Path filePath = Paths.get(baseDir, objectKey);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, data);
            return urlPrefix + "/" + objectKey;
        } catch (IOException e) {
            throw new RuntimeException("本地文件存储失败: " + objectKey, e);
        }
    }

    @Override
    public byte[] download(String objectKey) {
        try {
            return Files.readAllBytes(Paths.get(baseDir, objectKey));
        } catch (IOException e) {
            throw new RuntimeException("本地文件读取失败: " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(Paths.get(baseDir, objectKey));
        } catch (IOException e) {
            throw new RuntimeException("本地文件删除失败: " + objectKey, e);
        }
    }
}
