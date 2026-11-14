/**
 * LinkTypesGraphView — 本体关系视图
 *
 * 使用 @antv/g6 渲染本体类（节点）和对象属性关系（边）的交互式图谱。
 * 参考 Ontology/index.tsx Protégé 精修 → 本体可视化 → 本体视图展示。
 * 支持：添加类、添加关系、右键新建关系、拖拽节点、缩放、导出。
 */
import {
createLinkType,
createObjectType,
deleteLinkType,
deleteObjectType,
getLinkTypes,
getObjectTypes,
regenerateTtl,
updateLinkType,
updateObjectPosition,
updateObjectType,
} from '@/api/ontology';
import { syncRulesToTtl } from '@/api/rule';
import {
DeleteOutlined,
DownloadOutlined,
EditOutlined,
LinkOutlined,
MinusOutlined,
PlusOutlined,
RedoOutlined
} from '@ant-design/icons';
import {
Button,
Card,Descriptions,
Empty,
Form,Input,message,
Modal,
Popconfirm,
Select,
Space,
Spin,
Tag,
Tooltip,
Typography,
} from 'antd';
import React,{ useCallback,useEffect,useRef,useState } from 'react';

const { Title, Text } = Typography;

const NODE_COLORS = [
  '#1677ff', '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16',
  '#52c41a', '#2f54eb', '#faad14', '#f5222d', '#1d39c4',
  '#9254de', '#36cfc9', '#ff85c0', '#ffc53d', '#73d13d',
];

interface LinkTypesGraphViewProps {
  ontologyId: string;
  /** 已有的词汇表数据用于初始展示，避免首屏空白等待 */
  classes?: any[];
  objectProperties?: any[];
}

const LinkTypesGraphView: React.FC<LinkTypesGraphViewProps> = ({
  ontologyId,
  classes: initialClasses,
  objectProperties: initialProperties,
}) => {
  // ---- 数据状态 ----
  const [graphClasses, setGraphClasses] = useState<any[]>(initialClasses || []);
  const [graphProperties, setGraphProperties] = useState<any[]>(initialProperties || []);
  const [loading, setLoading] = useState(false);

  // ---- 图谱状态 ----
  const graphRef = useRef<HTMLDivElement>(null);
  const graphInstRef = useRef<any>(null);
  const [selectedNode, setSelectedNode] = useState<any>(null);
  const [selectedEdge, setSelectedEdge] = useState<any>(null);
  const [graphZoom, setGraphZoom] = useState(1);
  const [edgeCreating, setEdgeCreating] = useState(false);
  const [contextMenuNode, setContextMenuNode] = useState<any>(null);

  // ref 版本避免闭包问题
  const edgeCreatingRef = useRef(false);
  const contextMenuNodeRef = useRef<any>(null);
  useEffect(() => { edgeCreatingRef.current = edgeCreating; }, [edgeCreating]);
  useEffect(() => { contextMenuNodeRef.current = contextMenuNode; }, [contextMenuNode]);

  // ---- 模态框状态 ----
  const [addClassModalVisible, setAddClassModalVisible] = useState(false);
  const [editClassModalVisible, setEditClassModalVisible] = useState(false);
  const [addLinkModalVisible, setAddLinkModalVisible] = useState(false);
  const [editLinkModalVisible, setEditLinkModalVisible] = useState(false);
  const [createEdgeModalVisible, setCreateEdgeModalVisible] = useState(false);
  const [edgeDraft, setEdgeDraft] = useState<{ sourceId: string; targetId: string } | null>(null);
  const [saving, setSaving] = useState(false);

  // ---- 表单 ----
  const [classForm] = Form.useForm();
  const [linkForm] = Form.useForm();
  const [createEdgeForm] = Form.useForm();
  const [editEdgeForm] = Form.useForm();

  // ---- 数据加载 ----
  const loadData = useCallback(async () => {
    if (!ontologyId) return;
    setLoading(true);
    try {
      const [classes, properties] = await Promise.all([
        getObjectTypes(ontologyId),
        getLinkTypes(ontologyId),
      ]);
      setGraphClasses(classes || []);
      setGraphProperties(properties || []);
    } catch (e: any) {
      message.error('加载图谱数据失败: ' + (e?.message || '未知错误'));
    } finally {
      setLoading(false);
    }
  }, [ontologyId]);

  useEffect(() => {
    // 如果已有初始数据就直接用，否则加载
    if (initialClasses && initialClasses.length > 0) {
      setGraphClasses(initialClasses);
    }
    if (initialProperties && initialProperties.length > 0) {
      setGraphProperties(initialProperties);
    }
  }, [initialClasses, initialProperties]);

  // ---- 图谱渲染 ----
  const renderGraph = useCallback(async () => {
    if (!graphRef.current) return;

    // 清理旧图谱
    if (graphInstRef.current) {
      const oldGraph = graphInstRef.current;
      if (oldGraph.__contextMenuClickHandler) {
        document.removeEventListener('click', oldGraph.__contextMenuClickHandler, true);
      }
      oldGraph.destroy();
      graphInstRef.current = null;
    }

    if (graphClasses.length === 0 && graphProperties.length === 0) return;

    try {
      const { Graph: G6Graph } = await import('@antv/g6');

      const classColorMap: Record<string, string> = {};
      graphClasses.forEach((cls: any, i: number) => {
        classColorMap[cls.iri || cls.id] = NODE_COLORS[i % NODE_COLORS.length];
      });

      // 构建节点
      const nodes: any[] = graphClasses.map((cls: any, i: number) => {
        const label = cls.label || cls.iri?.split('/').pop() || `Class${i}`;
        const color = NODE_COLORS[i % NODE_COLORS.length];
        return {
          id: cls.iri || cls.id || `class-${i}`,
          label,
          data: { ...cls, colorIndex: i, color },
          style: {
            fill: '#fff',
            stroke: color,
            lineWidth: 1.5,
            shadowColor: 'rgba(0,0,0,0.08)',
            shadowBlur: 6,
            labelText: label,
            labelFill: '#1a1a1a',
            labelFontSize: 12,
            labelFontWeight: 500,
            labelPlacement: 'center' as const,
            size: 36,
          },
          type: 'circle',
        };
      });

      // IRI 本地名提取：从完整URI / 前缀名 / 尖括号形式中提取纯本地名
      const extractLocalName = (iri: string | undefined | null): string => {
        if (!iri) return '';
        let cleaned = iri.replace(/^<\s*|\s*>$/g, '').trim();
        cleaned = cleaned.replace(/^[^:]*:/, '');
        const hashIdx = cleaned.lastIndexOf('#');
        const slashIdx = cleaned.lastIndexOf('/');
        const startIdx = Math.max(hashIdx, slashIdx);
        return startIdx >= 0 ? cleaned.substring(startIdx + 1) : cleaned;
      };

      const iriMatches = (iriA: string | undefined | null, iriB: string | undefined | null): boolean => {
        if (!iriA || !iriB) return false;
        if (iriA === iriB) return true;
        const localA = extractLocalName(iriA);
        const localB = extractLocalName(iriB);
        return localA !== '' && localA === localB;
      };

      // 构建边
      const edges: any[] = [];
      graphProperties.forEach((prop: any, i: number) => {
        const sourceLabel = prop.sourceType || prop.domain;
        const targetLabel = prop.targetType || prop.range;
        if (!sourceLabel || !targetLabel) return;

        const sourceNode = graphClasses.find((c: any) =>
          c.label === sourceLabel || iriMatches(c.iri, sourceLabel) || c.id === sourceLabel);
        const targetNode = graphClasses.find((c: any) =>
          c.label === targetLabel || iriMatches(c.iri, targetLabel) || c.id === targetLabel);
        if (!sourceNode || !targetNode) return;

        const edgeColor = classColorMap[sourceNode.iri || sourceNode.id] || '#666';
        edges.push({
          id: `edge-${i}-${prop.id || i}`,
          source: sourceNode.iri || sourceNode.id,
          target: targetNode.iri || targetNode.id,
          label: prop.label || prop.iri?.split('/').pop() || '',
          data: { ...prop },
          style: {
            stroke: edgeColor,
            lineWidth: 1.5,
            opacity: 0.7,
            endArrow: true,
            endArrowFill: edgeColor,
            endArrowSize: 6,
            labelText: prop.label || prop.iri?.split('/').pop() || '',
            labelFill: '#555',
            labelFontSize: 10,
            labelAutoRotate: true,
            labelBackground: true,
            labelBackgroundFill: '#fff',
            labelBackgroundOpacity: 0.95,
            labelPadding: [2, 6, 2, 6],
            labelBackgroundRadius: 3,
          },
        });
      });

      const container = graphRef.current;
      if (!container) return;

      const scrollContainer = container.parentElement;
      const containerWidth = scrollContainer?.offsetWidth || 800;
      const containerHeight = scrollContainer?.offsetHeight || 650;
      const estimatedSize = Math.max(600, Math.sqrt(graphClasses.length) * 70 * 2);
      const initialWidth = Math.max(containerWidth, estimatedSize);
      const initialHeight = Math.max(containerHeight, estimatedSize);

      container.style.visibility = 'hidden';

      const graph: any = new G6Graph({
        container,
        width: initialWidth,
        height: initialHeight,
        autoFit: 'view',
        animation: false,
        data: { nodes, edges },
        layout: {
          type: 'dagre',
          rankdir: 'TB',
          nodesep: 60,
          ranksep: 100,
          preventOverlap: true,
          animate: false,
        },
        node: {
          style: {
            labelText: (d: any) => d.label || '',
            labelPlacement: 'center',
            cursor: 'move',
          },
          state: {
            selected: { lineWidth: 3, shadowBlur: 12, shadowColor: 'rgba(22,119,255,0.4)' },
            hover: { lineWidth: 3, shadowBlur: 12, shadowColor: 'rgba(22,119,255,0.3)' },
          },
        },
        edge: {
          type: 'quadratic',
          style: { endArrow: true, cursor: 'pointer' },
          state: {
            selected: { lineWidth: 3, stroke: '#1677ff' },
            hover: { lineWidth: 2, stroke: '#1677ff' },
          },
        },
        behaviors: [
          'drag-canvas',
          { type: 'zoom-canvas', sensitivity: 2, trigger: 'wheel' },
          { type: 'drag-element', key: 'drag-element', enable: (evt: any) => evt.targetType === 'node' },
          'click-select',
          {
            type: 'create-edge',
            key: 'create-edge',
            trigger: 'click',
            style: { stroke: '#1677ff', lineWidth: 2, lineDash: [6, 4], endArrow: true },
            enable: (evt: any) => {
              if (!edgeCreatingRef.current || !contextMenuNodeRef.current) return false;
              if (evt.targetType !== 'node') return false;
              return true;
            },
            onCreate: (edgeData: any) => {
              if (!contextMenuNodeRef.current) return undefined;
              if (edgeData.source !== contextMenuNodeRef.current.id) return undefined;
              return edgeData;
            },
            onFinish: (edgeData: any) => {
              const sourceId = edgeData.source;
              const targetId = edgeData.target;
              if (contextMenuNodeRef.current?.id) {
                graph.setElementState(contextMenuNodeRef.current.id, 'selected', false);
              }
              setEdgeDraft({ sourceId, targetId });
              setCreateEdgeModalVisible(true);
              setEdgeCreating(false);
              setContextMenuNode(null);

              const sourceNode = graphClasses.find((c: any) => c.iri === sourceId || c.id === sourceId);
              const targetNode = graphClasses.find((c: any) => c.iri === targetId || c.id === targetId);
              const sLabel = sourceNode?.label || '';
              const tLabel = targetNode?.label || '';
              const cleanLabel = (l: string) => (l || '').replace(/[^a-zA-Z0-9一-鿿]/g, '');

              createEdgeForm.setFieldsValue({
                label: tLabel ? `has${tLabel}` : 'hasRelation',
                iri: `http://ontologybuilder.local/ontology/${ontologyId}#${cleanLabel(sLabel)}To${cleanLabel(tLabel)}`,
                description: sLabel && tLabel ? `${sLabel} 到 ${tLabel} 的关系` : '',
              });

              setTimeout(() => {
                if (edgeData.id) graph.deleteData(edgeData.id);
              }, 100);
            },
          },
        ],
        plugins: [
          {
            type: 'tooltip',
            key: 'tooltip',
            enterable: true,
            getContent: (evt: any, items: any[]) => {
              const { targetType } = evt;
              if (!items || items.length === 0) return '';
              const item = items[0];

              if (targetType === 'edge') {
                const d = item.data || {};
                const result: string[] = [];
                if (d.label) result.push(`<b>${d.label}</b>`);
                if (d.sourceType && d.targetType) result.push(`<span style="color:#888">${d.sourceType} → ${d.targetType}</span>`);
                if (d.iri) result.push(`<span style="color:#999">IRI: ${d.iri}</span>`);
                if (d.description) result.push(d.description);
                if (result.length === 0) result.push('<span style="color:#888">对象属性关系</span>');
                return `<div style="padding:8px 12px;font-size:12px;line-height:1.8">${result.join('<br/>')}</div>`;
              }

              if (targetType === 'node') {
                const d = item.data || {};
                const result: string[] = [];
                const label = d.label || item.label;
                if (label) result.push(`<b>${label}</b>`);
                if (d.iri) result.push(`<span style="color:#999">IRI: ${d.iri}</span>`);
                if (d.description) result.push(d.description);
                if (result.length === 0) result.push('<span style="color:#888">对象类型</span>');
                return `<div style="padding:8px 12px;font-size:12px;line-height:1.8">${result.join('<br/>')}</div>`;
              }

              return '';
            },
          },
          {
            type: 'contextmenu',
            key: 'contextmenu',
            trigger: 'contextmenu',
            offset: [0, 0],
            getContent: (evt: any) => {
              const { targetType } = evt;
              if (targetType !== 'node') return '';
              const nodeId = evt.target?.id || '';
              return `<ul class="g6-contextmenu-ul" style="list-style:none;padding:4px 0;margin:0;min-width:100px;">
                <li class="g6-contextmenu-li" value="add-edge" data-node-id="${nodeId}" style="padding:8px 12px;cursor:pointer;font-size:13px;">+ 新建关系</li>
              </ul>`;
            },
          },
        ],
      });

      // 右键菜单点击处理
      const handleContextMenuClick = (evt: MouseEvent) => {
        const target = evt.target as HTMLElement;
        const value = target?.getAttribute?.('value');
        const nodeId = target?.getAttribute?.('data-node-id');
        if (value === 'add-edge' && nodeId) {
          evt.preventDefault();
          evt.stopPropagation();
          edgeCreatingRef.current = true;
          contextMenuNodeRef.current = { id: nodeId };
          setEdgeCreating(true);
          setContextMenuNode({ id: nodeId });
          try { graph.setElementState(nodeId, 'selected', true); } catch {}
          try { graph.emit('node:click', { targetType: 'node', target: { id: nodeId } }); } catch {}
        }
      };
      document.addEventListener('click', handleContextMenuClick, true);
      (graph as any).__contextMenuClickHandler = handleContextMenuClick;

      // 画布点击：取消连线模式、取消选中
      graph.on('canvas:click', () => {
        if (edgeCreatingRef.current) {
          if (contextMenuNodeRef.current?.id) {
            graph.setElementState(contextMenuNodeRef.current.id, 'selected', false);
          }
          setEdgeCreating(false);
          setContextMenuNode(null);
        }
        setSelectedNode(null);
        setSelectedEdge(null);
      });

      graph.on('afterlayout', () => {
        try {
          if (scrollContainer) {
            graph.setSize(scrollContainer.offsetWidth, scrollContainer.offsetHeight);
          }
        } catch { /* ignore */ }
      });

      await graph.render();
      container.style.visibility = 'visible';

      graph.on('wheel', (evt: any) => {
        setGraphZoom(graph.getZoom());
      });

      // 单击节点
      graph.on('node:click', (evt: any) => {
        const nodeId = evt.target?.id || evt.itemId;
        const nodeData = graph.getNodeData(nodeId);
        if (nodeData?.data) {
          setSelectedNode(nodeData.data);
          setSelectedEdge(null);
        }
      });

      // 双击节点 → 编辑
      graph.on('node:dblclick', (evt: any) => {
        const nodeId = evt.target?.id || evt.itemId;
        const nodeData = graph.getNodeData(nodeId);
        if (nodeData?.data) {
          setSelectedNode(nodeData.data);
          setSelectedEdge(null);
          classForm.setFieldsValue({
            iri: nodeData.data.iri || '',
            label: nodeData.data.label || '',
            parentIri: nodeData.data.parentIri || '',
            description: nodeData.data.description || '',
          });
          setEditClassModalVisible(true);
        }
      });

      // 双击边 → 编辑
      graph.on('edge:dblclick', (evt: any) => {
        const edgeId = evt.target?.id || evt.itemId;
        if (!edgeId) return;
        const edgeData = graph.getEdgeData(edgeId);
        if (edgeData?.data && edgeData.data.id) {
          setSelectedEdge(edgeData.data);
          setSelectedNode(null);
          editEdgeForm.setFieldsValue({
            label: edgeData.data.label || '',
            iri: edgeData.data.iri || '',
            sourceType: edgeData.data.sourceType || edgeData.data.domain || '',
            targetType: edgeData.data.targetType || edgeData.data.range || '',
            description: edgeData.data.description || '',
          });
          setEditLinkModalVisible(true);
        }
      });

      // 节点拖拽结束 → 保存位置
      graph.on('node:dragend', async (evt: any) => {
        const nodeId = evt.target?.id || evt.itemId;
        const nodeData = graph.getNodeData(nodeId);
        if (nodeData?.data?.id && ontologyId) {
          const pos = { x: nodeData.data?.x || 0, y: nodeData.data?.y || 0 };
          try {
            await updateObjectPosition(ontologyId, nodeData.data.id, pos);
          } catch { /* ignore */ }
        }
      });

      graphInstRef.current = graph;
    } catch (e) {
      console.error('G6 初始化失败:', e);
    }
  }, [graphClasses, graphProperties, ontologyId, classForm, createEdgeForm, editEdgeForm]);

  // 当数据加载后渲染图谱
  useEffect(() => {
    if (graphClasses.length >= 0 || graphProperties.length >= 0) {
      renderGraph();
    }
    return () => {
      if (graphInstRef.current) {
        if (graphInstRef.current.__contextMenuClickHandler) {
          document.removeEventListener('click', graphInstRef.current.__contextMenuClickHandler, true);
        }
        graphInstRef.current.destroy();
        graphInstRef.current = null;
      }
    };
  }, [graphClasses, graphProperties, renderGraph]);

  // 选中变化时调整画布大小
  useEffect(() => {
    const timer = setTimeout(() => {
      const graph = graphInstRef.current;
      const container = graphRef.current;
      if (graph && container) {
        const scrollContainer = container.parentElement;
        if (scrollContainer) {
          graph.setSize(scrollContainer.offsetWidth, scrollContainer.offsetHeight);
        }
      }
    }, 100);
    return () => clearTimeout(timer);
  }, [selectedNode, selectedEdge]);

  // ---- 缩放控制 ----
  const handleZoomIn = () => {
    const graph = graphInstRef.current;
    if (!graph) return;
    graph.zoomTo(Math.min(graph.getZoom() * 1.2, 3));
    setGraphZoom(graph.getZoom());
  };
  const handleZoomOut = () => {
    const graph = graphInstRef.current;
    if (!graph) return;
    graph.zoomTo(Math.max(graph.getZoom() / 1.2, 0.1));
    setGraphZoom(graph.getZoom());
  };
  const handleZoomReset = () => {
    const graph = graphInstRef.current;
    if (!graph) return;
    graph.zoomTo(1);
    graph.fitView(40);
    setGraphZoom(graph.getZoom());
  };

  const refreshAfterChange = async () => {
    try {
      await regenerateTtl(ontologyId);
      await syncRulesToTtl(ontologyId);
    } catch { /* ignore */ }
    await loadData();
  };

  // ---- 添加类 ----
  const handleAddClass = () => {
    classForm.resetFields();
    classForm.setFieldsValue({ iri: 'http://ontologybuilder.local/ontology#' });
    setAddClassModalVisible(true);
  };
  const handleSaveAddClass = async () => {
    try {
      const values = await classForm.validateFields();
      setSaving(true);
      await createObjectType(ontologyId, values);
      message.success('类创建成功');
      setAddClassModalVisible(false);
      await refreshAfterChange();
    } catch (e: any) {
      if (e.errorFields) return;
      message.error('创建失败: ' + (e?.message || '未知错误'));
    } finally {
      setSaving(false);
    }
  };

  // ---- 编辑类 ----
  const handleSaveEditClass = async () => {
    if (!selectedNode?.id) return;
    try {
      const values = await classForm.validateFields();
      setSaving(true);
      await updateObjectType(ontologyId, selectedNode.id, values);
      message.success('类更新成功');
      setEditClassModalVisible(false);
      setSelectedNode(null);
      await refreshAfterChange();
    } catch (e: any) {
      if (e.errorFields) return;
      message.error('更新失败: ' + (e?.message || '未知错误'));
    } finally {
      setSaving(false);
    }
  };
  const handleDeleteClass = async () => {
    if (!selectedNode?.id) return;
    try {
      await deleteObjectType(ontologyId, selectedNode.id);
      message.success('类已删除');
      setSelectedNode(null);
      await refreshAfterChange();
    } catch (e: any) {
      message.error('删除失败: ' + (e?.message || '未知错误'));
    }
  };

  // ---- 添加关系（手动弹窗） ----
  const handleAddLink = () => {
    linkForm.resetFields();
    linkForm.setFieldsValue({ iri: `http://ontologybuilder.local/ontology/${ontologyId}#` });
    setAddLinkModalVisible(true);
  };
  const handleSaveAddLink = async () => {
    try {
      const values = await linkForm.validateFields();
      setSaving(true);
      await createLinkType(ontologyId, values);
      message.success('关系创建成功');
      setAddLinkModalVisible(false);
      await refreshAfterChange();
    } catch (e: any) {
      if (e.errorFields) return;
      message.error('创建失败: ' + (e?.message || '未知错误'));
    } finally {
      setSaving(false);
    }
  };

  // ---- 从画布连线创建关系 ----
  const handleSaveCreateEdge = async () => {
    if (!edgeDraft) return;
    try {
      const values = await createEdgeForm.validateFields();
      setSaving(true);
      await createLinkType(ontologyId, {
        ...values,
        sourceType: edgeDraft.sourceId,
        targetType: edgeDraft.targetId,
      });
      message.success('关系创建成功');
      setCreateEdgeModalVisible(false);
      setEdgeDraft(null);
      await refreshAfterChange();
    } catch (e: any) {
      if (e.errorFields) return;
      message.error('创建失败: ' + (e?.message || '未知错误'));
    } finally {
      setSaving(false);
    }
  };

  // ---- 编辑关系（双击边） ----
  const handleSaveEditEdge = async () => {
    if (!selectedEdge?.id) return;
    try {
      const values = await editEdgeForm.validateFields();
      setSaving(true);
      await updateLinkType(ontologyId, selectedEdge.id, values);
      message.success('关系更新成功');
      setEditLinkModalVisible(false);
      setSelectedEdge(null);
      await refreshAfterChange();
    } catch (e: any) {
      if (e.errorFields) return;
      message.error('更新失败: ' + (e?.message || '未知错误'));
    } finally {
      setSaving(false);
    }
  };
  const handleDeleteEdge = async () => {
    if (!selectedEdge?.id) return;
    try {
      await deleteLinkType(ontologyId, selectedEdge.id);
      message.success('关系已删除');
      setSelectedEdge(null);
      await refreshAfterChange();
    } catch (e: any) {
      message.error('删除失败: ' + (e?.message || '未知错误'));
    }
  };

  // ---- 导出图谱为 JPEG ----
  const handleExportGraph = useCallback(async () => {
    const graph = graphInstRef.current;
    const container = graphRef.current;
    if (!graph || !container) {
      message.warning('请先加载图谱');
      return;
    }
    try {
      message.loading({ content: '正在导出本体关系视图...', key: 'exportGraph' });
      try { graph.fitView(40); } catch {}
      await new Promise(resolve => setTimeout(resolve, 100));

      const canvas = container.querySelector('canvas');
      if (!canvas) {
        message.error({ content: '未找到画布元素', key: 'exportGraph' });
        return;
      }
      const dataURL = canvas.toDataURL('image/jpeg', 0.95);
      const link = document.createElement('a');
      link.download = `ontology-relation-view-${ontologyId}.jpg`;
      link.href = dataURL;
      link.click();
      message.success({ content: '导出成功', key: 'exportGraph' });
    } catch (e: any) {
      message.error({ content: '导出失败: ' + (e?.message || '未知错误'), key: 'exportGraph' });
    }
  }, [ontologyId]);

  // ---- 可选择的类列表（用于创建关系表单） ----
  const classOptions = graphClasses.map((c: any) => ({
    label: c.label || c.iri?.split('/').pop() || c.iri,
    value: c.iri || c.label,
  }));

  // ---- 渲染 ----
  return (
    <div style={{ padding: '0 24px 24px' }}>
      {/* 标题栏 */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12, flexWrap: 'wrap', gap: 8 }}>
        <Title level={4} style={{ margin: 0 }}>本体关系视图</Title>
        <Space wrap>
          <Button icon={<RedoOutlined />} onClick={loadData} loading={loading}>刷新数据</Button>
          <Button icon={<PlusOutlined />} type="primary" onClick={handleAddClass}>添加类</Button>
          <Button icon={<PlusOutlined />} onClick={handleAddLink}>添加关系</Button>
          <Button icon={<DownloadOutlined />} onClick={handleExportGraph} disabled={graphClasses.length === 0}>导出视图</Button>
        </Space>
      </div>

      <Spin spinning={loading}>
        {graphClasses.length === 0 && graphProperties.length === 0 ? (
          <Empty description="暂无数据，请先添加类和关系" image={Empty.PRESENTED_IMAGE_SIMPLE}>
            <Space>
              <Button type="primary" icon={<PlusOutlined />} onClick={handleAddClass}>添加类</Button>
              <Button icon={<PlusOutlined />} onClick={handleAddLink}>添加关系</Button>
            </Space>
          </Empty>
        ) : (
          <div style={{ display: 'flex', gap: 16 }}>
            {/* 图谱画布区域 */}
            <div style={{ flex: selectedNode || selectedEdge ? '0 0 66%' : 1, minWidth: 0 }}>
              <div style={{ position: 'relative' }}>
                {/* 图例 */}
                <div style={{
                  position: 'absolute', top: 8, right: 8, zIndex: 10,
                  background: 'rgba(255,255,255,0.92)', borderRadius: 6,
                  padding: '6px 12px', fontSize: 12, display: 'flex', gap: 14, alignItems: 'center',
                  boxShadow: '0 1px 4px rgba(0,0,0,0.1)',
                }}>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    <svg width="14" height="14"><circle cx="7" cy="7" r="6" fill="#fff" stroke="#1677ff" strokeWidth="2" /></svg>
                    <span style={{ color: '#555' }}>类</span>
                  </span>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    <svg width="22" height="14"><line x1="2" y1="7" x2="20" y2="7" stroke="#666" strokeWidth="1.5" /><polygon points="18,4 22,7 18,10" fill="#666" /></svg>
                    <span style={{ color: '#555' }}>对象关系</span>
                  </span>
                </div>
                {/* 缩放控制 */}
                <div style={{
                  position: 'absolute', bottom: 12, right: 12, zIndex: 10,
                  background: 'rgba(255,255,255,0.92)', borderRadius: 6,
                  padding: '4px 8px', display: 'flex', gap: 4, alignItems: 'center',
                  boxShadow: '0 1px 4px rgba(0,0,0,0.1)',
                }}>
                  <Button size="small" icon={<PlusOutlined />} onClick={handleZoomIn} title="放大" />
                  <Text style={{ fontSize: 12, minWidth: 40, textAlign: 'center' }}>{Math.round(graphZoom * 100)}%</Text>
                  <Button size="small" icon={<MinusOutlined />} onClick={handleZoomOut} title="缩小" />
                  <Button size="small" onClick={handleZoomReset}>重置</Button>
                </div>
                {/* 连线模式提示条 */}
                {edgeCreating && contextMenuNode && (
                  <div style={{
                    position: 'absolute', top: 8, left: '50%', transform: 'translateX(-50%)',
                    zIndex: 100, background: '#1677ff', color: '#fff',
                    padding: '6px 16px', borderRadius: 4, fontSize: 13,
                    display: 'flex', alignItems: 'center', gap: 8,
                    boxShadow: '0 2px 6px rgba(22,119,255,0.3)',
                  }}>
                    <LinkOutlined />
                    <span>连线模式：请点击目标节点以创建关系</span>
                    <Button size="small" type="text" style={{ color: '#fff', marginLeft: 8 }}
                      onClick={() => {
                        if (graphInstRef.current && contextMenuNode?.id) {
                          try { graphInstRef.current.setElementState(contextMenuNode.id, 'selected', false); } catch {}
                        }
                        setEdgeCreating(false);
                        setContextMenuNode(null);
                      }}>
                      取消
                    </Button>
                  </div>
                )}
                {/* 画布容器 */}
                <div style={{
                  width: '100%', height: 600, border: '1px solid #f0f0f0',
                  borderRadius: 8, background: '#fafafa', overflow: 'hidden',
                }}>
                  <div ref={graphRef} style={{ width: '100%', height: '100%' }} />
                </div>
              </div>
              {/* 提示 */}
              <div style={{ marginTop: 8 }}>
                <Text type="secondary" style={{ fontSize: 11 }}>
                  💡 右键节点新建关系 | 双击节点/边进行编辑 | 拖拽节点调整位置 | 滚轮缩放 | 空白处拖拽平移
                </Text>
              </div>
            </div>

            {/* 详情面板 */}
            {(selectedNode || selectedEdge) && (
              <div style={{ flex: '0 0 320px', maxHeight: 600, overflow: 'auto' }}>
                {selectedNode && (
                  <Card size="small"
                    title={
                      <Space>
                        <Tag color={selectedNode.color || NODE_COLORS[(selectedNode.colorIndex || 0) % NODE_COLORS.length]}>
                          {selectedNode.label || '未命名类'}
                        </Tag>
                        <span style={{ fontSize: 12, color: '#888' }}>对象类型</span>
                      </Space>
                    }
                    extra={
                      <Space>
                        <Button size="small" type="link" icon={<EditOutlined />}
                          onClick={() => {
                            classForm.setFieldsValue({
                              iri: selectedNode.iri || '',
                              label: selectedNode.label || '',
                              parentIri: selectedNode.parentIri || '',
                              description: selectedNode.description || '',
                            });
                            setEditClassModalVisible(true);
                          }}>编辑</Button>
                        <Popconfirm title="确认删除该类？关联的关系也将被删除" onConfirm={handleDeleteClass} okText="删除" cancelText="取消">
                          <Button size="small" type="link" danger icon={<DeleteOutlined />}>删除</Button>
                        </Popconfirm>
                      </Space>
                    }>
                    <Descriptions size="small" column={1}>
                      <Descriptions.Item label="IRI">
                        <Tooltip title={selectedNode.iri}>
                          <Text copyable style={{ fontSize: 12, maxWidth: 200, display: 'inline-block', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                            {selectedNode.iri}
                          </Text>
                        </Tooltip>
                      </Descriptions.Item>
                      <Descriptions.Item label="名称">{selectedNode.label || '-'}</Descriptions.Item>
                      {selectedNode.parentIri && (
                        <Descriptions.Item label="父类">
                          <Tooltip title={selectedNode.parentIri}>
                            <Text style={{ fontSize: 12 }}>{selectedNode.parentIri?.split('/').pop()}</Text>
                          </Tooltip>
                        </Descriptions.Item>
                      )}
                      <Descriptions.Item label="描述">{selectedNode.description || '-'}</Descriptions.Item>
                    </Descriptions>
                    {/* 关联的关系 */}
                    {(() => {
                      const relatedLinks = graphProperties.filter((p: any) =>
                        p.sourceType === selectedNode.iri || p.targetType === selectedNode.iri ||
                        p.domain === selectedNode.iri || p.range === selectedNode.iri);
                      if (relatedLinks.length === 0) return null;
                      return (
                        <div style={{ marginTop: 8 }}>
                          <Text type="secondary" style={{ fontSize: 12 }}>关联关系 ({relatedLinks.length})</Text>
                          <div style={{ marginTop: 4, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                            {relatedLinks.map((l: any) => (
                              <Tag key={l.id || l.iri} color="purple" style={{ cursor: 'pointer', marginBottom: 4 }}
                                onClick={() => {
                                  setSelectedEdge(l);
                                  setSelectedNode(null);
                                  editEdgeForm.setFieldsValue({
                                    label: l.label || '', iri: l.iri || '',
                                    sourceType: l.sourceType || l.domain || '',
                                    targetType: l.targetType || l.range || '',
                                    description: l.description || '',
                                  });
                                }}>
                                {l.label || l.iri?.split('/').pop()}
                              </Tag>
                            ))}
                          </div>
                        </div>
                      );
                    })()}
                  </Card>
                )}
                {selectedEdge && (
                  <Card size="small"
                    title={
                      <Space>
                        <LinkOutlined />
                        <span>{selectedEdge.label || '未命名关系'}</span>
                        <span style={{ fontSize: 12, color: '#888' }}>对象属性</span>
                      </Space>
                    }
                    extra={
                      <Space>
                        <Button size="small" type="link" icon={<EditOutlined />}
                          onClick={() => {
                            editEdgeForm.setFieldsValue({
                              label: selectedEdge.label || '', iri: selectedEdge.iri || '',
                              sourceType: selectedEdge.sourceType || selectedEdge.domain || '',
                              targetType: selectedEdge.targetType || selectedEdge.range || '',
                              description: selectedEdge.description || '',
                            });
                            setEditLinkModalVisible(true);
                          }}>编辑</Button>
                        <Popconfirm title="确认删除该关系？" onConfirm={handleDeleteEdge} okText="删除" cancelText="取消">
                          <Button size="small" type="link" danger icon={<DeleteOutlined />}>删除</Button>
                        </Popconfirm>
                      </Space>
                    }>
                    <Descriptions size="small" column={1}>
                      <Descriptions.Item label="IRI">
                        <Tooltip title={selectedEdge.iri}>
                          <Text copyable style={{ fontSize: 12, maxWidth: 200, display: 'inline-block', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                            {selectedEdge.iri}
                          </Text>
                        </Tooltip>
                      </Descriptions.Item>
                      <Descriptions.Item label="名称">{selectedEdge.label || '-'}</Descriptions.Item>
                      <Descriptions.Item label="定义域 (Domain)">
                        <Tooltip title={selectedEdge.sourceType || selectedEdge.domain}>
                          {selectedEdge.sourceType || selectedEdge.domain || '-'}
                        </Tooltip>
                      </Descriptions.Item>
                      <Descriptions.Item label="值域 (Range)">
                        <Tooltip title={selectedEdge.targetType || selectedEdge.range}>
                          {selectedEdge.targetType || selectedEdge.range || '-'}
                        </Tooltip>
                      </Descriptions.Item>
                      <Descriptions.Item label="描述">{selectedEdge.description || '-'}</Descriptions.Item>
                    </Descriptions>
                  </Card>
                )}
              </div>
            )}
          </div>
        )}
      </Spin>

      {/* ---- 模态框 ---- */}

      {/* 添加类 */}
      <Modal title="添加类（对象类型）" open={addClassModalVisible} onOk={handleSaveAddClass}
        onCancel={() => setAddClassModalVisible(false)} confirmLoading={saving} okText="创建" cancelText="取消">
        <Form form={classForm} layout="vertical">
          <Form.Item name="iri" label="类 IRI" rules={[{ required: true, message: '请输入 IRI' }]}>
            <Input placeholder="http://ontologybuilder.local/ontology#ClassName" />
          </Form.Item>
          <Form.Item name="label" label="中文标签" rules={[{ required: true, message: '请输入中文标签' }]}>
            <Input placeholder="如：订单" />
          </Form.Item>
          <Form.Item name="parentIri" label="父类 IRI（可选）">
            <Select placeholder="选择父类（可选）" allowClear showSearch optionFilterProp="label"
              options={classOptions} />
          </Form.Item>
          <Form.Item name="description" label="备注描述">
            <Input.TextArea rows={2} placeholder="描述该类的业务含义..." />
          </Form.Item>
        </Form>
      </Modal>

      {/* 编辑类 */}
      <Modal title="编辑类" open={editClassModalVisible} onOk={handleSaveEditClass}
        onCancel={() => setEditClassModalVisible(false)} confirmLoading={saving} okText="保存" cancelText="取消">
        <Form form={classForm} layout="vertical">
          <Form.Item name="iri" label="类 IRI">
            <Input disabled />
          </Form.Item>
          <Form.Item name="label" label="中文标签" rules={[{ required: true, message: '请输入中文标签' }]}>
            <Input placeholder="如：订单" />
          </Form.Item>
          <Form.Item name="parentIri" label="父类 IRI（可选）">
            <Select placeholder="选择父类（可选）" allowClear showSearch optionFilterProp="label"
              options={classOptions.filter(o => o.value !== selectedNode?.iri)} />
          </Form.Item>
          <Form.Item name="description" label="备注描述">
            <Input.TextArea rows={2} placeholder="描述该类的业务含义..." />
          </Form.Item>
        </Form>
      </Modal>

      {/* 添加关系（手动弹窗） */}
      <Modal title="添加关系（对象属性）" open={addLinkModalVisible} onOk={handleSaveAddLink}
        onCancel={() => setAddLinkModalVisible(false)} confirmLoading={saving} okText="创建" cancelText="取消"
        width={560}>
        <Form form={linkForm} layout="vertical">
          <Form.Item name="iri" label="关系 IRI" rules={[{ required: true, message: '请输入 IRI' }]}>
            <Input placeholder="http://ontologybuilder.local/ontology#hasRelation" />
          </Form.Item>
          <Form.Item name="label" label="中文标签" rules={[{ required: true, message: '请输入中文标签' }]}>
            <Input placeholder="如：拥有订单" />
          </Form.Item>
          <Form.Item name="sourceType" label="定义域 Domain（源类）" rules={[{ required: true, message: '请选择源类' }]}>
            <Select placeholder="选择源类" showSearch optionFilterProp="label" options={classOptions} />
          </Form.Item>
          <Form.Item name="targetType" label="值域 Range（目标类）" rules={[{ required: true, message: '请选择目标类' }]}>
            <Select placeholder="选择目标类" showSearch optionFilterProp="label" options={classOptions} />
          </Form.Item>
          <Form.Item name="description" label="备注描述">
            <Input.TextArea rows={2} placeholder="描述该关系的业务含义..." />
          </Form.Item>
        </Form>
      </Modal>

      {/* 编辑关系（双击边弹窗） */}
      <Modal title="编辑关系" open={editLinkModalVisible} onOk={handleSaveEditEdge}
        onCancel={() => setEditLinkModalVisible(false)} confirmLoading={saving} okText="保存" cancelText="取消"
        width={560}>
        <Form form={editEdgeForm} layout="vertical">
          <Form.Item name="iri" label="关系 IRI">
            <Input disabled />
          </Form.Item>
          <Form.Item name="label" label="中文标签" rules={[{ required: true, message: '请输入中文标签' }]}>
            <Input placeholder="如：拥有订单" />
          </Form.Item>
          <Form.Item name="sourceType" label="定义域 Domain（源类）" rules={[{ required: true, message: '请选择源类' }]}>
            <Select placeholder="选择源类" showSearch optionFilterProp="label" options={classOptions} />
          </Form.Item>
          <Form.Item name="targetType" label="值域 Range（目标类）" rules={[{ required: true, message: '请选择目标类' }]}>
            <Select placeholder="选择目标类" showSearch optionFilterProp="label" options={classOptions} />
          </Form.Item>
          <Form.Item name="description" label="备注描述">
            <Input.TextArea rows={2} placeholder="描述该关系的业务含义..." />
          </Form.Item>
        </Form>
      </Modal>

      {/* 从画布连线创建关系 */}
      <Modal title="创建新关系" open={createEdgeModalVisible} onOk={handleSaveCreateEdge}
        onCancel={() => { setCreateEdgeModalVisible(false); setEdgeDraft(null); }}
        confirmLoading={saving} okText="创建" cancelText="取消" width={560}>
        {edgeDraft && (
          <div style={{ marginBottom: 16, padding: '8px 12px', background: '#f5f5f5', borderRadius: 6 }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              源节点: <Text strong>{edgeDraft.sourceId}</Text> → 目标节点: <Text strong>{edgeDraft.targetId}</Text>
            </Text>
          </div>
        )}
        <Form form={createEdgeForm} layout="vertical">
          <Form.Item name="iri" label="关系 IRI" rules={[{ required: true, message: '请输入 IRI' }]}>
            <Input placeholder="http://ontologybuilder.local/ontology#hasRelation" />
          </Form.Item>
          <Form.Item name="label" label="中文标签" rules={[{ required: true, message: '请输入中文标签' }]}>
            <Input placeholder="如：拥有" />
          </Form.Item>
          <Form.Item name="description" label="备注描述">
            <Input.TextArea rows={2} placeholder="描述该关系的业务含义..." />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default LinkTypesGraphView;
