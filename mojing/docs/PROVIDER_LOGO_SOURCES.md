# 内置平台标志

这些资源仅用于在连接配置中识别服务商，本地打包，不在运行时联网取图。保留品牌原色；深浅主题都在中性浅色底上显示。自定义地址使用服务器图标，品牌按服务地址识别，不按用户填写的名称猜测。

2026-10-02 从官网引用的图标取得：

| 平台 | 原始资源 | 本地资源 |
|---|---|---|
| DeepSeek | https://www.deepseek.com/favicon.ico | `provider_deepseek.png` |
| OpenAI | https://openai.com/apple-icon.png?apple-icon.3o0tf8iy4mx7y.png | `provider_openai.png` |
| Anthropic | https://cdn.prod.website-files.com/67ce28cfec624e2b733f8a52/67d31dd7aa394792257596c5_webclip.png | `provider_anthropic.png` |
| 硅基流动 | https://siliconflow.cn/favicon.ico | `provider_siliconflow.png` |

资源位于 `android/app/src/main/res/drawable-nodpi/`；仅转换封装格式为 PNG，未重绘标志。官网入口：[DeepSeek](https://www.deepseek.com)、[OpenAI 品牌规范](https://openai.com/brand/)、[Anthropic](https://www.anthropic.com)、[硅基流动](https://siliconflow.cn)。标志属于各自权利人。
