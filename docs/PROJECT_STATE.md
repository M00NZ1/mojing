# MoJing 当前项目状态

更新日期：2026-09-02

本文是适合公开仓库的短状态说明，不记录个人设备、绝对路径、会话数据、密钥、一次性施工过程或逐提交日志。

## 当前产品

- 产品名称：墨境 MoJing；
- 正式源码根：`mojing/`；
- Web：React/Vite；
- 本机 API：FastAPI/SQLAlchemy/SQLite/文件存储；
- Android：Kotlin/Jetpack Compose/Room；
- Android 标识：`com.mojing.app`；
- 运行边界：本地优先、单用户、无账号体系、无自动云同步。

## 当前能力

- 角色、人格、百科、WorldInfo 和世界模板；
- 单角色/多角色会话与流式回复；
- 停止、重试、继续生成、非破坏性编辑和剧情分支；
- 会话草稿、历史分页、全文搜索、收藏定位；
- 上下文预算、摘要、长期记忆与来源；
- 图片、语音、附件和本地导入导出；
- Web 与原生 Android 两条客户端路线。

## 数据边界

- Web 数据位于本机 Backend 的 SQLite/文件存储；
- Android 数据位于应用私有 Room/文件存储；
- 两端不共享运行时数据库，跨端通过版本化导入/导出交换；
- `.env`、API Key、数据库、媒体、日志、备份和本机配置不进入 Git；
- 原始消息、角色、设定、分支和手动记忆不可用清缓存方式处理。

## 当前证据边界

仓库包含 Backend、Web 和 Android 自动测试与构建入口，但公开状态文档不把旧运行记录当作当前验证。每次变更应在任务报告或 CI 中分别记录：

- 自动测试；
- Web/Android 构建；
- 浏览器真实运行；
- 模拟器运行；
- 真机运行；
- 大型数据/性能测试；
- 用户报告；
- 尚未验证项。

## 兼容变化

本次命名收口把正式源码目录、内部项目标识和 Android namespace/application ID 统一到 MoJing。早期预改名 Android 构建不会被原位覆盖；旧数据应通过显式导出/导入迁移，旧应用和数据在用户确认前不得自动删除。

## 维护入口

- [仓库说明](../README.md)
- [产品与架构](../mojing/PROJECT.md)
- [Android 基线](../mojing/docs/ANDROID.md)
- [构建、验证与交付](../mojing/docs/MAINTENANCE.md)
- [公开路线图](PRODUCT_GAP_MAP.md)
