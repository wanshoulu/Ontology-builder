/**
 * LogicRulesView — 逻辑规则管理
 *
 * 简化设计：自然语言描述 → AI 生成 SWRL → 预览/手动修改 → 测试 → 保存
 */
import { getActiveProviders } from '@/api/model';
import { getTtlContent } from '@/api/ontology';
import {
createRule,
deleteRule,
explainSwrl,
generateSwrl,
syncRulesToTtl,testAdHocSwrl,
updateRule,
validateSwrl,
} from '@/api/rule';
import type { BusinessRule,ModelProvider,SwrlVocabulary } from '@/types';
import {
BulbOutlined,
DeleteOutlined,
EyeOutlined,
PlayCircleOutlined,
PlusOutlined,
SaveOutlined,StopOutlined
} from '@ant-design/icons';
import {
Alert,
Button,
Card,
Col,
Empty,
Input,
message,
Modal,
Popconfirm,
Row,
Select,
Space,
Spin,
Table,Tag,
Tooltip,
Typography,
} from 'antd';
import React,{ useCallback,useEffect,useRef,useState } from 'react';

const { Title, Text, Paragraph } = Typography;

// ==================== 常量 ====================

const RULE_TYPE_TAG: Record<string, { label: string; color: string }> = {
  swrl: { label: 'SWRL', color: 'geekblue' },
  condition: { label: '条件', color: 'blue' },
  event: { label: '事件', color: 'orange' },
  temporal: { label: '时序', color: 'purple' },
  composite: { label: '复合', color: 'cyan' },
};

// ==================== 主组件 ====================

interface LogicRulesViewProps {
  ontologyId: string;
  vocabulary: SwrlVocabulary | null;
  rules: BusinessRule[];
  onRulesChange: (rules: BusinessRule[]) => void;
}

const LogicRulesView: React.FC<LogicRulesViewProps> = ({
  ontologyId, vocabulary, rules, onRulesChange,
}) => {
  const classes = vocabulary?.classes || [];
  const objectProperties = vocabulary?.objectProperties || [];
  const dataProperties = vocabulary?.dataProperties || [];

  // 选中规则
  const [selectedRule, setSelectedRule] = useState<BusinessRule | null>(null);
  const [isNew, setIsNew] = useState(false);

  // 新建规则表单
  const [newRuleName, setNewRuleName] = useState('');
  const [newRuleType, setNewRuleType] = useState<string>('swrl');

  // 模型选择
  const [providers, setProviders] = useState<ModelProvider[]>([]);
  const [selectedModelKey, setSelectedModelKey] = useState<string>('');

  // 分页
  const [pageSize, setPageSize] = useState(12);

  // 加载可用模型
  React.useEffect(() => {
    getActiveProviders().then((list) => {
      const active = (list || []).filter((p: any) => p.status?.toUpperCase?.() === 'ACTIVE' || p.status === 'active');
      setProviders(active);
      if (active.length > 0 && !selectedModelKey) {
        const first = active.find((p: any) => (p.models?.length || 0) > 0);
        if (first?.models?.[0]) {
          setSelectedModelKey(`${first.id}::${first.models[0].modelId}`);
        }
      }
    }).catch(() => {});
  }, []);

  // 自然语言 → SWRL
  const [description, setDescription] = useState('');
  const [swrlText, setSwrlText] = useState('');
  const [generating, setGenerating] = useState(false);

  // 保存
  const [saving, setSaving] = useState(false);

  // SWRL 解释
  const [explanation, setExplanation] = useState('');
  const [explaining, setExplaining] = useState(false);

  // AbortController 用于取消进行中的生成/解释请求
  const abortRef = useRef<AbortController | null>(null);

  // 组件卸载时取消进行中的请求
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  const handleExplain = async () => {
    if (!swrlText.trim()) { message.warning('请先生成或输入 SWRL'); return; }
    // 取消上一次未完成的请求
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setExplaining(true);
    setExplanation('');
    try {
      // 解释时包含中文 label 和 TTL 上下文
      let ttlContent = '';
      try { ttlContent = await getTtlContent(ontologyId); } catch {}
      const vocab = {
        classes: classes.map((c) => ({
          name: c.shortName, label: c.label, description: (c as any).description,
          objectProperties: objectProperties.filter((p) => p.domain && (p.domain.includes(c.shortName) || p.domain === c.iri))
            .map((p) => ({ name: p.shortName, label: p.label, range: p.range?.split('#')?.pop() || p.range?.split('/')?.pop() })),
          dataProperties: dataProperties.filter((p) => p.domain && (p.domain.includes(c.shortName) || p.domain === c.iri))
            .map((p) => ({ name: p.shortName, label: p.label, datatype: p.datatype?.split('#')?.pop() || p.datatype })),
        })),
        ttlContent,
      };
      const [providerId, modelId] = selectedModelKey ? selectedModelKey.split('::') : ['', ''];
      const result = await explainSwrl(swrlText.trim(), vocab, providerId, modelId, controller.signal);
      setExplanation(result.explanation || '');
    } catch (e: any) {
      if (e?.name === 'AbortError') return; // 用户取消，不提示错误
      message.error(e?.message || '解释失败');
    } finally {
      setExplaining(false);
      abortRef.current = null;
    }
  };

  // 左侧面板宽度（可拖拽调整）
  const [leftPanelWidth, setLeftPanelWidth] = useState(380);
  const [dragging, setDragging] = useState(false);
  const startXRef = useRef(0);
  const startWidthRef = useRef(380);

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    setDragging(true);
    startXRef.current = e.clientX;
    startWidthRef.current = leftPanelWidth;
  }, [leftPanelWidth]);

  useEffect(() => {
    if (!dragging) return;
    const handleMouseMove = (e: MouseEvent) => {
      const delta = e.clientX - startXRef.current;
      const newWidth = Math.max(280, Math.min(600, startWidthRef.current + delta));
      setLeftPanelWidth(newWidth);
    };
    const handleMouseUp = () => {
      setDragging(false);
    };
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, [dragging]);

  // 测试
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<any>(null);
  const [testModalOpen, setTestModalOpen] = useState(false);

  // ---- 选择规则 ----
  const handleSelectRule = useCallback((rule: BusinessRule | null, createNew: boolean) => {
    if (createNew) {
      setIsNew(true);
      setSelectedRule(null);
      setNewRuleName('');
      setNewRuleType('swrl');
      setDescription('');
      setSwrlText('');
      setExplanation('');
      return;
    }
    setIsNew(false);
    setSelectedRule(rule);
    setNewRuleName(rule?.name || '');
    setSwrlText(rule?.swrlRule || '');
    setDescription(rule?.description || '');
    setExplanation('');
  }, []);

  // ---- AI 生成 SWRL ----
  const handleGenerate = async () => {
    if (!description.trim()) {
      message.warning('请先输入业务规则的文字描述');
      return;
    }
    // 取消上一次未完成的请求
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setGenerating(true);
    try {
      // 构建结构化词汇表：每个类包含其拥有的对象属性和数据属性
      // 生成时只传英文 IRI 名称，不传中文 label（避免 LLM 使用中文名）
      const vocabClasses = classes.map((c) => {
        const className = c.shortName;
        const objProps = objectProperties
          .filter((p) => p.domain && (p.domain.includes(className) || p.domain === c.iri))
          .map((p) => ({ name: p.shortName, range: p.range?.split('#')?.pop() || p.range?.split('/')?.pop(), description: (p as any).description }));
        const dataProps = dataProperties
          .filter((p) => p.domain && (p.domain.includes(className) || p.domain === c.iri))
          .map((p) => ({ name: p.shortName, datatype: p.datatype?.split('#')?.pop() || p.datatype, description: (p as any).description }));
        return { name: className, description: (c as any).description, objectProperties: objProps, dataProperties: dataProps };
      });
      // 同时获取 TTL 内容作为参考上下文
      let ttlContent = '';
      try { ttlContent = await getTtlContent(ontologyId); } catch {}
      const vocab = { classes: vocabClasses, ttlContent };
      const [providerId, modelId] = selectedModelKey ? selectedModelKey.split('::') : ['', ''];
      const result = await generateSwrl(description.trim(), vocab, providerId, modelId, controller.signal);
      setSwrlText(result.swrl || '');
      message.success('SWRL 已生成，请检查内容后保存');
    } catch (e: any) {
      if (e?.name === 'AbortError') return; // 用户取消，不提示错误
      message.error(e?.message || 'AI 生成失败，请确认 Agent 服务可用');
    } finally {
      setGenerating(false);
      abortRef.current = null;
    }
  };

  // ---- 保存 ----
  const handleSave = async () => {
    const finalSwrl = swrlText.trim();
    if (!finalSwrl) {
      message.warning('请先生成或输入 SWRL 表达式');
      return;
    }

    // 校验
    try {
      const vr = await validateSwrl(finalSwrl, ontologyId);
      if (!vr.valid) {
        message.error(vr.errors.map((e: any) => e.message).join('; '));
        return;
      }
      if (vr.warnings.length > 0) {
        message.warning(vr.warnings.map((w: any) => w.message).join('; '));
      }
    } catch {}

    setSaving(true);
    try {
      const payload = {
        ontologyId,
        name: isNew ? (newRuleName || '未命名规则') : (selectedRule?.name || newRuleName || '未命名规则'),
        description: description.trim() || (selectedRule?.description || ''),
        type: (isNew ? newRuleType : selectedRule?.type || 'swrl') as any,
        conditionJson: selectedRule?.conditionJson || '{}',
        actionJson: selectedRule?.actionJson || '{}',
        swrlRule: finalSwrl,
        source: '人工',
        confidence: 1.0,
      };

      if (isNew || !selectedRule) {
        const created = await createRule(payload as any);
        await syncRulesToTtl(ontologyId);
        message.success('规则创建成功');
        setSelectedRule(created);
        setIsNew(false);
      } else {
        await updateRule(selectedRule.id, payload as any);
        await syncRulesToTtl(ontologyId);
        message.success('规则更新成功');
      }

      const { getRulesByOntology } = await import('@/api/rule');
      const updated = await getRulesByOntology(ontologyId);
      onRulesChange(updated || []);
    } catch (e: any) {
      // axios 拦截器已显示后端错误消息，此处只处理非 HTTP 异常
      if (!e?.response) {
        message.error(e?.message || '保存失败');
      }
    } finally {
      setSaving(false);
    }
  };

  // ---- 删除 ----
  const handleDelete = async (id: string) => {
    try {
      await deleteRule(id);
      await syncRulesToTtl(ontologyId);
      message.success('规则已删除');
    } catch (e: any) {
      // 如果后端返回 404（规则可能已被其他操作删除），静默处理并刷新列表
      const status = e?.response?.status;
      const is404 = status === 404 || e?.message?.includes('未找到');
      if (is404) {
        message.info('该规则已不存在，将刷新列表');
      } else {
        message.error(e?.message || '删除失败');
      }
    }
    if (selectedRule?.id === id) {
      setSelectedRule(null); setIsNew(false); setSwrlText(''); setDescription(''); setExplanation('');
    }
    const { getRulesByOntology } = await import('@/api/rule');
    const updated = await getRulesByOntology(ontologyId);
    onRulesChange(updated || []);
  };

  // ---- 测试 ----
  const handleTest = async () => {
    if (!swrlText.trim()) { message.warning('请先生成或输入 SWRL'); return; }
    setTesting(true);
    try {
      const res = await testAdHocSwrl(swrlText.trim(), ontologyId);
      setTestResult(res);
      setTestModalOpen(true);
    } catch (e: any) { message.error(e?.message || '测试失败'); }
    finally { setTesting(false); }
  };

  // ==================== 渲染 ====================

  const allRules = rules;
  const ruleColumns = [
    { title: '规则名称', dataIndex: 'name', key: 'name', width: 120, ellipsis: true,
      render: (n: string, r: BusinessRule) => (
        <Tooltip title={r.description || undefined}>
          <Text strong>{n || '未命名'}</Text>
        </Tooltip>
      )},
    { title: '类型', dataIndex: 'type', key: 'type', width: 60,
      render: (t: string) => <Tag color={RULE_TYPE_TAG[t]?.color} style={{ fontSize: 11 }}>{RULE_TYPE_TAG[t]?.label || t}</Tag> },
    { title: '内容预览', key: 'content', ellipsis: true,
      render: (_: any, r: BusinessRule) => (
        <Tooltip title={r.swrlRule}>
          <Text style={{ fontSize: 11 }} ellipsis>{r.swrlRule?.substring(0, 60) || '-'}</Text>
        </Tooltip>
      )},
    { title: '操作', key: 'action', width: 50,
      render: (_: any, r: BusinessRule) => (
        <Popconfirm title="确定删除此规则?" onConfirm={(e) => { e?.stopPropagation(); handleDelete(r.id); }}
          onCancel={(e) => e?.stopPropagation()}>
          <Button type="link" size="small" danger icon={<DeleteOutlined />}
            onClick={(e) => e.stopPropagation()} />
        </Popconfirm>
      )},
  ];

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>逻辑规则管理</Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => handleSelectRule(null, true)}>
          新建规则
        </Button>
      </div>

      <div style={{ display: 'flex', height: 'calc(100vh - 220px)' }}>
        {/* 左侧：规则列表 */}
        <div style={{ width: leftPanelWidth, flexShrink: 0, overflow: 'auto', paddingRight: 8 }}>
          {allRules.length === 0 ? (
            <Empty description="暂无逻辑规则" style={{ marginTop: 40 }}>
              <Button type="primary" onClick={() => handleSelectRule(null, true)}>创建第一条规则</Button>
            </Empty>
          ) : (
            <Table size="small" dataSource={allRules} columns={ruleColumns} rowKey="id"
              pagination={{ pageSize, size: 'small' as const, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条`, pageSizeOptions: ['10', '20', '50', '100'] }}
              onChange={(p) => setPageSize(p.pageSize ?? 12)}
              scroll={{ y: 'calc(100vh - 380px)' }}
              onRow={(record) => ({ onClick: () => handleSelectRule(record, false),
                style: { cursor: 'pointer', background: selectedRule?.id === record.id ? '#e6f4ff' : undefined } })}
            />
          )}
        </div>

        {/* 拖拽分隔条 */}
        <div
          onMouseDown={handleMouseDown}
          style={{
            width: 6, flexShrink: 0, cursor: 'col-resize',
            background: dragging ? '#1677ff' : '#f0f0f0',
            transition: dragging ? 'none' : 'background 0.2s',
            borderLeft: '1px solid #e8e8e8', borderRight: '1px solid #e8e8e8',
            userSelect: 'none',
          }}
          onMouseEnter={(e) => { if (!dragging) (e.target as HTMLElement).style.background = '#d9d9d9'; }}
          onMouseLeave={(e) => { if (!dragging) (e.target as HTMLElement).style.background = '#f0f0f0'; }}
        />

        {/* 右侧：规则编辑器 */}
        <div style={{ flex: 1, overflow: 'auto', paddingLeft: 8 }}>
          {!selectedRule && !isNew ? (
            <Empty description="选择左侧规则或点击「新建规则」开始编辑" style={{ marginTop: 80 }} />
          ) : (
            <div>
              {/* 规则名称 + 类型 + 描述 */}
              <Card size="small" style={{ marginBottom: 12 }}>
                <Row gutter={12}>
                  <Col flex="auto">
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 2 }}>规则名称</Text>
                    <Input placeholder="如：成年人分类" style={{ fontWeight: 500 }}
                      value={isNew ? newRuleName : (selectedRule?.name || '')}
                      onChange={(e) => isNew ? setNewRuleName(e.target.value) : setSelectedRule((p: any) => p ? { ...p, name: e.target.value } : null)} />
                  </Col>
                  <Col flex="110px">
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 2 }}>类型</Text>
                    <Select style={{ width: '100%' }}
                      value={isNew ? newRuleType : (selectedRule?.type || 'swrl')}
                      onChange={(v) => isNew ? setNewRuleType(v) : setSelectedRule((p: any) => p ? { ...p, type: v } : null)}
                      options={[{ label: 'SWRL', value: 'swrl' }, { label: '条件', value: 'condition' }, { label: '事件', value: 'event' }, { label: '时序', value: 'temporal' }, { label: '复合', value: 'composite' }]} />
                  </Col>
                </Row>
                <Row style={{ marginTop: 8 }}>
                  <Col flex="auto">
                    <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 2 }}>规则描述（将随自然语言描述一同保存）</Text>
                    <Input placeholder="简要描述该规则的业务含义..."
                      value={description}
                      onChange={(e) => setDescription(e.target.value)} />
                  </Col>
                </Row>
              </Card>

              {/* 自然语言描述 → AI 生成 */}
              <Card size="small" title={<span><BulbOutlined style={{ color: '#faad14', marginRight: 6 }} />自然语言描述</span>} style={{ marginBottom: 12 }}>
                <Input.TextArea rows={3}
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="用自然语言描述你的业务规则，例如：&#10;年龄大于18岁且收入大于50000的人标记为VIP客户&#10;订单金额大于10000的订单标记为大额订单"
                  disabled={generating}
                  style={{ marginBottom: 8 }} />
                <Row gutter={12} align="middle" style={{ marginBottom: 8 }}>
                  <Col flex="auto">
                    <Select
                      style={{ width: '100%' }}
                      placeholder="选择生成模型（默认使用系统配置）"
                      value={selectedModelKey || undefined}
                      onChange={setSelectedModelKey}
                      allowClear
                      showSearch
                      optionFilterProp="label"
                      options={providers.flatMap((p: any) =>
                        (p.models || []).map((m: any) => ({
                          label: `${p.name} / ${m.modelName || m.modelId}`,
                          value: `${p.id}::${m.modelId}`,
                        }))
                      )}
                    />
                  </Col>
                  <Col>
                    {generating ? (
                      <Button danger icon={<StopOutlined />} onClick={() => abortRef.current?.abort()}>
                        停止生成
                      </Button>
                    ) : (
                      <Button type="primary" icon={<BulbOutlined />} onClick={handleGenerate}>
                        生成 SWRL
                      </Button>
                    )}
                  </Col>
                </Row>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  AI 会根据本体的类和属性自动生成 SWRL 表达式。可选择不同模型获得更好的生成效果。
                </Text>
              </Card>

              {/* SWRL 结果预览 + 手动编辑 */}
              <Card size="small" title={<span><EyeOutlined style={{ marginRight: 6 }} />生成的 SWRL 规则</span>}
                extra={<Button size="small" icon={<PlayCircleOutlined />} onClick={handleTest} loading={testing}>测试</Button>}
                style={{ marginBottom: 12 }}>
                <Input.TextArea rows={5}
                  value={swrlText}
                  onChange={(e) => setSwrlText(e.target.value)}
                  placeholder="SWRL 表达式将在此显示，生成后也可以手动修改..."
                  style={{ fontFamily: 'monospace', fontSize: 13 }} />
                <div style={{ marginTop: 8 }}>
                  <Space wrap>
                    <Select
                      size="small"
                      style={{ width: 260 }}
                      placeholder="选择模型"
                      value={selectedModelKey || undefined}
                      onChange={setSelectedModelKey}
                      allowClear
                      options={providers.flatMap((p: any) =>
                        (p.models || []).map((m: any) => ({
                          label: `${p.name} / ${m.modelName || m.modelId}`,
                          value: `${p.id}::${m.modelId}`,
                        }))
                      )}
                    />
                    <Button size="small" icon={<BulbOutlined />} onClick={handleExplain} loading={explaining} disabled={!swrlText.trim()}>
                      解释 SWRL
                    </Button>
                  </Space>
                </div>
                {explaining && (
                  <div style={{ textAlign: 'center', marginTop: 8 }}>
                    <Button size="small" danger icon={<StopOutlined />} onClick={() => abortRef.current?.abort()}>
                      停止解释
                    </Button>
                  </div>
                )}
                {explanation && (
                  <Alert
                    type="info"
                    message="规则解释 (IF-THEN)"
                    description={
                      <pre style={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', margin: 0, fontSize: 13 }}>
                        {explanation}
                      </pre>
                    }
                    style={{ marginTop: 8 }}
                    showIcon
                  />
                )}
              </Card>

              {/* 保存 + 操作 */}
              <Card size="small">
                <div style={{ textAlign: 'right' }}>
                  <Space>
                    <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}>保存规则</Button>
                    {selectedRule && !isNew && (
                      <Popconfirm title="确定删除此规则?" onConfirm={() => handleDelete(selectedRule.id)}>
                        <Button danger icon={<DeleteOutlined />}>删除</Button>
                      </Popconfirm>
                    )}
                  </Space>
                </div>
              </Card>
            </div>
          )}
        </div>
      </div>

      {/* 测试结果弹窗 */}
      <Modal title="SWRL 规则测试结果" open={testModalOpen} onCancel={() => setTestModalOpen(false)} footer={null} width={700}>
        {testResult ? (
          <div>
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col span={6}><div style={{ textAlign: 'center', padding: 8, background: '#f6ffed', borderRadius: 4 }}>
                <div style={{ fontSize: 24, fontWeight: 600, color: '#52c41a' }}>{testResult.inferredCount}</div><Text type="secondary">推断三元组</Text></div></Col>
              <Col span={6}><div style={{ textAlign: 'center', padding: 8, background: '#e6f4ff', borderRadius: 4 }}>
                <div style={{ fontSize: 24, fontWeight: 600, color: '#1677ff' }}>{testResult.inputTriplesUsed}</div><Text type="secondary">输入三元组</Text></div></Col>
              <Col span={6}><div style={{ textAlign: 'center', padding: 8, background: '#fff7e6', borderRadius: 4 }}>
                <div style={{ fontSize: 24, fontWeight: 600, color: '#fa8c16' }}>{testResult.executionTimeMs}ms</div><Text type="secondary">耗时</Text></div></Col>
              <Col span={6}><div style={{ textAlign: 'center', padding: 8, background: testResult.success ? '#f6ffed' : '#fff2f0', borderRadius: 4 }}>
                <Tag color={testResult.success ? 'green' : 'red'}>{testResult.success ? '成功' : '失败'}</Tag><div><Text type="secondary">状态</Text></div></div></Col>
            </Row>
            {testResult.error && <Alert type="error" message={testResult.error} style={{ marginBottom: 12 }} />}
            {testResult.inferredTriples?.length > 0 && (
              <div>
                <Text strong style={{ marginBottom: 8, display: 'block' }}>推断出的新三元组 ({testResult.inferredTriples.length} 条):</Text>
                <div style={{ maxHeight: 300, overflow: 'auto' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                    <thead><tr style={{ background: '#fafafa' }}>
                      <th style={{ padding: '4px 8px', border: '1px solid #f0f0f0' }}>Subject</th>
                      <th style={{ padding: '4px 8px', border: '1px solid #f0f0f0' }}>Predicate</th>
                      <th style={{ padding: '4px 8px', border: '1px solid #f0f0f0' }}>Object</th>
                    </tr></thead>
                    <tbody>
                      {testResult.inferredTriples.map((t: any, i: number) => (
                        <tr key={i}><td style={{ padding: '2px 8px', border: '1px solid #f0f0f0', fontFamily: 'monospace', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          <Tooltip title={t.subject}>{t.subject.split('#').pop() || t.subject.split('/').pop()}</Tooltip></td>
                          <td style={{ padding: '2px 8px', border: '1px solid #f0f0f0', fontFamily: 'monospace' }}>
                            <Tooltip title={t.predicate}>{t.predicate.split('#').pop() || t.predicate.split('/').pop()}</Tooltip></td>
                          <td style={{ padding: '2px 8px', border: '1px solid #f0f0f0', fontFamily: 'monospace' }}>
                            <Tooltip title={t.object}>{t.object.split('#').pop() || t.object.split('/').pop()}</Tooltip></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
            {testResult.inferredCount === 0 && testResult.success && (
              <Alert type="info" message="未推断出新三元组。可能 Body 条件未匹配到实例。" />
            )}
          </div>
        ) : <Spin tip="测试执行中..." />}
      </Modal>
    </div>
  );
};

export default LogicRulesView;
