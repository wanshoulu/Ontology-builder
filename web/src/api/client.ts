import { getToken, removeToken } from '@/utils/auth';
import { message } from 'antd';
import type { AxiosInstance,AxiosRequestConfig } from 'axios';
import axios from 'axios';

interface BodyHttpClient extends Omit<AxiosInstance, 'get' | 'post' | 'put' | 'patch' | 'delete'> {
  get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>;
  post<T = any, D = any>(url: string, data?: D, config?: AxiosRequestConfig<D>): Promise<T>;
  put<T = any, D = any>(url: string, data?: D, config?: AxiosRequestConfig<D>): Promise<T>;
  patch<T = any, D = any>(url: string, data?: D, config?: AxiosRequestConfig<D>): Promise<T>;
  delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>;
}

const axiosClient = axios.create({
  // 生产环境使用相对路径通过nginx代理，开发环境代理到后端
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 21600000, // 6小时，AI自动映射等长任务需要更长超时
  headers: { 'Content-Type': 'application/json' },
  maxContentLength: Infinity,
  maxBodyLength: Infinity,
});

// 请求拦截器：注入 JWT
axiosClient.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  // 如果请求体是 FormData，删除默认的 Content-Type，让浏览器自动设置（含 boundary）
  if (config.data instanceof FormData) {
    config.headers.delete('Content-Type');
  }
  return config;
});

// 响应拦截器：统一错误处理
axiosClient.interceptors.response.use(
  (response) => response.data,
  (error) => {
    // 401 认证失败：清除 Token 并跳转登录页
    if (error.response?.status === 401) {
      removeToken();
      message.error('登录已过期，请重新登录');
      window.location.href = '/login';
      return Promise.reject(error);
    }

    // 提取错误消息
    let errorMessage = '请求失败';
    const backendError = error.response?.data;

    if (backendError) {
      // 优先使用后端返回的 message 字段
      if (backendError.message) {
        errorMessage = backendError.message;
      } else if (backendError.detail?.message) {
        // FastAPI 结构化错误：{ detail: { code, message } }
        errorMessage = backendError.detail.message;
      } else if (typeof backendError.detail === 'string') {
        errorMessage = backendError.detail;
      } else if (typeof backendError === 'string') {
        errorMessage = backendError;
      }
    } else if (error.message) {
      errorMessage = error.message;
    }

    // 网络错误特殊处理
    if (!error.response) {
      errorMessage = '网络连接失败，请检查网络设置';
    }

    message.error(errorMessage);
    return Promise.reject(error);
  }
);

// The response interceptor returns response.data, so expose body-level method types.
const client = axiosClient as BodyHttpClient;

export default client;
