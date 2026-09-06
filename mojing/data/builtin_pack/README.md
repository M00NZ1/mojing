# 内置资料

## 雾港来信

[starter_catalog.json](starter_catalog.json)定义初始目录：

- 百科：雾港来信，包含七个基础条目与两个角色条目。
- 世界：雾港来信。
- 角色：沈照、林汐。

Web 从该目录安装资料，Android 使用 [seed_data.json](../../android/app/src/main/res/raw/seed_data.json)分发副本。[test_starter_catalog.py](../../tests/test_starter_catalog.py)检查两端内容一致性。

## 初始化

目录只安装一次，删除示例后重启保持删除状态。旧示例通过内容与引用识别，原样且未使用的示例归档，已修改或被引用的资料保留。Web 创作中心提供旧示例恢复入口。

Web 创作中心的“从雾港开始”将世界、百科与两名角色带入新会话。

## 资源

```text
starter_catalog.json
assets/builtin/
├─ avatars/        SVG 头像与 raster 图片
└─ world_covers/   世界封面
```

资源登记在 [manifest.json](../assets/manifest.json)，记录存储路径、来源、作者与许可。启动时由资源服务同步到本地 `storage/assets/builtin/`。

资源包中保留的旧头像与封面独立于当前初始目录。新增资源时，将文件相对路径与清单的 `storage_path` 对齐，并填写来源字段。
