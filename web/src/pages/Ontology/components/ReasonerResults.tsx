/**
 * ReasonerResults — 共享推理结果展示组件
 *
 * 统一 HermiT 和 Pellet 的结果渲染（消除原有 ~500 行复制粘贴）
 * 展示 6 张能力卡片: 一致性/层次结构/实例分类/属性推理/数据属性/SWRL规则
 */
import {
ApartmentOutlined,
CheckCircleOutlined,CloseCircleOutlined,
LinkOutlined,ThunderboltOutlined,
} from '@ant-design/icons';
import { Alert,Card,Col,Empty,Row,Spin,Tag,Typography } from 'antd';
import React from 'react';

const { Text, Paragraph } = Typography;

interface ReasonerCapability {
  key: string;
  title: string;
  icon: React.ReactNode;
  passed: boolean;
  details?: string;
  items?: { label: string; value: string }[];
}

interface Props {
  reasonerName: string;
  loading: boolean;
  result: any;
  color?: string;
}

const ReasonerResults: React.FC<Props> = ({ reasonerName, loading, result, color = '#1677ff' }) => {
  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 40 }}>
        <Spin tip={`${reasonerName} 推理中...`}>
          <div style={{ padding: 20 }} />
        </Spin>
      </div>
    );
  }

  if (!result) {
    return <Empty description={`尚未执行 ${reasonerName} 推理`} />;
  }

  if (result.error) {
    return (
      <Alert
        type="error"
        showIcon
        message={`${reasonerName} 推理失败`}
        description={result.message || result.error || '未知错误'}
      />
    );
  }

  const capabilities: ReasonerCapability[] = [
    {
      key: 'consistency',
      title: '一致性检查',
      icon: <CheckCircleOutlined />,
      passed: result.consistent !== false,
      details: result.consistent ? '本体逻辑一致，无矛盾' : '检测到逻辑矛盾',
    },
    {
      key: 'hierarchy',
      title: '层次结构推理',
      icon: <ApartmentOutlined />,
      passed: !result.hierarchyErrors?.length,
      details: result.hierarchyErrors?.length
        ? `发现 ${result.hierarchyErrors.length} 个层次结构问题`
        : '类层次结构正确',
      items: result.hierarchyErrors?.slice(0, 5).map((e: any) => ({
        label: typeof e === 'string' ? '问题' : (e.class || ''),
        value: typeof e === 'string' ? e : (e.message || JSON.stringify(e)),
      })),
    },
    {
      key: 'instances',
      title: '实例分类',
      icon: <ApartmentOutlined />,
      passed: result.instancesClassified !== false,
      details: result.instanceCount
        ? `成功分类 ${result.instanceCount} 个个体`
        : '个体分类正常',
    },
    {
      key: 'properties',
      title: '属性推理',
      icon: <LinkOutlined />,
      passed: !result.propertyErrors?.length,
      details: result.propertyErrors?.length
        ? `发现 ${result.propertyErrors.length} 个属性问题`
        : '属性推理正常',
      items: result.propertyErrors?.slice(0, 5).map((e: any) => ({
        label: typeof e === 'string' ? '问题' : (e.property || ''),
        value: typeof e === 'string' ? e : (e.message || JSON.stringify(e)),
      })),
    },
    {
      key: 'dataProperties',
      title: '数据属性推理',
      icon: <LinkOutlined />,
      passed: !result.dataPropertyErrors?.length,
      details: result.dataPropertyErrors?.length
        ? `发现 ${result.dataPropertyErrors.length} 个数据属性问题`
        : '数据属性推理正常',
    },
    {
      key: 'swrl',
      title: 'SWRL 规则推理',
      icon: <ThunderboltOutlined />,
      passed: !result.ruleErrors?.length,
      details: result.ruleCount
        ? `成功执行 ${result.ruleCount} 条规则`
        : result.ruleErrors?.length
          ? `${result.ruleErrors.length} 条规则执行异常`
          : '无 SWRL 规则或推理正常',
      items: result.ruleErrors?.slice(0, 5).map((e: any) => ({
        label: typeof e === 'string' ? '问题' : (e.rule || ''),
        value: typeof e === 'string' ? e : (e.message || JSON.stringify(e)),
      })),
    },
  ];

  return (
    <div>
      {/* Summary bar */}
      <div style={{
        padding: '8px 14px', background: '#f6ffed', borderRadius: 6,
        marginBottom: 12, border: '1px solid #b7eb8f',
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
      }}>
        <Text strong style={{ color: '#389e0d' }}>
          <CheckCircleOutlined style={{ marginRight: 6 }} />
          {reasonerName} OWL 2 DL 推理完成
        </Text>
        <Tag color={result.consistent !== false ? 'green' : 'red'}>
          {result.consistent !== false ? '一致' : '矛盾'}
        </Tag>
      </div>

      {/* Capability cards */}
      <Row gutter={[12, 12]}>
        {capabilities.map(cap => (
          <Col key={cap.key} span={8}>
            <Card
              size="small"
              style={{
                borderLeft: `3px solid ${cap.passed ? '#52c41a' : '#ff4d4f'}`,
                height: '100%',
              }}
              title={
                <span style={{ fontSize: 12 }}>
                  {cap.passed
                    ? <CheckCircleOutlined style={{ color: '#52c41a', marginRight: 4 }} />
                    : <CloseCircleOutlined style={{ color: '#ff4d4f', marginRight: 4 }} />
                  }
                  {cap.title}
                </span>
              }
            >
              <Paragraph style={{ fontSize: 12, marginBottom: 4, color: '#666' }}>
                {cap.details}
              </Paragraph>
              {cap.items && cap.items.length > 0 && (
                <div style={{ maxHeight: 150, overflow: 'auto' }}>
                  {cap.items.map((item, i) => (
                    <div key={i} style={{
                      fontSize: 11, padding: '4px 6px', marginBottom: 3,
                      background: '#fff2f0', borderRadius: 4, border: '1px solid #ffccc7',
                    }}>
                      {item.label && <Text strong style={{ fontSize: 10, color: '#cf1322' }}>{item.label}: </Text>}
                      <Text style={{ fontSize: 10 }}>{item.value}</Text>
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </Col>
        ))}
      </Row>

      {/* Full result detail */}
      {result.fullDetail && (
        <Card size="small" style={{ marginTop: 12, background: '#fafafa' }}
          title={<Text style={{ fontSize: 12 }}>📋 完整推理输出</Text>}>
          <pre style={{
            fontSize: 11, lineHeight: 1.5, maxHeight: 300, overflow: 'auto',
            whiteSpace: 'pre-wrap', wordBreak: 'break-all',
          }}>
            {typeof result.fullDetail === 'string'
              ? result.fullDetail
              : JSON.stringify(result.fullDetail, null, 2)}
          </pre>
        </Card>
      )}
    </div>
  );
};

export default ReasonerResults;
