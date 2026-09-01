package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.remote.ChatMessage
import com.google.gson.Gson
import javax.inject.Inject

data class CharacterSnapshot(
    val mood: String = "",
    val attitudeToUser: String = "",
    val currentGoal: String = "",
    val recentKeyActions: List<String> = emptyList(),
    val knownFacts: List<String> = emptyList(),
    val relationshipChanges: List<String> = emptyList()
)

class CharacterSnapshotExtractor @Inject constructor(
    private val llmRetry: LlmRetry,
) {
    private val gson = Gson()

    suspend fun extract(
        messages: List<MessageEntity>,
        character: CharacterEntity,
        apiKey: String,
        baseUrl: String,
        model: String,
        triggerInterval: Int = 15
    ): CharacterSnapshot? {
        val userMessages = messages.filter { it.speakerType == "user" }
        if (userMessages.size % triggerInterval != 0 || messages.isEmpty()) return null
        
        val recentText = messages.takeLast(triggerInterval * 2).joinToString("\n") { 
            "${if (it.speakerType == "user") "玩家" else character.name}: ${ConversationMessageText.forDerivedContext(it).take(300)}"
        }
        
        val prompt = """
            分析以下对话，提取角色「${character.name}」的当前状态。
            对话内容：
            $recentText
            
            返回JSON格式：
            {
              "mood": "当前情绪（如：警惕、愉悦、愤怒、忧虑）",
              "attitudeToUser": "对用户的态度变化",
              "currentGoal": "角色当前的目标或意图",
              "recentKeyActions": ["关键行为1", "关键行为2"],
              "knownFacts": ["已知事实1", "已知事实2"],
              "relationshipChanges": ["关系变化1"]
            }
        """.trimIndent()

        return try {
            val result = llmRetry.chatCompletionWithRetry(
                apiKey, baseUrl, model,
                listOf(
                    ChatMessage("system", "你是角色心理分析专家，必须严格返回JSON格式，不包含Markdown标签。"),
                    ChatMessage("user", prompt),
                ),
                temperature = 0.3f,
                maxTokens = 2000,
            )
            val json = extractJson(result)
            gson.fromJson(json, CharacterSnapshot::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun extractJson(text: String): String {
        val start = text.indexOf("{")
        val end = text.lastIndexOf("}")
        return if (start != -1 && end != -1 && end >= start) {
            text.substring(start, end + 1)
        } else {
            text
        }
    }
}
