/**
 * 变更历史时间线 — 可视化本体版本演进
 *
 * 使用 Ant Design Timeline 展示版本变更，支持跳转到版本 Diff
 */
import { getAllOntologyVersions } from '@/api/ontology';
import {
ApartmentOutlined,
ClockCircleOutlined,
LinkOutlined,
SwapOutlined,
ThunderboltOutlined,
} from '@ant-design/icons';
import {
Button,
Empty,
Modal,
Space,
Spin,
Tag,
Timeline,
Typography,
} from 'antd';
import React,{ useEffect,useState } from 'react';
import VersionDiff from './VersionDiff';

const { Text } = Typography;

interface VersionItem {
  id: string;
  version: string;
  versionName: string;
  name: string;
  description: string;
  classCount: number;
  propertyCount: number;
  objectPropertyCount?: number;
  dataPropertyCount?: number;
  ruleCount: number;
  individualCount: number;
  createdAt: string;
  updatedAt: string;
  instantiated: boolean;
  status: string;
}

interface Props {
  ontologyName: string;
  open: boolean;
  onClose: () => void;
}

const ChangeHistory: React.FC<Props> = ({ ontologyName, open, onClose }) => {
  const [allVersions, setAllVersions] = useState<VersionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [filteredName, setFilteredName] = useState('');

  // Diff modal state
  const [diffModalOpen, setDiffModalOpen] = useState(false);
  const [diffOntologyId, setDiffOntologyId] = useState('');
  const [diffOntologyName, setDiffOntologyName] = useState('');

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    setFilteredName(ontologyName);
    getAllOntologyVersions(ontologyName || undefined)
      .then((list: any[]) => {
        const mapped: VersionItem[] = (Array.isArray(list) ? list : []).map((v: any) => ({
          id: v.id || v.ontologyId || '',
          version: v.version || '1.0',
          versionName: v.versionName || v.version || '',
          name: v.name || '',
          description: v.description || '',
          classCount: v.classCount || 0,
          propertyCount: (v.objectPropertyCount || 0) + (v.dataPropertyCount || 0),
          objectPropertyCount: v.objectPropertyCount || 0,
          dataPropertyCount: v.dataPropertyCount || 0,
          ruleCount: v.ruleCount || 0,
          individualCount: v.individualCount || 0,
          createdAt: v.createdAt || '',
          updatedAt: v.updatedAt || '',
          instantiated: v.instantiated || false,
          status: v.status || '',
        }));
        setAllVersions(mapped);
      })
      .catch(() => setAllVersions([]))
      .finally(() => setLoading(false));
  }, [open, ontologyName]);

  // Group by name
  const grouped = allVersions.reduce((acc: Record<string, VersionItem[]>, v) => {
    const key = v.name || '其他';
    if (!acc[key]) acc[key] = [];
    acc[key].push(v);
    return acc;
  }, {});

  const handleCompareVersions = (v1: VersionItem, v2: VersionItem) => {
    setDiffOntologyId(v1.id);
    setDiffOntologyName(`${v1.name || ''} ${v1.versionName} vs ${v2.versionName}`);
    setDiffModalOpen(true);
  };

  const formatTime = (t: string) => {
    if (!t) return '-';
    try {
      return new Date(t).toLocaleString('zh-CN', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit',
      });
    } catch {
      return t.replace('T', ' ').substring(0, 19);
    }
  };

  return (
    <>
      <Modal
        title={
          <Space>
            <ClockCircleOutlined />
            <span>变更历史{ontologyName ? ` — ${ontologyName}` : ''}</span>
          </Space>
        }
        open={open}
        onCancel={onClose}
        footer={null}
        width="80vw"
        style={{ top: 20 }}
        destroyOnHidden
      >
        <Spin spinning={loading}>
          {Object.keys(grouped).length === 0 ? (
            <Empty description={loading ? '加载中...' : '暂无版本历史'} />
          ) : (
            <div style={{ maxHeight: '60vh', overflow: 'auto', padding: '16px 0' }}>
              {Object.entries(grouped).map(([name, versions]) => (
                <div key={name} style={{ marginBottom: 24 }}>
                  <Text strong style={{ fontSize: 15, display: 'block', marginBottom: 12, paddingLeft: 8, borderLeft: '3px solid #1677ff' }}>
                    📦 {name}
                    <Tag style={{ marginLeft: 8 }}>{versions.length} 个版本</Tag>
                  </Text>
                  <Timeline
                    mode="left"
                    items={versions
                      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
                      .map((v, i, arr) => ({
                        color: i === 0 ? 'green' : v.instantiated ? 'blue' : 'gray',
                        dot: i === 0 ? <ClockCircleOutlined style={{ fontSize: 16 }} /> : undefined,
                        children: (
                          <div style={{
                            padding: '8px 12px', background: '#fafafa', borderRadius: 6,
                            border: '1px solid #f0f0f0', marginBottom: 4,
                          }}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
                              <Space size={8}>
                                <Text strong style={{ fontSize: 14 }}>
                                  {v.versionName || `v${v.version}`}
                                </Text>
                                {i === 0 && <Tag color="green">最新</Tag>}
                                {v.instantiated && <Tag color="blue">已实例化</Tag>}
                              </Space>
                              <Space size={4}>
                                <Text type="secondary" style={{ fontSize: 11 }}>
                                  {formatTime(v.createdAt)}
                                </Text>
                                {i < arr.length - 1 && (
                                  <Button
                                    size="small"
                                    type="link"
                                    icon={<SwapOutlined />}
                                    onClick={() => handleCompareVersions(v, arr[i + 1])}
                                  >
                                    与上一版对比
                                  </Button>
                                )}
                              </Space>
                            </div>
                            {v.description && (
                              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
                                {v.description}
                              </Text>
                            )}
                            <Space size={12} wrap>
                              <span><ApartmentOutlined style={{ color: '#1677ff', marginRight: 4 }} />{v.classCount} 类</span>
                              <span><LinkOutlined style={{ color: '#722ed1', marginRight: 4 }} />{v.objectPropertyCount ?? 0} 对象属性 · {v.dataPropertyCount ?? 0} 数据属性</span>
                              {v.ruleCount > 0 && <span><ThunderboltOutlined style={{ color: '#fa8c16', marginRight: 4 }} />{v.ruleCount} 规则</span>}
                            </Space>
                          </div>
                        ),
                      }))}
                  />
                </div>
              ))}
            </div>
          )}
        </Spin>
      </Modal>

      {/* Nested Version Diff */}
      <VersionDiff
        ontologyId={diffOntologyId}
        ontologyName={diffOntologyName}
        open={diffModalOpen}
        onClose={() => setDiffModalOpen(false)}
      />
    </>
  );
};

export default ChangeHistory;
