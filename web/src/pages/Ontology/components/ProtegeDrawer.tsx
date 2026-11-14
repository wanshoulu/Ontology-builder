/**
 * ProtegeDrawer — Protege 风格编辑器 (Drawer 模式)
 *
 * 替代原来的 Modal 内 Tab 嵌套 (消除 10 层 Tab 问题)
 * 布局: 左侧类层次树 + 中间图谱 + 右侧属性面板 (三栏)
 * 集成: 选择驱动协调视图、撤销/重做、图谱编辑、JPG导出
 */
import {
ApartmentOutlined,
CameraOutlined,
ExpandOutlined,
RedoOutlined,
SaveOutlined,
UndoOutlined,
ZoomInOutlined,
ZoomOutOutlined
} from '@ant-design/icons';
import {
Badge,
Button,
Drawer,
message,
Segmented,
Space,Tag,
Tooltip,
Typography,
} from 'antd';
import React,{ useCallback,useState } from 'react';
import { useOntologyGraph } from '../hooks/useOntologyGraph';
import { useProtegeEditor } from '../hooks/useProtegeEditor';
import ClassHierarchyTree from './ClassHierarchyTree';
import PropertyEditor from './PropertyEditor';

const { Text } = Typography;

interface ProtegeDrawerProps {
  ontologyId: string;
  ontologyName: string;
  visible: boolean;
  onClose: () => void;
  onDataChange: () => void;
}

const ProtegeDrawer: React.FC<ProtegeDrawerProps> = ({
  ontologyId, ontologyName, visible, onClose, onDataChange,
}) => {
  // Editor state
  const editor = useProtegeEditor({ ontologyId });

  // Sync initial data when drawer opens
  React.useEffect(() => {
    if (visible && ontologyId) editor.loadData();
  }, [visible, ontologyId]);

  // Class hierarchy search
  const [classSearchText, setClassSearchText] = useState('');

  // Selection-driven coordination
  const handleClassSelect = useCallback((cls: any) => {
    editor.setSelectedClass(cls);
    editor.setSelectedProperty(null);
  }, [editor]);

  // Graph hook
  const graph = useOntologyGraph({
    interactionMode: 'edit',
    onNodeClick: (node) => {
      const cls = editor.classes.find((c: any) => (c.iri || c.id) === (node.iri || node.id));
      if (cls) editor.setSelectedClass(cls);
    },
    onNodeDoubleClick: (node) => {
      const cls = editor.classes.find((c: any) => (c.iri || c.id) === (node.iri || node.id));
      if (cls) editor.setSelectedClass(cls);
    },
    onEdgeClick: (edge) => {
      const prop = editor.properties.find((p: any) => (p.iri || p.id) === (edge.iri || edge.id));
      if (prop) editor.setSelectedProperty(prop);
    },
    onNodeDragEnd: (nodeId, x, y) => {
      editor.savePosition(nodeId, x, y);
    },
  });

  // Render graph when data changes
  React.useEffect(() => {
    if (visible && editor.classes.length > 0) {
      graph.renderGraph(editor.classes, editor.properties, graph.viewMode);
    }
  }, [visible, editor.classes, editor.properties, graph.viewMode]);

  // Expert/Novice mode (persisted)
  const [expertMode, setExpertMode] = useState(() => {
    try { return localStorage.getItem('ontology_expert_mode') === 'true'; }
    catch { return false; }
  });

  const toggleExpertMode = () => {
    const next = !expertMode;
    setExpertMode(next);
    try { localStorage.setItem('ontology_expert_mode', String(next)); }
    catch { /* localStorage unavailable */ }
  };

  // Keyboard shortcuts — use refs to avoid re-registering on every render
  const editorRef = React.useRef(editor);
  editorRef.current = editor;
  const visibleRef = React.useRef(visible);
  visibleRef.current = visible;
  React.useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (!visibleRef.current) return;
      if ((e.ctrlKey || e.metaKey) && e.key === 'z') {
        e.preventDefault();
        if (e.shiftKey) editorRef.current.redo();
        else editorRef.current.undo();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []); // Only register once

  // Export graph to JPG
  const handleExportJpg = async () => {
    const dataUrl = await graph.exportToJpg();
    if (dataUrl) {
      const a = document.createElement('a');
      a.href = dataUrl;
      a.download = `ontology-${ontologyName.replace(/\s+/g, '-')}-graph.jpg`;
      a.click();
      message.success('图谱已导出为 JPG');
    }
  };

  return (
    <Drawer
      title={
        <Space>
          <ApartmentOutlined style={{ color: '#1677ff' }} />
          <span>Protege 精修</span>
          <Tag color="blue">{ontologyName}</Tag>
        </Space>
      }
      open={visible}
      onClose={onClose}
      width="95vw"
      style={{ top: 0 }}
      destroyOnHidden
      extra={
        <Space size={4}>
          {/* Undo/Redo */}
          <Tooltip title="撤销 (Ctrl+Z)">
            <Button size="small" icon={<UndoOutlined />} disabled={!editor.canUndo} onClick={editor.undo} />
          </Tooltip>
          <Tooltip title="重做 (Ctrl+Shift+Z)">
            <Button size="small" icon={<RedoOutlined />} disabled={!editor.canRedo} onClick={editor.redo} />
          </Tooltip>

          {/* Staged changes */}
          {editor.stagedChanges.length > 0 && (
            <Tooltip title={`${editor.stagedChanges.length} 个待提交变更`}>
              <Badge count={editor.stagedChanges.length} size="small">
                <Button size="small" type="primary" icon={<SaveOutlined />}
                  loading={editor.saving} onClick={editor.commitChanges}>
                  提交变更
                </Button>
              </Badge>
            </Tooltip>
          )}

          {/* JPG Export */}
          <Tooltip title="导出图谱为 JPG">
            <Button size="small" icon={<CameraOutlined />} onClick={handleExportJpg} />
          </Tooltip>

          {/* Zoom controls */}
          <Button size="small" icon={<ZoomInOutlined />} onClick={graph.zoomIn} />
          <Button size="small" icon={<ZoomOutOutlined />} onClick={graph.zoomOut} />
          <Button size="small" icon={<ExpandOutlined />} onClick={graph.zoomReset} />

          {/* View mode */}
          <Segmented size="small" value={graph.viewMode} onChange={(v) => graph.setViewMode(v as any)}
            options={[
              { value: 'graph', label: '图' },
              { value: 'mindmap', label: '脑图' },
            ]} />

          {/* Expert toggle */}
          <Button size="small" type={expertMode ? 'primary' : 'default'}
            onClick={toggleExpertMode}>
            {expertMode ? '专家' : '新手'}
          </Button>
        </Space>
      }
    >
      {/* Three-column layout */}
      <div style={{ display: 'flex', gap: 12, height: 'calc(100vh - 100px)' }}>
        {/* Left: Class Hierarchy Tree */}
        <div style={{ width: 280, flexShrink: 0, border: '1px solid #f0f0f0', borderRadius: 6, overflow: 'hidden' }}>
          <ClassHierarchyTree
            classes={editor.classes}
            selectedClassId={editor.selectedClass?.iri || editor.selectedClass?.id || null}
            onSelect={handleClassSelect}
            searchText={classSearchText}
            onSearchChange={setClassSearchText}
          />
        </div>

        {/* Center: Graph Canvas */}
        <div style={{ flex: 1, border: '1px solid #f0f0f0', borderRadius: 6, overflow: 'hidden', position: 'relative' }}>
          <div ref={graph.containerRef} style={{ width: '100%', height: '100%' }} />

          {/* Graph legend overlay */}
          <div style={{
            position: 'absolute', bottom: 8, left: 8,
            background: 'rgba(255,255,255,0.9)', padding: '4px 8px',
            borderRadius: 4, fontSize: 10, color: '#999',
            border: '1px solid #f0f0f0',
          }}>
            <span>🖱 滚轮缩放</span> · <span>拖拽移动</span>
            {!expertMode && (
              <div style={{ marginTop: 2 }}>
                <Text type="secondary" style={{ fontSize: 9 }}>
                  💡 提示: 点击类查看属性 · Ctrl+Z 撤销
                </Text>
              </div>
            )}
          </div>
        </div>

        {/* Right: Property Editor (expert mode only) */}
        {expertMode && (
          <div style={{ width: 320, flexShrink: 0, border: '1px solid #f0f0f0', borderRadius: 6, overflow: 'hidden' }}>
            <PropertyEditor
              selectedClass={editor.selectedClass}
              objectProperties={editor.properties}
              dataProperties={[]}
              allClasses={editor.classes}
              onCreateProperty={editor.createProperty}
              onUpdateProperty={editor.updateProperty}
              onDeleteProperty={editor.deleteProperty}
            />
          </div>
        )}
      </div>

      {/* Stats footer */}
      <div style={{
        marginTop: 8, padding: '6px 12px',
        background: '#fafafa', borderRadius: 6, fontSize: 11, color: '#999',
        display: 'flex', gap: 16, border: '1px solid #f0f0f0',
      }}>
        <span>📦 {editor.classes.length} 类</span>
        <span>🔗 {editor.properties.length} 属性</span>
        <span>📐 {editor.rules.length} 规则</span>
        <span>👤 {editor.individuals.length} 个体</span>
        {editor.canUndo && <span style={{ color: '#1677ff' }}>🔙 可撤销 {editor.undoStack.length} 步</span>}
        {editor.stagedChanges.length > 0 && (
          <span style={{ color: '#fa8c16' }}>⏳ {editor.stagedChanges.length} 个变更待提交</span>
        )}
      </div>
    </Drawer>
  );
};

export default ProtegeDrawer;
