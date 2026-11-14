/**
 * ObjectListView — 对象类型列表（运维浏览视角）
 * 展示本体中所有 Object Types，支持搜索、按类查看属性和关联规则
 */
import { deleteLinkType,deleteObjectType,regenerateTtl,updateLinkType,updateObjectType } from '@/api/ontology';
import { syncRulesToTtl } from '@/api/rule';
import type { BusinessRule,SwrlVocabItem,SwrlVocabulary } from '@/types';
import {
DeleteOutlined,
EditOutlined,
SearchOutlined
} from '@ant-design/icons';
import {
Button,
Collapse,
Divider,
Empty,
Form,
Input,
List,
message,
Modal,
Popconfirm,
Space,
Table,Tag,
Tooltip,
Typography
} from 'antd';
import React,{ useCallback,useEffect,useMemo,useRef,useState } from 'react';

const { Title, Text, Paragraph } = Typography;

interface ObjectListViewProps {
  ontologyId: string;
  vocabulary: SwrlVocabulary | null;
  rules: BusinessRule[];
  onRefresh?: () => void;
}

const ObjectListView: React.FC<ObjectListViewProps> = ({ ontologyId, vocabulary, rules, onRefresh }) => {
  const [searchText, setSearchText] = useState('');
  const [pageSize, setPageSize] = useState(15);
  const [selectedClass, setSelectedClass] = useState<SwrlVocabItem | null>(null);
  const [editingClass, setEditingClass] = useState<SwrlVocabItem | null>(null);
  const [editForm] = Form.useForm();
  const [saving, setSaving] = useState(false);

  // 单个属性编辑相关状态
  const [editingProperty, setEditingProperty] = useState<SwrlVocabItem | null>(null);
  const [editingPropertyType, setEditingPropertyType] = useState<'object' | 'data'>('object');
  const [propertyEditForm] = Form.useForm();
  const [propertySaving, setPropertySaving] = useState(false);

  // 左侧面板宽度（可拖拽调整）
  const [leftPanelWidth, setLeftPanelWidth] = useState(450);
  const [dragging, setDragging] = useState(false);
  const startXRef = useRef(0);
  const startWidthRef = useRef(450);

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
      const newWidth = Math.max(320, Math.min(700, startWidthRef.current + delta));
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

  const handleEditClass = (cls: SwrlVocabItem) => {
    setEditingClass(cls);
    editForm.setFieldsValue({ label: cls.label, description: cls.description || '' });
  };

  const handleSaveClass = async () => {
    const values = await editForm.validateFields();
    if (!editingClass?.id) return;
    setSaving(true);
    try {
      // 1. 获取编辑类关联的属性
      const { objProps, dataProps } = getPropertiesForClass(editingClass.iri);

      // 2. 保存对象属性（LinkType）的 label 和 description 编辑
      const objPropUpdates = objProps
        .filter((p) => (p as any)._labelEdit !== undefined || (p as any)._descEdit !== undefined)
        .map(async (p) => {
          if (!p.id) return;
          const updateData: any = {};
          if ((p as any)._labelEdit !== undefined) updateData.label = (p as any)._labelEdit;
          if ((p as any)._descEdit !== undefined) updateData.description = (p as any)._descEdit;
          await updateLinkType(ontologyId, p.id, updateData);
        });
      await Promise.all(objPropUpdates);

      // 3. 构建数据属性的 propertiesJson（合并编辑后的 label 和 description）
      const updatedDataProps = dataProps.map((p) => ({
        iri: p.iri,
        shortName: p.shortName,
        label: (p as any)._labelEdit !== undefined ? (p as any)._labelEdit : (p.label || p.shortName),
        domain: editingClass.iri,
        range: p.datatype || '',
        description: (p as any)._descEdit !== undefined ? (p as any)._descEdit : (p.description || ''),
      }));

      // 4. 保存对象类型基本信息 + 数据属性
      await updateObjectType(ontologyId, editingClass.id, {
        label: values.label,
        description: values.description || '',
        propertiesJson: JSON.stringify(updatedDataProps),
      });

      await regenerateTtl(ontologyId);
      await syncRulesToTtl(ontologyId);
      message.success('已更新，TTL 文件已同步');
      setEditingClass(null);
      setSelectedClass({ ...editingClass, label: values.label, description: values.description });
      onRefresh?.();
    } catch (e: any) {
      message.error(e?.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleDeleteClass = async (cls: SwrlVocabItem) => {
    if (!cls.id) return;
    setSaving(true);
    try {
      await deleteObjectType(ontologyId, cls.id);
      await regenerateTtl(ontologyId);
      await syncRulesToTtl(ontologyId);
      message.success(`已删除对象类型 "${cls.label || cls.shortName}"，TTL 文件已同步`);
      setSelectedClass(null);
      setEditingClass(null);
      onRefresh?.();
    } catch (e: any) {
      message.error(e?.message || '删除失败');
    } finally {
      setSaving(false);
    }
  };

  // ==================== 单个属性编辑 / 删除 ====================

  /** 打开属性编辑弹窗 */
  const handleEditProperty = (prop: SwrlVocabItem, type: 'object' | 'data') => {
    setEditingProperty(prop);
    setEditingPropertyType(type);
    propertyEditForm.setFieldsValue({
      label: prop.label || prop.shortName,
      description: prop.description || '',
    });
  };

  /** 保存属性编辑 */
  const handleSaveProperty = async () => {
    const values = await propertyEditForm.validateFields();
    if (!editingProperty) return;
    setPropertySaving(true);
    try {
      if (editingPropertyType === 'object') {
        // 对象属性：通过 updateLinkType API 更新
        if (editingProperty.id) {
          await updateLinkType(ontologyId, editingProperty.id, {
            label: values.label,
            description: values.description || '',
          });
        }
      } else {
        // 数据属性：更新所在类的 propertiesJson
        if (!selectedClass?.id) return;
        const { dataProps } = getPropertiesForClass(selectedClass.iri);
        const updatedDataProps = dataProps.map((p) => {
          const isTarget = p.iri === editingProperty.iri;
          return {
            iri: p.iri,
            shortName: p.shortName,
            label: isTarget ? values.label : (p.label || p.shortName),
            domain: selectedClass.iri,
            range: p.datatype || '',
            description: isTarget ? (values.description || '') : (p.description || ''),
          };
        });
        await updateObjectType(ontologyId, selectedClass.id, {
          propertiesJson: JSON.stringify(updatedDataProps),
        });
      }
      await regenerateTtl(ontologyId);
      await syncRulesToTtl(ontologyId);
      message.success('属性已更新，TTL 文件已同步');
      setEditingProperty(null);
      onRefresh?.();
    } catch (e: any) {
      message.error(e?.message || '保存失败');
    } finally {
      setPropertySaving(false);
    }
  };

  /** 删除单个属性 */
  const handleDeleteProperty = async (prop: SwrlVocabItem, type: 'object' | 'data') => {
    setPropertySaving(true);
    try {
      if (type === 'object') {
        // 对象属性：通过 deleteLinkType API 删除
        if (prop.id) {
          await deleteLinkType(ontologyId, prop.id);
        }
      } else {
        // 数据属性：从 propertiesJson 中移除
        if (!selectedClass?.id) return;
        const { dataProps } = getPropertiesForClass(selectedClass.iri);
        const updatedDataProps = dataProps
          .filter((p) => p.iri !== prop.iri)
          .map((p) => ({
            iri: p.iri,
            shortName: p.shortName,
            label: p.label || p.shortName,
            domain: selectedClass.iri,
            range: p.datatype || '',
            description: p.description || '',
          }));
        await updateObjectType(ontologyId, selectedClass.id, {
          propertiesJson: JSON.stringify(updatedDataProps),
        });
      }
      await regenerateTtl(ontologyId);
      await syncRulesToTtl(ontologyId);
      message.success(`已删除属性 "${prop.label || prop.shortName}"，TTL 文件已同步`);
      onRefresh?.();
    } catch (e: any) {
      message.error(e?.message || '删除失败');
    } finally {
      setPropertySaving(false);
    }
  };

  const classes = vocabulary?.classes || [];
  const objectProperties = vocabulary?.objectProperties || [];
  const dataProperties = vocabulary?.dataProperties || [];

  // 过滤
  const filteredClasses = useMemo(
    () =>
      classes.filter(
        (c) =>
          !searchText ||
          c.label?.toLowerCase().includes(searchText.toLowerCase()) ||
          c.shortName?.toLowerCase().includes(searchText.toLowerCase()) ||
          c.iri?.toLowerCase().includes(searchText.toLowerCase())
      ),
    [classes, searchText]
  );

  // 获取某个类涉及的规则
  const getRulesForClass = (classShortName: string): BusinessRule[] => {
    return rules.filter((r) => {
      if (!r.swrlRule) return false;
      return r.swrlRule.includes(classShortName);
    });
  };

  // 获取某个类的属性
  const getPropertiesForClass = (classIri: string) => {
    const objProps = objectProperties.filter((p) => p.domain === classIri);
    const dataProps = dataProperties.filter((p) => p.domain === classIri);
    return { objProps, dataProps };
  };

  const columns = [
    {
      title: '类名',
      dataIndex: 'label',
      key: 'label',
      width: 180,
      render: (label: string, record: SwrlVocabItem) => (
        <div>
          <Text strong style={{ fontSize: 14 }}>{label || record.shortName}</Text>
          <br />
          <Text type="secondary" style={{ fontSize: 12 }} code>
            {record.shortName}
          </Text>
        </div>
      ),
    },
    {
      title: 'IRI',
      dataIndex: 'iri',
      key: 'iri',
      ellipsis: true,
      render: (iri: string) => (
        <Tooltip title={iri}>
          <Text style={{ fontSize: 12 }} code ellipsis>
            {iri}
          </Text>
        </Tooltip>
      ),
    },
    {
      title: '数据属性',
      key: 'dataPropCount',
      width: 80,
      sorter: (a: SwrlVocabItem, b: SwrlVocabItem) => {
        const aProps = getPropertiesForClass(a.iri);
        const bProps = getPropertiesForClass(b.iri);
        return aProps.dataProps.length - bProps.dataProps.length;
      },
      render: (_: any, record: SwrlVocabItem) => {
        const { dataProps } = getPropertiesForClass(record.iri);
        return <Text>{dataProps.length}</Text>;
      },
    },
    {
      title: '对象属性',
      key: 'objPropCount',
      width: 80,
      sorter: (a: SwrlVocabItem, b: SwrlVocabItem) => {
        const aProps = getPropertiesForClass(a.iri);
        const bProps = getPropertiesForClass(b.iri);
        return aProps.objProps.length - bProps.objProps.length;
      },
      render: (_: any, record: SwrlVocabItem) => {
        const { objProps } = getPropertiesForClass(record.iri);
        return <Text>{objProps.length}</Text>;
      },
    },
  ];

  // 选中类后的详情面板
  const renderDetail = () => {
    if (!selectedClass) {
      return (
        <Empty
          description="点击左侧表格中的类来查看详情"
          style={{ marginTop: 60 }}
        />
      );
    }

    const { objProps, dataProps } = getPropertiesForClass(selectedClass.iri);
    const relatedRules = getRulesForClass(selectedClass.shortName);
    const bodyRules = relatedRules.filter(
      (r) => r.swrlRule && r.swrlRule.split('->')[0]?.includes(selectedClass.shortName)
    );
    const headRules = relatedRules.filter(
      (r) => r.swrlRule && r.swrlRule.split('->')[1]?.includes(selectedClass.shortName)
    );

    return (
      <div style={{ padding: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <div>
            <Title level={5} style={{ marginBottom: 4 }}>{selectedClass.label || selectedClass.shortName}</Title>
            <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 4 }} code>
              {selectedClass.shortName}
            </Paragraph>
            {selectedClass.description && (
              <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
                备注: {selectedClass.description}
              </Paragraph>
            )}
          </div>
          <Space>
            <Button icon={<EditOutlined />} size="small" onClick={() => handleEditClass(selectedClass)}>编辑</Button>
            <Popconfirm
              title="确认删除"
              description={`确定要删除 "${selectedClass.label || selectedClass.shortName}" 吗？该类的所有属性也会被删除，此操作不可恢复。`}
              onConfirm={() => handleDeleteClass(selectedClass)}
              okText="确认删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
            >
              <Button icon={<DeleteOutlined />} size="small" danger>删除</Button>
            </Popconfirm>
          </Space>
        </div>

        <Collapse
          defaultActiveKey={['props', 'rules']}
          size="small"
          style={{ marginTop: 12 }}
          items={[
            {
              key: 'props',
              label: `属性 (对象: ${objProps.length}, 数据: ${dataProps.length})`,
              children: (
                <div>
                  {objProps.length > 0 && (
                    <div style={{ marginBottom: 8 }}>
                      <Text strong>对象属性 ({objProps.length}):</Text>
                      <List
                        size="small"
                        dataSource={objProps}
                        renderItem={(p) => (
                          <List.Item
                            actions={[
                              <Button key="edit" type="link" size="small" icon={<EditOutlined />}
                                onClick={(e) => { e.stopPropagation(); handleEditProperty(p, 'object'); }}
                                title="编辑对象属性" />,
                              <Popconfirm key="delete" title="确认删除"
                                description={`确定要删除对象属性 "${p.label || p.shortName}" 吗？此操作不可恢复。`}
                                onConfirm={() => handleDeleteProperty(p, 'object')}
                                okText="确认删除" cancelText="取消" okButtonProps={{ danger: true }}>
                                <Button type="link" size="small" danger icon={<DeleteOutlined />}
                                  onClick={(e) => e.stopPropagation()}
                                  title="删除对象属性" />
                              </Popconfirm>,
                            ]}
                          >
                            <div>
                              <Text type="secondary" style={{ fontSize: 11 }}>label: </Text>
                              <Text>{p.label || p.shortName}</Text>
                              <br />
                              <Text code style={{ fontSize: 11 }}>{p.iri}</Text>
                              <Text type="secondary"> → {p.range?.split('#')?.pop() || p.range}</Text>
                              {p.description && (
                                <div><Text type="secondary" style={{ fontSize: 11 }}>备注: {p.description}</Text></div>
                              )}
                            </div>
                          </List.Item>
                        )}
                      />
                    </div>
                  )}
                  {dataProps.length > 0 && (
                    <div>
                      <Text strong>数据属性 ({dataProps.length}):</Text>
                      <List
                        size="small"
                        dataSource={dataProps}
                        renderItem={(p) => (
                          <List.Item
                            actions={[
                              <Button key="edit" type="link" size="small" icon={<EditOutlined />}
                                onClick={(e) => { e.stopPropagation(); handleEditProperty(p, 'data'); }}
                                title="编辑数据属性" />,
                              <Popconfirm key="delete" title="确认删除"
                                description={`确定要删除数据属性 "${p.label || p.shortName}" 吗？此操作不可恢复。`}
                                onConfirm={() => handleDeleteProperty(p, 'data')}
                                okText="确认删除" cancelText="取消" okButtonProps={{ danger: true }}>
                                <Button type="link" size="small" danger icon={<DeleteOutlined />}
                                  onClick={(e) => e.stopPropagation()}
                                  title="删除数据属性" />
                              </Popconfirm>,
                            ]}
                          >
                            <div>
                              <Text type="secondary" style={{ fontSize: 11 }}>label: </Text>
                              <Text>{p.label || p.shortName}</Text>
                              <br />
                              <Text code style={{ fontSize: 11 }}>{p.iri}</Text>
                              <Tag style={{ marginLeft: 8 }}>{p.datatype?.split('#')?.pop() || p.datatype || 'string'}</Tag>
                              {p.description && (
                                <div><Text type="secondary" style={{ fontSize: 11 }}>备注: {p.description}</Text></div>
                              )}
                            </div>
                          </List.Item>
                        )}
                      />
                    </div>
                  )}
                  {objProps.length === 0 && dataProps.length === 0 && (
                    <Text type="secondary">未找到属性</Text>
                  )}
                </div>
              ),
            },
            {
              key: 'rules',
              label: `关联规则 (${relatedRules.length})`,
              children: (
                <div>
                  {bodyRules.length > 0 && (
                    <div style={{ marginBottom: 8 }}>
                      <Text strong>作为输入 ({bodyRules.length}):</Text>
                      <List
                        size="small"
                        dataSource={bodyRules}
                        renderItem={(r) => (
                          <List.Item>
                            <List.Item.Meta
                              title={
                                <span>
                                  {r.name || '未命名'}
                                  <Tag
                                    color={r.status === 'active' ? 'green' : 'default'}
                                    style={{ marginLeft: 8 }}
                                  >
                                    {r.status}
                                  </Tag>
                                </span>
                              }
                              description={
                                <Text style={{ fontSize: 12 }} code ellipsis>
                                  {r.swrlRule?.substring(0, 80)}
                                  {(r.swrlRule?.length || 0) > 80 ? '...' : ''}
                                </Text>
                              }
                            />
                          </List.Item>
                        )}
                      />
                    </div>
                  )}
                  {headRules.length > 0 && (
                    <div>
                      <Text strong>作为输出 ({headRules.length}):</Text>
                      <List
                        size="small"
                        dataSource={headRules}
                        renderItem={(r) => (
                          <List.Item>
                            <List.Item.Meta
                              title={r.name || '未命名'}
                              description={
                                <Text style={{ fontSize: 12 }} code ellipsis>
                                  {r.swrlRule?.substring(0, 80)}
                                </Text>
                              }
                            />
                          </List.Item>
                        )}
                      />
                    </div>
                  )}
                  {relatedRules.length === 0 && <Text type="secondary">暂无关联规则</Text>}
                </div>
              ),
            },
          ]}
        />
      </div>
    );
  };

  return (
    <div style={{ padding: 24 }}>
      <Title level={4} style={{ marginBottom: 16 }}>
        对象类型
      </Title>

      <div style={{ display: 'flex', height: 'calc(100vh - 240px)' }}>
        {/* 左侧列表 */}
        <div style={{ width: leftPanelWidth, flexShrink: 0, overflow: 'auto', paddingRight: 8 }}>
          <Input
            prefix={<SearchOutlined />}
            placeholder="搜索对象类型..."
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            allowClear
            style={{ marginBottom: 12 }}
          />
          <div style={{ marginBottom: 8, display: 'flex', gap: 20, fontSize: 13, color: '#666', flexWrap: 'wrap' }}>
            <span>对象类型: <Text strong>{classes.length}</Text></span>
            <span>对象属性总计: <Text strong>{objectProperties.length}</Text></span>
            <span>数据属性总计: <Text strong>{dataProperties.length}</Text></span>
          </div>
          <Table
            size="small"
            dataSource={filteredClasses}
            columns={columns}
            rowKey="iri"
            pagination={{ pageSize, showSizeChanger: true, showTotal: (total: number) => `共 ${total} 条`, pageSizeOptions: ['10', '20', '50', '100'] }}
            onChange={(p) => setPageSize(p.pageSize ?? 15)}
            onRow={(record) => ({
              onClick: () => setSelectedClass(record),
              style: {
                cursor: 'pointer',
                background: selectedClass?.iri === record.iri ? '#e6f4ff' : undefined,
              },
            })}
            locale={{ emptyText: <Empty description="暂无对象类型" /> }}
          />
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

        {/* 右侧详情 */}
        <div
          style={{
            flex: 1, overflow: 'auto', paddingLeft: 8,
            minHeight: 400,
          }}
        >
          {renderDetail()}
        </div>
      </div>
      {/* 编辑类弹窗 */}
      <Modal title="编辑对象类型" open={!!editingClass} onOk={handleSaveClass} onCancel={() => setEditingClass(null)}
        confirmLoading={saving} okText="保存" cancelText="取消" width={700}>
        <Form form={editForm} layout="vertical">
          <Form.Item label="IRI (不可修改)">
            <Input value={editingClass?.iri || ''} disabled />
          </Form.Item>
          <Form.Item name="label" label="中文名称" rules={[{ required: true, message: '请输入中文名称' }]}>
            <Input placeholder="如：人员" />
          </Form.Item>
          <Form.Item name="description" label="备注描述">
            <Input.TextArea rows={2} placeholder="描述该对象类型的业务含义..." />
          </Form.Item>
        </Form>

        {/* 对象属性（关联的 LinkType） */}
        {editingClass && (() => {
          const { objProps } = getPropertiesForClass(editingClass.iri);
          return (
            <div style={{ marginTop: 16 }}>
              <Divider orientation="left" style={{ fontSize: 13, margin: '0 0 8px' }}>
                对象属性 ({objProps.length})
              </Divider>
              {objProps.length > 0 ? (
                <div style={{ maxHeight: 200, overflow: 'auto' }}>
                  {objProps.map((prop) => (
                    <div key={prop.iri} style={{ display: 'flex', gap: 8, marginBottom: 8, alignItems: 'flex-start' }}>
                      <div style={{ flex: 1 }}>
                        <Input
                          size="small"
                          defaultValue={prop.label || prop.shortName}
                          placeholder="属性标签"
                          onChange={(e) => {
                            // track changes to save via updateLinkType
                            (prop as any)._labelEdit = e.target.value;
                          }}
                        />
                        <div style={{ display: 'flex', gap: 4, marginTop: 2 }}>
                          <Text code style={{ fontSize: 11 }}>{prop.shortName}</Text>
                          <Text type="secondary" style={{ fontSize: 11 }}>
                            → {prop.range?.split('#')?.pop() || prop.range}
                          </Text>
                        </div>
                      </div>
                      <Input
                        size="small"
                        defaultValue={prop.description || ''}
                        placeholder="备注"
                        style={{ flex: 1 }}
                        onChange={(e) => {
                          (prop as any)._descEdit = e.target.value;
                        }}
                      />
                    </div>
                  ))}
                </div>
              ) : (
                <Text type="secondary" style={{ fontSize: 12 }}>暂无对象属性</Text>
              )}
            </div>
          );
        })()}

        {/* 数据属性（来自 propertiesJson） */}
        {editingClass && (() => {
          const { dataProps } = getPropertiesForClass(editingClass.iri);
          return (
            <div style={{ marginTop: 16 }}>
              <Divider orientation="left" style={{ fontSize: 13, margin: '0 0 8px' }}>
                数据属性 ({dataProps.length})
              </Divider>
              {dataProps.length > 0 ? (
                <div style={{ maxHeight: 200, overflow: 'auto' }}>
                  {dataProps.map((prop) => (
                    <div key={prop.iri} style={{ display: 'flex', gap: 8, marginBottom: 8, alignItems: 'flex-start' }}>
                      <div style={{ flex: 1 }}>
                        <Input
                          size="small"
                          defaultValue={prop.label || prop.shortName}
                          placeholder="属性标签"
                          onChange={(e) => {
                            (prop as any)._labelEdit = e.target.value;
                          }}
                        />
                        <Text code style={{ fontSize: 11 }}>{prop.shortName}</Text>
                      </div>
                      <Input
                        size="small"
                        defaultValue={prop.description || ''}
                        placeholder="备注"
                        style={{ flex: 1 }}
                        onChange={(e) => {
                          (prop as any)._descEdit = e.target.value;
                        }}
                      />
                      <Tag style={{ marginTop: 2 }}>
                        {prop.datatype?.split('#')?.pop() || prop.datatype || 'string'}
                      </Tag>
                    </div>
                  ))}
                </div>
              ) : (
                <Text type="secondary" style={{ fontSize: 12 }}>暂无数据属性</Text>
              )}
            </div>
          );
        })()}
      </Modal>

      {/* 单个属性编辑弹窗 */}
      <Modal
        title={`编辑${editingPropertyType === 'object' ? '对象' : '数据'}属性`}
        open={!!editingProperty}
        onOk={handleSaveProperty}
        onCancel={() => setEditingProperty(null)}
        confirmLoading={propertySaving}
        okText="保存"
        cancelText="取消"
        width={500}
      >
        <Form form={propertyEditForm} layout="vertical">
          <Form.Item label="IRI (不可修改)">
            <Input value={editingProperty?.iri || ''} disabled />
          </Form.Item>
          {editingPropertyType === 'object' && (
            <Form.Item label="关联目标类 (不可修改)">
              <Input value={editingProperty?.range || ''} disabled />
            </Form.Item>
          )}
          {editingPropertyType === 'data' && (
            <Form.Item label="数据类型 (不可修改)">
              <Input value={editingProperty?.datatype || 'string'} disabled />
            </Form.Item>
          )}
          <Form.Item name="label" label="中文名称" rules={[{ required: true, message: '请输入中文名称' }]}>
            <Input placeholder="如：姓名" />
          </Form.Item>
          <Form.Item name="description" label="备注描述">
            <Input.TextArea rows={2} placeholder="描述该属性的业务含义..." />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ObjectListView;
