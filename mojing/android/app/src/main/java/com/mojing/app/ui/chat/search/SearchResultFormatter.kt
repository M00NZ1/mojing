package com.mojing.app.ui.chat.search

import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.ui.chat.ChatMessageTextFormat
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** CPU-bound body parsing and Unicode matching must not occupy the reader's UI thread. */
class SearchResultFormatter internal constructor(private val dispatcher: CoroutineDispatcher) {
    @Inject constructor() : this(Dispatchers.Default)

    suspend fun format(messages: List<MessageEntity>, query: String): List<SearchHit> = withContext(dispatcher) {
        messages.map { message ->
            ensureActive()
            SearchHit(
                message.copy(content = "", structuredContentJson = "{}", searchNormalized = "", searchTerms = ""),
                ChatMessageTextFormat.searchPreview(message.content, message.speakerType, query),
            )
        }
    }
}
