/**
 * 本体实例化相关类型定义
 */

// ==================== 实例化流水线相关类型 ====================

/** 流水线任务 */
/** 执行日志单条记录 */
export interface ExecutionLogRecord {
  id: string;
  startedAt: string;
  finishedAt: string;
  status: 'SUCCESS' | 'FAILED';
  jobId: string;
  triplesCount: number;
  errorMessage?: string;
}

export interface PipelineTask {
  id: string;
  tenantId: string;
  /** 目标本体实例ID */
  instanceId?: string;
  /** 本体实例名称（后端解析返回） */
  instanceName?: string;
  /** 本体实例图URI（后端解析返回） */
  instanceGraphUri?: string;
  /** 租户名称（后端解析返回） */
  tenantName?: string;
  name: string;
  version: string;
  /** 模式: db (数据库模式) 或 ttl_only (仅TTL模式) */
  mode: PipelineMode;
  /** TTL文件路径 */
  ttlOriginalPath?: string;
  ttlMergedPath?: string;
  /** base URI */
  baseUri?: string;
  ontologyId?: string;
  datasourceId?: string;
  modelId?: string;
  selectedTables?: string[];
  /** 表→类映射 JSON */
  tableClassMapping?: string;
  /** 字段→属性映射 JSON */
  dataPropMapping?: string;
  /** 对象属性映射 JSON */
  objPropMapping?: string;
  /** Fuseki相关信息 */
  fusekiDatasetName?: string;
  fusekiEndpoint?: string;
  /** 状态 */
  status: PipelineTaskStatus;
  /** 推理进度 (Pellet OWL-DL + SWRL 物化进度) */
  reasoningProgress?: {
    percentage: number;
    phase: string;
  };
  /** 定时调度 */
  scheduleEnabled?: boolean;
  scheduleCron?: string;
  lastScheduledRunAt?: string;
  nextScheduledRunAt?: string;
  /** 时间过滤配置 JSON */
  timeFilterConfig?: TimeFilterConfig | string;
  /** 执行日志记录数组 */
  executionLogs?: ExecutionLogRecord[];
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** db_ttl=数据库+TTL模式, db_only=仅数据库模式, ttl_only=仅TTL模式 */
export type PipelineMode = 'db_ttl' | 'db_only' | 'ttl_only';

/** 需要数据库源的模式 */
export const DB_REQUIRED_MODES: PipelineMode[] = ['db_ttl', 'db_only'];

/** 需要 TTL 生成/预览的模式 */
export const TTL_FLOW_MODES: PipelineMode[] = ['db_ttl', 'ttl_only'];

/** 判断是否为需要数据库的模式（兼容旧值 'db' → 'db_ttl'） */
export function normalizePipelineMode(raw: string | undefined): PipelineMode {
  if (raw === 'db') return 'db_ttl'; // 旧值兼容
  if (raw === 'db_ttl' || raw === 'db_only' || raw === 'ttl_only') return raw;
  return 'db_ttl';
}
export type PipelineTaskStatus =
  | 'init'
  | 'mapping_generated'
  | 'mapping_confirmed'
  | 'ttl_generated'
  | 'reasoning'
  | 'loaded'
  | 'failed'
  | 'rolled_back';

/** 流水线任务步骤 */
export type PipelineStep =
  | 'STEP1_UPLOAD_TTL'
  | 'STEP2_MODEL_MAPPING'
  | 'STEP3_GENERATE'
  | 'STEP4_PREVIEW'
  | 'STEP5_LOAD';

export const PipelineStepMap: Record<PipelineStep, { label: string; number: number }> = {
  STEP1_UPLOAD_TTL: { label: '上传本体与数据源', number: 1 },
  STEP2_MODEL_MAPPING: { label: 'AI映射配置', number: 2 },
  STEP3_GENERATE: { label: '实例生成与合并', number: 3 },
  STEP4_PREVIEW: { label: 'TTL预览与编辑', number: 4 },
  STEP5_LOAD: { label: '加载到Jena TDB2', number: 5 },
};

/** Step1 初始化请求 */
export interface PipelineInitRequest {
  ttlContent?: string;
  mode: PipelineMode;
  ontologyId?: string;
  datasourceId?: string;
  selectedTables?: string[];
  name: string;
  description?: string;
  tenantId?: string;
  instanceId?: string;
}

/** Step1 初始化响应 */
export interface PipelineInitResponse {
  taskId: string;
  classes: PipelineClassInfo[];
  totalDataProperties: number;
  totalObjectProperties: number;
  datasourceInfo?: PipelineDataSourceInfo;
  tables?: PipelineTableInfo[];
  mode: PipelineMode;
}

export interface PipelineClassInfo {
  name: string;
  iri: string;
  dataProps: string[];
  objProps: string[];
}

export interface PipelineDataSourceInfo {
  id: string;
  name: string;
  dbType: string;
}

export interface PipelineTableInfo {
  name: string;
  columns: PipelineColumnInfo[];
  foreignKeys: PipelineForeignKey[];
}

export interface PipelineColumnInfo {
  name: string;
  type: string;
  nullable: boolean;
  primaryKey: boolean;
  comment: string;
}

export interface PipelineForeignKey {
  constrainedColumn: string;
  referencedTable: string;
  referencedColumn: string;
}

/** Step2 映射生成请求 */
export interface PipelineMappingRequest {
  taskId: string;
  modelId: string;
  systemPrompt: string;
}

/** Step2 映射生成响应 */
export interface PipelineMappingResponse {
  dataPropertyMappings: DataPropertyMapping[];
  objectPropertyMappings: ObjectPropertyMappingItem[];
}

export interface DataPropertyMapping {
  className: string;
  classIri?: string;
  propertyIri: string;
  propertyLabel: string;
  column: string;
  columnType: string;
  columnComment: string;
  dataType: string;
  transformRule: string;
  confidence: number;
  reason: string;
  tableName?: string;
}

export interface ObjectPropertyMappingItem {
  className: string;
  /** Legacy display name returned by older mapping endpoints. */
  refClassName?: string;
  classIri?: string;
  /** 源类匹配的数据库表名 */
  sourceTableName?: string;
  /** 源表中作为外键的列名（关联目标表） */
  sourceMatchedColumn?: string;
  /** 兼容旧映射响应字段 */
  sourceColumn?: string;
  objectPropertyIri: string;
  /** 兼容旧映射响应字段 */
  propertyIri?: string;
  objectPropertyLabel: string;
  targetClassIri: string;
  targetClassLabel: string;
  /** 目标类匹配的数据库表名 */
  targetTableName?: string;
  /** 目标表中作为主键/关联键的列名 */
  targetMatchedColumn?: string;
  /** 兼容旧映射响应字段 */
  refColumn?: string;
  /** @deprecated 使用 sourceMatchedColumn 代替 */
  matchedColumn: string;
  confidence: number;
  reason: string;
}

/** Step3 实例生成请求 */
export interface PipelineGenerateRequest {
  taskId: string;
}

/** Step3 异步任务响应 */
export interface PipelineJobResponse {
  jobId: string;
  status: 'pending' | 'processing' | 'success' | 'failed';
}

/** Step3 生成状态 */
export interface PipelineGenerateStatus {
  status: string;
  progress: number;
  previewUrl?: string;
  processedRows?: number;
  totalRows?: number;
  error?: string;
}

/** TTL预览 */
export interface PipelineTtlPreview {
  fileId: string;
  content: string;
  totalLines: number;
}

/** Step5 加载请求 */
export interface PipelineLoadRequest {
  taskId: string;
  overwrite: boolean;
  fusekiBaseUrl?: string;
}

/** Step5 加载结果 */
export interface PipelineLoadResult {
  jobId: string;
  status: string;
  triplesCount: number;
  tdbPath: string;
  fusekiEndpoint: string;
}

/** SPARQL查询 */
export interface SparqlQueryRequest {
  query: string;
}

export interface SparqlQueryResponse {
  results: any;
}

/** 系统提示词模板 */
export interface PromptTemplateInfo {
  defaultSystemPrompt: string;
  placeholders: string[];
}

/** 类-表映射（用户指定） */
export interface ClassTableMapping {
  className: string;
  tableName: string;
  primaryKeyColumn: string;
}

// ==================== 租户相关类型 ====================

/** 租户 */
export interface Tenant {
  id: string;
  name: string;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** 本体实例 — 租户下的一个加载目标，对应 Jena TDB2 中一个命名图 */
export interface OntologyInstance {
  id: string;
  tenantId: string;
  name: string;
  /** 本体实例图 URI */
  graphUri: string;
  ontologyId?: string;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

// ==================== 时间过滤配置 ====================

/** 抽取模式 */
export type ExtractMode = 'full' | 'incremental';

/** 增量模式 */
export type IncrementalMode = 'timeRange' | 'customSql';

/** 时间范围模式 */
export type TimeRangeMode = 'sinceLastRun' | 'relative';

/** 时间范围配置 */
export interface TimeRangeConfig {
  mode: TimeRangeMode;
  value: string;  // e.g. "24h", "7d", "1h"  -- used when mode='relative'
}

/** 列检测模式 */
export type ColumnDetectionMode = 'regex' | 'exact';

/** 列检测配置 */
export interface ColumnDetectionConfig {
  mode: ColumnDetectionMode;
  patterns: string[];        // regex patterns for matching columns
  fallbackColumns: string[]; // exact column names as fallback
}

/** 表过滤配置 */
export interface TableFilterConfig {
  includeAll: boolean;
  specificTables: string[];
}

/** 单表覆盖规则 — 只存与全局默认不同的字段 */
export interface PerTableExtractRule {
  extractMode?: ExtractMode;
  incrementalMode?: IncrementalMode;
  timeRange?: TimeRangeConfig;
  columnDetection?: ColumnDetectionConfig;
  customSql?: string;
}

/** 完整的时间过滤配置 */
export interface TimeFilterConfig {
  /** 抽取模式：全量 / 增量 */
  extractMode: ExtractMode;
  /** 增量模式：时间列过滤 / 自定义SQL */
  incrementalMode: IncrementalMode;
  /** 时间范围配置（incrementalMode=timeRange 时有效） */
  timeRange: TimeRangeConfig;
  /** 列检测配置（incrementalMode=timeRange 时有效） */
  columnDetection: ColumnDetectionConfig;
  /** 自定义SQL模板（incrementalMode=customSql 时有效），支持 {{.Since}} 占位符 */
  customSql: string;
  /** 表过滤配置 */
  tableFilter: TableFilterConfig;
  /** 单表覆盖规则，key 为表名 */
  tableOverrides?: Record<string, PerTableExtractRule>;
}
