# MoJing 开发与运行

本目录是 MoJing 的正式源码根，包含本机 API、Web 客户端和原生 Android 应用。

## 目录

```text
backend/      FastAPI、SQLAlchemy、SQLite 与文件存储
frontend/     React、TypeScript 与 Vite
android/      Kotlin、Jetpack Compose 与 Room
data/         内置内容与迁移输入
scripts/      本地启动、停止、迁移和诊断脚本
tests/        Backend 与跨模块自动测试
docs/         Android 与维护说明
```

## Web 与 Backend

### 首次准备

从仓库根目录执行：

```powershell
Set-Location .\mojing
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r .\backend\requirements.txt
Copy-Item .\.env.example .\.env

Set-Location .\frontend
npm install
Set-Location ..
```

`.env` 和 `mojing_config.json` 只用于本机配置，禁止提交真实密钥。

### 启动

```powershell
.\一键启动.bat
```

也可以分别运行：

```powershell
# Backend
.\.venv\Scripts\python.exe -m uvicorn backend.app.main:app --host 127.0.0.1 --port 8000

# Web（另一个终端）
Set-Location .\frontend
npm run dev
```

常用脚本：

- `一键关闭.bat`：停止本项目启动的本地进程；
- `查看日志.bat`：打开本机日志目录；
- `scripts/start_dev_all.ps1`：前台开发启动；
- `scripts/start_dev_detached.ps1`：分离窗口启动。

## Android

需要 JDK 17 与 Android SDK：

```powershell
Set-Location .\android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Android namespace 与 application ID 均为 `com.mojing.app`。Room schema 位于 `android/app/schemas/com.mojing.app.data.local.AppDatabase/`。

## 自动验证

```powershell
# Backend
Set-Location .\mojing
.\.venv\Scripts\python.exe -m pytest

# Web
Set-Location .\mojing\frontend
npm run build

# Android
Set-Location .\mojing\android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

构建或测试只证明对应静态/自动化边界；真实浏览器、模拟器和真机交互需要单独记录。

## 本地数据

以下路径属于本机状态，不应提交或在清理时自动删除：

- `.env`、`mojing_config.json`、Android `local.properties`；
- `backend/storage/` 中的 SQLite、WAL/SHM 与媒体；
- `data/sessions/` 中的迁移输入；
- `.venv/`、`frontend/node_modules/`、Gradle 缓存和构建目录；
- 日志、APK、备份以及用户导入的资源。

数据库或交换格式变化必须提供版本、幂等迁移、失败恢复和旧样本测试，不能通过删除旧数据库解决升级问题。

## 更多说明

- [产品与架构](PROJECT.md)
- [Android 开发基线](docs/ANDROID.md)
- [构建、验证与交付](docs/MAINTENANCE.md)
