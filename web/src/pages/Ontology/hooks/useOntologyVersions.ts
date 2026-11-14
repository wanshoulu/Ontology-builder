/**
 * useOntologyVersions — 本体版本管理 hook
 *
 * 从 OntologyPage 抽取版本列表、搜索、分组、选择、删除等状态
 * 替代原来 12 个分散的 useState
 */
import { batchDeleteOntologies,deleteOntology,getAllOntologyVersions } from '@/api/ontology';
import { message,Modal } from 'antd';
import { useCallback,useEffect,useMemo,useState } from 'react';

export interface OntologyVersion {
  id: string;
  version: string;
  versionName: string;
  name: string;
  description: string;
  status: string;
  classCount: number;
  propertyCount: number;
  objectPropertyCount?: number;
  dataPropertyCount?: number;
  ruleCount?: number;
  individualCount?: number;
  ttlPath: string | null;
  instantiated: boolean;
  createdAt: string;
  updatedAt: string;
  current: boolean;
  sourceMdFileName?: string;
  sourceMdFileId?: string;
  sourceModelName?: string;
  ttlGeneratedAt?: string;
}

interface UseOntologyVersionsReturn {
  // Data
  allVersions: OntologyVersion[];
  allVersionsLoading: boolean;
  groupedVersions: Record<string, OntologyVersion[]>;
  ontologyNames: string[];
  // Selection
  selectedVersionId: string;
  setSelectedVersionId: (id: string) => void;
  selectedVersion: OntologyVersion | null;
  // Search
  searchText: string;
  setSearchText: (text: string) => void;
  filteredGroupedVersions: Record<string, OntologyVersion[]>;
  // Batch delete
  selectedRowKeys: string[];
  setSelectedRowKeys: (keys: string[]) => void;
  handleBatchDelete: () => Promise<void>;
  handleDeleteVersion: (id: string) => Promise<void>;
  // Refresh
  refreshVersions: () => Promise<void>;
}

export function useOntologyVersions(): UseOntologyVersionsReturn {
  const [allVersions, setAllVersions] = useState<OntologyVersion[]>([]);
  const [allVersionsLoading, setAllVersionsLoading] = useState(false);
  const [selectedVersionId, setSelectedVersionId] = useState<string>('');
  const [searchText, setSearchText] = useState('');
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);

  // Load all versions
  const refreshVersions = useCallback(async () => {
    setAllVersionsLoading(true);
    try {
      const data = await getAllOntologyVersions();
      const mapped = Array.isArray(data) ? data.map((v: any) => ({
        ...v,
        classCount: v.classCount || 0,
        propertyCount: (v.objectPropertyCount || 0) + (v.dataPropertyCount || 0),
      })) : [];
      setAllVersions(mapped);
    } catch (e: any) {
      message.error('加载本体版本列表失败: ' + (e.message || '未知错误'));
      setAllVersions([]);
    } finally {
      setAllVersionsLoading(false);
    }
  }, []);

  useEffect(() => { refreshVersions(); }, [refreshVersions]);

  // Group versions by name
  const groupedVersions = useMemo(() => {
    const groups: Record<string, OntologyVersion[]> = {};
    allVersions.forEach(v => {
      const key = v.name || '未命名';
      if (!groups[key]) groups[key] = [];
      groups[key].push(v);
    });
    // Sort versions within each group: newest first
    Object.values(groups).forEach(g => {
      g.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
    });
    return groups;
  }, [allVersions]);

  const ontologyNames = useMemo(() => Object.keys(groupedVersions), [groupedVersions]);

  // Filter by search
  const filteredGroupedVersions = useMemo(() => {
    if (!searchText.trim()) return groupedVersions;
    const lower = searchText.toLowerCase();
    const filtered: Record<string, OntologyVersion[]> = {};
    Object.entries(groupedVersions).forEach(([name, versions]) => {
      const matching = versions.filter(v =>
        v.name?.toLowerCase().includes(lower) ||
        v.versionName?.toLowerCase().includes(lower) ||
        v.description?.toLowerCase().includes(lower)
      );
      if (matching.length > 0) filtered[name] = matching;
    });
    return filtered;
  }, [groupedVersions, searchText]);

  // Selected version
  const selectedVersion = useMemo(
    () => allVersions.find(v => v.id === selectedVersionId) || null,
    [allVersions, selectedVersionId]
  );

  // Delete single version
  const handleDeleteVersion = useCallback(async (id: string) => {
    try {
      await deleteOntology(id);
      message.success('已删除');
      if (selectedVersionId === id) setSelectedVersionId('');
      await refreshVersions();
    } catch (e: any) {
      message.error('删除失败: ' + (e.message || '未知错误'));
    }
  }, [selectedVersionId, refreshVersions]);

  // Batch delete
  const handleBatchDelete = useCallback(async () => {
    if (selectedRowKeys.length === 0) {
      message.warning('请先勾选要删除的版本');
      return;
    }
    Modal.confirm({
      title: '确认批量删除',
      content: `已选择 ${selectedRowKeys.length} 个本体版本，删除后将移除版本相关的类、属性、规则等数据。确认删除吗？`,
      okText: '确认删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          const result = await batchDeleteOntologies(selectedRowKeys);
          message.success(`已删除 ${result.deleted} 个版本`);
          if (result.failed > 0) {
            message.warning(`${result.failed} 个版本删除失败`);
          }
          setSelectedRowKeys([]);
          await refreshVersions();
        } catch (e: any) {
          message.error('批量删除失败: ' + (e.message || '未知错误'));
        }
      },
    });
  }, [selectedRowKeys, refreshVersions]);

  return {
    allVersions,
    allVersionsLoading,
    groupedVersions,
    ontologyNames,
    selectedVersionId,
    setSelectedVersionId,
    selectedVersion,
    searchText,
    setSearchText,
    filteredGroupedVersions,
    selectedRowKeys,
    setSelectedRowKeys,
    handleBatchDelete,
    handleDeleteVersion,
    refreshVersions,
  };
}
