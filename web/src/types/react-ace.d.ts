declare module 'react-ace' {
  import { Component } from 'react';

  export interface AceEditorProps {
    mode?: string;
    theme?: string;
    value?: string;
    onChange?: (value: string, event?: unknown) => void;
    name?: string;
    editorProps?: Record<string, unknown>;
    setOptions?: Record<string, unknown>;
    style?: React.CSSProperties;
    className?: string;
    fontSize?: number;
    showGutter?: boolean;
    showPrintMargin?: boolean;
    highlightActiveLine?: boolean;
    readOnly?: boolean;
    width?: string;
    height?: string;
    [key: string]: unknown;
  }

  export default class AceEditor extends Component<AceEditorProps> {}
}
