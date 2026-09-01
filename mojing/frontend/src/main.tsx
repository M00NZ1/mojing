import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';

import App from './App';
import './styles.css';
import ErrorBoundary from './components/ErrorBoundary';
import RouteErrorPage from './components/RouteErrorPage';
import { ToastProvider } from './hooks/useToast';
import { applyChatDensity, applyTheme, readSavedChatDensityId, readSavedThemeId } from './theme';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 本机服务未启动时，立即把失败交给页面处理；默认连续重试只会放大请求与日志噪声。
      retry: false,
    },
  },
});

const router = createBrowserRouter([
  { path: '*', element: <App />, errorElement: <RouteErrorPage /> },
]);

// 在 React 渲染前恢复用户选择的主题色，避免刷新后闪回默认主题
const savedTheme = readSavedThemeId();
if (savedTheme) {
  applyTheme(savedTheme);
}

const savedChatDensity = readSavedChatDensityId();
if (savedChatDensity) {
  applyChatDensity(savedChatDensity);
} else {
  applyChatDensity('comfortable');
}

// 生产环境只注册一次安装壳 Service Worker。业务请求与用户数据不由它缓存。
if ('serviceWorker' in navigator) {
  // 开发模式下不注册，避免影响 HMR 与 Vite 代理。
  if (import.meta.env.PROD) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('/sw.js').then((reg) => {
        console.log('[SW] 生产模式已注册:', reg.scope);
      }).catch((err) => {
        console.warn('[SW] 注册失败:', err);
      });
    });
  }
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <RouterProvider router={router} />
        </ToastProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  </React.StrictMode>,
);
