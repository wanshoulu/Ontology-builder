/**
 * SKOS 词表查看 — Simple Knowledge Organization System
 *
 * 解析本体 TTL 中的 SKOS 命名空间，展示概念层级树和详情
 */
import { getTtlContent } from '@/api/ontology';
import {
NodeIndexOutlined,
SearchOutlined,
TagsOutlined,
} from '@ant-design/icons';
import {
Card,
Descriptions,
Empty,
Input,Space,
Tag,
Tree,
Typography
} from 'antd';
import React,{ useEffect,useMemo,useState } from 'react';

const { Text, Paragraph } = Typography;

interface SkosConcept {
  id: string;
  prefLabel: string;
  altLabels: string[];
  definition: string;
  scopeNote: string;
  broader: string[];
  narrower: string[];
  related: string[];
  children: SkosConcept[];
  rawIri: string;
}

interface Props {
  ontologyId: string;
  vocabulary: any;
}

const SkosView: React.FC<Props> = ({ ontologyId, vocabulary }) => {
  const [loading, setLoading] = useState(false);
  const [concepts, setConcepts] = useState<SkosConcept[]>([]);
  const [selectedConcept, setSelectedConcept] = useState<SkosConcept | null>(null);
  const [searchText, setSearchText] = useState('');
  const [hasSkos, setHasSkos] = useState(false);

  useEffect(() => {
    if (!ontologyId) return;
    setLoading(true);
    getTtlContent(ontologyId)
      .then((ttl: string) => {
        const content = typeof ttl === 'string' ? ttl : String(ttl || '');
        const parsed = parseSkosFromTtl(content);
        setConcepts(parsed);
        setHasSkos(parsed.length > 0);
      })
      .catch(() => {
        setConcepts([]);
        setHasSkos(false);
      })
      .finally(() => setLoading(false));
  }, [ontologyId]);

  /**
   * Parse SKOS constructs from TTL content
   * Looks for: skos:Concept, skos:prefLabel, skos:broader, skos:narrower, skos:related
   */
  const parseSkosFromTtl = (ttl: string): SkosConcept[] => {
    if (!ttl || !ttl.includes('skos:')) return [];

    const conceptMap = new Map<string, SkosConcept>();

    // Extract individual concept definitions
    // Pattern: :ConceptName a skos:Concept ;
    const conceptRegex = /([\w-]+)\s+a\s+skos:Concept\s*;([\s\S]*?)(?=\n\w+\s+a\s+skos:Concept|\n\w+\s+a\s+owl:|$)/g;
    let match;

    while ((match = conceptRegex.exec(ttl)) !== null) {
      const iri = match[1].trim();
      const body = match[2] || '';

      const concept: SkosConcept = {
        id: iri,
        prefLabel: '',
        altLabels: [],
        definition: '',
        scopeNote: '',
        broader: [],
        narrower: [],
        related: [],
        children: [],
        rawIri: iri,
      };

      // prefLabel
      const prefMatch = body.match(/skos:prefLabel\s+"([^"]+)"/);
      if (prefMatch) concept.prefLabel = prefMatch[1];

      // altLabels
      const altRegex = /skos:altLabel\s+"([^"]+)"/g;
      let altMatch;
      while ((altMatch = altRegex.exec(body)) !== null) {
        concept.altLabels.push(altMatch[1]);
      }

      // definition
      const defMatch = body.match(/skos:definition\s+"([^"]+)"/);
      if (defMatch) concept.definition = defMatch[1];

      // scopeNote
      const scopeMatch = body.match(/skos:scopeNote\s+"([^"]+)"/);
      if (scopeMatch) concept.scopeNote = scopeMatch[1];

      // broader — matches both IRI references and prefixed names
      const broaderRegex = /skos:broader\s+<?([\w:#/-]+?)>?\s*;/g;
      let broaderMatch;
      while ((broaderMatch = broaderRegex.exec(body)) !== null) {
        const raw = broaderMatch[1];
        concept.broader.push(raw.includes(':') ? raw.split(':').pop() || raw : raw);
      }

      // narrower
      const narrowerRegex = /skos:narrower\s+<?([\w:#/-]+?)>?\s*;/g;
      let narrowerMatch;
      while ((narrowerMatch = narrowerRegex.exec(body)) !== null) {
        const raw = narrowerMatch[1];
        concept.narrower.push(raw.includes(':') ? raw.split(':').pop() || raw : raw);
      }

      // related
      const relatedRegex = /skos:related\s+<?([\w:#/-]+?)>?\s*;/g;
      let relatedMatch;
      while ((relatedMatch = relatedRegex.exec(body)) !== null) {
        const raw = relatedMatch[1];
        concept.related.push(raw.includes(':') ? raw.split(':').pop() || raw : raw);
      }

      if (concept.prefLabel) {
        conceptMap.set(iri, concept);
      }
    }

    // Build hierarchy: link children to parents
    const roots: SkosConcept[] = [];
    conceptMap.forEach(concept => {
      let isChild = false;
      concept.broader.forEach(parentIri => {
        const parent = conceptMap.get(parentIri);
        if (parent) {
          parent.children.push(concept);
          isChild = true;
        }
      });
      if (!isChild) {
        roots.push(concept);
      }
    });

    return roots;
  };

  // Build tree data
  const buildTree = (nodes: SkosConcept[]): any[] =>
    nodes.map(c => ({
      key: c.id,
      title: (
        <span>
          {c.prefLabel}
          {c.altLabels.length > 0 && (
            <Text type="secondary" style={{ fontSize: 10, marginLeft: 6 }}>
              ({c.altLabels.join(', ')})
            </Text>
          )}
        </span>
      ),
      icon: <TagsOutlined style={{ color: '#722ed1' }} />,
      children: c.children.length > 0 ? buildTree(c.children) : undefined,
      concept: c,
    }));

  // Filter tree by search
  const filterTree = (nodes: any[], query: string): any[] => {
    if (!query) return nodes;
    const lower = query.toLowerCase();
    return nodes.reduce((acc: any[], node: any) => {
      const matches = node.concept?.prefLabel?.toLowerCase().includes(lower) ||
        node.concept?.altLabels?.some((l: string) => l.toLowerCase().includes(lower));
      const filteredChildren = node.children ? filterTree(node.children, query) : [];
      if (matches || filteredChildren.length > 0) {
        acc.push({ ...node, children: filteredChildren });
      }
      return acc;
    }, []);
  };

  const treeData = useMemo(() => buildTree(concepts), [concepts]);
  const filteredTree = useMemo(() => filterTree(treeData, searchText), [treeData, searchText]);

  const findConceptById = (id: string): SkosConcept | undefined => {
    const search = (nodes: SkosConcept[]): SkosConcept | undefined => {
      for (const n of nodes) {
        if (n.id === id) return n;
        const found = search(n.children);
        if (found) return found;
      }
      return undefined;
    };
    return search(concepts);
  };

  if (!ontologyId) {
    return <Empty description="请先选择本体模型" />;
  }

  return (
    <div style={{ padding: 16 }}>
      <Card size="small" loading={loading}>
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <div>
            <Text strong style={{ fontSize: 15 }}>
              <TagsOutlined style={{ color: '#722ed1', marginRight: 6 }} />SKOS 词表浏览
            </Text>
            <br />
            <Text type="secondary" style={{ fontSize: 12 }}>
              展示本体中的 SKOS (Simple Knowledge Organization System) 概念体系。
              支持查看概念层级（上位词/下位词/相关词）、标签和定义。
            </Text>
          </div>

          {!loading && !hasSkos ? (
            <Empty
              description={
                <span>
                  当前本体不包含 SKOS 词表<br />
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    本体 TTL 中未检测到 skos:Concept 声明。如需使用 SKOS，
                    请在 TTL 中定义概念，例如：<br />
                    <code>:MyConcept a skos:Concept ; skos:prefLabel "我的概念" .</code>
                  </Text>
                </span>
              }
            />
          ) : (
            <>
              <Input
                placeholder="搜索 SKOS 概念..."
                prefix={<SearchOutlined />}
                value={searchText}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) => setSearchText(e.target.value)}
                allowClear
              />

              <div style={{ display: 'flex', gap: 16, minHeight: 400 }}>
                {/* Left: Concept Tree */}
                <div style={{
                  flex: '0 0 45%',
                  maxHeight: 500,
                  overflow: 'auto',
                  border: '1px solid #f0f0f0',
                  borderRadius: 6,
                  padding: 12,
                }}>
                  <Text strong style={{ fontSize: 13, display: 'block', marginBottom: 8 }}>
                    概念层级 ({concepts.length} 个顶级概念, {conceptMapSize(concepts)} 个总计)
                  </Text>
                  {filteredTree.length > 0 ? (
                    <Tree
                      showIcon
                      defaultExpandAll
                      treeData={filteredTree}
                      onSelect={(keys) => {
                        if (keys.length > 0) {
                          const c = findConceptById(keys[0] as string);
                          if (c) setSelectedConcept(c);
                        }
                      }}
                      style={{ background: 'transparent' }}
                    />
                  ) : (
                    <Text type="secondary">无匹配概念</Text>
                  )}
                </div>

                {/* Right: Concept Detail */}
                <div style={{ flex: 1, maxHeight: 500, overflow: 'auto' }}>
                  {selectedConcept ? (
                    <Card size="small" title={
                      <Space>
                        <NodeIndexOutlined />
                        <span>{selectedConcept.prefLabel}</span>
                      </Space>
                    }>
                      <Descriptions column={1} size="small" layout="vertical">
                        <Descriptions.Item label="IRI">
                          <Text code>{selectedConcept.rawIri}</Text>
                        </Descriptions.Item>
                        {selectedConcept.prefLabel && (
                          <Descriptions.Item label="首选标签 (prefLabel)">
                            <Text strong>{selectedConcept.prefLabel}</Text>
                          </Descriptions.Item>
                        )}
                        {selectedConcept.altLabels.length > 0 && (
                          <Descriptions.Item label="备选标签 (altLabel)">
                            <Space wrap>
                              {selectedConcept.altLabels.map((l, i) => (
                                <Tag key={i}>{l}</Tag>
                              ))}
                            </Space>
                          </Descriptions.Item>
                        )}
                        {selectedConcept.definition && (
                          <Descriptions.Item label="定义 (definition)">
                            <Paragraph style={{ marginBottom: 0 }}>{selectedConcept.definition}</Paragraph>
                          </Descriptions.Item>
                        )}
                        {selectedConcept.scopeNote && (
                          <Descriptions.Item label="范围说明 (scopeNote)">
                            <Text type="secondary">{selectedConcept.scopeNote}</Text>
                          </Descriptions.Item>
                        )}
                        {selectedConcept.broader.length > 0 && (
                          <Descriptions.Item label="上位词 (broader)">
                            <Space wrap>
                              {selectedConcept.broader.map((b, i) => (
                                <Tag key={i} color="blue">{b}</Tag>
                              ))}
                            </Space>
                          </Descriptions.Item>
                        )}
                        {selectedConcept.narrower.length > 0 && (
                          <Descriptions.Item label="下位词 (narrower)">
                            <Space wrap>
                              {selectedConcept.narrower.map((n, i) => (
                                <Tag key={i} color="green">{n}</Tag>
                              ))}
                            </Space>
                          </Descriptions.Item>
                        )}
                        {selectedConcept.related.length > 0 && (
                          <Descriptions.Item label="相关词 (related)">
                            <Space wrap>
                              {selectedConcept.related.map((r, i) => (
                                <Tag key={i} color="purple">{r}</Tag>
                              ))}
                            </Space>
                          </Descriptions.Item>
                        )}
                      </Descriptions>
                    </Card>
                  ) : (
                    <div style={{ textAlign: 'center', padding: 60 }}>
                      <TagsOutlined style={{ fontSize: 48, color: '#d9d9d9', marginBottom: 16 }} />
                      <div><Text type="secondary">点击左侧概念查看详情</Text></div>
                    </div>
                  )}
                </div>
              </div>
            </>
          )}
        </Space>
      </Card>
    </div>
  );
};

/** Count total concepts in hierarchy */
const conceptMapSize = (nodes: SkosConcept[]): number => {
  let count = nodes.length;
  nodes.forEach(n => { count += n.children.length > 0 ? conceptMapSize(n.children) - 1 : 0; });
  return count;
};

export default SkosView;
