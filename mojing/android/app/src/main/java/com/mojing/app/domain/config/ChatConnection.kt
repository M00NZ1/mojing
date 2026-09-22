package com.mojing.app.domain.config

/** A complete connection from one scope. Never log credentials. */
class ChatConnection(val apiKey: String, val baseUrl: String, val error: String? = null, val source: String = "公共") {
    fun model(characterModel: String, publicModel: String, thinking: Boolean = false,
              characterThinkingModel: String = "", publicThinkingModel: String = ""): String {
        val main = if (source == "角色") characterModel.trim() else publicModel.trim()
        if (!thinking) return main
        val thinkingModel = if (source == "角色") characterThinkingModel.trim() else publicThinkingModel.trim()
        return thinkingModel.ifBlank { main }
    }
}

object ChatConnectionResolver {
    fun resolve(
        worldKey: String = "", worldBase: String = "",
        characterKey: String = "", characterBase: String = "",
        publicKey: String, publicBase: String,
    ): ChatConnection {
        fun configured(key: String, base: String): Boolean = key.isNotBlank() ||
            (base.isNotBlank() && !ApiKeyResolver.isPlaceholderApiBaseField(base))
        return when {
            configured(worldKey, worldBase) -> complete("会话", worldKey, worldBase)
            configured(characterKey, characterBase) -> complete("角色", characterKey, characterBase)
            else -> ChatConnection(publicKey.trim(), publicBase.trim())
        }
    }

    fun complete(source: String, key: String, base: String): ChatConnection {
        val missing = when {
            key.isBlank() && base.isBlank() -> "API Key 和接口地址"
            key.isBlank() -> "API Key"
            base.isBlank() -> "接口地址"
            else -> null
        }
        return ChatConnection(key.trim(), base.trim(), missing?.let {
            "${source}对话配置缺少$it，请补齐配置，或在输入框下方直接选择已配置的平台和模型。"
        }, source)
    }
}
