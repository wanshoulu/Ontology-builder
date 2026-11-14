/**
 * VersionList — 本体版本列表（左侧面板）
 * 设计对齐: 文档知识模块的卡片式布局 + 紧凑表格 + 状态标签
 */
import {
ApartmentOutlined,
DeleteOutlined,
EditOutlined,
ExportOutlined,
EyeOutlined,
FileTextOutlined,
HistoryOutlined,
LinkOutlined,
SafetyCertificateOutlined,
ThunderboltOutlined
} from '@ant-design/icons';
import { Button,Empty,Input,Space,Spin,Table,Tag,Tooltip,Typography } from 'antd';
import React from 'react';
import type { OntologyVersion } from '../hooks/useOntologyVersions';

const { Text } = Typography;

interface VersionListProps {
  allVersionsLoading: boolean;
  groupedVersions: Record<string, OntologyVersion[]>;
  ontologyNames: string[];
  selectedVersionId: string;
  searchText: string;
  onSearchChange: (text: string) => void;
  filteredGroupedVersions: Record<string, OntologyVersion[]>;
  selectedRowKeys: string[];
  onSelectedRowKeysChange: (keys: string[]) => void;
  onVersionSelect: (id: string) => void;
  onBatchDelete: () => void;
  onViewTtl: (id: string) => void;
  onEditTtl: (id: string) => void;
  onExportTtl: (version: OntologyVersion) => void;
  onExportMd: (version: OntologyVersion) => void;
  onRename: (version: OntologyVersion) => void;
  onValidate: (version: OntologyVersion) => void;
  onDelete: (id: string) => void;
  onOpenProtege?: (version: OntologyVersion) => void;
}

const VersionList: React.FC<VersionListProps> = ({
  allVersionsLoading, groupedVersions, ontologyNames,
  selectedVersionId, searchText, onSearchChange,
  filteredGroupedVersions, selectedRowKeys, onSelectedRowKeysChange,
  onVersionSelect, onBatchDelete,
  onViewTtl, onEditTtl, onExportTtl, onExportMd,
  onRename, onValidate, onDelete, onOpenProtege,
}) => {
  // Compute total stats
  const totalVersions = Object.values(groupedVersions).reduce((s, g) => s + g.length, 0);
  const totalClasses = Object.values(groupedVersions).flat().reduce((s, v) => s + (v.classCount || 0), 0);

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* Search bar */}
      <div style={{ padding: '10px 14px', borderBottom: '1px solid #f0f0f0', background: '#fafafa' }}>
        <Input.Search
          placeholder="搜索本体名称、描述、版本..."
          allowClear
          value={searchText}
          onChange={e => onSearchChange(e.target.value)}
          size="small"
        />
      </div>

      {/* Title + Batch actions */}
      <div style={{
        padding: '8px 14px', borderBottom: '1px solid #f0f0f0',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <Space size={4}>
          <HistoryOutlined style={{ color: '#1677ff', fontSize: 16 }} />
          <Text strong style={{ fontSize: 14 }}>版本列表</Text>
          <Tag color="blue">{totalVersions} 版本</Tag>
        </Space>
        <Space size={4}>
          <Text type="secondary" style={{ fontSize: 10 }}>{totalClasses} 类总计</Text>
          <Button danger size="small" icon={<DeleteOutlined />}
            onClick={onBatchDelete}
            disabled={selectedRowKeys.length === 0}>
            批量删除{selectedRowKeys.length > 0 ? ` (${selectedRowKeys.length})` : ''}
          </Button>
        </Space>
      </div>

      {/* Version table */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        {allVersionsLoading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <Spin tip="加载版本列表..." />
          </div>
        ) : ontologyNames.length === 0 ? (
          <Empty description={searchText ? `未找到 "${searchText}"` : '暂无本体模型，点击上方「Markdown 自动提取本体模型」创建'} style={{ marginTop: 60 }} />
        ) : (
          <>
            {ontologyNames.map(name => {
              const group = filteredGroupedVersions[name] || groupedVersions[name] || [];
              const gClasses = group.reduce((s, v) => s + (v.classCount || 0), 0);
              const gProps = group.reduce((s, v) => s + (v.objectPropertyCount || 0) + (v.dataPropertyCount || 0), 0);
              const gRules = group.reduce((s, v) => s + (v.ruleCount || 0), 0);
              return (
                <div key={name} style={{ borderBottom: '1px solid #f0f0f0' }}>
                  {/* Group header */}
                  <div style={{
                    padding: '10px 14px',
                    background: selectedVersionId && group.some(v => v.id === selectedVersionId) ? '#e6f4ff' : '#fafbfc',
                    display: 'flex', alignItems: 'center', gap: 8,
                    borderLeft: selectedVersionId && group.some(v => v.id === selectedVersionId) ? '3px solid #1677ff' : '3px solid transparent',
                    transition: 'all .2s',
                  }}>
                    <FileTextOutlined style={{ color: '#1677ff', fontSize: 15 }} />
                    <Text strong style={{ fontSize: 14, flex: 1 }}>{name}</Text>
                    <Space size={4}>
                      <Tag color="blue" style={{ fontSize: 11 }}>{group.length} 版本</Tag>
                      <Tag style={{ fontSize: 10 }}>{gClasses} 类</Tag>
                      {gProps > 0 && <Tag color="purple" style={{ fontSize: 10 }}>{gProps} 属性</Tag>}
                      {gRules > 0 && <Tag color="orange" style={{ fontSize: 10 }}>{gRules} 规则</Tag>}
                    </Space>
                  </div>

                  {/* Version rows */}
                  <Table
                    dataSource={group}
                    rowKey="id"
                    size="small"
                    showHeader={false}
                    pagination={false}
                    rowSelection={{
                      selectedRowKeys: group.filter(v => selectedRowKeys.includes(v.id)).map(v => v.id),
                      onChange: (keys) => {
                        const thisSet = new Set(group.map(v => v.id));
                        const others = selectedRowKeys.filter(k => !thisSet.has(k));
                        onSelectedRowKeysChange([...others, ...keys.map(String)]);
                      },
                    }}
                    rowClassName={(r: OntologyVersion) =>
                      selectedVersionId === r.id ? 'ant-table-row-selected' : ''
                    }
                    onRow={(r: OntologyVersion) => ({
                      onClick: () => onVersionSelect(r.id),
                      onDoubleClick: () => onOpenProtege?.(r),
                      style: { cursor: 'pointer' },
                    })}
                    columns={[
                      {
                        title: '版本',
                        width: 120,
                        render: (_: any, r: OntologyVersion) => (
                          <div>
                            {r.versionName && (
                              <Tag color={r.current ? 'success' : 'processing'} style={{ fontSize: 11 }}>
                                {r.versionName}
                              </Tag>
                            )}
                            {r.current && <span style={{ color: '#52c41a', fontSize: 10, marginLeft: 2 }}>最新</span>}
                            {r.instantiated && <Tag color="cyan" style={{ fontSize: 9, marginLeft: 2 }}>实例化</Tag>}
                          </div>
                        ),
                      },
                      {
                        title: '统计',
                        width: 130,
                        render: (_: any, r: OntologyVersion) => (
                          <Space size={4} wrap style={{ fontSize: 11 }}>
                            <Tooltip title="类"><span><ApartmentOutlined style={{ color: '#1677ff', fontSize: 10 }} /> {r.classCount || 0}</span></Tooltip>
                            <Tooltip title="对象属性"><span><LinkOutlined style={{ color: '#722ed1', fontSize: 10 }} /> {r.objectPropertyCount ?? 0}</span></Tooltip>
                            <Tooltip title="数据属性"><span><LinkOutlined style={{ color: '#13c2c2', fontSize: 10 }} /> {r.dataPropertyCount ?? 0}</span></Tooltip>
                            {(r.ruleCount ?? 0) > 0 && <Tooltip title="规则"><span><ThunderboltOutlined style={{ color: '#fa8c16', fontSize: 10 }} /> {r.ruleCount}</span></Tooltip>}
                          </Space>
                        ),
                      },
                      {
                        title: '时间/来源',
                        width: 115,
                        render: (_: any, r: OntologyVersion) => (
                          <div style={{ fontSize: 10, lineHeight: '14px' }}>
                            {r.ttlGeneratedAt ? (
                              <Text type="secondary">{r.ttlGeneratedAt.replace('T', ' ').substring(0, 16)}</Text>
                            ) : <Text type="secondary">-</Text>}
                            {r.sourceModelName && <Tag style={{ fontSize: 9, marginTop: 2 }}>{r.sourceModelName.slice(0, 12)}</Tag>}
                          </div>
                        ),
                      },
                      {
                        title: '操作',
                        width: 170,
                        render: (_: any, r: OntologyVersion) => (
                          <Space size={0}>
                            {onOpenProtege && (
                              <Tooltip title="可视化编辑">
                                <Button type="text" size="small"
                                  icon={<ApartmentOutlined style={{ color: '#1677ff' }} />}
                                  onClick={e => { e.stopPropagation(); onOpenProtege(r); }} />
                              </Tooltip>
                            )}
                            <Tooltip title="查看 TTL"><Button type="text" size="small" icon={<EyeOutlined />} onClick={e => { e.stopPropagation(); onViewTtl(r.id); }} /></Tooltip>
                            <Tooltip title="编辑 TTL"><Button type="text" size="small" icon={<EditOutlined />} onClick={e => { e.stopPropagation(); onEditTtl(r.id); }} /></Tooltip>
                            <Tooltip title="校验"><Button type="text" size="small" icon={<SafetyCertificateOutlined />} onClick={e => { e.stopPropagation(); onValidate(r); }} /></Tooltip>
                            <Tooltip title="导出 TTL"><Button type="text" size="small" icon={<ExportOutlined />} onClick={e => { e.stopPropagation(); onExportTtl(r); }} /></Tooltip>
                            <Tooltip title="导出 Markdown"><Button type="text" size="small" icon={<FileTextOutlined />} onClick={e => { e.stopPropagation(); onExportMd(r); }} /></Tooltip>
                            <Tooltip title="重命名"><Button type="text" size="small" icon={<EditOutlined />} onClick={e => { e.stopPropagation(); onRename(r); }} /></Tooltip>
                            <Tooltip title="删除"><Button type="text" size="small" danger icon={<DeleteOutlined />} onClick={e => { e.stopPropagation(); onDelete(r.id); }} /></Tooltip>
                          </Space>
                        ),
                      },
                    ]}
                  />
                </div>
              );
            })}
          </>
        )}
      </div>
    </div>
  );
};

export default VersionList;
