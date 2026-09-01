package com.mojing.app.domain.engine

import com.mojing.app.data.local.dao.EncyclopediaEntryDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RagSearcher @Inject constructor(
    private val entryDao: EncyclopediaEntryDao
) {
    data class SearchResult(
        val title: String,
        val content: String,
        val entryType: String,
        val score: Double
    )

    suspend fun search(
        encyclopediaId: Long,
        query: String,
        maxResults: Int = 5
    ): List<SearchResult> {
        val entries = entryDao.search(encyclopediaId, query)
        if (entries.isEmpty()) return emptyList()

        val queryTokens = query.split(Regex("[\\s，。！？、；：\"'（）\\[\\]【】,.!?;:()]+"))
            .filter { it.length >= 2 }.toSet()

        return entries.map { entry ->
            val titleTokens = entry.title.split(Regex("[\\s，。！？、；：]+"))
                .filter { it.length >= 2 }.toSet()
            val contentTokens = entry.content.take(500)
                .split(Regex("[\\s，。！？、；：]+"))
                .filter { it.length >= 2 }.toSet()

            val titleMatch = titleTokens.count { it in queryTokens }.toDouble() / maxOf(titleTokens.size, 1)
            val contentMatch = contentTokens.count { it in queryTokens }.toDouble() / maxOf(contentTokens.size, 1)
            val score = titleMatch * 3.0 + contentMatch

            SearchResult(
                title = entry.title,
                content = entry.content,
                entryType = entry.entryType,
                score = score
            )
        }.filter { it.score > 0.2 }
            .sortedByDescending { it.score }
            .take(maxResults)
    }
}
