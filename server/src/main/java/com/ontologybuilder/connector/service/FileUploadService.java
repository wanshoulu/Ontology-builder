package com.ontologybuilder.connector.service;

import com.ontologybuilder.connector.model.FileUpload;
import com.ontologybuilder.connector.model.TableSchema;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件上传服务接口
 */
public interface FileUploadService {

    /**
     * 上传文件
     * @param file 上传的文件
     * @param tenantId 租户ID
     * @param configJson 配置文件（可选）
     * @return 文件上传记录
     */
    FileUpload uploadFile(MultipartFile file, String tenantId, String configJson);

    /**
     * 获取文件上传记录
     * @param id 文件上传记录ID
     * @return 文件上传记录
     */
    FileUpload getFileUpload(String id);

    /**
     * 获取租户下的所有文件上传记录
     * @param tenantId 租户ID
     * @return 文件上传记录列表
     */
    List<FileUpload> listFileUploads(String tenantId);

    /**
     * 获取租户下指定存储类型的文件上传记录
     * @param tenantId 租户ID
     * @param storageType 存储类型（LOCAL/MINIO/S3），为空则返回全部
     * @return 文件上传记录列表
     */
    List<FileUpload> listFileUploads(String tenantId, String storageType);

    /**
     * 获取所有已完成的文件上传记录（跨文件夹）
     * @return 文件上传记录列表
     */
    List<FileUpload> listAllCompletedFileUploads();

    /**
     * 获取指定存储类型的所有已完成文件上传记录（跨文件夹）
     * @param storageType 存储类型（LOCAL/MINIO/S3），为空则返回全部
     * @return 文件上传记录列表
     */
    List<FileUpload> listAllCompletedFileUploads(String storageType);

    /**
     * 删除文件上传记录
     * @param id 文件上传记录ID
     */
    void deleteFileUpload(String id);

    /**
     * 解析文件并生成虚拟表定义
     * @param fileUploadId 文件上传记录ID
     * @return 表结构定义
     */
    TableSchema parseFileAndCreateVirtualTable(String fileUploadId);

    /**
     * 处理文件上传（异步）
     * @param fileUploadId 文件上传记录ID
     */
    void processFileAsync(String fileUploadId);

    /**
     * 获取文件内容预览
     * @param fileUploadId 文件上传记录ID
     * @param limit 限制行数
     * @return 预览内容
     */
    List<List<String>> previewFileContent(String fileUploadId, int limit);

    /**
     * 测试文件解析
     * @param fileUploadId 文件上传记录ID
     * @return 测试结果
     */
    FileParseTestResult testFileParse(String fileUploadId);

    /**
     * 获取文件下载URL
     * @param fileUploadId 文件上传记录ID
     * @param expiresInSeconds URL有效期（秒）
     * @return 下载URL
     */
    String getFileDownloadUrl(String fileUploadId, int expiresInSeconds);

    /**
     * 获取文件预览URL（仅支持可预览的文件类型）
     * @param fileUploadId 文件上传记录ID
     * @return 预览URL，如果不支持预览则返回null
     */
    String getFilePreviewUrl(String fileUploadId);

    /**
     * 更新文件内容（在线编辑，覆盖写入）
     * @param id 文件上传记录ID
     * @param content 新的文件内容
     * @return 更新后的文件上传记录
     */
    FileUpload updateFileContent(String id, String content);

    /**
     * 重命名文件上传记录（修改原始文件名）
     * @param id 文件上传记录ID
     * @param newName 新文件名
     * @return 更新后的文件上传记录
     */
    FileUpload renameFileUpload(String id, String newName);

    /**
     * 文件解析测试结果
     */
    record FileParseTestResult(boolean success, String message, TableSchema tableSchema) {}
}