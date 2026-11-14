/**
 * ClassHierarchyTree — 类层次树 (对标 Protégé)
 *
 * 功能: 以 Tree 展现类继承关系，支持搜索、选择同步、右键操作
 * 选中类 → 触发 PropertyEditor + 图谱同步高亮
 */
import {
ApartmentOutlined,SearchOutlined,
} from '@ant-design/icons';
import { Empty,Input,Space,Tag,Tree,Typography } from 'antd';
import React,{ useMemo } from 'react';

const { Text } = Typography;

interface ClassNode {
  iri: string;
  id: string;
  label: string;
  parentIri?: string;
  color?: string;
  [key: string]: any;
}

interface ClassHierarchyTreeProps {
  classes: ClassNode[];
  selectedClassId: string | null;
  onSelect: (cls: ClassNode) => void;
  searchText: string;
  onSearchChange: (text: string) => void;
}

const NODE_COLORS = [
  '#1677ff', '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16',
  '#52c41a', '#2f54eb', '#faad14', '#f5222d', '#1d39c4',
];

const ClassHierarchyTree: React.FC<ClassHierarchyTreeProps> = ({
  classes, selectedClassId, onSelect, searchText, onSearchChange,
}) => {
  // Build tree from flat class list with parentIri
  const treeData = useMemo(() => {
    const buildTree = (parentIri?: string): any[] => {
      return classes
        .filter(c => (c.parentIri || undefined) === parentIri)
        .map((c, i) => {
          const color = c.color || NODE_COLORS[i % NODE_COLORS.length];
          return {
            key: c.iri || c.id,
            title: (
              <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <span style={{
                  width: 8, height: 8, borderRadius: '50%', background: color,
                  display: 'inline-block', flexShrink: 0,
                }} />
                <Text ellipsis={{ tooltip: c.label }} style={{ fontSize: 12 }}>
                  {c.label}
                </Text>
                {c.parentIri && <Tag style={{ fontSize: 9, margin: 0 }}>⊂</Tag>}
              </span>
            ),
            icon: <ApartmentOutlined style={{ color }} />,
            children: buildTree(c.iri || c.id),
            isLeaf: !classes.some(child => child.parentIri === (c.iri || c.id)),
            _classData: c,
          };
        });
    };

    // Roots first
    const roots = buildTree(undefined);
    return roots;
  }, [classes]);

  // Filter by search
  const filteredTree = useMemo(() => {
    if (!searchText.trim()) return treeData;
    const lower = searchText.toLowerCase();
    const filter = (nodes: any[]): any[] => {
      return nodes.reduce((acc: any[], node: any) => {
        const matches = node._classData?.label?.toLowerCase().includes(lower);
        const filteredChildren = node.children ? filter(node.children) : [];
        if (matches || filteredChildren.length > 0) {
          acc.push({ ...node, children: filteredChildren });
        }
        return acc;
      }, []);
    };
    return filter(treeData);
  }, [treeData, searchText]);

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <div style={{ padding: '8px 12px', borderBottom: '1px solid #f0f0f0' }}>
        <Space>
          <ApartmentOutlined style={{ color: '#1677ff' }} />
          <Text strong style={{ fontSize: 13 }}>类层次树</Text>
          <Tag>{classes.length}</Tag>
        </Space>
        <Input
          size="small"
          placeholder="搜索类..."
          prefix={<SearchOutlined />}
          value={searchText}
          onChange={e => onSearchChange(e.target.value)}
          allowClear
          style={{ marginTop: 6 }}
        />
      </div>

      {/* Tree */}
      <div style={{ flex: 1, overflow: 'auto', padding: '4px 0' }}>
        {filteredTree.length > 0 ? (
          <Tree
            showIcon
            defaultExpandAll
            treeData={filteredTree}
            selectedKeys={selectedClassId ? [selectedClassId] : []}
            onSelect={(keys) => {
              if (keys.length > 0) {
                const findNode = (nodes: any[]): any => {
                  for (const n of nodes) {
                    if (n.key === keys[0]) return n._classData;
                    if (n.children) {
                      const found = findNode(n.children);
                      if (found) return found;
                    }
                  }
                  return null;
                };
                const cls = findNode(filteredTree);
                if (cls) onSelect(cls);
              }
            }}
            style={{ background: 'transparent' }}
          />
        ) : (
          <Empty description="暂无类数据" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ marginTop: 20 }} />
        )}
      </div>

      {/* Legend */}
      <div style={{ padding: '4px 12px', borderTop: '1px solid #f0f0f0', fontSize: 10, color: '#999' }}>
        <span>● 颜色区分类型</span> · <span>⊂ 子类</span> · <span>点击选择</span>
      </div>
    </div>
  );
};

export default ClassHierarchyTree;
