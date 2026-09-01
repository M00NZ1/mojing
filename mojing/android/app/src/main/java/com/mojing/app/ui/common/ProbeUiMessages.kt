package com.mojing.app.ui.common

import com.google.gson.JsonObject

object ProbeUiMessages {

    fun missingKey(channel: String): String = when (channel) {
        "text" -> "请先填写 API Key"
        "image" -> "请先填写配图 API Key（或对话 API Key）"
        else -> "请先填写 API Key"
    }

    fun missingUrl(channel: String): String = when (channel) {
        "text" -> "请先填写 URL"
        "image" -> "请先填写配图 URL"
        else -> "请先填写 URL"
    }

    fun formatProbeDone(
        channel: String,
        ok: Boolean,
        err: String,
        done: JsonObject,
        rawBaseField: String,
    ): String {
        if (!ok) {
            val e = err.ifBlank { "测试未通过" }
            return when {
                e.contains("auth", ignoreCase = true) ||
                    e.contains("401", ignoreCase = true) ||
                    e.contains("密钥", ignoreCase = true) -> "API Key 无效或未填写"
                else -> e
            }
        }
        val baseLabel = when (channel) {
            "text" -> "对话已连通"
            "image" -> "配图已连通"
            else -> "已连通"
        }
        val win = done.get("base_url")?.asString?.trim().orEmpty()
        val multi = rawBaseField.contains('\n') || rawBaseField.contains(';')
        return if (multi && win.isNotBlank()) {
            val short = win.take(48) + if (win.length > 48) "…" else ""
            "$baseLabel（$short）"
        } else {
            baseLabel
        }
    }
}
