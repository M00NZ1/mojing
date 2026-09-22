# 墨境语音集成方案

## 微软与第三方引擎

微软在线语音采用 Azure Speech REST，通过用户配置的区域和 Key 直接请求，不增加自建服务端。音色列表从对应区域的 `voices/list` 获取并缓存；晓晓、云希等中文音色以接口实际返回为准。首版使用现有 OkHttp 基础设施，新增 Azure 请求与 SSML 编码适配，处理文本转义、语速及音色能力。

Azure Embedded Speech 属于限量访问能力，独立于公开云端接口，不作为默认离线资源。微软云端音色不打包为离线模型。

MultiTTS 作为外部系统引擎兼容；目前未找到可核验的官方源码与开源许可，不把应用或其社区音色包重新分发。系统语音页列出已安装引擎，用户选择后通过 Android TextToSpeech 调用。

`edge-tts` 是访问 Edge 在线语音的 Python 项目，采用混合许可证，不直接嵌入 Android APK。它与 Azure 官方产品接口分开；当前方案优先 Azure 正式接口。

参考：

- [Azure TTS REST 与音色列表](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/rest-text-to-speech)
- [Azure 语言与音色](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support)
- [Azure Embedded Speech](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/embedded-speech)
- [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [edge-tts](https://github.com/rany2/edge-tts) / [许可证](https://github.com/rany2/edge-tts/blob/master/LICENSE)

## 离线方案选型

首选 `sherpa-onnx` Android JNI 运行时，配合 `MeloTTS` 中文及中英混读模型包。sherpa-onnx 提供 Android TTS Engine 示例；MeloTTS 采用 MIT 许可，首包面向单音色日常朗读。模型按需安装，基础 APK 保留运行时与下载入口。

多角色音色扩展考察 `Kokoro-82M-v1.1-zh`，作为独立增强包；上线前测量目标手机上的首段等待与持续朗读速度。Matcha Baker 作为纯中文备选，模型与声码器的许可证随选定版本单独核对。Piper 暂不作为默认集成：原项目已归档，后续维护实现与语音权重需要分别核对许可。

参考：

- [sherpa-onnx Android 示例](https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxTtsEngine)
- [sherpa-onnx 模型说明](https://k2-fsa.github.io/sherpa/onnx/tts/apk.html)
- [MeloTTS](https://github.com/myshell-ai/MeloTTS) / [中文模型卡](https://huggingface.co/myshell-ai/MeloTTS-Chinese)
- [Kokoro 中文模型卡](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh)
- [Matcha Baker](https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese/matcha-icefall-zh-baker.html)
- [Piper 原仓库](https://github.com/rhasspy/piper) / [后续实现](https://github.com/OHF-Voice/piper1-gpl)

## 产品入口

设置中的朗读页采用「内置离线 / 微软在线 / 系统引擎 / 自定义服务」分类。每个音色展示语言、音色名、来源、离线状态与试听；未安装的离线音色显示下载大小与安装操作。正文朗读显示准备、播放、暂停和停止状态，长文按段落衔接。

角色可选择独立音色，旁白拥有单独音色。未设置时继承全局音色；切换角色或返回页面不修改全局偏好。平台密钥只保存在用户设备，应用不内置公共 Key。

## 实施顺序

1. 系统朗读兼容：优先已安装的离线中文语音，分类提示合成、网络和语音包错误，记录引擎诊断信息。
2. 语音设置与试听：列出设备实际引擎和音色，提供明确选择、试听、停止与系统设置入口。
3. 微软在线：独立配置区域与 Key，获取音色列表，按语言、性别和名称筛选，保存角色音色。
4. 内置离线：集成移动端推理运行时，中文音色包按需下载或本地导入，完成解压校验后安装。
5. 长篇播放：统一分段、队列、进度、取消、音频缓存与角色切换；配合媒体通知管理播放。

## 技术衔接

复用当前 `ApiKeyResolver` 的角色、会话与全局配置入口；新增类型化语音配置时保留旧字段的兼容读取，将线路和音色作为整体解析。`system` 保持系统引擎语义，已有 HTTP 朗读配置保持可用。

系统引擎作为独立适配器；微软和离线引擎输出音频，通过单一播放控制器管理。合成与播放状态分开，避免把请求提交成功视作播放完成。播放会话使用稳定请求编号，停止后丢弃旧回调与待播片段。

离线模型与音色清单包含版本、语言、推理配置、文件大小、许可证与下载来源。下载采用临时文件和完整性校验，校验通过后替换已安装版本。卸载只删除所选语音包及对应派生缓存。

网络语音失败后由用户选择重试或切换离线，不静默更换付费服务。朗读正文仅传给选中的在线供应商；系统引擎是否联网在音色详情展示。

## 验收

覆盖短句、长章节、中英混合、标点和表情，连续多角色切换、快速停止再播放、下载中断、缺失语音包与引擎服务失效。设备覆盖当前反馈的 vivo V2458A / Android 16，并记录首段等待、持续播放间隙、内存与安装空间。

Web 复用音色与角色绑定语义；系统引擎采用浏览器提供的能力，Android 离线模型包不作为 Web 可用音色自动导出。
