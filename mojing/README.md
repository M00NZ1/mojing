# 墨境开发与运行

正式源码根为 `mojing/`，包含 Web、本机 API 与原生 Android 工程。

## 环境

| 模块 | 环境 |
|---|---|
| 本机 API | Python 3.11+ |
| Web | Node.js 20+、npm |
| Android | JDK 17、Android SDK、Gradle Wrapper |

## Web 首次安装

从仓库根目录执行：

```powershell
Set-Location .\mojing
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r .\backend\requirements.txt
if (-not (Test-Path -LiteralPath .\.env)) {
    Copy-Item .\.env.example .\.env
}
Set-Location .\frontend
npm ci
Set-Location ..
```

后续命令以 `mojing/` 为起始目录。

### 一键启动

```powershell
.\一键启动.bat
```

脚本在后台启动 API 和 Web，等待服务就绪后打开浏览器。

| 入口 | 地址 |
|---|---|
| Web | [127.0.0.1:5175](http://127.0.0.1:5175) |
| API 健康检查 | [127.0.0.1:8000/health](http://127.0.0.1:8000/health) |
| API 文档 | [127.0.0.1:8000/docs](http://127.0.0.1:8000/docs) |

### 分别启动

在两个终端中分别从 `mojing/` 执行：

```powershell
# 终端一：本机 API
.\.venv\Scripts\python.exe -m uvicorn backend.app.main:app --host 127.0.0.1 --port 8000
```

```powershell
# 终端二：Web
Set-Location .\frontend
npm run dev
```

Vite 将 `/api`、`/health` 和 `/storage` 代理到 `127.0.0.1:8000`。代理配置位于 [vite.config.ts](frontend/vite.config.ts)。

### 启停与日志

| 文件 | 功能 |
|---|---|
| [一键关闭.bat](一键关闭.bat) | 依据本目录 PID 记录和进程命令停止托管服务 |
| [查看日志.bat](查看日志.bat) | 持续显示 API 与 Web 日志，按 Ctrl+C 退出查看 |
| [start_dev_all.ps1](scripts/start_dev_all.ps1) | 在两个独立终端窗口启动开发服务 |
| [start_dev_detached.ps1](scripts/start_dev_detached.ps1) | 后台启动并记录 PID 与日志 |

日志位于 `logs/backend.log` 和 `logs/frontend.log`。手动启动的服务可在对应终端按 Ctrl+C 停止。

## 模型配置

进入设置中的模型页面，添加平台并填写名称、接口地址和 Key。可通过平台获取模型列表，也可手动填写一个或多个模型名称。同类服务商可以建立多个独立配置。

预设包括 DeepSeek、OpenAI、硅基流动、Anthropic 和自定义。保存默认平台后，新会话可使用默认线路；聊天页的模型入口支持为当前会话切换平台与模型。

## 构建

从 `mojing/` 执行 Web 生产构建：

```powershell
Set-Location .\frontend
npm run build
```

产物位于 `frontend/dist/`，静态页面运行方式见 [Web 构建说明](frontend/APP_BUILD.md)。

Android 工程位于 [android/](android/)，使用 JDK 17 和 Android SDK：

```powershell
# 从 mojing/ 执行
Set-Location .\android
.\gradlew.bat assembleDebug
```

构建、签名和输出目录见 [Android 开发指南](docs/ANDROID.md)。

## 数据与配置

| 路径 | 内容 |
|---|---|
| `backend/storage/` | SQLite 数据库、媒体和导出文件 |
| `.env` | 本机 API 环境配置 |
| `mojing_config.json` | 本机配置文件 |
| `android/local.properties` | Android SDK 与本机签名配置 |
| `data/builtin_pack/` | 随源码分发的初始资料与资源 |

存储目录可通过 `MOJING_STORAGE_DIR` 指定。数据库迁移与维护命令见 [构建与维护](docs/MAINTENANCE.md)。

## 文档

- [产品与架构](PROJECT.md)
- [文档目录](docs/README.md)
- [项目状态](../docs/PROJECT_STATE.md)
- [优化目标](../docs/PRODUCT_GAP_MAP.md)
