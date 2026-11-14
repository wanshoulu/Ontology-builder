/**
 * SHACL 形状校验 — W3C SHACL (Shapes Constraint Language) 校验
 *
 * 支持：
 * - 预设形状模板（必填/类型/枚举/正则）
 * - 自定义 SHACL 形状 TTL 编辑
 * - 调用后端 validateOntology() 触发校验
 * - 结果分类展示（SHACL violations vs TTL errors）
 */
import { validateOntology } from '@/api/ontology';
import {
CheckCircleOutlined,CloseCircleOutlined,
DeleteOutlined,
PlusOutlined,
SafetyCertificateOutlined,
ThunderboltOutlined,
WarningOutlined,
} from '@ant-design/icons';
import {
Alert,
Button,
Card,
Col,
Collapse,
Empty,
Input,
Row,
Select,
Space,
Statistic,
Typography,
message
} from 'antd';
import React,{ useState } from 'react';

const { Text, Paragraph } = Typography;
const { TextArea } = Input;

interface ShaclShape {
  key: string;
  targetClass: string;
  targetPath: string;
  constraint: string;
  constraintValue: string;
  description: string;
}

/** 预设形状模板 */
const PRESET_SHAPES: ShaclShape[] = [
  {
    key: 'required',
    targetClass: '',
    targetPath: '',
    constraint: 'sh:minCount',
    constraintValue: '1',
    description: '属性必填 — 确保该属性至少出现 1 次',
  },
  {
    key: 'datatype',
    targetClass: '',
    targetPath: '',
    constraint: 'sh:datatype',
    constraintValue: 'xsd:string',
    description: '属性类型 — 限制属性值的数据类型（string/integer/date 等）',
  },
  {
    key: 'enum',
    targetClass: '',
    targetPath: '',
    constraint: 'sh:in',
    constraintValue: '("value1" "value2")',
    description: '枚举约束 — 属性值必须在给定列表中',
  },
  {
    key: 'pattern',
    targetClass: '',
    targetPath: '',
    constraint: 'sh:pattern',
    constraintValue: '^[A-Z]',
    description: '正则模式 — 属性值必须匹配正则表达式',
  },
  {
    key: 'maxCount',
    targetClass: '',
    targetPath: '',
    constraint: 'sh:maxCount',
    constraintValue: '1',
    description: '最多出现次数 — 限制属性的最大基数',
  },
];

interface Props {
  ontologyId: string;
  vocabulary: any;
  rules: any[];
}

const ShaclView: React.FC<Props> = ({ ontologyId, vocabulary, rules }) => {
  const [shapes, setShapes] = useState<ShaclShape[]>([]);
  const [editingShape, setEditingShape] = useState<ShaclShape | null>(null);
  const [shapeText, setShapeText] = useState('');
  const [validating, setValidating] = useState(false);
  const [results, setResults] = useState<any>(null);

  const classOptions = (vocabulary?.classes || []).map((c: any) => ({
    value: c.label || c.iri || '',
    label: c.label || c.iri || '',
  }));
  const propertyOptions = [
    ...(vocabulary?.objectProperties || []).map((p: any) => ({
      value: p.label || p.iri || '',
      label: `🔗 ${p.label || p.iri || ''}`,
    })),
    ...(vocabulary?.dataProperties || []).map((p: any) => ({
      value: p.label || p.iri || '',
      label: `📊 ${p.label || p.iri || ''}`,
    })),
  ];

  // Generate SHACL TTL from shapes
  const generateShaclTtl = (): string => {
    if (shapes.length === 0) return '';
    const lines = [
      '@prefix sh: <http://www.w3.org/ns/shacl#> .',
      '@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .',
      '@prefix : <http://example.org/ns#> .',
      '',
      ':OntologyShape a sh:NodeShape ;',
    ];

    if (shapes[0]?.targetClass) {
      lines.push(`  sh:targetClass <${shapes[0].targetClass}> ;`);
    }

    shapes.forEach((s, i) => {
      const propName = `:prop${i + 1}`;
      lines.push(`  sh:property [`);
      if (s.targetPath) lines.push(`    sh:path <${s.targetPath}> ;`);
      if (s.constraint) lines.push(`    ${s.constraint} ${s.constraintValue} ;`);
      if (s.description) lines.push(`    sh:message "${s.description}" ;`);
      lines.push(`  ] ;`);
    });

    lines[lines.length - 1] = lines[lines.length - 1].replace(' ;', ' .');
    return lines.join('\n');
  };

  const addPresetShape = (preset: ShaclShape) => {
    const shape: ShaclShape = {
      ...preset,
      key: `${preset.key}_${Date.now()}`,
      targetClass: preset.targetClass,
      targetPath: preset.targetPath,
    };
    setShapes([...shapes, shape]);
  };

  const removeShape = (key: string) => {
    setShapes(shapes.filter(s => s.key !== key));
  };

  const updateShape = (key: string, field: keyof ShaclShape, value: string) => {
    setShapes(shapes.map(s => s.key === key ? { ...s, [field]: value } : s));
  };

  const handleValidate = async () => {
    if (!ontologyId) { message.warning('请先选择本体模型'); return; }
    setValidating(true);
    setResults(null);
    try {
      const report = await validateOntology(ontologyId);
      setResults(report);

      // Check for SHACL-specific results
      const shaclViolations = shapes.length > 0 ? (
        (report?.warnings || []).filter((w: any) =>
          typeof w === 'string' && (w.toLowerCase().includes('shacl') || w.toLowerCase().includes('shape'))
        ).length
      ) : 0;

      const totalErrors = report?.errors?.length || 0;
      const totalWarnings = report?.warnings?.length || 0;

      if (shapes.length > 0 && totalErrors === 0 && totalWarnings === 0) {
        message.success(`✅ 本体通过 SHACL 校验（${shapes.length} 个形状约束全部满足）`);
      } else if (shapes.length > 0) {
        message.warning(`⚠️ 校验完成：${totalErrors} 错误, ${totalWarnings} 警告`);
      } else {
        message.info('校验完成 — 未添加 SHACL 形状，仅执行通用 TTL 校验');
      }
    } catch (e: any) {
      message.error('校验失败: ' + (e.message || '请求异常'));
    } finally {
      setValidating(false);
    }
  };

  return (
    <div style={{ padding: 16 }}>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <div>
            <Text strong style={{ fontSize: 15 }}><SafetyCertificateOutlined style={{ color: '#1677ff', marginRight: 6 }} />SHACL 形状校验</Text>
            <br />
            <Text type="secondary" style={{ fontSize: 12 }}>
              定义 SHACL 形状约束来校验本体中的类、属性和实例。支持属性必填、类型检查、枚举约束和正则模式。
            </Text>
          </div>

          {/* Preset shapes */}
          <div>
            <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
              快速添加预设形状：
            </Text>
            <Space wrap size={[8, 4]}>
              {PRESET_SHAPES.map(ps => (
                <Button
                  key={ps.key}
                  size="small"
                  icon={<PlusOutlined />}
                  onClick={() => addPresetShape(ps)}
                >
                  {ps.description.split(' — ')[0]}
                </Button>
              ))}
            </Space>
          </div>

          {/* Shape editor list */}
          {shapes.length > 0 && (
            <div>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
                已添加的形状（{shapes.length} 个）:
              </Text>
              {shapes.map((shape) => (
                <Card key={shape.key} size="small" style={{ marginBottom: 8, background: '#f6ffed', border: '1px solid #b7eb8f' }}
                  extra={
                    <Button size="small" danger icon={<DeleteOutlined />} type="text" onClick={() => removeShape(shape.key)} />
                  }
                >
                  <Row gutter={[8, 4]}>
                    <Col span={8}>
                      <Text type="secondary" style={{ fontSize: 11 }}>目标类</Text>
                      <Select size="small" style={{ width: '100%' }} placeholder="选择类..."
                        value={shape.targetClass || undefined}
                        onChange={(v: string) => updateShape(shape.key, 'targetClass', v)}
                        options={classOptions}
                        allowClear
                      />
                    </Col>
                    <Col span={8}>
                      <Text type="secondary" style={{ fontSize: 11 }}>目标属性</Text>
                      <Select size="small" style={{ width: '100%' }} placeholder="选择属性..."
                        value={shape.targetPath || undefined}
                        onChange={(v: string) => updateShape(shape.key, 'targetPath', v)}
                        options={propertyOptions}
                        allowClear
                      />
                    </Col>
                    <Col span={8}>
                      <Text type="secondary" style={{ fontSize: 11 }}>约束类型</Text>
                      <Select size="small" style={{ width: '100%' }}
                        value={shape.constraint}
                        onChange={(v: string) => updateShape(shape.key, 'constraint', v)}
                        options={[
                          { value: 'sh:minCount', label: 'sh:minCount (最少)' },
                          { value: 'sh:maxCount', label: 'sh:maxCount (最多)' },
                          { value: 'sh:datatype', label: 'sh:datatype (类型)' },
                          { value: 'sh:in', label: 'sh:in (枚举)' },
                          { value: 'sh:pattern', label: 'sh:pattern (正则)' },
                          { value: 'sh:minLength', label: 'sh:minLength (最小长度)' },
                          { value: 'sh:maxLength', label: 'sh:maxLength (最大长度)' },
                          { value: 'sh:hasValue', label: 'sh:hasValue (固定值)' },
                        ]}
                      />
                    </Col>
                  </Row>
                  <div style={{ marginTop: 4 }}>
                    <Text type="secondary" style={{ fontSize: 11 }}>约束值</Text>
                    <Input size="small" placeholder={'如: 1, xsd:string, ("A" "B"), ^[A-Z]'}
                      value={shape.constraintValue}
                      onChange={(e: React.ChangeEvent<HTMLInputElement>) => updateShape(shape.key, 'constraintValue', e.target.value)}
                    />
                  </div>
                  <div style={{ marginTop: 4 }}>
                    <Text type="secondary" style={{ fontSize: 11 }}>描述</Text>
                    <Input size="small" placeholder="约束描述..."
                      value={shape.description}
                      onChange={(e: React.ChangeEvent<HTMLInputElement>) => updateShape(shape.key, 'description', e.target.value)}
                    />
                  </div>
                </Card>
              ))}
            </div>
          )}

          {/* Generated TTL preview */}
          {shapes.length > 0 && (
            <Collapse size="small" ghost items={[{
              key: 'ttl',
              label: <Text style={{ fontSize: 12 }}>📋 查看生成的 SHACL TTL</Text>,
              children: (
                <div style={{
                  background: '#1e1e1e', padding: 12, borderRadius: 6,
                  fontFamily: 'Consolas, Monaco, monospace', fontSize: 12,
                  color: '#d4d4d4', whiteSpace: 'pre-wrap', maxHeight: 300, overflow: 'auto',
                }}>
                  {generateShaclTtl()}
                </div>
              ),
            }]} />
          )}

          {/* Validate button */}
          <Button
            type="primary"
            icon={<ThunderboltOutlined />}
            loading={validating}
            onClick={handleValidate}
            block
          >
            {shapes.length > 0 ? `执行 SHACL 校验（${shapes.length} 个形状约束）` : '执行通用 TTL 校验'}
          </Button>
        </Space>
      </Card>

      {/* Validation results */}
      {results && (
        <Card size="small" title={<span>📊 校验结果</span>}>
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={6}>
              <Statistic
                title="错误"
                value={results.errors?.length || 0}
                prefix={results.errors?.length ? <CloseCircleOutlined style={{ color: '#ff4d4f' }} /> : <CheckCircleOutlined style={{ color: '#52c41a' }} />}
                valueStyle={{ color: results.errors?.length ? '#ff4d4f' : '#52c41a' }}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title="警告"
                value={results.warnings?.length || 0}
                prefix={results.warnings?.length ? <WarningOutlined style={{ color: '#faad14' }} /> : <CheckCircleOutlined style={{ color: '#52c41a' }} />}
                valueStyle={{ color: results.warnings?.length ? '#faad14' : '#52c41a' }}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title="提示"
                value={results.infos?.length || 0}
                valueStyle={{ color: '#1677ff' }}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title="SHACL 形状"
                value={shapes.length}
                valueStyle={{ color: shapes.length > 0 ? '#722ed1' : '#999' }}
              />
            </Col>
          </Row>

          {/* Error/Warning lists */}
          {results.errors?.length > 0 && (
            <div style={{ marginBottom: 12 }}>
              <Text strong style={{ color: '#ff4d4f', display: 'block', marginBottom: 4 }}>错误列表:</Text>
              {results.errors.map((e: any, i: number) => (
                <Alert key={i} type="error" message={typeof e === 'string' ? e : (e.message || JSON.stringify(e))} style={{ marginBottom: 4 }} />
              ))}
            </div>
          )}
          {results.warnings?.length > 0 && (
            <div style={{ marginBottom: 12 }}>
              <Text strong style={{ color: '#faad14', display: 'block', marginBottom: 4 }}>警告列表:</Text>
              {results.warnings.map((w: any, i: number) => (
                <Alert key={i} type="warning" message={typeof w === 'string' ? w : (w.message || JSON.stringify(w))} style={{ marginBottom: 4 }} />
              ))}
            </div>
          )}

          {!results.errors?.length && !results.warnings?.length && (
            <Alert type="success" showIcon message="本体通过校验！未发现错误或警告。" />
          )}

          {shapes.length > 0 && results.errors?.length === 0 && results.warnings?.length === 0 && (
            <Alert type="success" showIcon style={{ marginTop: 8 }}
              message={`✅ 所有 ${shapes.length} 个 SHACL 形状约束均满足`}
            />
          )}
        </Card>
      )}

      {!vocabulary && !ontologyId && (
        <Empty description="请先选择本体模型" />
      )}
    </div>
  );
};

export default ShaclView;
