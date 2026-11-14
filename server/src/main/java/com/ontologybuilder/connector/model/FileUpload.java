package com.ontologybuilder.connector.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件上传记录实体
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "tm_file_upload")
public class FileUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false, length = 500)
    private String filename;

    @Column(nullable = false, length = 500)
    private String originalFilename;

    @Column(nullable = false)
    private String fileType; // CSV, EXCEL, JSON, etc.

    @Column(nullable = false)
    private Long fileSize; // bytes

    @Column(nullable = false, length = 500)
    private String filePath;

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private StorageType storageType;

    @Column(name = "storage_path", length = 500)
    private String storagePath;

    @Column(columnDefinition = "TEXT")
    private String configJson; // 文件解析配置，如分隔符、编码等

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private UploadStatus status;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "virtual_table_id")
    private String virtualTableId; // 关联的虚拟表ID

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = UploadStatus.PENDING;
        }
        if (tenantId == null) {
            tenantId = "default";
        }
        if (storageType == null) {
            storageType = StorageType.LOCAL;
        }
        if (storagePath == null && filePath != null) {
            storagePath = filePath;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum UploadStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    /**
     * 文件类型枚举
     */
    public enum FileType {
        CSV,
        EXCEL,
        JSON,
        XML,
        TEXT
    }

    /**
     * 存储类型枚举
     */
    public enum StorageType {
        LOCAL,      // 本地磁盘存储
        MINIO,      // MinIO对象存储
        S3          // AWS S3兼容存储
    }
}