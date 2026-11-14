/**
 * LinkTypesView — 关系类型管理
 * 仅展示对象属性，支持编辑 label 和 description 并同步 TTL
 */
import { deleteLinkType,regenerateTtl,updateLinkType } from '@/api/ontology';
import { syncRulesToTtl } from '@/api/rule';
import type { SwrlVocabItem,SwrlVocabulary } from '@/types';
import { ApartmentOutlined,DeleteOutlined,EditOutlined,SearchOutlined,TableOutlined } from '@ant-design/icons';
import { Button,Empty,Form,Input,message,Modal,Popconfirm,Segmented,Space,Table,Tag,Tooltip,Typography } from 'antd';
import React,{ useMemo,useState } from 'react';
import LinkTypesGraphView from './LinkTypesGraphView';

const { Title, Text } = Typography;

interface LinkTypesViewProps {
  vocabulary: SwrlVocabulary | null;
  ontologyId: string;
  onRefresh?: () => void;
}

const LinkTypesView: React.FC<LinkTypesViewProps> = ({ vocabulary, ontologyId, onRefresh }) => {
  const [viewMode, setViewMode] = useState<'table' | 'graph'>('table');
  const [searchText, setSearchText] = useState('');
  const [pageSize, setPageSize] = useState(20);
  const [editingLink, setEditingLink] = useState<SwrlVocabItem | null>(null);
  const [editForm] = Form.useForm();
  const [saving, setSaving] = useState(false);

  const links = vocabulary?.objectProperties || [];
  const classes = vocabulary?.classes || [];

  const classLabelMap = useMemo(() => {
    const map: Record<string, string> = {};
    for (const c of classes) {
      if (c.iri) map[c.iri] = c.label || c.shortName;
      if (c.shortName) map[c.shortName] = c.label || c.shortName;
    }
    return map;
  }, [classes]);

  const getShort = (iri: string | undefined | null) => {
    if (!iri) return '-';
    return iri.includes('#') ? iri.split('#').pop()! : iri.split('/').pop() || iri;
  };

  const filtered = useMemo(() => {
    if (!searchText) return links;
    const s = searchText.toLowerCase();
    return links.filter((l) => {
      if (l.label?.toLowerCase().includes(s)) return true;
      if (l.shortName?.toLowerCase().includes(s)) return true;
      if (l.domain?.toLowerCase().includes(s)) return true;
      if (l.range?.toLowerCase().includes(s)) return true;
      // 同时按 domain/range 解析后的中文标签搜索
      const domainShort = getShort(l.domain);
      const domainLabel = classLabelMap[l.domain || ''] || classLabelMap[domainShort] || '';
      if (domainLabel.toLowerCase().includes(s)) return true;
      const rangeShort = getShort(l.range);
      const rangeLabel = classLabelMap[l.range || ''] || classLabelMap[rangeShort] || '';
      if (rangeLabel.toLowerCase().includes(s)) return true;
      return false;
    });
  }, [links, searchText, classLabelMap]);

  const handleEdit = (link: SwrlVocabItem) => {
    setEditingLink(link);
    editForm.setFieldsValue({ label: link.label, description: link.description || '' });
  };

  const handleSave = async () => {
    const values = await editForm.validateFields();
    if (!editingLink?.id) return;
    setSaving(true);
    try {
      await updateLinkType(ontologyId, editingLink.id, {
        label: values.label,
        description: values.description || '',
      });
      await regenerateTtl(ontologyId);
      await syncRulesToTtl(ontologyId); // 同时同步 SWRL 规则到 TTL
      message.success('已更新，TTL 文件已同步');
      setEditingLink(null);
      onRefresh?.();
    } catch (e: any) {
      message.error(e?.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (link: SwrlVocabItem) => {
    if (!link.id) return;
    setSaving(true);
    try {
      await deleteLinkType(ontologyId, link.id);
      await regenerateTtl(ontologyId);
      await syncRulesToTtl(ontologyId);
      message.success(`已删除关系 "${link.label || link.shortName}"，TTL 文件已同步`);
      setEditingLink(null);
      onRefresh?.();
    } catch (e: any) {
      message.error(e?.message || '删除失败');
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    { title: '对象1 (Domain)', dataIndex: 'domain', key: 'domain', width: 180,
      render: (d: string) => { const s = getShort(d); const l = classLabelMap[d] || classLabelMap[s] || '';
        return <Tooltip title={d}><div><Text strong style={{ fontSize: 13 }}>{l || s}</Text><br /><Text code style={{ fontSize: 11 }}>{s}</Text></div></Tooltip>; }},
    { title: '关系', dataIndex: 'label', key: 'label', width: 180,
      render: (label: string, r: SwrlVocabItem) => <div><Text strong style={{ color: '#1677ff' }}>{label || r.shortName}</Text><br /><Text code style={{ fontSize: 11 }}>{r.shortName}</Text></div> },
    { title: '对象2 (Range)', dataIndex: 'range', key: 'range', width: 180,
      render: (r: string) => { const s = getShort(r); const l = classLabelMap[r] || classLabelMap[s] || '';
        return <Tooltip title={r}><div><Text strong style={{ fontSize: 13 }}>{l || s}</Text><br /><Text code style={{ fontSize: 11 }}>{s}</Text></div></Tooltip>; }},
    { title: '操作', key: 'action', width: 130,
      render: (_: any, r: SwrlVocabItem) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(r)}>编辑</Button>
          <Popconfirm
            title="确认删除"
            description={`确定要删除关系 "${r.label || r.shortName}" 吗？此操作不可恢复。`}
            onConfirm={() => handleDelete(r)}
            okText="确认删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ) },
  ];

  const tableContentView = (
    <>
      <Input prefix={<SearchOutlined />} placeholder="搜索关系..." value={searchText}
        onChange={(e) => setSearchText(e.target.value)} allowClear style={{ marginBottom: 12, width: 320 }} />
      <div style={{ marginBottom: 12 }}><Tag color="blue">对象关系: {links.length}</Tag></div>
      <Table size="small" dataSource={filtered} columns={columns} rowKey="iri"
        pagination={{ pageSize, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条关系`, pageSizeOptions: ['10', '20', '50', '100'] }}
        onChange={(p) => setPageSize(p.pageSize ?? 20)}
        locale={{ emptyText: <Empty description="暂无关系类型" /> }} />

      <Modal title="编辑关系" open={!!editingLink} onOk={handleSave} onCancel={() => setEditingLink(null)}
        confirmLoading={saving} okText="保存" cancelText="取消">
        <Form form={editForm} layout="vertical">
          <Form.Item label="关系 IRI (不可修改)">
            <Input value={editingLink?.iri || ''} disabled />
          </Form.Item>
          <Form.Item label="关系英文名 (不可修改)">
            <Input value={editingLink?.shortName || ''} disabled />
          </Form.Item>
          <Form.Item name="label" label="中文标签" rules={[{ required: true, message: '请输入中文标签' }]}>
            <Input placeholder="如：拥有订单" />
          </Form.Item>
          <Form.Item name="description" label="备注描述">
            <Input.TextArea rows={2} placeholder="描述该关系的业务含义..." />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, flexWrap: 'wrap', gap: 8 }}>
        <Title level={4} style={{ margin: 0 }}>关系类型</Title>
        <Segmented
          value={viewMode}
          onChange={(val) => setViewMode(val as 'table' | 'graph')}
          options={[
            { label: <span><TableOutlined /> 表格视图</span>, value: 'table' },
            { label: <span><ApartmentOutlined /> 关系视图</span>, value: 'graph' },
          ]}
        />
      </div>
      {viewMode === 'graph' ? (
        <LinkTypesGraphView
          ontologyId={ontologyId}
          classes={vocabulary?.classes || []}
          objectProperties={vocabulary?.objectProperties || []}
        />
      ) : (
        tableContentView
      )}
    </div>
  );
};

export default LinkTypesView;
