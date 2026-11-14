/**
 * ActionsView — 动作管理（Palantir 风格）
 * 当前阶段：规划中，未来将支持 Form/Rules/Submission Criteria 完整配置
 * 基础的条件/事件/时序/复合类型规则已移至「逻辑规则」模块统一管理
 */
import {
ApartmentOutlined,
ApiOutlined,
BellOutlined,
FormOutlined,
ThunderboltOutlined,
} from '@ant-design/icons';
import { Card,Empty,Timeline,Typography } from 'antd';
import React from 'react';

const { Title, Text, Paragraph } = Typography;

interface ActionsViewProps {
  ontologyId: string;
  vocabulary: any;
  rules: any[];
  onRulesChange: (rules: any[]) => void;
}

const ActionsView: React.FC<ActionsViewProps> = () => {
  return (
    <div style={{ padding: 24 }}>
      <Title level={4} style={{ marginBottom: 16 }}>
        动作管理
      </Title>

      <Card style={{ marginBottom: 24 }}>
        <Empty
          image={<ThunderboltOutlined style={{ fontSize: 48, color: '#fa8c16' }} />}
          description={
            <div>
              <Text strong>规划中</Text>
              <Paragraph type="secondary" style={{ marginTop: 8, maxWidth: 500, margin: '8px auto 0' }}>
                动作管理将提供完整的本体写操作定义能力，包括参数表单、提交条件、规则块编辑和副作用配置。
              </Paragraph>
            </div>
          }
        />
      </Card>

      <Card title="规划功能" size="small">
        <Timeline
          items={[
            {
              color: 'green',
              dot: <FormOutlined />,
              children: (
                <div>
                  <Text strong>参数定义 (Form)</Text>
                  <br />
                  <Text type="secondary">定义 Action 输入参数：名称、类型、必填/可选、可见性、值来源</Text>
                </div>
              ),
            },
            {
              color: 'blue',
              dot: <ApartmentOutlined />,
              children: (
                <div>
                  <Text strong>规则块编辑 (Rules)</Text>
                  <br />
                  <Text type="secondary">可组合的本体编辑块：Modify/Create/Upsert/Delete Object + Function + Webhook + Notification</Text>
                </div>
              ),
            },
            {
              color: 'orange',
              dot: <ApiOutlined />,
              children: (
                <div>
                  <Text strong>提交条件 (Submission Criteria)</Text>
                  <br />
                  <Text type="secondary">定义 Action 可提交的前置条件构建器</Text>
                </div>
              ),
            },
            {
              color: 'purple',
              dot: <BellOutlined />,
              children: (
                <div>
                  <Text strong>副作用 & 测试</Text>
                  <br />
                  <Text type="secondary">通知/Webhook 副作用配置 + 模拟执行 & 预览</Text>
                </div>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
};

export default ActionsView;
