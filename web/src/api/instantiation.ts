import type { TimeFilterConfig } from '@/types/instantiation';
import { getToken } from '@/utils/auth';
import client from './client';

export type PipelineLogLevel = 'info' | 'warn' | 'error' | 'success';

function normalizePipelineLog(log: { timestamp: number; level: string; message: string }) {
  const validLevels: PipelineLogLevel[] = ['info', 'warn', 'error', 'success'];
  return {
    ...log,
    level: validLevels.includes(log.level as PipelineLogLevel)
      ? log.level as PipelineLogLevel
      : 'info' as const,
  };
}

/**
 * 本体实例化 API
 */

/** 获取数据源信息（表列表、行数等） */
export async function getDataSourceInfo(datasourceId: string): Promise<any> {
  const res: any = await client.get(`/instantiation/datasource/${datasourceId}/info`);
  return res.data;
}

/** 获取任务列表 */
export async function listTasks(tenantId: string = 'default'): Promise<any[]> {
  const res: any = await client.get('/instantiation/tasks', { params: { tenantId } });
  return res.data || [];
}

/** 获取指定本体下已完成的实例化版本列表 */
export async function listCompletedVersions(ontologyId: string): Promise<any[]> {
  const res: any = await client.get('/instantiation/versions', { params: { ontologyId } });
  return res.data || [];
}

// ==================== 实例化流水线 API ====================

/** Step1: 初始化流水线任务（上传TTL + 选择数据源） */
export async function initPipeline(data: {
  ttlContent?: string;
  mode: string;
  ontologyId?: string;
  datasourceId?: string;
  selectedTables?: string[];
  name: string;
  description?: string;
  tenantId?: string;
  instanceId?: string;
}): Promise<any> {
  const res: any = await client.post('/instantiation/pipeline/init', data);
  return res.data;
}

/** 获取流水线任务详情 */
export async function getPipelineTask(taskId: string): Promise<any> {
  const res: any = await client.get(`/instantiation/pipeline/tasks/${taskId}`);
  return res.data;
}

/** 获取流水线任务列表（不传tenantId则返回所有租户） */
export async function listPipelineTasks(tenantId?: string): Promise<any[]> {
  const params: any = {};
  if (tenantId) params.tenantId = tenantId;
  const res: any = await client.get('/instantiation/pipeline/tasks', { params });
  return Array.isArray(res.data) ? res.data : [];
}

/** 删除流水线任务 */
export async function deletePipelineTask(taskId: string): Promise<void> {
  await client.delete(`/instantiation/pipeline/tasks/${taskId}`);
}

/** 批量删除流水线任务 */
export async function batchDeletePipelineTasks(taskIds: string[]): Promise<{
  total: number;
  deleted: number;
  failed: number;
  errors: { taskId: string; error: string }[];
}> {
  const res: any = await client.delete('/instantiation/pipeline/tasks/batch', {
    data: taskIds,
  });
  return res.data;
}

/** Step2: 生成映射（调用LLM） */
export async function generateMapping(data: {
  taskId: string;
  modelId: string;
  systemPrompt: string;
}): Promise<any> {
  const res: any = await client.post('/instantiation/pipeline/mapping/generate', data);
  return res.data;
}

/** Step2: 流式生成映射（SSE），支持实时输出 */
export function createPipelineMappingSSE(
  taskId: string,
  params: { providerId?: string; modelId?: string; systemPrompt?: string },
  handlers: {
    onEvent: (event: string, data: any) => void;
    onError: (error: any) => void;
    onComplete: () => void;
  },
): { close: () => void } {
  const abortController = new AbortController();
  const baseUrl = '/api/v1';
  const query = new URLSearchParams({ taskId });
  if (params.providerId) query.append('providerId', params.providerId);
  if (params.modelId) query.append('modelId', params.modelId);
  const url = `${baseUrl}/instantiation/pipeline/mapping/generate-stream?${query.toString()}`;

  const bodyParts: Record<string, string> = {};
  if (params.systemPrompt) bodyParts.systemPrompt = params.systemPrompt;
  const body = Object.keys(bodyParts).length > 0 ? JSON.stringify(bodyParts) : undefined;

  let streamFinished = false;

  fetch(url, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${getToken()}`,
      Accept: 'text/event-stream',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body,
    signal: abortController.signal,
  }).then(async (response) => {
    if (!response.ok) {
      handlers.onError(new Error(`HTTP ${response.status}`));
      return;
    }
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let eventName = '';
    let dataLines: string[] = [];
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventName = line.substring(6).trim();
          } else if (line.startsWith('data:')) {
            dataLines.push(line.substring(5).trim());
          } else if (line === '' && dataLines.length > 0) {
            const dataStr = dataLines.join('\n');
            if (eventName === 'done' || eventName === 'result') streamFinished = true;
            try {
              handlers.onEvent(eventName, JSON.parse(dataStr));
            } catch {
              handlers.onEvent(eventName, dataStr);
            }
            eventName = '';
            dataLines = [];
          }
        }
      }
      if (dataLines.length > 0) {
        if (eventName === 'done' || eventName === 'result') streamFinished = true;
        const dataStr = dataLines.join('\n');
        try {
          handlers.onEvent(eventName, JSON.parse(dataStr));
        } catch {
          handlers.onEvent(eventName, dataStr);
        }
      }
      streamFinished = true;
      handlers.onComplete();
    } catch (streamErr: any) {
      if (streamFinished && streamErr?.message === 'network error') {
        handlers.onComplete();
      } else if (!streamFinished) {
        handlers.onError(streamErr);
      } else {
        handlers.onError(streamErr);
      }
    }
  }).catch((e: any) => {
    if (e.name !== 'AbortError' && !streamFinished) {
      handlers.onError(e);
    }
  });

  return {
    close: () => abortController.abort(),
  };
}

/** Step2: 获取默认系统提示词模板 */
export async function getDefaultSystemPrompt(taskId: string): Promise<any> {
  const res: any = await client.get(`/instantiation/pipeline/prompt-template/${taskId}`);
  return res.data;
}

/** Step2: 保存映射确认 */
export async function saveMappingConfirm(taskId: string, mapping: {
  dataPropertyMappings: any[];
  objectPropertyMappings: any[];
}): Promise<any> {
  const res: any = await client.put(`/instantiation/pipeline/mapping/${taskId}`, mapping);
  return res.data;
}

/** Step3: 触发生成实例 */
export async function triggerGenerate(data: { taskId: string }): Promise<any> {
  const res: any = await client.post('/instantiation/pipeline/generate', data);
  return res.data;
}

/** Step3: 查询生成状态 */
export async function getGenerateStatus(jobId: string): Promise<any> {
  const res: any = await client.get(`/instantiation/pipeline/generate/status/${jobId}`);
  return res.data;
}

/** Step3: SSE 流式输出实例生成日志 */
export function streamGenerateLogs(
  jobId: string,
  handlers: {
    onLog: (log: { timestamp: number; level: PipelineLogLevel; message: string }) => void;
    onDone: (result: { status: string; progress: number; error?: string }) => void;
    onError: (error: any) => void;
  },
): { close: () => void } {
  const abortController = new AbortController();
  const baseUrl = '/api/v1';
  const url = `${baseUrl}/instantiation/pipeline/generate/stream/${jobId}`;

  let streamFinished = false;

  fetch(url, {
    method: 'GET',
    headers: {
      Authorization: `Bearer ${getToken()}`,
      Accept: 'text/event-stream',
    },
    signal: abortController.signal,
  }).then(async (response) => {
    if (!response.ok) {
      handlers.onError(new Error(`HTTP ${response.status}`));
      return;
    }
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let eventName = '';
    let dataLines: string[] = [];

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventName = line.substring(6).trim();
          } else if (line.startsWith('data:')) {
            dataLines.push(line.substring(5).trim());
          } else if (line === '' && dataLines.length > 0) {
            const dataStr = dataLines.join('\n');
            try {
              const parsed = JSON.parse(dataStr);
              if (eventName === 'log') {
                handlers.onLog(normalizePipelineLog(parsed));
              } else if (eventName === 'done') {
                streamFinished = true;
                handlers.onDone(parsed);
              }
            } catch {
              // ignore unparseable
            }
            eventName = '';
            dataLines = [];
          }
        }
      }
      if (dataLines.length > 0) {
        const dataStr = dataLines.join('\n');
        try {
          const parsed = JSON.parse(dataStr);
          if (eventName === 'done') {
            streamFinished = true;
            handlers.onDone(parsed);
          }
        } catch { /* ignore */ }
      }
      handlers.onError(new Error('SSE stream ended unexpectedly'));
    } catch (streamErr: any) {
      if (streamFinished && streamErr?.message === 'network error') {
        // normal close after done
      } else if (!streamFinished) {
        handlers.onError(streamErr);
      }
    }
  }).catch((e: any) => {
    if (e.name !== 'AbortError' && !streamFinished) {
      handlers.onError(e);
    }
  });

  return { close: () => abortController.abort() };
}

/** Step4: 获取TTL预览 */
export async function getTtlPreview(fileId: string, offset: number = 0, limit: number = 50000): Promise<any> {
  const res: any = await client.get(`/instantiation/pipeline/preview/${fileId}`, {
    params: { offset, limit },
  });
  return res.data;
}

/** Step4: 保存用户编辑后的TTL */
export async function saveTtlContent(fileId: string, content: string): Promise<any> {
  const res: any = await client.put(`/instantiation/pipeline/preview/${fileId}`, { content });
  return res.data;
}

/** Step5: 加载到Jena TDB2 */
export async function loadToJena(data: {
  taskId: string;
  overwrite: boolean;
  fusekiBaseUrl?: string;
}): Promise<any> {
  const res: any = await client.post('/instantiation/pipeline/load', data);
  return res.data;
}

/** Step5: 查询加载状态 */
export async function getLoadStatus(jobId: string): Promise<any> {
  const res: any = await client.get(`/instantiation/pipeline/load/status/${jobId}`);
  return res.data;
}

/** Step5: SSE流式获取加载日志 */
export function streamLoadLogs(
  jobId: string,
  handlers: {
    onLog: (log: { timestamp: number; level: PipelineLogLevel; message: string }) => void;
    onDone: (result: { status: string; progress: number; error?: string }) => void;
    onError: (error: any) => void;
  },
): { close: () => void } {
  const abortController = new AbortController();
  const baseUrl = '/api/v1';
  const url = `${baseUrl}/instantiation/pipeline/load/stream/${jobId}`;

  let streamFinished = false;

  fetch(url, {
    method: 'GET',
    headers: {
      Authorization: `Bearer ${getToken()}`,
      Accept: 'text/event-stream',
    },
    signal: abortController.signal,
  }).then(async (response) => {
    if (!response.ok) {
      handlers.onError(new Error(`HTTP ${response.status}`));
      return;
    }
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let eventName = '';
    let dataLines: string[] = [];

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventName = line.substring(6).trim();
          } else if (line.startsWith('data:')) {
            dataLines.push(line.substring(5).trim());
          } else if (line === '' && dataLines.length > 0) {
            const dataStr = dataLines.join('\n');
            try {
              const parsed = JSON.parse(dataStr);
              if (eventName === 'log') {
                handlers.onLog(normalizePipelineLog(parsed));
              } else if (eventName === 'done') {
                streamFinished = true;
                handlers.onDone(parsed);
              }
            } catch {
              // ignore unparseable
            }
            eventName = '';
            dataLines = [];
          }
        }
      }
      if (dataLines.length > 0) {
        const dataStr = dataLines.join('\n');
        try {
          const parsed = JSON.parse(dataStr);
          if (eventName === 'done') {
            streamFinished = true;
            handlers.onDone(parsed);
          }
        } catch { /* ignore */ }
      }
      if (!streamFinished) {
        handlers.onError(new Error('SSE stream ended unexpectedly'));
      }
    } catch (streamErr: any) {
      if (streamFinished && streamErr?.message === 'network error') {
        // normal close after done
      } else if (!streamFinished) {
        handlers.onError(streamErr);
      }
    }
  }).catch((e: any) => {
    if (e.name !== 'AbortError' && !streamFinished) {
      handlers.onError(e);
    }
  });

  return { close: () => abortController.abort() };
}

/** SPARQL 查询代理 */
export async function executeSparqlQuery(taskId: string, query: string): Promise<any> {
  const res: any = await client.post(`/instantiation/pipeline/sparql/${taskId}`, { query });
  return res.data;
}

/** 更新流水线任务 */
export async function updatePipelineTask(taskId: string, data: any): Promise<any> {
  const res: any = await client.put(`/instantiation/pipeline/tasks/${taskId}`, data);
  return res.data;
}

// ==================== 定时调度 ====================

/** 配置/更新流水线定时调度（含时间过滤配置） */
export async function updatePipelineSchedule(
  taskId: string,
  data: {
    scheduleEnabled: boolean;
    scheduleCron: string;
    timeFilterConfig?: TimeFilterConfig;
  },
): Promise<any> {
  const res: any = await client.put(`/instantiation/pipeline/tasks/${taskId}/schedule`, data);
  return res.data;
}

/** 查询流水线定时调度状态 */
export async function getPipelineSchedule(taskId: string): Promise<any> {
  const res: any = await client.get(`/instantiation/pipeline/tasks/${taskId}/schedule`);
  return res.data;
}

/** 立即执行一次流水线任务（测试定时调度） */
export async function triggerRunNow(taskId: string): Promise<any> {
  const res: any = await client.post(`/instantiation/pipeline/tasks/${taskId}/run-now`);
  return res.data;
}

// ==================== 流水线 TTL 上传 ====================

/** 上传TTL内容到Agent临时目录 */
export async function uploadPipelineTtl(taskId: string, content: string): Promise<any> {
  const res: any = await client.post('/instantiation/pipeline/ttl-upload', {
    task_id: taskId,
    content,
  });
  return res.data;
}

/** 从平台获取本体TTL并写入Agent临时目录 */
export async function fetchOntologyTtlForPipeline(taskId: string, ontologyId: string): Promise<any> {
  const res: any = await client.post('/instantiation/pipeline/ttl-fetch-ontology', {
    task_id: taskId,
    ontology_id: ontologyId,
  });
  return res.data;
}

// ==================== 租户管理 API ====================

/** 获取租户列表 */
export async function listTenants(): Promise<any[]> {
  const res: any = await client.get('/tenants');
  return res.data || [];
}

/** 获取租户详情 */
export async function getTenant(id: string): Promise<any> {
  const res: any = await client.get(`/tenants/${id}`);
  return res.data;
}

/** 创建租户 */
export async function createTenant(data: {
  name: string;
  description?: string;
}): Promise<any> {
  const res: any = await client.post('/tenants', data);
  return res.data;
}

/** 更新租户 */
export async function updateTenant(id: string, data: any): Promise<any> {
  const res: any = await client.put(`/tenants/${id}`, data);
  return res.data;
}

/** 删除租户 */
export async function deleteTenant(id: string): Promise<void> {
  await client.delete(`/tenants/${id}`);
}

// ==================== 本体实例管理 API ====================

/** 获取所有本体实例 */
export async function listInstances(): Promise<any[]> {
  const res: any = await client.get('/instances');
  return res.data || [];
}

/** 获取指定租户下的本体实例列表 */
export async function listInstancesByTenant(tenantId: string): Promise<any[]> {
  const res: any = await client.get(`/instances/tenant/${tenantId}`);
  return res.data || [];
}

/** 获取本体实例详情 */
export async function getInstance(id: string): Promise<any> {
  const res: any = await client.get(`/instances/${id}`);
  return res.data;
}

/** 创建本体实例 */
export async function createInstance(data: {
  tenantId: string;
  name: string;
  graphUri: string;
  ontologyId?: string;
  description?: string;
}): Promise<any> {
  const res: any = await client.post('/instances', data);
  return res.data;
}

/** 更新本体实例 */
export async function updateInstance(id: string, data: any): Promise<any> {
  const res: any = await client.put(`/instances/${id}`, data);
  return res.data;
}

/** 删除本体实例 */
export async function deleteInstance(id: string): Promise<void> {
  await client.delete(`/instances/${id}`);
}

/** 清空本体实例数据（保留实例记录，仅清除命名图数据） */
export async function clearInstanceData(id: string): Promise<void> {
  await client.delete(`/instances/${id}/data`);
}
