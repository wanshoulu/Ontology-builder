/**
 * 本体建模 — 主页面
 * 设计对齐: 文档知识模块 (LocalRagWorkflow/LocalGraphWorkflow)
 * 卡片式步骤布局 + 统计行 + 紧凑控件 + 状态标签
 */
import { exportOntology,exportOntologyMarkdown,getLinkTypes,getObjectTypes,renameOntologyVersion } from '@/api/ontology';
import {
ApartmentOutlined,
FileTextOutlined,
HistoryOutlined,
LinkOutlined,
NodeIndexOutlined,SafetyCertificateOutlined,
SwapOutlined,
ThunderboltOutlined
} from '@ant-design/icons';
import { Alert,Button,Card,Col,Input,message,Modal,Row,Segmented,Select,Space,Statistic,Tag,Typography } from 'antd';
import React,{ useCallback,useEffect,useState } from 'react';
import ChangeHistory from './ChangeHistory';
import ExtractionWizard from './components/ExtractionWizard';
import ProtegeDrawer from './components/ProtegeDrawer';
import TtlViewer from './components/TtlViewer';
import ValidatePanel from './components/ValidatePanel';
import VersionList from './components/VersionList';
import { useExtraction } from './hooks/useExtraction';
import { useOntologyGraph } from './hooks/useOntologyGraph';
import { useOntologyVersions } from './hooks/useOntologyVersions';
import VersionDiff from './VersionDiff';

const { Title, Text } = Typography;

const OntologyPage: React.FC = () => {
  // ── Hooks ──
  const versions = useOntologyVersions();
  const extraction = useExtraction();

  // ── Local state ──
  const [ttlViewMode, setTtlViewMode] = useState<'view' | 'edit'>('view');
  const [ttlViewOntologyId, setTtlViewOntologyId] = useState('');
  const [ttlViewVisible, setTtlViewVisible] = useState(false);

  const [protegeVisible, setProtegeVisible] = useState(false);
  const [protegeOntologyId, setProtegeOntologyId] = useState('');
  const [protegeName, setProtegeName] = useState('');

  const [validateVisible, setValidateVisible] = useState(false);
  const [validateOntologyId, setValidateOntologyId] = useState('');
  const [validateName, setValidateName] = useState('');
  const [validateVersion, setValidateVersion] = useState('');

  const [renameVisible, setRenameVisible] = useState(false);
  const [renameId, setRenameId] = useState('');
  const [renameName, setRenameName] = useState('');
  const [renameVersion, setRenameVersion] = useState('');
  const [renameSaving, setRenameSaving] = useState(false);

  const [diffModalOpen, setDiffModalOpen] = useState(false);
  const [diffOntologyId, setDiffOntologyId] = useState('');
  const [diffOntologyName, setDiffOntologyName] = useState('');
  const [historyModalOpen, setHistoryModalOpen] = useState(false);
  const [historyOntologyName, setHistoryOntologyName] = useState('');

  // ── Browse graph ──
  const [browseClasses, setBrowseClasses] = useState<any[]>([]);
  const [browseProperties, setBrowseProperties] = useState<any[]>([]);

  const browseGraph = useOntologyGraph({
    interactionMode: 'browse',
    onNodeClick: () => {},
  });

  // ── Computed stats ──
  const totalVersions = Object.values(versions.groupedVersions).reduce((s, g) => s + g.length, 0);
  const totalClasses = Object.values(versions.groupedVersions).flat().reduce((s, v) => s + (v.classCount || 0), 0);
  const totalProps = Object.values(versions.groupedVersions).flat().reduce((s, v) => s + (v.objectPropertyCount || 0) + (v.dataPropertyCount || 0), 0);

  // ── Handlers ──
  const handleViewTtl = useCallback((id: string) => { setTtlViewOntologyId(id); setTtlViewMode('view'); setTtlViewVisible(true); }, []);
  const handleEditTtl = useCallback((id: string) => { setTtlViewOntologyId(id); setTtlViewMode('edit'); setTtlViewVisible(true); }, []);

  const handleExportTtl = useCallback(async (version: any) => {
    try {
      const blob = await exportOntology(version.id, 'TURTLE');
      const url = URL.createObjectURL(blob as Blob);
      const a = document.createElement('a'); a.href = url;
      a.download = `${version.name || 'ontology'}_${version.versionName || 'v1'}.ttl`;
      a.click(); URL.revokeObjectURL(url);
    } catch (e: any) { message.error('导出 TTL 失败: ' + (e.message || '未知错误')); }
  }, []);

  const handleExportMd = useCallback(async (version: any) => {
    try {
      const blob = await exportOntologyMarkdown(version.id);
      const url = URL.createObjectURL(blob as Blob);
      const a = document.createElement('a'); a.href = url;
      a.download = `${version.name || 'ontology'}_${version.versionName || 'v1'}.md`;
      a.click(); URL.revokeObjectURL(url);
    } catch (e: any) { message.error('导出 Markdown 失败: ' + (e.message || '未知错误')); }
  }, []);

  const handleOpenRename = useCallback((version: any) => {
    setRenameId(version.id); setRenameName(version.name || ''); setRenameVersion(version.versionName || ''); setRenameVisible(true);
  }, []);

  const handleDoRename = useCallback(async () => {
    setRenameSaving(true);
    try {
      await renameOntologyVersion(renameId, { name: renameName, versionName: renameVersion });
      setRenameVisible(false);
      await versions.refreshVersions();
    } catch (e: any) { message.error('重命名失败: ' + (e.message || '未知错误')); }
    finally { setRenameSaving(false); }
  }, [renameId, renameName, renameVersion, versions]);

  const handleOpenValidate = useCallback((version: any) => {
    setValidateOntologyId(version.id); setValidateName(version.name || ''); setValidateVersion(version.versionName || ''); setValidateVisible(true);
  }, []);

  const handleOpenProtege = useCallback((version: any) => {
    setProtegeOntologyId(version.id); setProtegeName(version.name + ' ' + (version.versionName || '')); setProtegeVisible(true);
  }, []);

  const handleVersionSelect = useCallback(async (id: string) => {
    versions.setSelectedVersionId(id);
    try {
      const [classes, props] = await Promise.all([getObjectTypes(id), getLinkTypes(id)]);
      setBrowseClasses(classes || []); setBrowseProperties(props || []);
    } catch (e: any) { message.error('加载图谱数据失败: ' + (e.message || '未知错误')); }
  }, [versions]);

  // Render browse graph
  useEffect(() => {
    if (browseClasses.length > 0) {
      browseGraph.renderGraph(browseClasses, browseProperties, browseGraph.viewMode);
    }
  }, [browseClasses, browseProperties, browseGraph.viewMode]);

  // ── Render ──
  return (
    <div>
      {/* Page header */}
      <div style={{ marginBottom: 16 }}>
        <Title level={4} style={{ marginBottom: 4 }}>
          <ApartmentOutlined style={{ color: '#1677ff', marginRight: 8 }} />本体建模
        </Title>
        <Text type="secondary">从 Wiki 文档或 Markdown 提取 W3C OWL2 本体模型，支持可视化编辑、多版本管理和推理校验</Text>
      </div>

      {/* Stats row */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small" style={{ background: '#f6ffed', border: '1px solid #b7eb8f' }}>
            <Statistic title="本体模型" value={versions.ontologyNames.length}
              prefix={<FileTextOutlined style={{ color: '#52c41a' }} />}
              valueStyle={{ color: '#52c41a', fontSize: 22 }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" style={{ background: '#e6f4ff', border: '1px solid #91caff' }}>
            <Statistic title="版本总数" value={totalVersions}
              prefix={<HistoryOutlined style={{ color: '#1677ff' }} />}
              valueStyle={{ color: '#1677ff', fontSize: 22 }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" style={{ background: '#f9f0ff', border: '1px solid #d3adf7' }}>
            <Statistic title="类总数" value={totalClasses}
              prefix={<NodeIndexOutlined style={{ color: '#722ed1' }} />}
              valueStyle={{ color: '#722ed1', fontSize: 22 }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" style={{ background: '#fff7e6', border: '1px solid #ffd591' }}>
            <Statistic title="属性总数" value={totalProps}
              prefix={<LinkOutlined style={{ color: '#fa8c16' }} />}
              valueStyle={{ color: '#fa8c16', fontSize: 22 }} />
          </Card>
        </Col>
      </Row>

      {/* Step 1: 构建本体 */}
      <Card
        size="small" style={{ marginBottom: 16 }}
        title={<Space><ThunderboltOutlined style={{ color: '#1677ff' }} /><span>第1步：构建本体模型</span><Tag color="blue">AI 驱动</Tag></Space>}
      >
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="选择 Markdown 文档，AI 将自动分析文档结构并生成 W3C OWL2 标准本体模型（类/属性/规则/个体）" />
        <Space wrap>
          <Button type="primary" icon={<ThunderboltOutlined />} onClick={extraction.openModal} size="middle">
            Markdown → TTL 本体提取
          </Button>
          <Button icon={<HistoryOutlined />} size="small"
            onClick={() => { setHistoryOntologyName(versions.ontologyNames[0] || ''); setHistoryModalOpen(true); }}>
            变更历史
          </Button>
          <Button icon={<SwapOutlined />} size="small"
            onClick={() => {
              const name = versions.ontologyNames[0];
              if (name) {
                const group = versions.groupedVersions[name];
                if (group?.length >= 2) {
                  setDiffOntologyId(group[0].id);
                  setDiffOntologyName(name + ' ' + (group[0].versionName || '') + ' vs ' + (group[1].versionName || ''));
                } else if (group?.length > 0) {
                  setDiffOntologyId(group[0].id); setDiffOntologyName(name + ' ' + (group[0].versionName || ''));
                }
              }
              setDiffModalOpen(true);
            }}>
            版本对比
          </Button>
        </Space>
      </Card>

      {/* Step 2: 浏览 & 编辑 */}
      <Card
        size="small" style={{ marginBottom: 16 }}
        title={<Space><ApartmentOutlined style={{ color: '#722ed1' }} /><span>第2步：浏览 & 编辑本体</span><Tag color="purple">可视化</Tag></Space>}
        extra={
          <Space size={8} wrap>
            {/* Version selector */}
            <Select
              size="small"
              style={{ minWidth: 260 }}
              placeholder="选择要浏览的版本..."
              value={versions.selectedVersionId || undefined}
              onChange={handleVersionSelect}
              loading={versions.allVersionsLoading}
              showSearch
              optionFilterProp="label"
              options={Object.entries(versions.groupedVersions).flatMap(([name, group]) =>
                group.map(v => ({
                  label: `${name} — ${v.versionName || v.version || '未知版本'} (${v.classCount || 0}类)`,
                  value: v.id,
                }))
              )}
              notFoundContent={versions.allVersionsLoading ? '加载中...' : '暂无本体，请先构建'}
            />
            {versions.selectedVersionId && (
              <>
                <Button size="small" icon={<ApartmentOutlined />}
                  onClick={() => handleOpenProtege(versions.selectedVersion!)}>
                  Protégé 编辑
                </Button>
                <Button size="small" icon={<SafetyCertificateOutlined />}
                  onClick={() => handleOpenValidate(versions.selectedVersion!)}>
                  校验
                </Button>
              </>
            )}
          </Space>
        }
        styles={{ body: { padding: 0 } }}
      >
        <div style={{ display: 'flex', minHeight: 520 }}>
          {/* Left: Version list */}
          <div style={{ width: 420, flexShrink: 0, borderRight: '1px solid #f0f0f0' }}>
            <VersionList
              allVersionsLoading={versions.allVersionsLoading}
              groupedVersions={versions.groupedVersions}
              ontologyNames={versions.ontologyNames}
              selectedVersionId={versions.selectedVersionId}
              searchText={versions.searchText}
              onSearchChange={versions.setSearchText}
              filteredGroupedVersions={versions.filteredGroupedVersions}
              selectedRowKeys={versions.selectedRowKeys}
              onSelectedRowKeysChange={versions.setSelectedRowKeys}
              onVersionSelect={handleVersionSelect}
              onBatchDelete={versions.handleBatchDelete}
              onViewTtl={handleViewTtl}
              onEditTtl={handleEditTtl}
              onExportTtl={handleExportTtl}
              onExportMd={handleExportMd}
              onRename={handleOpenRename}
              onValidate={handleOpenValidate}
              onDelete={versions.handleDeleteVersion}
              onOpenProtege={handleOpenProtege}
            />
          </div>

          {/* Right: Graph preview */}
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
            <div style={{
              padding: '6px 14px', borderBottom: '1px solid #f0f0f0',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            }}>
              <Space size={4}>
                <span>📊 知识图谱</span>
                {versions.selectedVersion && (
                  <Space size={2}>
                    <Tag color="blue">{versions.selectedVersion.name}</Tag>
                    <Tag>{versions.selectedVersion.versionName}</Tag>
                    <Tag color="green">{browseClasses.length} 类</Tag>
                    <Tag color="purple">{browseProperties.length} 属性</Tag>
                  </Space>
                )}
              </Space>
              <Segmented size="small" value={browseGraph.viewMode}
                onChange={(v) => browseGraph.setViewMode(v as any)}
                options={[{ value: 'graph', label: '图谱' }, { value: 'mindmap', label: '思维导图' }]} />
            </div>
            <div style={{ flex: 1, position: 'relative' }}>
              {versions.selectedVersionId ? (
                browseClasses.length > 0 ? (
                  <div ref={browseGraph.containerRef} style={{ width: '100%', height: '100%' }} />
                ) : browseGraph.loading ? (
                  <div style={{ textAlign: 'center', padding: 80, color: '#999' }}>加载图谱中...</div>
                ) : (
                  <div style={{ textAlign: 'center', padding: 80, color: '#bbb' }}>
                    该版本暂无可视化数据
                  </div>
                )
              ) : (
                <div style={{
                  textAlign: 'center', padding: 80,
                  color: '#bbb', fontSize: 14,
                }}>
                  <ApartmentOutlined style={{ fontSize: 48, color: '#d9d9d9', marginBottom: 16, display: 'block' }} />
                  请从上方下拉框选择一个版本，或点击左侧版本列表<br />
                  <Text type="secondary" style={{ fontSize: 12 }}>选中后双击版本行可直接进入 Protégé 编辑器</Text>
                </div>
              )}
            </div>
          </div>
        </div>
      </Card>

      {/* Step 3: 校验 & 推理 */}
      <Card
        size="small" style={{ marginBottom: 16 }}
        title={<Space><SafetyCertificateOutlined style={{ color: '#52c41a' }} /><span>第3步：校验 & 推理</span><Tag color="green">质量保障</Tag></Space>}
      >
        <Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
          对本体执行五层语法校验，并可借助 HermiT / Pellet 推理器进行 OWL 2 DL 逻辑一致性验证
        </Text>
        {versions.selectedVersionId ? (
          <Button icon={<SafetyCertificateOutlined />}
            onClick={() => handleOpenValidate(versions.selectedVersion!)}>
            校验当前选中版本
          </Button>
        ) : (
          <Text type="secondary">请先在左侧版本列表中选择一个版本</Text>
        )}
      </Card>

      {/* ── Modals & Drawers ── */}

      <ExtractionWizard
        visible={extraction.visible}
        onClose={extraction.closeModal}
        phase={extraction.phase}
        mdFiles={extraction.mdFiles}
        mdFilesLoading={extraction.mdFilesLoading}
        selectedFileId={extraction.selectedFileId}
        onFileSelect={extraction.setSelectedFileId}
        activeProviders={extraction.activeProviders}
        selectedModelKey={extraction.selectedModelKey}
        onModelSelect={extraction.setSelectedModelKey}
        systemPrompt={extraction.systemPrompt}
        onPromptChange={extraction.setSystemPrompt}
        showPromptEditor={extraction.showPromptEditor}
        onTogglePromptEditor={extraction.setShowPromptEditor}
        ontologyName={extraction.ontologyName}
        onOntologyNameChange={extraction.setOntologyName}
        versionName={extraction.versionName}
        onVersionNameChange={extraction.setVersionName}
        streamContent={extraction.streamContent}
        extractStatus={extraction.extractStatus}
        extractionResult={extraction.extractionResult}
        provenance={extraction.provenance}
        onStart={extraction.startExtraction}
        onPause={extraction.pauseExtraction}
        onResume={extraction.resumeExtraction}
        onAbort={extraction.abortExtraction}
        onSave={async () => {
          const newId = await extraction.saveResult();
          if (newId) { await versions.refreshVersions(); versions.setSelectedVersionId(newId); extraction.closeModal(); }
          return newId;
        }}
      />

      <TtlViewer
        ontologyId={ttlViewOntologyId} mode={ttlViewMode} visible={ttlViewVisible}
        onClose={() => setTtlViewVisible(false)} onSaved={() => versions.refreshVersions()}
      />

      <ValidatePanel
        ontologyId={validateOntologyId} ontologyName={validateName} versionName={validateVersion}
        visible={validateVisible} onClose={() => setValidateVisible(false)}
      />

      <ProtegeDrawer
        ontologyId={protegeOntologyId} ontologyName={protegeName} visible={protegeVisible}
        onClose={() => setProtegeVisible(false)} onDataChange={() => versions.refreshVersions()}
      />

      {/* Rename Modal */}
      <Modal title="重命名版本" open={renameVisible} onOk={handleDoRename}
        onCancel={() => setRenameVisible(false)} confirmLoading={renameSaving}>
        <div style={{ marginBottom: 12 }}>
          <Text strong>名称</Text>
          <Input style={{ marginTop: 4 }} value={renameName} onChange={e => setRenameName(e.target.value)} />
        </div>
        <div>
          <Text strong>版本名称</Text>
          <Input style={{ marginTop: 4 }} value={renameVersion} onChange={e => setRenameVersion(e.target.value)} />
        </div>
      </Modal>

      <VersionDiff ontologyId={diffOntologyId} ontologyName={diffOntologyName} open={diffModalOpen} onClose={() => setDiffModalOpen(false)} />
      <ChangeHistory ontologyName={historyOntologyName} open={historyModalOpen} onClose={() => setHistoryModalOpen(false)} />
    </div>
  );
};

export default OntologyPage;
