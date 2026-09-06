# 内置角色 / 封面图（随仓库分发）

`starter_catalog.json` 维护“雾港来信”的一个百科、一个世界和两名角色。Web 直接读取此文件；Android 的 `res/raw/seed_data.json` 是相同内容的分发副本，由 `tests/test_starter_catalog.py` 校验一致性。旧目录代码仅供迁移识别，不再参与正常启动填充。

## 版权与来源策略

- **本目录下的 SVG 均为项目原创的抽象 / 装饰性图形**，在 `manifest.json` 中标注为 **CC0-1.0**，可自由用于产品内置展示。
- **请勿**将搜索引擎或社交平台上的「动漫截图、同人图、无授权立绘」直接打包进应用：存在版权风险，也可能触发应用商店政策。
- 若需扩充真实人物立绘，请仅使用 **明确允许再分发** 的素材，例如：
  - 自行绘制或委托并保留授权合同；
  - **CC0 / 明确商业可用的素材包**（如 Kenney、部分 OpenClipart 等），并在 `manifest.json` 中填写 `license_name`、`author`、`source_label`。
- 将新文件放入 `assets/builtin/avatars/` 或 `assets/builtin/world_covers/` 后，在 **`mojing/data/assets/manifest.json`** 增加对应条目；后端启动会把本包同步到本地 `storage/assets/builtin/`。
- 拉取 **Unsplash** 免费插画到 `avatars/raster/`：`python scripts/fetch_unsplash_builtin_portraits.py`（可在脚本里追加 URL）。

## 目录结构

`assets/builtin/` 下的相对路径须与 `manifest.json` 里的 `storage_path` 一致。
