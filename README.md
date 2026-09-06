# 墨境 MoJing

本地优先的 AI 角色扮演、长篇对话与世界设定工作台。

墨境将角色、世界设定和剧情记录整合在同一创作空间，支持单角色与多角色对话、剧情分支、历史搜索、长期记忆，以及图片和语音扩展。Web 版通过本机 FastAPI 管理本地数据，Android 版使用原生 Kotlin 应用。

[下载 Android 应用](https://github.com/M00NZ1/mojing/releases/latest) · [运行指南](mojing/README.md) · [文档目录](mojing/docs/README.md)

## 功能

| 模块 | 内容 |
|---|---|
| 对话 | 流式回复、停止、重试、继续生成、多角色参与 |
| 角色 | 角色卡、详细资料、人格、收藏、导入导出 |
| 世界 | 世界百科、条目关系、时间线、WorldInfo、世界模板 |
| 剧情 | 消息编辑、回复版本、故事线与检查点 |
| 历史 | 分页阅读、搜索、原文定位、收藏 |
| 记忆 | 上下文预算、自动摘要、长期事实与来源 |
| 模型 | 多平台独立配置、模型获取、手动模型列表、会话内切换 |
| 创作 | 世界生成、文本整理、生成记录、暂停与续跑 |
| 媒体 | 图片生成、语音合成、语音输入与消息附件 |

内置“雾港来信”包含一个百科、一个世界，以及沈照、林汐两名角色。Web 创作中心的“从雾港开始”可直接创建配套会话。

模型预设包括 DeepSeek、OpenAI、硅基流动、Anthropic 和自定义。每个平台独立保存地址、Key 和模型列表；聊天页按平台选择模型，从下一次发送生效。

## 快速开始

### Web

需要 Python 3.11+、Node.js 20+ 和 npm。在仓库根目录执行：

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
.\一键启动.bat
```

浏览器地址为 [127.0.0.1:5175](http://127.0.0.1:5175)，本机 API 使用端口 8000。进入设置配置模型平台后即可开始对话。

### Android

从 [Releases](https://github.com/M00NZ1/mojing/releases/latest) 下载 APK。源码构建方法见 [Android 开发指南](mojing/docs/ANDROID.md)。

## 项目结构

```text
mojing/
├─ backend/      FastAPI、业务逻辑与 SQLite
├─ frontend/     React、TypeScript 与 Vite
├─ android/      Kotlin、Jetpack Compose 与 Room
├─ data/         内置资料与资源
├─ scripts/      启停、迁移与诊断脚本
├─ tests/        Python 与跨模块测试
└─ docs/         技术与维护文档
docs/            项目状态与优化目标
```

## 本地数据

Web 数据保存在本机数据库和文件目录，Android 数据保存在应用私有存储。两端通过本地文件导入导出交换资料。模型、图片和语音请求使用各自配置的供应商。

Android 应用标识为 `com.mojing.app`。从早期不同应用标识的版本迁移时，先在旧应用导出数据，再导入墨境。

## 文档

- [产品与架构](mojing/PROJECT.md)
- [开发与运行](mojing/README.md)
- [构建与维护](mojing/docs/MAINTENANCE.md)
- [项目状态](docs/PROJECT_STATE.md)
- [优化目标](docs/PRODUCT_GAP_MAP.md)
