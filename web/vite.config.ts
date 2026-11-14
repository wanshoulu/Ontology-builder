import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

// https://vitejs.dev/config/
export default defineConfig({
  base: '/ontology/',
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  optimizeDeps: {
    include: ['mermaid', '@mermaid-js/parser'],
  },
  server: {
    port: 3000,
    proxy: {
      '/ontomind/wiki/api': {
        target: 'http://localhost:3000',
        changeOrigin: true,
        rewrite: (requestPath) => requestPath.replace(/^\/ontomind\/wiki\/api/, '/api'),
        timeout: 0,
        proxyTimeout: 0,
      },
      // 后端 API 代理
      '/ontomind/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ontomind/, ''),
        timeout: 0,
        proxyTimeout: 0,
      },
      // Agent 服务代理 — Wiki 对话构建路由到 ontomind-agent (host port 8002)
      '/ontomind/agent/wiki': {
        target: 'http://localhost:8002',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ontomind/, ''),
        timeout: 0,
        proxyTimeout: 0,
      },
      // Wiki 构建对话新链路 — wiki-build-agent 独立容器 (host port 8500)
      '/ontomind/wiki/build-agent': {
        target: 'http://localhost:8500',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ontomind\/wiki\/build-agent/, ''),
        timeout: 0,
        proxyTimeout: 0,
      },
      // Agent 服务代理 — 其他 agent 路由到 ontomind-knowledge (host port 8001)
      '/ontomind/agent': {
        target: 'http://localhost:8001',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ontomind/, ''),
        timeout: 0,
        proxyTimeout: 0,
      },
      // G6VP 图分析平台（AI Harbor 托管）
      '/g6vp': {
        target: 'http://localhost:3000',
        changeOrigin: true,
      },
      // 图谱构建画布 iframe 与独立工具页（AI Harbor 托管；工具页内部还有根相对 /api/* 调用）
      '/tools': {
        target: 'http://localhost:3000',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:3000',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['react', 'react-dom', 'react-router-dom'],
          antd: ['antd', '@ant-design/icons'],
        },
      },
    },
  },
});
