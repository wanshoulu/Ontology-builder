import type {
Ontology,
OntologyExtractionResult,
ValidationReport
} from '@/types';
import { getToken } from '@/utils/auth';
import client from './client';

/** �޸� JSON β�����ŵȳ��� LLM ������� */
function fixTrailingCommas(s: string): string {
  return s.replace(/,\s*([}\]])/g, '$1');
}

/** 清理 LLM 生成的 TTL 内容：压缩连续空行，确保本体头部后无多余空行 */
function cleanTtlContent(ttl: string): string {
  if (!ttl) return ttl;
  // 1. 压缩连续空行：3+ 换行 → 2 换行（即 1 个空行）
  ttl = ttl.replace(/\n{3,}/g, '\n\n');
  // 2. 确保 : a owl:Ontology . 之后最多 1 个空行
  ttl = ttl.replace(/(:\s+a\s+owl:Ontology\s*\.)\s*\n{2,}/g, '$1\n\n');
  // 3. 去除首尾空白
  return ttl.trim();
}

/** ���Խ��� JSON�����ض���� null */
function tryParse(s: string): any {
  try { return JSON.parse(s); } catch { return null; }
}

/** ���Դ��ı��н��� JSON��֧�ִ�JSON��markdown����顢{}���䣬����β�����ŵȣ� */
function tryParseJson(text: string): any {
  if (!text) return null;

  // 1. ֱ�ӽ���
  let result = tryParse(text) || tryParse(fixTrailingCommas(text));
  if (result) return result;

  // 2. �������һ�� markdown ����飨̰��ƥ�䣬�����ڲ�Ƕ�� ``` ���ţ�
  const lastBlock = text.match(/```(?:json)?\s*\n?([\s\S]*)\n?```$/);
  if (lastBlock) {
    const trimmed = lastBlock[1].trim();
    result = tryParse(trimmed) || tryParse(fixTrailingCommas(trimmed));
    if (result) return result;
  }

  // 3. �������� markdown �����
  const lazyMatches = [...text.matchAll(/```(?:json)?\s*\n?([\s\S]*?)\n?```/g)];
  for (const m of lazyMatches) {
    const trimmed = m[1].trim();
    result = tryParse(trimmed) || tryParse(fixTrailingCommas(trimmed));
    if (result) return result;
  }

  // 4. ���� { } ����
  const s = text.indexOf('{'), e = text.lastIndexOf('}');
  if (s !== -1 && e > s) {
    const substr = text.substring(s, e + 1);
    result = tryParse(substr) || tryParse(fixTrailingCommas(substr));
    if (result) return result;
  }

  return null;
}

/**
 * ������� API
 */

/** ��ȡ�����б� */
export async function getOntologyList(): Promise<Ontology[]> {
  const res: any = await client.get('/ontologies');
  return res.data || [];
}

/** ��ȡ�������� */
export async function getOntology(id: string): Promise<Ontology> {
  const res: any = await client.get(`/ontologies/${id}`);
  return res.data;
}

/** �������� */
export async function createOntology(data: { name: string; description?: string; industryOntology?: string }): Promise<Ontology> {
  const res: any = await client.post('/ontologies', data);
  return res.data;
}

/** ���±��� */
export async function updateOntology(id: string, data: Partial<{ name: string; description: string; status: string }>): Promise<Ontology> {
  const res: any = await client.put(`/ontologies/${id}`, data);
  return res.data;
}

/** ɾ������ */
export async function deleteOntology(id: string): Promise<void> {
  await client.delete(`/ontologies/${id}`);
}

/** 批量删除本体 */
export async function batchDeleteOntologies(ids: string[]): Promise<{
  total: number;
  deleted: number;
  failed: number;
  errors: { id: string; error: string }[];
}> {
  const res: any = await client.delete('/ontologies/batch', { data: ids });
  return res.data;
}

/** ���� Markdown ������ LLM ��ȡ���� */
export async function importFromMarkdown(formData: FormData): Promise<OntologyExtractionResult> {
  const res: any = await client.post('/ontologies/import-markdown', formData);
  return res.data;
}

/** �� done �¼��а�ȫ��ȡ result��ȷ�������ֶζ���Ĭ��ֵ */
function safeExtractResult(data: any): any {
  const result = data.result;

  // �����κΰ��� name �� classes �� ttlContent ����Ч���
  if (result && typeof result === 'object' && (result.name || result.classes || result.ttlContent)) {
    return {
      name: result.name || '',
      description: result.description || '',
      classes: Array.isArray(result.classes) ? result.classes : [],
      objectProperties: Array.isArray(result.objectProperties) ? result.objectProperties : [],
      dataProperties: Array.isArray(result.dataProperties) ? result.dataProperties : [],
      rules: Array.isArray(result.rules) ? result.rules : [],
      ttlContent: cleanTtlContent(result.ttlContent || ''),
    };
  }

  // �� raw_text ���Խ�����Python �˽���ʧ��ʱ�Ľ�����
  const rawText = data.raw_text || '';
  if (rawText) {
    const parsed = tryParseJson(rawText);
    if (parsed && typeof parsed === 'object' && (parsed.name || parsed.classes || parsed.ttlContent)) {
      return {
        name: parsed.name || '',
        description: parsed.description || '',
        classes: Array.isArray(parsed.classes) ? parsed.classes : [],
        objectProperties: Array.isArray(parsed.objectProperties) ? parsed.objectProperties : [],
        dataProperties: Array.isArray(parsed.dataProperties) ? parsed.dataProperties : [],
        rules: Array.isArray(parsed.rules) ? parsed.rules : [],
        ttlContent: cleanTtlContent(parsed.ttlContent || ''),
      };
    }

    // JSON ��ȫ�޷�����ʱ�����Դ� raw_text ��ֱ����ȡ TTL ����
    const extractedTtl = extractTtlFromRawText(rawText);
    if (extractedTtl) {
      console.warn('������ȡJSON����ʧ�ܣ�����ԭʼ�������ȡ����TTL����');
      return {
        name: '', description: '', classes: [],
        objectProperties: [], dataProperties: [], rules: [],
        ttlContent: cleanTtlContent(extractedTtl),
      };
    }
  }

  return null;
}

/** ��ԭʼ�ı��г�����ȡ TTL ���� */
function extractTtlFromRawText(text: string): string {
  if (!text) return '';

  // 1. ������ȡ ```ttl ... ``` �����
  const ttlBlock = text.match(/```(?:ttl|turtle)?\s*\n?([\s\S]*?)\n?```/i);
  if (ttlBlock && ttlBlock[1].includes('@prefix')) {
    return cleanTtlContent(ttlBlock[1].trim());
  }

  // 2. 尝试提取最后一个 ```ttl ... ``` 代码块（贪婪匹配）
  const lastTtlBlock = text.match(/```(?:ttl|turtle)?\s*\n?([\s\S]*)\n?```$/i);
  if (lastTtlBlock && lastTtlBlock[1].includes('@prefix')) {
    return cleanTtlContent(lastTtlBlock[1].trim());
  }

  // 3. 尝试从 @prefix 开始到文本末尾
  const prefixIdx = text.indexOf('@prefix');
  if (prefixIdx >= 0) {
    // 取从 @prefix 到文本末尾，去除可能残留的 ``` 标记
    let ttl = text.substring(prefixIdx).replace(/```\s*$/g, '').trim();
    if (ttl.includes('owl:Class') || ttl.includes('owl:Ontology') || ttl.includes('rdfs:subClassOf')) {
      return cleanTtlContent(ttl);
    }
  }

  return '';
}

/** ��ʽ���� Markdown ��ʵʱ���� LLM �������� */
export async function importFromMarkdownStream(
  fileId: string,
  providerId: string,
  modelId: string,
  systemPrompt: string,
  onChunk: (content: string) => void,
  onDone: (result: OntologyExtractionResult) => void,
  onError: (error: string) => void,
  signal?: AbortSignal,
  onStatus?: (message: string) => void,
  onWarning?: (message: string) => void,
): Promise<void> {
  const baseURL = '/api/v1';
  const token = getToken();
  const headers: Record<string, string> = {};
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  // 使用 URL 参数传递 fileId 等参数（与 fileToMarkdownStream 相同的方式）
  const params = new URLSearchParams();
  params.append('fileId', fileId);
  if (providerId) params.append('providerId', providerId);
  if (modelId) params.append('modelId', modelId);

  // systemPrompt 可能很长，通过 POST body 传递
  const body = systemPrompt ? JSON.stringify({ systemPrompt }) : undefined;
  if (body) {
    headers['Content-Type'] = 'application/json';
  }

  const res = await fetch(`${baseURL}/ontologies/import-markdown-stream?${params.toString()}`, {
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
            onError(data.message || '��ʽ��ȡʧ��');
            return;
          }
          if (data.done) {
            doneReceived = true;
            const result = safeExtractResult(data);
            if (result) {
              onDone(result as OntologyExtractionResult);
            } else {
              // ��������ʧ�ܣ����Դ�ԭʼ�ı���ȡTTL
              const rawText = data.raw_text || fullContent || '';
              const hint = rawText.length > 200 ? rawText.substring(0, 200) + '...' : rawText;
              console.warn('������ȡJSON����ʧ�ܣ�ԭʼ���ǰ200��:', hint);
              const extractedTtl = extractTtlFromRawText(rawText);
              onDone({
                name: '', description: '', classes: [],
                objectProperties: [], dataProperties: [], rules: [],
                ttlContent: extractedTtl || cleanTtlContent(rawText),
              } as OntologyExtractionResult);
            }
            return;
          }
          if (data.content) {
            fullContent += data.content;
            onChunk(data.content);
          }
          if (data.status) {
            if (onStatus) {
              onStatus(data.message || data.status);
            } else {
              console.log(`提取状态: ${data.message || data.status}`);
            }
          }
          if (data.warning) {
            if (onWarning) {
              onWarning(data.message || '');
            } else {
              console.warn(`提取警告: ${data.message || ''}`);
            }
          }
        } catch {
          // ���Խ�������
        }
      }
    }
  }

  // ���� buffer �п��ܲ��������һ��
  if (buffer.trim().startsWith('data:')) {
    const jsonStr = buffer.trim().slice(5).trim();
    try {
      const data = JSON.parse(jsonStr);
      if (data.error) {
        onError(data.message || '��ʽ��ȡʧ��');
        return;
      }
      if (data.done) {
        doneReceived = true;
        const result = safeExtractResult(data);
        if (result) {
          onDone(result as OntologyExtractionResult);
        } else {
          const rawText = data.raw_text || fullContent || '';
          console.warn('������ȡJSON����ʧ��(buffer)');
          const extractedTtl = extractTtlFromRawText(rawText);
          onDone({
            name: '', description: '', classes: [],
            objectProperties: [], dataProperties: [], rules: [],
            ttlContent: extractedTtl || rawText,
          } as OntologyExtractionResult);
        }
        return;
      }
      if (data.content) {
        fullContent += data.content;
        onChunk(data.content);
      }
      if (data.status) {
        if (onStatus) {
          onStatus(data.message || data.status);
        } else {
          console.log(`提取状态: ${data.message || data.status}`);
        }
      }
      if (data.warning) {
        if (onWarning) {
          onWarning(data.message || '');
        } else {
          console.warn(`提取警告: ${data.message || ''}`);
        }
      }
    } catch {
      // ����
    }
  }

  // ��ʽ������δ�յ� done �¼������Դ��ۻ������н��� JSON ���
  if (!doneReceived && fullContent) {
    const result = tryParseJson(fullContent);
    if (result && typeof result === 'object') {
      const safeResult: any = {
        name: result.name || '',
        description: result.description || '',
        classes: Array.isArray(result.classes) ? result.classes : [],
        objectProperties: Array.isArray(result.objectProperties) ? result.objectProperties : [],
        dataProperties: Array.isArray(result.dataProperties) ? result.dataProperties : [],
        rules: Array.isArray(result.rules) ? result.rules : [],
        ttlContent: cleanTtlContent(result.ttlContent || ''),
      };
      onDone(safeResult as OntologyExtractionResult);
    } else {
      onError('������ȡ��ɵ��������ʧ�ܣ�LLM �����ʽ������ JSON �淶');
    }
  } else if (!doneReceived) {
    onError('��ʽ��ȡ�쳣������δ�յ�����ź�');
  }
}

/** ������ȡ���Ϊ���壨�� TTL �ļ��� */
export async function saveExtraction(ontologyId: string, data: OntologyExtractionResult): Promise<Ontology> {
  const res: any = await client.post(`/ontologies/${ontologyId}/save-extraction`, data);
  return res.data;
}

/** ��ȡ���� TTL ���� */
export async function getTtlContent(ontologyId: string): Promise<string> {
  const res: any = await client.get(`/ontologies/${ontologyId}/ttl`);
  return res.data;
}

/** ���±��� TTL ���� */
export async function updateTtlContent(ontologyId: string, content: string): Promise<void> {
  await client.put(`/ontologies/${ontologyId}/ttl`, { content });
}

/** ִ�� SPARQL ��ѯ */
export async function executeSparqlQuery(ontologyId: string, query: string): Promise<any> {
  const res: any = await client.post(`/ontologies/${ontologyId}/sparql`, { query });
  return res.data;
}

/** ��ȡ����������� */
export async function getObjectTypes(ontologyId: string): Promise<any[]> {
  const res: any = await client.get(`/ontologies/${ontologyId}/object-types`);
  return res.data || [];
}

/** 获取本体个体列表 */
export async function getIndividuals(ontologyId: string): Promise<any[]> {
  const res: any = await client.get(`/ontologies/${ontologyId}/individuals`);
  return res.data || [];
}

/** ������������ */
export async function createObjectType(ontologyId: string, data: { iri: string; label: string; parentIri?: string; description?: string; propertiesJson?: string }): Promise<any> {
  const res: any = await client.post(`/ontologies/${ontologyId}/object-types`, data);
  return res.data;
}

/** ���¶������� */
export async function updateObjectType(ontologyId: string, objectTypeId: string, data: { iri?: string; label?: string; parentIri?: string; description?: string; propertiesJson?: string }): Promise<any> {
  const res: any = await client.put(`/ontologies/${ontologyId}/object-types/${objectTypeId}`, data);
  return res.data;
}

/** ɾ���������� */
export async function deleteObjectType(ontologyId: string, objectTypeId: string): Promise<void> {
  await client.delete(`/ontologies/${ontologyId}/object-types/${objectTypeId}`);
}

/** 更新对象类型位置 */
export async function updateObjectPosition(
  ontologyId: string,
  objectTypeId: string,
  position: { x: number; y: number }
): Promise<any> {
  const res: any = await client.put(`/ontologies/${ontologyId}/object-types/${objectTypeId}/position`, position);
  return res.data;
}

/** ��ȡ������������ */
export async function getLinkTypes(ontologyId: string): Promise<any[]> {
  const res: any = await client.get(`/ontologies/${ontologyId}/link-types`);
  return res.data || [];
}

/** ������������ */
export async function createLinkType(ontologyId: string, data: { iri: string; label: string; sourceType: string; targetType: string; description?: string }): Promise<any> {
  const res: any = await client.post(`/ontologies/${ontologyId}/link-types`, data);
  return res.data;
}

/** ������������ */
export async function updateLinkType(ontologyId: string, linkTypeId: string, data: { iri?: string; label?: string; sourceType?: string; targetType?: string; description?: string }): Promise<any> {
  const res: any = await client.put(`/ontologies/${ontologyId}/link-types/${linkTypeId}`, data);
  return res.data;
}

/** ɾ���������� */
export async function deleteLinkType(ontologyId: string, linkTypeId: string): Promise<void> {
  await client.delete(`/ontologies/${ontologyId}/link-types/${linkTypeId}`);
}

/** ��֤����һ���� */
export async function validateOntology(ontologyId: string): Promise<ValidationReport> {
  const res: any = await client.post(`/ontologies/${ontologyId}/validate`);
  return res.data;
}

/** �Զ��޸�����У����� */
export async function autoFixOntology(ontologyId: string, selectedErrorIndices: number[], selectedWarningIndices?: number[]): Promise<any> {
  const body: any = { selectedErrorIndices };
  if (selectedWarningIndices && selectedWarningIndices.length > 0) {
    body.selectedWarningIndices = selectedWarningIndices;
  }
  const res: any = await client.post(`/ontologies/${ontologyId}/auto-fix`, body);
  return res.data;
}

/** HermiT OWL 2 DL ������֤ */
export async function hermitReason(ontologyId: string): Promise<any> {
  const res: any = await client.post(`/ontologies/${ontologyId}/hermit-reason`);
  if (res.code !== 200) {
    throw new Error(res.message || 'HermiT ����ʧ��');
  }
  return res.data;
}

/** Pellet OWL 2 DL ������֤ */
export async function pelletReason(ontologyId: string): Promise<any> {
  const res: any = await client.post(`/ontologies/${ontologyId}/pellet-reason`);
  if (res.code !== 200) {
    throw new Error(res.message || 'Pellet ����ʧ��');
  }
  return res.data;
}

/** �����ݿ��������ɲ����� TTL �ļ� */
export async function regenerateTtl(ontologyId: string): Promise<void> {
  const res: any = await client.post(`/ontologies/${ontologyId}/regenerate-ttl`);
  if (res.code !== undefined && res.code !== 200) {
    throw new Error(res.message || '�������� TTL ʧ��');
  }
}

/** 导出本体 Markdown 文档 */
export async function exportOntologyMarkdown(ontologyId: string): Promise<Blob> {
  const res = await client.get(`/ontologies/${ontologyId}/export-markdown`, {
    responseType: 'blob',
  });
  return res as unknown as Blob;
}

/** 重命名本体版本 */
export async function renameOntologyVersion(id: string, data: { name?: string; versionName?: string }): Promise<any> {
  const res: any = await client.put(`/ontologies/${id}/rename`, data);
  return res.data;
}

/** �������� */
export async function exportOntology(ontologyId: string, format?: string): Promise<Blob> {
  // responseType: 'blob' ʱ��axios ���������ص� response.data ���� Blob ����
  // �����ٷ��� .data��Blob û�� .data ���ԣ���ֱ�ӷ��� res
  const res = await client.get(`/ontologies/${ontologyId}/export`, {
    params: { format: format || 'TURTLE' },
    responseType: 'blob',
  });
  // res �Ѿ��� response.data����������ȡ�������� Blob
  return res as unknown as Blob;
}

/** ��ȡ����汾�б� */
export async function getOntologyVersions(ontologyId: string): Promise<any[]> {
  const res: any = await client.get(`/ontologies/${ontologyId}/versions`);
  return res.data || [];
}

/** ��ȡ���б���汾���ɰ����ƹ��ˣ� */
export async function getAllOntologyVersions(name?: string): Promise<any[]> {
  const res: any = await client.get('/ontologies/versions', { params: name ? { name } : {} });
  return res.data || [];
}

/** ������ȡ���Ϊ�°汾 */
export async function saveOntologyVersion(data: {
  name: string;
  versionName?: string;
  description?: string;
  classes: any[];
  objectProperties: any[];
  dataProperties: any[];
  rules?: any[];
  ttlContent?: string;
  sourceMdFileName?: string;
  sourceMdFileId?: string;
  sourceModelName?: string;
}): Promise<any> {
  const res: any = await client.post('/ontologies/save-version', data);
  return res.data;
}

/** 直接导入 TTL 本体模型文件 */
export async function importTtlFile(fileId: string, name: string, versionName?: string): Promise<any> {
  const params = new URLSearchParams();
  params.append('fileId', fileId);
  params.append('name', name);
  if (versionName) params.append('versionName', versionName);
  const res: any = await client.post(`/ontologies/import-ttl?${params.toString()}`);
  return res.data;
}

// ==================== ҵ����� API ====================

/** ��ȡ�����ҵ������б� */
export async function getRulesByOntology(ontologyId: string): Promise<any[]> {
  const res: any = await client.get(`/rules/ontology/${ontologyId}`);
  return res.data || [];
}

/** 从 TTL 同步 SWRL 规则 */
export async function syncSwrlRules(ontologyId: string): Promise<any> {
  const res: any = await client.post(`/ontologies/${ontologyId}/sync-swrl-rules`);
  return res.data;
}

/** 从 DB 同步规则到 TTL 文件 */
export async function syncRulesToTtl(ontologyId: string): Promise<any> {
  const res: any = await client.post(`/ontologies/${ontologyId}/sync-rules-to-ttl`);
  return res.data;
}

/** ����ҵ����� */
export async function createRule(data: any): Promise<any> {
  const res: any = await client.post('/rules', data);
  return res.data;
}

/** ����ҵ����� */
export async function updateRule(id: string, data: any): Promise<any> {
  const res: any = await client.put(`/rules/${id}`, data);
  return res.data;
}

/** ɾ��ҵ����� */
export async function deleteRule(id: string): Promise<void> {
  await client.delete(`/rules/${id}`);
}

/** ������� */
export async function activateRule(id: string): Promise<any> {
  const res: any = await client.post(`/rules/${id}/activate`);
  return res.data;
}

/** 停用规则 */
export async function deactivateRule(id: string): Promise<any> {
  const res: any = await client.post(`/rules/${id}/deactivate`);
  return res.data;
}

/** 获取后端统一管理的默认本体提取系统提示词 */
export async function fetchDefaultOntologyExtractionPrompt(): Promise<string> {
  const res = await fetch('/agent/prompts/ontology-extraction-default');
  const data = await res.json();
  return data.prompt || '';
}
