/**
 * useValidation — 本体校验 + 推理状态 hook
 *
 * 管理: 五层校验、HermiT/Pellet 推理、auto-fix
 */
import {
autoFixOntology,
hermitReason,pelletReason,
validateOntology,
} from '@/api/ontology';
import { message } from 'antd';
import { useCallback,useState } from 'react';

export type ValidateActiveTab = 'validate' | 'hermit' | 'pellet' | 'protege';

interface UseValidationOptions {
  ontologyId: string;
  ontologyName: string;
  versionName: string;
}

export function useValidation(options: UseValidationOptions) {
  const { ontologyId, ontologyName, versionName } = options;

  const [visible, setVisible] = useState(false);
  const [activeTab, setActiveTab] = useState<ValidateActiveTab>('validate');

  // Validate
  const [validateLoading, setValidateLoading] = useState(false);
  const [validateResult, setValidateResult] = useState<any>(null);

  // Auto-fix
  const [autoFixVisible, setAutoFixVisible] = useState(false);
  const [autoFixLoading, setAutoFixLoading] = useState(false);
  const [selectedFixIndices, setSelectedFixIndices] = useState<number[]>([]);
  const [selectedWarningIndices, setSelectedWarningIndices] = useState<number[]>([]);

  // HermiT
  const [hermitLoading, setHermitLoading] = useState(false);
  const [hermitResult, setHermitResult] = useState<any>(null);

  // Pellet
  const [pelletLoading, setPelletLoading] = useState(false);
  const [pelletResult, setPelletResult] = useState<any>(null);

  // SPARQL timing
  const [sparqlTiming, setSparqlTiming] = useState<number | null>(null);

  const openModal = useCallback(() => {
    setVisible(true);
    setActiveTab('validate');
    setValidateResult(null);
    setHermitResult(null);
    setPelletResult(null);
    setSparqlTiming(null);
  }, []);

  const closeModal = useCallback(() => {
    setVisible(false);
  }, []);

  const handleValidate = useCallback(async () => {
    setValidateLoading(true);
    setValidateResult(null);
    try {
      const report = await validateOntology(ontologyId);
      setValidateResult(report);
      const errorCount = report?.errors?.length || 0;
      const warnCount = report?.warnings?.length || 0;
      if (errorCount === 0 && warnCount === 0) {
        message.success('本体通过全部五层校验');
      } else {
        message.warning(`校验完成: ${errorCount} 个错误, ${warnCount} 个警告`);
      }
    } catch (e: any) {
      message.error('校验失败: ' + (e.message || '未知错误'));
    } finally {
      setValidateLoading(false);
    }
  }, [ontologyId]);

  const handleAutoFix = useCallback(async () => {
    setAutoFixLoading(true);
    try {
      const result = await autoFixOntology(ontologyId, selectedFixIndices, selectedWarningIndices);
      message.success(`自动修复完成: ${result.fixed || 0} 项已修复`);
      setAutoFixVisible(false);
      await handleValidate();
    } catch (e: any) {
      message.error('自动修复失败: ' + (e.message || '未知错误'));
    } finally {
      setAutoFixLoading(false);
    }
  }, [ontologyId, selectedFixIndices, selectedWarningIndices, handleValidate]);

  const handleHermitReason = useCallback(async () => {
    setHermitLoading(true);
    setHermitResult(null);
    try {
      const result = await hermitReason(ontologyId);
      setHermitResult(result);
      message.success('HermiT 推理完成');
    } catch (e: any) {
      message.error('HermiT 推理失败: ' + (e.message || '未知错误'));
      setHermitResult({ error: true, message: e.message });
    } finally {
      setHermitLoading(false);
    }
  }, [ontologyId]);

  const handlePelletReason = useCallback(async () => {
    setPelletLoading(true);
    setPelletResult(null);
    try {
      const result = await pelletReason(ontologyId);
      setPelletResult(result);
      message.success('Pellet 推理完成');
    } catch (e: any) {
      message.error('Pellet 推理失败: ' + (e.message || '未知错误'));
      setPelletResult({ error: true, message: e.message });
    } finally {
      setPelletLoading(false);
    }
  }, [ontologyId]);

  return {
    // Modal
    visible, openModal, closeModal,
    activeTab, setActiveTab,
    target: { id: ontologyId, name: ontologyName, versionName },
    // Validate
    validateLoading, validateResult, handleValidate,
    // Auto-fix
    autoFixVisible, setAutoFixVisible,
    autoFixLoading,
    selectedFixIndices, setSelectedFixIndices,
    selectedWarningIndices, setSelectedWarningIndices,
    handleAutoFix,
    // HermiT
    hermitLoading, hermitResult, handleHermitReason,
    // Pellet
    pelletLoading, pelletResult, handlePelletReason,
    // SPARQL timing
    sparqlTiming, setSparqlTiming,
  };
}
