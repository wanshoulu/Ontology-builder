/**
 * AtomRow — 单个 SWRL Atom 编辑行
 * 按 Atom 类型动态渲染不同的表单字段组合
 */
import type { SwrlAtom,SwrlAtomType,SwrlBuiltinFunction,SwrlVocabulary } from '@/types';
import { DeleteOutlined } from '@ant-design/icons';
import { Button,Input,Select,Space,Tag } from 'antd';
import React from 'react';

interface AtomRowProps {
  atom: SwrlAtom;
  index: number;
  vocabulary: SwrlVocabulary;
  allVariables: string[];
  isBody: boolean; // true = Body (IF), false = Head (THEN)
  onChange: (atom: SwrlAtom) => void;
  onDelete: () => void;
  canDelete: boolean;
}

const ATOM_TYPE_OPTIONS: { value: SwrlAtomType; label: string; color: string }[] = [
  { value: 'CLASS', label: '类归属', color: '#1677ff' },
  { value: 'OBJECT_PROPERTY', label: '对象属性(关系)', color: '#52c41a' },
  { value: 'DATA_PROPERTY', label: '数据属性(值)', color: '#fa8c16' },
  { value: 'BUILTIN', label: '内置函数', color: '#722ed1' },
  { value: 'SAME_AS', label: '等价个体', color: '#eb2f96' },
  { value: 'DIFFERENT_FROM', label: '不同个体', color: '#fa541c' },
];

const AtomRow: React.FC<AtomRowProps> = ({
  atom, index, vocabulary, allVariables, isBody, onChange, onDelete, canDelete,
}) => {
  const { classes, objectProperties, dataProperties, builtins } = vocabulary;
  const allBuiltins: SwrlBuiltinFunction[] = builtins?.flatMap((c) => c.functions) || [];

  // 切换 Atom 类型
  const handleTypeChange = (type: SwrlAtomType) => {
    const base = { id: atom.id, type };
    switch (type) {
      case 'CLASS':
        onChange({ ...base, className: '', variable1: '' });
        break;
      case 'OBJECT_PROPERTY':
        onChange({ ...base, propertyName: '', variable1: '', variable2: '', variable2Type: 'VARIABLE' });
        break;
      case 'DATA_PROPERTY':
        onChange({ ...base, propertyName: '', variable1: '', variable2: '', variable2Type: 'LITERAL' });
        break;
      case 'BUILTIN':
        onChange({ ...base, builtinName: '', args: [] });
        break;
      case 'SAME_AS':
      case 'DIFFERENT_FROM':
        onChange({ ...base, variable1: '', variable2: '' });
        break;
    }
  };

  // 更新单个字段
  const update = (field: Partial<SwrlAtom>) => {
    onChange({ ...atom, ...field });
  };

  // 根据类型渲染字段
  const renderFields = () => {
    switch (atom.type) {
      case 'CLASS':
        return (
          <Space size={8}>
            <Select
              style={{ width: 180 }}
              placeholder="选择类"
              value={atom.className || undefined}
              onChange={(v) => update({ className: v })}
              showSearch
              optionFilterProp="label"
              options={classes.map((c) => ({ label: c.label || c.shortName, value: c.shortName }))}
            />
            <Input
              style={{ width: 100, fontFamily: 'monospace' }}
              placeholder="?变量"
              value={atom.variable1 ? `?${atom.variable1}` : ''}
              onChange={(e) => {
                const v = e.target.value.replace(/^\?/, '');
                update({ variable1: v });
              }}
              addonBefore={<span style={{ fontFamily: 'monospace', fontSize: 12 }}>?</span>}
            />
          </Space>
        );

      case 'OBJECT_PROPERTY':
        return (
          <Space size={8}>
            <Select
              style={{ width: 220 }}
              placeholder="选择对象属性（关系）"
              value={atom.propertyName || undefined}
              onChange={(v) => update({ propertyName: v })}
              showSearch
              optionFilterProp="label"
              options={[
                { label: '—— 对象属性（关系） ——', value: '', disabled: true },
                ...objectProperties.map((p) => ({
                  label: `${p.shortName} → ${p.range?.split('#')?.pop() || '?'}`,
                  value: p.shortName,
                })),
                ...(dataProperties.length > 0 ? [
                  { label: '—— 以下为数据属性，请切换类型 ——', value: '', disabled: true },
                  ...dataProperties.map((p) => ({
                    label: `${p.shortName} (${p.datatype?.split('#')?.pop() || 'string'})`,
                    value: p.shortName,
                    disabled: true,
                  })),
                ] : []),
              ]}
            />
            <Select
              style={{ width: 110 }}
              placeholder="主语"
              value={atom.variable1 || undefined}
              onChange={(v) => update({ variable1: v })}
              showSearch
              options={allVariables.map((v) => ({ label: `?${v}`, value: v }))}
            />
            <Select
              style={{ width: 110 }}
              placeholder="宾语（变量）"
              value={atom.variable2 || undefined}
              onChange={(v) => update({ variable2: v, variable2Type: 'VARIABLE' })}
              showSearch
              options={allVariables.map((v) => ({ label: `?${v}`, value: v }))}
            />
          </Space>
        );

      case 'DATA_PROPERTY':
        return (
          <Space size={8}>
            <Select
              style={{ width: 220 }}
              placeholder="选择数据属性（值）"
              value={atom.propertyName || undefined}
              onChange={(v) => update({ propertyName: v })}
              showSearch
              optionFilterProp="label"
              options={[
                { label: '—— 数据属性（值） ——', value: '', disabled: true },
                ...dataProperties.map((p) => ({
                  label: `${p.shortName} (${p.datatype?.split('#')?.pop() || 'string'})`,
                  value: p.shortName,
                })),
                ...(objectProperties.length > 0 ? [
                  { label: '—— 以下为对象属性，请切换类型 ——', value: '', disabled: true },
                  ...objectProperties.map((p) => ({
                    label: `${p.shortName} → ${p.range?.split('#')?.pop() || '?'}`,
                    value: p.shortName,
                    disabled: true,
                  })),
                ] : []),
              ]}
            />
            <Select
              style={{ width: 110 }}
              placeholder="主语"
              value={atom.variable1 || undefined}
              onChange={(v) => update({ variable1: v })}
              showSearch
              options={allVariables.map((v) => ({ label: `?${v}`, value: v }))}
            />
            {atom.variable2Type === 'VARIABLE' ? (
              <Select
                style={{ width: 110 }}
                placeholder="变量"
                value={atom.variable2 || undefined}
                onChange={(v) => update({ variable2: v, variable2Type: 'VARIABLE' })}
                showSearch
                options={allVariables.map((v) => ({ label: `?${v}`, value: v }))}
              />
            ) : (
              <Input
                style={{ width: 120 }}
                placeholder="字面量值"
                value={atom.variable2 || ''}
                onChange={(e) => update({ variable2: e.target.value, variable2Type: 'LITERAL' })}
              />
            )}
            <Button
              size="small"
              type="link"
              onClick={() =>
                update({
                  variable2Type: atom.variable2Type === 'VARIABLE' ? 'LITERAL' : 'VARIABLE',
                  variable2: '',
                })
              }
            >
              {atom.variable2Type === 'VARIABLE' ? '切换为字面量' : '切换为变量'}
            </Button>
          </Space>
        );

      case 'BUILTIN':
        return (
          <Space size={8}>
            <Select
              style={{ width: 200 }}
              placeholder="选择内置函数"
              value={atom.builtinName || undefined}
              onChange={(v) => {
                const fn = allBuiltins.find((b) => b.shortName === v);
                const args: any[] = [];
                if (fn) {
                  for (let i = 0; i < (fn.argCount > 0 ? fn.argCount : 2); i++) {
                    args.push({ type: 'VARIABLE' as const, value: '' });
                  }
                }
                update({ builtinName: v, args });
              }}
              showSearch
              optionFilterProp="label"
              options={allBuiltins.map((b) => ({
                label: `${b.displayName} (${b.shortName})`,
                value: b.shortName,
                title: b.description,
              }))}
              optionRender={(opt) => (
                <div>
                  <div>{opt.label}</div>
                  <div style={{ fontSize: 11, color: '#999' }}>{(opt.data as any)?.title}</div>
                </div>
              )}
            />
            {(atom.args || []).map((arg, i) => (
              arg.type === 'VARIABLE' ? (
                <Select
                  key={i}
                  style={{ width: 100 }}
                  placeholder={`参数${i + 1}`}
                  value={arg.value || undefined}
                  onChange={(v) => {
                    const newArgs = [...(atom.args || [])];
                    newArgs[i] = { type: 'VARIABLE', value: v };
                    update({ args: newArgs });
                  }}
                  showSearch
                  options={allVariables.map((v) => ({ label: `?${v}`, value: v }))}
                />
              ) : (
                <Input
                  key={i}
                  style={{ width: 100 }}
                  placeholder={`参数${i + 1}`}
                  value={arg.value || ''}
                  onChange={(e) => {
                    const newArgs = [...(atom.args || [])];
                    newArgs[i] = { type: 'LITERAL', value: e.target.value };
                    update({ args: newArgs });
                  }}
                />
              )
            ))}
          </Space>
        );

      case 'SAME_AS':
      case 'DIFFERENT_FROM':
        return (
          <Space size={8}>
            <Select
              style={{ width: 130 }}
              placeholder="变量1"
              value={atom.variable1 || undefined}
              onChange={(v) => update({ variable1: v })}
              showSearch
              options={allVariables.map((v) => ({ label: `?${v}`, value: v }))}
            />
            <Select
              style={{ width: 130 }}
              placeholder="变量2"
              value={atom.variable2 || undefined}
              onChange={(v) => update({ variable2: v })}
              showSearch
              options={allVariables.map((v) => ({ label: `?${v}`, value: v }))}
            />
          </Space>
        );

      default:
        return <span>未知类型</span>;
    }
  };

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '6px 8px',
        marginBottom: 4,
        background: isBody ? '#f0f5ff' : '#f6ffed',
        borderRadius: 4,
        border: `1px solid ${isBody ? '#d6e4ff' : '#d9f7be'}`,
      }}
    >
      <Tag style={{ margin: 0, minWidth: 24, textAlign: 'center' }}>#{index + 1}</Tag>

      <Select
        size="small"
        style={{ width: 100 }}
        value={atom.type}
        onChange={(v) => handleTypeChange(v)}
        options={ATOM_TYPE_OPTIONS.map((o) => ({
          label: (
            <span>
              <span style={{ color: o.color, marginRight: 4 }}>●</span>
              {o.label}
            </span>
          ),
          value: o.value,
        }))}
      />

      <div style={{ flex: 1 }}>{renderFields()}</div>

      {canDelete && (
        <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={onDelete} />
      )}
    </div>
  );
};

export default AtomRow;
