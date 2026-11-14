/** API 统一响应 */
export interface Result<T = void> {
  code: number;
  message: string;
  data: T;
  timestamp: string;
}

/** 兼容别名 */
export type ApiResponse<T = void> = Result<T>;

/** 分页结果 */
export interface PageResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** 数据源 */
export interface DataSource {
  id: string;
  tenantId: string;
  name: string;
  type: 'jdbc' | 'rest' | 'file' | 'mq';
  configJson: string;
  status: 'connected' | 'disconnected' | 'error';
  lastConnectedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateDataSourceRequest {
  name: string;
  type: DataSource['type'];
  configJson: string;
  tenantId?: string;
}

/** 本体 */
export interface Ontology {
  id: string;
  tenantId: string;
  name: string;
  description: string;
  version: string;
  versionName?: string;
  industryOntology: 'fibo' | 'iof' | 'custom' | 'none';
  status: 'draft' | 'active' | 'deprecated';
  classCount: number;
  propertyCount: number;
  ttlPath?: string;
  ttlContent?: string;
  extractedAt?: string;
  instantiated?: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateOntologyRequest {
  name: string;
  description?: string;
  industryOntology?: 'fibo' | 'iof' | 'custom' | 'none';
}

/** 对象类型 */
export interface ObjectType {
  id: string;
  ontologyId: string;
  iri: string;
  label: string;
  parentIri?: string;
  description?: string;
  properties?: ObjectProperty[];
  propertiesJson?: string;
  positionJson?: string;
  createdAt: string;
}

export interface CreateObjectTypeRequest {
  ontologyId: string;
  iri: string;
  label: string;
  parentIri?: string;
  description?: string;
}

/** 对象属性 */
export interface ObjectProperty {
  iri: string;
  label: string;
  dataType: string;
  description?: string;
}

/** 链接类型 */
export interface LinkType {
  id: string;
  ontologyId: string;
  iri: string;
  label: string;
  sourceType?: string;
  targetType?: string;
  description?: string;
  createdAt: string;
}

export interface CreateLinkTypeRequest {
  ontologyId: string;
  iri: string;
  label: string;
  sourceType?: string;
  targetType?: string;
  description?: string;
}

/** 本体提取结果（LLM 从 Markdown 提取） */
export interface OntologyExtractionResult {
  name: string;
  description: string;
  classes: ExtractedClass[];
  objectProperties: ExtractedProperty[];
  dataProperties: ExtractedProperty[];
  rules: ExtractedRule[];
  ttlContent: string;
}

export interface ExtractedClass {
  iri: string;
  label: string;
  parentIri?: string;
  description?: string;
}

export interface ExtractedProperty {
  iri: string;
  label: string;
  domain?: string;
  range?: string;
  description?: string;
}

export interface ExtractedRule {
  name: string;
  condition: string;
  action: string;
  swrlRule?: string;
  involvedClasses?: string[];
  involvedDataProperties?: string[];
  description?: string;
}

/** SPARQL 查询结果 */
export interface SparqlQueryResult {
  variables: string[];
  bindings: Record<string, { type: string; value: string }>[];
}

/** 验证报告 */
export interface ValidationReport {
  valid: boolean;
  errors: ValidationIssue[];
  warnings: ValidationIssue[];
}

export interface ValidationIssue {
  type: string;
  message: string;
  source?: string;
}

/** 模型供应商 */
export interface ModelProvider {
  id: string;
  name: string;
  type: 'deepseek' | 'qwen' | 'openai' | 'zhipu' | 'custom';
  apiUrl: string;
  apiKey: string;
  status: 'active' | 'inactive';
  models: ModelConfig[];
  createdAt: string;
  updatedAt: string;
}

export interface CreateModelProviderRequest {
  name: string;
  type: 'deepseek' | 'qwen' | 'openai' | 'zhipu' | 'custom';
  apiUrl: string;
  apiKey: string;
}

/** 模型配置 */
export interface ModelConfig {
  id: string;
  providerId: string;
  modelId: string;
  name: string;
  maxTokens: number;
  temperature: number;
  capabilities: string[];
  createdAt: string;
}

export interface CreateModelConfigRequest {
  providerId: string;
  modelId: string;
  name: string;
  maxTokens?: number;
  temperature?: number;
  capabilities?: string[];
}

/** 业务规则 */
export interface BusinessRule {
  id: string;
  ontologyId: string;
  name: string;
  description?: string;
  type: 'condition' | 'event' | 'temporal' | 'composite' | 'swrl';
  priority: 'critical' | 'high' | 'medium' | 'low';
  conditionJson: string;
  actionJson: string;
  swrlRule?: string;
  involvedClasses?: string;
  involvedDataProperties?: string;
  source?: string;
  confidence: number;
  status: 'draft' | 'active' | 'deprecated';
  createdAt: string;
  updatedAt: string;
}

export interface CreateRuleRequest {
  ontologyId: string;
  name: string;
  description?: string;
  type: 'condition' | 'event' | 'temporal' | 'composite' | 'swrl';
  priority: 'critical' | 'high' | 'medium' | 'low';
  conditionJson: string;
  actionJson: string;
  swrlRule?: string;
  involvedClasses?: string;
  involvedDataProperties?: string;
  source?: string;
  confidence?: number;
}

/** SWRL 词汇表（供规则构建器使用） */
export interface SwrlVocabulary {
  classes: SwrlVocabItem[];
  objectProperties: SwrlVocabItem[];
  dataProperties: SwrlVocabItem[];
  builtins: SwrlBuiltinCategory[];
}

export interface SwrlVocabItem {
  id?: string;
  iri: string;
  label: string;
  shortName: string;
  domain?: string;
  range?: string;
  datatype?: string;
  description?: string;
}

export interface SwrlBuiltinCategory {
  category: string;
  label: string;
  icon: string;
  functions: SwrlBuiltinFunction[];
}

export interface SwrlBuiltinFunction {
  iri: string;
  shortName: string;
  displayName: string;
  description: string;
  argCount: number;
  argLabels: string[];
  category: string;
  categoryLabel: string;
  support: 'FULL' | 'OXIRS_ONLY' | 'JENA_ONLY';
  usageExample: string;
}

/** SWRL Atom（可视化构建器的数据结构） */
export type SwrlAtomType = 'CLASS' | 'OBJECT_PROPERTY' | 'DATA_PROPERTY' | 'BUILTIN' | 'SAME_AS' | 'DIFFERENT_FROM';

export interface SwrlAtom {
  id: string;
  type: SwrlAtomType;
  className?: string;
  propertyName?: string;
  builtinName?: string;
  variable1?: string;
  variable2?: string;
  variable2Type?: 'VARIABLE' | 'LITERAL';
  dataType?: string;
  args?: SwrlBuiltinArg[];
}

export interface SwrlBuiltinArg {
  type: 'VARIABLE' | 'LITERAL';
  value: string;
  dataType?: string;
}

/** SWRL 校验结果 */
export interface SwrlValidationResult {
  valid: boolean;
  errors: SwrlValidationIssue[];
  warnings: SwrlValidationIssue[];
  parsedAtoms?: {
    body: any[];
    head: any[];
  };
  variables?: string[];
}

export interface SwrlValidationIssue {
  type: string;
  message: string;
  position?: number;
}

/** 规则模板 */
export interface RuleTemplate {
  key: string;
  name: string;
  description: string;
  category: string;
  body: SwrlAtom[];
  head: SwrlAtom[];
  variables: string[];
  swrlTemplate: string;
}

/** 规则依赖图 */
export interface RuleDependencyGraph {
  nodes: { id: string; label: string; type: string; status: string; fcBc: string }[];
  edges: { from: string; to: string; label: string }[];
  fcCount: number;
  bcCount: number;
}

/** Skill */
export interface Skill {
  id: string;
  ontologyId?: string;
  name: string;
  description: string;
  version?: string;
  sourceCatalogId?: string;
  sourceDigest?: string;
  markdownContent?: string;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  inputSchemaJson?: string;
  outputSchemaJson?: string;
  stepsJson?: string;
  steps: SkillStep[];
  executionMode: 'AI_AGENT' | 'API_CALL' | 'HUMAN';
  status: 'DRAFT' | 'ACTIVE' | 'DEPRECATED';
  createdAt: string;
  updatedAt?: string;
}

export interface SkillStep {
  id?: string;
  name: string;
  description?: string;
  dependencies?: string[];
  retryCount?: number;
  timeout?: number;
  agentConfig?: Record<string, unknown>;
}

export interface CreateSkillRequest {
  ontologyId?: string;
  name: string;
  description?: string;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  steps?: SkillStep[];
  executionMode: Skill['executionMode'];
}

export interface ExecuteSkillRequest {
  skillId: string;
  inputs: Record<string, unknown>;
}

export interface ExecuteSkillStepResult {
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  duration?: number;
  error?: string;
}

export interface ExecuteSkillResult {
  executionId: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  duration?: number;
  steps: ExecuteSkillStepResult[];
  outputs?: Record<string, unknown>;
  error?: string;
}

/** 构建任务 */
export type AgentPhase = 'INIT' | 'PLAN' | 'EXECUTE' | 'VERIFY';

export interface BuildOptions {
  enableLLM: boolean;
  autoVerify: boolean;
  incremental: boolean;
  industryTemplate?: string;
  model?: string;
}

export interface BuildStep {
  name: string;
  description?: string;
  phase: AgentPhase;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  duration?: number;
  output?: string;
  error?: string;
}

export interface BuildTask {
  id: string;
  ontologyId?: string;
  ontologyName?: string;
  name: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  progressJson?: string;
  sourcesJson?: string;
  optionsJson?: string;
  estimatedMinutes?: number;
  dataSourceIds: string[];
  currentPhase: AgentPhase;
  progress: number;
  steps: BuildStep[];
  options: BuildOptions;
  error?: string;
  startedAt?: string;
  completedAt?: string;
  createdAt: string;
}

export interface CreateBuildTaskRequest {
  name: string;
  ontologyId?: string;
  dataSourceIds: string[];
  documentIds: string[];
  options: BuildOptions;
}

export interface LoginRequest { username: string; password: string; }
export interface RegisterRequest { username: string; password: string; email?: string; role: string; }
export interface LoginResponse { token: string; username: string; role: string; tenantId: string; }
export type UserInfo = User;

export interface QueryResult {
  columns: string[];
  rows: Record<string, unknown>[];
  total: number;
}

export interface NLQueryRequest { query: string; ontologyId?: string; }
export interface NLQueryResponse {
  id: string;
  query: string;
  sparql: string;
  answer?: string;
  results: QueryResult;
  duration: number;
  createdAt: string;
}

/** 表信息 */
export interface TableInfo {
  tableName: string;
  schema?: string;
  columns?: Array<{ name: string; type: string; nullable?: boolean; comment?: string }>;
  primaryKey?: string[];
  foreignKeys?: Array<{ column: string; targetTable: string; targetColumn: string }>;
}

/** 表数据 */
export interface TableData {
  rows: Record<string, any>[];
  total: number;
  columns: string[];
}

/** 用户信息 */
export interface User {
  id: string;
  username: string;
  email?: string;
  role: string;
  tenantId?: string;
  status: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateUserRequest {
  username: string;
  password: string;
  email?: string;
  role: string;
  tenantId?: string;
}

export interface UpdateUserRequest {
  email?: string;
  role?: string;
  status?: string;
  tenantId?: string;
}

export interface ResetPasswordRequest {
  newPassword: string;
}

/** 知识库管理 */

export type KnowledgeBaseCategory = 'rag' | 'graph';

export type RagBackend = 'chroma' | 'pinecone' | 'weaviate' | 'qdrant' | 'milvus' | 'pgvector';
export type GraphBackend = 'neo4j' | 'falkordb' | 'apache_age' | 'hugegraph';
export type KnowledgeBaseBackend = RagBackend | GraphBackend | 'local_rag' | 'local_graph';

export interface KnowledgeBase {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  category: KnowledgeBaseCategory;
  backend: KnowledgeBaseBackend;
  configJson: string;
  status: 'connected' | 'disconnected' | 'error';
  lastConnectedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface PineconeConfig {
  environment: string;
  api_key: string;
  index_name: string;
}

export interface WeaviateConfig {
  url: string;
  api_key: string;
  index_name: string;
}

export interface QdrantConfig {
  host: string;
  port: number;
  grpc_port?: number;
  api_key?: string;
}

export interface MilvusConfig {
  host: string;
  port: number;
  user?: string;
  password?: string;
}

export interface PgVectorConfig {
  connection_string: string;
}

export interface Neo4jConfig {
  uri: string;
  user: string;
  password: string;
}

export interface FalkorDBConfig {
  host: string;
  port: number;
  graph_name: string;
  username?: string;
  password?: string;
}

export interface ApacheAgeConfig {
  connection_string: string;
  graph_name?: string;
}

export interface HugeGraphConfig {
  url: string;
  graph: string;
  user?: string;
  password?: string;
  gremlin_endpoint?: string;
}

export type KnowledgeBaseConfig =
  | PineconeConfig
  | WeaviateConfig
  | QdrantConfig
  | MilvusConfig
  | PgVectorConfig
  | Neo4jConfig
  | FalkorDBConfig
  | ApacheAgeConfig
  | HugeGraphConfig;
