# 墨境项目状态

更新日期：2026-09-10

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
- **Android 视觉与导航**：八套主题使用统一控件、文字和表面层级；首页、悬浮导航、页面过渡、应用图标和启动页已更新。启动标语保持“以墨为界，入境如梦。”。系统栏明暗随当前页面变化，小屏大字号保留主要操作入口。角色人设与百科正文采用有限高度的长文本输入框，支持展开、收起与框内滚动。
- **模型配置与切换**：平台独立保存 Key、地址和模型列表。预设为 DeepSeek、OpenAI、硅基流动、Anthropic 和自定义；支持发现与手动填写模型。聊天选择从下次发送生效，当前轮使用固定线路。Android 面板展示下次发送和最近请求，支持搜索、保存状态与失败重试。
- **对话与记忆**：引用使用独立预览，头像保留固定空间。记忆、事件和用户纠正按当前故事线刷新，长文本按需展开；定位原文请求未被接受时保留入口。Web 编辑失败保留草稿，离开页面后的旧返回不会激活错误故事线。Web 支持独立排除和恢复消息上下文。
- **创作与生成记录**：单章生成兼容章节对象；旁白接入事件整理与百科沉淀。生成记录区分加载、空与错误状态，支持暂停、继续和保留进度的重试。Android 取消失败保留确认框；结果读取失败保留详情，关闭或离开后取消查询。
- **Web 控件**：按钮提供主题对应的默认、悬停、按下、禁用与键盘焦点状态。确认弹窗长说明独立滚动，底部操作保持可达；中文选词不误关弹窗。新确认替换旧请求时，旧请求按取消结束。

## 验证记录

| 范围 | 源码与结果 | 入口或证据 |
|---|---|---|
| Web 消息菜单键盘操作 | Chrome 模拟 API 测试通过，覆盖菜单焦点、方向键、首尾定位、Esc/Tab 与输入法；消息编辑、删除、上下文操作和移动端回归通过 | `test-message-deletion.mjs` |
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
- 继续完善百科推断条目的批量确认、来源范围与编辑，以及大型历史的增量摘要整理。
- 大型历史目标是累计几十万至上百万 Token 后的打开、阅读、搜索、编辑与继续对话；单轮模型上下文单独预算。

完整排序见[优化目标](PRODUCT_GAP_MAP.md)。

## 本地状态与交付

当前分支为 `main`。近期修改已分批本地提交，尚未推送或发布新版；版本与 schema 保持不变。

既有 Android 下载记录：[1.0.21 · 2026-09-09](https://github.com/M00NZ1/mojing/releases/tag/build-20260909-3992bfb0)。下次发布需递增版本并核对 APK 元数据。

正式数据库 `mojing/backend/storage/app.db` 为 3,891,200 字节，修改时间为 2026-08-28 23:23:12。本批未修改数据库、媒体、凭据、签名或历史 worktree；测试 AVD 当前已停止，测试日志与截图保留在 `.codex-work/`。

构建、测试与运行命令见[构建与维护](../mojing/docs/MAINTENANCE.md)。
