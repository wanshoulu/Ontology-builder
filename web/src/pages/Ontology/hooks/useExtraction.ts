/**
 * useExtraction — LLM 提取本体模型流程 hook
 *
 * 管理 Markdown→TTL 提取的完整状态: 文件选择/模型选择/流式提取/暂停/保存
 */
import type { FileUploadRecord } from '@/api/fileUpload';
import { listAllCompletedFiles } from '@/api/fileUpload';
import { getProviderList } from '@/api/model';
import {
fetchDefaultOntologyExtractionPrompt,
importFromMarkdownStream,
saveOntologyVersion,
} from '@/api/ontology';
import type { ModelProvider,OntologyExtractionResult } from '@/types';
import { message } from 'antd';
import { useCallback,useEffect,useRef,useState } from 'react';

const VERSION_NAME_PATTERN = /^[一-龥a-zA-Z0-9_ \-]{1,50}$/;

export type ExtractPhase = 'config' | 'extracting' | 'paused' | 'done';

interface UseExtractionReturn {
  // Modal state
  visible: boolean;
  openModal: () => void;
  closeModal: () => void;
  // Config
  phase: ExtractPhase;
  mdFiles: FileUploadRecord[];
  mdFilesLoading: boolean;
  selectedFileId: string;
  setSelectedFileId: (id: string) => void;
  systemPrompt: string;
  setSystemPrompt: (p: string) => void;
  showPromptEditor: boolean;
  setShowPromptEditor: (v: boolean) => void;
  ontologyName: string;
  setOntologyName: (n: string) => void;
  versionName: string;
  setVersionName: (n: string) => void;
  // Model
  activeProviders: ModelProvider[];
  selectedModelKey: string;
  setSelectedModelKey: (k: string) => void;
  // Streaming
  streamContent: string;
  extractStatus: string;
  extractionResult: OntologyExtractionResult | null;
  // Actions
  startExtraction: () => Promise<void>;
  pauseExtraction: () => void;
  resumeExtraction: () => void;
  abortExtraction: () => void;
  saveResult: () => Promise<string | null>;
  // Provenance tracking
  provenance: { modelName: string; timestamp: string; fileSource: string } | null;
}

export function useExtraction(): UseExtractionReturn {
  const [visible, setVisible] = useState(false);
  const [phase, setPhase] = useState<ExtractPhase>('config');
  const [mdFiles, setMdFiles] = useState<FileUploadRecord[]>([]);
  const [mdFilesLoading, setMdFilesLoading] = useState(false);
  const [selectedFileId, setSelectedFileId] = useState('');
  const [systemPrompt, setSystemPrompt] = useState('');
  const [showPromptEditor, setShowPromptEditor] = useState(false);
  const [ontologyName, setOntologyName] = useState('');
  const [versionName, setVersionName] = useState('');
  // Model
  const [activeProviders, setActiveProviders] = useState<ModelProvider[]>([]);
  const [selectedModelKey, setSelectedModelKey] = useState('');
  // Streaming
  const [streamContent, setStreamContent] = useState('');
  const [extractStatus, setExtractStatus] = useState('');
  const [extractionResult, setExtractionResult] = useState<OntologyExtractionResult | null>(null);
  // Provenance
  const [provenance, setProvenance] = useState<{ modelName: string; timestamp: string; fileSource: string } | null>(null);

  const abortRef = useRef<AbortController | null>(null);

  // Load providers
  useEffect(() => {
    getProviderList()
      .then(all => {
        const active = (all || []).filter((p: any) => p.status?.toUpperCase() === 'ACTIVE');
        setActiveProviders(active);
        if (active.length > 0 && !selectedModelKey) {
          const first = active.find((p: any) => p.models?.length > 0);
          if (first?.models?.[0]) setSelectedModelKey(`${first.id}::${first.models[0].modelId}`);
        }
      })
      .catch((e: any) => { message.error('加载模型列表失败: ' + (e.message || '未知错误')); });
  }, []);

  const openModal = useCallback(async () => {
    setVisible(true);
    setPhase('config');
    setStreamContent('');
    setExtractionResult(null);
    setSelectedFileId('');
    setOntologyName('');
    setVersionName('');
    setProvenance(null);

    // Load MD files
    setMdFilesLoading(true);
    try {
      const files = await listAllCompletedFiles();
      setMdFiles((files || []).filter((f: FileUploadRecord) =>
        (f.originalFilename || '').toLowerCase().endsWith('.md') ||
        (f.originalFilename || '').toLowerCase().endsWith('.markdown')
      ));
    } catch (e: any) {
      message.error('加载文档列表失败: ' + (e.message || '未知错误'));
      setMdFiles([]);
    } finally {
      setMdFilesLoading(false);
    }

    // Load default prompt
    try {
      const prompt = await fetchDefaultOntologyExtractionPrompt();
      if (prompt) setSystemPrompt(prompt);
    } catch { /* use empty string as fallback */ }
  }, []);

  const closeModal = useCallback(() => {
    abortExtraction();
    setVisible(false);
  }, []);

  const startExtraction = useCallback(async () => {
    if (!selectedFileId) { message.warning('请选择要转换的 Markdown 文件'); return; }
    if (!selectedModelKey) { message.warning('请先选择提取模型'); return; }
    if (!ontologyName.trim()) { message.warning('请定义本体模型名称'); return; }
    if (!versionName.trim()) { message.warning('请定义版本号名称'); return; }
    if (!VERSION_NAME_PATTERN.test(versionName.trim())) {
      message.warning('版本名称仅支持中英文/数字/空格/下划线/短横线，且不超过50个字符');
      return;
    }

    setPhase('extracting');
    setStreamContent('');
    setExtractStatus('');
    setExtractionResult(null);

    const [providerId, modelId] = selectedModelKey.split('::');
    const ctrl = new AbortController();
    abortRef.current = ctrl;

    try {
      const selectedFile = mdFiles.find(f => f.id === selectedFileId);
      await importFromMarkdownStream(
        selectedFileId,
        providerId || '',
        modelId || '',
        systemPrompt,
        (chunk: string) => setStreamContent(prev => prev + chunk),
        (result: OntologyExtractionResult) => {
          setExtractionResult(result);
          setPhase('done');
          // Track provenance
          const provider = activeProviders.find(p => p.id === providerId);
          const model = provider?.models?.find((m: any) => m.modelId === modelId);
          setProvenance({
            modelName: model?.name || modelId || '未知模型',
            timestamp: new Date().toISOString(),
            fileSource: selectedFile?.originalFilename || selectedFileId,
          });
        },
        (error: string) => {
          message.error('提取失败: ' + error);
          setPhase('config');
        },
        ctrl.signal,
        (status: string) => setExtractStatus(status),
      );
    } catch (e: any) {
      if (e.name !== 'AbortError') {
        message.error('提取失败: ' + (e.message || '未知错误'));
        setPhase('config');
      }
    }
  }, [selectedFileId, selectedModelKey, ontologyName, versionName, systemPrompt, mdFiles, activeProviders]);

  const pauseExtraction = useCallback(() => {
    abortRef.current?.abort();
    setPhase('paused');
  }, []);

  const resumeExtraction = useCallback(() => {
    // Don't reset stream content on resume — continue from where we left off
    setPhase('extracting');
    setExtractStatus('');
    // Re-run extraction without resetting accumulated content
    const [providerId, modelId] = selectedModelKey.split('::');
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    // Abort any previous controller that may be lingering
    try {
      importFromMarkdownStream(
        selectedFileId,
        providerId || '',
        modelId || '',
        systemPrompt,
        (chunk: string) => setStreamContent(prev => prev + chunk),
        (result: OntologyExtractionResult) => {
          setExtractionResult(result);
          setPhase('done');
          const provider = activeProviders.find(p => p.id === providerId);
          const model = provider?.models?.find((m: any) => m.modelId === modelId);
          setProvenance({
            modelName: model?.name || modelId || '未知模型',
            timestamp: new Date().toISOString(),
            fileSource: mdFiles.find(f => f.id === selectedFileId)?.originalFilename || selectedFileId,
          });
        },
        (error: string) => {
          message.error('提取失败: ' + error);
          setPhase('config');
        },
        ctrl.signal,
        (status: string) => setExtractStatus(status),
      ).catch((e: any) => {
        if (e.name !== 'AbortError') {
          message.error('恢复提取失败: ' + (e.message || '未知错误'));
          setPhase('config');
        }
      });
    } catch (e: any) {
      if (e.name !== 'AbortError') {
        message.error('恢复提取失败: ' + (e.message || '未知错误'));
        setPhase('config');
      }
    }
  }, [selectedFileId, selectedModelKey, systemPrompt, mdFiles, activeProviders]);

  const abortExtraction = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setPhase('config');
  }, []);

  const saveResult = useCallback(async (): Promise<string | null> => {
    if (!extractionResult) return null;
    try {
      const saved = await saveOntologyVersion({
        name: ontologyName.trim(),
        versionName: versionName.trim(),
        description: extractionResult.description || '',
        classes: extractionResult.classes || [],
        objectProperties: extractionResult.objectProperties || [],
        dataProperties: extractionResult.dataProperties || [],
        rules: (extractionResult as any).rules || [],
        ttlContent: extractionResult.ttlContent || '',
        sourceMdFileName: provenance?.fileSource,
        sourceMdFileId: selectedFileId,
        sourceModelName: provenance?.modelName,
      });
      message.success(`本体模型保存成功，版本 ${(saved as any).versionName || versionName}`);
      return (saved as any).id || null;
    } catch (e: any) {
      message.error('保存失败: ' + (e.message || '未知错误'));
      return null;
    }
  }, [extractionResult, ontologyName, versionName, selectedFileId, provenance]);

  return {
    visible, openModal, closeModal,
    phase,
    mdFiles, mdFilesLoading,
    selectedFileId, setSelectedFileId,
    systemPrompt, setSystemPrompt,
    showPromptEditor, setShowPromptEditor,
    ontologyName, setOntologyName,
    versionName, setVersionName,
    activeProviders, selectedModelKey, setSelectedModelKey,
    streamContent, extractStatus, extractionResult,
    startExtraction, pauseExtraction, resumeExtraction, abortExtraction,
    saveResult,
    provenance,
  };
}
