package com.ontologybuilder.connector.repository;

import com.ontologybuilder.connector.model.FileUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 文件上传记录仓库
 */
@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, String> {

    /**
     * 根据租户ID查找文件上传记录
     * @param tenantId 租户ID
     * @return 文件上传记录列表
     */
    List<FileUpload> findByTenantId(String tenantId);

    /**
     * 根据租户ID和状态查找文件上传记录
     * @param tenantId 租户ID
     * @param status 状态
     * @return 文件上传记录列表
     */
    List<FileUpload> findByTenantIdAndStatus(String tenantId, FileUpload.UploadStatus status);

    /**
     * 根据租户ID和存储类型查找文件上传记录
     * @param tenantId 租户ID
     * @param storageType 存储类型
     * @return 文件上传记录列表
     */
    List<FileUpload> findByTenantIdAndStorageType(String tenantId, FileUpload.StorageType storageType);

    /**
     * 查找所有已完成状态的文件上传记录（跨文件夹）
     * @return 文件上传记录列表
     */
    List<FileUpload> findByStatus(FileUpload.UploadStatus status);

    /**
     * 根据存储类型查找所有已完成状态的文件上传记录（跨文件夹）
     * @param status 状态
     * @param storageType 存储类型
     * @return 文件上传记录列表
     */
    List<FileUpload> findByStatusAndStorageType(FileUpload.UploadStatus status, FileUpload.StorageType storageType);
}