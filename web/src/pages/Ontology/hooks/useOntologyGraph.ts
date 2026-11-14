/**
 * useOntologyGraph — 共享图谱可视化 hook
 *
 * 合并原有两个独立 G6 实现 (renderProtegeGraph + renderGraphViz)
 * 支持: 浏览模式 / 编辑模式 / 思维导图模式
 * 修复: 增量更新、debounce 保存、离屏导出、canvas 内事件监听
 */
import { message } from 'antd';
import { useCallback,useEffect,useRef,useState } from 'react';

interface GraphNode {
  iri: string;
  id: string;
  label: string;
  parentIri?: string;
  description?: string;
  [key: string]: any;
}

interface GraphEdge {
  iri: string;
  id: string;
  label: string;
  sourceType: string;
  targetType: string;
  description?: string;
  [key: string]: any;
}

export type GraphViewMode = 'graph' | 'mindmap';
export type GraphInteractionMode = 'browse' | 'edit';

interface UseOntologyGraphOptions {
  interactionMode: GraphInteractionMode;
  onNodeClick?: (node: GraphNode) => void;
  onEdgeClick?: (edge: GraphEdge) => void;
  onNodeDoubleClick?: (node: GraphNode) => void;
  onEdgeDoubleClick?: (edge: GraphEdge) => void;
  onEdgeCreate?: (sourceId: string, targetId: string) => void;
  onNodeDragEnd?: (nodeId: string, x: number, y: number) => void;
  savePositionsDebounceMs?: number;
}

const NODE_COLORS = [
  '#1677ff', '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16',
  '#52c41a', '#2f54eb', '#faad14', '#f5222d', '#1d39c4',
  '#9254de', '#36cfc9', '#ff85c0', '#ffc53d', '#73d13d',
];

export function useOntologyGraph(options: UseOntologyGraphOptions) {
  const {
    interactionMode = 'browse',
    onNodeClick,
    onEdgeClick,
    onNodeDoubleClick,
    onEdgeDoubleClick,
    onEdgeCreate,
    onNodeDragEnd,
    savePositionsDebounceMs = 500,
  } = options;

  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<any>(null);
  const [loading, setLoading] = useState(false);
  const [viewMode, setViewMode] = useState<GraphViewMode>('graph');
  const [zoom, setZoom] = useState(1);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);

  // Debounced position save
  const positionBufferRef = useRef<Map<string, { x: number; y: number }>>(new Map());
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const flushPositions = useCallback(() => {
    if (!onNodeDragEnd || positionBufferRef.current.size === 0) return;
    const entries = Array.from(positionBufferRef.current.entries());
    positionBufferRef.current.clear();
    entries.forEach(([nodeId, pos]) => onNodeDragEnd(nodeId, pos.x, pos.y));
  }, [onNodeDragEnd]);

  // Cleanup
  useEffect(() => {
    return () => {
      if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
      if (graphRef.current) {
        try { graphRef.current.destroy(); } catch { /* cleanup */ }
        graphRef.current = null;
      }
    };
  }, []);

  // Render graph (lazy-load G6)
  const renderGraph = useCallback(async (
    classes: GraphNode[],
    properties: GraphEdge[],
    mode: GraphViewMode = 'graph',
  ) => {
    if (!containerRef.current) return;
    setLoading(true);

    try {
      const { Graph: G6Graph } = await import('@antv/g6');

      // Destroy old instance if exists
      if (graphRef.current) {
        try { graphRef.current.destroy(); } catch { /* cleanup */ }
        graphRef.current = null;
      }

      if (classes.length === 0) {
        setLoading(false);
        return;
      }

      const isMindmap = mode === 'mindmap';
      const hasInheritance = classes.some(c => c.parentIri);

      // Build nodes — use id for API calls, iri for display matching
      // API endpoints (updateObjectPosition, etc.) expect the database ID, not prefixed IRI
      const nodes = classes.map((cls, i) => {
        const color = NODE_COLORS[i % NODE_COLORS.length];
        const isRoot = !cls.parentIri;
        const nodeId = cls.id || cls.iri || `class-${i}`;
        return {
          id: nodeId,
          data: {
            ...cls,
            color,
            label: cls.label || cls.iri?.split('/').pop() || `Class${i}`,
          },
          style: {
            fill: isMindmap ? '#fff' : color,
            stroke: color,
            lineWidth: 2,
            size: isRoot ? (isMindmap ? 56 : 50) : (isMindmap ? 40 : 36),
            labelText: cls.label || cls.iri?.split('/').pop() || `Class${i}`,
            labelFill: isMindmap ? '#333' : '#fff',
            labelFontSize: isRoot ? 12 : 11,
            cursor: 'pointer' as const,
          },
        };
      });

      // Build edges
      const edges: any[] = [];
      // Inheritance edges (dashed) — use same ID priority as nodes (id first)
      classes.forEach(cls => {
        if (cls.parentIri) {
          const parent = classes.find(c => c.iri === cls.parentIri || c.id === cls.parentIri);
          if (parent) {
            edges.push({
              source: cls.id || cls.iri,
              target: parent.id || parent.iri,
              data: { edgeType: 'inheritance' },
              style: { stroke: '#999', lineWidth: 1, lineDash: [4, 2], endArrow: true },
            });
          }
        }
      });
      // Property edges (solid) — match by iri OR id, use id priority
      properties.forEach(prop => {
        const source = classes.find(c => c.iri === prop.sourceType || c.id === prop.sourceType);
        const target = classes.find(c => c.iri === prop.targetType || c.id === prop.targetType);
        if (source && target) {
          edges.push({
            source: source.id || source.iri,
            target: target.id || target.iri,
            data: { ...prop, edgeType: 'property' },
            style: { stroke: '#1677ff', lineWidth: 1.5, endArrow: true },
          });
        }
      });

      const graph = new G6Graph({
        container: containerRef.current,
        width: containerRef.current.clientWidth,
        height: 520,
        autoFit: 'view',
        data: { nodes, edges },
        layout: {
          type: 'dagre',
          rankdir: isMindmap ? 'LR' : 'TB',
          nodesep: 20,
          ranksep: 50,
        },
        behaviors: interactionMode === 'edit'
          ? ['drag-canvas', 'zoom-canvas', 'drag-element', 'click-select']
          : ['drag-canvas', 'zoom-canvas'],
        node: {
          type: 'circle',
          style: (d: any) => d.style || {},
        },
        edge: {
          type: 'line',
          style: (d: any) => ({
            stroke: d.style?.stroke || '#bbb',
            lineWidth: d.style?.lineWidth || 1,
            lineDash: d.style?.lineDash || undefined,
            endArrow: d.style?.endArrow,
          }),
        },
      });

      // Event handlers — match by id first (same priority as node IDs)
      graph.on('node:click', (evt: any) => {
        const id = evt.target?.id;
        setSelectedNodeId(id || null);
        setSelectedEdgeId(null);
        if (onNodeClick && id) {
          const node = classes.find(c => c.id === id || c.iri === id);
          if (node) onNodeClick(node);
        }
      });

      graph.on('edge:click', (evt: any) => {
        const id = evt.target?.id;
        setSelectedEdgeId(id || null);
        setSelectedNodeId(null);
        if (onEdgeClick && id) {
          const edge = properties.find(p => p.id === id || p.iri === id);
          if (edge) onEdgeClick(edge);
        }
      });

      graph.on('canvas:click', () => {
        setSelectedNodeId(null);
        setSelectedEdgeId(null);
      });

      if (interactionMode === 'edit') {
        graph.on('node:dblclick', (evt: any) => {
          const id = evt.target?.id;
          if (onNodeDoubleClick && id) {
            const node = classes.find(c => c.id === id || c.iri === id);
            if (node) onNodeDoubleClick(node);
          }
        });

        graph.on('edge:dblclick', (evt: any) => {
          const id = evt.target?.id;
          if (onEdgeDoubleClick && id) {
            const edge = properties.find(p => p.id === id || p.iri === id);
            if (edge) onEdgeDoubleClick(edge);
          }
        });

        graph.on('node:dragend', (evt: any) => {
          const id = evt.target?.id;
          const pos = evt.target?.getPosition?.();
          if (id && pos && onNodeDragEnd) {
            positionBufferRef.current.set(id, { x: pos.x, y: pos.y });
            if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
            saveTimerRef.current = setTimeout(flushPositions, savePositionsDebounceMs);
          }
        });
      }

      await graph.render();
      graphRef.current = graph;
    } catch (e: any) {
      console.error('G6 render error:', e);
      message.error('图谱渲染失败: ' + (e.message || '未知错误'));
    } finally {
      setLoading(false);
    }
  }, [interactionMode, onNodeClick, onEdgeClick, onNodeDoubleClick, onEdgeDoubleClick, onEdgeCreate, onNodeDragEnd, savePositionsDebounceMs, flushPositions]);

  // Export graph to JPG (offscreen — no flash)
  const exportToJpg = useCallback(async (): Promise<string | null> => {
    if (!graphRef.current) return null;
    try {
      const canvas = await graphRef.current.toCanvas();
      return canvas.toDataURL('image/jpeg', 0.95);
    } catch (e: any) {
      message.error('导出失败: ' + (e.message || '未知错误'));
      return null;
    }
  }, []);

  // Zoom controls
  const zoomIn = useCallback(() => {
    if (!graphRef.current) return;
    const current = graphRef.current.getZoom();
    graphRef.current.zoomTo(Math.min(current * 1.3, 5));
    setZoom(graphRef.current.getZoom());
  }, []);

  const zoomOut = useCallback(() => {
    if (!graphRef.current) return;
    const current = graphRef.current.getZoom();
    graphRef.current.zoomTo(Math.max(current / 1.3, 0.1));
    setZoom(graphRef.current.getZoom());
  }, []);

  const zoomReset = useCallback(() => {
    if (!graphRef.current) return;
    graphRef.current.fitView(40);
    setZoom(graphRef.current.getZoom());
  }, []);

  return {
    containerRef,
    graphRef,
    loading,
    viewMode,
    setViewMode,
    zoom,
    selectedNodeId,
    selectedEdgeId,
    renderGraph,
    exportToJpg,
    zoomIn,
    zoomOut,
    zoomReset,
  };
}
