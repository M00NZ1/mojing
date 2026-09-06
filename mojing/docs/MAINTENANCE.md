# MoJing 构建、验证与交付

本文只保留当前可执行的维护流程。历史施工日志、个人设备名称、绝对路径和一次性测试记录不属于公开维护文档。

## 1. 环境

| 模块 | 建议环境 |
|---|---|
| Backend | Python 3.11+ |
| Web | Node.js 20+、npm |
| Android | JDK 17、Android SDK、Gradle Wrapper |

从仓库根目录准备 Web/Backend：

```powershell
Set-Location .\mojing
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r .\backend\requirements.txt
Copy-Item .\.env.example .\.env

Set-Location .\frontend
npm install
Set-Location ..
```

`.env`、`mojing_config.json` 和 Android `local.properties` 都是本机文件，不得提交真实值。

## 2. 启动与停止

### 一键启动

```powershell
Set-Location .\mojing
.\一键启动.bat
```

默认监听：

- Web：`127.0.0.1:5175`；
- Backend：`127.0.0.1:8000`。

### 分别启动

```powershell
# Backend
Set-Location .\mojing
.\.venv\Scripts\python.exe -m uvicorn backend.app.main:app --host 127.0.0.1 --port 8000

# Web（另一个终端）
Set-Location .\mojing\frontend
npm run dev
```

停止前先确认 PID、端口和启动路径确实属于当前工作区。不要按进程名批量结束其他项目的 Python、Node 或 Java 进程。

## 3. 自动验证

只运行与改动风险相称的子集；跨数据、并发、导航、协议或核心交互的改动需要提高验证等级。

### Backend

```powershell
Set-Location .\mojing
.\.venv\Scripts\python.exe -m pytest
```

定向测试示例：

```powershell
.\.venv\Scripts\python.exe -m pytest .\tests\test_sessions.py -q
```

### Web

```powershell
Set-Location .\mojing\frontend
npm run build
```

仓库内的 `.test.ts`、`.test.tsx` 和 `.test.mjs` 应按 `package.json` 中现有脚本运行。不要用生产构建替代交互测试。

### Android

```powershell
Set-Location .\mojing\android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

设备/模拟器测试只在已有、可确认的数据安全环境中运行：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

不要擅自启动、重置、擦除或覆盖用户的模拟器和真机应用数据。

## 4. 验证分层

报告结果时使用以下边界：

| 证据 | 能证明 | 不能证明 |
|---|---|---|
| 静态源码/配置 | 引用、结构和配置一致 | 运行行为正确 |
| Backend 测试 | 被覆盖的服务与数据行为 | 浏览器/设备交互 |
| Web build | TypeScript/打包链可完成 | 页面流程、触控和网络恢复 |
| Android JVM 测试 | 被覆盖的 Kotlin 逻辑 | Room 设备迁移、IME、系统返回 |
| Android build | 编译、资源、R8/打包边界 | 安装、冷启动和真实交互 |
| 浏览器运行 | 当前浏览器中的实际流程 | Android 行为 |
| 模拟器 | 指定 API/镜像上的实际流程 | 真机厂商差异和闪存性能 |
| 真机 | 指定设备上的真实交互 | 其他设备和数据规模 |
| 性能测试 | 给定数据/设备的指标 | 未测规模或环境 |

用户反馈应标记为“用户报告”；Agent 独立运行应单独记录。

## 5. 手工验收

### Web

- 首次启动、无配置、空数据、加载失败和重试；
- 新建会话、发送、停止、失败恢复、重试和继续生成；
- 草稿在刷新、离页和会话切换后的隔离与恢复；
- 编辑、分支、搜索、跳转、收藏和历史分页；
- 角色、百科、模板的创建、导入、删除和失败恢复；
- 桌面与窄屏；浏览器前进/后退；中文输入法；
- 附件、图片、语音和文件选择器的取消/失败路径。

### Android

- 冷启动、进程重建、旋转、分屏；
- 系统返回、手势返回、底部导航和深链；
- IME 打开/关闭、输入栏可见性、光标插入和覆盖层；
- 发送、停止、重试、编辑、分支与生成单飞；
- 权限拒绝、文件取消、云端 URI、媒体保存失败；
- Room 迁移、备份恢复、导入导出往返；
- 小屏、字体放大、深色/浅色主题；
- 大型会话的打开、滚动、搜索、跳转和继续生成。

## 6. 数据保护

以下状态默认受保护：

- `backend/storage/`、SQLite、WAL/SHM、媒体；
- `.env`、`mojing_config.json`、`local.properties`、签名和 API Key；
- `data/sessions/`、Android 应用数据、备份和用户导入资源；
- 任务前已有的修改、未跟踪/ignored 文件、缓存和恢复目录。

规则：

1. ignored 不等于可删除；
2. 不使用 `git clean`、`reset --hard` 或强制 checkout 清理不明状态；
3. schema/格式迁移前先校验，并提供幂等路径和失败恢复；
4. 派生索引可重建，原始消息、角色、设定和手动记忆不可随意重建；
5. 日志、测试快照、公开导出和 Git 不得包含完整密钥或认证头。

## 7. Android 标识与数据迁移

当前 namespace/application ID：`com.mojing.app`。

采用新标识后，早期预改名构建与 MoJing 会作为不同应用存在。迁移流程必须是：

1. 在旧应用中完成本地导出；
2. 保留旧应用和原始数据，直到导入验证成功；
3. 安装 MoJing；
4. 导入并核对角色、会话、分支、百科、模板和媒体；
5. 只有用户确认后，才自行决定是否卸载旧应用。

构建任务不得自动卸载旧应用、清除数据或覆盖备份。

## 8. 发布检查

发布前核对：

- Git remote、base、branch、HEAD、merge-base 与工作区状态；
- staged 文件只包含当前任务；
- `.env`、密钥、数据库、媒体、日志、缓存和本机路径没有进入 diff；
- 版本文件变化是当前任务明确授权的；
- Backend、Web、Android 的相关测试/构建已按风险完成；
- 导入导出版本、Room schema 和公开文档与代码一致；
- APK/制品名称使用 MoJing 品牌；
- 自动化通过、真实运行与未验证项分开报告。

未经明确授权，不 commit、push、创建 PR、改版本、发布或合并。

## 9. 清理

只有当前任务产生临时文件、构建缓存或临时工作区时才清理。清理前先列出精确候选，确认它们由当前任务创建、可重建、未被进程或配置使用，并严格位于任务目录。

不得自动删除数据库、WAL/SHM、媒体、备份、签名、配置、用户资源、既有 Gradle/Node/Python 环境或用途不明目录。


## 10. Web 平台配置回归与兼容

- 平台目录使用现有 `app_settings` 中的 `model_platforms_v1`，内部 `version=1`；会话选择使用 `chat_model_choice_<session_id>`，只保存平台标识与模型名。
- 首次读取只投影旧 `local_config` 的文字线路，首次显式保存才写入目录。旧文字字段原样保留；其他设置保存不会把旧页面草稿覆盖到新平台目录。未知版本/无效配置拒绝覆盖，平台保存采用一次数据库提交；没有数据库 schema 升级。
- 回退旧代码可读取保留的旧文字配置。进行任何人工修复前，先保留一致性数据库副本与原有本机密钥文件；不要删除原数据库、密钥文件或平台目录来尝试修复。新建的平台仍保存在目录中，回退旧代码不会自动迁入旧单平台界面。
- 平台 Key 复用现有本机凭据存储，API 返回掩码；普通备份递归清除目录中的 Key。恢复普通备份后需重新填写 Key；模型名与会话选择保留。
- 聊天选择在请求入口解析成不可变线路快照，覆盖同回合角色与旁白；修改选择不会改变已开始的请求。选择失效时在创建用户消息前返回明确错误。
- Anthropic 模型发现遵循 [Models API](https://platform.claude.com/docs/en/api/models/list)；Web 对话仍使用已有 [Chat Completions 兼容接口](https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk)，原生提示缓存、思考细节等能力不在本批范围。

定向后端验证（在 `mojing/`）：

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_model_platforms.py tests/test_text_config_fallback.py tests/test_stream_character_reply.py tests/test_secret_storage.py tests/test_project_backup.py tests/test_branch_context.py -q
```

前端验证（在 `mojing/frontend/`，不执行生产打包）：

```powershell
.\node_modules\.bin\tsc.cmd --noEmit
# 使用已安装的 Playwright；若未在当前模块路径中，可将 PLAYWRIGHT_MODULE 指向已有包目录。
# 可通过 SMOKE_BROWSER=chrome 使用本机 Chrome，SMOKE_OUTPUT 指定截图输出目录。
node scripts/test-model-platforms.mjs
```

浏览器脚本自行启动/关闭独立 Vite 进程，默认使用空闲端口 15175；所有 API 由浏览器拦截模拟，并阻止其他外部请求，不启动真实 Backend、不访问用户数据。覆盖保存/重进、Key 隔离、取消获取、手填、未保存离页、窄屏、5,000 模型虚拟列表、选择重试及聊天发送。此测试不能替代真实供应商、浏览器键盘弹出或 Android 真机验收。

## 11. 世界包导入与导出回归

- 交换格式仍为 v1，无 schema 迁移。旧包可省略版本与可选字段；未知版本、字段无效、数量不符、重复标识或内置覆盖冲突在写入前拒绝。
- 单包/合集导入和成功记录共用一次提交。任何校验、写入或提交前异常先回滚，再记录失败；不再提前提交清空操作。相同标识模板保留数据库 ID，替换模式仅删除包外自定义模板。
- 导入失败可以修正包后重试；重复覆盖不生成额外模板。成功的显式替换仍会移除包外模板，如需撤销成功替换，应在导入前保留导出包并通过预览恢复。不能靠回退代码找回已经成功替换的内容。
- 回退代码不需要降级数据库或数据格式，但会恢复旧导入风险；不得以删除数据库处理导入错误。

定向验证（分别在产品根与 frontend 目录）：

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_world_package_atomicity.py -q
node scripts/test-world-transfer.mjs
```

浏览器脚本与模型配置脚本使用相同 `PLAYWRIGHT_MODULE`、`SMOKE_BROWSER` 和可选 `SMOKE_OUTPUT` 约定，默认独立端口 15176；模拟全部 API 并阻止外部请求，只关闭自己启动的进程。验证桌面/窄屏导出可见、失败重试、单包及合集下载完整性。SQLite 样本覆盖 100 世界、2,000 Lore、约 800 万中文字符的重复导入与往返；这是正确性验证，不是 UI 性能或真实设备验收。

## 12. 世界生成记录与结果恢复

- 复用 `job_runs.output_json`，完整结果采用 `world_result_version=1` / `world_result`，无数据库 schema 迁移。旧记录原样保留；没有完整结果或不支持的格式拒绝恢复，不伪造正文。
- 结果与成功状态同一次提交，先于响应及可选自动保存；自动保存失败时结果仍可从记录找回。保存世界与记录中的保存标记共用事务，SQLite 写事务串行处理重复请求；失败可重试，重复请求不覆盖后续编辑或新增重复模板。
- 记录列表使用 ID 游标、上限 50 的有界查询，SQL 只投影摘要；原 `/jobs` 列表也移除完整世界结果字段。详情单独读取；不缓存多个大结果，Lore 分页显示。尚未完成真实大规模记录性能验收。
- 整库便携备份包含原有任务表及新结果字段；单个世界导出仍只包含已保存模板与 Lore。回退代码不会删除新增结果字段，但旧 UI 无法显示恢复入口。不要删记录或数据库修复显示问题。

定向命令：产品根运行 `pytest tests/test_world_job_results.py`（使用项目虚拟环境）；frontend 运行 `node scripts/test-world-history.mjs`。浏览器脚本使用同样的模块/浏览器/截图环境变量，默认独立端口 15177，模拟 API 并阻止其他外部请求。覆盖桌面/窄屏空状态、状态显示、游标翻页、旧记录说明、详情失败重试、条目分页、保存失败重试、深链刷新及管理跳转。HTTP 测试仅挂载相关路由、注入临时数据库，不运行真实服务生命周期。

## 13. 世界请求取消与浏览器草稿

- 世界构建的在线调用改用既有 `build_async_client` / `safe_async_non_streaming_call`，每次调用通过异步上下文关闭客户端；请求监视器取消并等待当前协程结束，不创建脱离请求的后台线程。取消不会被本地回退或重试吞掉，记录只从 pending/running 转入 cancelled。
- 浏览器停止按钮使用 AbortController；离页确认可以继续原请求，或停止并离开。请求完成会关闭过期确认框；刷新/关闭页面仍使用浏览器原生提示。供应商已经接收的计算是否立即停止及其计费不能由客户端保证，真实线路停止时延尚未测量。
- 草稿使用当前源下的 IndexedDB `mojing-creation-drafts` v1 / `drafts` / `world`，数据包含 version、revision 和表单值。先读取、验证，再允许写入；新建时无旧格式迁移。事务按 revision 检测其他页面更新，冲突、未知格式或配额失败保留已有记录及当前输入。写入中的草稿受离页提示保护；故障时可先复制当前输入，修复浏览器存储后再重进，不删除旧数据库尝试恢复。
- 浏览器草稿不会随本机 Backend 的整库备份迁移；完整生成结果仍在 Backend 的原任务表。回退前端代码不会删除草稿数据库，但旧界面不会恢复它。未完成的生成步骤尚无持久化检查点，停止后重新生成；服务器崩溃后的旧 running 记录仍待恢复机制处理。

定向验证：产品根运行项目虚拟环境的 `pytest tests/test_world_cancellation.py`；frontend 运行 `node scripts/test-world-cancellation.mjs`，使用既有浏览器环境变量，默认独立端口 15178。全部模型请求为模拟上游，HTTP 路由注入临时 SQLite；浏览器测试使用独立空上下文和模拟 API，覆盖 12 万字源文本重进恢复、取消竞态、跨页冲突、配额异常和未来草稿版本原样保留，不访问用户数据库或真实供应商。

## 14. 内置目录升级

- `data/builtin_pack/starter_catalog.json` 是正式目录内容源，Android `res/raw/seed_data.json` 是打包分发副本；调整内容时同步两份，并通过 `test_starter_catalog.py` 的一致性断言。
- Web 在既有 `app_settings` 写入 `builtin_catalog_v2` 标记（内部 version=1），与新百科、条目、关系、时间线、世界和角色同一事务提交。没有 schema 迁移；失败全回滚，可在修正故障后再次启动。已有标记时不再安装或补回已删除示例；同名用户资料保留，示例使用不冲突的名称/标识。
- 旧种子程序只在临时内存数据库重建参考，不再对用户库执行覆盖/补充。收起前核对所有内容字段、条目数量及内容、数据库引用和默认世界配置。无法证明原样或存在引用的旧资料保持可见；更早、无法匹配参考版本的资料不会强行处理。
- 收起只记录旧 ID 与时间标识，不删除旧行。列表和普通世界合集导出排除收起的示例；整库备份仍包含原始行及标记。旧行后续被编辑或 ID 被复用时不会被旧标记继续隐藏。配套开局同时校验原始创建标识；资料删除后即使 ID 被复用，也不会带入无关资料。创作中心“恢复旧示例”清除收起状态，不覆盖现有内容，也不重新运行旧种子程序。
- 回滚首选恢复旧示例的可见性。直接回退到仍会覆盖/补回示例的旧代码会重新引入旧启动行为，不能将代码回退等同于数据恢复。任何人工修复前先保留一致性数据库副本；本批没有对真实用户库执行升级。

验证入口：项目虚拟环境运行 `pytest tests/test_starter_catalog.py tests/test_world_package_atomicity.py`；frontend 运行 `node scripts/test-starter-catalog.mjs`，使用既有 Playwright 环境变量，默认独立端口 15179。测试使用临时 SQLite 与隔离浏览器，包含配套会话创建、旧目录完整保留、失败回滚、恢复、配置引用及普通导出；浏览器覆盖桌面/窄屏开局、失败重试和资料缺失状态。尚未验证真实旧库升级耗时或 Android 真机迁移。


## 15. Web 角色导入与排序

- `CharactersPage` 的角色库和资料工具共用 `CharacterImportDialog`。同一次导入只有一个提交；失败保留选择可重试，成功后显式查看结果，不自动覆盖当前编辑。列表按收藏、创建时间、ID 倒序，新建和查看导入结果清理旧搜索条件。
- 便携角色与 profile 一次提交，失败回滚；PNG/JSON/链接角色卡复用同一保存函数。PNG 上传直接读取，不再创建或删除按文件名共用的临时文件。文件上限 32 MiB，链接使用 30 秒连接/读取超时；该值不代表整个下载的总时限。
- 便携交换仍为 v1，TXT/DOCX 元数据保留可选 profile；旧包没有 profile 时继续兼容，旧导出已遗漏的资料无法追溯恢复。零温度不再被默认值覆盖。DOCX 正文按 OOXML 段落和运行片段读取，保留实体文本、制表与换行；格式化外观不是纯文本导入的保留目标。
- 无 schema 或已有数据迁移；应用回滚不会删除本批导入的数据，但旧程序会重新出现导入的已知缺陷。
- 定向验证：`pytest tests/test_character_import_workflow.py tests/test_starter_catalog.py`；frontend 的 `tsc --noEmit` 与 `node scripts/test-character-import.mjs`（既有 Playwright 环境，独立端口 15180）。使用临时 SQLite、隔离 HTTP 应用和模拟 API 浏览器，不导入正式 app、不读取用户包、不发送供应商请求。真实下载和用户文件回归另行记录。


## 16. 世界生成检查点与中断恢复

- 新 Web 流程先 `POST /jobs/world-request` 保存输入，再 `POST /jobs/{id}/run-world` 执行；`world-progress` 只读摘要并校正已中断执行，`pause-world` 请求当前步骤保存后暂停。现有 `/worlds/generate`、`/worlds/import` 保留旧调用契约；旧记录不补造检查点。
- 无新表或 schema 迁移。输入使用 `world_request_version: 1` 与 `world_request`；中间结果使用 `world_checkpoint_version: 1`、`world_steps`、`completed_steps`、`stage_label`。步骤名称是 v1 的稳定键，修改流水线语义时必须升级检查点版本。未知格式拒绝执行且保留原记录。
- 原文只保存一次；每个步骤结果独立于世界库提交到原 JobRun。生成保存命名、骨架、条目；在线文本整理保存逐块、合并与整理结果。暂停在步骤提交后落为 `paused`；未提交的步骤在恢复时重做，已提交步骤经类型校验后复用。成功后以完整结果替代检查点，继续沿用幂等的保存世界流程。
- 状态：`pending → running → pause_requested → paused`；失败保留检查点为 `failed`；失去执行所有者为 `interrupted`；这些未完成状态可显式继续。`cancelled` 是停止，不能通过继续按钮重新运行；`succeeded` 不被晚到暂停/停止覆盖。没有后台任务队列，页面离开/请求断开仍会取消执行，应先暂停并等到保存成功再离开。
- 每个执行持有数据库同目录 `.<数据库文件名>.world-job-locks/<任务 ID>.lock` 的操作系统排他锁，Windows 用 msvcrt，其他平台用 flock。锁文件不删除，文件存在不代表进程仍活着。恢复扫描只分页检查带 v1 输入的新任务；只有能获得锁并重新确认仍是运行态时才标记中断，活跃实例不会因另一个实例启动或读取记录被取消。
- 暂停/续跑不保存 Key 或客户端；一次执行固定解析后的线路，下一次继续使用当前配置。在线错误保留失败与检查点，不静默回退成本地骨架。列表 SQL 排除原始输入和中间正文，运行或恢复步骤时才按需读取。
- 回滚前先暂停并等待当前步骤保存。旧代码仍能读取已完成世界，但不具备新检查点控制；保留 JobRun 和锁目录，重新升级可继续。无需删除数据库或用户文件。
- 验证入口：`pytest tests/test_world_checkpoints.py tests/test_world_cancellation.py tests/test_world_job_results.py tests/test_world_package_atomicity.py`；frontend `tsc --noEmit`、`node scripts/test-world-checkpoints.mjs`（独立端口 15181）、`node scripts/test-world-cancellation.mjs`。SQLite、HTTP、模拟在线调用及 Windows 子进程锁回归使用隔离数据；浏览器使用模拟 API。真实供应商时延、完整本机服务联调与非 Windows 文件锁未验证。
