/**
 * 语义版本 Diff — 比较两个本体版本之间的 TTL 差异
 *
 * 使用 diff 库实现行级文本 Diff，支持统一视图和并排视图切换
 */
import { getOntologyVersions,getTtlContent } from '@/api/ontology';
import {
EditOutlined,
MinusCircleOutlined,
PlusCircleOutlined,
SwapOutlined,
} from '@ant-design/icons';
import {
Col,
Empty,
Modal,
Row,
Segmented,
Select,Space,
Spin,
Statistic,
Tag,Typography,
} from 'antd';
import { Change,diffLines } from 'diff';
import React,{ useCallback,useEffect,useState } from 'react';

const { Text } = Typography;

interface VersionItem {
  id: string;
  version: string;
  versionName: string;
  createdAt: string;
  classCount?: number;
  propertyCount?: number;
}

interface Props {
  ontologyId: string;
  ontologyName: string;
  open: boolean;
  onClose: () => void;
}

type DiffViewMode = 'unified' | 'split';

const VersionDiff: React.FC<Props> = ({ ontologyId, ontologyName, open, onClose }) => {
  const [versions, setVersions] = useState<VersionItem[]>([]);
  const [loadingVersions, setLoadingVersions] = useState(false);
  const [leftVersionId, setLeftVersionId] = useState<string>('');
  const [rightVersionId, setRightVersionId] = useState<string>('');
  const [leftContent, setLeftContent] = useState<string>('');
  const [rightContent, setRightContent] = useState<string>('');
  const [loadingDiff, setLoadingDiff] = useState(false);
  const [diffResult, setDiffResult] = useState<Change[]>([]);
  const [viewMode, setViewMode] = useState<DiffViewMode>('unified');

  // Load version list
  useEffect(() => {
    if (!open || !ontologyId) return;
    setLoadingVersions(true);
    setLeftVersionId(''); setRightVersionId('');
    setLeftContent(''); setRightContent(''); setDiffResult([]);
    getOntologyVersions(ontologyId)
      .then((list: any[]) => {
        const mapped: VersionItem[] = (Array.isArray(list) ? list : []).map((v: any) => ({
          id: v.id || v.ontologyId || v.versionId || '',
          version: v.version || v.versionName || 'v1',
          versionName: v.versionName || v.version || '',
          createdAt: v.createdAt || v.updatedAt || '',
          classCount: v.classCount,
          propertyCount: v.propertyCount,
        }));
        setVersions(mapped);
        // Auto-select last two versions
        if (mapped.length >= 2) {
          setLeftVersionId(mapped[1].id);   // older
          setRightVersionId(mapped[0].id);  // newer
        } else if (mapped.length === 1) {
          setRightVersionId(mapped[0].id);
        }
      })
      .catch(() => setVersions([]))
      .finally(() => setLoadingVersions(false));
  }, [open, ontologyId]);

  // Compare two versions
  const handleCompare = useCallback(async () => {
    if (!leftVersionId || !rightVersionId) return;
    setLoadingDiff(true);
    try {
      const [left, right] = await Promise.all([
        getTtlContent(leftVersionId).catch(() => ''),
        getTtlContent(rightVersionId).catch(() => ''),
      ]);
      setLeftContent(typeof left === 'string' ? left : String(left || ''));
      setRightContent(typeof right === 'string' ? right : String(right || ''));
      const changes = diffLines(
        typeof left === 'string' ? left : String(left || ''),
        typeof right === 'string' ? right : String(right || ''),
      );
      setDiffResult(changes);
    } catch {
      setDiffResult([]);
    } finally {
      setLoadingDiff(false);
    }
  }, [leftVersionId, rightVersionId]);

  // Auto compare when both versions are selected
  useEffect(() => {
    if (leftVersionId && rightVersionId) handleCompare();
  }, [leftVersionId, rightVersionId, handleCompare]);

  // Stats
  const added = diffResult.filter(c => c.added).length;
  const removed = diffResult.filter(c => c.removed).length;
  const unchanged = diffResult.filter(c => !c.added && !c.removed).length;

  const getLeftVersion = () => versions.find(v => v.id === leftVersionId);
  const getRightVersion = () => versions.find(v => v.id === rightVersionId);

  // Render unified diff
  const renderUnified = () => (
    <div style={{
      fontFamily: 'Consolas, Monaco, "Courier New", monospace',
      fontSize: 12, lineHeight: 1.7, maxHeight: '55vh', overflow: 'auto',
      background: '#fafafa', border: '1px solid #e8e8e8', borderRadius: 6, padding: 12,
      whiteSpace: 'pre-wrap', wordBreak: 'break-all',
    }}>
      {diffResult.map((change, i) => {
        let bg = 'transparent';
        let prefix = ' ';
        if (change.added) { bg = '#e6ffed'; prefix = '+'; }
        else if (change.removed) { bg = '#ffeef0'; prefix = '-'; }
        return (
          <div key={i} style={{ background: bg, padding: '0 4px' }}>
            {change.value.split('\n').filter((line, j, arr) => j < arr.length - 1 || line).map((line, j) => (
              <div key={j}>
                <span style={{ color: change.added ? '#22863a' : change.removed ? '#cb2431' : '#999', width: 20, display: 'inline-block', textAlign: 'center', userSelect: 'none' }}>
                  {prefix}
                </span>
                <span>{line || ' '}</span>
              </div>
            ))}
          </div>
        );
      })}
      {diffResult.length === 0 && !loadingDiff && (
        <div style={{ textAlign: 'center', padding: 40, color: '#999' }}>两个版本内容完全相同</div>
      )}
    </div>
  );

  // Render split view
  const renderSplit = () => {
    const leftLines: { text: string; type: 'removed' | 'unchanged' | 'empty' }[] = [];
    const rightLines: { text: string; type: 'added' | 'unchanged' | 'empty' }[] = [];

    diffResult.forEach(change => {
      const lines = change.value.split('\n').filter((l, j, arr) => j < arr.length - 1 || l);
      if (change.added) {
        lines.forEach(l => { leftLines.push({ text: '', type: 'empty' }); rightLines.push({ text: l, type: 'added' }); });
      } else if (change.removed) {
        lines.forEach(l => { leftLines.push({ text: l, type: 'removed' }); rightLines.push({ text: '', type: 'empty' }); });
      } else {
        lines.forEach(l => { leftLines.push({ text: l, type: 'unchanged' }); rightLines.push({ text: l, type: 'unchanged' }); });
      }
    });

    const lineStyle: React.CSSProperties = {
      fontFamily: 'Consolas, Monaco, "Courier New", monospace',
      fontSize: 12, lineHeight: 1.7, padding: '0 8px',
      whiteSpace: 'pre', overflow: 'hidden',
    };

    return (
      <Row gutter={0} style={{
        maxHeight: '55vh', overflow: 'auto',
        border: '1px solid #e8e8e8', borderRadius: 6,
      }}>
        <Col span={12} style={{ borderRight: '1px solid #e8e8e8', overflow: 'hidden' }}>
          <div style={{ background: '#f6f8fa', padding: '4px 12px', borderBottom: '1px solid #e8e8e8', fontSize: 12 }}>
            <Text type="secondary">{getLeftVersion()?.versionName || '旧版本'}</Text>
          </div>
          {leftLines.map((l, i) => (
            <div key={i} style={{
              ...lineStyle,
              background: l.type === 'removed' ? '#ffeef0' : l.type === 'empty' ? '#f8f8f8' : 'transparent',
            }}>
              <span style={{ color: '#999', width: 40, display: 'inline-block', textAlign: 'right', marginRight: 8, userSelect: 'none', flexShrink: 0 }}>
                {l.type !== 'empty' ? i + 1 : ''}
              </span>
              <span style={{ color: l.type === 'removed' ? '#cb2431' : '#333' }}>
                {l.type === 'removed' ? '- ' : '  '}{l.text || ' '}
              </span>
            </div>
          ))}
        </Col>
        <Col span={12} style={{ overflow: 'hidden' }}>
          <div style={{ background: '#f6f8fa', padding: '4px 12px', borderBottom: '1px solid #e8e8e8', fontSize: 12 }}>
            <Text type="secondary">{getRightVersion()?.versionName || '新版本'}</Text>
          </div>
          {rightLines.map((l, i) => (
            <div key={i} style={{
              ...lineStyle,
              background: l.type === 'added' ? '#e6ffed' : l.type === 'empty' ? '#f8f8f8' : 'transparent',
            }}>
              <span style={{ color: '#999', width: 40, display: 'inline-block', textAlign: 'right', marginRight: 8, userSelect: 'none', flexShrink: 0 }}>
                {l.type !== 'empty' ? i + 1 : ''}
              </span>
              <span style={{ color: l.type === 'added' ? '#22863a' : '#333' }}>
                {l.type === 'added' ? '+ ' : '  '}{l.text || ' '}
              </span>
            </div>
          ))}
        </Col>
        {diffResult.length === 0 && !loadingDiff && (
          <div style={{ textAlign: 'center', padding: 40, color: '#999', width: '100%' }}>两个版本内容完全相同</div>
        )}
      </Row>
    );
  };

  return (
    <Modal
      title={
        <Space>
          <SwapOutlined />
          <span>版本对比 — {ontologyName}</span>
        </Space>
      }
      open={open}
      onCancel={onClose}
      footer={null}
      width="90vw"
      style={{ top: 20 }}
      destroyOnHidden
    >
      {/* Version selectors */}
      <Row gutter={16} align="middle" style={{ marginBottom: 16 }}>
        <Col span={10}>
          <Select
            placeholder="选择旧版本"
            style={{ width: '100%' }}
            value={leftVersionId || undefined}
            onChange={setLeftVersionId}
            loading={loadingVersions}
            options={versions.map(v => ({
              value: v.id,
              label: `${v.versionName || v.version || '未知'} (${v.createdAt ? new Date(v.createdAt).toLocaleDateString() : '-'})`,
            }))}
          />
        </Col>
        <Col span={4} style={{ textAlign: 'center' }}>
          <SwapOutlined style={{ fontSize: 18, color: '#999' }} />
        </Col>
        <Col span={10}>
          <Select
            placeholder="选择新版本"
            style={{ width: '100%' }}
            value={rightVersionId || undefined}
            onChange={setRightVersionId}
            loading={loadingVersions}
            options={versions.map(v => ({
              value: v.id,
              label: `${v.versionName || v.version || '未知'} (${v.createdAt ? new Date(v.createdAt).toLocaleDateString() : '-'})`,
            }))}
          />
        </Col>
      </Row>

      {/* Stats */}
      {diffResult.length > 0 && (
        <Row gutter={16} style={{ marginBottom: 12 }}>
          <Col span={6}>
            <Statistic title="新增行" value={added} prefix={<PlusCircleOutlined style={{ color: '#52c41a' }} />} valueStyle={{ color: '#52c41a', fontSize: 18 }} />
          </Col>
          <Col span={6}>
            <Statistic title="删除行" value={removed} prefix={<MinusCircleOutlined style={{ color: '#ff4d4f' }} />} valueStyle={{ color: '#ff4d4f', fontSize: 18 }} />
          </Col>
          <Col span={6}>
            <Statistic title="修改段" value={diffResult.length} prefix={<EditOutlined style={{ color: '#1677ff' }} />} valueStyle={{ fontSize: 18 }} />
          </Col>
          <Col span={6}>
            <Statistic title="不变行" value={unchanged} valueStyle={{ color: '#999', fontSize: 18 }} />
          </Col>
        </Row>
      )}

      {/* View mode switcher */}
      <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Segmented
          size="small"
          value={viewMode}
          onChange={(val) => setViewMode(val as DiffViewMode)}
          options={[
            { value: 'unified', label: '统一视图' },
            { value: 'split', label: '并排视图' },
          ]}
        />
        {diffResult.length > 0 && (
          <Space size={4}>
            <Tag color="green">+{added}</Tag>
            <Tag color="red">-{removed}</Tag>
          </Space>
        )}
      </div>

      {/* Diff content */}
      <Spin spinning={loadingDiff} tip="加载版本内容...">
        {diffResult.length > 0 || loadingDiff ? (
          viewMode === 'unified' ? renderUnified() : renderSplit()
        ) : (
          !loadingDiff && leftVersionId && rightVersionId ? (
            <Empty description="两个版本内容完全相同" />
          ) : (
            <Empty description={loadingVersions ? '加载版本列表...' : '请选择两个版本进行对比'} />
          )
        )}
      </Spin>
    </Modal>
  );
};

export default VersionDiff;
