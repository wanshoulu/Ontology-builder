/**
 * ExtractionWizard — Markdown→TTL 提取向导 (卡片式步骤设计)
 * 设计对齐: 文档知识 RAG 模块的三步卡片风格
 */
import type { FileUploadRecord } from '@/api/fileUpload';
import {
FileTextOutlined,
LinkOutlined,
NodeIndexOutlined,
PauseCircleOutlined,RedoOutlined,
SaveOutlined,
ThunderboltOutlined,
} from '@ant-design/icons';
import {
Alert,
Button,
Card,
Col,
Collapse,
Input,
Modal,
Row,
Select,
Space,
Statistic,
Steps,
Tag,
TreeSelect,
Typography,
} from 'antd';
import React from 'react';
import type { ExtractPhase } from '../hooks/useExtraction';

const { Text } = Typography;
const { TextArea } = Input;

interface ExtractionWizardProps {
  visible: boolean; onClose: () => void; phase: ExtractPhase;
  mdFiles: FileUploadRecord[]; mdFilesLoading: boolean;
  selectedFileId: string; onFileSelect: (id: string) => void;
  activeProviders: any[]; selectedModelKey: string; onModelSelect: (key: string) => void;
  systemPrompt: string; onPromptChange: (p: string) => void;
  showPromptEditor: boolean; onTogglePromptEditor: (v: boolean) => void;
  ontologyName: string; onOntologyNameChange: (n: string) => void;
  versionName: string; onVersionNameChange: (n: string) => void;
  streamContent: string; extractStatus: string; extractionResult: any;
  provenance: { modelName: string; timestamp: string; fileSource: string } | null;
  onStart: () => void; onPause: () => void; onResume: () => void; onAbort: () => void;
  onSave: () => Promise<string | null>;
}

const ExtractionWizard: React.FC<ExtractionWizardProps> = ({
  visible, onClose, phase,
  mdFiles, mdFilesLoading, selectedFileId, onFileSelect,
  activeProviders, selectedModelKey, onModelSelect,
  systemPrompt, onPromptChange, showPromptEditor, onTogglePromptEditor,
  ontologyName, onOntologyNameChange, versionName, onVersionNameChange,
  streamContent, extractStatus, extractionResult, provenance,
  onStart, onPause, onResume, onAbort, onSave,
}) => {
  const currentStep = phase === 'done' ? 2 : phase === 'extracting' || phase === 'paused' ? 1 : 0;

  const providerOptions = activeProviders.flatMap((p: any) =>
    (p.models || []).map((m: any) => ({ value: `${p.id}::${m.modelId}`, label: `${m.name} · ${p.name}` })));

  const buildTree = (files: FileUploadRecord[]) => {
    const localFiles = files.filter((f: any) => (f.storageType || 'LOCAL').toUpperCase() === 'LOCAL');
    return localFiles.map(f => ({
      title: `${f.originalFilename} (${(f.fileSize / 1024).toFixed(1)} KB)`,
      value: f.id, key: f.id,
    }));
  };

  return (
    <Modal
      title={<Space><ThunderboltOutlined style={{ color: '#1677ff' }} /><span>Markdown → TTL 本体提取</span><Tag color="blue">AI 驱动</Tag></Space>}
      open={visible} onCancel={onClose} footer={null} width="80%" style={{ top: 20 }} destroyOnHidden
    >
      {/* Progress steps */}
      <Steps size="small" current={currentStep} style={{ marginBottom: 20 }}
        items={[
          { title: '配置', description: '选择文档和模型', icon: <FileTextOutlined /> },
          { title: '提取', description: 'LLM 流式生成', icon: <ThunderboltOutlined /> },
          { title: '保存', description: '预览并入库', icon: <SaveOutlined /> },
        ]}
      />

      {/* Step 0: Config */}
      {phase === 'config' && (
        <Row gutter={16}>
          <Col span={14}>
            <Card size="small" title={<Space><FileTextOutlined /><span>选择文档 & 模型</span></Space>} style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 12 }}>
                <Text strong>Markdown 文档 <span style={{ color: '#ff4d4f' }}>*</span></Text>
                <TreeSelect style={{ width: '100%', marginTop: 4 }} size="middle"
                  placeholder="选择要转换的 Markdown 文件"
                  treeData={buildTree(mdFiles)} value={selectedFileId || undefined}
                  onChange={(v: string) => onFileSelect(v)} loading={mdFilesLoading}
                  treeDefaultExpandAll />
              </div>
              <div style={{ marginBottom: 12 }}>
                <Text strong>AI 模型 <span style={{ color: '#ff4d4f' }}>*</span></Text>
                <Select style={{ width: '100%', marginTop: 4 }} size="middle"
                  placeholder="选择 AI 模型" value={selectedModelKey || undefined}
                  onChange={onModelSelect} options={providerOptions}
                  notFoundContent="无可用模型" />
              </div>
              <Collapse ghost size="small" activeKey={showPromptEditor ? ['prompt'] : []}
                onChange={keys => onTogglePromptEditor(keys.includes('prompt'))}
                items={[{ key: 'prompt', label: <Text style={{ fontSize: 12 }}>📝 自定义系统提示词（可选）</Text>,
                  children: <TextArea rows={8} value={systemPrompt} onChange={e => onPromptChange(e.target.value)}
                    style={{ fontFamily: 'monospace', fontSize: 12 }} />,
                }]} />
            </Card>
          </Col>
          <Col span={10}>
            <Card size="small" title={<Space><SaveOutlined /><span>本体信息</span></Space>} style={{ marginBottom: 12 }}>
              <div style={{ marginBottom: 12 }}>
                <Text strong>本体名称 <span style={{ color: '#ff4d4f' }}>*</span></Text>
                <Input style={{ marginTop: 4 }} placeholder="如: 金融风控本体" value={ontologyName}
                  onChange={e => onOntologyNameChange(e.target.value)} maxLength={100} />
              </div>
              <div style={{ marginBottom: 16 }}>
                <Text strong>版本名称 <span style={{ color: '#ff4d4f' }}>*</span></Text>
                <Input style={{ marginTop: 4 }} placeholder="如: v1.0-初始版本" value={versionName}
                  onChange={e => onVersionNameChange(e.target.value)} maxLength={50} />
              </div>
              <Button type="primary" size="large" icon={<ThunderboltOutlined />} block
                onClick={onStart}
                disabled={!selectedFileId || !selectedModelKey || !ontologyName.trim() || !versionName.trim()}>
                开始 AI 提取
              </Button>
            </Card>

            {/* Stats cards */}
            <Row gutter={8}>
              <Col span={12}>
                <Card size="small" style={{ background: '#f6ffed', border: '1px solid #b7eb8f' }}>
                  <Statistic title="可用文档" value={mdFiles.length} suffix="篇"
                    valueStyle={{ color: '#52c41a', fontSize: 18 }} />
                </Card>
              </Col>
              <Col span={12}>
                <Card size="small" style={{ background: '#e6f4ff', border: '1px solid #91caff' }}>
                  <Statistic title="可用模型" value={activeProviders.length} suffix="个"
                    valueStyle={{ color: '#1677ff', fontSize: 18 }} />
                </Card>
              </Col>
            </Row>
          </Col>
        </Row>
      )}

      {/* Step 1: Extracting */}
      {(phase === 'extracting' || phase === 'paused') && (
        <Card size="small"
          title={<Space><ThunderboltOutlined style={{ color: '#1677ff' }} /><span>AI 正在提取...</span>
            {extractStatus && <Tag color="processing">{extractStatus}</Tag>}</Space>}
          extra={
            <Space>
              {phase === 'extracting' ? (
                <Button danger icon={<PauseCircleOutlined />} onClick={onPause}>暂停</Button>
              ) : (
                <Button type="primary" icon={<ThunderboltOutlined />} onClick={onResume}>继续提取</Button>
              )}
              <Button onClick={onAbort}>中止</Button>
            </Space>
          }>
          <div style={{
            background: '#1e1e1e', padding: 16, borderRadius: 8, fontSize: 13,
            fontFamily: 'Consolas, Monaco, "Courier New", monospace', whiteSpace: 'pre-wrap',
            maxHeight: 480, overflow: 'auto', color: '#d4d4d4', lineHeight: 1.6,
          }}>
            {streamContent || <span style={{ color: '#6a9955' }}>// 等待 AI 响应...</span>}
            {phase === 'extracting' && <span style={{ borderRight: '2px solid #569cd6', marginLeft: 2 }}>&nbsp;</span>}
          </div>
        </Card>
      )}

      {/* Step 2: Done */}
      {phase === 'done' && extractionResult && (
        <div>
          <Alert type="success" showIcon style={{ marginBottom: 12 }}
            message={`提取完成 — ${extractionResult.classes?.length || 0} 个类, ${(extractionResult.objectProperties?.length || 0) + (extractionResult.dataProperties?.length || 0)} 个属性`} />

          {/* Provenance */}
          {provenance && (
            <Card size="small" style={{ marginBottom: 12, background: '#f0f5ff', border: '1px solid #adc6ff' }}
              title={<Space><FileTextOutlined /><span>溯源信息</span></Space>}>
              <Row gutter={16}>
                <Col span={8}><Text type="secondary" style={{ fontSize: 11 }}>提取模型</Text><br /><Text strong style={{ fontSize: 12 }}>{provenance.modelName}</Text></Col>
                <Col span={8}><Text type="secondary" style={{ fontSize: 11 }}>来源文件</Text><br /><Text strong style={{ fontSize: 12 }}>{provenance.fileSource}</Text></Col>
                <Col span={8}><Text type="secondary" style={{ fontSize: 11 }}>提取时间</Text><br /><Text strong style={{ fontSize: 12 }}>{new Date(provenance.timestamp).toLocaleString('zh-CN')}</Text></Col>
              </Row>
            </Card>
          )}

          {/* Result stats */}
          <Row gutter={12} style={{ marginBottom: 12 }}>
            <Col span={6}><Card size="small" style={{ background: '#e6f4ff' }}><Statistic title="类" value={extractionResult.classes?.length || 0} prefix={<NodeIndexOutlined />} valueStyle={{ color: '#1677ff', fontSize: 20 }} /></Card></Col>
            <Col span={6}><Card size="small" style={{ background: '#f9f0ff' }}><Statistic title="对象属性" value={extractionResult.objectProperties?.length || 0} prefix={<LinkOutlined />} valueStyle={{ color: '#722ed1', fontSize: 20 }} /></Card></Col>
            <Col span={6}><Card size="small" style={{ background: '#e6fffb' }}><Statistic title="数据属性" value={extractionResult.dataProperties?.length || 0} prefix={<LinkOutlined />} valueStyle={{ color: '#13c2c2', fontSize: 20 }} /></Card></Col>
            <Col span={6}><Card size="small" style={{ background: '#fff7e6' }}><Statistic title="规则" value={extractionResult.rules?.length || 0} prefix={<ThunderboltOutlined />} valueStyle={{ color: '#fa8c16', fontSize: 20 }} /></Card></Col>
          </Row>

          {/* TTL Preview */}
          {extractionResult.ttlContent && (
            <Collapse ghost size="small" items={[{ key: 'ttl',
              label: <Text style={{ fontSize: 12 }}>📋 TTL 预览（前 500 字符）</Text>,
              children: <pre style={{ maxHeight: 250, overflow: 'auto', fontSize: 11, background: '#1e1e1e', color: '#d4d4d4', padding: 12, borderRadius: 6 }}>{extractionResult.ttlContent.slice(0, 500)}</pre>,
            }]} />
          )}

          <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
            <Button type="primary" size="large" icon={<SaveOutlined />} onClick={onSave}>保存本体模型</Button>
            <Button size="large" icon={<RedoOutlined />} onClick={onStart}>重新提取</Button>
          </div>
        </div>
      )}
    </Modal>
  );
};

export default ExtractionWizard;
