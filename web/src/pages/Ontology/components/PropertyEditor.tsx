/**
 * PropertyEditor — 属性编辑侧面板 (对标 Protégé Property Assertions)
 *
 * 功能: 展示选中类的所有属性 (对象属性+数据属性)，支持内联编辑
 * 替代原来 Modal 式的属性添加/编辑
 */
import {
CloseOutlined,
DeleteOutlined,
EditOutlined,
LinkOutlined,
PlusOutlined,
SaveOutlined,
} from '@ant-design/icons';
import {
Button,
Card,
Divider,
Empty,
Input,
Popconfirm,
Select,
Space,
Tag,Typography
} from 'antd';
import React,{ useState } from 'react';

const { Text, Paragraph } = Typography;

interface Property {
  iri: string;
  id: string;
  label: string;
  sourceType?: string;
  targetType?: string;
  description?: string;
  // Data properties
  domain?: string;
  range?: string;
}

interface PropertyEditorProps {
  selectedClass: any | null;
  objectProperties: Property[];
  dataProperties: Property[];
  allClasses: any[]; // For dropdown options
  onCreateProperty: (data: any) => Promise<any>;
  onUpdateProperty: (id: string, data: any) => Promise<void>;
  onDeleteProperty: (id: string) => Promise<void>;
}

const PropertyEditor: React.FC<PropertyEditorProps> = ({
  selectedClass, objectProperties, dataProperties, allClasses,
  onCreateProperty, onUpdateProperty, onDeleteProperty,
}) => {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editForm, setEditForm] = useState({ label: '', sourceType: '', targetType: '', description: '' });
  const [addingNew, setAddingNew] = useState(false);
  const [newForm, setNewForm] = useState({ label: '', sourceType: '', targetType: '', description: '' });

  if (!selectedClass) {
    return (
      <Card size="small" style={{ height: '100%' }}>
        <Empty description="选择类以查看属性" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </Card>
    );
  }

  // Filter properties for this class
  const classIri = selectedClass.iri || selectedClass.id;
  const relatedObjProps = objectProperties.filter(p => p.sourceType === classIri || p.targetType === classIri);
  const relatedDataProps = dataProperties.filter(p => p.domain === classIri || (p as any).className === selectedClass.label);

  const classOptions = allClasses.map((c: any) => ({
    value: c.iri || c.id,
    label: c.label || c.iri || c.id,
  }));

  const handleStartEdit = (prop: Property) => {
    setEditingId(prop.iri || prop.id);
    setEditForm({
      label: prop.label || '',
      sourceType: prop.sourceType || classIri,
      targetType: prop.targetType || '',
      description: prop.description || '',
    });
  };

  const handleSaveEdit = async () => {
    if (!editingId) return;
    await onUpdateProperty(editingId, editForm);
    setEditingId(null);
  };

  const handleAddNew = async () => {
    await onCreateProperty({
      ...newForm,
      sourceType: classIri,
    });
    setAddingNew(false);
    setNewForm({ label: '', sourceType: classIri, targetType: '', description: '' });
  };

  return (
    <Card size="small" style={{ height: '100%', overflow: 'auto' }}
      title={
        <Space>
          <LinkOutlined />
          <Text ellipsis style={{ maxWidth: 200 }}>{selectedClass.label || '未命名类'}</Text>
          <Tag>{relatedObjProps.length + relatedDataProps.length} 个属性</Tag>
        </Space>
      }
      extra={
        <Button size="small" icon={<PlusOutlined />} onClick={() => setAddingNew(true)}>
          添加属性
        </Button>
      }
    >
      {/* Object Properties */}
      <Text strong style={{ fontSize: 12, color: '#1677ff' }}>对象属性</Text>
      {relatedObjProps.length === 0 ? (
        <Text type="secondary" style={{ fontSize: 11, display: 'block', margin: '8px 0' }}>无对象属性</Text>
      ) : (
        relatedObjProps.map(prop => (
          <div key={prop.iri || prop.id} style={{
            padding: '6px 8px', marginBottom: 4, background: '#f6f8fa',
            borderRadius: 4, border: '1px solid #e8e8e8',
          }}>
            {editingId === (prop.iri || prop.id) ? (
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                <Input size="small" placeholder="标签" value={editForm.label}
                  onChange={e => setEditForm(p => ({ ...p, label: e.target.value }))} />
                <Select size="small" style={{ width: '100%' }} placeholder="目标类"
                  value={editForm.targetType || undefined}
                  onChange={v => setEditForm(p => ({ ...p, targetType: v }))}
                  options={classOptions} />
                <Input size="small" placeholder="描述" value={editForm.description}
                  onChange={e => setEditForm(p => ({ ...p, description: e.target.value }))} />
                <Space size={4}>
                  <Button size="small" icon={<SaveOutlined />} type="primary" onClick={handleSaveEdit} />
                  <Button size="small" icon={<CloseOutlined />} onClick={() => setEditingId(null)} />
                </Space>
              </Space>
            ) : (
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                  <Text style={{ fontSize: 12 }}>{prop.label || prop.iri}</Text>
                  {prop.targetType && (
                    <Text type="secondary" style={{ fontSize: 10, marginLeft: 6 }}>
                      → {allClasses.find(c => (c.iri || c.id) === prop.targetType)?.label || prop.targetType}
                    </Text>
                  )}
                </div>
                <Space size={2}>
                  <Button size="small" type="text" icon={<EditOutlined />} onClick={() => handleStartEdit(prop)} />
                  <Popconfirm title="删除此属性?" onConfirm={() => onDeleteProperty(prop.iri || prop.id)}>
                    <Button size="small" type="text" danger icon={<DeleteOutlined />} />
                  </Popconfirm>
                </Space>
              </div>
            )}
          </div>
        ))
      )}

      <Divider style={{ margin: '8px 0' }} />

      {/* Data Properties */}
      <Text strong style={{ fontSize: 12, color: '#722ed1' }}>数据属性</Text>
      {relatedDataProps.length === 0 ? (
        <Text type="secondary" style={{ fontSize: 11, display: 'block', margin: '8px 0' }}>无数据属性</Text>
      ) : (
        relatedDataProps.map(prop => (
          <div key={prop.iri || prop.id} style={{
            padding: '4px 8px', marginBottom: 2, fontSize: 11, color: '#666',
          }}>
            📊 {prop.label || prop.iri}
            {prop.range && <Tag style={{ fontSize: 9, marginLeft: 4 }}>{prop.range}</Tag>}
          </div>
        ))
      )}

      {/* Add New Property Form */}
      {addingNew && (
        <Card size="small" style={{ marginTop: 8, background: '#f6ffed', border: '1px solid #b7eb8f' }}
          title="新建对象属性">
          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            <Input size="small" placeholder="属性标签 (如 hasPart)" value={newForm.label}
              onChange={e => setNewForm(p => ({ ...p, label: e.target.value }))} />
            <Select size="small" style={{ width: '100%' }} placeholder="目标类"
              value={newForm.targetType || undefined}
              onChange={v => setNewForm(p => ({ ...p, targetType: v }))}
              options={classOptions} />
            <Input size="small" placeholder="描述 (可选)" value={newForm.description}
              onChange={e => setNewForm(p => ({ ...p, description: e.target.value }))} />
            <Space>
              <Button size="small" type="primary" icon={<PlusOutlined />} onClick={handleAddNew}>创建</Button>
              <Button size="small" onClick={() => setAddingNew(false)}>取消</Button>
            </Space>
          </Space>
        </Card>
      )}
    </Card>
  );
};

export default PropertyEditor;
