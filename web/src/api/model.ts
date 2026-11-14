import type {
ApiResponse,
CreateModelConfigRequest,
CreateModelProviderRequest,
ModelConfig,
ModelProvider,
} from '@/types';
import client from './client';

/**
 * 模型管理 API
 */

/** 获取所有模型供应商 */
export async function getProviderList(): Promise<ModelProvider[]> {
  const res = await client.get<ApiResponse<ModelProvider[]>>('/model-providers');
  return res.data;
}

/** 获取所有启用的供应商（对外提供服务） */
export async function getActiveProviders(): Promise<ModelProvider[]> {
  const res = await client.get<ApiResponse<ModelProvider[]>>('/model-providers/active');
  return res.data;
}

/** 创建模型供应商 */
export async function createProvider(data: CreateModelProviderRequest): Promise<ModelProvider> {
  const res = await client.post<ApiResponse<ModelProvider>>('/model-providers', data);
  return res.data;
}

/** 更新模型供应商 */
export async function updateProvider(id: string, data: Partial<CreateModelProviderRequest>): Promise<ModelProvider> {
  const res = await client.put<ApiResponse<ModelProvider>>(`/model-providers/${id}`, data);
  return res.data;
}

/** 删除模型供应商 */
export async function deleteProvider(id: string): Promise<void> {
  await client.delete(`/model-providers/${id}`);
}

/** 测试模型供应商连接 */
export async function testProviderConnection(id: string): Promise<{ success: boolean; message: string }> {
  const res = await client.post<ApiResponse<{ success: boolean; message: string }>>(`/model-providers/${id}/test`);
  return res.data;
}

/** 获取供应商下的模型列表 */
export async function getProviderModels(providerId: string): Promise<ModelConfig[]> {
  const res = await client.get<ApiResponse<ModelConfig[]>>(`/model-providers/${providerId}/models`);
  return res.data;
}

/** 添加模型配置 */
export async function createModelConfig(data: CreateModelConfigRequest): Promise<ModelConfig> {
  const res = await client.post<ApiResponse<ModelConfig>>('/model-configs', data);
  return res.data;
}

/** 更新模型配置 */
export async function updateModelConfig(id: string, data: Partial<CreateModelConfigRequest>): Promise<ModelConfig> {
  const res = await client.put<ApiResponse<ModelConfig>>(`/model-configs/${id}`, data);
  return res.data;
}

/** 删除模型配置 */
export async function deleteModelConfig(id: string): Promise<void> {
  await client.delete(`/model-configs/${id}`);
}
