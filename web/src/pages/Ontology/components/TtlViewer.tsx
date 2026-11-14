/**
 * TtlViewer — TTL 查看/编辑/导出 Modals
 */
import { getTtlContent,updateTtlContent } from '@/api/ontology';
import { EditOutlined,EyeOutlined,SaveOutlined } from '@ant-design/icons';
import { Button,Input,message,Modal,Space,Spin,Typography } from 'antd';
import React,{ useEffect,useState } from 'react';

const { Text } = Typography;
const { TextArea } = Input;

interface TtlViewerProps {
  ontologyId: string;
  mode: 'view' | 'edit';
  visible: boolean;
  onClose: () => void;
  onSaved?: () => void;
}

const TtlViewer: React.FC<TtlViewerProps> = ({ ontologyId, mode, visible, onClose, onSaved }) => {
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!visible || !ontologyId) return;
    setLoading(true);
    getTtlContent(ontologyId)
      .then((ttl: any) => setContent(typeof ttl === 'string' ? ttl : String(ttl || '')))
      .catch((e: any) => message.error('加载 TTL 失败: ' + (e.message || '未知错误')))
      .finally(() => setLoading(false));
  }, [visible, ontologyId]);

  const handleSave = async () => {
    setSaving(true);
    try {
      await updateTtlContent(ontologyId, content);
      message.success('TTL 已保存');
      onSaved?.();
      onClose();
    } catch (e: any) {
      message.error('保存 TTL 失败: ' + (e.message || '未知错误'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title={<Space>{mode === 'view' ? <EyeOutlined /> : <EditOutlined />}<span>{mode === 'view' ? '查看' : '编辑'} TTL</span></Space>}
      open={visible}
      onCancel={onClose}
      width="80%"
      style={{ top: 20 }}
      footer={mode === 'edit' ? (
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>保存</Button>
        </Space>
      ) : (
        <Button onClick={onClose}>关闭</Button>
      )}
      destroyOnHidden
    >
      <Spin spinning={loading}>
        {mode === 'edit' ? (
          <TextArea
            rows={25}
            value={content}
            onChange={e => setContent(e.target.value)}
            style={{ fontFamily: 'Consolas, Monaco, monospace', fontSize: 12 }}
          />
        ) : (
          <pre style={{
            maxHeight: '60vh', overflow: 'auto', fontSize: 12,
            fontFamily: 'Consolas, Monaco, monospace',
            background: '#fafafa', padding: 12, borderRadius: 6,
            whiteSpace: 'pre-wrap', wordBreak: 'break-all',
          }}>
            {content || '(空)'}
          </pre>
        )}
      </Spin>
    </Modal>
  );
};

export default TtlViewer;
