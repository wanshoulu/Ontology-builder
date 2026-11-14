/**
 * VisualComposer — 可视化 SWRL Atom 构建器
 * 提供 Body(条件) / Head(结论) 两区域的 Atom 拖拽式组合
 */
import type { SwrlAtom,SwrlVocabulary } from '@/types';
import { ArrowDownOutlined,PlusOutlined } from '@ant-design/icons';
import { Alert,Button,Empty,Space,Tag,Typography } from 'antd';
import React,{ useMemo } from 'react';
import AtomRow from '../atoms/AtomRow';

const { Text } = Typography;

interface VisualComposerProps {
  bodyAtoms: SwrlAtom[];
  headAtoms: SwrlAtom[];
  vocabulary: SwrlVocabulary;
  onChangeBody: (atoms: SwrlAtom[]) => void;
  onChangeHead: (atoms: SwrlAtom[]) => void;
}

// 将 atoms 编译为 SWRL 字符串（供外部使用）
export function atomsToSwrl(bodyAtoms: SwrlAtom[], headAtoms: SwrlAtom[]): string {
  const renderAtom = (a: SwrlAtom): string => {
    switch (a.type) {
      case 'CLASS':
        return `${a.className || '?'}(?${a.variable1 || 'x'})`;
      case 'OBJECT_PROPERTY':
        return `${a.propertyName || '?'}(?${a.variable1 || 'x'}, ?${a.variable2 || 'y'})`;
      case 'DATA_PROPERTY':
        if (a.variable2Type === 'LITERAL') {
          const val = a.variable2 || '';
          const isNum = /^-?\d+(\.\d+)?$/.test(val);
          return `${a.propertyName || '?'}(?${a.variable1 || 'x'}, ${isNum ? val : `"${val}"`})`;
        }
        return `${a.propertyName || '?'}(?${a.variable1 || 'x'}, ?${a.variable2 || 'v'})`;
      case 'BUILTIN':
        const args = (a.args || []).map((arg) =>
          arg.type === 'VARIABLE' ? `?${arg.value}` : (() => {
            const isNum = /^-?\d+(\.\d+)?$/.test(arg.value);
            return isNum ? arg.value : `"${arg.value}"`;
          })()
        ).join(', ');
        return `swrlb:${a.builtinName || '?'}(${args})`;
      case 'SAME_AS':
        return `SameAs(?${a.variable1 || 'a'}, ?${a.variable2 || 'b'})`;
      case 'DIFFERENT_FROM':
        return `DifferentFrom(?${a.variable1 || 'a'}, ?${a.variable2 || 'b'})`;
      default:
        return '';
    }
  };

  const body = bodyAtoms.map(renderAtom).filter(Boolean).join(' ^ ');
  const head = headAtoms.map(renderAtom).filter(Boolean).join(' ^ ');
  return `${body} -> ${head}`;
}

// 从 SWRL 字符串反向解析为 atoms
export function swrlToAtoms(swrl: string): { body: SwrlAtom[]; head: SwrlAtom[] } {
  const result: { body: SwrlAtom[]; head: SwrlAtom[] } = { body: [], head: [] };
  if (!swrl || !swrl.includes('->')) return result;

  const [bodyStr, headStr] = swrl.split('->').map((s) => s.trim());
  let id = 0;

  // 解析 body
  const bodyAtoms = bodyStr.split('^').map((s) => s.trim()).filter(Boolean);
  for (const atomStr of bodyAtoms) {
    // Class: Name(?var)
    let m = atomStr.match(/^(\w+)\(\?(\w+)\)$/);
    if (m) {
      result.body.push({ id: `b${++id}`, type: 'CLASS', className: m[1], variable1: m[2] });
      continue;
    }
    // Builtin: swrlb:name(args)
    m = atomStr.match(/^swrlb:(\w+)\((.+)\)$/);
    if (m) {
      const argsStr = m[2];
      const args = argsStr.split(',').map((a) => a.trim()).map((a) => {
        if (a.startsWith('?')) return { type: 'VARIABLE' as const, value: a.substring(1) };
        const val = a.replace(/^"(.*)"$/, '$1');
        return { type: 'LITERAL' as const, value: val };
      });
      result.body.push({ id: `b${++id}`, type: 'BUILTIN', builtinName: m[1], args });
      continue;
    }
    // Property: name(?v1, ?v2) or name(?v1, "literal") or name(?v1, 123)
    m = atomStr.match(/^(\w+)\(\?(\w+),\s*(.+)\)$/);
    if (m) {
      const obj = m[3].trim();
      // Check if it's SameAs or DifferentFrom format
      if (atomStr.startsWith('SameAs(')) {
        const sm = atomStr.match(/^SameAs\(\?(\w+),\s*\?(\w+)\)$/);
        if (sm) {
          result.body.push({ id: `b${++id}`, type: 'SAME_AS', variable1: sm[1], variable2: sm[2] });
          continue;
        }
      }
      if (atomStr.startsWith('DifferentFrom(')) {
        const dm = atomStr.match(/^DifferentFrom\(\?(\w+),\s*\?(\w+)\)$/);
        if (dm) {
          result.body.push({ id: `b${++id}`, type: 'DIFFERENT_FROM', variable1: dm[1], variable2: dm[2] });
          continue;
        }
      }

      if (obj.startsWith('?')) {
        result.body.push({ id: `b${++id}`, type: 'OBJECT_PROPERTY', propertyName: m[1], variable1: m[2], variable2: obj.substring(1), variable2Type: 'VARIABLE' });
      } else {
        const literalVal = obj.replace(/^"(.*)"$/, '$1');
        result.body.push({ id: `b${++id}`, type: 'DATA_PROPERTY', propertyName: m[1], variable1: m[2], variable2: literalVal, variable2Type: 'LITERAL' });
      }
    }
  }

  // 解析 head
  const headAtoms = headStr.split('^').map((s) => s.trim()).filter(Boolean);
  for (const atomStr of headAtoms) {
    const m = atomStr.match(/^(\w+)\(\?(\w+)\)$/);
    if (m) {
      result.head.push({ id: `h${++id}`, type: 'CLASS', className: m[1], variable1: m[2] });
      continue;
    }
    const pm = atomStr.match(/^(\w+)\(\?(\w+),\s*\?(\w+)\)$/);
    if (pm) {
      result.head.push({ id: `h${++id}`, type: 'OBJECT_PROPERTY', propertyName: pm[1], variable1: pm[2], variable2: pm[3], variable2Type: 'VARIABLE' });
    }
  }

  return result;
}

const VisualComposer: React.FC<VisualComposerProps> = ({
  bodyAtoms, headAtoms, vocabulary, onChangeBody, onChangeHead,
}) => {
  // 收集所有变量
  const allVariables = useMemo(() => {
    const vars = new Set<string>();
    [...bodyAtoms, ...headAtoms].forEach((a) => {
      if (a.variable1) vars.add(a.variable1);
      if (a.variable2 && a.variable2Type === 'VARIABLE') vars.add(a.variable2);
      (a.args || []).forEach((arg) => {
        if (arg.type === 'VARIABLE' && arg.value) vars.add(arg.value);
      });
    });
    return Array.from(vars);
  }, [bodyAtoms, headAtoms]);

  // 安全性检查
  const headOnlyVars = useMemo(() => {
    const bodyVars = new Set<string>();
    bodyAtoms.forEach((a) => {
      if (a.variable1) bodyVars.add(a.variable1);
      if (a.variable2 && a.variable2Type === 'VARIABLE') bodyVars.add(a.variable2);
      (a.args || []).forEach((arg) => {
        if (arg.type === 'VARIABLE') bodyVars.add(arg.value);
      });
    });
    const headVars = new Set<string>();
    headAtoms.forEach((a) => {
      if (a.variable1) headVars.add(a.variable1);
      if (a.variable2 && a.variable2Type === 'VARIABLE') headVars.add(a.variable2);
    });
    return Array.from(headVars).filter((v) => !bodyVars.has(v));
  }, [bodyAtoms, headAtoms]);

  return (
    <div>
      {/* 变量管理器 */}
      {allVariables.length > 0 && (
        <div style={{ marginBottom: 12, padding: '6px 10px', background: '#fafafa', borderRadius: 4 }}>
          <Space size={4} wrap>
            <Text type="secondary" style={{ fontSize: 12 }}>变量:</Text>
            {allVariables.map((v) => (
              <Tag key={v} style={{ fontFamily: 'monospace', fontSize: 12 }}>
                ?{v}
              </Tag>
            ))}
          </Space>
        </div>
      )}

      {/* 安全性警告 */}
      {headOnlyVars.length > 0 && (
        <Alert
          type="error"
          showIcon
          message={`变量 ${headOnlyVars.map((v) => `?${v}`).join(', ')} 在 Head 中出现但未在 Body 中声明（违反 SWRL 安全性条件）`}
          style={{ marginBottom: 12 }}
        />
      )}

      {/* Body 区域 */}
      <div style={{ marginBottom: 12 }}>
        <div
          style={{
            background: '#1677ff',
            color: '#fff',
            padding: '6px 12px',
            borderRadius: '4px 4px 0 0',
            display: 'flex',
            justifyContent: 'space-between',
          }}
        >
          <Text strong style={{ color: '#fff' }}>IF — 条件 (Body)</Text>
          <Button size="small" ghost icon={<PlusOutlined />} onClick={() => {
            const newAtom: SwrlAtom = { id: `b${Date.now()}`, type: 'CLASS', className: '', variable1: '' };
            onChangeBody([...bodyAtoms, newAtom]);
          }}>
            添加 Atom
          </Button>
        </div>
        <div style={{ padding: '8px 4px', border: '1px solid #1677ff', borderTop: 0, borderRadius: '0 0 4px 4px' }}>
          {bodyAtoms.length === 0 ? (
            <Empty description="暂无 Body Atom" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            bodyAtoms.map((atom, idx) => (
              <AtomRow
                key={atom.id}
                atom={atom}
                index={idx}
                vocabulary={vocabulary}
                allVariables={allVariables}
                isBody
                onChange={(updated) => {
                  const newAtoms = [...bodyAtoms];
                  newAtoms[idx] = updated;
                  onChangeBody(newAtoms);
                }}
                onDelete={() => onChangeBody(bodyAtoms.filter((_, i) => i !== idx))}
                canDelete={bodyAtoms.length > 1}
              />
            ))
          )}
        </div>
      </div>

      {/* 箭头 */}
      <div style={{ textAlign: 'center', margin: '8px 0' }}>
        <ArrowDownOutlined style={{ fontSize: 24, color: '#1677ff' }} />
        <Text type="secondary" style={{ display: 'block', fontSize: 12 }}>THEN</Text>
      </div>

      {/* Head 区域 */}
      <div>
        <div
          style={{
            background: '#52c41a',
            color: '#fff',
            padding: '6px 12px',
            borderRadius: '4px 4px 0 0',
            display: 'flex',
            justifyContent: 'space-between',
          }}
        >
          <Text strong style={{ color: '#fff' }}>THEN — 结论 (Head)</Text>
          <Button size="small" ghost icon={<PlusOutlined />} onClick={() => {
            const newAtom: SwrlAtom = { id: `h${Date.now()}`, type: 'CLASS', className: '', variable1: '' };
            onChangeHead([...headAtoms, newAtom]);
          }}>
            添加 Atom
          </Button>
        </div>
        <div style={{ padding: '8px 4px', border: '1px solid #52c41a', borderTop: 0, borderRadius: '0 0 4px 4px' }}>
          {headAtoms.length === 0 ? (
            <Empty description="暂无 Head Atom" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            headAtoms.map((atom, idx) => (
              <AtomRow
                key={atom.id}
                atom={atom}
                index={idx}
                vocabulary={vocabulary}
                allVariables={allVariables}
                isBody={false}
                onChange={(updated) => {
                  const newAtoms = [...headAtoms];
                  newAtoms[idx] = updated;
                  onChangeHead(newAtoms);
                }}
                onDelete={() => onChangeHead(headAtoms.filter((_, i) => i !== idx))}
                canDelete={headAtoms.length > 1}
              />
            ))
          )}
        </div>
      </div>
    </div>
  );
};

export default VisualComposer;
