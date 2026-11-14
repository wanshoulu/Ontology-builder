import { listInstances,listPipelineTasks,listTenants } from '@/api/instantiation';
import { executeSparqlQuery,getLinkTypes,getObjectTypes,getRulesByOntology } from '@/api/ontology';
import {
ApartmentOutlined,
ArrowRightOutlined,
CloudServerOutlined,
CodeOutlined,
CopyOutlined,
DatabaseOutlined,
ExpandOutlined,
HistoryOutlined,
InfoCircleOutlined,ReloadOutlined,
SafetyCertificateOutlined,
SearchOutlined,
ShareAltOutlined,
TableOutlined,
TeamOutlined,
} from '@ant-design/icons';
import {
Alert,
Badge,
Button,
Card,
Col,
Descriptions,
Divider,
Empty,
Input,
Row,
Select,Space,
Spin,
Statistic,
Table,
Tabs,
Tag,
Tooltip,
Typography,
message
} from 'antd';
import React,{ useCallback,useEffect,useMemo,useRef,useState } from 'react';
import { Network } from 'vis-network/standalone';

const { Text } = Typography;
const { TextArea } = Input;

/** 节点颜色方案 */
const NODE_COLORS = [
  '#1677ff', '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16',
  '#52c41a', '#2f54eb', '#faad14', '#f5222d', '#1d39c4',
  '#9254de', '#36cfc9', '#ff85c0', '#ffc53d', '#73d13d',
];

interface InstantiatedVersion {
  id: string;
  taskId?: string;
  name: string;
  ontologyId: string;
  version?: string;
  versionName?: string;
  jenaModelName?: string;
  instanceId?: string;
  status?: string;
  description?: string;
  createdAt?: string;
  resultInfo?: string;
  tenantId?: string;
}

/** SPARQL 查询预设模板 */
const SPARQL_TEMPLATES = [
  {
    label: '所有类',
    query: `PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>

SELECT DISTINCT ?class ?label ?parentClass ?classType WHERE {
  ?class a owl:Class .
  FILTER(isURI(?class))
  FILTER(!STRSTARTS(STR(?class), "http://www.w3.org/2002/07/owl#"))
  FILTER(!STRSTARTS(STR(?class), "http://www.w3.org/1999/02/22-rdf-syntax-ns#"))
  FILTER(!STRSTARTS(STR(?class), "http://www.w3.org/2000/01/rdf-schema#"))
  OPTIONAL { ?class rdfs:label ?label }
  OPTIONAL { ?class rdfs:subClassOf ?parentClass . FILTER(isURI(?parentClass)) FILTER(!STRSTARTS(STR(?parentClass), "http://www.w3.org/")) }
  BIND(IF(EXISTS { ?child rdfs:subClassOf ?class }, IF(BOUND(?parentClass), "中间类", "超类"), IF(BOUND(?parentClass), "继承类", "独立类")) AS ?classType)
}
ORDER BY ?class
LIMIT 500`,
  },
  {
    label: '所有实例',
    query: `PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n\nSELECT ?instance ?type WHERE {\n  ?instance a ?type .\n  FILTER(!isBlank(?instance))\n}\nLIMIT 200`,
  },
  {
    label: '类实例统计',
    query: `PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\nPREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n\nSELECT ?type ?label (COUNT(?instance) AS ?count) WHERE {\n  ?instance a ?type .\n  FILTER(!isBlank(?instance))\n  OPTIONAL { ?type rdfs:label ?label . FILTER(LANG(?label) = '' || LANGMATCHES(LANG(?label), 'zh')) }\n}\nGROUP BY ?type ?label\nORDER BY DESC(?count)\nLIMIT 50`,
  },
  {
    label: '所有对象属性',
    query: `PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\nPREFIX owl: <http://www.w3.org/2002/07/owl#>\n\nSELECT DISTINCT ?prop ?label WHERE {\n  { ?prop a owl:ObjectProperty }\n  UNION\n  { ?s ?prop ?o . FILTER(!isLiteral(?o)) FILTER(!isBlank(?o)) FILTER(STR(?prop) != "http://www.w3.org/1999/02/22-rdf-syntax-ns#type") }\n  OPTIONAL { ?prop rdfs:label ?label }\n}\nLIMIT 100`,
  },
  {
    label: '所有数据属性',
    query: `PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\nPREFIX owl: <http://www.w3.org/2002/07/owl#>\n\nSELECT DISTINCT ?prop ?label WHERE {\n  { ?prop a owl:DatatypeProperty }\n  UNION\n  { ?s ?prop ?o . FILTER(isLiteral(?o)) FILTER(STR(?prop) != "http://www.w3.org/1999/02/22-rdf-syntax-ns#type") }\n  OPTIONAL { ?prop rdfs:label ?label }\n}\nLIMIT 100`,
  },
  {
    label: 'SWRL规则',
    query: `PREFIX swrl: <http://www.w3.org/2003/11/swrl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?rule ?ruleName
  (GROUP_CONCAT(DISTINCT ?className; separator=" | ") AS ?classNames)
  (GROUP_CONCAT(DISTINCT ?class; separator=" | ") AS ?classIris)
  (GROUP_CONCAT(DISTINCT ?bodyAtomStr; separator=" ∧ ") AS ?bodyExpr)
  (GROUP_CONCAT(DISTINCT ?headAtomStr; separator=", ") AS ?headExpr)
WHERE {
  ?rule a swrl:Imp .
  OPTIONAL { ?rule rdfs:label ?label }
  OPTIONAL { ?rule rdfs:comment ?comment }
  BIND(COALESCE(?label, ?comment) AS ?ruleName)
  OPTIONAL {
    ?rule swrl:body ?bodyList .
    ?bodyList rdf:rest*/rdf:first ?bodyAtom .
    {
      ?bodyAtom a swrl:ClassAtom .
      ?bodyAtom swrl:classPredicate ?bodyClass .
      BIND(STR(?bodyClass) AS ?bodyAtomStr)
    } UNION {
      ?bodyAtom a swrl:DatavaluedPropertyAtom .
      ?bodyAtom swrl:propertyPredicate ?bodyProp .
      ?bodyAtom swrl:argument1 ?bodyArg1 .
      ?bodyAtom swrl:argument2 ?bodyArg2 .
      BIND(CONCAT("data:", STR(?bodyProp), "(", STR(?bodyArg1), ",", STR(?bodyArg2), ")") AS ?bodyAtomStr)
    } UNION {
      ?bodyAtom a swrl:IndividualPropertyAtom .
      ?bodyAtom swrl:propertyPredicate ?bodyProp .
      ?bodyAtom swrl:argument1 ?bodyArg1 .
      ?bodyAtom swrl:argument2 ?bodyArg2 .
      BIND(CONCAT("obj:", STR(?bodyProp), "(", STR(?bodyArg1), ",", STR(?bodyArg2), ")") AS ?bodyAtomStr)
    } UNION {
      ?bodyAtom a swrl:BuiltinAtom .
      ?bodyAtom swrl:builtin ?bodyBuiltin .
      BIND(CONCAT("builtin:", STR(?bodyBuiltin)) AS ?bodyAtomStr)
    }
  }
  OPTIONAL {
    ?rule swrl:head ?headList .
    ?headList rdf:rest*/rdf:first ?headAtom .
    {
      ?headAtom a swrl:ClassAtom .
      ?headAtom swrl:classPredicate ?headClass .
      BIND(STR(?headClass) AS ?headAtomStr)
    } UNION {
      ?headAtom a swrl:DatavaluedPropertyAtom .
      ?headAtom swrl:propertyPredicate ?headProp .
      ?headAtom swrl:argument1 ?headArg1 .
      ?headAtom swrl:argument2 ?headArg2 .
      BIND(CONCAT("data:", STR(?headProp), "(", STR(?headArg1), ",", STR(?headArg2), ")") AS ?headAtomStr)
    } UNION {
      ?headAtom a swrl:IndividualPropertyAtom .
      ?headAtom swrl:propertyPredicate ?headProp .
      ?headAtom swrl:argument1 ?headArg1 .
      ?headAtom swrl:argument2 ?headArg2 .
      BIND(CONCAT("obj:", STR(?headProp), "(", STR(?headArg1), ",", STR(?headArg2), ")") AS ?headAtomStr)
    }
  }
  OPTIONAL {
    {
      ?rule swrl:body ?bList .
      ?bList rdf:rest*/rdf:first ?a .
      ?a a swrl:ClassAtom .
      ?a swrl:classPredicate ?class .
    } UNION {
      ?rule swrl:head ?hList .
      ?hList rdf:rest*/rdf:first ?a .
      ?a a swrl:ClassAtom .
      ?a swrl:classPredicate ?class .
    }
    OPTIONAL { ?class rdfs:label ?className }
  }
}
GROUP BY ?rule ?ruleName
LIMIT 100`,
  },
  {
    label: 'SWRL规则详情',
    query: `PREFIX swrl: <http://www.w3.org/2003/11/swrl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?rule ?ruleName
  (GROUP_CONCAT(DISTINCT ?bodyDetail; separator=" | ") AS ?bodyAtoms)
  (GROUP_CONCAT(DISTINCT ?headDetail; separator=" | ") AS ?headAtoms)
WHERE {
  ?rule a swrl:Imp .
  OPTIONAL { ?rule rdfs:label ?label }
  OPTIONAL { ?rule rdfs:comment ?comment }
  BIND(COALESCE(?label, ?comment) AS ?ruleName)
  OPTIONAL {
    ?rule swrl:body ?bodyList .
    ?bodyList rdf:rest*/rdf:first ?bodyAtom .
    {
      ?bodyAtom a swrl:ClassAtom .
      ?bodyAtom swrl:classPredicate ?bodyClass .
      ?bodyAtom swrl:argument1 ?bodyArg1 .
      BIND(CONCAT("ClassAtom(", STR(?bodyClass), ", ", STR(?bodyArg1), ")") AS ?bodyDetail)
    } UNION {
      ?bodyAtom a swrl:DatavaluedPropertyAtom .
      ?bodyAtom swrl:propertyPredicate ?bodyProp .
      ?bodyAtom swrl:argument1 ?bodyArg1 .
      ?bodyAtom swrl:argument2 ?bodyArg2 .
      BIND(CONCAT("DataProp(", STR(?bodyProp), ", ", STR(?bodyArg1), ", ", STR(?bodyArg2), ")") AS ?bodyDetail)
    } UNION {
      ?bodyAtom a swrl:IndividualPropertyAtom .
      ?bodyAtom swrl:propertyPredicate ?bodyProp .
      ?bodyAtom swrl:argument1 ?bodyArg1 .
      ?bodyAtom swrl:argument2 ?bodyArg2 .
      BIND(CONCAT("ObjProp(", STR(?bodyProp), ", ", STR(?bodyArg1), ", ", STR(?bodyArg2), ")") AS ?bodyDetail)
    } UNION {
      ?bodyAtom a swrl:BuiltinAtom .
      ?bodyAtom swrl:builtin ?bodyBuiltin .
      BIND(CONCAT("Builtin(", STR(?bodyBuiltin), ")") AS ?bodyDetail)
    }
  }
  OPTIONAL {
    ?rule swrl:head ?headList .
    ?headList rdf:rest*/rdf:first ?headAtom .
    {
      ?headAtom a swrl:ClassAtom .
      ?headAtom swrl:classPredicate ?headClass .
      ?headAtom swrl:argument1 ?headArg1 .
      BIND(CONCAT("ClassAtom(", STR(?headClass), ", ", STR(?headArg1), ")") AS ?headDetail)
    } UNION {
      ?headAtom a swrl:DatavaluedPropertyAtom .
      ?headAtom swrl:propertyPredicate ?headProp .
      ?headAtom swrl:argument1 ?headArg1 .
      ?headAtom swrl:argument2 ?headArg2 .
      BIND(CONCAT("DataProp(", STR(?headProp), ", ", STR(?headArg1), ", ", STR(?headArg2), ")") AS ?headDetail)
    } UNION {
      ?headAtom a swrl:IndividualPropertyAtom .
      ?headAtom swrl:propertyPredicate ?headProp .
      ?headAtom swrl:argument1 ?headArg1 .
      ?headAtom swrl:argument2 ?headArg2 .
      BIND(CONCAT("ObjProp(", STR(?headProp), ", ", STR(?headArg1), ", ", STR(?headArg2), ")") AS ?headDetail)
    }
  }
}
GROUP BY ?rule ?ruleName
LIMIT 100`,
  },
  {
    label: 'SWRL规则涉及的类',
    query: `PREFIX swrl: <http://www.w3.org/2003/11/swrl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>

SELECT DISTINCT ?rule ?ruleName ?class ?className ?prop ?propName WHERE {
  ?rule a swrl:Imp .
  OPTIONAL { ?rule rdfs:label ?label }
  OPTIONAL { ?rule rdfs:comment ?comment }
  BIND(COALESCE(?label, ?comment) AS ?ruleName)
  {
    ?rule swrl:body ?bodyList .
    ?bodyList rdf:rest*/rdf:first ?atom .
    { ?atom a swrl:ClassAtom . ?atom swrl:classPredicate ?class . OPTIONAL { ?class rdfs:label ?className } }
    UNION
    { ?atom a swrl:IndividualPropertyAtom . ?atom swrl:propertyPredicate ?prop . OPTIONAL { ?prop rdfs:label ?propName } }
    UNION
    { ?atom a swrl:DatavaluedPropertyAtom . ?atom swrl:propertyPredicate ?prop . OPTIONAL { ?prop rdfs:label ?propName } }
  } UNION {
    ?rule swrl:head ?headList .
    ?headList rdf:rest*/rdf:first ?atom .
    { ?atom a swrl:ClassAtom . ?atom swrl:classPredicate ?class . OPTIONAL { ?class rdfs:label ?className } }
    UNION
    { ?atom a swrl:IndividualPropertyAtom . ?atom swrl:propertyPredicate ?prop . OPTIONAL { ?prop rdfs:label ?propName } }
    UNION
    { ?atom a swrl:DatavaluedPropertyAtom . ?atom swrl:propertyPredicate ?prop . OPTIONAL { ?prop rdfs:label ?propName } }
  }
}
LIMIT 200`,
  },
  {
    label: 'SWRL诊断',
    query: `PREFIX swrl: <http://www.w3.org/2003/11/swrl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?rule ?p ?o WHERE {
  ?rule a swrl:Imp .
  ?rule ?p ?o .
}
LIMIT 500`,
  },
  {
    label: 'SWRL List结构',
    query: `PREFIX swrl: <http://www.w3.org/2003/11/swrl#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?rule ?listNode ?pred ?obj WHERE {
  ?rule a swrl:Imp .
  { ?rule swrl:body ?headNode . } UNION { ?rule swrl:head ?headNode . }
  ?headNode rdf:rest* ?listNode .
  { ?listNode rdf:first ?obj . BIND("rdf:first" AS ?pred) }
  UNION
  { ?listNode rdf:rest ?obj . BIND("rdf:rest" AS ?pred) }
}
LIMIT 500`,
  },
  {
    label: '命名个体',
    query: `PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?individual ?label ?type WHERE {
  ?individual a owl:NamedIndividual .
  OPTIONAL { ?individual rdfs:label ?label }
  OPTIONAL { ?individual a ?type . FILTER(?type != owl:NamedIndividual) }
}
LIMIT 200`,
  },
  {
    label: '三元组总数',
    query: `SELECT (COUNT(*) AS ?total) WHERE {\n  ?s ?p ?o .\n}`,
  },
  {
    label: '所有三元组',
    query: `SELECT ?s ?p ?o WHERE {\n  ?s ?p ?o .\n  FILTER(!isBlank(?s))\n}\nLIMIT 500`,
  },
];

/** 简单的 URI 缩短（取最后一个 / 或 # 后的部分，并去除尖括号） */
function shortUri(uri: string): string {
  if (!uri) return '';
  const clean = uri.replace(/^<|>$/g, '');
  const idx = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('#'));
  if (idx > 0 && idx < clean.length - 1) return clean.substring(idx + 1);
  const lastColon = clean.lastIndexOf(':');
  if (lastColon >= 0 && lastColon < clean.length - 1) return clean.substring(lastColon + 1);
  return clean;
}

/** 格式化 SWRL 原子表达式（处理 data:/obj:/builtin: 前缀） */
function formatSwrlAtomExpr(expr: string): string {
  if (!expr) return '';
  const separator = expr.includes(' ∧ ') ? ' ∧ ' : ', ';
  return expr.split(/ ∧ |, /).map((atom: string) => {
    if (atom.startsWith('data:')) {
      const inner = atom.replace('data:', '');
      const parenIdx = inner.indexOf('(');
      if (parenIdx > 0) {
        return shortUri(inner.substring(0, parenIdx)) + inner.substring(parenIdx) + '(data)';
      }
      return inner.split('/').pop() + '(data)';
    }
    if (atom.startsWith('obj:')) {
      const inner = atom.replace('obj:', '');
      const parenIdx = inner.indexOf('(');
      if (parenIdx > 0) {
        return shortUri(inner.substring(0, parenIdx)) + inner.substring(parenIdx) + '(obj)';
      }
      return inner.split('/').pop() + '(obj)';
    }
    if (atom.startsWith('builtin:')) {
      const inner = atom.replace('builtin:', '');
      const parenIdx = inner.indexOf('(');
      if (parenIdx > 0) {
        return shortUri(inner.substring(0, parenIdx)) + inner.substring(parenIdx);
      }
      return inner.split('/').pop() + '()';
    }
    return shortUri(atom);
  }).join(separator);
}

export default function DataBrowserPage() {
  const [ontologies, setOntologies] = useState<InstantiatedVersion[]>([]);
  const [selectedOntologyId, setSelectedOntologyId] = useState<string>('');
  const [loading, setLoading] = useState(false);

  // 租户列表
  const [tenants, setTenants] = useState<any[]>([]);

  // 按租户分组的已完成任务: { tenantId: { name: [versions] } }
  const [groupedTasks, setGroupedTasks] = useState<Record<string, Record<string, InstantiatedVersion[]>>>({});

  // 元数据
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [classStats, setClassStats] = useState<any[]>([]);
  const [tripleCount, setTripleCount] = useState<number | null>(null);
  const [instanceCount, setInstanceCount] = useState<number | null>(null);

  // Schema 图可视化
  const [schemaClasses, setSchemaClasses] = useState<any[]>([]);
  const [schemaProperties, setSchemaProperties] = useState<any[]>([]);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [viewMode, setViewMode] = useState<'graph' | 'tree'>('tree');
  const graphContainerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<any>(null);

  // SPARQL
  const [sparqlQuery, setSparqlQuery] = useState(SPARQL_TEMPLATES[0].query);
  const [sparqlResult, setSparqlResult] = useState<any>(null);
  const [sparqlLoading, setSparqlLoading] = useState(false);

  // 实例浏览
  const [selectedClass, setSelectedClass] = useState<string>('');
  const [instanceData, setInstanceData] = useState<any[]>([]);
  const [instanceColumns, setInstanceColumns] = useState<any[]>([]);
  const [instanceLoading, setInstanceLoading] = useState(false);

  // SWRL 规则浏览
  const [rulesLoading, setRulesLoading] = useState(false);
  const [rulesData, setRulesData] = useState<any[]>([]);
  const [swrlSparqlRules, setSwrlSparqlRules] = useState<any[]>([]);
  const [swrlDetailRules, setSwrlDetailRules] = useState<any[]>([]);
  const [swrlStats, setSwrlStats] = useState({ dbSwrlCount: 0, tdb2SwrlCount: 0 });
  const [swrlDetailLoading, setSwrlDetailLoading] = useState(false);
  const [swrlActiveCard, setSwrlActiveCard] = useState<string | null>(null); // 'tdb2' | 'db' | null

  // 数据属性（Schema Tab 中展示）
  const [dataProperties, setDataProperties] = useState<any[]>([]);
  const [dataPropertiesLoading, setDataPropertiesLoading] = useState(false);

  // TDB2 对象属性
  const [objectProperties, setObjectProperties] = useState<any[]>([]);

  // 个体关系图
  const [graphRelations, setGraphRelations] = useState<any[]>([]);
  const [graphDataLoading, setGraphDataLoading] = useState(false);
  const [graphPage, setGraphPage] = useState(0);
  const [hasMoreGraph, setHasMoreGraph] = useState(false);
  const [graphClassFilter, setGraphClassFilter] = useState<string[]>([]);
  const [graphPropFilter, setGraphPropFilter] = useState<string[]>([]);
  const [graphLayoutType, setGraphLayoutType] = useState<'force' | 'dagre'>('force');
  const [selectedIndividual, setSelectedIndividual] = useState<string | null>(null);
  const [individualDetail, setIndividualDetail] = useState<any[]>([]);
  const [individualDataProperties, setIndividualDataProperties] = useState<any[]>([]);
  const [individualObjectProperties, setIndividualObjectProperties] = useState<any[]>([]);
  const [individualDetailLoading, setIndividualDetailLoading] = useState(false);
  const individualGraphContainerRef = useRef<HTMLDivElement>(null);
  const individualGraphRef = useRef<any>(null);
  const individualGraphRenderEpoch = useRef<number>(0);

  // 个体搜索
  const [graphSearchKeyword, setGraphSearchKeyword] = useState('');
  const [graphIsSearchMode, setGraphIsSearchMode] = useState(false);
  const [graphSearchLoading, setGraphSearchLoading] = useState(false);
  const [graphSearchRelations, setGraphSearchRelations] = useState<any[]>([]);
  const [graphSearchHighlightNodes, setGraphSearchHighlightNodes] = useState<Set<string>>(new Set());
  // 独立节点列表（搜索匹配但无对象属性关系的个体 IRI）
  const [searchStandaloneIndividuals, setSearchStandaloneIndividuals] = useState<string[]>([]);

  // 个体关系图性能优化
  const [graphMaxNodes, setGraphMaxNodes] = useState<number>(300);
  const [graphHideLeafNodes, setGraphHideLeafNodes] = useState(false);
  const [graphRenderedStats, setGraphRenderedStats] = useState<{ nodes: number; edges: number; totalNodes: number; totalEdges: number }>({ nodes: 0, edges: 0, totalNodes: 0, totalEdges: 0 });
  const [graphRenderVersion, setGraphRenderVersion] = useState(0);
  const [graphDebugInfo, setGraphDebugInfo] = useState<string>('');

  const GRAPH_PAGE_SIZE = 2000;

  /** 安全销毁 vis-network 实例。
   *  vis-network 的 destroy() 会调用 container.removeChild(canvas)，
   *  若 React 条件渲染已先一步移除容器 div，则会抛出 NotFoundError。
   *  同时 vis-network 内部 requestAnimationFrame 循环在 DOM 被移除后
   *  也可能抛出同样的错误，因此调用方应在改变触发容器卸载的 state 前，
   *  先调用本函数并随后清空容器的 innerHTML。 */
  const safeDestroyNetwork = (networkRef: React.MutableRefObject<any>) => {
    if (!networkRef.current) return;
    try {
      networkRef.current.destroy();
    } catch {
      // 容器 DOM 已被 React 移除时，忽略 removeChild 错误
    } finally {
      networkRef.current = null;
    }
  };

  const selectedOntology = ontologies.find(o => o.id === selectedOntologyId);

  /** 获取当前选中实例的 TDB2 命名图标识（instanceId 优先，支持 OntologyController 自动解析） */
  const getJenaModelName = (): string => {
    const inst = ontologies.find(o => o.id === selectedOntologyId);
    return inst?.instanceId || inst?.jenaModelName || inst?.ontologyId || selectedOntologyId;
  };

  // 加载所有租户的本体实例列表（仅展示流水线中已实例化完成的）
  const loadOntologies = useCallback(async () => {
    setLoading(true);
    try {
      // 获取流水线任务中已加载完成的（status=loaded），以此为准过滤租户和实例
      const pipelineTasks = await listPipelineTasks();
      const loadedTasks = (pipelineTasks || []).filter((t: any) => t.status === 'loaded');
      const loadedTenantIds = new Set(loadedTasks.map((t: any) => t.tenantId).filter(Boolean));
      const loadedInstanceIds = new Set(loadedTasks.map((t: any) => t.instanceId).filter(Boolean));

      const allTenants = await listTenants();
      // 只保留在已加载流水线任务中出现过的租户
      const filteredTenants = (allTenants || []).filter((t: any) => loadedTenantIds.has(t.id));
      setTenants(filteredTenants);

      const allInstances: InstantiatedVersion[] = [];

      // 从所有租户获取本体实例列表，只保留在已加载流水线任务中出现过的实例
      const instances = await listInstances();
      const mapped = (instances || [])
        .filter((inst: any) => loadedInstanceIds.has(inst.id))
        .map((inst: any) => ({
          id: inst.id,
          instanceId: inst.id,
          taskId: inst.id,
          name: inst.name || '未命名实例',
          ontologyId: inst.ontologyId,
          jenaModelName: inst.graphUri || inst.id,
          status: 'COMPLETED',
          description: inst.description,
          createdAt: inst.createdAt,
          tenantId: inst.tenantId,
        }));
      allInstances.push(...mapped);

      setOntologies(allInstances);

      // 按租户→名称分组
      const grouped: Record<string, Record<string, InstantiatedVersion[]>> = {};
      allInstances.forEach((t) => {
        const tid = t.tenantId || 'default';
        if (!grouped[tid]) grouped[tid] = {};
        if (!grouped[tid][t.name]) grouped[tid][t.name] = [];
        grouped[tid][t.name].push(t);
      });
      setGroupedTasks(grouped);

      if (allInstances.length > 0 && !selectedOntologyId) {
        setSelectedOntologyId(allInstances[0].id);
      }
    } catch (err) {
      console.error('加载实例化版本列表失败:', err);
    } finally {
      setLoading(false);
    }
  }, [selectedOntologyId]);

  useEffect(() => {
    loadOntologies();
  }, []);

  // 监听页面可见性变化，从其他页面返回时自动刷新列表
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        loadOntologies();
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange);
  }, [loadOntologies]);

  // 选择本体后清空旧数据并加载新数据
  useEffect(() => {
    if (!selectedOntologyId) return;
    // 清空右侧所有旧数据，避免切换时残留上一次的内容
    setMetadataLoading(false);
    setClassStats([]);
    setTripleCount(null);
    setInstanceCount(null);
    setSchemaClasses([]);
    setSchemaProperties([]);
    setSchemaLoading(false);
    setViewMode('tree');
    setSparqlQuery(SPARQL_TEMPLATES[0].query);
    setSparqlResult(null);
    setSparqlLoading(false);
    setSelectedClass('');
    setInstanceData([]);
    setInstanceColumns([]);
    setInstanceLoading(false);
    setRulesLoading(false);
    setRulesData([]);
    setSwrlSparqlRules([]);
    setSwrlDetailRules([]);
    setSwrlStats({ dbSwrlCount: 0, tdb2SwrlCount: 0 });
    setSwrlDetailLoading(false);
    setSwrlActiveCard(null);
    setDataProperties([]);
    setDataPropertiesLoading(false);
    setObjectProperties([]);
    setGraphRelations([]);
    setGraphDataLoading(false);
    setGraphPage(0);
    setHasMoreGraph(false);
    setGraphClassFilter([]);
    setGraphPropFilter([]);
    setSelectedIndividual(null);
    setIndividualDetail([]);
    setIndividualDataProperties([]);
    setIndividualObjectProperties([]);
    setIndividualDetailLoading(false);
    setGraphSearchKeyword('');
    setGraphIsSearchMode(false);
    setGraphSearchLoading(false);
    setGraphSearchRelations([]);
    setGraphSearchHighlightNodes(new Set());
    setSearchStandaloneIndividuals([]);
    setGraphRenderedStats({ nodes: 0, edges: 0, totalNodes: 0, totalEdges: 0 });
    setGraphDebugInfo('');
    // 销毁旧的图实例
    safeDestroyNetwork(graphRef);
    safeDestroyNetwork(individualGraphRef);
    // 加载新数据
    loadMetadata();
    loadSchemaData();
    loadRulesData();
    loadDataProperties();
    loadIndividualRelations(0);
  }, [selectedOntologyId]);

  const loadMetadata = async () => {
    setMetadataLoading(true);
    try {
      const modelName = getJenaModelName();
      // 并行查询统计信息
      // SPARQL_TEMPLATES 索引: 2=类实例统计, 11=三元组总数, 1=所有实例
      const [statsResult, tripleResult, instanceResult] = await Promise.all([
        executeSparqlQuery(modelName, SPARQL_TEMPLATES[2].query).catch(() => null),
        executeSparqlQuery(modelName, SPARQL_TEMPLATES[11].query).catch(() => null),
        executeSparqlQuery(modelName, SPARQL_TEMPLATES[1].query).catch(() => null),
      ]);

      if (statsResult?.success && statsResult?.bindings) {
        setClassStats(statsResult.bindings);
        // 默认选中实例数最多的类
        if (statsResult.bindings.length > 0 && !selectedClass) {
          loadInstancesForClass(statsResult.bindings[0].type);
        }
      }
      if (tripleResult?.success && tripleResult?.bindings?.[0]?.total) {
        setTripleCount(Number(tripleResult.bindings[0].total));
      }
      if (instanceResult?.success && instanceResult?.bindings) {
        setInstanceCount(instanceResult.bindings.length);
      }
    } catch (err) {
      console.error('加载元数据失败:', err);
    } finally {
      setMetadataLoading(false);
    }
  };

  const loadSchemaData = async () => {
    setSchemaLoading(true);
    try {
      const inst = ontologies.find(o => o.id === selectedOntologyId);
      const ontologyId = inst?.ontologyId || selectedOntologyId;

      // ---- 从 API 加载类和对��属性 ----
      let classes: any[] = [];
      let properties: any[] = [];
      let needSparqlFallback = false;

      try {
        const [apiClasses, apiProperties] = await Promise.all([
          getObjectTypes(ontologyId).catch(() => []),
          getLinkTypes(ontologyId).catch(() => []),
        ]);
        classes = apiClasses || [];
        properties = apiProperties || [];

        // 检查属性是否缺少 domain/range（sourceType/targetType）
        // 如果属性为空，或者有属性但缺少 domain/range，都需要 SPARQL 回退
        const hasValidDomainRange = properties.length > 0 &&
          properties.some((p: any) => (p.sourceType || p.domain) && (p.targetType || p.range));
        if (!hasValidDomainRange) {
          if (properties.length === 0) {
            console.warn('API 未返回对象属性，尝试从 TDB2 SPARQL 查询...');
          } else {
            console.warn('API 返回的对象属性缺少 domain/range，尝试从 TDB2 SPARQL 补充...');
          }
          needSparqlFallback = true;
        }
      } catch {
        console.warn('本体 Schema API 失败，尝试从 TDB2 SPARQL 查询...');
        needSparqlFallback = true;
      }

      // ---- SPARQL 回退：加载类或属性 ----
      if (needSparqlFallback) {
        try {
          const modelName = getJenaModelName();

          // 回退加载类
          if (classes.length === 0) {
            const classResult = await executeSparqlQuery(modelName, SPARQL_TEMPLATES[0].query);
            if (classResult?.success && classResult?.bindings) {
              classes = classResult.bindings.map((b: any) => ({
                iri: b.class || '',
                label: b.label || shortenUri(b.class || ''),
                parentIri: b.parentClass || '',
              }));
            }
          }

          // 回退加载对象属性（含 domain/range）
          const propQuery = `PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>

SELECT DISTINCT ?prop ?label ?domain ?range WHERE {
  { ?prop a owl:ObjectProperty }
  UNION
  { ?s ?prop ?o . FILTER(isURI(?o)) FILTER(?prop != rdf:type) }
  OPTIONAL { ?prop rdfs:label ?label }
  OPTIONAL { ?prop rdfs:domain ?domain }
  OPTIONAL { ?prop rdfs:range ?range }
}
LIMIT 200`;
          const propResult = await executeSparqlQuery(modelName, propQuery);
          if (propResult?.success && propResult?.bindings) {
            properties = propResult.bindings
              .filter((b: any) => b.prop)
              .map((b: any) => ({
                iri: b.prop || '',
                label: b.label || shortenUri(b.prop || ''),
                sourceType: b.domain || '',
                targetType: b.range || '',
                domain: b.domain || '',
                range: b.range || '',
              }));
          }

          // 如果 SPARQL rdfs:domain/rdfs:range 也为空，从实例数据推断
          const stillMissingDomainRange = properties.length > 0 &&
            !properties.some((p: any) => (p.sourceType || p.domain) && (p.targetType || p.range));
          if (stillMissingDomainRange || properties.length === 0) {
            const inferQuery = `PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>

SELECT DISTINCT ?prop ?domainClass ?rangeClass ?label WHERE {
  ?s ?prop ?o .
  FILTER(isURI(?s) && isURI(?o))
  FILTER(?prop != rdf:type)
  FILTER(!STRSTARTS(STR(?prop), "http://www.w3.org/"))
  ?s a ?domainClass .
  ?o a ?rangeClass .
  FILTER(?domainClass != owl:NamedIndividual)
  FILTER(?rangeClass != owl:NamedIndividual)
  OPTIONAL { ?prop rdfs:label ?label }
}
LIMIT 500`;
            const inferResult = await executeSparqlQuery(modelName, inferQuery);
            if (inferResult?.success && inferResult?.bindings && inferResult.bindings.length > 0) {
              // 合并同一属性的多个 domain/range
              const propMap = new Map<string, any>();
              inferResult.bindings.forEach((b: any) => {
                if (!b.prop) return;
                const key = b.prop;
                if (!propMap.has(key)) {
                  propMap.set(key, {
                    iri: b.prop,
                    label: b.label || shortenUri(b.prop),
                    sourceType: b.domainClass || '',
                    targetType: b.rangeClass || '',
                    domain: b.domainClass || '',
                    range: b.rangeClass || '',
                  });
                }
              });
              if (propMap.size > 0) {
                properties = Array.from(propMap.values());
              }
            }
          }
        } catch (e) {
          console.warn('SPARQL Schema 回退失败:', e);
        }
      }

      setSchemaClasses(classes);
      setSchemaProperties(properties);
    } catch (err) {
      console.error('加载 Schema 失败:', err);
      setSchemaClasses([]);
      setSchemaProperties([]);
    } finally {
      setSchemaLoading(false);
    }
  };

  // 加载 SWRL 规则数据
  const loadRulesData = async () => {
    if (!selectedOntologyId) return;
    setRulesLoading(true);
    try {
      const modelName = getJenaModelName();
      const inst = ontologies.find(o => o.id === selectedOntologyId);
      // 并行从 DB 和 SPARQL 查询规则（本体可能已删除，catch 异常）
      const [dbRules, sparqlResult] = await Promise.all([
        getRulesByOntology(inst?.ontologyId || selectedOntologyId).catch(() => []),
        executeSparqlQuery(modelName, SPARQL_TEMPLATES[5].query).catch(() => null),
      ]);
      setRulesData(dbRules || []);
      if (sparqlResult?.success && sparqlResult?.bindings) {
        setSwrlSparqlRules(sparqlResult.bindings);
      } else {
        setSwrlSparqlRules([]);
      }

      // 计算 SWRL 统计
      const dbSwrlCount = (dbRules || []).filter((r: any) => r.type === 'SWRL' || r.swrlRule).length;
      const tdb2SwrlCount = sparqlResult?.bindings?.length || 0;
      setSwrlStats({ dbSwrlCount, tdb2SwrlCount });
    } catch (err) {
      console.error('加载规则数据失败:', err);
      setRulesData([]);
      setSwrlSparqlRules([]);
    } finally {
      setRulesLoading(false);
    }
  };

  // 加载 SWRL 规则详情（包含 body/head 原子）
  const loadSwrlDetails = async () => {
    if (!selectedOntologyId) return;
    setSwrlDetailLoading(true);
    try {
      const modelName = getJenaModelName();
      const result = await executeSparqlQuery(modelName, SPARQL_TEMPLATES[6].query);
      if (result?.success && result?.bindings) {
        setSwrlDetailRules(result.bindings);
      } else {
        setSwrlDetailRules([]);
      }
    } catch {
      setSwrlDetailRules([]);
    } finally {
      setSwrlDetailLoading(false);
    }
  };

  // 加载数据属性和对象属性
  const loadDataProperties = async () => {
    if (!selectedOntologyId) return;
    setDataPropertiesLoading(true);
    try {
      const modelName = getJenaModelName();
      const [dataResult, objectResult] = await Promise.all([
        executeSparqlQuery(modelName, SPARQL_TEMPLATES[4].query).catch(() => null),
        executeSparqlQuery(modelName, SPARQL_TEMPLATES[3].query).catch(() => null),
      ]);
      if (dataResult?.success && dataResult?.bindings) {
        setDataProperties(dataResult.bindings);
      } else {
        setDataProperties([]);
      }
      if (objectResult?.success && objectResult?.bindings) {
        setObjectProperties(objectResult.bindings);
      } else {
        setObjectProperties([]);
      }
    } catch {
      setDataProperties([]);
      setObjectProperties([]);
    } finally {
      setDataPropertiesLoading(false);
    }
  };

  // 加载个体关系图数据
  const loadIndividualRelations = async (page: number = 0) => {
    if (!selectedOntologyId) return;
    setGraphDataLoading(true);
    try {
      const modelName = getJenaModelName();
      const offset = page * GRAPH_PAGE_SIZE;
      const query = `PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?subject ?subjectClass ?predicate ?object ?objectClass WHERE {
  ?subject ?predicate ?object .
  OPTIONAL { ?subject a ?subjectClass }
  OPTIONAL { ?object a ?objectClass }
  FILTER(isURI(?subject) && isURI(?object))
  FILTER(?predicate != rdf:type)
  FILTER(!STRSTARTS(STR(?predicate), "http://www.w3.org/"))
}
LIMIT ${GRAPH_PAGE_SIZE}
OFFSET ${offset}`;
      const result = await executeSparqlQuery(modelName, query);
      if (result?.success && result?.bindings) {
        if (page === 0) {
          setGraphRelations(result.bindings);
        } else {
          setGraphRelations(prev => [...prev, ...result.bindings]);
        }
        setGraphPage(page);
        setHasMoreGraph(result.bindings.length >= GRAPH_PAGE_SIZE);
        if (page === 0) setGraphRenderVersion(v => v + 1);
      } else {
        if (page === 0) { setGraphRelations([]); setGraphRenderVersion(v => v + 1); }
        setHasMoreGraph(false);
      }
    } catch (err) {
      console.error('加载个体关系失败:', err);
      if (page === 0) { setGraphRelations([]); setGraphRenderVersion(v => v + 1); }
    } finally {
      setGraphDataLoading(false);
    }
  };

  // 加载个体详情（区分数据属性和对象属性）
  const loadIndividualDetail = async (individualIri: string) => {
    setIndividualDetailLoading(true);
    setSelectedIndividual(individualIri);
    try {
      const modelName = getJenaModelName();
      const query = `PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?property ?propertyLabel ?value (isLiteral(?value) AS ?isDataProp) WHERE {
  <${individualIri}> ?property ?value .
  FILTER(?property != rdf:type)
  OPTIONAL { ?property rdfs:label ?propertyLabel . FILTER(LANG(?propertyLabel) = '' || LANGMATCHES(LANG(?propertyLabel), 'zh')) }
}
LIMIT 200`;
      const result = await executeSparqlQuery(modelName, query);
      if (result?.success && result?.bindings) {
        const allProps = result.bindings;
        setIndividualDetail(allProps);
        // 区分数据属性（字面量值）和对象属性（IRI 值）
        const dataProps = allProps.filter((r: any) => r.isDataProp !== 'false' && r.isDataProp !== false);
        const objProps = allProps.filter((r: any) => r.isDataProp === 'false' || r.isDataProp === false);
        setIndividualDataProperties(dataProps);
        setIndividualObjectProperties(objProps);
      } else {
        setIndividualDetail([]);
        setIndividualDataProperties([]);
        setIndividualObjectProperties([]);
      }
    } catch (err) {
      console.error('加载个体详情失败:', err);
      setIndividualDetail([]);
      setIndividualDataProperties([]);
      setIndividualObjectProperties([]);
    } finally {
      setIndividualDetailLoading(false);
    }
  };

  // 搜索个体：根据关键字查找个体及其关联个体
  // 分两步：1) 搜索任意属性值匹配关键字的个体（含所有字面量数据属性，不限于 rdfs:label）
  //         2) 查找这些个体的对象属性关系（用于关系图渲染）
  // 与原实现的区别：
  //   a) 步骤1 搜索所有 isLiteral 属性值，而非仅 rdfs:label + IRI
  //   b) 步骤2 将 ?object a ?objectClass 改为 OPTIONAL（部分个体可能缺 rdf:type）
  const searchIndividuals = async (keyword: string) => {
    if (!keyword.trim() || !selectedOntologyId) return;
    // 在 React 可能移除容器 div 之前，主动销毁旧 vis-network 实例并清空 DOM
    safeDestroyNetwork(individualGraphRef);
    if (individualGraphContainerRef.current) {
      individualGraphContainerRef.current.innerHTML = '';
    }
    setGraphSearchLoading(true);
    setGraphIsSearchMode(true);
    setGraphSearchKeyword(keyword);
    setSearchStandaloneIndividuals([]); // 新搜索时清空独立节点
    const kw = keyword.trim();
    const kwLower = kw.toLowerCase();
    // 转义 REGEX 特殊字符
    const escapedKw = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    let matchedIris: string[] = [];
    try {
      const modelName = getJenaModelName();

      // 步骤1：搜索任意属性值匹配关键字的个体
      // 搜索范围：任意字面量数据属性值 + IRI 字符串（不再仅限于 rdfs:label）
      const matchQuery = `PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT DISTINCT ?individual WHERE {
  ?individual ?prop ?value .
  FILTER(isURI(?individual))
  FILTER(
    (isLiteral(?value) && REGEX(STR(?value), "${escapedKw}")) ||
    REGEX(STR(?individual), "${escapedKw}")
  )
}
LIMIT 200`;
      console.log('[searchIndividuals] 步骤1: 搜索匹配个体, keyword=', kw);
      const matchResult = await executeSparqlQuery(modelName, matchQuery);
      console.log('[searchIndividuals] 步骤1结果:', {
        success: matchResult?.success,
        bindingsCount: matchResult?.bindings?.length,
        sampleIRIs: (matchResult?.bindings || []).slice(0, 5).map((r: any) => r.individual),
      });
      matchedIris = (matchResult?.bindings || [])
        .map((r: any) => r.individual)
        .filter(Boolean);

      if (matchedIris.length === 0) {
        console.log('[searchIndividuals] 步骤1: 未找到任何匹配个体');
        setGraphSearchRelations([]);
        setGraphSearchHighlightNodes(new Set());
        message.info(`未找到匹配 "${keyword}" 的个体`);
        setGraphSearchLoading(false);
        return;
      }

      console.log(`[searchIndividuals] 步骤1: 找到 ${matchedIris.length} 个匹配个体`);

      // 步骤2：查询这些个体的对象属性关系
      // 限制最多 100 个 IRI 构建 VALUES 子句，避免查询字符串过长
      const maxValuesIris = matchedIris.slice(0, 100);
      const iriValues = maxValuesIris
        .map(iri => `<${iri.replace(/^<|>$/g, '')}>`)
        .join(' ');
      // 使用 UNION 替代 FILTER(?subject = ?matchNode || ?object = ?matchNode)
      // TDB2 能通过 SPO/POS 索引直接定位，避免全表扫描
      const relQuery = `PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>

SELECT ?subject ?subjectClass ?predicate ?object ?objectClass ?sLabel ?oLabel WHERE {
  {
    VALUES ?subject { ${iriValues} }
    ?subject ?predicate ?object .
    FILTER(isURI(?object))
  } UNION {
    VALUES ?object { ${iriValues} }
    ?subject ?predicate ?object .
    FILTER(isURI(?subject))
  }
  OPTIONAL { ?subject a ?subjectClass }
  OPTIONAL { ?object a ?objectClass }
  FILTER(?predicate != rdf:type)
  FILTER(!STRSTARTS(STR(?predicate), "http://www.w3.org/"))
  OPTIONAL { ?subject rdfs:label ?sLabel }
  OPTIONAL { ?object rdfs:label ?oLabel }
}
LIMIT 2000`;
      console.log('[searchIndividuals] 步骤2: 查询关系, VALUES IRI数=', maxValuesIris.length);

      const result = await executeSparqlQuery(modelName, relQuery);
      console.log('[searchIndividuals] 步骤2结果:', {
        success: result?.success,
        bindingsCount: result?.bindings?.length,
        error: result?.error,
      });

      if (result?.success && result?.bindings && result.bindings.length > 0) {
        const bindings = result.bindings;
        setGraphSearchRelations(bindings);
        // 计算匹配关键字的节点（高亮节点）
        const iriSet = new Set(matchedIris);
        const highlightNodes = new Set<string>();
        bindings.forEach((r: any) => {
          const subj = r.subject || '';
          const obj = r.object || '';
          const sLabel = r.sLabel || '';
          const oLabel = r.oLabel || '';
          if (iriSet.has(subj) || (sLabel && sLabel.toLowerCase().includes(kwLower))) {
            highlightNodes.add(subj);
          }
          if (iriSet.has(obj) || (oLabel && oLabel.toLowerCase().includes(kwLower))) {
            highlightNodes.add(obj);
          }
        });
        setGraphSearchHighlightNodes(highlightNodes);
        setGraphRenderVersion(v => v + 1);
      } else {
        // 匹配个体存在，但无对象属性关系（独立个体或仅有数据属性）
        // 使用 HTML/CSS 可视化渲染独立节点，不走图渲染
        console.log('[searchIndividuals] 匹配个体无对象属性关系，展示独立节点列表');
        const displayIris = matchedIris.slice(0, 50);
        setGraphSearchRelations([]);
        setGraphSearchHighlightNodes(new Set(displayIris));
        setSearchStandaloneIndividuals(displayIris);
        if (matchedIris.length > 50) {
          message.info(`找到 ${matchedIris.length} 个匹配个体（含无关系数据的独立节点），已显示前 50 个`);
        } else {
          message.info(`找到 ${matchedIris.length} 个匹配个体，均无对象属性关系，已显示为独立节点`);
        }
      }
    } catch (err) {
      console.error('[searchIndividuals] 搜索失败:', err);
      setGraphSearchRelations([]);
      // 步骤2失败时，使用步骤1已匹配的IRI展示为独立节点，避免"0节点0边"的空白结果
      if (matchedIris.length > 0) {
        const displayIris = matchedIris.slice(0, 50);
        setGraphSearchHighlightNodes(new Set(displayIris));
        setSearchStandaloneIndividuals(displayIris);
        message.warning(`搜索 "${keyword}" 时关系查询失败，已展示 ${displayIris.length} 个匹配个体为独立节点`);
      } else {
        setGraphSearchHighlightNodes(new Set());
        message.error(`搜索 "${keyword}" 失败，请检查网络或重试`);
      }
    } finally {
      setGraphSearchLoading(false);
    }
  };

  // 清除搜索
  const clearSearch = () => {
    // 在 React 可能移除容器 div 之前，主动销毁旧 vis-network 实例并清空 DOM
    safeDestroyNetwork(individualGraphRef);
    if (individualGraphContainerRef.current) {
      individualGraphContainerRef.current.innerHTML = '';
    }
    setGraphIsSearchMode(false);
    setGraphSearchKeyword('');
    setGraphSearchRelations([]);
    setGraphSearchHighlightNodes(new Set());
    setSearchStandaloneIndividuals([]);
    setGraphRenderVersion(v => v + 1);
  };

  // 加载某个类的所有实例及其属性
  const loadInstancesForClass = async (classIri: string | undefined) => {
    if (!classIri) {
      setSelectedClass('');
      setInstanceData([]);
      setInstanceColumns([]);
      return;
    }
    setSelectedClass(classIri);
    setInstanceLoading(true);
    try {
      // 确保 IRI 不带多余尖括号
      const cleanIri = classIri.replace(/^<|>$/g, '');
      // 使用更可靠的查询：先查所有实例，再查所有属性（排除 rdf:type）
      const query = `PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>

SELECT ?instance ?property ?value WHERE {
  ?instance a <${cleanIri}> .
  ?instance ?property ?value .
  FILTER(?property != rdf:type)
}
ORDER BY ?instance ?property
LIMIT 5000`;
      console.log('[DataBrowser] SPARQL query:', query);
      const modelName = getJenaModelName();
      const result = await executeSparqlQuery(modelName, query);
      console.log('[DataBrowser] SPARQL result:', result);
      // 详细打印每行数据
      if (result?.bindings) {
        console.log('[DataBrowser] 返回行数:', result.bindings.length);
        result.bindings.slice(0, 20).forEach((row: any, i: number) => {
          console.log(`[DataBrowser] 行${i}: instance=${row.instance}, property=${row.property}, value=${row.value}`);
        });
      }

      if (result?.success && result?.bindings && result.bindings.length > 0) {
        // 将三元组转换为表格形式：行=实例，列=属性
        const instanceMap = new Map<string, Record<string, string>>();
        const allProps = new Set<string>();
        for (const row of result.bindings) {
          if (!row.instance) continue;
          const inst = shortenUri(row.instance);
          if (!instanceMap.has(inst)) {
            instanceMap.set(inst, { '_instance': row.instance });
          }
          if (row.property && row.value != null) {
            const prop = shortenUri(row.property) || row.property;
            const value = shortenUri(row.value);
            allProps.add(prop);
            instanceMap.get(inst)![prop] = value;
          }
        }
        const propsArr = Array.from(allProps).filter(p => p);
        setInstanceColumns([
          { title: '实例', dataIndex: '_instance', key: '_instance', ellipsis: true, width: 200,
            render: (v: string) => <Tooltip title={v}><Text copyable style={{ fontSize: 12 }}>{shortenUri(v)}</Text></Tooltip> },
          ...propsArr.map(p => ({
            title: p, dataIndex: p, key: p, ellipsis: true, width: 180,
            render: (v: string) => v != null ? <Tooltip title={v}><Text style={{ fontSize: 12 }}>{v}</Text></Tooltip> : <Text type="secondary">-</Text>,
          })),
        ]);
        setInstanceData(
          Array.from(instanceMap.entries()).map(([inst, props]) => ({
            key: inst,
            ...props,
          }))
        );
      } else if (result?.success) {
        // 查询成功但无属性行——尝试回退查询仅获取实例列表（无属性）
        const fallbackQuery = `PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT DISTINCT ?instance WHERE {
  ?instance a <${cleanIri}> .
}
LIMIT 500`;
        console.log('[DataBrowser] 回退查询（无属性行）:', fallbackQuery);
        const fallbackResult = await executeSparqlQuery(modelName, fallbackQuery);
        if (fallbackResult?.success && fallbackResult?.bindings) {
          const instances = fallbackResult.bindings
            .filter((r: any) => r.instance)
            .map((r: any) => ({
              key: shortenUri(r.instance),
              _instance: r.instance,
            }));
          setInstanceColumns([
            { title: '实例', dataIndex: '_instance', key: '_instance', ellipsis: true, width: 200,
              render: (v: string) => <Tooltip title={v}><Text copyable style={{ fontSize: 12 }}>{shortenUri(v)}</Text></Tooltip> },
          ]);
          setInstanceData(instances);
        } else {
          setInstanceColumns([]);
          setInstanceData([]);
        }
      } else {
        message.error(`查询失败: ${result?.error || result?.message || '未知错误'}`);
        setInstanceColumns([]);
        setInstanceData([]);
      }
    } catch (err) {
      message.error('加载实例数据失败');
      console.error(err);
      setInstanceColumns([]);
      setInstanceData([]);
    } finally {
      setInstanceLoading(false);
    }
  };

  // SPARQL 查询
  const handleRunSparql = async () => {
    if (!selectedOntologyId || !sparqlQuery.trim()) return;
    setSparqlLoading(true);
    setSparqlResult(null);
    try {
      const modelName = getJenaModelName();
      const result = await executeSparqlQuery(modelName, sparqlQuery);
      setSparqlResult(result);
    } catch (err: any) {
      setSparqlResult({ error: true, message: err.message });
    } finally {
      setSparqlLoading(false);
    }
  };

  // 图渲染
  useEffect(() => {
    if (viewMode !== 'graph' || schemaClasses.length === 0) return;
    renderSchemaGraph();
    return () => {
      safeDestroyNetwork(graphRef);
    };
  }, [viewMode, schemaClasses, schemaProperties]);

  const renderSchemaGraph = () => {
    if (!graphContainerRef.current || schemaClasses.length === 0) return;

    try {
      safeDestroyNetwork(graphRef);

      const classColorMap: Record<string, string> = {};
      schemaClasses.forEach((cls: any, i: number) => {
        classColorMap[cls.iri || cls.id] = NODE_COLORS[i % NODE_COLORS.length];
      });

      const nodes = schemaClasses.map((cls: any, i: number) => {
        const label = cls.label || cls.iri?.split('/').pop() || `Class${i}`;
        const color = NODE_COLORS[i % NODE_COLORS.length];
        const isRoot = !cls.parentIri;
        // 使用 ellipse 形状使标签文字显示在圆圈内部（dot 形状的标签在外部）
        return {
          id: cls.iri || cls.id || `class-${i}`,
          label,
          data: { ...cls, color },
          color: { background: '#fff', border: color },
          borderWidth: isRoot ? 1.5 : 1,
          font: { color: '#1a1a1a', size: isRoot ? 14 : 12 },
          shape: 'ellipse',
          size: isRoot ? 50 : 40,
        };
      });

      const edges: any[] = [];

      // 对象属性边
      schemaProperties.forEach((prop: any, i: number) => {
        const sourceNode = schemaClasses.find((c: any) =>
          c.label === (prop.sourceType || prop.domain) || c.iri === (prop.sourceType || prop.domain)
        );
        const targetNode = schemaClasses.find((c: any) =>
          c.label === (prop.targetType || prop.range) || c.iri === (prop.targetType || prop.range)
        );
        if (!sourceNode || !targetNode) return;
        const edgeColor = classColorMap[sourceNode.iri || sourceNode.id] || '#666';
        edges.push({
          id: `edge-${i}`,
          from: sourceNode.iri || sourceNode.id,
          to: targetNode.iri || targetNode.id,
          label: prop.label || prop.iri?.split('/').pop() || '',
          data: prop,
          color: { color: edgeColor, opacity: 0.7 },
          arrows: { to: { enabled: true } },
          font: { color: '#555', size: 10, background: '#fff', strokeWidth: 0 },
        });
      });

      // 继承边
      schemaClasses.forEach((cls: any, i: number) => {
        if (cls.parentIri) {
          const parentNode = schemaClasses.find((c: any) => c.iri === cls.parentIri || c.label === cls.parentIri);
          if (parentNode) {
            edges.push({
              id: `inherit-${i}`,
              from: cls.iri || cls.id,
              to: parentNode.iri || parentNode.id,
              data: { isInherit: true },
              color: { color: '#888', opacity: 0.6 },
              arrows: { to: { enabled: true } },
              dashes: true,
            });
          }
        }
      });

      const hasInheritance = schemaClasses.some((c: any) => c.parentIri);
      const container = graphContainerRef.current;
      if (container) container.style.visibility = 'hidden';

      const network = new Network(container, { nodes, edges }, {
        layout: hasInheritance
          ? { hierarchical: { direction: 'UD', sortMethod: 'directed', nodeSpacing: 150, levelSeparation: 120 } }
          : undefined,
        physics: hasInheritance ? false : {
          solver: 'forceAtlas2Based',
          forceAtlas2Based: { gravitationalConstant: -50, centralGravity: 0.01, springLength: 200, springConstant: 0.08 },
        },
        interaction: { dragNodes: true, dragView: true, zoomView: true },
      });

      if (container) container.style.visibility = 'visible';
      try { network.fit({ animation: { duration: 400, easingFunction: 'easeInOutQuad' } }); } catch { /* ignore */ }
      graphRef.current = network;
    } catch (e) {
      console.error('Schema 图渲染失败:', e);
    }
  };

  // 根据类/属性过滤个体关系数据（搜索模式下使用搜索结果）
  // 注意：独立节点（_standalone）不受类/属性过滤影响，始终保留
  const filteredRelations = useMemo(() => {
    const base = graphIsSearchMode ? graphSearchRelations : graphRelations;
    let filtered = base;
    if (graphClassFilter.length > 0) {
      filtered = filtered.filter((r: any) =>
        r._standalone || graphClassFilter.includes(r.subjectClass) || graphClassFilter.includes(r.objectClass)
      );
    }
    if (graphPropFilter.length > 0) {
      filtered = filtered.filter((r: any) => r._standalone || graphPropFilter.includes(r.predicate));
    }
    return filtered;
  }, [graphRelations, graphSearchRelations, graphIsSearchMode, graphClassFilter, graphPropFilter]);

  // 从 graphRelations 提取唯一的类和属性用于过滤选项
  const availableClasses = useMemo(() => {
    const source = graphIsSearchMode ? graphSearchRelations : graphRelations;
    const classSet = new Map<string, { iri: string; count: number }>();
    source.forEach((r: any) => {
      if (r.subjectClass) {
        const existing = classSet.get(r.subjectClass);
        if (existing) existing.count++; else classSet.set(r.subjectClass, { iri: r.subjectClass, count: 1 });
      }
      if (r.objectClass) {
        const existing = classSet.get(r.objectClass);
        if (existing) existing.count++; else classSet.set(r.objectClass, { iri: r.objectClass, count: 1 });
      }
    });
    return Array.from(classSet.values()).sort((a, b) => b.count - a.count);
  }, [graphRelations, graphSearchRelations, graphIsSearchMode]);

  const availableProperties = useMemo(() => {
    const source = graphIsSearchMode ? graphSearchRelations : graphRelations;
    const propMap = new Map<string, { iri: string; count: number }>();
    source.forEach((r: any) => {
      if (r.predicate) {
        const existing = propMap.get(r.predicate);
        if (existing) existing.count++; else propMap.set(r.predicate, { iri: r.predicate, count: 1 });
      }
    });
    return Array.from(propMap.values()).sort((a, b) => b.count - a.count);
  }, [graphRelations, graphSearchRelations, graphIsSearchMode]);

  const nodeMapSize = (relations: any[]): number => {
    const nodes = new Set<string>();
    relations.forEach((r: any) => {
      if (r.subject) nodes.add(r.subject);
      if (r.object) nodes.add(r.object);
    });
    return nodes.size;
  };

  // 渲染个体关系图
  const renderIndividualGraph = async (epoch: number) => {
    const container = individualGraphContainerRef.current;
    console.log('[个体关系图] renderIndividualGraph 开始 epoch:', epoch, 'container:', !!container, 'filteredRelations:', filteredRelations.length, 'container size:', container?.offsetWidth, 'x', container?.offsetHeight);
    if (!container || filteredRelations.length === 0) return;

    // 检查容器是否有实际尺寸，如果没有则重试一次
    if (container.offsetWidth === 0 || container.offsetHeight === 0) {
      console.log('[个体关系图] 容器尺寸为 0，延迟重试');
      await new Promise<void>(resolve => setTimeout(resolve, 100));
      if (individualGraphRenderEpoch.current !== epoch) { console.log('[个体关系图] epoch 过期(尺寸检查后)'); return; }
      if (container.offsetWidth === 0 || container.offsetHeight === 0) {
        console.log('[个体关系图] 容器尺寸仍为 0，放弃渲染');
        return;
      }
    }

    try {
      if (individualGraphRenderEpoch.current !== epoch) { console.log('[个体关系图] epoch 过期(导入后)'); return; }

      // 清理旧图
      safeDestroyNetwork(individualGraphRef);

      // 边去重：同一 (subject, predicate, object) 只保留一条
      const edgeKeySet = new Set<string>();
      const uniqueRelations: any[] = [];
      filteredRelations.forEach((row: any) => {
        const key = `${row.subject}|${row.predicate}|${row.object}`;
        if (!edgeKeySet.has(key)) {
          edgeKeySet.add(key);
          uniqueRelations.push(row);
        }
      });
      console.log('[个体关系图] 去重前:', filteredRelations.length, '去重后:', uniqueRelations.length);

      // 分离真实边和独立节点（_standalone 行的个体没有关联边）
      const standaloneIds = new Set<string>();
      const realRelations: any[] = [];
      uniqueRelations.forEach((row: any) => {
        if (row._standalone) {
          standaloneIds.add(row.subject);
        } else {
          realRelations.push(row);
        }
      });

      // 构建节点映射并计算节点的度
      const nodeMap = new Map<string, { iri: string; classIri: string; label: string; color: string; degree: number }>();
      const classSet = new Set<string>();
      const nodeDegree = new Map<string, number>();
      const nodeBestClass = new Map<string, string>();

      realRelations.forEach((row: any) => {
        if (row.subjectClass) classSet.add(row.subjectClass);
        if (row.objectClass) classSet.add(row.objectClass);
        nodeDegree.set(row.subject, (nodeDegree.get(row.subject) || 0) + 1);
        nodeDegree.set(row.object, (nodeDegree.get(row.object) || 0) + 1);
        if (row.subjectClass && !row.subjectClass.includes('NamedIndividual')) {
          if (!nodeBestClass.has(row.subject)) nodeBestClass.set(row.subject, row.subjectClass);
        } else if (row.subjectClass && !nodeBestClass.has(row.subject)) {
          nodeBestClass.set(row.subject, row.subjectClass);
        }
        if (row.objectClass && !row.objectClass.includes('NamedIndividual')) {
          if (!nodeBestClass.has(row.object)) nodeBestClass.set(row.object, row.objectClass);
        } else if (row.objectClass && !nodeBestClass.has(row.object)) {
          nodeBestClass.set(row.object, row.objectClass);
        }
      });

      const classList = Array.from(classSet);
      const classColorMap: Record<string, string> = {};
      classList.forEach((cls, i) => {
        classColorMap[cls] = NODE_COLORS[i % NODE_COLORS.length];
      });

      realRelations.forEach((row: any) => {
        if (row.subject && !nodeMap.has(row.subject)) {
          const cls = nodeBestClass.get(row.subject) || row.subjectClass || '';
          nodeMap.set(row.subject, {
            iri: row.subject,
            classIri: cls,
            label: truncateLabel(shortenUri(row.subject)),
            color: classColorMap[cls] || '#1677ff',
            degree: nodeDegree.get(row.subject) || 0,
          });
        }
        if (row.object && !nodeMap.has(row.object)) {
          const cls = nodeBestClass.get(row.object) || row.objectClass || '';
          nodeMap.set(row.object, {
            iri: row.object,
            classIri: cls,
            label: truncateLabel(shortenUri(row.object)),
            color: classColorMap[cls] || '#1677ff',
            degree: nodeDegree.get(row.object) || 0,
          });
        }
      });

      standaloneIds.forEach((iri) => {
        if (!nodeMap.has(iri)) {
          nodeMap.set(iri, {
            iri,
            classIri: '',
            label: truncateLabel(shortenUri(iri)),
            color: '#1677ff',
            degree: 0,
          });
        }
      });

      const totalNodes = nodeMap.size;
      const totalEdges = realRelations.length;

      let visibleNodeIds = new Set(Array.from(nodeMap.keys()));

      if (graphHideLeafNodes) {
        const nonLeafIds = new Set<string>();
        nodeMap.forEach((n, id) => {
          if (n.degree >= 2 || standaloneIds.has(id)) nonLeafIds.add(id);
        });
        if (nonLeafIds.size >= 2) {
          visibleNodeIds = nonLeafIds;
        }
      }

      const maxNodes = graphMaxNodes <= 0 ? Infinity : graphMaxNodes;
      if (visibleNodeIds.size > maxNodes) {
        const standaloneVisible = new Set<string>();
        const otherIds: string[] = [];
        visibleNodeIds.forEach((id) => {
          if (standaloneIds.has(id)) {
            standaloneVisible.add(id);
          } else {
            otherIds.push(id);
          }
        });
        const remaining = maxNodes - standaloneVisible.size;
        if (remaining > 0) {
          const sorted = otherIds.sort((a, b) => {
            return (nodeMap.get(b)?.degree || 0) - (nodeMap.get(a)?.degree || 0);
          });
          sorted.slice(0, remaining).forEach((id) => standaloneVisible.add(id));
        }
        visibleNodeIds = standaloneVisible;
      }

      const visibleEdges = realRelations
        .map((row: any, i: number) => ({ row, i }))
        .filter(({ row }) => visibleNodeIds.has(row.subject) && visibleNodeIds.has(row.object));

      const edges = visibleEdges.map(({ row, i }) => {
        const predLabel = shortenUri(row.predicate);
        return {
          id: `irel-${i}`,
          from: row.subject,
          to: row.object,
          label: predLabel,
          data: row,
          color: { color: '#91caff', opacity: 0.7 },
          arrows: { to: { enabled: true } },
          font: { color: '#555', size: 9, background: '#fff', strokeWidth: 0, align: 'middle' },
          width: 1.2,
        };
      });

      const isHighlightNode = (iri: string) => graphIsSearchMode && graphSearchHighlightNodes.has(iri);

      const isLargeGraph = visibleNodeIds.size > 200;
      const visibleNodeArr = Array.from(visibleNodeIds);
      const nodes = visibleNodeArr.map((id) => {
        const n = nodeMap.get(id)!;
        const highlighted = isHighlightNode(n.iri);
        return {
          id: n.iri,
          label: n.label,
          data: { ...n, highlighted },
          color: { background: highlighted ? '#fa8c16' : n.color, border: highlighted ? '#faad14' : n.color },
          borderWidth: highlighted ? 4 : 2,
          font: { color: '#fff', size: highlighted ? 12 : isLargeGraph ? 8 : 10, background: highlighted ? '#fa8c16' : n.color },
          shape: 'dot',
          size: highlighted ? 36 : isLargeGraph ? 22 : 28,
        };
      });

      const isDagre = graphLayoutType === 'dagre';

      // 等待浏览器完成布局，确保容器有正确尺寸
      await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
      if (individualGraphRenderEpoch.current !== epoch) { console.log('[个体关系图] epoch 过期(rAF后)'); return; }

      console.log('[个体关系图] after rAF container size:', container.offsetWidth, 'x', container.offsetHeight,
        'nodes:', nodes.length, 'edges:', edges.length);

      // 二次确认容器尺寸
      if (container.offsetWidth === 0 || container.offsetHeight === 0) {
        console.log('[个体关系图] rAF后容器尺寸为0，放弃渲染');
        return;
      }

      const network = new Network(container, { nodes, edges }, {
        layout: isDagre
          ? { hierarchical: { direction: 'UD', sortMethod: 'directed', nodeSpacing: 120, levelSeparation: 150 } }
          : undefined,
        physics: isDagre ? false : {
          solver: 'forceAtlas2Based',
          forceAtlas2Based: { gravitationalConstant: -40, centralGravity: 0.005, springLength: 150, springConstant: 0.04 },
          stabilization: { iterations: 100 },
        },
        interaction: { dragNodes: true, dragView: true, zoomView: true, hover: true },
      });

      network.on('click', (params: any) => {
        if (params.nodes.length > 0) {
          const nodeId = params.nodes[0];
          const n = nodeMap.get(nodeId as string);
          if (n?.iri) {
            loadIndividualDetail(n.iri);
          }
        }
      });

      if (individualGraphRenderEpoch.current !== epoch) { console.log('[个体关系图] epoch 过期(render后)'); return; }
      console.log('[个体关系图] 渲染完成');
      try { network.fit({ animation: { duration: 400, easingFunction: 'easeInOutQuad' } }); } catch { /* ignore */ }
      individualGraphRef.current = network;

      setGraphRenderedStats({ nodes: visibleNodeIds.size, edges: visibleEdges.length, totalNodes, totalEdges });
      console.log('[个体关系图] 渲染完成, 节点:', visibleNodeIds.size, '边:', visibleEdges.length);
    } catch (e: any) {
      const errMsg = e?.message || e?.toString() || '未知错误';
      console.error('个体关系图渲染失败:', e);
      // 渲染失败时，从 filteredRelations 计算实际节点/边数并更新统计
      const nodeSet = new Set<string>();
      const edgeSet = new Set<string>();
      filteredRelations.forEach((r: any) => {
        if (r._standalone) { nodeSet.add(r.subject); return; }
        if (r.subject) nodeSet.add(r.subject);
        if (r.object) nodeSet.add(r.object);
        edgeSet.add(`${r.subject}|${r.predicate}|${r.object}`);
      });
      setGraphRenderedStats({ nodes: nodeSet.size, edges: edgeSet.size, totalNodes: nodeSet.size, totalEdges: edgeSet.size });
      // 如果是搜索模式且渲染失败，回退显示独立节点
      if (graphIsSearchMode) {
        const fallbackIris: string[] = Array.from(nodeSet);
        if (fallbackIris.length > 0) {
          setGraphSearchHighlightNodes(new Set(fallbackIris));
          setSearchStandaloneIndividuals(fallbackIris.slice(0, 50));
          message.warning(`图形渲染失败: ${errMsg}，已切换为独立节点展示`, 8);
        }
      }
    }
  };

  // 个体关系图渲染
  useEffect(() => {
    if (filteredRelations.length === 0) return;
    // 递增 epoch，使之前正在进行的异步渲染作废
    individualGraphRenderEpoch.current += 1;
    const currentEpoch = individualGraphRenderEpoch.current;
    const container = individualGraphContainerRef.current;
    console.log('[个体关系图 useEffect] 触发渲染, epoch:', currentEpoch, 'container:', !!container, 'filteredRelations:', filteredRelations.length, 'container size:', container?.offsetWidth, 'x', container?.offsetHeight);
    renderIndividualGraph(currentEpoch);
    return () => {
      safeDestroyNetwork(individualGraphRef);
    };
  }, [filteredRelations, graphLayoutType, graphIsSearchMode, graphSearchHighlightNodes, graphMaxNodes, graphHideLeafNodes, graphRenderVersion]);

  // 独立节点（HTML 可视化）的统计更新
  useEffect(() => {
    const count = searchStandaloneIndividuals.length;
    if (count > 0) {
      setGraphRenderedStats({ nodes: count, edges: 0, totalNodes: count, totalEdges: 0 });
    }
  }, [searchStandaloneIndividuals]);

  // 关系数据统计兜底：无论图渲染是否成功，filteredRelations 变化时同步更新 stats
  // 避免因容器尺寸为0、渲染失败等导致 stats 卡在 "0节点 0条边"
  useEffect(() => {
    if (filteredRelations.length === 0) return;
    const nodeSet = new Set<string>();
    const edgeSet = new Set<string>();
    filteredRelations.forEach((r: any) => {
      if (r._standalone) { nodeSet.add(r.subject); return; }
      if (r.subject) nodeSet.add(r.subject);
      if (r.object) nodeSet.add(r.object);
      edgeSet.add(`${r.subject}|${r.predicate}|${r.object}`);
    });
    setGraphRenderedStats({ nodes: nodeSet.size, edges: edgeSet.size, totalNodes: nodeSet.size, totalEdges: edgeSet.size });
  }, [filteredRelations]);

  const shortenUri = (uri: string): string => {
    if (!uri) return '';
    // 移除尖括号
    const clean = uri.replace(/^<|>$/g, '');
    // 解码 URL 编码字符（如 %E5%B9%BF → 广）
    let decoded = clean;
    try { decoded = decodeURIComponent(clean); } catch { /* 解码失败则使用原始值 */ }
    const lastSlash = decoded.lastIndexOf('/');
    const lastHash = decoded.lastIndexOf('#');
    const idx = Math.max(lastSlash, lastHash);
    if (idx > 0 && idx < decoded.length - 1) {
      return decoded.substring(idx + 1);
    }
    // 处理前缀:本地名 形式（如 "prefix:LocalName" 或 ":LocalName"）
    const lastColon = decoded.lastIndexOf(':');
    if (lastColon >= 0 && lastColon < decoded.length - 1) {
      return decoded.substring(lastColon + 1);
    }
    return decoded;
  };

  // 截断过长标签，用于关系图节点显示
  const truncateLabel = (label: string, maxLen: number = 18): string => {
    if (!label || label.length <= maxLen) return label;
    return label.substring(0, maxLen) + '...';
  };

  // 解析类的有效 IRI：优先使用 node.iri（如果是完整 HTTP URL），
  // 否则从 classStats（TDB2 统计结果）中通过多种策略匹配
  const resolveClassIri = (iri: string | undefined, label: string): string | null => {
    if (!iri) return null;
    // 移除尖括号（部分数据源可能包裹 < >）
    const cleanIri = iri.replace(/^<|>$/g, '');
    // 1. 如果清理后的 IRI 是完整的 HTTP(S) URL，直接使用
    if (cleanIri.startsWith('http://') || cleanIri.startsWith('https://')) {
      return cleanIri;
    }
    // 2. 在 classStats 中多策略匹配
    const shortIri = shortenUri(cleanIri);
    const matched = classStats.find((s: any) => {
      const statIri = s.type;
      const statShort = shortenUri(statIri);
      // 2a. 类标签匹配统计中的短名称
      if (label && statShort === label) return true;
      // 2b. 类IRI短名称匹配（如 ElectricCustomer === ElectricCustomer）
      if (shortIri && statShort === shortIri) return true;
      // 2c. 完整 IRI 直接相等
      if (statIri === cleanIri) return true;
      // 2d. 不区分大小写短名称匹配
      if (shortIri && statShort.toLowerCase() === shortIri.toLowerCase()) return true;
      return false;
    });
    return matched?.type || null;
  };

  // 构建类继承树
  const buildClassTree = (classes: any[]): any[] => {
    const map = new Map<string, any>();
    const roots: any[] = [];

    classes.forEach((cls: any, i: number) => {
      const key = cls.iri || cls.id || `class-${i}`;
      map.set(key, { ...cls, key, children: [] as any[] });
    });

    classes.forEach((cls: any, i: number) => {
      const key = cls.iri || cls.id || `class-${i}`;
      const node = map.get(key)!;
      if (cls.parentIri && map.has(cls.parentIri)) {
        map.get(cls.parentIri)!.children.push(node);
      } else {
        roots.push(node);
      }
    });

    return roots;
  };

  const renderTreeNodes = (nodes: any[]): React.ReactNode => {
    return nodes.map((node, idx) => {
      const actualIri = resolveClassIri(node.iri, node.label);

      return (
        <div key={node.key || idx} style={{ marginBottom: 4 }}>
          <div
            style={{
              padding: '6px 12px',
              borderRadius: 6,
              background: actualIri ? '#fafafa' : '#f5f5f5',
              border: '1px solid #e8e8e8',
              cursor: actualIri ? 'pointer' : 'not-allowed',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              transition: 'all 0.2s',
              opacity: actualIri ? 1 : 0.6,
            }}
            onClick={() => {
              if (actualIri) {
                loadInstancesForClass(actualIri);
              } else {
                message.warning(`类 "${node.label}" 缺少有效的 IRI，无法查询实例数据`);
              }
            }}
          >
            <div style={{
              width: 10, height: 10, borderRadius: '50%',
              background: NODE_COLORS[(schemaClasses.findIndex((c: any) =>
                (c.iri || c.id) === (node.iri || node.id)
              )) % NODE_COLORS.length] || '#1677ff',
            }} />
            <Text strong style={{ fontSize: 13 }}>{node.label || shortenUri(node.iri || '')}</Text>
            {node.description && (
              <Tooltip title={node.description}>
                <InfoCircleOutlined style={{ color: '#999', fontSize: 11 }} />
              </Tooltip>
            )}
          </div>
          {node.children && node.children.length > 0 && (
            <div style={{ marginLeft: 24, borderLeft: '1px dashed #d9d9d9', paddingLeft: 12 }}>
              {renderTreeNodes(node.children)}
            </div>
          )}
        </div>
      );
    });
  };

  const classTree = buildClassTree(schemaClasses);

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Text style={{ fontSize: 20, fontWeight: 600 }}><DatabaseOutlined /> 本体知识库管理</Text>
      </div>

      {/* 主体区域：左侧租户-实例-版本树 + 右侧详情 */}
      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        {/* 左侧：租户→实例→版本 选择面板 */}
        <Card
          size="small"
          title={
            <Space>
              <TeamOutlined />
              <span>本体知识库列表</span>
            </Space>
          }
          extra={
            <Button size="small" icon={<ReloadOutlined />} onClick={loadOntologies} loading={loading} />
          }
          style={{ width: 200, flexShrink: 0 }}
          styles={{ body: { padding: 8, maxHeight: 'calc(100vh - 220px)', overflow: 'auto' } }}
        >
          {loading ? (
            <div style={{ textAlign: 'center', padding: 40 }}><Spin size="small" /></div>
          ) : Object.keys(groupedTasks).length === 0 ? (
            <Empty description="暂无已完成的实例化版本" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            Object.entries(groupedTasks).map(([tenantId, nameMap]) => {
              const tenant = tenants.find(t => t.id === tenantId);
              const tenantName = tenant?.name || tenantId;
              const nameEntries = Object.entries(nameMap);
              return (
                <div key={tenantId} style={{ marginBottom: 12 }}>
                  {/* 租户标题 */}
                  <div style={{
                    display: 'flex', alignItems: 'center', gap: 6,
                    padding: '6px 8px', background: '#f0f5ff', borderRadius: 6,
                    marginBottom: 6, fontWeight: 600, fontSize: 13,
                  }}>
                    <CloudServerOutlined style={{ color: '#1677ff', fontSize: 14 }} />
                    <span style={{ flex: 1 }}>{tenantName}</span>
                    <Tag style={{ fontSize: 10, margin: 0 }}>{nameEntries.length} 实例</Tag>
                  </div>
                  {/* 实例名称 → 版本列表 */}
                  {nameEntries.map(([name, versions]) => (
                    <div key={name} style={{ marginBottom: 4, marginLeft: 4 }}>
                      <div
                        onClick={() => {
                          if (versions.length > 0) {
                            setSelectedOntologyId(versions[0].id);
                          }
                        }}
                        style={{
                          fontSize: 12, fontWeight: 500, color: '#555',
                          padding: '2px 8px', display: 'flex', alignItems: 'center', gap: 4,
                          cursor: 'pointer', borderRadius: 4,
                          background: versions.some(v => v.id === selectedOntologyId) ? '#f5f5f5' : 'transparent',
                        }}
                      >
                        <HistoryOutlined style={{ fontSize: 11 }} />
                        <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {name}
                        </span>
                      </div>
                      {/* 版本列表 */}
                      {versions.map((v) => (
                        <div
                          key={v.id}
                          onClick={() => { setSelectedOntologyId(v.id); }}
                          style={{
                            marginLeft: 16, marginBottom: 2, padding: '4px 8px',
                            borderRadius: 4, cursor: 'pointer', fontSize: 12,
                            display: 'flex', alignItems: 'center', gap: 6,
                            background: selectedOntologyId === v.id ? '#f5f5f5' : 'transparent',
                            transition: 'all 0.15s',
                          }}
                        >
                          {v.version != null && (
                            <Tag color="default" style={{ fontSize: 10, margin: 0, lineHeight: '16px' }}>
                              v{v.version}
                            </Tag>
                          )}
                          <span style={{ flex: 1, color: '#666', fontSize: 11, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {v.versionName || ''}
                          </span>
                        </div>
                      ))}
                    </div>
                  ))}
                </div>
              );
            })
          )}
        </Card>

        {/* 右侧：详情面板 */}
        <div style={{ flex: 1, minWidth: 0 }}>
          {!selectedOntologyId ? (
            <Card size="small">
              <Empty description={
                <span>
                  请从左侧选择一个本体实例化版本<br />
                  <Text type="secondary" style={{ fontSize: 12 }}>按 租户 → 实例名称 → 版本 选择要浏览的知识库</Text>
                </span>
              } />
            </Card>
          ) : (
            <>
              {/* 知识库名称标题 */}
              <div style={{ marginBottom: 16 }}>
                <Text style={{ fontSize: 20, fontWeight: 600 }}>
                  {selectedOntology?.name || '知识库'}
                </Text>
                {selectedOntology?.tenantId && (
                  <Tag style={{ marginLeft: 8 }} color="blue">
                    {tenants.find(t => t.id === selectedOntology.tenantId)?.name || selectedOntology.tenantId}
                  </Tag>
                )}
                {selectedOntology?.version != null && (
                  <Tag style={{ marginLeft: 4 }}>v{selectedOntology.version}</Tag>
                )}
              </div>
              <Tabs
              defaultActiveKey="metadata"
              type="card"
              items={[
            {
              key: 'metadata',
              label: <span><InfoCircleOutlined /> 本体知识库概览</span>,
              children: (
                <div>
                  {metadataLoading ? (
                    <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" tip="加载中..." /></div>
                  ) : (
                    <>
                      <Row gutter={16} style={{ marginBottom: 24 }}>
                        <Col span={4}>
                          <Card size="small">
                            <Statistic title="三元组总数" value={tripleCount ?? '-'} prefix={<DatabaseOutlined style={{ fontSize: 12 }} />} valueStyle={{ fontSize: 18 }} />
                          </Card>
                        </Col>
                        <Col span={4}>
                          <Card size="small">
                            <Statistic title="实例总数" value={instanceCount ?? '-'} prefix={<TableOutlined style={{ fontSize: 12 }} />} valueStyle={{ fontSize: 18 }} />
                          </Card>
                        </Col>
                        <Col span={4}>
                          <Card size="small">
                            <Statistic title="有实例的类" value={classStats.length || '-'} prefix={<ApartmentOutlined style={{ fontSize: 12 }} />} valueStyle={{ fontSize: 18 }} />
                          </Card>
                        </Col>
                        <Col span={3}>
                          <Card size="small">
                            <Statistic title="对象属性" value={objectProperties.length} prefix={<ExpandOutlined style={{ fontSize: 12 }} />} valueStyle={{ fontSize: 18 }} />
                          </Card>
                        </Col>
                        <Col span={3}>
                          <Card size="small">
                            <Statistic title="数据属性" value={dataProperties.length} prefix={<TableOutlined style={{ fontSize: 12 }} />} valueStyle={{ fontSize: 18 }} />
                          </Card>
                        </Col>
                        <Col span={3}>
                          <Card size="small">
                            <Statistic title="SWRL规则" value={swrlStats.tdb2SwrlCount} prefix={<SafetyCertificateOutlined style={{ fontSize: 12 }} />} valueStyle={{ color: '#722ed1', fontSize: 18 }} />
                          </Card>
                        </Col>
                        <Col span={3}>
                          <Card size="small">
                            <Statistic title="业务规则(DB)" value={rulesData.length} prefix={<SafetyCertificateOutlined style={{ fontSize: 12 }} />} valueStyle={{ color: '#1677ff', fontSize: 18 }} />
                          </Card>
                        </Col>
                      </Row>

                      <Card
                        title={<span><TableOutlined /> 类实例统计</span>}
                        size="small"
                        extra={<Button size="small" icon={<ReloadOutlined />} onClick={loadMetadata}>刷新</Button>}
                      >
                        <Table
                          size="small"
                          bordered
                          dataSource={classStats.map((r: any, i: number) => ({
                            key: i,
                            className: shortenUri(r.type),
                            label: r.label || '',
                            count: Number(r.count),
                            iri: r.type,
                          }))}
                          columns={[
                            {
                              title: '序号', width: 60,
                              render: (_: any, __: any, idx: number) => idx + 1,
                            },
                            {
                              title: '类名称', dataIndex: 'className', key: 'className',
                              render: (v: string) => <Text strong>{v}</Text>,
                            },
                            {
                              title: '中文标签', dataIndex: 'label', key: 'label', width: 160,
                              render: (v: string) => v ? <Tag color="blue">{v}</Tag> : <Text type="secondary">-</Text>,
                            },
                            {
                              title: '实例数量', dataIndex: 'count', key: 'count',
                              width: 120,
                              sorter: (a: any, b: any) => a.count - b.count,
                              render: (v: number) => <Badge count={v} style={{ backgroundColor: '#1677ff' }} />,
                            },
                            {
                              title: '类 IRI', dataIndex: 'iri', key: 'iri',
                              ellipsis: true,
                              render: (v: string) => <Tooltip title={v}><Text type="secondary" style={{ fontSize: 11 }}>{shortenUri(v)}</Text></Tooltip>,
                            },
                          ]}
                          pagination={{ defaultPageSize: 20, size: 'small', showTotal: (t) => `共 ${t} 条`, showSizeChanger: true, pageSizeOptions: ['10', '20', '50', '100'] }}
                        />
                      </Card>

                      {/* SWRL 规则概览 */}
                      <Card
                        title={<span><SafetyCertificateOutlined /> SWRL 规则概览</span>}
                        size="small"
                        style={{ marginTop: 16 }}
                        extra={<Button size="small" icon={<ReloadOutlined />} onClick={loadRulesData} loading={rulesLoading}>刷新</Button>}
                      >
                        <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
                          {/* 左侧：DB 规则 */}
                          <Col span={9}>
                            <Card
                              size="small"
                              hoverable
                              style={{
                                textAlign: 'center', background: swrlActiveCard === 'db' ? '#bae7ff' : '#e6f7ff',
                                borderColor: swrlActiveCard === 'db' ? '#1677ff' : '#91d5ff',
                                cursor: 'pointer',
                              }}
                              onClick={() => setSwrlActiveCard(swrlActiveCard === 'db' ? null : 'db')}
                            >
                              <Statistic
                                title="DB 中 SWRL 类型规则"
                                value={swrlStats.dbSwrlCount}
                                prefix={<DatabaseOutlined />}
                                valueStyle={{ color: '#1677ff' }}
                              />
                              <Text type="secondary" style={{ fontSize: 11 }}>点击查看详情</Text>
                            </Card>
                          </Col>
                          {/* 中间：流转箭头 */}
                          <Col span={6} style={{ textAlign: 'center' }}>
                            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
                              <Text type="secondary" style={{ fontSize: 11 }}>TTL导出 → 实例化加载</Text>
                              <ArrowRightOutlined style={{ fontSize: 28, color: '#8c8c8c' }} />
                            </div>
                          </Col>
                          {/* 右侧：TDB2 规则 */}
                          <Col span={9}>
                            <Card
                              size="small"
                              hoverable
                              style={{
                                textAlign: 'center', background: swrlActiveCard === 'tdb2' ? '#efdbff' : '#f9f0ff',
                                borderColor: swrlActiveCard === 'tdb2' ? '#722ed1' : '#d3adf7',
                                cursor: 'pointer',
                              }}
                              onClick={() => setSwrlActiveCard(swrlActiveCard === 'tdb2' ? null : 'tdb2')}
                            >
                              <Statistic
                                title="TDB2 中 SWRL 规则"
                                value={swrlStats.tdb2SwrlCount}
                                prefix={<SafetyCertificateOutlined />}
                                valueStyle={{ color: '#722ed1' }}
                              />
                              <Text type="secondary" style={{ fontSize: 11 }}>点击查看详情</Text>
                            </Card>
                          </Col>
                        </Row>

                        {/* 点击卡片后展示的详情列表 */}
                        {swrlActiveCard === 'tdb2' && (
                          <Card size="small" title="TDB2 中的 SWRL 规则列表" style={{ marginBottom: 12 }}>
                            {swrlSparqlRules.length > 0 ? (
                              <Table
                                size="small" bordered
                                dataSource={swrlSparqlRules.map((r: any, i: number) => {
                                  const classNames = r.classNames ? r.classNames.split(' | ') : [];
                                  const classIris = r.classIris ? r.classIris.split(' | ') : [];
                                  const bodyExpr = formatSwrlAtomExpr(r.bodyExpr || '');
                                  const headExpr = formatSwrlAtomExpr(r.headExpr || '');
                                  const swrlExpr = bodyExpr && headExpr ? `${bodyExpr} → ${headExpr}` : (bodyExpr || headExpr || '');
                                  return { key: i, rule: r.rule || '', label: r.ruleName || r.label || '', swrlExpr, classNames, classIris };
                                })}
                                columns={[
                                  { title: '序号', width: 60, render: (_: any, __: any, idx: number) => idx + 1 },
                                  { title: '规则名称', dataIndex: 'label', width: 220,
                                    render: (v: string, record: any) => {
                                      const ruleIri = record.rule || '';
                                      const display = v || ruleIri.split('#').pop() || ruleIri.split('/').pop() || '(未命名)';
                                      return <Tooltip title={ruleIri}><Text strong>{display}</Text></Tooltip>;
                                    },
                                  },
                                  { title: '规则 IRI', dataIndex: 'rule', width: 160, ellipsis: true,
                                    render: (v: string) => <Tooltip title={v}><Text copyable style={{ fontSize: 11 }}>{shortenUri(v)}</Text></Tooltip>,
                                  },
                                  { title: 'SWRL 表达式', dataIndex: 'swrlExpr', ellipsis: true,
                                    render: (v: string) => v ? <Tooltip title={v}><Text style={{ fontSize: 12 }}>{v}</Text></Tooltip> : <Text type="secondary">-</Text>,
                                  },
                                  { title: '涉及的类', dataIndex: 'classNames', width: 180,
                                    render: (v: string[]) => v && v.length > 0 ? v.map((c: string, i: number) => <Tag key={i} color="blue">{c}</Tag>) : <Text type="secondary">-</Text>,
                                  },
                                ]}
                                pagination={{ defaultPageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 条` }}
                              />
                            ) : <Empty description="暂无 TDB2 中的 SWRL 规则" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
                          </Card>
                        )}

                        {swrlActiveCard === 'db' && (
                          <Card size="small" title="DB 中的 SWRL 类型规则列表" style={{ marginBottom: 12 }}>
                            {(() => {
                              const swrlDbRules = (rulesData || []).filter((r: any) => r.type === 'SWRL' || r.swrlRule);
                              return swrlDbRules.length > 0 ? (
                                <Table
                                  size="small" bordered
                                  dataSource={swrlDbRules.map((r: any, i: number) => {
                                    let classNames: string[] = [];
                                    let classIris: string[] = [];
                                    // 构建 IRI -> label 映射（从 schemaClasses 获取中文名称）
                                    const classLabelMap: Record<string, string> = {};
                                    (schemaClasses || []).forEach((cls: any) => {
                                      if (cls.iri && cls.label) classLabelMap[cls.iri] = cls.label;
                                    });
                                    try {
                                      const parsed = typeof r.involvedClasses === 'string' ? JSON.parse(r.involvedClasses) : (Array.isArray(r.involvedClasses) ? r.involvedClasses : []);
                                      classNames = parsed.map((iri: string) => classLabelMap[iri] || shortenUri(iri));
                                      classIris = parsed;
                                    } catch { /* ignore parse error */ }
                                    return { key: i, name: r.name || '', swrlRule: r.swrlRule || '', classNames, classIris };
                                  })}
                                  columns={[
                                    { title: '序号', width: 60, render: (_: any, __: any, idx: number) => idx + 1 },
                                    { title: '规则名称', dataIndex: 'name', width: 180, render: (v: string) => <Text strong>{v || '(未命名)'}</Text> },
                                    { title: 'SWRL 表达式', dataIndex: 'swrlRule', ellipsis: true,
                                      render: (v: string) => v ? <Tooltip title={v}><Text style={{ fontSize: 12 }}>{v}</Text></Tooltip> : <Text type="secondary">-</Text>,
                                    },
                                    { title: '涉及的类', dataIndex: 'classNames', width: 180,
                                      render: (v: string[]) => v && v.length > 0 ? v.map((c: string, i: number) => <Tag key={i} color="blue">{c}</Tag>) : <Text type="secondary">-</Text>,
                                    },
                                    { title: '类 IRI', dataIndex: 'classIris',
                                      render: (v: string[]) => v && v.length > 0 ? v.map((iri: string, i: number) => <Tooltip key={i} title={iri}><Tag><Text copyable style={{ fontSize: 11 }}>{shortenUri(iri)}</Text></Tag></Tooltip>) : <Text type="secondary">-</Text>,
                                    },
                                  ]}
                                  pagination={{ defaultPageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 条` }}
                                />
                              ) : <Empty description="暂无 DB 中的 SWRL 类型规则" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
                            })()}
                          </Card>
                        )}
                      </Card>
                    </>
                  )}
                </div>
              ),
            },
            {
              key: 'schema',
              label: <span><ApartmentOutlined /> Schema 浏览</span>,
              children: (
                <div>
                  {schemaLoading ? (
                    <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" tip="加载中..." /></div>
                  ) : schemaClasses.length === 0 ? (
                    <Empty description="未找到 Schema 数据" />
                  ) : (
                    <>
                      <Space style={{ marginBottom: 16 }}>
                        <Text strong>视图模式：</Text>
                        <Select
                          value={viewMode}
                          onChange={setViewMode}
                          style={{ width: 120 }}
                          options={[
                            { value: 'tree', label: '树形结构' },
                            { value: 'graph', label: '图形可视化' },
                          ]}
                        />
                        <Divider type="vertical" />
                        <Tag color="blue">{schemaClasses.length} 个类</Tag>
                        <Tag color="purple">{schemaProperties.length} 个属性</Tag>
                        <Button size="small" icon={<ReloadOutlined />} onClick={loadSchemaData}>刷新</Button>
                      </Space>

                      {viewMode === 'tree' ? (
                        <>
                          <div style={{ display: 'flex', gap: 24 }}>
                            {/* 类层次结构（左侧） */}
                            <Card
                              title={<span><ApartmentOutlined /> 类层次结构 <Text type="secondary" style={{ fontSize: 12, fontWeight: 'normal' }}>（点击类名可查看其实例数据）</Text></span>}
                              size="small"
                              style={{ flex: 1, minWidth: 0, minHeight: 400, maxHeight: 600, overflow: 'auto' }}
                            >
                              {classTree.length > 0 ? (
                                renderTreeNodes(classTree)
                              ) : (
                                schemaClasses.map((cls: any, i: number) => {
                                  const actualIri = resolveClassIri(cls.iri, cls.label);

                                  return (
                                  <div key={cls.iri || i} style={{ marginBottom: 4 }}>
                                    <div
                                      style={{
                                        padding: '6px 12px', borderRadius: 6,
                                        background: actualIri ? '#fafafa' : '#f5f5f5',
                                        border: '1px solid #e8e8e8',
                                        cursor: actualIri ? 'pointer' : 'not-allowed',
                                        display: 'inline-flex', alignItems: 'center', gap: 6,
                                        opacity: actualIri ? 1 : 0.6,
                                      }}
                                      onClick={() => {
                                        if (actualIri) {
                                          loadInstancesForClass(actualIri);
                                        } else {
                                          message.warning(`类 "${cls.label}" 缺少有效的 IRI，无法查询实例数据`);
                                        }
                                      }}
                                    >
                                      <div style={{
                                        width: 10, height: 10, borderRadius: '50%',
                                        background: NODE_COLORS[i % NODE_COLORS.length],
                                      }} />
                                      <Text strong style={{ fontSize: 13 }}>{cls.label || shortenUri(cls.iri || '')}</Text>
                                      {cls.description && (
                                        <Tooltip title={cls.description}>
                                          <InfoCircleOutlined style={{ color: '#999', fontSize: 11 }} />
                                        </Tooltip>
                                      )}
                                    </div>
                                  </div>
                                );})
                              )}
                            </Card>

                            {/* 实例数据（右侧） */}
                            {selectedClass ? (
                              <Card
                                title={<span><TableOutlined /> 实例数据 - {shortenUri(selectedClass)}</span>}
                                size="small"
                                style={{ flex: 1, minWidth: 0, minHeight: 400, overflow: 'auto' }}
                                extra={
                                  <Button size="small" onClick={() => { setSelectedClass(''); setInstanceData([]); setInstanceColumns([]); }}>
                                    关闭
                                  </Button>
                                }
                              >
                                {instanceLoading ? (
                                  <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
                                ) : instanceData.length > 0 ? (
                                  <Table
                                    size="small"
                                    bordered
                                    dataSource={instanceData}
                                    columns={instanceColumns}
                                    pagination={{ defaultPageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 条`, showSizeChanger: true, pageSizeOptions: ['10', '20', '50'] }}
                                    scroll={{ x: 'max-content', y: 400 }}
                                  />
                                ) : (
                                  <Empty description="该类暂无实例数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                                )}
                              </Card>
                            ) : (
                              <Card
                                title={<span><TableOutlined /> 实例数据</span>}
                                size="small"
                                style={{ flex: 1, minWidth: 0, minHeight: 400, overflow: 'auto' }}
                              >
                                <Empty description="点击左侧类名可查看实例数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                              </Card>
                            )}
                          </div>

                          {/* 对象属性（下方） */}
                          <Card
                            title={<span><ExpandOutlined /> 对象属性</span>}
                            size="small"
                            style={{ marginTop: 16 }}
                          >
                            {schemaProperties.length > 0 ? (
                              <Table
                                size="small"
                                bordered
                                dataSource={schemaProperties.map((p: any, i: number) => ({
                                  key: p.id || i,
                                  name: p.label || shortenUri(p.iri || ''),
                                  iri: p.iri,
                                  source: p.sourceType || p.domain || '',
                                  target: p.targetType || p.range || '',
                                  description: p.description,
                                }))}
                                columns={[
                                  { title: '属性名', dataIndex: 'name', render: (v: string) => <Text strong>{v}</Text> },
                                  { title: '源类', dataIndex: 'source', render: (v: string) => <Tag color="blue">{shortenUri(v)}</Tag> },
                                  { title: '目标类', dataIndex: 'target', render: (v: string) => <Tag color="green">{shortenUri(v)}</Tag> },
                                ]}
                                pagination={{ defaultPageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 条`, showSizeChanger: true, pageSizeOptions: ['10', '20', '50'] }}
                              />
                            ) : (
                              <Empty description="无对象属性" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                            )}
                          </Card>

                          {/* 数据属性 */}
                          <Card
                            title={<span><TableOutlined /> 数据属性</span>}
                            size="small"
                            style={{ marginTop: 16 }}
                            extra={<Button size="small" icon={<ReloadOutlined />} onClick={loadDataProperties} loading={dataPropertiesLoading}>刷新</Button>}
                          >
                            {dataProperties.length > 0 ? (
                              <Table
                                size="small"
                                bordered
                                dataSource={dataProperties.map((p: any, i: number) => ({
                                  key: i,
                                  name: shortenUri(p.prop || ''),
                                  iri: p.prop || '',
                                  label: p.label || '',
                                }))}
                                columns={[
                                  { title: '属性名', dataIndex: 'name', render: (v: string) => <Text strong>{v}</Text> },
                                  { title: '标签', dataIndex: 'label', render: (v: string) => v ? <Tag color="cyan">{v}</Tag> : <Text type="secondary">-</Text> },
                                  { title: 'IRI', dataIndex: 'iri', ellipsis: true, render: (v: string) => <Tooltip title={v}><Text style={{ fontSize: 11 }} copyable>{v}</Text></Tooltip> },
                                ]}
                                pagination={{ defaultPageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 条`, showSizeChanger: true, pageSizeOptions: ['10', '20', '50'] }}
                              />
                            ) : (
                              <Empty description="无数据属性" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                            )}
                          </Card>

                          {/* SWRL 规则 */}
                          <Card
                            title={<span><SafetyCertificateOutlined /> SWRL 规则</span>}
                            size="small"
                            style={{ marginTop: 16 }}
                            extra={<Button size="small" icon={<ReloadOutlined />} onClick={loadRulesData} loading={rulesLoading}>刷新</Button>}
                          >
                            {swrlSparqlRules.length > 0 ? (
                              <Table
                                size="small"
                                bordered
                                dataSource={swrlSparqlRules.map((r: any, i: number) => {
                                  const classNames = r.classNames ? r.classNames.split(' | ') : [];
                                  const classIris = r.classIris ? r.classIris.split(' | ') : [];
                                  const bodyExpr = formatSwrlAtomExpr(r.bodyExpr || '');
                                  const headExpr = formatSwrlAtomExpr(r.headExpr || '');
                                  const swrlExpr = bodyExpr && headExpr ? `${bodyExpr} → ${headExpr}` : (bodyExpr || headExpr || '');
                                  return { key: i, rule: r.rule || '', label: r.ruleName || r.label || '', swrlExpr, classNames, classIris };
                                })}
                                columns={[
                                  { title: '序号', width: 60, render: (_: any, __: any, idx: number) => idx + 1 },
                                  { title: '规则名称', dataIndex: 'label', width: 220,
                                    render: (v: string, record: any) => {
                                      const ruleIri = record.rule || '';
                                      const display = v || ruleIri.split('#').pop() || ruleIri.split('/').pop() || '(未命名)';
                                      return <Tooltip title={ruleIri}><Text strong>{display}</Text></Tooltip>;
                                    },
                                  },
                                  { title: '规则 IRI', dataIndex: 'rule', width: 160, ellipsis: true,
                                    render: (v: string) => <Tooltip title={v}><Text copyable style={{ fontSize: 11 }}>{shortenUri(v)}</Text></Tooltip>,
                                  },
                                  { title: 'SWRL 表达式', dataIndex: 'swrlExpr', ellipsis: true,
                                    render: (v: string) => v ? <Tooltip title={v}><Text style={{ fontSize: 12 }}>{v}</Text></Tooltip> : <Text type="secondary">-</Text>,
                                  },
                                  { title: '涉及的类', dataIndex: 'classNames', width: 180,
                                    render: (v: string[]) => v && v.length > 0 ? v.map((c: string, i: number) => <Tag key={i} color="blue">{c}</Tag>) : <Text type="secondary">-</Text>,
                                  },
                                ]}
                                pagination={{ defaultPageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 条` }}
                              />
                            ) : (
                              <Empty description="暂无 SWRL 规则 (swrl:Imp)" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                            )}
                          </Card>
                        </>
                      ) : (
                        <Card
                          title={<span>Schema 图形可视化</span>}
                          size="small"
                          style={{ height: 600 }}
                          styles={{ body: { height: 'calc(100% - 57px)' } }}
                        >
                          <div ref={graphContainerRef} style={{ width: '100%', height: '100%' }} />
                        </Card>
                      )}

                      {/* 图形模式下的实例数据详情 */}
                      {viewMode === 'graph' && selectedClass && (
                        <Card
                          title={<span><TableOutlined /> 实例数据 - {shortenUri(selectedClass)}</span>}
                          size="small"
                          style={{ marginTop: 16 }}
                          extra={
                            <Button size="small" onClick={() => { setSelectedClass(''); setInstanceData([]); setInstanceColumns([]); }}>
                              关闭
                            </Button>
                          }
                        >
                          {instanceLoading ? (
                            <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
                          ) : instanceData.length > 0 ? (
                            <Table
                              size="small"
                              bordered
                              dataSource={instanceData}
                              columns={instanceColumns}
                              pagination={{ defaultPageSize: 20, size: 'small', showTotal: (t) => `共 ${t} 条`, showSizeChanger: true, pageSizeOptions: ['10', '20', '50', '100'] }}
                              scroll={{ x: 'max-content', y: 400 }}
                            />
                          ) : (
                            <Empty description="该类暂无实例数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                          )}
                        </Card>
                      )}
                    </>
                  )}
                </div>
              ),
            },
            {
              key: 'individual-graph',
              label: <span><ShareAltOutlined /> 个体关系图</span>,
              children: (
                <div>
                  {/* 控制栏 */}
                  <Card size="small" style={{ marginBottom: 16 }}>
                    <Space wrap size="small">
                      <Text strong style={{ fontSize: 12 }}>搜索个体：</Text>
                      <Input.Search
                        placeholder="输入关键字查找个体"
                        allowClear
                        size="small"
                        style={{ width: 260 }}
                        value={graphSearchKeyword}
                        onChange={(e) => {
                          const v = e.target.value;
                          setGraphSearchKeyword(v);
                          if (!v) clearSearch();
                        }}
                        onSearch={(val) => {
                          if (val.trim()) searchIndividuals(val);
                          else clearSearch();
                        }}
                        loading={graphSearchLoading}
                        enterButton={<><SearchOutlined /> 搜索</>}
                      />
                      {graphIsSearchMode && (
                        <Button size="small" onClick={clearSearch}>清除搜索</Button>
                      )}
                      {graphIsSearchMode && graphSearchHighlightNodes.size > 0 && (
                        <Tag color="orange">{graphSearchHighlightNodes.size} 个匹配个体</Tag>
                      )}
                      <Divider type="vertical" />
                      <Text strong style={{ fontSize: 12 }}>类过滤：</Text>
                      <Select
                        mode="multiple"
                        allowClear
                        size="small"
                        placeholder="按类筛选"
                        style={{ minWidth: 200, maxWidth: 400 }}
                        value={graphClassFilter}
                        onChange={(vals) => { setGraphClassFilter(vals); setGraphPage(0); }}
                        options={availableClasses.map((c) => ({
                          value: c.iri,
                          label: `${shortenUri(c.iri)} (${c.count})`,
                        }))}
                        maxTagCount={3}
                      />
                      <Divider type="vertical" />
                      <Text strong style={{ fontSize: 12 }}>属性过滤：</Text>
                      <Select
                        mode="multiple"
                        allowClear
                        size="small"
                        placeholder="按属性筛选"
                        style={{ minWidth: 200, maxWidth: 400 }}
                        value={graphPropFilter}
                        onChange={(vals) => { setGraphPropFilter(vals); setGraphPage(0); }}
                        options={availableProperties.map((p) => ({
                          value: p.iri,
                          label: `${shortenUri(p.iri)} (${p.count})`,
                        }))}
                        maxTagCount={3}
                      />
                      <Divider type="vertical" />
                      <Text strong style={{ fontSize: 12 }}>节点上限：</Text>
                      <Select
                        size="small"
                        value={graphMaxNodes}
                        onChange={(v) => setGraphMaxNodes(v)}
                        style={{ width: 100 }}
                        options={[
                          { value: 100, label: '100' },
                          { value: 300, label: '300' },
                          { value: 500, label: '500' },
                          { value: 1000, label: '1000' },
                          { value: 0, label: '全部' },
                        ]}
                      />
                      <Divider type="vertical" />
                      <Text strong style={{ fontSize: 12 }}>叶子节点：</Text>
                      <Button
                        size="small"
                        type={graphHideLeafNodes ? 'primary' : 'default'}
                        onClick={() => setGraphHideLeafNodes(!graphHideLeafNodes)}
                        style={{ fontSize: 11 }}
                      >
                        {graphHideLeafNodes ? '已隐藏' : '显示'}
                      </Button>
                      <Divider type="vertical" />
                      <Text strong style={{ fontSize: 12 }}>布局：</Text>
                      <Select
                        size="small"
                        value={graphLayoutType}
                        onChange={setGraphLayoutType}
                        style={{ width: 100 }}
                        options={[
                          { value: 'force', label: '力导向' },
                          { value: 'dagre', label: '层级图' },
                        ]}
                      />
                      <Divider type="vertical" />
                      <Tooltip title={`数据总计: ${graphRenderedStats.totalNodes} 节点 / ${graphRenderedStats.totalEdges} 边`}>
                        <Tag color="blue">{graphRenderedStats.nodes} 节点</Tag>
                      </Tooltip>
                      <Tag color="green">{graphRenderedStats.edges} 条边</Tag>
                      {graphRenderedStats.totalNodes > graphRenderedStats.nodes && (
                        <Tag color="warning" style={{ fontSize: 11 }}>
                          已截断 ({graphRenderedStats.nodes}/{graphRenderedStats.totalNodes})
                        </Tag>
                      )}
                      {!graphIsSearchMode && (
                        <Button size="small" icon={<ReloadOutlined />} onClick={() => loadIndividualRelations(0)} loading={graphDataLoading}>
                          刷新
                        </Button>
                      )}
                      {!graphIsSearchMode && hasMoreGraph && (
                        <Button size="small" icon={<ExpandOutlined />} onClick={() => loadIndividualRelations(graphPage + 1)} loading={graphDataLoading}>
                          加载更多
                        </Button>
                      )}
                    </Space>
                  </Card>

                  {/* 图 / 独立节点列表 */}
                  <Card
                    size="small"
                    style={{ height: 600, marginBottom: 16 }}
                    styles={{ body: { height: 'calc(100% - 57px)', padding: 0 } }}
                  >
                    {((graphDataLoading || graphSearchLoading) && filteredRelations.length === 0 && searchStandaloneIndividuals.length === 0) ? (
                      <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" tip="加载关系图..." /></div>
                    ) : (!graphIsSearchMode && graphRelations.length === 0 && searchStandaloneIndividuals.length === 0) ? (
                      <Empty description="该知识库暂无个体关系数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    ) : searchStandaloneIndividuals.length > 0 ? (
                      <div
                        id="standalone-html-graph"
                        style={{
                          position: 'relative',
                          width: '100%',
                          height: '100%',
                          minHeight: 500,
                          overflow: 'hidden',
                          background: '#fafafa',
                        }}
                      >
                        {searchStandaloneIndividuals.map((iri: string, i: number) => {
                          const total = searchStandaloneIndividuals.length;
                          const radiusPct = 35;
                          const cxPct = 50;
                          const cyPct = 50;
                          const angle = (2 * Math.PI * i) / total - Math.PI / 2;
                          const xPct = cxPct + radiusPct * Math.cos(angle);
                          const yPct = cyPct + radiusPct * Math.sin(angle);
                          const label = shortenUri(iri);
                          return (
                            <div
                              key={iri}
                              title={iri}
                              onClick={() => loadIndividualDetail(iri)}
                              style={{
                                position: 'absolute',
                                left: `${xPct}%`,
                                top: `${yPct}%`,
                                transform: 'translate(-50%, -50%)',
                                width: 72,
                                height: 72,
                                borderRadius: '50%',
                                background: 'linear-gradient(135deg, #1677ff 0%, #4096ff 100%)',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                cursor: 'pointer',
                                boxShadow: '0 4px 16px rgba(22,119,255,0.45)',
                                transition: 'transform 0.2s, box-shadow 0.2s',
                                border: '3px solid #fff',
                              }}
                              onMouseEnter={(e) => {
                                e.currentTarget.style.transform = 'translate(-50%, -50%) scale(1.15)';
                                e.currentTarget.style.boxShadow = '0 6px 24px rgba(22,119,255,0.65)';
                              }}
                              onMouseLeave={(e) => {
                                e.currentTarget.style.transform = 'translate(-50%, -50%) scale(1)';
                                e.currentTarget.style.boxShadow = '0 4px 16px rgba(22,119,255,0.45)';
                              }}
                            >
                              <span
                                style={{
                                  color: '#fff',
                                  fontSize: 10,
                                  fontWeight: 600,
                                  textAlign: 'center',
                                  lineHeight: 1.2,
                                  padding: '0 6px',
                                  overflow: 'hidden',
                                  textOverflow: 'ellipsis',
                                  display: '-webkit-box',
                                  WebkitLineClamp: 2,
                                  WebkitBoxOrient: 'vertical',
                                  wordBreak: 'break-all',
                                }}
                              >
                                {label}
                              </span>
                            </div>
                          );
                        })}
                      </div>
                    ) : filteredRelations.length === 0 ? (
                      <Empty description={graphIsSearchMode ? '未找到匹配的个体' : '当前过滤条件下无匹配数据'} image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    ) : (
                      <div
                        key={`individual-graph-${graphIsSearchMode ? 's' : 'b'}-${graphRenderVersion}`}
                        ref={individualGraphContainerRef}
                        style={{ width: '100%', height: '100%', minHeight: 500 }}
                      />
                    )}
                  </Card>

                  {/* 底部详情表：选中个体后展示 */}
                  {selectedIndividual && (
                    <Card
                      size="small"
                      title={<span><InfoCircleOutlined /> 个体详情 - <Text type="warning">{shortenUri(selectedIndividual)}</Text></span>}
                      extra={
                        <Button size="small" onClick={() => { setSelectedIndividual(null); setIndividualDetail([]); setIndividualDataProperties([]); setIndividualObjectProperties([]); }}>
                          关闭
                        </Button>
                      }
                    >
                      {individualDetailLoading ? (
                        <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
                      ) : individualDetail.length === 0 ? (
                        <Empty description="该个体无属性数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                      ) : (
                        <Row gutter={16}>
                          {/* 数据属性 */}
                          <Col span={12}>
                            <Card
                              size="small"
                              title={<span><DatabaseOutlined /> 数据属性 (Data Properties)</span>}
                              style={{ borderColor: '#1677ff' }}
                              styles={{ body: { padding: 0 } }}
                            >
                              {individualDataProperties.length === 0 ? (
                                <Empty description="无数据属性" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ padding: 20 }} />
                              ) : (
                                <Table
                                  size="small"
                                  bordered
                                  dataSource={individualDataProperties.map((r: any, i: number) => ({
                                    key: i,
                                    property: shortenUri(r.property),
                                    label: r.propertyLabel || '',
                                    value: r.value || '',
                                    propertyIri: r.property,
                                  }))}
                                  columns={[
                                    {
                                      title: '属性', dataIndex: 'property', key: 'property', width: '35%',
                                      render: (v: string, rec: any) => (
                                        <Tooltip title={rec.propertyIri}>
                                          <div><Text strong style={{ fontSize: 11 }}>{v}</Text>
                                          {rec.label && <div><Tag color="cyan" style={{ fontSize: 10, marginTop: 2 }}>{rec.label}</Tag></div>}</div>
                                        </Tooltip>
                                      ),
                                    },
                                    {
                                      title: '属性值', dataIndex: 'value', key: 'value', width: '65%',
                                      render: (v: string) => (
                                        <Tooltip title={v}><Text style={{ fontSize: 11 }}>{v.length > 80 ? v.substring(0, 80) + '...' : v}</Text></Tooltip>
                                      ),
                                    },
                                  ]}
                                  pagination={{ defaultPageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 条`, showSizeChanger: true, pageSizeOptions: ['10', '20', '50', '100'] }}
                                  scroll={{ y: 300 }}
                                />
                              )}
                            </Card>
                          </Col>
                          {/* 对象属性 */}
                          <Col span={12}>
                            <Card
                              size="small"
                              title={<span><ShareAltOutlined /> 对象属性 (Object Properties)</span>}
                              style={{ borderColor: '#52c41a' }}
                              styles={{ body: { padding: 0 } }}
                            >
                              {individualObjectProperties.length === 0 ? (
                                <Empty description="无对象属性" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ padding: 20 }} />
                              ) : (
                                <Table
                                  size="small"
                                  bordered
                                  dataSource={individualObjectProperties.map((r: any, i: number) => ({
                                    key: i,
                                    property: shortenUri(r.property),
                                    label: r.propertyLabel || '',
                                    value: shortenUri(r.value),
                                    propertyIri: r.property,
                                    valueIri: r.value,
                                  }))}
                                  columns={[
                                    {
                                      title: '属性', dataIndex: 'property', key: 'property', width: '35%',
                                      render: (v: string, rec: any) => (
                                        <Tooltip title={rec.propertyIri}>
                                          <div><Text strong style={{ fontSize: 11, color: '#52c41a' }}>{v}</Text>
                                          {rec.label && <div><Tag color="cyan" style={{ fontSize: 10, marginTop: 2 }}>{rec.label}</Tag></div>}</div>
                                        </Tooltip>
                                      ),
                                    },
                                    {
                                      title: '关联个体', dataIndex: 'value', key: 'value', width: '65%',
                                      render: (v: string, rec: any) => (
                                        <Tooltip title={rec.valueIri}>
                                          <Text style={{ fontSize: 11, color: '#1677ff', cursor: 'pointer' }}
                                            onClick={() => loadIndividualDetail(rec.valueIri)}>
                                            {v}
                                          </Text>
                                        </Tooltip>
                                      ),
                                    },
                                  ]}
                                  pagination={{ defaultPageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 条`, showSizeChanger: true, pageSizeOptions: ['10', '20', '50', '100'] }}
                                  scroll={{ y: 300 }}
                                />
                              )}
                            </Card>
                          </Col>
                        </Row>
                      )}
                    </Card>
                  )}
                </div>
              ),
            },
            {
              key: 'sparql',
              label: <span><CodeOutlined /> SPARQL 查询</span>,
              children: (
                <div>
                  <Alert
                    type="info"
                    showIcon
                    message="交互式 SPARQL 查询面板 - 可直接查询 Jena TDB2 中的本体数据"
                    style={{ marginBottom: 12 }}
                  />

                  <Space wrap style={{ marginBottom: 12 }}>
                    <Text type="secondary" style={{ fontSize: 12 }}>快速查询模板：</Text>
                    {SPARQL_TEMPLATES.map(t => (
                      <Tag
                        key={t.label}
                        color="blue"
                        style={{ cursor: 'pointer' }}
                        onClick={() => setSparqlQuery(t.query)}
                      >
                        {t.label}
                      </Tag>
                    ))}
                  </Space>

                  <TextArea
                    value={sparqlQuery}
                    onChange={e => setSparqlQuery(e.target.value)}
                    placeholder="输入 SPARQL 查询语句..."
                    style={{
                      fontFamily: 'Consolas, Monaco, "Courier New", monospace',
                      fontSize: 13,
                      marginBottom: 12,
                    }}
                    autoSize={{ minRows: 8, maxRows: 16 }}
                  />

                  <Space style={{ marginBottom: 16 }}>
                    <Button
                      type="primary"
                      icon={<SearchOutlined />}
                      loading={sparqlLoading}
                      onClick={handleRunSparql}
                    >
                      执行查询
                    </Button>
                    <Button
                      icon={<CopyOutlined />}
                      onClick={() => {
                        navigator.clipboard.writeText(sparqlQuery);
                        message.success('已复制');
                      }}
                    >
                      复制查询
                    </Button>
                    <Button onClick={() => { setSparqlQuery(''); setSparqlResult(null); }}>清空</Button>
                  </Space>

                  {sparqlLoading && (
                    <div style={{ textAlign: 'center', padding: 40 }}><Spin tip="查询中..." /></div>
                  )}

                  {sparqlResult && !sparqlLoading && (
                    <div>
                      {sparqlResult.error ? (
                        <Alert type="error" showIcon message="查询失败" description={sparqlResult.message || sparqlResult.error} />
                      ) : sparqlResult.variables && sparqlResult.variables.length > 0 ? (
                        <>
                          <Space style={{ marginBottom: 8 }}>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              返回 {sparqlResult.bindings?.length || 0} 条结果，{sparqlResult.variables.length} 个变量
                            </Text>
                          </Space>
                          <Table
                            size="small"
                            bordered
                            dataSource={(sparqlResult.bindings || []).map((row: any, idx: number) => {
                              const processed: any = { _key: idx };
                              sparqlResult.variables.forEach((v: string) => {
                                processed[v] = row[v] || '';
                              });
                              return processed;
                            })}
                            columns={sparqlResult.variables.map((v: string) => {
                              // 对 classType 列做特殊渲染，标记超类/继承类/中间类/独立类
                              if (v === 'classType') {
                                return {
                                  title: '类类型',
                                  dataIndex: v,
                                  key: v,
                                  width: 120,
                                  render: (val: string) => {
                                    const typeMap: Record<string, { color: string; label: string }> = {
                                      '超类': { color: 'gold', label: '超类 (Top Class)' },
                                      '中间类': { color: 'blue', label: '中间类 (Middle)' },
                                      '继承类': { color: 'green', label: '继承类 (Sub)' },
                                      '独立类': { color: 'default', label: '独立类 (Standalone)' },
                                    };
                                    const info = typeMap[val] || { color: 'default', label: val };
                                    return <Tag color={info.color}>{info.label}</Tag>;
                                  },
                                };
                              }
                              // 对 parentClass 列显示短名称
                              if (v === 'parentClass') {
                                return {
                                  title: '父类',
                                  dataIndex: v,
                                  key: v,
                                  ellipsis: true,
                                  width: 220,
                                  render: (val: string) => val ? (
                                    <Tooltip title={val}><Tag color="orange">{shortenUri(val)}</Tag></Tooltip>
                                  ) : <Text type="secondary">-</Text>,
                                };
                              }
                              // 对 class 列显示可复制的短名称
                              if (v === 'class') {
                                return {
                                  title: '类 IRI',
                                  dataIndex: v,
                                  key: v,
                                  ellipsis: true,
                                  width: 280,
                                  render: (val: string) => (
                                    <Tooltip title={val}>
                                      <Text copyable style={{ fontSize: 12, wordBreak: 'break-all' }}>{shortenUri(val)}</Text>
                                    </Tooltip>
                                  ),
                                };
                              }
                              return {
                                title: v,
                                dataIndex: v,
                                key: v,
                                ellipsis: true,
                                width: 220,
                                render: (val: string) => (
                                  <Tooltip title={val}>
                                    <Text style={{ fontSize: 12, wordBreak: 'break-all' }}>{shortenUri(val || '')}</Text>
                                  </Tooltip>
                                ),
                              };
                            })}
                            rowKey="_key"
                            scroll={{ x: 'max-content' }}
                            pagination={{ defaultPageSize: 50, size: 'small', showTotal: (t) => `共 ${t} 条`, showSizeChanger: true, pageSizeOptions: ['20', '50', '100', '200'] }}
                          />
                        </>
                      ) : (
                        <Empty description="查询无结果" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                      )}
                    </div>
                  )}
                </div>
              ),
            },
            {
              key: 'rules',
              label: <span><SafetyCertificateOutlined /> 规则浏览</span>,
              children: (
                <div>
                  <Space style={{ marginBottom: 16 }}>
                    <Button icon={<ReloadOutlined />} onClick={loadRulesData} loading={rulesLoading}>刷新规则</Button>
                    <Button icon={<SafetyCertificateOutlined />} onClick={loadSwrlDetails} loading={swrlDetailLoading}>加载 SWRL 详情</Button>
                    <Tag color="blue">{rulesData.length} 条业务规则</Tag>
                    <Tag color="purple">{swrlSparqlRules.length} 条 SWRL 规则 (TDB2)</Tag>
                    <Tag color="green">{swrlStats.dbSwrlCount} 条 SWRL 规则 (DB)</Tag>
                  </Space>

                  {rulesLoading ? (
                    <div style={{ textAlign: 'center', padding: 40 }}><Spin tip="加载规则中..." /></div>
                  ) : (
                    <>
                      {/* DB 规则表格 */}
                      <Card
                        title={<span><SafetyCertificateOutlined /> 业务规则列表</span>}
                        size="small"
                        style={{ marginBottom: 16 }}
                      >
                        {rulesData.length > 0 ? (
                          <Table
                            size="small"
                            bordered
                            dataSource={rulesData.map((r: any, i: number) => ({
                              key: r.id || i,
                              name: r.name || '',
                              type: r.type || '',
                              priority: r.priority || '',
                              swrlRule: r.swrlRule || '',
                              involvedClasses: r.involvedClasses || '',
                              involvedDataProperties: r.involvedDataProperties || '',
                              status: r.status || '',
                              condition: r.conditionJson || '',
                              action: r.actionJson || '',
                            }))}
                            columns={[
                              { title: '规则名称', dataIndex: 'name', key: 'name', width: 180,
                                render: (v: string) => <Text strong>{v}</Text>,
                              },
                              { title: '类型', dataIndex: 'type', key: 'type', width: 100,
                                render: (v: string) => {
                                  const colorMap: Record<string, string> = { SWRL: 'purple', CONDITION: 'blue', EVENT: 'green', TEMPORAL: 'orange', COMPOSITE: 'cyan' };
                                  return <Tag color={colorMap[v] || 'default'}>{v}</Tag>;
                                },
                              },
                              { title: '优先级', dataIndex: 'priority', key: 'priority', width: 80,
                                render: (v: string) => {
                                  const colorMap: Record<string, string> = { CRITICAL: 'red', HIGH: 'orange', MEDIUM: 'blue', LOW: 'default' };
                                  return <Tag color={colorMap[v] || 'default'}>{v}</Tag>;
                                },
                              },
                              { title: '状态', dataIndex: 'status', key: 'status', width: 80,
                                render: (v: string) => {
                                  const colorMap: Record<string, string> = { ACTIVE: 'green', DRAFT: 'default', DEPRECATED: 'red' };
                                  return <Tag color={colorMap[v] || 'default'}>{v}</Tag>;
                                },
                              },
                              { title: '涉及的类', dataIndex: 'involvedClasses', key: 'involvedClasses', width: 200,
                                render: (v: any) => {
                                  if (!v) return <Text type="secondary">-</Text>;
                                  const classLabelMap: Record<string, string> = {};
                                  (schemaClasses || []).forEach((cls: any) => {
                                    if (cls.iri && cls.label) classLabelMap[cls.iri] = cls.label;
                                  });
                                  let arr: any[] = [];
                                  if (typeof v === 'string') {
                                    try { arr = JSON.parse(v); } catch { return <Text style={{ fontSize: 11 }}>{v}</Text>; }
                                  } else if (Array.isArray(v)) {
                                    arr = v;
                                  } else {
                                    return <Text style={{ fontSize: 11 }}>{String(v)}</Text>;
                                  }
                                  if (!Array.isArray(arr) || arr.length === 0) return <Text type="secondary">-</Text>;
                                  return arr.map((iri: string) => {
                                    const label = classLabelMap[iri];
                                    return <Tag key={iri} color="blue" style={{ marginBottom: 2 }}>{label || shortUri(iri)}</Tag>;
                                  });
                                },
                              },
                              { title: '涉及的数据属性', dataIndex: 'involvedDataProperties', key: 'involvedDataProperties', width: 200,
                                render: (v: any) => {
                                  if (!v) return <Text type="secondary">-</Text>;
                                  let arr: any[] = [];
                                  if (typeof v === 'string') {
                                    try { arr = JSON.parse(v); } catch { return <Text style={{ fontSize: 11 }}>{v}</Text>; }
                                  } else if (Array.isArray(v)) {
                                    arr = v;
                                  } else {
                                    return <Text style={{ fontSize: 11 }}>{String(v)}</Text>;
                                  }
                                  if (!Array.isArray(arr) || arr.length === 0) return <Text type="secondary">-</Text>;
                                  return arr.map((iri: string) => <Tag key={iri} color="cyan" style={{ marginBottom: 2 }}>{shortUri(iri)}</Tag>);
                                },
                              },
                              { title: 'SWRL 表达式', dataIndex: 'swrlRule', key: 'swrlRule', width: 300,
                                render: (v: string) => v ? (
                                  <Tooltip title={v}>
                                    <Text code style={{ fontSize: 11, wordBreak: 'break-all' }}>{v.length > 60 ? v.substring(0, 60) + '...' : v}</Text>
                                  </Tooltip>
                                ) : <Text type="secondary">-</Text>,
                              },
                              { title: '条件', dataIndex: 'condition', key: 'condition', width: 200,
                                render: (v: string) => v ? (
                                  <Tooltip title={v}>
                                    <Text style={{ fontSize: 11 }}>{v.length > 40 ? v.substring(0, 40) + '...' : v}</Text>
                                  </Tooltip>
                                ) : <Text type="secondary">-</Text>,
                              },
                              { title: '动作', dataIndex: 'action', key: 'action', width: 200,
                                render: (v: string) => v ? (
                                  <Tooltip title={v}>
                                    <Text style={{ fontSize: 11 }}>{v.length > 40 ? v.substring(0, 40) + '...' : v}</Text>
                                  </Tooltip>
                                ) : <Text type="secondary">-</Text>,
                              },
                            ]}
                            pagination={{ defaultPageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 条`, showSizeChanger: true, pageSizeOptions: ['10', '20', '50'] }}
                            scroll={{ x: 'max-content' }}
                            expandable={{
                              expandedRowRender: (record: any) => (
                                <Descriptions size="small" bordered column={1}>
                                  {record.swrlRule && (
                                    <Descriptions.Item label="SWRL 规则表达式">
                                      <Text code style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>{record.swrlRule}</Text>
                                    </Descriptions.Item>
                                  )}
                                  {record.involvedClasses && (
                                    <Descriptions.Item label="涉及的类">
                                      {(() => {
                                        const classLabelMap: Record<string, string> = {};
                                        (schemaClasses || []).forEach((cls: any) => {
                                          if (cls.iri && cls.label) classLabelMap[cls.iri] = cls.label;
                                        });
                                        let arr: any[] = [];
                                        if (typeof record.involvedClasses === 'string') {
                                          try { arr = JSON.parse(record.involvedClasses); } catch { return <Text>{record.involvedClasses}</Text>; }
                                        } else if (Array.isArray(record.involvedClasses)) {
                                          arr = record.involvedClasses;
                                        } else {
                                          return <Text>{String(record.involvedClasses)}</Text>;
                                        }
                                        if (!Array.isArray(arr) || arr.length === 0) return <Text type="secondary">-</Text>;
                                        return arr.map((iri: string) => {
                                          const label = classLabelMap[iri];
                                          return <Tag key={iri} color="blue" style={{ marginBottom: 2 }}>{label || shortUri(iri)}</Tag>;
                                        });
                                      })()}
                                    </Descriptions.Item>
                                  )}
                                  {record.involvedDataProperties && (
                                    <Descriptions.Item label="涉及的数据属性">
                                      {(() => {
                                        let arr: any[] = [];
                                        if (typeof record.involvedDataProperties === 'string') {
                                          try { arr = JSON.parse(record.involvedDataProperties); } catch { return <Text>{record.involvedDataProperties}</Text>; }
                                        } else if (Array.isArray(record.involvedDataProperties)) {
                                          arr = record.involvedDataProperties;
                                        } else {
                                          return <Text>{String(record.involvedDataProperties)}</Text>;
                                        }
                                        if (!Array.isArray(arr) || arr.length === 0) return <Text type="secondary">-</Text>;
                                        return arr.map((iri: string) => <Tag key={iri} color="cyan" style={{ marginBottom: 2 }}>{shortUri(iri)}</Tag>);
                                      })()}
                                    </Descriptions.Item>
                                  )}
                                  {record.condition && (
                                    <Descriptions.Item label="条件 JSON">
                                      <Text style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>{record.condition}</Text>
                                    </Descriptions.Item>
                                  )}
                                  {record.action && (
                                    <Descriptions.Item label="动作 JSON">
                                      <Text style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>{record.action}</Text>
                                    </Descriptions.Item>
                                  )}
                                </Descriptions>
                              ),
                            }}
                          />
                        ) : (
                          <Empty description="暂无业务规则" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                        )}
                      </Card>

                      {/* TDB2 中的 SWRL 规则 */}
                      <Card
                        title={<span><CodeOutlined /> TDB2 中的 SWRL 规则 (SPARQL 查询)</span>}
                        size="small"
                        style={{ marginBottom: 16 }}
                      >
                        {swrlSparqlRules.length > 0 ? (
                          <Table
                            size="small"
                            bordered
                            dataSource={swrlSparqlRules.map((r: any, i: number) => {
                              const classNames = r.classNames ? r.classNames.split(' | ') : [];
                              const classIris = r.classIris ? r.classIris.split(' | ') : [];
                              const bodyExpr = formatSwrlAtomExpr(r.bodyExpr || '');
                              const headExpr = formatSwrlAtomExpr(r.headExpr || '');
                              const swrlExpr = bodyExpr && headExpr ? `${bodyExpr} → ${headExpr}` : (bodyExpr || headExpr || '');
                              return { key: i, rule: r.rule || '', label: r.ruleName || r.label || '', swrlExpr, classNames, classIris };
                            })}
                            columns={[
                              { title: '序号', width: 60, render: (_: any, __: any, idx: number) => idx + 1 },
                              { title: '规则名称', dataIndex: 'label', width: 220,
                                render: (v: string, record: any) => {
                                  const ruleIri = record.rule || '';
                                  const display = v || ruleIri.split('#').pop() || ruleIri.split('/').pop() || '(未命名)';
                                  return <Tooltip title={ruleIri}><Text strong>{display}</Text></Tooltip>;
                                },
                              },
                              { title: '规则 IRI', dataIndex: 'rule', width: 160, ellipsis: true,
                                render: (v: string) => <Tooltip title={v}><Text copyable style={{ fontSize: 11 }}>{shortenUri(v)}</Text></Tooltip>,
                              },
                              { title: 'SWRL 表达式', dataIndex: 'swrlExpr', ellipsis: true,
                                render: (v: string) => v ? <Tooltip title={v}><Text style={{ fontSize: 12 }}>{v}</Text></Tooltip> : <Text type="secondary">-</Text>,
                              },
                              { title: '涉及的类', dataIndex: 'classNames', width: 180,
                                render: (v: string[]) => v && v.length > 0 ? v.map((c: string, i: number) => <Tag key={i} color="blue">{c}</Tag>) : <Text type="secondary">-</Text>,
                              },
                            ]}
                            pagination={{ defaultPageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 条` }}
                          />
                        ) : (
                          <Empty description="TDB2 中暂无 SWRL 规则声明 (swrl:Imp)" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                        )}
                      </Card>

                      {/* SWRL 规则详情（含 body/head 原子分解） */}
                      <Card
                        title={<span><SafetyCertificateOutlined /> SWRL 规则详情（规则体/头分解）</span>}
                        size="small"
                        extra={<Button size="small" icon={<ReloadOutlined />} onClick={loadSwrlDetails} loading={swrlDetailLoading}>加载详情</Button>}
                      >
                        <Alert
                          type="info"
                          showIcon
                          message="点击「加载详情」按钮可获取 SWRL 规则的 body（条件）和 head（结论）原子分解信息"
                          style={{ marginBottom: 12 }}
                        />
                        {swrlDetailRules.length > 0 ? (
                          <Table
                            size="small"
                            bordered
                            dataSource={swrlDetailRules.map((r: any, i: number) => ({
                              key: i,
                              rule: r.rule || '',
                              label: r.label || '',
                              bodyAtoms: r.bodyAtoms || '',
                              headAtoms: r.headAtoms || '',
                            }))}
                            columns={[
                              { title: '序号', width: 60, render: (_: any, __: any, idx: number) => idx + 1 },
                              { title: '规则名称', dataIndex: 'label', width: 180,
                                render: (v: string) => v ? <Text strong>{v}</Text> : <Text type="secondary">(无标签)</Text>,
                              },
                              { title: '规则 IRI', dataIndex: 'rule', width: 250,
                                render: (v: string) => <Tooltip title={v}><Text copyable style={{ fontSize: 12 }}>{shortenUri(v)}</Text></Tooltip>,
                              },
                              { title: 'Body（条件原子）', dataIndex: 'bodyAtoms', width: 300,
                                render: (v: string) => v ? (
                                  <div>
                                    {v.split(' | ').map((atom: string, idx: number) => (
                                      <Tag key={idx} color="orange" style={{ marginBottom: 2 }}>{shortenUri(atom)}</Tag>
                                    ))}
                                  </div>
                                ) : <Text type="secondary">-</Text>,
                              },
                              { title: 'Head（结论原子）', dataIndex: 'headAtoms', width: 300,
                                render: (v: string) => v ? (
                                  <div>
                                    {v.split(' | ').map((atom: string, idx: number) => (
                                      <Tag key={idx} color="green" style={{ marginBottom: 2 }}>{shortenUri(atom)}</Tag>
                                    ))}
                                  </div>
                                ) : <Text type="secondary">-</Text>,
                              },
                            ]}
                            pagination={{ defaultPageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 条` }}
                            scroll={{ x: 'max-content' }}
                          />
                        ) : (
                          <Empty description="请点击「加载详情」按钮获取 SWRL 规则分解信息" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                        )}
                      </Card>
                    </>
                  )}
                </div>
              ),
            },
            {
              key: 'instances',
              label: <span><TableOutlined /> 实例数据浏览</span>,
              children: (
                <div>
                  {/* 按类统计实例数量 */}
                  <Card
                    title={<span><ApartmentOutlined /> 按类统计实例数量</span>}
                    size="small"
                    extra={<Button size="small" icon={<ReloadOutlined />} onClick={loadMetadata}>刷新</Button>}
                    style={{ marginBottom: 16 }}
                  >
                    {classStats.length > 0 ? (
                      <Table
                        size="small"
                        bordered
                        dataSource={classStats.map((r: any, i: number) => ({
                          key: i,
                          className: shortenUri(r.type),
                          label: r.label || '',
                          count: Number(r.count),
                          iri: r.type,
                        }))}
                        columns={[
                          {
                            title: '序号', width: 60,
                            render: (_: any, __: any, idx: number) => idx + 1,
                          },
                          {
                            title: '类名称', dataIndex: 'className', key: 'className',
                            render: (v: string) => <Text strong>{v}</Text>,
                          },
                          {
                            title: '中文标签', dataIndex: 'label', key: 'label', width: 160,
                            render: (v: string) => v ? <Tag color="blue">{v}</Tag> : <Text type="secondary">-</Text>,
                          },
                          {
                            title: '实例数量', dataIndex: 'count', key: 'count',
                            width: 120, sorter: (a: any, b: any) => a.count - b.count,
                            render: (v: number) => <Badge count={v} style={{ backgroundColor: '#1677ff' }} />,
                          },
                          {
                            title: '类 IRI', dataIndex: 'iri',
                            ellipsis: true,
                            render: (v: string) => <Tooltip title={v}><Text type="secondary" style={{ fontSize: 11 }}>{shortenUri(v)}</Text></Tooltip>,
                          },
                        ]}
                        pagination={{ defaultPageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 个类`, showSizeChanger: true, pageSizeOptions: ['10', '20', '50'] }}
                        onRow={(record) => ({
                          style: { cursor: 'pointer' },
                          onClick: () => loadInstancesForClass(record.iri),
                        })}
                        rowClassName={(record) => record.iri === selectedClass ? 'ant-table-row-selected' : ''}
                      />
                    ) : (
                      <Empty description="暂无实例统计数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    )}
                  </Card>

                  {/* 选中类的详细实例数据 */}
                  {selectedClass ? (
                    <Card
                      title={<span><TableOutlined /> 实例详情 - {shortenUri(selectedClass)}</span>}
                      size="small"
                      extra={
                        <Button size="small" onClick={() => { setSelectedClass(''); setInstanceData([]); setInstanceColumns([]); }}>
                          关闭
                        </Button>
                      }
                    >
                      {instanceLoading ? (
                        <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
                      ) : instanceData.length > 0 ? (
                        <Table
                          size="small"
                          bordered
                          dataSource={instanceData}
                          columns={instanceColumns}
                          pagination={{ defaultPageSize: 20, size: 'small', showTotal: (t) => `共 ${t} 条`, showSizeChanger: true, pageSizeOptions: ['10', '20', '50', '100'] }}
                          scroll={{ x: 'max-content', y: 500 }}
                          title={() => (
                            <Space>
                              <Text strong>{shortenUri(selectedClass)}</Text>
                              <Tag color="blue">{instanceData.length} 条实例</Tag>
                            </Space>
                          )}
                        />
                      ) : (
                        <Empty description="该类暂无实例数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                      )}
                    </Card>
                  ) : null}
                </div>
              ),
            },
          ]}
        />
            </>
      )}
        </div>
      </div>
    </div>
  );
}
