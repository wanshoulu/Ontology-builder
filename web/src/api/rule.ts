/**
 * 逻辑规则管理 API 模块
 * 提供 SWRL 规则 CRUD、词汇表查询、语法校验、测试执行等接口
 */
import type {
BusinessRule,
CreateRuleRequest,
RuleDependencyGraph,
SwrlValidationResult,
SwrlVocabulary,
} from '@/types';
import client from './client';

// ==================== 规则 CRUD ====================

/** 获取指定本体的所有规则 */
export async function getRulesByOntology(ontologyId: string): Promise<BusinessRule[]> {
  const res: any = await client.get(`/rules/ontology/${ontologyId}`);
  return res.data || [];
}

/** 获取单条规则详情 */
export async function getRule(id: string): Promise<BusinessRule> {
  const res: any = await client.get(`/rules/${id}`);
  return res.data;
}

/** 创建规则 */
export async function createRule(data: CreateRuleRequest): Promise<BusinessRule> {
  const res: any = await client.post('/rules', data);
  return res.data;
}

/** 更新规则 */
export async function updateRule(id: string, data: Partial<CreateRuleRequest>): Promise<BusinessRule> {
  const res: any = await client.put(`/rules/${id}`, data);
  return res.data;
}

/** 删除规则 */
export async function deleteRule(id: string): Promise<void> {
  await client.delete(`/rules/${id}`);
}

/** 激活规则 */
export async function activateRule(id: string): Promise<BusinessRule> {
  const res: any = await client.post(`/rules/${id}/activate`);
  return res.data;
}

/** 停用规则 */
export async function deactivateRule(id: string): Promise<BusinessRule> {
  const res: any = await client.post(`/rules/${id}/deactivate`);
  return res.data;
}

/** 统计 SWRL 规则数量 */
export async function countSwrlRules(): Promise<number> {
  const res: any = await client.get('/rules/swrl-count');
  return res.data ?? 0;
}

// ==================== SWRL 词汇表 ====================

/** 获取 SWRL 规则构建所需的完整词汇表 */
export async function getSwrlVocabulary(ontologyId: string): Promise<SwrlVocabulary> {
  const res: any = await client.get(`/ontologies/${ontologyId}/swrl-vocabulary`);
  return res.data;
}

// ==================== SWRL 语法校验 ====================

/** 校验 SWRL 表达式 */
export async function validateSwrl(
  swrlRule: string,
  ontologyId?: string
): Promise<SwrlValidationResult> {
  const res: any = await client.post('/rules/validate-swrl', { swrlRule, ontologyId });
  return res.data;
}

// ==================== TTL 同步 ====================

/** 将数据库中的规则同步到 TTL 文件 */
export async function syncRulesToTtl(ontologyId: string): Promise<{ syncedCount: number }> {
  const res: any = await client.post(`/ontologies/${ontologyId}/sync-rules-to-ttl`);
  return res.data;
}

/** 从 TTL 文件提取规则到数据库 */
export async function syncSwrlRules(ontologyId: string): Promise<{ syncedCount: number; deletedCount: number }> {
  const res: any = await client.post(`/ontologies/${ontologyId}/sync-swrl-rules`);
  return res.data;
}

// ==================== 依赖图（后端就绪后启用） ====================

/** 获取规则依赖图 */
export async function getRuleDependencyGraph(ontologyId: string): Promise<RuleDependencyGraph> {
  const res: any = await client.get(`/rules/ontology/${ontologyId}/dependency-graph`);
  return res.data;
}

// ==================== 规则测试 ====================

/** 测试已保存的规则 */
export async function testRule(ruleId: string): Promise<{
  success: boolean;
  inferredTriples: { subject: string; predicate: string; object: string }[];
  inferredCount: number;
  inputTriplesUsed: number;
  executionTimeMs: number;
  error?: string;
}> {
  const res: any = await client.post(`/rules/${ruleId}/test`);
  return res.data;
}

// ==================== AI 生成 SWRL ====================

/** 使用 LLM 根据自然语言描述生成 SWRL 规则 */
export async function generateSwrl(
  description: string,
  vocabulary: { classes: any[]; properties?: any[] },
  providerId?: string,
  modelId?: string,
  signal?: AbortSignal
): Promise<{ swrl: string }> {
  // Agent 服务在 /agent 路径下，使用 fetch 调用
  const res = await fetch('/agent/generate-swrl', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ description, vocabulary, providerId: providerId || '', modelId: modelId || '' }),
    signal,
  });
  if (!res.ok) throw new Error(`SWRL 生成失败: ${res.status}`);
  return res.json();
}

/** 使用 LLM 解释 SWRL 规则的含义 */
export async function explainSwrl(
  swrl: string,
  vocabulary: { classes: any[] },
  providerId?: string,
  modelId?: string,
  signal?: AbortSignal
): Promise<{ explanation: string }> {
  const res = await fetch('/agent/explain-swrl', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ swrl, vocabulary, providerId: providerId || '', modelId: modelId || '' }),
    signal,
  });
  if (!res.ok) throw new Error(`SWRL 解释失败: ${res.status}`);
  return res.json();
}

/** 临时测试 SWRL 表达式（无需保存） */
export async function testAdHocSwrl(
  swrlRule: string,
  ontologyId: string
): Promise<any> {
  const res: any = await client.post('/rules/test-swrl', { swrlRule, ontologyId });
  return res.data;
}
