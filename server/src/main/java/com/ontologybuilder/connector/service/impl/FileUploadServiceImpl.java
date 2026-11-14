package com.ontologybuilder.connector.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologybuilder.common.exception.BusinessException;
import com.ontologybuilder.common.exception.NotFoundException;
import com.ontologybuilder.connector.model.FileUpload;
import com.ontologybuilder.connector.model.TableSchema;
import com.ontologybuilder.connector.repository.FileUploadRepository;
import com.ontologybuilder.connector.service.FileUploadService;
import com.ontologybuilder.connector.storage.StorageException;
import com.ontologybuilder.connector.storage.StorageStrategy;
import com.ontologybuilder.connector.storage.StorageStrategyFactory;
import com.ontologybuilder.virtualtable.model.VirtualTable;
import com.ontologybuilder.virtualtable.service.VirtualTableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件上传服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadServiceImpl implements FileUploadService {

    private final FileUploadRepository fileUploadRepository;
    private final ObjectMapper objectMapper;
    private final StorageStrategyFactory storageStrategyFactory;
    private final VirtualTableService virtualTableService;

    @Value("${file.upload.dir:./uploads}")
    private String uploadDir;

    @Override
    @Transactional
    public FileUpload uploadFile(MultipartFile file, String tenantId, String configJson) {
        try {
            // 验证文件
            if (file.isEmpty()) {
                throw new BusinessException("上传的文件为空");
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                throw new BusinessException("文件名不能为空");
            }

            // 确定文件类型
            String fileType = determineFileType(originalFilename);
            String filename = generateUniqueFilename(originalFilename);

            // 使用存储策略上传文件
            StorageStrategy storageStrategy;
            try {
                if (configJson != null && !configJson.trim().isEmpty()) {
                    JsonNode configNode = objectMapper.readTree(configJson);
                    JsonNode storageTypeNode = configNode.path("storageType");
                    if (!storageTypeNode.isMissingNode() && !storageTypeNode.asText().isEmpty()) {
                        storageStrategy = storageStrategyFactory.getStrategy(storageTypeNode.asText());
                    } else {
                        storageStrategy = storageStrategyFactory.getDefaultStrategy();
                    }
                } else {
                    storageStrategy = storageStrategyFactory.getDefaultStrategy();
                }
            } catch (Exception e) {
                log.warn("解析configJson失败，使用默认存储策略", e);
                storageStrategy = storageStrategyFactory.getDefaultStrategy();
            }

            Map<String, String> metadata = new HashMap<>();
            metadata.put("original-filename", originalFilename);
            metadata.put("tenant-id", tenantId);
            metadata.put("content-type", file.getContentType());

            String storagePath = storageStrategy.uploadFile(file, tenantId, metadata);

            // 创建文件上传记录
            FileUpload fileUpload = FileUpload.builder()
                    .tenantId(tenantId)
                    .filename(filename)
                    .originalFilename(originalFilename)
                    .fileType(fileType)
                    .fileSize(file.getSize())
                    .filePath(storagePath) // 存储路径（对象名称或文件路径）
                    .storageType(FileUpload.StorageType.valueOf(storageStrategy.getType().name()))
                    .storagePath(storagePath)
                    .configJson(configJson)
                    .status(FileUpload.UploadStatus.PENDING)
                    .build();

            FileUpload saved = fileUploadRepository.save(fileUpload);
            log.info("文件上传成功: {} -> {}", originalFilename, saved.getId());

            // 异步处理文件
            processFileAsync(saved.getId());

            return saved;
        } catch (StorageException e) {
            log.error("文件存储失败", e);
            throw new BusinessException("文件存储失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("文件上传处理失败", e);
            throw new BusinessException("文件上传处理失败: " + e.getMessage());
        }
    }

    @Override
    public FileUpload getFileUpload(String id) {
        return fileUploadRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("文件上传记录不存在: " + id));
    }

    @Override
    public List<FileUpload> listFileUploads(String tenantId) {
        return fileUploadRepository.findByTenantId(tenantId);
    }

    @Override
    public List<FileUpload> listFileUploads(String tenantId, String storageType) {
        if (storageType == null || storageType.isBlank()) {
            return listFileUploads(tenantId);
        }
        try {
            FileUpload.StorageType st = FileUpload.StorageType.valueOf(storageType.toUpperCase());
            return fileUploadRepository.findByTenantIdAndStorageType(tenantId, st);
        } catch (IllegalArgumentException e) {
            log.warn("无效的存储类型参数: {}, 返回全部文件", storageType);
            return listFileUploads(tenantId);
        }
    }

    @Override
    public List<FileUpload> listAllCompletedFileUploads() {
        List<FileUpload> files = fileUploadRepository.findByStatus(FileUpload.UploadStatus.COMPLETED);
        log.info("listAllCompletedFileUploads 返回 {} 个文件", files.size());
        // 打印 tenantId 分布
        Map<String, Long> tenantCount = new HashMap<>();
        files.forEach(f -> {
            String key = f.getTenantId() != null ? f.getTenantId() : "null";
            tenantCount.put(key, tenantCount.getOrDefault(key, 0L) + 1);
        });
        log.info("tenantId 分布: {}", tenantCount);
        return files;
    }

    @Override
    public List<FileUpload> listAllCompletedFileUploads(String storageType) {
        if (storageType == null || storageType.isBlank()) {
            return listAllCompletedFileUploads();
        }
        try {
            FileUpload.StorageType st = FileUpload.StorageType.valueOf(storageType.toUpperCase());
            List<FileUpload> files = fileUploadRepository.findByStatusAndStorageType(FileUpload.UploadStatus.COMPLETED, st);
            log.info("listAllCompletedFileUploads(storageType={}) 返回 {} 个文件", storageType, files.size());
            return files;
        } catch (IllegalArgumentException e) {
            log.warn("无效的存储类型参数: {}, 返回全部已完成文件", storageType);
            return listAllCompletedFileUploads();
        }
    }

    @Override
    @Transactional
    public FileUpload updateFileContent(String id, String content) {
        if (content == null) {
            throw new BusinessException("文件内容不能为空");
        }

        FileUpload fileUpload = getFileUpload(id);

        // 获取存储策略
        StorageStrategy storageStrategy;
        if (fileUpload.getStorageType() != null) {
            storageStrategy = storageStrategyFactory.getStrategy(
                    StorageStrategy.StorageType.valueOf(fileUpload.getStorageType().name()));
        } else {
            storageStrategy = storageStrategyFactory.getDefaultStrategy();
        }

        try {
            byte[] contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String contentType = determineContentTypeForEdit(fileUpload.getOriginalFilename());
            storageStrategy.overwriteFile(fileUpload.getStoragePath(), contentBytes, contentType);

            // 更新文件大小
            fileUpload.setFileSize((long) contentBytes.length);
            FileUpload saved = fileUploadRepository.save(fileUpload);
            log.info("文件内容更新成功: id={}, filename={}, newSize={}", id, fileUpload.getOriginalFilename(), contentBytes.length);
            return saved;
        } catch (StorageException e) {
            log.error("文件内容更新失败: id={}", id, e);
            throw new BusinessException("文件内容更新失败: " + e.getMessage());
        }
    }

    /**
     * 根据文件名推断 Content-Type（用于在线编辑保存）
     */
    private String determineContentTypeForEdit(String filename) {
        if (filename == null) return "text/plain; charset=utf-8";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".xml")) return "application/xml; charset=utf-8";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".csv")) return "text/csv; charset=utf-8";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "text/yaml; charset=utf-8";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown; charset=utf-8";
        if (lower.endsWith(".sql")) return "text/x-sql; charset=utf-8";
        return "text/plain; charset=utf-8";
    }

    @Override
    @Transactional
    public FileUpload renameFileUpload(String id, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }
        if (newName.contains("/") || newName.contains("\\")) {
            throw new BusinessException("文件名不能包含路径分隔符");
        }

        FileUpload fileUpload = getFileUpload(id);
        String oldName = fileUpload.getOriginalFilename();

        if (newName.equals(oldName)) {
            return fileUpload;
        }

        // 如果扩展名变化，重新检测文件类型
        String oldExt = getExtension(oldName);
        String newExt = getExtension(newName);
        if (newExt != null && !newExt.equals(oldExt)) {
            String newFileType = determineFileType(newName);
            fileUpload.setFileType(newFileType);
        }

        fileUpload.setOriginalFilename(newName);
        FileUpload saved = fileUploadRepository.save(fileUpload);
        log.info("文件重命名成功: id={}, {} -> {}", id, oldName, newName);
        return saved;
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return null;
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex < filename.length() - 1 ? filename.substring(dotIndex).toLowerCase() : null;
    }

    @Override
    @Transactional
    public void deleteFileUpload(String id) {
        FileUpload fileUpload = getFileUpload(id);
        try {
            // 使用存储策略删除物理文件
            StorageStrategy storageStrategy;
            if (fileUpload.getStorageType() != null) {
                storageStrategy = storageStrategyFactory.getStrategy(StorageStrategy.StorageType.valueOf(fileUpload.getStorageType().name()));
            } else {
                storageStrategy = storageStrategyFactory.getDefaultStrategy();
            }
            storageStrategy.deleteFile(fileUpload.getStoragePath());
        } catch (Exception e) {
            log.warn("删除物理文件失败: {}", fileUpload.getStoragePath(), e);
        }
        fileUploadRepository.deleteById(id);
        log.info("删除文件上传记录: {}", id);
    }

    @Override
    @Transactional
    public TableSchema parseFileAndCreateVirtualTable(String fileUploadId) {
        FileUpload fileUpload = getFileUpload(fileUploadId);
        try {
            // 只对结构化文件（CSV/Excel）创建虚拟表，非结构化文件（TXT/PDF/Word）跳过
            String fileType = fileUpload.getFileType();
            if (!"CSV".equalsIgnoreCase(fileType) && !"EXCEL".equalsIgnoreCase(fileType)) {
                log.info("非结构化文件，跳过虚拟表创建: {} (type={})", fileUpload.getOriginalFilename(), fileType);
                fileUpload.setStatus(FileUpload.UploadStatus.COMPLETED);
                fileUploadRepository.save(fileUpload);
                return null;
            }

            // 更新状态为处理中
            fileUpload.setStatus(FileUpload.UploadStatus.PROCESSING);
            fileUploadRepository.save(fileUpload);

            // 解析文件
            TableSchema tableSchema = parseFile(fileUpload);

            // 读取实际数据行
            List<Map<String, Object>> dataRows = readFileData(fileUpload, tableSchema);

            // 创建虚拟表实体
            String tableName = sanitizeTableName(fileUpload.getOriginalFilename());
            VirtualTable vt = VirtualTable.builder()
                    .tenantId(fileUpload.getTenantId())
                    .name(tableName)
                    .schemaJson(objectMapper.writeValueAsString(tableSchema))
                    .dataJson(objectMapper.writeValueAsString(dataRows))
                    .sourceRefJson(objectMapper.writeValueAsString(Map.of(
                        "fileUploadId", fileUploadId,
                        "originalFilename", fileUpload.getOriginalFilename(),
                        "fileType", fileUpload.getFileType()
                    )))
                    .refreshPolicy(VirtualTable.RefreshPolicy.manual)
                    .build();
            VirtualTable saved = virtualTableService.create(vt);

            // 更新文件上传记录
            fileUpload.setVirtualTableId(saved.getId());
            fileUpload.setStatus(FileUpload.UploadStatus.COMPLETED);
            fileUploadRepository.save(fileUpload);

            log.info("文件解析成功，创建虚拟表: {} (原文件: {})", saved.getId(), fileUpload.getOriginalFilename());
            return tableSchema;
        } catch (Exception e) {
            log.error("文件解析失败", e);
            fileUpload.setStatus(FileUpload.UploadStatus.FAILED);
            fileUpload.setErrorMessage(e.getMessage());
            fileUploadRepository.save(fileUpload);
            throw new BusinessException("文件解析失败: " + e.getMessage());
        }
    }

    @Override
    @Async
    public void processFileAsync(String fileUploadId) {
        try {
            log.info("开始异步处理文件: {}", fileUploadId);
            parseFileAndCreateVirtualTable(fileUploadId);
            log.info("异步文件处理完成: {}", fileUploadId);
        } catch (Exception e) {
            log.error("异步文件处理失败: {}", fileUploadId, e);
            // Update status to FAILED
            try {
                FileUpload fu = fileUploadRepository.findById(fileUploadId).orElse(null);
                if (fu != null) {
                    fu.setStatus(FileUpload.UploadStatus.FAILED);
                    fu.setErrorMessage(e.getMessage());
                    fileUploadRepository.save(fu);
                }
            } catch (Exception ex) {
                log.error("更新失败状态时出错", ex);
            }
        }
    }

    /**
     * Sanitize filename to be a valid table name
     */
    private String sanitizeTableName(String filename) {
        if (filename == null) return "imported_table";
        String name = filename;
        // URL-decode if the name appears to be URL-encoded
        if (name.contains("%") || name.matches(".*[0-9A-Fa-f]{2}(_[0-9A-Fa-f]{2}){3}.*")) {
            try {
                // Replace underscores back to percent signs and decode
                String decoded = name.replaceAll("_([0-9A-Fa-f]{2})", "%$1");
                decoded = java.net.URLDecoder.decode(decoded, "UTF-8");
                if (decoded.chars().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)) {
                    name = decoded; // Successfully decoded Chinese characters
                }
            } catch (Exception e) { /* keep original name */ }
        }
        // Remove extension
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx > 0) name = name.substring(0, dotIdx);
        // Remove numeric prefix like "01-" if present
        name = name.replaceFirst("^[0-9]{2}[-_]", "");
        // Replace special chars with underscore, keep Chinese
        name = name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_]", "_");
        // Collapse multiple underscores
        name = name.replaceAll("_+", "_");
        // Trim leading/trailing underscores
        name = name.replaceAll("^_|_$", "");
        if (name.isEmpty()) name = "imported_table";
        // Limit length
        if (name.length() > 80) name = name.substring(0, 80);
        return name;
    }

    @Override
    public List<List<String>> previewFileContent(String fileUploadId, int limit) {
        FileUpload fileUpload = getFileUpload(fileUploadId);
        List<List<String>> previewData = new ArrayList<>();

        // 获取存储策略
        StorageStrategy storageStrategy;
        if (fileUpload.getStorageType() != null) {
            storageStrategy = storageStrategyFactory.getStrategy(StorageStrategy.StorageType.valueOf(fileUpload.getStorageType().name()));
        } else {
            storageStrategy = storageStrategyFactory.getDefaultStrategy();
        }

        try (InputStream inputStream = storageStrategy.getFileInputStream(fileUpload.getStoragePath())) {
            String fileType = fileUpload.getFileType();

            if ("CSV".equalsIgnoreCase(fileType)) {
                // 解析CSV文件
                JsonNode config = fileUpload.getConfigJson() != null ?
                        objectMapper.readTree(fileUpload.getConfigJson()) : objectMapper.createObjectNode();

                char delimiter = config.path("delimiter").asText(",").charAt(0);
                Charset charset = Charset.forName(config.path("encoding").asText("UTF-8"));
                boolean hasHeader = config.path("hasHeader").asBoolean(true);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, charset));
                     CSVParser parser = CSVFormat.newFormat(delimiter)
                             .withIgnoreEmptyLines(true)
                             .withTrim(true)
                             .withQuote('"')
                             .parse(reader)) {

                    int rowCount = 0;
                    for (CSVRecord record : parser) {
                        if (rowCount >= limit) break;

                        List<String> row = new ArrayList<>();
                        for (String value : record) {
                            row.add(value);
                        }
                        previewData.add(row);
                        rowCount++;
                    }
                }
            } else if ("EXCEL".equalsIgnoreCase(fileType)) {
                // 使用 Apache POI 解析 Excel 文件
                Workbook workbook = WorkbookFactory.create(inputStream);
                try {
                    Sheet sheet = workbook.getSheetAt(0);
                    int lastRow = Math.min(sheet.getLastRowNum(), limit - 1);
                    for (int i = 0; i <= lastRow; i++) {
                        Row row = sheet.getRow(i);
                        List<String> rowData = new ArrayList<>();
                        if (row != null) {
                            for (int j = 0; j < row.getLastCellNum(); j++) {
                                Cell cell = row.getCell(j);
                                if (cell == null) {
                                    rowData.add("");
                                } else {
                                    rowData.add(getCellValueAsString(cell));
                                }
                            }
                        }
                        previewData.add(rowData);
                    }
                } finally {
                    workbook.close();
                }
            } else if ("WORD".equalsIgnoreCase(fileType)) {
                // Word 文件预览：提取文本内容按行展示
                String wordFileName = fileUpload.getOriginalFilename() != null ? fileUpload.getOriginalFilename() : fileUpload.getFilename();
                String text = extractWordText(inputStream, wordFileName);
                String[] textLines = text.split("\\r?\\n");
                int lineCount = 0;
                for (String textLine : textLines) {
                    if (lineCount >= limit) break;
                    if (!textLine.trim().isEmpty()) {
                        previewData.add(List.of(textLine));
                        lineCount++;
                    }
                }
            } else if ("PDF".equalsIgnoreCase(fileType)) {
                // PDF 文件预览：提取文本内容按行展示
                String text = extractPdfText(inputStream);
                String[] textLines = text.split("\\r?\\n");
                int lineCount = 0;
                for (String textLine : textLines) {
                    if (lineCount >= limit) break;
                    if (!textLine.trim().isEmpty()) {
                        previewData.add(List.of(textLine));
                        lineCount++;
                    }
                }
            } else {
                // 文本文件预览
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                    int lineCount = 0;
                    String line;
                    while ((line = reader.readLine()) != null && lineCount < limit) {
                        previewData.add(List.of(line));
                        lineCount++;
                    }
                }
            }

            return previewData;
        } catch (Exception e) {
            log.error("文件预览失败", e);
            throw new BusinessException("文件预览失败: " + e.getMessage());
        }
    }

    @Override
    public FileParseTestResult testFileParse(String fileUploadId) {
        try {
            FileUpload fileUpload = getFileUpload(fileUploadId);
            TableSchema tableSchema = parseFile(fileUpload);
            return new FileParseTestResult(true, "文件解析测试成功", tableSchema);
        } catch (Exception e) {
            return new FileParseTestResult(false, "文件解析测试失败: " + e.getMessage(), null);
        }
    }

    /**
     * 解析文件并生成表结构
     */
    private TableSchema parseFile(FileUpload fileUpload) throws IOException {
        // 获取存储策略
        StorageStrategy storageStrategy;
        if (fileUpload.getStorageType() != null) {
            storageStrategy = storageStrategyFactory.getStrategy(StorageStrategy.StorageType.valueOf(fileUpload.getStorageType().name()));
        } else {
            storageStrategy = storageStrategyFactory.getDefaultStrategy();
        }

        String fileType = fileUpload.getFileType();
        String fileName = fileUpload.getOriginalFilename();
        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = fileUpload.getFilename();
        }

        try (InputStream inputStream = storageStrategy.getFileInputStream(fileUpload.getStoragePath())) {
            if ("CSV".equalsIgnoreCase(fileType)) {
                return parseCsvFile(inputStream, fileName, fileUpload.getConfigJson());
            } else if ("EXCEL".equalsIgnoreCase(fileType)) {
                return parseExcelFile(inputStream, fileName, fileUpload.getConfigJson());
            } else if ("WORD".equalsIgnoreCase(fileType)) {
                return parseWordFile(inputStream, fileName);
            } else if ("PDF".equalsIgnoreCase(fileType)) {
                return parsePdfFile(inputStream, fileName);
            } else {
                // 通用文本文件解析
                return parseTextFile(inputStream, fileName);
            }
        } catch (StorageException e) {
            throw new IOException("获取文件输入流失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析CSV文件
     */
    private TableSchema parseCsvFile(InputStream inputStream, String fileName, String configJson) throws IOException {
        JsonNode config = configJson != null ?
                objectMapper.readTree(configJson) : objectMapper.createObjectNode();

        char delimiter = config.path("delimiter").asText(",").charAt(0);
        Charset charset = Charset.forName(config.path("encoding").asText("UTF-8"));
        boolean hasHeader = config.path("hasHeader").asBoolean(true);

        List<TableSchema.ColumnSchema> columns = new ArrayList<>();
        List<List<String>> sampleData = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, charset));
             CSVParser parser = CSVFormat.newFormat(delimiter)
                     .withIgnoreEmptyLines(true)
                     .withTrim(true)
                     .withQuote('"')
                     .parse(reader)) {

            boolean isFirstRow = true;
            int rowCount = 0;

            for (CSVRecord record : parser) {
                if (rowCount >= 100) break; // 只分析前100行

                List<String> row = new ArrayList<>();
                for (String value : record) {
                    row.add(value);
                }
                sampleData.add(row);

                if (isFirstRow && hasHeader) {
                    // 第一行是表头
                    for (int i = 0; i < row.size(); i++) {
                        TableSchema.ColumnSchema column = new TableSchema.ColumnSchema();
                        column.setName(row.get(i).trim());
                        column.setType("string"); // 初始类型
                        column.setNullable(true);
                        columns.add(column);
                    }
                } else if (isFirstRow && !hasHeader) {
                    // 没有表头，生成列名
                    for (int i = 0; i < row.size(); i++) {
                        TableSchema.ColumnSchema column = new TableSchema.ColumnSchema();
                        column.setName("column_" + (i + 1));
                        column.setType(inferDataType(row.get(i)));
                        column.setNullable(true);
                        columns.add(column);
                    }
                } else if (!isFirstRow && columns.isEmpty()) {
                    // 没有表头且第一行是数据
                    for (int i = 0; i < row.size(); i++) {
                        TableSchema.ColumnSchema column = new TableSchema.ColumnSchema();
                        column.setName("column_" + (i + 1));
                        column.setType(inferDataType(row.get(i)));
                        column.setNullable(true);
                        columns.add(column);
                    }
                } else if (!isFirstRow) {
                    // 分析数据类型
                    for (int i = 0; i < Math.min(row.size(), columns.size()); i++) {
                        String value = row.get(i);
                        TableSchema.ColumnSchema column = columns.get(i);
                        String currentType = column.getType();
                        String inferredType = inferDataType(value);

                        // 更新为更具体的类型
                        if (isTypeMoreSpecific(currentType, inferredType)) {
                            column.setType(inferredType);
                        }
                    }
                }

                isFirstRow = false;
                rowCount++;
            }
        }

        // 构建表结构
        String tableName = fileName.replaceAll("\\.[^.]*$", "");
        return TableSchema.builder()
                .tableName(tableName)
                .columns(columns)
                .primaryKey(new ArrayList<>())
                .foreignKeys(new ArrayList<>())
                .build();
    }

    /**
     * 解析Excel文件
     */
    private TableSchema parseExcelFile(InputStream inputStream, String fileName, String configJson) throws IOException {
        List<TableSchema.ColumnSchema> columns = new ArrayList<>();
        List<List<String>> sampleData = new ArrayList<>();

        boolean isXlsx = fileName.toLowerCase().endsWith(".xlsx");

        try (Workbook workbook = isXlsx ? new XSSFWorkbook(inputStream) : new HSSFWorkbook(inputStream)) {

            Sheet sheet = workbook.getSheetAt(0); // 只处理第一个工作表
            JsonNode config = configJson != null ?
                    objectMapper.readTree(configJson) : objectMapper.createObjectNode();

            boolean hasHeader = config.path("hasHeader").asBoolean(true);
            int headerRowIndex = config.path("headerRowIndex").asInt(0);
            int dataStartRowIndex = hasHeader ? headerRowIndex + 1 : headerRowIndex;
            int maxRowsToAnalyze = config.path("maxRowsToAnalyze").asInt(100);

            int rowCount = 0;
            for (Row row : sheet) {
                if (rowCount >= maxRowsToAnalyze) break;

                List<String> rowData = new ArrayList<>();
                for (Cell cell : row) {
                    String cellValue = getCellValueAsString(cell);
                    rowData.add(cellValue);
                }

                sampleData.add(rowData);

                if (rowCount == headerRowIndex && hasHeader && !rowData.isEmpty()) {
                    // 处理表头行
                    for (int i = 0; i < rowData.size(); i++) {
                        String headerName = rowData.get(i);
                        if (headerName == null || headerName.trim().isEmpty()) {
                            headerName = "Column_" + (i + 1);
                        }

                        TableSchema.ColumnSchema column = new TableSchema.ColumnSchema();
                        column.setName(headerName.trim());
                        column.setType("string"); // 初始类型
                        column.setNullable(true);
                        columns.add(column);
                    }
                } else if (rowCount >= dataStartRowIndex && columns.isEmpty()) {
                    // 没有表头，生成列名
                    for (int i = 0; i < rowData.size(); i++) {
                        TableSchema.ColumnSchema column = new TableSchema.ColumnSchema();
                        column.setName("Column_" + (i + 1));
                        column.setType(inferDataType(rowData.get(i)));
                        column.setNullable(true);
                        columns.add(column);
                    }
                } else if (rowCount >= dataStartRowIndex && !columns.isEmpty()) {
                    // 分析数据类型
                    for (int i = 0; i < Math.min(rowData.size(), columns.size()); i++) {
                        String cellValue = rowData.get(i);
                        TableSchema.ColumnSchema column = columns.get(i);
                        String currentType = column.getType();
                        String inferredType = inferDataType(cellValue);

                        // 更新为更具体的类型
                        if (isTypeMoreSpecific(currentType, inferredType)) {
                            column.setType(inferredType);
                        }
                    }
                }

                rowCount++;
            }
        }

        // 如果经过分析后仍然没有列，创建默认列
        if (columns.isEmpty()) {
            TableSchema.ColumnSchema column = new TableSchema.ColumnSchema();
            column.setName("Data");
            column.setType("string");
            column.setNullable(true);
            columns.add(column);
        }

        String tableName = fileName.replaceAll("\\.[^.]*$", "");
        return TableSchema.builder()
                .tableName(tableName)
                .columns(columns)
                .primaryKey(new ArrayList<>())
                .foreignKeys(new ArrayList<>())
                .build();
    }

    /**
     * 获取单元格值作为字符串
     */
    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    double numericValue = cell.getNumericCellValue();
                    // 检查是否为整数
                    if (numericValue == Math.floor(numericValue)) {
                        return String.valueOf((long) numericValue);
                    } else {
                        return String.valueOf(numericValue);
                    }
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return getCellValueAsString(cell.getCachedFormulaResultType());
                } catch (Exception e) {
                    return cell.getCellFormula();
                }
            case BLANK:
                return "";
            default:
                return "";
        }
    }

    /**
     * 获取单元格值作为字符串（重载版本）
     */
    private String getCellValueAsString(CellType cellType) {
        // 简化实现，对于公式结果类型返回空字符串
        return "";
    }

    /**
     * 解析文本文件 — 提取结构化元数据 + 正文内容
     * 支持格式: "键: 值" 开头的元数据行, 空行后为正文
     */
    private TableSchema parseTextFile(InputStream inputStream, String fileName) throws IOException {
        List<TableSchema.ColumnSchema> columns = new ArrayList<>();
        String content = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        // 提取元数据列
        String[] lines = content.split("\n", 50); // 只分析前50行找元数据
        Map<String, String> metadata = new LinkedHashMap<>();
        int bodyStartLine = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() && metadata.isEmpty()) continue;
            int colonIdx = line.indexOf('：'); // 中文冒号
            if (colonIdx < 0) colonIdx = line.indexOf(':'); // 英文冒号
            if (colonIdx > 0 && colonIdx < 40) {
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                if (!key.isEmpty() && key.length() < 20) {
                    metadata.put(key, value);
                    continue;
                }
            }
            // 第一个非元数据行就是正文开始
            bodyStartLine = i;
            break;
        }

        // 为每个元数据字段创建列
        for (String key : metadata.keySet()) {
            TableSchema.ColumnSchema col = new TableSchema.ColumnSchema();
            col.setName(sanitizeColumnName(key));
            col.setType("string");
            col.setNullable(true);
            columns.add(col);
        }

        // 正文内容列
        TableSchema.ColumnSchema contentCol = new TableSchema.ColumnSchema();
        contentCol.setName("内容");
        contentCol.setType("string");
        contentCol.setNullable(true);
        columns.add(contentCol);

        String tableName = fileName.replaceAll("\\.[^.]*$", "");
        return TableSchema.builder()
                .tableName(tableName)
                .columns(columns)
                .primaryKey(new ArrayList<>())
                .foreignKeys(new ArrayList<>())
                .build();
    }

    private String sanitizeColumnName(String key) {
        return key.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    /**
     * 读取文件中的数据行，映射到 schema 的列
     */
    private List<Map<String, Object>> readFileData(FileUpload fileUpload, TableSchema tableSchema) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        StorageStrategy storageStrategy = getStorageStrategy(fileUpload);
        List<String> colNames = tableSchema.getColumns().stream()
                .map(TableSchema.ColumnSchema::getName).toList();

        try (InputStream inputStream = getFileInputStreamSafe(storageStrategy, fileUpload.getStoragePath())) {
            String fileType = fileUpload.getFileType();
            if ("CSV".equalsIgnoreCase(fileType)) {
                rows = readCsvData(inputStream, colNames, fileUpload.getConfigJson());
            } else if ("EXCEL".equalsIgnoreCase(fileType)) {
                rows = readExcelData(inputStream, colNames, fileUpload.getConfigJson());
            }
        }
        return rows;
    }

    private List<Map<String, Object>> readCsvData(InputStream inputStream, List<String> colNames, String configJson) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode config = configJson != null ? objectMapper.readTree(configJson) : objectMapper.createObjectNode();
        char delimiter = config.path("delimiter").asText(",").charAt(0);
        Charset charset = Charset.forName(config.path("encoding").asText("UTF-8"));
        boolean hasHeader = config.path("hasHeader").asBoolean(true);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, charset));
             CSVParser parser = CSVFormat.newFormat(delimiter).withIgnoreEmptyLines(true).withTrim(true).withQuote('"').parse(reader)) {
            boolean skip = hasHeader;
            for (CSVRecord record : parser) {
                if (skip) { skip = false; continue; }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < Math.min(record.size(), colNames.size()); i++) {
                    row.put(colNames.get(i), record.get(i));
                }
                if (!row.isEmpty()) rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, Object>> readExcelData(InputStream inputStream, List<String> colNames, String configJson) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode config = configJson != null ? objectMapper.readTree(configJson) : objectMapper.createObjectNode();
        boolean hasHeader = config.path("hasHeader").asBoolean(true);
        int headerRow = config.path("headerRowIndex").asInt(0);

        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            int rowIdx = 0;
            for (Row row : sheet) {
                if (hasHeader && rowIdx == headerRow) { rowIdx++; continue; }
                Map<String, Object> dataRow = new LinkedHashMap<>();
                for (int i = 0; i < colNames.size(); i++) {
                    Cell cell = row.getCell(i);
                    dataRow.put(colNames.get(i), cell == null ? "" : getCellValueAsString(cell));
                }
                if (dataRow.values().stream().anyMatch(v -> !v.toString().isEmpty())) {
                    rows.add(dataRow);
                }
                rowIdx++;
            }
        }
        return rows;
    }

    private StorageStrategy getStorageStrategy(FileUpload fileUpload) {
        if (fileUpload.getStorageType() != null) {
            return storageStrategyFactory.getStrategy(StorageStrategy.StorageType.valueOf(fileUpload.getStorageType().name()));
        }
        return storageStrategyFactory.getDefaultStrategy();
    }

    private InputStream getFileInputStreamSafe(StorageStrategy strategy, String storagePath) throws IOException {
        try {
            return strategy.getFileInputStream(storagePath);
        } catch (StorageException e) {
            throw new IOException("获取文件输入流失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 Word 文件（docx/doc）- 提取文本后构建单列表结构
     */
    private TableSchema parseWordFile(InputStream inputStream, String fileName) throws IOException {
        List<TableSchema.ColumnSchema> columns = new ArrayList<>();
        TableSchema.ColumnSchema column = new TableSchema.ColumnSchema();
        column.setName("content");
        column.setType("string");
        column.setNullable(true);
        columns.add(column);

        String tableName = fileName.replaceAll("\\.[^.]*$", "");
        return TableSchema.builder()
                .tableName(tableName)
                .columns(columns)
                .primaryKey(new ArrayList<>())
                .foreignKeys(new ArrayList<>())
                .build();
    }

    /**
     * 解析 PDF 文件 - 提取文本后构建单列表结构
     */
    private TableSchema parsePdfFile(InputStream inputStream, String fileName) throws IOException {
        List<TableSchema.ColumnSchema> columns = new ArrayList<>();
        TableSchema.ColumnSchema column = new TableSchema.ColumnSchema();
        column.setName("content");
        column.setType("string");
        column.setNullable(true);
        columns.add(column);

        String tableName = fileName.replaceAll("\\.[^.]*$", "");
        return TableSchema.builder()
                .tableName(tableName)
                .columns(columns)
                .primaryKey(new ArrayList<>())
                .foreignKeys(new ArrayList<>())
                .build();
    }

    /**
     * 提取 Word 文件文本内容
     */
    private String extractWordText(InputStream inputStream, String fileName) throws IOException {
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".docx")) {
            try (XWPFDocument document = new XWPFDocument(inputStream);
                 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                return extractor.getText();
            }
        } else if (lowerName.endsWith(".doc")) {
            try (HWPFDocument document = new HWPFDocument(inputStream);
                 WordExtractor extractor = new WordExtractor(document)) {
                return extractor.getText();
            }
        }
        return "";
    }

    /**
     * 提取 PDF 文件文本内容
     */
    private String extractPdfText(InputStream inputStream) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * 推断数据类型
     */
    private String inferDataType(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "string";
        }

        String trimmed = value.trim();

        // 检查是否为整数
        if (trimmed.matches("-?\\d+")) {
            return "integer";
        }

        // 检查是否为小数
        if (trimmed.matches("-?\\d+\\.\\d+")) {
            return "float";
        }

        // 检查是否为布尔值
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false") ||
            trimmed.equalsIgnoreCase("yes") || trimmed.equalsIgnoreCase("no")) {
            return "boolean";
        }

        // 检查是否为日期（简单模式）
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}") ||
            trimmed.matches("\\d{4}/\\d{2}/\\d{2}")) {
            return "date";
        }

        // 默认字符串类型
        return "string";
    }

    /**
     * 判断类型是否更具体
     */
    private boolean isTypeMoreSpecific(String currentType, String newType) {
        // 类型优先级：string < boolean < integer < float < date < datetime
        List<String> typeHierarchy = List.of("string", "boolean", "integer", "float", "decimal", "date", "datetime");

        int currentIndex = typeHierarchy.indexOf(currentType);
        int newIndex = typeHierarchy.indexOf(newType);

        if (currentIndex == -1) return true;
        if (newIndex == -1) return false;

        return newIndex > currentIndex;
    }

    /**
     * 确定文件类型
     */
    private String determineFileType(String filename) {
        String lowerName = filename.toLowerCase();
        if (lowerName.endsWith(".csv")) {
            return "CSV";
        } else if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            return "EXCEL";
        } else if (lowerName.endsWith(".json")) {
            return "JSON";
        } else if (lowerName.endsWith(".xml")) {
            return "XML";
        } else if (lowerName.endsWith(".docx") || lowerName.endsWith(".doc")) {
            return "WORD";
        } else if (lowerName.endsWith(".pdf")) {
            return "PDF";
        } else {
            return "TEXT";
        }
    }

    /**
     * 生成唯一文件名
     */
    private String generateUniqueFilename(String originalFilename) {
        String extension = "";
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = originalFilename.substring(dotIndex);
        }
        return UUID.randomUUID().toString().replace("-", "") + extension;
    }

    @Override
    public String getFileDownloadUrl(String fileUploadId, int expiresInSeconds) {
        FileUpload fileUpload = getFileUpload(fileUploadId);
        StorageStrategy storageStrategy;
        if (fileUpload.getStorageType() != null) {
            storageStrategy = storageStrategyFactory.getStrategy(StorageStrategy.StorageType.valueOf(fileUpload.getStorageType().name()));
        } else {
            storageStrategy = storageStrategyFactory.getDefaultStrategy();
        }
        try {
            return storageStrategy.getFileDownloadUrl(fileUpload.getStoragePath(), expiresInSeconds);
        } catch (StorageException e) {
            throw new BusinessException("获取文件下载URL失败: " + e.getMessage());
        }
    }

    @Override
    public String getFilePreviewUrl(String fileUploadId) {
        FileUpload fileUpload = getFileUpload(fileUploadId);
        StorageStrategy storageStrategy;
        if (fileUpload.getStorageType() != null) {
            storageStrategy = storageStrategyFactory.getStrategy(StorageStrategy.StorageType.valueOf(fileUpload.getStorageType().name()));
        } else {
            storageStrategy = storageStrategyFactory.getDefaultStrategy();
        }
        try {
            return storageStrategy.getFilePreviewUrl(fileUpload.getStoragePath());
        } catch (StorageException e) {
            throw new BusinessException("获取文件预览URL失败: " + e.getMessage());
        }
    }
}