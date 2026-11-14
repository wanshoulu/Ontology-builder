/**
 * 本体管理 (Ontology Manager) 主页面
 *
 * 参考 Palantir Ontology Manager 的三位一体结构：
 * - Object Types: 本体对象运维总览
 * - Link Types: 关系类型管理
 * - Logic Rules: SWRL 规则可视化配置
 * - Action Types: 动作类型管理
 */
import { getOntologyList } from '@/api/ontology';
import { getRulesByOntology,getSwrlVocabulary,syncSwrlRules } from '@/api/rule';
import type { BusinessRule,Ontology,SwrlVocabulary } from '@/types';
import {
ApartmentOutlined,
CodeOutlined,
CompassOutlined,
LinkOutlined,
SafetyCertificateOutlined,
TagsOutlined
} from '@ant-design/icons';
import { Empty,Layout,Menu,message,Select,Spin,Tag,Typography } from 'antd';
import React,{ useCallback,useEffect,useState } from 'react';
import { useLocation,useNavigate } from 'react-router-dom';
import DiscoverView from './components/DiscoverView';
import LinkTypesView from './components/LinkTypesView';
import LogicRulesView from './components/LogicRulesView';
import ObjectListView from './components/ObjectListView';
import ShaclView from './components/ShaclView';
import SkosView from './components/SkosView';
// TODO: 动作管理暂时屏蔽，后续开启
// import ActionsView from './components/ActionsView';

const { Sider, Content } = Layout;
const { Title, Text } = Typography;

type NavKey = 'discover' | 'objects' | 'links' | 'logic' | 'actions' | 'shacl' | 'skos';

interface NavItem {
  key: NavKey;
  icon: React.ReactNode;
  label: string;
}

const NAV_ITEMS: NavItem[] = [
  { key: 'discover', icon: <CompassOutlined />, label: '总览' },
  { key: 'objects', icon: <ApartmentOutlined />, label: '对象类型' },
  { key: 'links', icon: <LinkOutlined />, label: '关系类型' },
  { key: 'logic', icon: <CodeOutlined />, label: '逻辑规则' },
  // TODO: 动作管理暂时屏蔽
  // { key: 'actions', icon: <ThunderboltOutlined />, label: '动作' },
  { key: 'shacl', icon: <SafetyCertificateOutlined />, label: 'SHACL校验' },
  { key: 'skos', icon: <TagsOutlined />, label: 'SKOS词表' },
];

const OntologyManager: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();

  // 本体模型选择
  const [ontologies, setOntologies] = useState<Ontology[]>([]);
  const [selectedOntologyId, setSelectedOntologyId] = useState<string | null>(null);
  const [ontologiesLoading, setOntologiesLoading] = useState(false);

  // 导航
  const [activeNav, setActiveNav] = useState<NavKey>('discover');

  // 数据
  const [vocabulary, setVocabulary] = useState<SwrlVocabulary | null>(null);
  const [rules, setRules] = useState<BusinessRule[]>([]);
  const [loading, setLoading] = useState(false);

  // 加载本体列表
  useEffect(() => {
    setOntologiesLoading(true);
    getOntologyList()
      .then((list: Ontology[]) => {
        setOntologies(Array.isArray(list) ? list : []);
      })
      .catch(() => message.error('加载本体列表失败'))
      .finally(() => setOntologiesLoading(false));
  }, []);

  // 选中本体后加载数据
  const handleOntologyChange = useCallback(async (ontologyId: string) => {
    setSelectedOntologyId(ontologyId);
    setLoading(true);
    try {
      // 先同步 TTL 中的 SWRL 规则到 DB
      await syncSwrlRules(ontologyId).catch(() => {});
      // 并行加载词汇表和规则列表
      const [vocab, ruleList] = await Promise.all([
        getSwrlVocabulary(ontologyId),
        getRulesByOntology(ontologyId),
      ]);
      setVocabulary(vocab);
      setRules(ruleList || []);
    } catch (e) {
      message.error('加载本体数据失败');
    } finally {
      setLoading(false);
    }
  }, []);

  // 刷新词汇表和规则（用于子组件增删改后的数据同步）
  const handleRefreshData = useCallback(async () => {
    if (!selectedOntologyId) return;
    try {
      const [vocab, ruleList] = await Promise.all([
        getSwrlVocabulary(selectedOntologyId),
        getRulesByOntology(selectedOntologyId),
      ]);
      setVocabulary(vocab);
      setRules(ruleList || []);
    } catch (e) {
      message.error('刷新数据失败');
    }
  }, [selectedOntologyId]);

  // 导航点击
  const handleNavClick = (key: NavKey) => {
    setActiveNav(key);
  };

  // 渲染右侧内容区
  const renderContent = () => {
    if (!selectedOntologyId) {
      return (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
          <Empty description={
            <span>
              请先在上方选择一个本体模型<br />
              <Text type="secondary">选择一个本体模型以开始管理其对象类型、关系类型和逻辑规则</Text>
            </span>
          } />
        </div>
      );
    }

    if (loading) {
      return (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
          <Spin size="large" tip="加载中..." />
        </div>
      );
    }

    switch (activeNav) {
      case 'discover':
        return (
          <DiscoverView
            ontologyId={selectedOntologyId}
            vocabulary={vocabulary}
            rules={rules}
            onNavigate={(key: NavKey) => setActiveNav(key)}
          />
        );
      case 'objects':
        return (
          <ObjectListView
            ontologyId={selectedOntologyId}
            vocabulary={vocabulary}
            rules={rules}
            onRefresh={handleRefreshData}
          />
        );
      case 'links':
        return <LinkTypesView vocabulary={vocabulary} ontologyId={selectedOntologyId} onRefresh={handleRefreshData} />;
      case 'logic':
        return (
          <LogicRulesView
            ontologyId={selectedOntologyId}
            vocabulary={vocabulary}
            rules={rules}
            onRulesChange={setRules}
          />
        );
      // TODO: 动作管理暂时屏蔽
      // case 'actions':
      //   return (
      //     <ActionsView
      //       ontologyId={selectedOntologyId}
      //       vocabulary={vocabulary}
      //       rules={rules}
      //       onRulesChange={setRules}
      //     />
      //   );
      case 'shacl':
        return (
          <ShaclView
            ontologyId={selectedOntologyId}
            vocabulary={vocabulary}
            rules={rules}
          />
        );
      case 'skos':
        return (
          <SkosView
            ontologyId={selectedOntologyId}
            vocabulary={vocabulary}
          />
        );
      default:
        return null;
    }
  };

  const selectedOntology = ontologies.find((o) => o.id === selectedOntologyId);

  return (
    <div style={{ height: 'calc(100vh - 64px)', display: 'flex', flexDirection: 'column' }}>
      {/* 页面标题 */}
      <div style={{ padding: '16px 24px 0' }}>
        <Title level={4} style={{ margin: 0, marginBottom: 4 }}>
          <ApartmentOutlined style={{ color: '#1677ff', marginRight: 8 }} />本体管理
        </Title>
        <Text type="secondary">管理本体模型的对象类型、关系类型、逻辑规则，支持 SHACL 校验和 SKOS 词表浏览</Text>
      </div>
      <Layout style={{ flex: 1, background: '#fff', marginTop: 16 }}>
      {/* 左侧边栏 */}
      <Sider
        width={240}
        style={{
          background: '#fafafa',
          borderRight: '1px solid #f0f0f0',
          overflow: 'auto',
        }}
      >
        {/* 本体选择器 */}
        <div style={{ padding: '16px 16px 8px' }}>
          <Text type="secondary" style={{ fontSize: 12, marginBottom: 4, display: 'block' }}>
            选择本体模型
          </Text>
          <Select
            placeholder="选择本体模型..."
            style={{ width: '100%' }}
            value={selectedOntologyId}
            onChange={handleOntologyChange}
            loading={ontologiesLoading}
            showSearch
            optionFilterProp="label"
            options={ontologies.map((o) => ({
              label: o.versionName ? `${o.name} (${o.versionName})` : o.name,
              value: o.id,
            }))}
            notFoundContent={ontologiesLoading ? <Spin size="small" /> : '无本体'}
          />
        </div>

        {/* 选中的本体信息 */}
        {selectedOntology && (
          <div style={{ padding: '8px 16px 12px', borderBottom: '1px solid #f0f0f0' }}>
            <Text strong style={{ fontSize: 13 }}>{selectedOntology.name}</Text>
            <br />
            <Tag color="blue" style={{ marginTop: 4 }}>
              {selectedOntology.versionName || `v${selectedOntology.version || '1.0'}`}
            </Tag>
          </div>
        )}

        {/* 导航菜单 */}
        <Menu
          mode="inline"
          selectedKeys={[activeNav]}
          onClick={({ key }) => handleNavClick(key as NavKey)}
          style={{ background: 'transparent', borderRight: 0, marginTop: 8 }}
          items={NAV_ITEMS.map((item) => ({
            key: item.key,
            icon: item.icon,
            label: item.label,
          }))}
        />
      </Sider>

      {/* 右侧内容区 */}
      <Content
        style={{
          overflow: 'auto',
          background: '#fff',
        }}
      >
        {renderContent()}
      </Content>
    </Layout>
    </div>
  );
};

export default OntologyManager;
