declare module 'react-markdown' {
  import { ComponentType } from 'react';

  interface ReactMarkdownProps {
    children?: string;
    components?: Record<string, ComponentType<any>>;
    remarkPlugins?: any[];
    rehypePlugins?: any[];
    className?: string;
    [key: string]: any;
  }

  const ReactMarkdown: ComponentType<ReactMarkdownProps>;
  export default ReactMarkdown;
}

declare module 'rehype-raw' {
  import { Plugin } from 'unified';
  const rehypeRaw: Plugin;
  export default rehypeRaw;
}
