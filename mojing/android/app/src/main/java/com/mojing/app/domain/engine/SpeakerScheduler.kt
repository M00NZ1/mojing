package com.mojing.app.domain.engine

import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

/**
 * 与后端 [speaker_scheduler.select_speakers] 对齐的离线选人逻辑（Room 数据）。
 * Web / 后端通过 `POST /sessions/{id}/speaker-plan` + `max_auto_speakers`；Android 直接读 [maxSpeakers]（来自 `SecureStorage.maxAutoSpeakers`）。
 */
object SpeakerScheduler {

    data class PickResult(
        val characterIds: List<Long>,
        /** 因「强制下一句」入选的参与者行 id，需在事务后 `forceNext = false` */
        val clearForceNextParticipantIds: List<Long>,
        /** 与后端 `select_speakers` 返回的说明文案对齐，供 UI 展示（对应 Web speaker-plan / 流式 reason） */
        val reason: String,
        /** 通过发言率抽签入选但本轮名额已满、暂未发言的角色 */
        val waitingCharacterIds: List<Long> = emptyList(),
    )

    fun pick(
        participants: List<SessionParticipantEntity>,
        charactersById: Map<Long, CharacterEntity>,
        contextMessages: List<MessageEntity>,
        userMessage: String,
        maxSpeakers: Int,
    ): PickResult {
        val ordered = participants.sortedWith(compareBy({ it.sortOrder }, { it.id }))
        if (ordered.isEmpty()) {
            return PickResult(
                characterIds = emptyList(),
                clearForceNextParticipantIds = emptyList(),
                reason = "当前会话没有可发言人物。",
            )
        }
        val active = ordered.filter { !it.muted }
        if (active.isEmpty()) {
            return PickResult(
                characterIds = emptyList(),
                clearForceNextParticipantIds = emptyList(),
                reason = "所有参与者均被禁言。",
            )
        }
        val max = maxSpeakers.coerceIn(1, 8)

        val forced = active.filter { it.forceNext }
        if (forced.isNotEmpty()) {
            val chosen = forced.take(max)
            return PickResult(
                characterIds = chosen.map { it.characterId },
                clearForceNextParticipantIds = chosen.map { it.id },
                reason = "检测到强制发言标记，优先发言。",
            )
        }

        val primaryStrategy = (active.firstOrNull()?.speakerStrategy ?: "natural").lowercase()
        val hasList = active.any { (it.speakerStrategy ?: "natural").equals("list", ignoreCase = true) }
        val hasPooled = active.any { (it.speakerStrategy ?: "natural").equals("pooled", ignoreCase = true) }

        return when {
            primaryStrategy == "list" || hasList -> PickResult(
                active.take(max).map { it.characterId },
                emptyList(),
                reason = "按参与者列表顺序选择。",
            )
            primaryStrategy == "pooled" || hasPooled -> pickPooled(active, contextMessages, max)
            else -> pickNatural(active, charactersById, contextMessages, userMessage, max)
        }
    }

    private fun pickPooled(
        active: List<SessionParticipantEntity>,
        contextMessages: List<MessageEntity>,
        max: Int,
    ): PickResult {
        val charMsgs = contextMessages.filter {
            it.speakerType == "character" && it.characterId != null && it.includeInContext
        }.takeLast(100)
        val counts = mutableMapOf<Long, Int>()
        for (m in charMsgs) {
            val cid = m.characterId ?: continue
            counts[cid] = counts.getOrDefault(cid, 0) + 1
        }
        val sorted = active.sortedWith(
            compareBy<SessionParticipantEntity>({ counts.getOrDefault(it.characterId, 0) }, { -it.sortOrder }),
        )
        return PickResult(
            characterIds = sorted.take(max).map { it.characterId },
            clearForceNextParticipantIds = emptyList(),
            reason = "按发言次数均衡分配，优先选择发言较少的角色。",
        )
    }

    private fun pickNatural(
        active: List<SessionParticipantEntity>,
        charactersById: Map<Long, CharacterEntity>,
        contextMessages: List<MessageEntity>,
        userMessage: String,
        max: Int,
    ): PickResult {
        val lowered = userMessage.lowercase()
        val now = System.currentTimeMillis()

        data class Scored(val score: Float, val p: SessionParticipantEntity, val rolledIn: Boolean)

        fun mentionBonus(name: String): Float {
            if (name.isEmpty() || lowered.isEmpty()) return 0f
            val nl = name.lowercase()
            if (lowered.contains(nl)) return 5f
            var bonus = 0f
            lowered.split(Regex("\\s+")).forEach { w ->
                if (w.length > 1 && nl.contains(w)) bonus = max(bonus, 3f)
            }
            return bonus
        }

        val scoredAll = active.map { p ->
            val name = charactersById[p.characterId]?.name?.trim().orEmpty()
            val mention = mentionBonus(name)
            val rolledIn = mention > 0f || Random.nextFloat() < p.talkativeness.coerceIn(0.05f, 1f)
            var score = p.talkativeness + mention
            val last = contextMessages.lastOrNull {
                it.speakerType == "character" &&
                    it.characterId == p.characterId &&
                    it.includeInContext
            }?.createdAt
            if (last != null) {
                val hours = (now - last) / 3600000.0
                when {
                    hours < 0.5 -> score -= 2f
                    hours < 2 -> score -= 1f
                }
            }
            Scored(score, p, rolledIn)
        }

        val pool = scoredAll.filter { it.rolledIn }.ifEmpty {
            listOf(scoredAll.maxByOrNull { it.score } ?: scoredAll.first())
        }
        val ranked = pool.sortedByDescending { it.score }
        val top = ranked.take(max)
        val speaking = top.map { it.p.characterId }
        val waiting = ranked.drop(max).map { it.p.characterId }

        val detail = top.joinToString(", ") { s ->
            val name = charactersById[s.p.characterId]?.name?.trim().orEmpty()
                .ifEmpty { "#${s.p.characterId}" }
            val rate = (s.p.talkativeness * 100).toInt()
            "$name(${String.format(Locale.US, "%.1f", s.score)}/率$rate%)"
        }
        val waitDetail = waiting.mapNotNull { id ->
            charactersById[id]?.name?.trim()?.takeIf { it.isNotEmpty() }
        }.joinToString("、")
        val reason = buildString {
            append("发言率抽签: $detail")
            if (waitDetail.isNotEmpty()) append("；候补: $waitDetail")
        }
        return PickResult(
            characterIds = speaking,
            clearForceNextParticipantIds = emptyList(),
            reason = reason,
            waitingCharacterIds = waiting,
        )
    }
}
