# 墨境构建与维护

环境安装和服务启停见[运行指南](../README.md)。下列各节独立从仓库根目录执行命令。

## Python

```powershell
Set-Location .\mojing
.\.venv\Scripts\python.exe -m pytest
```

定向运行角色导入与初始目录测试：

```powershell
Set-Location .\mojing
.\.venv\Scripts\python.exe -m pytest tests/test_character_import_workflow.py tests/test_starter_catalog.py -q
```

Python 测试主要位于 [tests/](../tests/)，API 实现位于 [backend/app/](../backend/app/)。

## Web

```powershell
Set-Location .\mojing\frontend
npm run build
```

仅检查 TypeScript：

```powershell
Set-Location .\mojing\frontend
npx tsc --noEmit
```

### 浏览器回归

[frontend/scripts/](../frontend/scripts/)中的 `test-*.mjs` 使用 Playwright 启动独立 Vite 端口，并在浏览器中拦截模拟 API。

准备可用的 Playwright 包和 Chromium 后，从 Web 目录运行对应脚本：

```powershell
Set-Location .\mojing\frontend
node scripts/test-model-platforms.mjs
```

| 环境变量 | 用途 |
|---|---|
| `PLAYWRIGHT_MODULE` | 指定可加载的 Playwright 包路径，默认解析 `playwright` |
| `SMOKE_BROWSER` | 浏览器 channel，例如 `chrome`；留空使用 Playwright Chromium |
| `SMOKE_PORT` | 覆盖脚本使用的独立 Vite 端口 |
| `SMOKE_OUTPUT` | 输出截图等文件的目录 |

按改动选择角色导入、初始目录、世界记录、世界转移、取消、检查点、搜索或删除脚本。各脚本负责关闭自身启动的浏览器和 Vite 进程。

### 数据规模分析

```powershell
Set-Location .\mojing
.\.venv\Scripts\python.exe scripts/benchmark_message_search.py
```

该脚本建立临时 SQLite 数据集，统计 6,000 条消息的 Token 数、查询耗时、索引分批耗时和数据库体积。运行环境需要已有的 `cl100k_base` tokenizer 缓存。

Web 数组与序列化推演：

```powershell
Set-Location .\mojing\frontend
npm run perf:h5
```

## Android

```powershell
Set-Location .\mojing\android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

连接设备后的测试入口：

```powershell
Set-Location .\mojing\android
.\gradlew.bat connectedDebugAndroidTest
```

测试位于 `app/src/test/` 和 `app/src/androidTest/`。签名、ABI 与 APK 输出见 [Android 开发指南](ANDROID.md)。

本机 SQLite 契约测试从 Room schema 和 DAO 读取表、索引与查询，覆盖角色排序、任务状态和百科版本分页。版本用例包含两万条交错记录、删除游标边界与新增记录：

```powershell
Set-Location .\mojing
.\.venv\Scripts\python.exe -m pytest tests/test_android_library_sql.py -q
```

## 数据库迁移

Web 使用 Alembic，迁移脚本位于 [backend/alembic/versions/](../backend/alembic/versions/)。

升级前保留完整数据库备份；SQLite 运行中的数据库使用 SQLite backup API 获取一致副本。离线复制应在数据库连接关闭后进行，并保留现有 WAL/SHM。

```powershell
Set-Location .\mojing
.\scripts\migrate_backend.ps1
```

该脚本调用 `alembic upgrade head`。数据库迁移实现应包含旧格式读取、重复执行和失败回滚处理。

Android 使用 Room migration，schema 位于 [app/schemas/](../android/app/schemas/)，迁移实现位于 [AppDatabase.kt](../android/app/src/main/java/com/mojing/app/data/local/AppDatabase.kt)。

## CI 与交付

[Android 工作流](../../.github/workflows/android-build.yml)在 Android 路径相关的 main 推送、PR 和手动运行时执行 JVM 测试与 Debug 构建；非 PR 运行还生成 Release APK。产物上传为 Actions artifacts。

GitHub Releases 由发布流程单独上传 APK。发布时沿用项目版本与签名配置，安装包放入 Release assets，源码通过 Git 管理。

## 文件管理

`.env`、数据库、媒体、签名文件和 `local.properties` 属于本机状态。日常构建使用现有依赖与缓存，临时输出放在 `.codex-work/`，交付文件放在 `outputs/`。

提交按本批文件清单暂存，检查 `git diff --cached` 后创建独立提交。产品介绍维护在 [PROJECT.md](../PROJECT.md)，后续工作维护在[优化目标](../../docs/PRODUCT_GAP_MAP.md)。
