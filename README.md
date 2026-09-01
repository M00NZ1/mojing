<div align="center">

# 墨境 MoJing

本地优先的 AI 角色扮演、长篇对话与世界设定工作台

</div>

MoJing 面向个人创作与长期角色扮演，提供角色管理、世界设定、多人对话、消息编辑、剧情分支、历史搜索、长期记忆以及图片和语音扩展。项目包含 React Web 客户端、本机 FastAPI 服务和原生 Android 应用。

## 主要能力

| 模块 | 能力 |
|---|---|
| 对话 | 单角色/多角色对话、流式回复、停止、重试、继续生成 |
| 角色 | 角色卡、用户人格、会话参与者、独立模型配置 |
| 世界设定 | 百科、WorldInfo、世界模板、设定工坊、会话绑定 |
| 消息与分支 | 非破坏性编辑、回复版本、剧情分支创建与切换 |
| 长篇会话 | 游标分页、全文搜索、收藏定位、上下文预算、长期记忆 |
| 导入导出 | 酒馆角色卡、聊天记录、WorldInfo、本地数据包 |
| 媒体 | 图片生成、语音合成、语音输入、消息附件 |
| 客户端 | React/Vite Web 与 Kotlin/Compose Android |

## 快速开始

正式源码位于 [`mojing/`](mojing/)。

### Web 与本机 API

需要 Python 3.11+、Node.js 20+ 和 npm。

```powershell
Set-Location .\mojing

python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r .\backend\requirements.txt
Copy-Item .\.env.example .\.env

Set-Location .\frontend
npm install
Set-Location ..

.\一键启动.bat
```

默认地址：

- Web：<http://127.0.0.1:5175>
- API 健康检查：<http://127.0.0.1:8000/health>

### Android

Android 工程需要 JDK 17 和可用的 Android SDK：

```powershell
Set-Location .\mojing\android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Debug APK 生成在 `mojing/android/app/build/outputs/apk/debug/`。

## 仓库结构

```text
mojing/
├─ backend/      # FastAPI、业务逻辑与本地存储
├─ frontend/     # React/Vite Web 客户端
├─ android/      # Kotlin/Compose 原生 Android 应用
├─ data/         # 内置资源与可选导入数据
├─ scripts/      # 启停、迁移和诊断工具
├─ tests/        # Backend 与跨模块测试
└─ docs/         # Android 与维护文档
docs/            # 项目状态与公开路线图
```

## 数据与隐私

- 核心数据默认保存在用户自己的设备，不提供账号体系或自动云同步。
- `.env`、API Key、数据库、媒体、日志、构建产物和本机配置均被 Git 忽略。
- Web 与 Android 使用各自的本地数据库；跨端连续性依赖显式导入/导出。
- 本机 API 默认绑定回环地址。公开到局域网或互联网前，请自行配置 TLS、访问控制和防火墙。

MoJing 可连接 DeepSeek、OpenAI-compatible 及其他受支持的模型供应商。源码中出现的供应商名称只表示兼容能力，不是项目名称。

## 兼容提醒

Android 应用标识现为 `com.mojing.app`。早期、尚未采用 MoJing 标识的 Android 构建不会被原位覆盖；升级前应先从旧应用导出数据，再在 MoJing 中导入。改名不会自动删除旧应用或旧数据。

## 文档

- [完整运行说明](mojing/README.md)
- [产品与架构](mojing/PROJECT.md)
- [Android 开发基线](mojing/docs/ANDROID.md)
- [构建、验证与交付](mojing/docs/MAINTENANCE.md)
- [当前项目状态](docs/PROJECT_STATE.md)
- [公开路线图](docs/PRODUCT_GAP_MAP.md)
