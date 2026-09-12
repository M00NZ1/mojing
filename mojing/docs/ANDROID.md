# Android 开发指南

墨境 Android 使用 Kotlin、Jetpack Compose、Room、Hilt 与 OkHttp。应用入口、界面、模型请求和本地数据管理均位于原生工程。

## 界面组件

`ui/theme/Theme.kt` 统一八套主题的语义颜色、表面层级和圆角，`Type.kt` 维护标题、正文和标签排版。表单使用 `ui/common/MoJingControls.kt` 的输入框与按钮；组件保留原有键盘、焦点、错误、禁用和输入值接口，业务页面可传入必要的覆盖样式。

`NavAnimations.kt` 定义前进与返回的淡入、轻位移过渡；底部导航独立处理系统导航栏与键盘边距。启动页的柔光仅作用于装饰背景，文字保持清晰。图标包含自适应前景、单色版本和各密度 PNG。

`MainActivity` 通过 `SystemBarAppearance` 统一管理状态栏与导航栏图标明暗；品牌开屏展示期间使用浅色图标，退出后按主题背景更新。开屏显示状态使用可恢复状态保存，页面重建不会重新显示已结束的开屏。

## 构建配置

| 配置 | 值 |
|---|---|
| namespace / applicationId | `com.mojing.app` |
| minSdk | 26 |
| targetSdk | 35 |
| compileSdk | 36 |
| JDK | 17 |
| Room schema | 20 |

界面组件使用 Compose BOM 2025.10.01（UI / Foundation 1.9.4、Material 3 1.4.0）。`StoryWorkspace.kt` 提供故事主卡、资料入口与分组标题；`MoJingControls.kt` 统一填充输入框及按钮状态，`ChatModelTitle.kt` 展示聊天模型选择入口。

版本与构建设置位于 [app/build.gradle.kts](../android/app/build.gradle.kts)。

## 源码结构

源码根为 `android/app/src/main/java/com/mojing/app/`。

| 目录 | 职责 |
|---|---|
| `ui/` | Compose 页面、ViewModel、导航与主题 |
| `domain/` | 对话、上下文、记忆、生成与格式解析 |
| `data/local/` | Room 数据库、Entity 与 DAO |
| `data/repository/` | 数据访问与业务状态 |
| `data/remote/` | 模型、图片、语音与网络协议 |
| `data/prefs/` | 界面偏好 |
| `di/` | 依赖注入 |
| `media/` | 媒体处理 |
| `service/` | Android 服务 |
| `util/` | 通用工具 |

## 导航与界面

[NavGraph.kt](../android/app/src/main/java/com/mojing/app/ui/navigation/NavGraph.kt)管理会话列表、聊天、创作中心、角色、百科、工坊、故事推演、生成记录和设置。

顶部提供模型切换、会话菜单和设置资料入口。会话菜单包含故事线、搜索、导入和导出。输入框与操作栏使用统一面板，图片直接添加，语音、表情、旁白、配图及试听归入输入工具，快捷词按需展开。

会话资料使用可横向浏览的角色、世界、记忆、事件和书签标签。角色页的发言方式、添加入口与角色列表统一滚动；世界线路存在未保存修改时，切换资料支持继续编辑或放弃修改。

世界页优先展示玩法开关，专用线路按需展开，对话、配图与朗读配置统一保存。收起线路或调整玩法时保留线路草稿。

消息点击与长按打开底部操作面板，首排提供复制、编辑和可用的重新生成；引用、收藏、朗读、续写、分支与撤回分组排列。生成期间禁用冲突操作，复制、收藏、朗读与保存图片保持可用。

聊天界面由消息区域、输入区和覆盖层组成。导航调整沿用现有 helper，键盘与系统栏 Insets 在对应容器处理。界面开发覆盖系统返回、输入栏可见性、弹层层级、小屏、分屏与字体缩放。

角色列表按置顶、收藏、创建时间排序，百科筛选沿用同一顺序。生成记录按任务状态分类，展示进度、详情和结果入口。

## 平台与模型

平台配置独立保存名称、地址、Key 和模型列表，支持多个同类平台。预设为 DeepSeek、OpenAI、硅基流动、Anthropic 和自定义。

模型可通过接口获取或手动填写。聊天页按平台分组选取模型，会话保存选择，从下一次发送生效；正在生成的回合继续使用原线路快照。

旧单平台配置按版本迁移到平台目录，公共文字线路与默认平台保持同步。相关实现位于 [ConfigRepository.kt](../android/app/src/main/java/com/mojing/app/data/repository/ConfigRepository.kt) 与 [SecureStorage.kt](../android/app/src/main/java/com/mojing/app/data/SecureStorage.kt)。

## 对话与记忆

[ContextBuilder.kt](../android/app/src/main/java/com/mojing/app/domain/engine/ContextBuilder.kt)负责上下文组装，[MemoryCompactor.kt](../android/app/src/main/java/com/mojing/app/domain/engine/MemoryCompactor.kt)负责自动摘要。

消息撤回在 Room 事务中检查故事线、检查点和编辑来源，计算受影响摘要并回退尾部进度。选中回复撤回时处理版本回退，历史窗口定位到附近消息。

自动摘要使用有界快照；模型返回后重新核对故事线修订、前段摘要和有效原文，再保存分段。自动事件批量核对来源、去重并提交，时间线按当前故事线刷新。

切换与重生成回复在同一事务中回退分段摘要，起点取新旧采用消息中较早的位置。重复选择保持原进度。继承摘要按编辑来源和分支回复选择过滤，保留较早有效摘要；消息刷新同步更新记忆面板。

长消息由 `MemoryCompactionInput` 按保守字符权重分段，原文预算为 4,000，承接摘要预算为 2,400；ASCII 字符权重为 1，其余 UTF-16 单元为 2。分段保留消息编号、发言类型与接续标记，保持 Unicode 代理对完整。正文处理在后台调度器执行，逐段合并摘要与关键事实，全部完成后复核来源并提交。输入区显示当前整理段数，停止操作取消后续请求。

生成任务将内容与进度同步保存。暂停在当前步骤完成后生效，任务状态持久化，续跑读取保存的进度。

自动百科整理由 `SedimentEngine` 提取条目，`SedimentStore` 管理来源快照与事务提交。快照包含百科、会话、故事线、修订和最多十条来源消息；提交时复核有效原文、修订、百科关联与自动整理开关。整批条目通过格式检查后写入，使用 `inferred` 标记，并在 `metaJson` 中记录来源消息和故事线。相同快照下标题与正文一致的内容去重；角色设定由显式编辑流程保存。

## 本地数据与资料交换

`SaveCharacterEntryUseCase` 根据数据库中的来源与角色关联处理编辑保存。带会话来源且未关联角色的条目独立保存，确认状态与扩展资料编辑沿用这一身份；已有角色镜像继续通过关联 ID 同步。编辑页由持久化条目生成“对话资料”提示。

条目编辑通过 `saveEdited` 在同一 Room 事务中读取旧正文、分配版本号、保存历史快照并更新条目。未变更内容不新增版本，保存失败整体回滚，已删除条目拒绝旧页面提交。

版本页按递增记录 ID 倒序进行游标查询，每次读取十一条判断下一页，保留十条作为当前窗口。翻页替换窗口并重置阅读位置，保存完成后显示最新版本。

[AppDatabase.kt](../android/app/src/main/java/com/mojing/app/data/local/AppDatabase.kt)维护 Room schema 与 migration。媒体使用应用文件或授权 URI，平台凭据由 SecureStorage 管理。

初始资料从 [seed_data.json](../android/app/src/main/res/raw/seed_data.json)读取，与[内置目录](../data/builtin_pack/starter_catalog.json)对应。目录包含“雾港来信”百科、世界和两名角色，初始化记录使用 `builtin_catalog_v2`。

角色 PNG 解析使用 [CharacterCardPngCodec.kt](../android/app/src/main/java/com/mojing/app/domain/util/CharacterCardPngCodec.kt)，WorldInfo 和聊天记录分别由领域解析器处理。格式说明见[角色卡与聊天记录兼容](酒馆生态与角色卡兼容调研.md)。

从不同应用标识的早期版本迁移时，通过旧应用导出、墨境导入完成数据转移。

## 构建与签名

从仓库根目录执行：

```powershell
Set-Location .\mojing\android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

APK 输出目录：

- Debug：`android/app/build/outputs/apk/debug/`
- Release：`android/app/build/outputs/apk/release/`

Release 启用 R8 与资源收缩，输出 arm64-v8a、armeabi-v7a 和 universal APK。

本机 `android/local.properties` 可配置 `KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。存在有效签名文件时使用 release 签名配置，否则使用当前环境的 debug 证书。连续覆盖安装使用同一签名证书。

## 开发检查

JVM 测试位于 `app/src/test/`，Room 与 Compose 测试位于 `app/src/androidTest/`。连接设备后可运行 `connectedDebugAndroidTest`。

数据变更覆盖旧 schema 迁移、事务回滚和恢复；交互变更覆盖键盘、返回、旋转、文件选择与权限。完整命令见[构建与维护](MAINTENANCE.md)。

### 对话创作草稿

旁白方向由 ChatViewModel 管理，随会话草稿存入现有 `chat_drafts_v1`。JSON 使用可选字段 `narratorGuidance` 和配图描述 `imagePrompt`，旧记录缺失时读取为空；消息正文、附件和提交标记保持原有语义。方向写入对话成功后清空对应草稿，期间改写通过修订号保留。

配图描述使用独立修订号，沿用同一草稿存储。生成请求被接受后关闭面板，图片和附件记录写入成功后清空对应描述；失败与取消保留描述。旧记录缺失该字段时读取为空，Room schema 保持不变。

### 引用草稿

`ChatDraftStore` 在 v1 草稿中保存可选的 `quotedMessageId`，旧草稿缺省为空。会话初始化按消息 ID 与会话 ID 读取原文，原文不存在时移除引用并保留输入。发送交接标记已提交时同时清除文字与引用；恢复读取和发送完成使用引用修订号保护期间的新选择。此扩展不修改 Room schema，旧版本可忽略新增字段读取其他草稿内容。

## 小说开篇与记忆请求

`CreateSessionUseCase.create(initialMessages)` 通过 `SessionCreationTransaction` 在同一 Room 事务中写入会话、世界配置、角色与开篇消息。任一写入失败均回滚；消息继续使用现有 DAO 更新检索字段。

`StoryOpeningDraftStore` 在现有 `app_config` 中使用独立键 `story_opening_draft_v1` 保存版本化开篇快照，包含正文、玩法、选定角色 ID 与完整 UUID。Room schema 保持 19；旧版本无此记录时正常进入创作表单。草稿随现有数据库备份恢复，不改变 Web 交换格式。

`SessionCreationTransaction` 核对 UUID，在会话与全部章节提交时将 pending 草稿更新为 saved 回执。重复保存复用会话 ID；待保存正文和完成回执分别按状态清除，旧页面不能重新写入已交接的草稿。`StorySimulationViewModel` 重进后读取草稿或回执，提供复制全文、继续保存、打开会话和开始新作。数据库读写与恢复解析在后台执行。

不支持的草稿版本或损坏内容保留原始记录，界面支持重新读取、复制恢复数据与确认清除。磁盘写入失败时保留页面内完整正文；章节或回执写入失败时整个会话事务回滚。恢复测试覆盖真实文件数据库关闭重开、故障注入、旧 UUID、回执清除，以及测试进程结束后的页面恢复。

`StorySimulationViewModel.inputRevision` 只随用户实际修改的文本、章节数量与选择递增。请求结果及错误以提交时的输入修订判断归属；资料列表的异步刷新和自动过滤不改变修订。生成与保存均复用提交时的世界、百科和角色快照。

小说开篇通过 `LlmRetry.chatCompletionStreamingWithRetry` 消费流式正文，保留完成协议校验、取消传播与有界重试。请求总时限为 5 分钟，OpenAI 兼容线路读取空闲上限为 90 秒；收到正文后不自动重发。`StoryStreamingPreviewParser` 增量提取章节内容，界面保留最近 12,000 字符的预览。

通用记忆使用明确的对象和数组结构，解析时校验嵌套字段类型。文本字段收到字符串数组时逐项换行保留，事实列表收到单个字符串时转换为单项列表；对象、数字和空值不作为文本事实接收。自动失败按会话、故事线和请求线路短暂冷却；重建与清空会重置冷却，过期修订不推进来源位置。

开篇和通用记忆为独立的结构化输出任务。已适配的 DeepSeek 混合模型使用 `response_format: json_object`，官方接口附加 `thinking.type: disabled`，硅基流动附加 `enable_thinking: false`；普通聊天沿用原有模型参数。记忆输出预算为 3,200 Token，提示词按字段压缩和去重；接口返回 `finish_reason: length` 时按输出不完整处理。参数定义见 [DeepSeek](https://api-docs.deepseek.com/guides/thinking_mode/) 与[硅基流动](https://docs.siliconflow.cn/docs/api/chat-completions-post)。

`RealProviderStoryTest` 提供显式启用的真实平台验收，使用独立 Room 数据库与偏好存储。临时 Key 从测试应用私有文件读取，测试参数仅传入 `realProviders=true`；正常测试和 CI 不会调用付费接口。覆盖单章、双章、记忆重建与清空、生成前后取消、无效模型修正后重试。测试完成后删除临时 Key；`captureProviderResponses` 仅用于保存该测试的中性故事请求与响应，不保存认证头。

### 对话与创作设置

个性化姓名与自我描述参与聊天、旁白和小说创作上下文。外观设置提供对话字体、旁白斜体与预览，偏好保存在本机。小说创作支持风格预设和自由输入；百科详情支持直接重命名，批量生成数量默认 1。

## 统一世界资料

`PromoteWorldTemplateUseCase` 在 Room 事务中复制旧工坊背景与 Lore，写入 `legacy_world_mappings`、`legacy_lore_mappings`。重复操作返回原世界；旧原文保持不变。同名资料分别保留，条目记录旧类型、关键词、核心标记与来源。

创作页集中角色与世界入口，新对话和小说创作统一世界选择。世界设置通过修订条件更新名称、简介、背景、玩法与规则；生成整包保存与归入同属一个事务。已有会话和恢复草稿保留其原始设定。

`MIGRATION_19_20` 仅增加映射表与索引，保留原数据库。旧模板、旧条目及关联世界受到外键保护；删除百科条目会清空对应映射目标，保留来源记录。
