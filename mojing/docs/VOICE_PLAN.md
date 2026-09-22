# 墨境语音

## 当前方案

Android 支持微软 Azure 语音、系统默认引擎和已安装的第三方 TTS 引擎。MultiTTS 安装后可从引擎列表选择。引擎有音色列表时展示供选择，无音色列表时使用引擎默认。微软默认音色为晓晓，其他音色通过区域接口获取。

对话输入框下方提供「语音」入口。角色资料可单独指定引擎和音色，或跟随对话设置。优先级为角色指定、对话选择、全局默认。旧语音模型、接口地址和前缀字段从常用页面隐藏，已有配置数据保留；朗读使用新的引擎选择规则。

设置的朗读页提供全局引擎选择及独立微软连接配置。Azure 区域和 Speech Key 一次保存，密钥沿用本机加密存储，不复用对话模型 Key。手机直接访问微软语音接口，无需自建服务端。

## 实现

`VoiceChoice` 保存引擎 ID 与音色 ID。全局和会话选择保存到本机偏好；角色选择沿用角色表的 `voiceProvider` 和 `voiceModel` 字段，`inherit` 表示继承。旧字段内容不删除，不进行数据库结构变更。

`VoiceEngineCatalog` 使用独立查询实例读取 Android 引擎音色，结束后释放。播放实例由 `AndroidTts` 管理，指定引擎或音色变化时重新配置，旧回调按请求编号隔离。默认音色尊重所选引擎，不强制替换为其他引擎。

微软语音经 `AzureSpeech` 使用 REST 获取音色、转义 SSML 并按段合成。播放复用 `TtsPlayer`，等待当前段结束再播放下一段，支持取消。角色自动朗读与手动朗读使用相同选择入口。

## 后续扩展

- 音色试听、语速与情绪设置。
- 播放进度、暂停继续、媒体通知与锁屏播放。
- 离线模型包可独立扩展，候选为 sherpa-onnx 与 MeloTTS；不影响系统引擎和微软语音的现有选择。

## 参考

- [Azure TTS REST 与音色列表](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/rest-text-to-speech)
- [Azure 语言与音色](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support)
- [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [sherpa-onnx Android 示例](https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxTtsEngine)
- [MeloTTS](https://github.com/myshell-ai/MeloTTS)
