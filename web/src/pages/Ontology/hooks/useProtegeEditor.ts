/**
 * useProtegeEditor — Protégé 可视化编辑器状态 hook
 *
 * 管理: 类/属性/规则/个体数据加载、CRUD、Command 模式撤销/重做、暂存区
 * 替代原来 35 个分散的 useState
 */
import {
activateRule,
createLinkType,
createObjectType,
createRule,
deactivateRule,
deleteLinkType,
deleteObjectType,
deleteRule,
executeSparqlQuery,
getLinkTypes,
getObjectTypes,
getRulesByOntology,
regenerateTtl,
syncRulesToTtl,
syncSwrlRules,
updateLinkType,
updateObjectPosition,
updateObjectType,
updateRule
} from '@/api/ontology';
import { message } from 'antd';
import { useCallback,useState } from 'react';

// Command pattern for undo/redo
interface EditCommand {
  type: 'create' | 'update' | 'delete';
  entityType: 'class' | 'property' | 'rule' | 'dataProperty' | 'subClassOf';
  entityId: string;
  previousState?: any;
  newState?: any;
  description: string;
  timestamp: number;
}

interface UseProtegeEditorOptions {
  ontologyId: string;
}

export function useProtegeEditor(options: UseProtegeEditorOptions) {
  const { ontologyId } = options;

  // Data
  const [classes, setClasses] = useState<any[]>([]);
  const [properties, setProperties] = useState<any[]>([]);
  const [rules, setRules] = useState<any[]>([]);
  const [individuals, setIndividuals] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  // Selection (coordinated views)
  const [selectedClass, setSelectedClass] = useState<any | null>(null);
  const [selectedProperty, setSelectedProperty] = useState<any | null>(null);

  // Command stack
  const [undoStack, setUndoStack] = useState<EditCommand[]>([]);
  const [redoStack, setRedoStack] = useState<EditCommand[]>([]);
  const [stagedChanges, setStagedChanges] = useState<EditCommand[]>([]);

  // Load all data
  const loadData = useCallback(async () => {
    if (!ontologyId) return;
    setLoading(true);
    try {
      const [cls, props] = await Promise.all([
        getObjectTypes(ontologyId),
        getLinkTypes(ontologyId),
      ]);
      setClasses(cls || []);
      setProperties(props || []);
    } catch (e: any) {
      message.error('加载本体数据失败: ' + (e.message || '未知错误'));
    }

    try {
      await syncSwrlRules(ontologyId).catch(() => {});
      const r = await getRulesByOntology(ontologyId);
      setRules(r || []);
    } catch { message.error('加载规则失败'); }

    try {
      const result = await executeSparqlQuery(ontologyId,
        `PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
SELECT ?individual ?label ?type WHERE {
  ?individual a owl:NamedIndividual .
  OPTIONAL { ?individual rdfs:label ?label }
  OPTIONAL { ?individual rdf:type ?type . FILTER(?type != owl:NamedIndividual) }
}`);
      const bindings = result?.bindings || [];
      const map = new Map<string, any>();
      bindings.forEach((row: any) => {
        const iri = row.individual || '';
        if (!iri) return;
        if (!map.has(iri)) map.set(iri, { iri, label: row.label || '', types: [] });
        if (row.type && !map.get(iri).types.includes(row.type)) map.get(iri).types.push(row.type);
      });
      setIndividuals(Array.from(map.values()));
    } catch { setIndividuals([]); }

    setLoading(false);
  }, [ontologyId]);

  // Push command to undo stack
  const pushCommand = useCallback((cmd: EditCommand) => {
    setUndoStack(prev => [...prev, cmd]);
    setRedoStack([]); // Clear redo on new action
    setStagedChanges(prev => [...prev, cmd]);
  }, []);

  // Undo
  const undo = useCallback(async () => {
    const cmd = undoStack[undoStack.length - 1];
    if (!cmd) return;
    try {
      switch (cmd.type) {
        case 'create':
          if (cmd.entityType === 'class') await deleteObjectType(ontologyId, cmd.entityId);
          else if (cmd.entityType === 'property') await deleteLinkType(ontologyId, cmd.entityId);
          else if (cmd.entityType === 'rule') await deleteRule(cmd.entityId);
          break;
        case 'update':
          if (cmd.entityType === 'class') await updateObjectType(ontologyId, cmd.entityId, cmd.previousState || {});
          else if (cmd.entityType === 'property') await updateLinkType(ontologyId, cmd.entityId, cmd.previousState || {});
          else if (cmd.entityType === 'rule') await updateRule(cmd.entityId, cmd.previousState || {});
          break;
        case 'delete':
          // Re-create the deleted entity from previousState
          if (cmd.entityType === 'class' && cmd.previousState) {
            await createObjectType(ontologyId, cmd.previousState);
          } else if (cmd.entityType === 'property' && cmd.previousState) {
            await createLinkType(ontologyId, cmd.previousState);
          } else if (cmd.entityType === 'rule' && cmd.previousState) {
            await createRule(cmd.previousState);
          }
          break;
      }
      setUndoStack(prev => prev.slice(0, -1));
      setRedoStack(prev => [...prev, cmd]);
      setStagedChanges(prev => prev.filter(c => c.timestamp !== cmd.timestamp));
      message.success(`已撤销: ${cmd.description}`);
      await loadData();
    } catch (e: any) {
      message.error('撤销失败: ' + (e.message || '未知错误'));
    }
  }, [undoStack, ontologyId, loadData]);

  // Redo
  const redo = useCallback(async () => {
    const cmd = redoStack[redoStack.length - 1];
    if (!cmd) return;
    try {
      switch (cmd.type) {
        case 'create':
          if (cmd.entityType === 'class') await createObjectType(ontologyId, cmd.newState || {});
          else if (cmd.entityType === 'property') await createLinkType(ontologyId, cmd.newState || {});
          else if (cmd.entityType === 'rule') await createRule(cmd.newState || {});
          break;
        case 'update':
          if (cmd.entityType === 'class') await updateObjectType(ontologyId, cmd.entityId, cmd.newState || {});
          else if (cmd.entityType === 'property') await updateLinkType(ontologyId, cmd.entityId, cmd.newState || {});
          else if (cmd.entityType === 'rule') await updateRule(cmd.entityId, cmd.newState || {});
          break;
        case 'delete':
          if (cmd.entityType === 'class') await deleteObjectType(ontologyId, cmd.entityId);
          else if (cmd.entityType === 'property') await deleteLinkType(ontologyId, cmd.entityId);
          else if (cmd.entityType === 'rule') await deleteRule(cmd.entityId);
          break;
      }
      setRedoStack(prev => prev.slice(0, -1));
      setUndoStack(prev => [...prev, cmd]);
      message.success(`已重做: ${cmd.description}`);
      await loadData();
    } catch (e: any) {
      message.error('重做失败: ' + (e.message || '未知错误'));
    }
  }, [redoStack, ontologyId, loadData]);

  // Commit staged changes (staging → working graph)
  const commitChanges = useCallback(async () => {
    if (stagedChanges.length === 0) return;
    setSaving(true);
    try {
      await regenerateTtl(ontologyId);
      await syncRulesToTtl(ontologyId);
      setStagedChanges([]);
      message.success(`已提交 ${stagedChanges.length} 个变更到工作图谱`);
      await loadData();
    } catch (e: any) {
      message.error('提交变更失败: ' + (e.message || '未知错误'));
    } finally {
      setSaving(false);
    }
  }, [stagedChanges, ontologyId, loadData]);

  // Class CRUD
  const createClass = useCallback(async (data: any) => {
    try {
      const result = await createObjectType(ontologyId, data);
      pushCommand({ type: 'create', entityType: 'class', entityId: result.id || '', newState: data, description: `创建类 ${data.label || data.iri}`, timestamp: Date.now() });
      await loadData();
      return result;
    } catch (e: any) {
      message.error('创建类失败: ' + (e.message || '未知错误'));
      return null;
    }
  }, [ontologyId, loadData, pushCommand]);

  const updateClass = useCallback(async (classId: string, data: any) => {
    try {
      const prev = classes.find(c => (c.iri || c.id) === classId);
      await updateObjectType(ontologyId, classId, data);
      pushCommand({ type: 'update', entityType: 'class', entityId: classId, previousState: prev, newState: data, description: `更新类 ${data.label || classId}`, timestamp: Date.now() });
      await loadData();
    } catch (e: any) {
      message.error('更新类失败: ' + (e.message || '未知错误'));
    }
  }, [ontologyId, classes, loadData, pushCommand]);

  const deleteClass = useCallback(async (classId: string) => {
    try {
      const prev = classes.find(c => (c.iri || c.id) === classId);
      await deleteObjectType(ontologyId, classId);
      pushCommand({ type: 'delete', entityType: 'class', entityId: classId, previousState: prev, description: `删除类 ${prev?.label || classId}`, timestamp: Date.now() });
      if (selectedClass?.id === classId || selectedClass?.iri === classId) setSelectedClass(null);
      await loadData();
    } catch (e: any) {
      message.error('删除类失败: ' + (e.message || '未知错误'));
    }
  }, [ontologyId, classes, selectedClass, loadData, pushCommand]);

  // Property CRUD
  const createProperty = useCallback(async (data: any) => {
    try {
      const result = await createLinkType(ontologyId, data);
      pushCommand({ type: 'create', entityType: 'property', entityId: result.id || '', newState: data, description: `创建关系 ${data.label || data.iri}`, timestamp: Date.now() });
      await loadData();
      return result;
    } catch (e: any) {
      message.error('创建关系失败: ' + (e.message || '未知错误'));
      return null;
    }
  }, [ontologyId, loadData, pushCommand]);

  const updateProperty = useCallback(async (propId: string, data: any) => {
    try {
      const prev = properties.find(p => (p.iri || p.id) === propId);
      await updateLinkType(ontologyId, propId, data);
      pushCommand({ type: 'update', entityType: 'property', entityId: propId, previousState: prev, newState: data, description: `更新关系 ${data.label || propId}`, timestamp: Date.now() });
      await loadData();
    } catch (e: any) {
      message.error('更新关系失败: ' + (e.message || '未知错误'));
    }
  }, [ontologyId, properties, loadData, pushCommand]);

  const deleteProperty = useCallback(async (propId: string) => {
    try {
      const prev = properties.find(p => (p.iri || p.id) === propId);
      await deleteLinkType(ontologyId, propId);
      pushCommand({ type: 'delete', entityType: 'property', entityId: propId, previousState: prev, description: `删除关系 ${prev?.label || propId}`, timestamp: Date.now() });
      await loadData();
    } catch (e: any) {
      message.error('删除关系失败: ' + (e.message || '未知错误'));
    }
  }, [ontologyId, properties, loadData, pushCommand]);

  // Position save
  const savePosition = useCallback(async (nodeId: string, x: number, y: number) => {
    try {
      await updateObjectPosition(ontologyId, nodeId, { x, y });
    } catch { /* position save is non-critical */ }
  }, [ontologyId]);

  return {
    // Data
    classes, properties, rules, individuals,
    loading, saving,
    loadData,
    // Selection
    selectedClass, setSelectedClass,
    selectedProperty, setSelectedProperty,
    // CRUD
    createClass, updateClass, deleteClass,
    createProperty, updateProperty, deleteProperty,
    savePosition,
    // Rule management
    setRules,
    createRule, updateRule, deleteRule, activateRule, deactivateRule,
    // Undo/Redo
    undoStack, redoStack, undo, redo, canUndo: undoStack.length > 0, canRedo: redoStack.length > 0,
    // Staging
    stagedChanges, commitChanges,
  };
}
