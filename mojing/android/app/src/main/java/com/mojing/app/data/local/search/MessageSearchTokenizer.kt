package com.mojing.app.data.local.search

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.domain.engine.ConversationMessageText
import java.text.Normalizer
import java.util.Locale

/**
 * 将任意 Unicode 子串转换为 FTS4 可安全匹配的字母数字 token。
 *
 * unicode61 会把连续中文正文视为一个长词，无法满足会话内任意子串搜索；这里显式写入
 * 单码点与双码点 token，再以规范化原文做最终复核。索引内容完全可由 messages 重建。
 */
object MessageSearchTokenizer {
    const val INDEX_VERSION = 2
    const val REBUILD_BATCH_SIZE = 256
    private const val MAX_QUERY_BIGRAMS = 24

    fun normalize(content: String): String =
        Normalizer.normalize(content, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    fun index(entity: MessageEntity): MessageEntity {
        val normalized = normalize(ConversationMessageText.forUserVisibleText(entity))
        return entity.copy(
            searchNormalized = normalized,
            searchTerms = indexTerms(entity.sessionId, normalized),
        )
    }

    fun matchExpression(sessionId: Long, query: String): String {
        require(sessionId > 0L)
        val codePoints = normalize(query).codePoints().toArray()
        require(codePoints.isNotEmpty())
        val contentTokens = if (codePoints.size == 1) {
            listOf(unigram(codePoints[0]))
        } else {
            val bigrams = (0 until codePoints.lastIndex).map { index ->
                bigram(codePoints[index], codePoints[index + 1])
            }
            sampleEvenly(bigrams, MAX_QUERY_BIGRAMS)
        }
        return (listOf(sessionToken(sessionId)) + contentTokens)
            .distinct()
            // FTS4 的基础查询语法用相邻词表示隐式 AND；部分 Android SQLite
            // 未启用增强布尔语法，显式 AND 会被当成普通检索词。
            .joinToString(" ") { token -> "\"$token\"" }
    }

    private fun indexTerms(sessionId: Long, normalized: String): String {
        require(sessionId > 0L)
        val codePoints = normalized.codePoints().toArray()
        val terms = LinkedHashSet<String>(minOf(codePoints.size * 2 + 1, 4096))
        terms += sessionToken(sessionId)
        codePoints.forEach { codePoint -> terms += unigram(codePoint) }
        for (index in 0 until codePoints.lastIndex) {
            terms += bigram(codePoints[index], codePoints[index + 1])
        }
        return terms.joinToString(" ")
    }

    private fun sessionToken(sessionId: Long): String = "s${sessionId.toString(16)}"

    private fun unigram(codePoint: Int): String = "u${hex(codePoint)}"

    private fun bigram(first: Int, second: Int): String = "b${hex(first)}${hex(second)}"

    private fun hex(codePoint: Int): String = codePoint.toString(16).padStart(6, '0')

    private fun <T> sampleEvenly(values: List<T>, limit: Int): List<T> {
        if (values.size <= limit) return values
        return (0 until limit)
            .map { index -> index * (values.lastIndex) / (limit - 1) }
            .distinct()
            .map(values::get)
    }
}
