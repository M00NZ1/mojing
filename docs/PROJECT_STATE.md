# 墨境项目状态

更新日期：2026-09-11

## 工程

| 项目 | 当前配置 |
|---|---|
| 产品 | 墨境 MoJing |
| 项目路径 | `F:\PYthon_Project\work\DeepSeek_Project` |
| 正式源码 | `mojing/` |
| Web | React、TypeScript、Vite；本机 FastAPI、SQLAlchemy、SQLite |
| Android | Kotlin、Jetpack Compose、Room |
| Android 应用标识 | `com.mojing.app` |
| Android 版本 | 1.0.21 / 10021 |
| Room schema | 19 |

## 当前能力

产品功能与实现统一维护在[产品与架构](../mojing/PROJECT.md)。此处记录近期完成的流程和继续工作的入口。

Android 百科编辑提供“查看对话原文”，支持滚动、复制和失败重试；自动沉淀条目可逐条浏览来源并查看计数。可进入来源故事线定位当前消息，返回后保留编辑草稿。来源页等待原文定位完成后显示，加载失败、消息缺失或故事线删除时保留重试和返回百科入口。

百科确认状态位于正文前，列表与编辑页统一将自动沉淀显示为“对话推断”；选择“已确认”后通过现有保存入口提交，保留来源与失败重试草稿。沉淀资料提供全部、待核对、已确认筛选及数量，切换筛选从列表顶部开始浏览；卡片使用主题语义色与统一留白。编辑返回同一百科时保留主标签与条目分类，重新读取内容；已删除条目的预览自动清除。

- **角色与世界**：初始目录“雾港来信”包含一个百科、一个世界、沈照与林汐两个角色。角色可以不绑定百科直接开聊；百科支持重命名。开局区分工坊玩法和百科知识，组合设定可预览。
- **Android 视觉与导航**：门扉与笔尖图标覆盖 Android 启动图标、单色图标及 Web 安装图标；搜索增加清空操作，按钮加入按压反馈，底部导航采用独立选中面板。八套主题使用统一控件、文字和表面层级；首页、悬浮导航、页面过渡、应用图标和启动页已更新。启动标语保持“以墨为界，入境如梦。”。系统栏明暗随当前页面变化，小屏大字号保留主要操作入口。角色人设与百科正文采用有限高度的长文本输入框，支持展开、收起与框内滚动。
- **模型配置与切换**：平台独立保存 Key、地址和模型列表。预设为 DeepSeek、OpenAI、硅基流动、Anthropic 和自定义；支持发现与手动填写模型。聊天选择从下次发送生效，当前轮使用固定线路。Android 面板展示下次发送和最近请求，支持搜索、保存状态与失败重试。
- **对话与记忆**：引用使用独立预览，头像保留固定空间。记忆、事件和用户纠正按当前故事线刷新，长文本按需展开；定位原文请求未被接受时保留入口。Web 编辑失败保留草稿，离开页面后的旧返回不会激活错误故事线。Web 支持独立排除和恢复消息上下文。
- **创作与生成记录**：单章生成兼容章节对象；旁白接入事件整理与百科沉淀。生成记录区分加载、空与错误状态，支持暂停、继续和保留进度的重试。Android 取消失败保留确认框；结果读取失败保留详情，关闭或离开后取消查询。
- **Web 控件**：按钮提供主题对应的默认、悬停、按下、禁用与键盘焦点状态。确认弹窗长说明独立滚动，底部操作保持可达；中文选词不误关弹窗。新确认替换旧请求时，旧请求按取消结束。

## 验证记录

| 范围 | 源码与结果 | 入口或证据 |
|---|---|---|
| Android 已保存配图恢复 | 93 项 Chat ViewModel 测试、Debug 应用与测试 APK 构建通过；Android 35、320dp 下提示卡测试通过，覆盖保存后刷新失败、重复读取保护、读取失败重试与原消息定位，生成和消息插入各执行一次 | `ChatViewModelTest`、`SavedImageNoticeCardTest` |
| Android 配图输入与草稿 | 92 项 Chat ViewModel 测试、Debug 应用与测试 APK 构建通过；Android 35、320dp 下 3 项草稿和配图面板测试通过，横向窗口下配图面板测试通过，覆盖长输入、空白与忙碌状态、成功提交、失败、取消及新编辑保护 | `ChatViewModelTest`、`ChatDraftStoreInstrumentedTest`、`ImagePromptDialogTest` |
| Android 旁白方向草稿 | 90 项 Chat ViewModel 测试、Debug 应用与测试 APK 构建通过；Android 35、320dp 下 5 项草稿、输入栏与旁白界面测试通过；覆盖旧草稿读取、会话隔离、恢复、预检失败保留、写入完成清空与等待期间改写保护 | `ChatViewModelTest`、`ChatDraftStoreInstrumentedTest` |
| Android 旁白操作面板 | Debug 应用与测试 APK 构建通过；Android 35、320dp 窄屏下 3 项旁白与输入栏测试通过，横向窗口下旁白测试通过，覆盖空白方向、长输入、两种生成请求和返回操作 | `NarratorRequestDialogTest`、`InputBarAttachmentStateTest` |
| Android 百科主列表分页 | 19 项百科 ViewModel 测试、Debug 应用与测试 APK 构建通过；Android 35、320dp 下 3 项 Room 与分页控件测试通过，覆盖 205 条分类遍历、分页恢复、失败重试、重复翻页与批量补全全分类 ID | `EncyclopediaDetailViewModelTest`、`SedimentConfirmationDaoTest`、`SedimentPageControlsTest` |
| Android 关系条目选择 | 17 项百科 ViewModel 测试、Debug 应用与测试 APK 构建通过；Android 35、320dp 下 4 项 Room 与选择面板测试通过，覆盖精简字段分页、中文查询、标点按原文匹配、第二页、搜索失败重试、选择及关闭取消读取 | `EncyclopediaDetailViewModelTest`、`SedimentConfirmationDaoTest`、`EncyclopediaEntryPickerTest` |
| Android 沉淀资料分页 | 17 项百科 ViewModel 测试、Debug 应用与测试 APK 构建通过；Android 35、320dp 下 3 项 Room 与分页控件测试通过，覆盖 205 条分类遍历、每页上限、重复翻页保护、失败重试、筛选切换及同百科重载保留页码 | `EncyclopediaDetailViewModelTest`、`SedimentConfirmationDaoTest`、`SedimentPageControlsTest` |
| Android 个人资料保存 | 12 项设置与存储测试、Debug 应用与测试 APK 构建通过；Android 35、320dp 下 3 项保存反馈与设置分类界面测试通过，覆盖失败重试、重复提交保护、单次写入、成功回调及旧错误提示隐藏 | `SettingsViewModelTest`、`ProfileStorageTest`、`ProfileSaveActionsTest`、`SettingsSectionsTest` |
| Android 设置分类恢复 | Debug 应用与测试 APK 构建通过；Android 35、320dp 下 2 项界面测试通过，覆盖保存状态恢复、未保存修改拦截、一次性入口消费，以及实际 NavHost 保存并恢复设置后打开模型分类 | `SettingsSectionsTest` |
| Android 最近请求线路 | 88 项聊天 ViewModel 测试、Debug 应用与测试 APK 构建通过；Android 35、320dp 下 5 项模型面板测试通过，覆盖请求参数与平台名称匹配、下次发送与最近请求分离、长名称与搜索入口 | `ChatViewModelTest`、`ChatModelPickerTest` |
| Android 模型配置入口 | Debug 应用与测试 APK 构建通过；Android 35、320dp 下 5 项视觉测试通过，横向窗口专项测试通过；覆盖完整说明、整卡点击与无操作提示状态 | `VisualRefreshTest`；`.codex-work/ui-refresh-20260910/model-setup.png` |
| 公共视觉与品牌资源 | Android Debug 与测试 APK、Web 生产构建通过；Android 35、320dp 与横向窗口分别通过 4 项视觉测试，覆盖控件、搜索、导航、长确认说明和启动标语；长文本编辑测试通过；Web 七套主题对比度、确认弹窗、模型配置与长文本回归通过 | `VisualRefreshTest`、`MoJingLongTextFieldTest`；`.codex-work/ui-refresh-20260910/` |
| Android 生成记录浏览状态 | Debug 构建与 9 项 ViewModel 测试通过；覆盖 SavedStateHandle 恢复分类和第二页游标、筛选与游标原子更新、返回近期模式及既有失败重试 | `GenerationTaskListViewModelTest` |
| Android 全部生成记录 | 8 项 ViewModel 测试、Debug 构建通过；Android 35、320dp 下 12 项 Room 与界面测试通过，覆盖 205 条记录游标遍历、查询前筛选、每页上限、重复翻页保护、失败后切换与翻页位置复位 | `GenerationTaskListViewModelTest`、`GenerationTaskDaoTest`、`GenerationTaskDetailTest` |
| Android 生成详情错误布局 | Debug 应用与测试 APK 构建通过；Android 35、320dp 竖屏下 9 项界面测试通过，另通过 1440×840 横向窗口专项测试，覆盖长标题、100 段展开反馈与重试、关闭操作 | `GenerationTaskDetailTest` |
| Android 生成记录筛选与位置 | Debug 应用与测试 APK 构建通过；Android 35、320dp 下 8 项界面测试通过，150 条测试记录覆盖深处吸顶筛选、保存状态恢复及切换分类回到开头 | `GenerationTaskDetailTest`；`.codex-work/android-ui-20260910/filter-scroll.png` |
| Android 生成详情状态恢复 | Debug 应用与测试 APK 构建通过；Android 35、320dp 下 7 项界面测试通过，包含 Compose 保存状态恢复、列表重载、最新进度与结果操作、关闭后不重开 | `GenerationTaskDetailTest` |
| Android 生成反馈阅读 | Debug 应用与测试 APK 构建通过；Android 35、320dp 下 6 项界面测试通过，覆盖长反馈展开收起、内容更新复位、结果入口、关闭与取消重试 | `GenerationTaskDetailTest` |
| Web 生成结果读取取消 | 生产构建与桌面、390px Chrome 模拟 API 回归通过；浏览器请求事件确认详情读取中返回、保存成功后取消过期读取均触发 `ABORTED`；重进、保存及重试流程通过 | `test-world-history.mjs` |
| Web 生成结果保存状态 | 生产构建与桌面、390px Chrome 模拟 API 回归通过；覆盖保存失败后切页重进、保存中返回再打开、重复提交禁用，以及保存成功后旧详情响应返回 | `test-world-history.mjs` |
| Web 生成结果长文阅读 | 生产构建与桌面、390px Chrome 模拟 API 回归通过；200 段正文覆盖预览、展开高度、键盘滚动、收起复位、复制失败重试与全文一致性；结果保存及重进流程通过 | `test-world-history.mjs`；`.codex-work/world-result-reader/` |
| Web 生成记录操作与布局 | 生产构建与桌面、390px Chrome 模拟 API 回归通过；覆盖暂停提交禁用、失败重试、暂停后继续入口、长错误展开滚动、翻页及结果保存；修复长列表底部操作被导航遮挡 | `test-world-history.mjs`；`.codex-work/world-history-actions/` |
| Web 沉淀资料详情返回 | 生产构建与 Chrome 模拟 API 回归通过；覆盖组件卸载重进、第二页筛选与焦点恢复，以及实际百科页面正常详情、失败重试后的返回入口 | `test-sediment-review.mjs` |
| Web 沉淀资料分页 | 9 项后端测试与生产构建通过；1,200 条测试资料覆盖筛选与完整遍历、边界删除及新增资料；Chrome 模拟 API 回归覆盖前后翻页、每页 100 张卡片、加载失败重试、选择清理及 320px 布局 | `test_sediment_batch_confirmation.py`、`test-sediment-review.mjs`；`.codex-work/sediment-pagination.png` |
| Web 沉淀资料批量核对 | 7 项后端测试与生产构建通过；Chrome 模拟 API 回归覆盖筛选、100 条上限、失败保留选择、重试、保存锁定及刷新；320px 截图检查通过 | `test_sediment_batch_confirmation.py`、`test-sediment-review.mjs`；`.codex-work/sediment-review.png` |
| Android 沉淀资料批量确认 | 16 项百科详情 ViewModel 测试通过；Android 35、320dp 下通过 Room 内存数据库测试与批量操作组件测试，覆盖百科隔离、重复确认、正文与来源保留、失败重试、跨页旧返回、选择计数和保存禁用；Debug 应用与测试 APK 构建通过 | `EncyclopediaDetailViewModelTest`、`SedimentConfirmationDaoTest`、`SedimentBatchControlsTest`；`.codex-work/android-ui-20260910/sediment-confirm-tests.txt` |
| Web 默认模型选择组件 | 生产构建与 Chrome 模拟 API 回归通过；5000 模型下验证每页十项、翻页、搜索末项、空结果恢复、键盘展开/关闭和焦点返回；移动端组件截图检查通过 | `test-model-platforms.mjs`；`.codex-work/web-default-model-20260910/default-model-picker.png` |
| Web 模型列表键盘导航 | 生产构建与 Chrome 模拟 API 回归通过；5000 模型下验证跨窗口连续导航、首尾定位、跳过缺少 Key 项、Enter 选择、输入法保护及有界 DOM | `test-model-platforms.mjs` |
| Web 模型面板布局与反馈 | 生产构建及 Chrome 模拟 API 回归通过，覆盖 5000 模型虚拟列表、320×480 固定入口、选中状态、恢复默认、保存中关闭保护、失败重试与输入法保护；390px 截图检查通过 | `test-model-platforms.mjs`；`.codex-work/web-model-panel-20260910/model-picker-mobile.png` |
| Android 恢复默认模型配置 | 88 项聊天 ViewModel 与 7 项平台存储测试通过，覆盖恢复失败重试、会话隔离、重复恢复与重新读取；Android 35、320dp 下 5 项模型面板测试通过；Debug 应用与测试 APK 构建通过 | `ChatViewModelTest`、`ModelPlatformsTest`、`ChatModelPickerTest`；`.codex-work/android-ui-20260910/model-follow-tests.txt` |
| Android 模型选择显示 | 87 项聊天 ViewModel 测试通过；Android 35、320dp 下 4 项模型面板测试通过，覆盖同名平台选择、搜索空状态恢复、缺少 Key 禁用、保存失败重试与长线路说明；Debug 应用与测试 APK 构建通过 | `ChatViewModelTest`、`ChatModelPickerTest`；`.codex-work/android-ui-20260910/model-label-tests.txt` |
| Android 消息编辑保存反馈 | 87 项聊天 ViewModel 测试通过，覆盖写入前失败与已提交后加载失败的结果区分；Android 35、320dp 下 3 项编辑界面测试通过，覆盖保存锁定、失败重试、草稿保留与已提交禁用；Debug 应用与测试 APK 构建通过 | `ChatViewModelTest`、`MessageEditDialogTest`；`.codex-work/android-ui-20260910/edit-saving-tests.txt` |
| Android 消息编辑面板 | Debug 应用与测试 APK 构建通过；Android 35、320dp、1.4 倍字号下 2 项界面测试通过，覆盖 200 段长文编辑、未修改禁用、放弃确认与草稿保留；键盘截图检查通过 | `MessageEditDialogTest`；`.codex-work/android-ui-20260910/message-editor-tests.txt`、`message-editor.png` |
| Android 消息预览语义 | 14 项文本格式测试、Android 35 模拟器 320dp 宽度下 5 项界面测试通过；覆盖用户标签原文、操作面板滚动、撤回影响读取与失败重试；Debug 应用与测试 APK 构建通过 | `ChatMessageTextFormatTest`、`RecallMessageDialogTest`、`MessageActionSheetTest`；`.codex-work/android-ui-20260910/literal-preview-tests.txt` |
| Android 消息操作面板 | Debug 应用与测试 APK 构建通过；Android 35、320dp 宽度下 3 项界面测试通过，覆盖固定预览与关闭入口、列表滚动、原消息操作、生成中禁用和图片保存状态 | `MessageActionSheetTest`、`MessageActionPanelTest`；`.codex-work/android-ui-20260910/message-sheet-tests.txt` |
| Web 消息操作面板 | 生产构建与 Chrome 模拟 API 回归通过；覆盖桌面键盘菜单、移动端所选消息预览、320×480 独立滚动与固定关闭入口、输入法保护及消息操作 | `test-message-deletion.mjs`；`.codex-work/web-message-sheet-20260910/message-actions-390.png` |
| Web 角色图片更新 | 生产构建通过；Chrome 模拟图片 API 测试覆盖上传状态、失败重试、并发文字编辑及头像和立绘的跨角色隔离 | `test-character-images.mjs` |
| Web 角色补全与保存 | 生产构建通过；实际角色页的 Chrome 模拟 API 测试覆盖并发编辑、人设冲突、停止重试、保存期间输入、再次保存及旧页面响应隔离 | `test-character-ai-completion.mjs` |
| Web 长文本编辑 | 生产构建通过；Chrome 桌面及 320px 窄屏验证高度限制、键盘展开、ARIA 关联、草稿保留与表单提交边界 | `test-expandable-textarea.mjs` |
| Android 角色采样参数输入 | 8 项角色 ViewModel 测试通过；Android 35、320dp 宽度下 2 项界面测试通过，覆盖清空、连续负小数输入、错误恢复和键盘完成；Debug 应用与测试 APK 构建通过 | `CharacterEditViewModelTest`、`SamplingParameterFieldTest`；`.codex-work/android-ui-20260910/sampling-input-tests.txt` |
| Android 长文本输入 | Debug 应用与测试 APK 构建通过；Android 35 默认尺寸、320dp 宽度各通过 1 项界面测试，覆盖 200 段文本、展开收起与编辑保留 | `MoJingLongTextFieldTest`；`.codex-work/android-ui-20260910/long-field-tests.txt`、`long-field-small-tests.txt` |
| Android 百科浏览与刷新 | 14 项详情 ViewModel 测试通过，覆盖返回标签、确认后刷新、分类往返、旧查询晚到、删除预览与百科隔离 | `EncyclopediaDetailViewModelTest` |
| Android 沉淀列表筛选与样式 | Kotlin 主源码编译通过；本批界面未单独运行 | `gradlew.bat compileDebugKotlin` |
| Android 百科确认状态 | 14 项百科 ViewModel 测试、1 项 Android 35 选择器界面测试通过；Debug 应用与测试 APK 构建通过 | `EntryEditViewModelTest`；`.codex-work/android-ui-20260910/entry-confidence-tests.txt` |
| Android 百科多条来源 | 15 项 JVM 测试、Android 35 模拟器 320dp 宽度下 2 项预览界面测试通过；Debug 应用与测试 APK 构建通过 | `EntryEditViewModelTest`、`EncyclopediaSourceReferencesTest`；`.codex-work/android-ui-20260910/source-pages-tests.txt` |
| Android 百科来源故事线 | 本批 87 项聊天 ViewModel 测试通过，覆盖定位等待、失败重试、消息缺失和历史操作；主源码与界面测试源码编译通过。`108e7784` 批次 97 项聊天与百科测试、2 项 Android 35 路由与预览测试及 Debug 构建通过 | `ChatViewModelTest`、`EntryEditViewModelTest`；`.codex-work/android-ui-20260910/source-route-tests.txt` |
| Android JVM 全量 | 2026-09-10 采样参数输入批次：95 个测试类，551 项通过，失败、错误、跳过均为 0；耗时 32 秒 | `gradlew.bat testDebugUnitTest`；`mojing/android/app/build/test-results/testDebugUnitTest/` |
| Web 浏览器回归 | `b1c67e6a`：七套主题主按钮默认与悬停对比度达到 4.5:1；确认替换、中止、长说明、小屏和输入法通过；对话编辑、删除、上下文参与及历史定位回归通过 | `test-control-states.mjs`、`test-confirm-dialog.mjs`、`test-message-deletion.mjs` |
| Web 生产构建 | `b1c67e6a` 对应源码构建通过 | `npm run build` |
| Android Debug 构建 | `4c6ea007` 对应源码的应用与测试 APK 构建通过 | `gradlew.bat assembleDebug assembleDebugAndroidTest` |
| Android 视觉交互 | `e8883b2c` 批次 Android 35 模拟器通过 32 项界面测试 | `.codex-work/android-ui-20260910/redesign-ui-final.txt` |
| Android 系统栏 | `e3f50776` 批次通过开屏退出及八套主题切换测试 | `.codex-work/android-ui-20260910/system-bars-tests.txt` |
| Android 生成详情 | `4c6ea007` 批次通过 5 项界面测试，覆盖取消状态、长反馈、结果读取与重试 | `.codex-work/android-ui-20260910/result-detail-tests.txt` |
| Android 小屏 | `2566beb9` 批次在 840 × 1440、420 dpi、1.4 倍字号下确认首页开始入口无遮挡，角色与百科可滚动到达 | `.codex-work/android-ui-20260910/large-type-home-fixed.png` |

浏览器接口回归使用隔离测试响应。模拟器记录按批次保留，未合并表述为当前源码的全量设备验收。真机反馈来自用户使用的 1.0.21。

## 反馈与后续重点

- 用户报告切换模型后仍使用原模型。角色与旁白的定向回归已覆盖同平台和跨平台线路参数；继续对照实际使用入口与供应商请求。
- 用户报告单章生成出现英文异常，现有章节对象样本已修复；继续跟进实际生成反馈。
- Android 消息上下文参与状态仍与回复版本选择相关，需要提供独立的排除、恢复入口。
- Android 已支持沉淀条目批量确认；继续完善跨端核对、来源范围与编辑，以及大型历史的增量摘要整理。
- 大型历史目标是累计几十万至上百万 Token 后的打开、阅读、搜索、编辑与继续对话；单轮模型上下文单独预算。

完整排序见[优化目标](PRODUCT_GAP_MAP.md)。

## 本地状态与交付

当前分支为 `main`。近期修改已分批本地提交，尚未推送或发布新版；版本与 schema 保持不变。

既有 Android 下载记录：[1.0.21 · 2026-09-09](https://github.com/M00NZ1/mojing/releases/tag/build-20260909-3992bfb0)。下次发布需递增版本并核对 APK 元数据。

正式数据库 `mojing/backend/storage/app.db` 为 3,891,200 字节，修改时间为 2026-08-28 23:23:12。本批未修改数据库、媒体、凭据、签名或历史 worktree；测试 AVD 当前已停止，测试日志与截图保留在 `.codex-work/`。

构建、测试与运行命令见[构建与维护](../mojing/docs/MAINTENANCE.md)。
