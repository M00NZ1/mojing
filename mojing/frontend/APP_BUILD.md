# 墨境 — 前端静态构建说明

## 构建

```bash
cd frontend && npm install && npm run build
```

产物在 `frontend/dist/`。若从 **`backend`** 启动 `uvicorn` 且 `frontend/dist` 存在，FastAPI 会挂载静态资源并在浏览器中提供单页应用（见 `backend/app/main.py` 中 `FRONTEND_DIST`）。

## 本地开发

通常使用 Vite 开发服务器（热更新）：

```bash
cd frontend && npm run dev
```

默认 [http://127.0.0.1:5175](http://127.0.0.1:5175)，API 指向 `vite.config` / 环境变量中的后端地址。

## 技术栈

| 层 | 技术 |
|----|------|
| 前端 | React 18 + Vite 5 + TypeScript |
| 后端 | FastAPI + SQLAlchemy 2.0 + SQLite |
