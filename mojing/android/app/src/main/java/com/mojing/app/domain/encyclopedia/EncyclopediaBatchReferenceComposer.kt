package com.mojing.app.domain.encyclopedia

import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import com.mojing.app.data.local.entity.EncyclopediaEntryEntity
import com.mojing.app.domain.engine.TokenCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 百科「批量新建条目」及人设/模板队列在调用 LLM 前拼接参考块：省 token、尽量对齐已有设定。
 * 与后端 [encyclopedia_context_pack.build_entry_reference_digest] 策略对齐。
 */
object EncyclopediaBatchReferenceComposer {

    private val contextSplitRegex = Regex("[\\s,，。;；、]+")

    fun contextKeywords(userContext: String, maxKeys: Int = 14): Set<String> =
        userContext.trim()
            .split(contextSplitRegex)
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 }
            .distinct()
            .take(maxKeys)
            .toSet()

    private fun scoreEntry(e: EncyclopediaEntryEntity, keys: Set<String>, targetEntryType: String): Double {
        var s = 0.0
        if (keys.isNotEmpty()) {
            val hay = "${e.title} ${e.summary} ${e.tags}".lowercase()
            s += keys.count { hay.contains(it) } * 1.2
        }
        if (e.isFeatured) s += 3.0
        if (e.entryType == targetEntryType) s += 1.0
        return s
    }

    /**
     * @param digestTokenBudget 非 null 时按 [TokenCounter] 估算累计 token 截断（与对话侧预算思路一致）；为 null 时仅用 [digestBudgetChars]。
     */
    suspend fun buildReferenceBlock(
        entryDao: EncyclopediaEntryDao,
        encyclopediaId: Long,
        targetEntryType: String,
        userContext: String,
        digestBudgetChars: Int = 3200,
        digestTokenBudget: Int? = 900,
        maxCharsSafety: Int = 4800,
        titleLimit: Int = 72,
        poolLimit: Int = 220,
    ): String = withContext(Dispatchers.IO) {
        val keys = contextKeywords(userContext)
        val pool = LinkedHashMap<Long, EncyclopediaEntryEntity>()
        entryDao.listRecentForDigest(encyclopediaId, poolLimit).forEach { pool[it.id] = it }
        if (keys.isNotEmpty()) {
            for (kw in keys.take(4)) {
                val hits = entryDao.search(encyclopediaId, kw).take(24)
                for (h in hits) pool[h.id] = h
            }
        }
        val entries = pool.values.toList()
        val typeTitles = entryDao.listTitlesByTypeRecent(encyclopediaId, targetEntryType, titleLimit)
        val sorted = entries.sortedWith(
            compareByDescending<EncyclopediaEntryEntity> { scoreEntry(it, keys, targetEntryType) }
                .thenByDescending { it.isFeatured }
                .thenByDescending { it.updatedAt },
        )
        var usedChars = 0
        var usedTok = 0
        val refLines = mutableListOf<String>()
        for (e in sorted) {
            val summ = e.summary.trim()
            val body = e.content.trim()
            val snippet = (if (summ.isNotEmpty()) summ else body)
                .take(if (summ.isNotEmpty()) 240 else 180)
                .replace("\n", " ")
                .trim()
            val title = e.title.trim()
            if (snippet.isEmpty() && title.isEmpty()) continue
            val line = "[${e.entryType}] ${title.take(120)} — $snippet"
            if (usedChars + line.length + 1 > maxCharsSafety) break
            if (digestTokenBudget != null) {
                val lineTok = TokenCounter.estimate("$line\n")
                if (refLines.isNotEmpty() && usedTok + lineTok > digestTokenBudget) break
                usedTok += lineTok
            } else {
                if (usedChars + line.length + 1 > digestBudgetChars) break
            }
            usedChars += line.length + 1
            refLines.add(line)
        }
        buildString {
            if (typeTitles.isNotEmpty()) {
                appendLine("【勿与下列同类型条目标题重复】")
                typeTitles.forEach { appendLine("- $it") }
                appendLine()
            }
            if (refLines.isNotEmpty()) {
                appendLine("【现有条目参考（节选；对齐语气与世界观，勿复制标题）】")
                refLines.forEach { appendLine(it) }
            }
        }.toString().trim()
    }
}
