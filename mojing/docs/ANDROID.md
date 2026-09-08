# Android 开发指南

墨境 Android 使用 Kotlin、Jetpack Compose、Room、Hilt 与 OkHttp。应用入口、界面、模型请求和本地数据管理均位于原生工程。

## 工程配置

| 配置 | 值 |
|---|---|
| namespace / applicationId | `com.mojing.app` |
| minSdk | 26 |
| targetSdk | 35 |
| compileSdk | 36 |
| JDK | 17 |
| Room schema | 19 |

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
