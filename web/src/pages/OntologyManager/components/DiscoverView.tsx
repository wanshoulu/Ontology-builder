/**
 * Discover 首页 — 本体管理总览仪表盘
 * 参考 Palantir OMA 的 Discover View：统计卡片 + 最近修改 + 快捷操作
 */
import type { BusinessRule,SwrlVocabulary } from '@/types';
import {
ApartmentOutlined,
CodeOutlined,
LinkOutlined,
PlusOutlined
} from '@ant-design/icons';
import { Button,Card,Col,Row,Space,Statistic,Typography } from 'antd';
import React from 'react';

const { Title } = Typography;

interface DiscoverViewProps {
  ontologyId: string;
  vocabulary: SwrlVocabulary | null;
  rules: BusinessRule[];
  onNavigate: (key: 'objects' | 'links' | 'logic') => void;
}

const DiscoverView: React.FC<DiscoverViewProps> = ({
  ontologyId,
  vocabulary,
  rules,
  onNavigate,
}) => {
  const classCount = vocabulary?.classes?.length || 0;
  const linkCount = vocabulary?.objectProperties?.length || 0;
  const logicCount = rules.length;

  const statCards = [
    {
      title: '对象类型',
      value: classCount,
      icon: <ApartmentOutlined />,
      color: '#1677ff',
      nav: 'objects' as const,
    },
    {
      title: '关系类型',
      value: linkCount,
      icon: <LinkOutlined />,
      color: '#52c41a',
      nav: 'links' as const,
    },
    {
      title: '逻辑规则',
      value: logicCount,
      icon: <CodeOutlined />,
      color: '#722ed1',
      nav: 'logic' as const,
    },
    // TODO: 动作管理暂时屏蔽
    // {
    //   title: '动作',
    //   value: 0,
    //   icon: <ThunderboltOutlined />,
    //   color: '#fa8c16',
    //   nav: 'actions' as const,
    // },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Title level={4} style={{ marginBottom: 24 }}>
        本体管理总览
      </Title>

      {/* 统计卡片 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        {statCards.map((card) => (
          <Col xs={24} sm={12} md={8} key={card.title}>
            <Card
              hoverable
              onClick={() => onNavigate(card.nav)}
              style={{ borderTop: `3px solid ${card.color}` }}
            >
              <Statistic
                title={card.title}
                value={card.value}
                prefix={card.icon}
                valueStyle={{ color: card.color }}
              />
            </Card>
          </Col>
        ))}
      </Row>

      {/* 快捷操作 */}
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card title="快捷操作" size="small">
            <Space direction="vertical" style={{ width: '100%' }}>
              <Button block icon={<PlusOutlined />} onClick={() => onNavigate('logic')}>
                新建逻辑规则
              </Button>
              <Button block icon={<ApartmentOutlined />} onClick={() => onNavigate('objects')}>
                浏览对象类型
              </Button>
              <Button block icon={<LinkOutlined />} onClick={() => onNavigate('links')}>
                浏览关系类型
              </Button>
            </Space>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default DiscoverView;
