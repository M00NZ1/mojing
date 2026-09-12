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

`node scripts/test-world-library.mjs` 检查世界集中入口、旧资料整理确认、失败重试、刷新恢复、搜索及桌面/320px 布局。使用独立浏览器与模拟 API；`SMOKE_OUTPUT` 可输出页面截图。

世界资料的数据层回归使用 `tests/test_unified_world_migration.py`、`tests/test_unified_world_context.py` 和 `tests/test_world_library_api.py`，覆盖身份回填、重复迁移、同名保护、事务回滚、条目触发与引用保护。测试数据库与正式存储隔离。

`node scripts/test-story-result-recovery.mjs` 检查结果读取重试、长正文滚动与全文复制、刷新恢复、继续保存、原会话打开和确认放弃。使用独立浏览器与模拟 API。

`node scripts/test-story-request-retry.mjs` 使用模拟 API 检查响应丢失、刷新后继续、引用资料保留和修改设定后的新请求。

`node scripts/test-story-draft-recovery.mjs` 检查旧创作草稿恢复、保存失败重试、跨页面修订保护、生成成功清理及窄屏按钮点击。使用隔离浏览器与模拟 API。

`node scripts/test-confirm-dialog.mjs` 检查桌面与 320 × 480 小屏的长说明滚动、底部操作和输入法 Escape；只连接独立 Vite 端口。

`node scripts/test-control-states.mjs` 在隔离 Chrome 页面中加载正式样式，检查七套主题的主按钮对比度、禁用悬停和键盘焦点；设置 `SMOKE_OUTPUT` 可保存控件截图。

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

本地 Release 打包可使用独立构建进程，设置 6 GB 堆内存与两个工作线程：

```powershell
Set-Location .\mojing\android
.\gradlew.bat assembleRelease --no-daemon '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' --max-workers=2 --console=plain
```

连接设备后的测试入口：

```powershell
Set-Location .\mojing\android
.\gradlew.bat connectedDebugAndroidTest
```

测试位于 `app/src/test/` 和 `app/src/androidTest/`。签名、ABI 与 APK 输出见 [Android 开发指南](ANDROID.md)。

### 独立模拟器交互回归

使用专用测试 AVD，数据放在 `.codex-work/`；通过设备序列号明确指定安装和测试目标。以下命令从 Android 目录执行，`$testDevice` 填写专用模拟器的实际序列号：

```powershell
$testDevice = 'emulator-5580'
$testAdb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
.\gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain
& $testAdb -s $testDevice install -r app/build/outputs/apk/debug/app-universal-debug.apk
& $testAdb -s $testDevice install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$testClasses = @(
    'com.mojing.app.ui.chat.SwipeRevealListRowTest'
    'com.mojing.app.ui.chat.MemoryPanelPresentationTest'
    'com.mojing.app.ui.chat.GenerationTaskFeedbackTest'
    'com.mojing.app.ui.chat.MessageActionPanelTest'
    'com.mojing.app.ui.chat.drawer.ChatDrawerNavigationTest'
    'com.mojing.app.ui.chat.drawer.ParticipantsTabPresentationTest'
    'com.mojing.app.ui.chat.InputBarAttachmentStateTest'
    'com.mojing.app.ui.chat.MessageBubbleLayoutTest'
) -join ','
& $testAdb -s $testDevice shell am instrument -w -r -e class $testClasses com.mojing.app.test/androidx.test.runner.AndroidJUnitRunner
```

以测试输出中的 `OK` 或失败报告判断结果。2026-09-10 使用 Android 35、Pixel 6 配置完成上述 12 项用例；原始结果保存在 `.codex-work/android-ui-20260910/`。头像用例在 280dp 宽度下检查 40dp 尺寸与明暗主题文字颜色。

本机 SQLite 契约测试从 Room schema 和 DAO 读取表、索引与查询，覆盖角色排序、任务状态和百科版本分页。版本用例包含两万条交错记录、删除游标边界与新增记录：

```powershell
Set-Location .\mojing
.\.venv\Scripts\python.exe -m pytest tests/test_android_library_sql.py -q
```

消息搜索分页契约覆盖主线、继承分支、替换消息、精确匹配和部分索引：

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_android_search_paging_sqlite.py -q
```

## 数据库迁移

Web 使用 Alembic，迁移脚本位于 [backend/alembic/versions/](../backend/alembic/versions/)。

升级前保留完整数据库备份；SQLite 运行中的数据库使用 SQLite backup API 获取一致副本。离线复制应在数据库连接关闭后进行，并保留现有 WAL/SHM。

```powershell
Set-Location .\mojing
.\scripts\migrate_backend.ps1
```

该脚本调用 `alembic upgrade head`。数据库迁移实现应包含旧格式读取、重复执行和失败回滚处理。

Web 迁移 `20260913_0013` 新增 `story_request_receipts`，保存版本 1 完成回执、输入摘要与原会话标识；不修改已有消息。升级支持重复执行，回退代码保留回执表，旧版本忽略该表。升级失败可重试，或按升级前备份恢复。当前单进程服务拒绝同编号的并发生成；会话与回执在同一事务提交，并以编号唯一约束阻止重复落库。旧客户端未提供编号时沿用原创建方式。

Web 迁移 `20260913_0014` 新增 `story_generation_drafts`，以版本 1 保存已校验正文、输入快照和生成时的世界背景。暂存先独立提交，成功创建会话后在会话事务内删除；保存失败保留暂存，重试只执行保存。升级支持重复执行，回退代码保留暂存表。数据库连暂存写入也无法完成时，当前请求返回保存错误。

Android 使用 Room migration，schema 位于 [app/schemas/](../android/app/schemas/)，迁移实现位于 [AppDatabase.kt](../android/app/src/main/java/com/mojing/app/data/local/AppDatabase.kt)。

## CI 与交付

[Android 工作流](../../.github/workflows/android-build.yml)在 Android 路径相关的 main 推送、PR 和手动运行时执行 JVM 测试与 Debug 构建；非 PR 运行还生成 Release APK。产物上传为 Actions artifacts。

GitHub Releases 由发布流程单独上传 APK。发布时沿用项目版本与签名配置，安装包放入 Release assets，源码通过 Git 管理。

## 文件管理

`.env`、数据库、媒体、签名文件和 `local.properties` 属于本机状态。日常构建使用现有依赖与缓存，临时输出放在 `.codex-work/`，交付文件放在 `outputs/`。

提交按本批文件清单暂存，检查 `git diff --cached` 后创建独立提交。产品介绍维护在 [PROJECT.md](../PROJECT.md)，后续工作维护在[优化目标](../../docs/PRODUCT_GAP_MAP.md)。

## 品牌图标

`frontend/scripts/generate-brand-assets.mjs` 维护留白山形与流动墨线的矢量路径，生成 Android 自适应前景、单色图标、各密度启动图标，以及 Web 的 SVG、192px 和 512px PNG。使用浏览器回归所配置的 Playwright 环境运行：

```powershell
node mojing/frontend/scripts/generate-brand-assets.mjs
```

启动页复用 Android 前景资源，标语维护在字符串资源中。

### Web 引用草稿

`chatDraftStorage.ts` 保留原有 v1 文字草稿键，引用使用独立的 `mojing:chat-quote:v1:<sessionId>` 记录，保存消息 ID、发言者和最多 120 字符的已选原文首行。引用不保存完整长消息或附件。读取时校验会话与字段，损坏的引用记录不影响文字草稿；发送确认通过引用修订号保护期间的新选择。

### 世界资料回归

`tests/test_android_unified_world_schema.py` 使用独立 SQLite 检查 Room 19→20 的新增表、重复执行、原表结构、外键与完整性。`PromoteWorldTemplateUseCaseInstrumentedTest` 覆盖本地归入、条目复制与重复操作；设备运行应使用隔离应用数据。

GitHub Actions 的优化测试包命名为 `mojing-ci-test-not-for-upgrade`。正式交付 APK 使用本机签名配置构建，构建完成后检查包内版本和签名。
