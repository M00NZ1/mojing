# Web 构建与运行

Web 客户端使用 React、TypeScript 和 Vite，主要页面为对话、创作和设置。

以下命令均从 `mojing/frontend/` 执行。

## 开发

```powershell
npm ci
npm run dev
```

开发地址为 [127.0.0.1:5175](http://127.0.0.1:5175)，端口占用时直接退出。Vite 将 `/api`、`/health` 和 `/storage` 转发到 `http://127.0.0.1:8000`，配置位于 [vite.config.ts](vite.config.ts)。

本机 API 启动方法见[运行指南](../README.md)。

## 生产构建

```powershell
npm run build
```

构建命令执行 `tsc -b` 和 `vite build`，产物写入 `dist/`。

单独进行 TypeScript 检查：

```powershell
npx tsc --noEmit
```

## 通过本机 API 提供页面

构建完成后，从 `mojing/` 启动 API：

```powershell
.\.venv\Scripts\python.exe -m uvicorn backend.app.main:app --host 127.0.0.1 --port 8000
```

打开 [127.0.0.1:8000](http://127.0.0.1:8000)。

[main.py](../backend/app/main.py)根据源码位置查找 `frontend/dist/`，在应用创建时挂载 `/assets` 和 `/icons`，其余页面路径返回 `index.html`。未匹配的 API 路径返回 404。

## 静态预览

```powershell
npm run preview
```

预览地址为 [127.0.0.1:4173](http://127.0.0.1:4173)。完整本地应用使用 API 提供页面的运行方式。

## 浏览器脚本

`scripts/test-*.mjs` 使用 Playwright，覆盖平台配置、角色导入、生成记录、暂停续跑、搜索与消息操作。脚本直接通过 Node 执行，环境变量和运行示例见[构建与维护](../docs/MAINTENANCE.md)。

`npm run perf:h5` 运行 Node 数据规模推演，统计模拟消息的数组合并、序列化时间和体积。
