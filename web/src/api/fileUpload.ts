import client from './client';

// ==================== 文件夹相关接口 ====================

export interface FolderRecord {
  id: string;
  name: string;
  path: string;
  tenantId: string;
  parentPath: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * 获取文件夹列表
 * @param tenantId 租户ID
 * @param storageType 存储类型（可选）
 */
export async function listFolders(tenantId: string = 'default', storageType?: string): Promise<FolderRecord[]> {
  const res: any = await client.get('/folders', {
    params: { tenantId, ...(storageType ? { storageType } : {}) },
  });
  return res.data;
}

/**
 * 创建文件夹
 * @param name 文件夹名称
 * @param path 文件夹完整路径
 * @param tenantId 租户ID
 * @param storageType 存储类型（可选）
 */
export async function createFolder(name: string, path: string, tenantId: string = 'default', storageType?: string): Promise<FolderRecord> {
  const res: any = await client.post('/folders', { name, path, tenantId, ...(storageType ? { storageType } : {}) });
  return res.data;
}

/**
 * 重命名文件夹
 * @param id 文件夹ID
 * @param newName 新名称
 */
export async function renameFolder(id: string, newName: string): Promise<FolderRecord> {
  const res: any = await client.put(`/folders/${id}`, { name: newName });
  return res.data;
}

/**
 * 删除文件夹
 * @param id 文件夹ID
 */
export async function deleteFolder(id: string): Promise<void> {
  await client.delete(`/folders/${id}`);
}

/**
 * 在线编辑保存文件内容
 * @param id 文件上传记录ID
 * @param content 新的文件内容
 */
export async function updateFileContent(id: string, content: string): Promise<FileUploadRecord> {
  const res: any = await client.put(`/file-uploads/${id}/content`, content, {
    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
  });
  return res.data;
}

/**
 * 重命名文件
 * @param id 文件上传记录ID
 * @param newName 新文件名
 */
export async function renameFileUpload(id: string, newName: string): Promise<FileUploadRecord> {
  const res: any = await client.put(`/file-uploads/${id}`, { name: newName });
  return res.data;
}

// ==================== 文件上传相关接口 ====================

export interface FileUploadRecord {
  id: string;
  tenantId: string;
  filename: string;
  originalFilename: string;
  fileType: string;
  fileSize: number;
  filePath: string;
  storageType: string;
  storagePath: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * 上传文件
 * @param formData FormData对象，包含file、tenantId、storageType等字�? */
export async function uploadFile(formData: FormData): Promise<FileUploadRecord> {
  const res: any = await client.post('/file-uploads', formData);
  return res.data;
}

export interface ExtractedReferenceDocument {
  filename: string;
  extension: string;
  mimeType: string;
  size: number;
  content: string;
  charCount: number;
  truncated: boolean;
}

/** Creator 参考资料解析预检：只提取文本，不持久化原始文件。 */
export async function extractReferenceDocument(file: File): Promise<ExtractedReferenceDocument> {
  const formData = new FormData();
  formData.append('file', file);
  const res: any = await client.post('/file-uploads/extract-text', formData);
  if (res?.code !== 200 || !res?.data?.content) {
    throw new Error(res?.message || '参考资料解析失败');
  }
  return res.data;
}

/**
 * 获取文件上传记录列表
 * @param tenantId 租户ID
 * @param storageType 存储类型（可选，local/minio/s3）
 */
export async function listFileUploads(tenantId: string = 'default', storageType?: string): Promise<FileUploadRecord[]> {
  const res: any = await client.get('/file-uploads', {
    params: { tenantId, ...(storageType ? { storageType } : {}) },
  });
  return res.data;
}

/** 获取所有已完成的文件（跨文件夹），可按存储类型过滤 */
export async function listAllCompletedFiles(storageType?: string): Promise<FileUploadRecord[]> {
  const res: any = await client.get('/file-uploads/all-completed', {
    params: { ...(storageType ? { storageType } : {}) },
  });
  return res.data;
}

/**
 * 删除文件上传记录
 * @param id 文件上传记录ID
 */
export async function deleteFileUpload(id: string): Promise<void> {
  await client.delete(`/file-uploads/${id}`);
}

/**
 * 获取文件下载URL
 * @param id 文件上传记录ID
 * @param expiresInSeconds URL有效期（秒）
 */
export async function getFileDownloadUrl(id: string, expiresInSeconds: number = 3600): Promise<string> {
  const res: any = await client.get(`/file-uploads/${id}/download-url`, {
    params: { expiresInSeconds },
  });
  return res.data;
}

/**
 * 获取文件预览URL
 * @param id 文件上传记录ID
 */
export async function getFilePreviewUrl(id: string): Promise<string | null> {
  const res: any = await client.get(`/file-uploads/${id}/preview-url`);
  return res.data;
}

/**
 * 预览文件内容（文�?CSV等）
 * @param id 文件上传记录ID
 * @param limit 限制行数
 */
export async function previewFileContent(id: string, limit: number = 10): Promise<string[][]> {
  const res: any = await client.get(`/file-uploads/${id}/preview`, {
    params: { limit },
  });
  return res.data;
}

/**
 * 解析文件并生成虚拟表
 * @param id 文件上传记录ID
 */
export async function parseFileAndCreateVirtualTable(id: string): Promise<any> {
  const res: any = await client.post(`/file-uploads/${id}/parse`);
  return res.data;
}

/**
 * �����ļ������ƹ���Ӧ��������ֱ�ӻ�ȡ Blob ��������Ԥ����
 * @param id �ļ��ϴ���¼ID
 */
export async function downloadFileBlob(id: string): Promise<Blob> {
  // 使用 client 实例以携带 JWT 认证 token（避免 403）
  const data = await client.get(`/file-uploads/${id}/download`, {
    responseType: 'blob',
  });
  return data as Blob;
}

/** ��ʽ���ļ�ת��Ϊ Markdown��SSE�� */
export async function fileToMarkdownStream(
  fileIds: string[], // 支持多个文件ID
  providerId: string,
  modelId: string,
  systemPrompt: string,
  onChunk: (content: string) => void,
  onDone: (markdown: string, truncated?: boolean) => void,
  onError: (error: string) => void,
  signal?: AbortSignal,
  refTemplateFileId?: string,
  refExampleFileId?: string,
): Promise<void> {
  const baseURL = '/api/v1';
  const { default: client } = await import('./client');
  const token = (await import('@/utils/auth')).getToken();

  const params = new URLSearchParams();
  // 将多个文件ID用逗号分隔传递
  params.append('fileIds', fileIds.join(','));
  if (providerId) params.append('providerId', providerId);
  if (modelId) params.append('modelId', modelId);
  if (refTemplateFileId) params.append('refTemplateFileId', refTemplateFileId);
  if (refExampleFileId) params.append('refExampleFileId', refExampleFileId);

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  // ����û�вο��ļ�ʱ���� body �д� systemPrompt����������ݴ��䵼�� 431 ����
  // ���вο��ļ�ʱ���ɺ�����ж�ȡ�ļ����ݣ�body ֻ�������� systemPrompt
  const body = (systemPrompt && !refTemplateFileId) ? JSON.stringify({ systemPrompt }) : undefined;

  const res = await fetch(`${baseURL}/file-uploads/convert-to-markdown-stream?${params.toString()}`, {
    method: 'POST',
    headers,
    body,
    signal,
  });

  if (!res.ok) {
    const errText = await res.text().catch(() => '');
    onError(`HTTP ${res.status}: ${errText}`);
    return;
  }

  const reader = res.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let fullContent = '';
  let doneReceived = false;
  let warningReceived = false;
  let warningMessage = '';
  let truncatedReceived = false;

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      if (line.startsWith(':') || line.trim() === '') continue; // 跳过 SSE 注释和空行
      if (line.startsWith('data:')) {
        const jsonStr = line.slice(5).trim();
        try {
          const data = JSON.parse(jsonStr);
          if (data.error) {
            onError(data.message || 'ת��ʧ��');
            return;
          }
          if (data.warning) {
            warningReceived = true;
            warningMessage = data.message || '������ܲ�����';
          }
          if (data.done) {
            doneReceived = true;
            truncatedReceived = !!data.truncated;
            onDone(data.markdown || fullContent, !!data.truncated || warningReceived);
            return;
          }
          if (data.content) {
            fullContent += data.content;
            onChunk(data.content);
          }
        } catch {
          // ���Խ�������
        }
      }
    }
  }

  // ���� buffer ����
  if (buffer.trim().startsWith('data:')) {
    const jsonStr = buffer.trim().slice(5).trim();
    try {
      const data = JSON.parse(jsonStr);
      if (data.error) { onError(data.message || 'ת��ʧ��'); return; }
      if (data.warning) { warningReceived = true; warningMessage = data.message || '������ܲ�����'; }
      if (data.done) { onDone(data.markdown || fullContent); return; }
      if (data.content) { fullContent += data.content; onChunk(data.content); }
    } catch { /* ignore */ }
  }

  if (!doneReceived && fullContent) {
    onDone(fullContent);
  } else if (!doneReceived) {
    onError('格式转换异常结束，未收到结束信号');
  }
}

/** 获取后端统一管理的默认文件转Markdown系统提示词 */
export async function fetchDefaultFileToMarkdownPrompt(): Promise<string> {
  const res = await fetch('/agent/prompts/file-to-markdown-default');
  const data = await res.json();
  return data.prompt || '';
}
