/**
 * ValidatePanel — 本体校验面板
 *
 * Tab: 五层校验 / HermiT推理 / Pellet推理
 * 使用共享 ReasonerResults 组件 (消除 500 行重复)
 */
import {
SafetyCertificateOutlined,ThunderboltOutlined
} from '@ant-design/icons';
import {
Alert,
Button,
Checkbox,
Modal,
Space,Steps,
Tabs,
Tag,Typography
} from 'antd';
import React from 'react';
import { useValidation } from '../hooks/useValidation';
import ReasonerResults from './ReasonerResults';

const { Text } = Typography;

interface ValidatePanelProps {
  ontologyId: string;
  ontologyName: string;
  versionName: string;
  visible: boolean;
  onClose: () => void;
}

const ValidatePanel: React.FC<ValidatePanelProps> = ({
  ontologyId, ontologyName, versionName, visible, onClose,
}) => {
  const v = useValidation({ ontologyId, ontologyName, versionName });

  // open when visible changes
  React.useEffect(() => {
    if (visible && ontologyId) {
      // update internal state
    }
  }, [visible, ontologyId]);

  const validateLayers = [
    { title: '语法校验', description: 'TTL 语法正确性' },
    { title: 'OWL2 DL 校验', description: '本体一致性检查' },
    { title: '命名空间校验', description: 'IRI 命名空间检查' },
    { title: '实体引用校验', description: '所有引用实体存在' },
    { title: '逻辑一致性', description: '无逻辑矛盾' },
  ];

  return (
    <Modal
      title={
        <Space>
          <SafetyCertificateOutlined style={{ color: '#1677ff' }} />
          <span>本体校验 & 推理 — {ontologyName} {versionName}</span>
        </Space>
      }
      open={visible}
      onCancel={onClose}
      width="85%"
      style={{ top: 20 }}
      footer={null}
      destroyOnHidden
    >
      <Tabs
        activeKey={v.activeTab}
        onChange={(key) => v.setActiveTab(key as any)}
        items={[
          {
            key: 'validate',
            label: <span><SafetyCertificateOutlined /> 五层校验</span>,
            children: (
              <div>
                {/* Steps indicator */}
                <Steps
                  size="small"
                  current={v.validateResult ? 5 : -1}
                  items={validateLayers.map((l, i) => ({
                    title: l.title,
                    description: l.description,
                    status: v.validateResult
                      ? (v.validateResult?.errors?.length === 0 ? 'finish' : 'error') as any
                      : 'wait' as any,
                  }))}
                  style={{ marginBottom: 16 }}
                />

                {/* Action buttons */}
                <Space style={{ marginBottom: 16 }}>
                  <Button type="primary" icon={<ThunderboltOutlined />} loading={v.validateLoading}
                    onClick={v.handleValidate}>
                    执行五层校验
                  </Button>
                </Space>

                {/* Results */}
                {v.validateResult && (
                  <div>
                    {/* Summary */}
                    <div style={{ marginBottom: 12, display: 'flex', gap: 8 }}>
                      <Tag color={v.validateResult.errors?.length ? 'red' : 'green'}>
                        {v.validateResult.errors?.length || 0} 错误
                      </Tag>
                      <Tag color={v.validateResult.warnings?.length ? 'orange' : 'green'}>
                        {v.validateResult.warnings?.length || 0} 警告
                      </Tag>
                      <Tag color="blue">{v.validateResult.infos?.length || 0} 提示</Tag>
                    </div>

                    {/* Errors */}
                    {v.validateResult.errors?.map((e: any, i: number) => (
                      <Alert key={`err-${i}`} type="error" showIcon style={{ marginBottom: 4 }}
                        message={typeof e === 'string' ? e : (e.message || JSON.stringify(e))}
                        action={
                          <Checkbox
                            checked={v.selectedFixIndices.includes(i)}
                            onChange={(ev) => {
                              if (ev.target.checked) v.setSelectedFixIndices([...v.selectedFixIndices, i]);
                              else v.setSelectedFixIndices(v.selectedFixIndices.filter(x => x !== i));
                            }}
                          >
                            修复此项
                          </Checkbox>
                        }
                      />
                    ))}

                    {/* Warnings */}
                    {v.validateResult.warnings?.map((w: any, i: number) => (
                      <Alert key={`warn-${i}`} type="warning" showIcon style={{ marginBottom: 4 }}
                        message={typeof w === 'string' ? w : (w.message || JSON.stringify(w))}
                      />
                    ))}

                    {/* Auto-fix button */}
                    {v.selectedFixIndices.length > 0 && (
                      <Button type="primary" icon={<ThunderboltOutlined />}
                        loading={v.autoFixLoading} onClick={v.handleAutoFix}
                        style={{ marginTop: 8 }}>
                        自动修复 ({v.selectedFixIndices.length} 项)
                      </Button>
                    )}

                    {/* No issues */}
                    {!v.validateResult.errors?.length && !v.validateResult.warnings?.length && (
                      <Alert type="success" showIcon message="本体通过全部五层校验！" />
                    )}
                  </div>
                )}
              </div>
            ),
          },
          {
            key: 'hermit',
            label: <span><ThunderboltOutlined /> HermiT 推理</span>,
            children: (
              <div>
                <Button type="primary" icon={<ThunderboltOutlined />}
                  loading={v.hermitLoading} onClick={v.handleHermitReason}
                  style={{ marginBottom: 16 }}>
                  执行 HermiT OWL 2 DL 推理验证
                </Button>
                <ReasonerResults reasonerName="HermiT" loading={v.hermitLoading} result={v.hermitResult} color="#1677ff" />
              </div>
            ),
          },
          {
            key: 'pellet',
            label: <span><ThunderboltOutlined /> Pellet 推理</span>,
            children: (
              <div>
                <Button type="primary" icon={<ThunderboltOutlined />}
                  loading={v.pelletLoading} onClick={v.handlePelletReason}
                  style={{ marginBottom: 16 }}>
                  执行 Pellet OWL 2 DL 推理验证
                </Button>
                <ReasonerResults reasonerName="Pellet" loading={v.pelletLoading} result={v.pelletResult} color="#722ed1" />
              </div>
            ),
          },
        ]}
      />
    </Modal>
  );
};

export default ValidatePanel;
