# Android 视觉标准对应表

2026-10-02：下表保留2026-09-30共享控件的历史静态映射，表中“待验证”不是当前运行总状态。当前完成的整页修正、完整App默认/320dp大字/深色/宽屏/无图证据，以及仍未覆盖的真实供应商等边界，统一见 [验收计划](ANDROID_PROTOTYPE_REBUILD_PLAN.md) 和 [截图交付](../../outputs/android-ui-review-20261002/REVIEW.md)。本地已打包，尚未用户最终接受，未公开发布。

更新：2026-09-30 UTC。来源：[完整原标准](ANDROID_VISUAL_STANDARD_SOURCE.md)。初版对应表记录于暂停构建阶段；2026-10-01已按用户新授权完成1.2.2构建与有限设备检查，结果见[发布验证](../../docs/PROJECT_STATE.md)。旧1.2.1包和截图不证明本次纠偏；表中未覆盖的状态仍须验证。

普通卡片使用10dp、大内容面板使用12dp，保留原标准第4节和第7节区别。44dp输入/Tab视觉尺寸与Android48dp触控尺寸分开；系统大字号需要自然增长，不能用固定高度截断文字。现有八套主题选择、存储数据及业务回调不变。

| 原标准 | 实现位置（Android ui/下） | 源码/静态状态 | 待运行验证或实际缺口 |
|---|---|---|---|
| 1 颜色 | theme/MoJingDesignTokens.kt、Color.kt、Theme.kt | 主浅色精确原 token；自选主题保留 | 待浅色/深色及其余主题渲染、状态色覆盖 |
| 2 字体 | theme/Type.kt、chat/ChatDensityMetrics.kt | UI 全无衬线与标准字号；阅读字体偏好保留 | 待中文字体实际回退、100～150%放大 |
| 3 栅格 | common/MoJingListTokens.kt、三个核心列表、StoryWorkspace.kt | 核心页面16dp/卡片12dp；保留控件所需10dp圆角和字重数值 | 少量原页面局部非4倍间距仍需页面审查，未机械替换业务布局 |
| 4 圆角 | Theme.kt、SwipeRevealListRow.kt、Chrome与对话框调用 | 普通卡片10，大面板12，Dialog16，Sheet20 | 待滑动前景/动作底层边缘与图片裁切 |
| 5 按钮 | common/MoJingControls.kt及导入调用 | 48dp最小高度、20dp水平padding；主浅色按下/禁用色 | 危险按钮原显式颜色保留；逐状态视觉待检查 |
| 6 输入 | MoJingControls.kt两个重载、chat/InputBar.kt | 44dp最小视觉体/48dp外层；多行112dp；聊天44～128dp/5行；边框1/1.5dp | 有48dp图标时按内在高度增长；焦点、选择、IME、前后缀、error、只读必须运行复核 |
| 7 卡片 | SwipeRevealListRow.kt、StoryWorkspace.kt、列表与现有面板 | 白表面、轻边界、无新增常驻阴影；大面板12dp | 所有嵌套Card/Surface状态及浮层阴影待视觉复核 |
| 8 图标 | common/MoJingChrome.kt、57个Outlined图标调用文件 | 已检查本地现有依赖含所需Outlined图标；普通20/顶栏22；显式辅助尺寸保留 | 待确认线性图标整体观感；无需新依赖 |
| 9 顶栏 | MoJingChrome.kt及TopAppBar调用 | 52dp标准高度、18sp标题/22dp图标；已有菜单与功能保留 | 长标题、大字号、密集操作区域待渲染 |
| 10 一级导航 | MainBottomNavigation.kt、ImeHideAwareNavigationBar.kt | 三项不变，56dp最小内容高/11sp文字/22dp图标；保留navigationBarsPadding/IME收起 | 实际系统安全区与大字号增长待渲染 |
| 11 Tab | common/MoJingSectionTabs.kt、聊天资料/条目编辑/世界/设置/工坊 | 44dp视觉体+48dp触控；28×2dp短下划线，14sp；可横向滚动 | 待窄屏、长标签、字体放大/选中反馈 |
| 12 Chip | common/MoJingFilterChip.kt及现有筛选调用 | 原生32dp可见Chip及48dp触控语义；13sp与标准选中色 | 原生内在尺寸及大字号必须渲染确认 |
| 13 列表 | SwipeRevealListRow.kt、SessionListScreen、CharacterListScreen、EncyclopediaScreen、Workbench、EncyclopediaDetail | 72dp最小行、轻边框圆角；标题16/一行、摘要14/两行；故事metadata一行 | 世界/角色/工坊/条目含更多真实信息，超长状态待检查；不删功能字段 |
| 14 图片 | 角色ListRow与GridCard、世界ListRow、条目现有AsyncImage | 角色48、世界72；真实头像空时复用现有cardImagePath；Crop及已有圆角 | 故事无封面字段；未新增数据库；有图/失效URI/无图差异待渲染。部分既有网格图比例不等同世界Banner |
| 15 Sheet | Theme.kt、NewSessionPickers、SessionListScreen、MessageBubble | 共用顶部20dp；19个现有Sheet显式0.42遮罩，避免原生默认覆盖token；原滚动/IME/创建中阻止关闭保留 | 原固定640dp上限为更保守上限；90%高度、拖拽条和各面板安全区待逐个测量 |
| 16 点击区域 | IconButton/共享按钮/字段/FilterChip/Tab | 按钮最小48，Tab与Chip原生触控支持；不以图标20代替按钮触控 | 头像开图、裸clickable及字段边缘点击需运行命中验证 |
| 17 分割线 | Theme.outlineVariant、列表容器 | 主浅色#E5E7E9；列表外边框替代行间分割；底栏轻线 | 少量业务内分隔保留，待确认无重复边界 |
| 18 状态 | MoJingStatus、StoryGenerationProgressCard、Theme.error | 小说已保存/生成/等待/停止/错误直接接标准状态色；实际ViewModel状态不改 | 其他生成记录/沉积确认状态仍需逐状态核对；原等待/警告色与文字对比度有冲突，仅作强调，文字保持可读颜色 |
| 19 动效 | MoJingControls、NavAnimations、ImeHideAwareNavigationBar | 点击110ms；页面180～220ms；克制位移/ease-out，无新增装饰 | 原生Sheet/Toast系统时长仍由依赖控制；240～280/200ms未强行改平台行为 |
| 20 阅读 | ChatScreen、ChatDensityMetrics、MessageSearchHighlight | 17/29、20dp边距、最大680dp居中；原空行14dp排版（按当前Density转换sp），不改变正文或搜索offset；隐藏输入保留 | 段距、大字19/32和小字15/26与现有全局缩放不等价，未新增字号选项；章节22/30需独立状态核验 |
| 21 大屏 | EncyclopediaDetail、现有GridCells.Adaptive、ChatScreen | 世界>840dp双栏、左300dp；聊天正文限宽；手机不写死390×844 | 未新增聊天目录双栏；600～840双栏为可选建议，此轮保留现有功能 |
| 22 无障碍 | Theme、Controls、原语义/标签/列表回调 | 基础文字#212224/#5A5E62；语义和系统×应用字体缩放保留；高度允许增长 | 仅静态对比度计算；TalkBack、实际100～150%溢出/命中须渲染验证 |
| 23 Tokens | MoJingDesignTokens、Type、Theme及本表 | 原文完整保存；普通10dp与大面板12dp区分，未覆盖用户主题选择 | 不以token存在证明每页完成；上述待验证和缺口保留 |

## 页面与继承入口

- 故事库/新建/重命名/删除：列表容器、文字、输入/按钮、Sheet语义主题直接改或继承。
- 创作/小说表单/进度：共享字体、字段、按钮、图标/顶栏、资源面板；未改生成行为。
- 角色列表/网格/编辑/头像裁切：列表卡片、真实图片回退、统一图标；既有图片选择、裁切、URI处理不改。
- 世界列表/网格/条目/版本/时间线/关系/沉积/设置/工坊/模板：滑动容器、Tab、字体、图标、表单/按钮继承；世界条目宽屏布局直接校正。关系图和媒体裁切保持原业务绘制。
- 聊天/阅读/消息操作/文字选择/资料五页/平台模型和语音选择：共享图标/输入/Tab/主题；阅读宽度/行距直接改，消息原文与搜索offset不改。
- 设置分类/平台模型/默认项/个性化/资料/外观/用量三级/更新：共享表单/按钮/字体/图标/顶栏/对话框；保留已有选项。

所有条目的“源码已改/继承”都不代表像素验收。原标准未实现项已明确列在表内，没有把业务适配作为风格走样的解释。

## 故事封面与上传资源（当前决定）

原型图片表示用户内容位置。角色/世界沿现有选图与持久化路径，故事封面从已绑定世界/角色资源投影；无图或资源失效显示中性占位。没有新增Session封面字段、Room迁移、自动填图或下载外部图片。最新反馈修正默认不再使用测试山水资源，创作入口改为功能图标；真实用户图片保留。最新截图见 `outputs/android-ui-polish-20261002/index.html`。

## 静态检查边界

本批检查原始SHA、防并发覆盖、导入/已安装API与Outlined图标存在、词法括号、视觉回调未变及diff空白。初版静态检查没有执行Gradle。后续1.2.2已完成编译与有限运行检查；这仍不替代每项像素、状态与无障碍验收。旧APK不冒充本轮。

## 1.2.2 聚焦检查补充

颜色、圆角列表、短下划线Tab、三项导航、控件输入与标准字号核心页面已在最新Debug观察。危险浅底文字保留原#B84D4D强调色，正文改#7D222B以达到4.5:1；聊天头像恢复40dp。320dp/150%字号键盘新建页收起辅助标题，表单与动作已复查。正式Release独立升级与导航结果见项目状态；上述表内尚未走到的状态不改为“通过”。
