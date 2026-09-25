package com.mojing.app.data.local.dao

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Small, versionless metadata used to make interrupted reply persistence idempotent. */
internal object ReplyRecoveryMetadata {
    const val TOKEN_FIELD = "reply_recovery_token"

    fun withToken(json: String, token: String): String {
        require(token.isNotBlank()) { "恢复 token 不能为空" }
        return objectOrEmpty(json).apply { addProperty(TOKEN_FIELD, token) }.toString()
    }

    fun token(json: String): String? = runCatching {
        objectOrEmpty(json).get(TOKEN_FIELD)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun objectOrEmpty(json: String): JsonObject =
        runCatching { JsonParser.parseString(json.ifBlank { "{}" }).asJsonObject }
            .getOrElse { JsonObject() }
}
