package com.mojing.app.ui.chat.search

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.*
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

data class SearchCharacter(val name: String, val avatar: String, val color: String, val card: String)
data class SearchPresentation(
    val characters: Map<Long, SearchCharacter> = emptyMap(),
    val attachments: Map<Long, List<MessageAttachmentEntity>> = emptyMap(),
    val userName: String = "我", val userAvatar: String = "", val userColor: String = "#53C7A8",
    val narratorName: String = "旁白", val density: String = "comfortable",
)
class SearchPresentationLoader @Inject constructor(
    private val characters: CharacterDao, private val attachments: AttachmentDao,
    private val worlds: SessionWorldDao, private val storage: SecureStorage,
    private val preferences: UiPreferencesRepository,
) {
    suspend fun load(sessionId: Long, messages: List<MessageEntity>): SearchPresentation {
        val cast = messages.mapNotNull { it.characterId }.distinct().mapNotNull { id ->
            characters.getById(id)?.let { id to SearchCharacter(it.name, it.avatarImagePath, it.avatarColor, it.cardImagePath) }
        }.toMap()
        return SearchPresentation(cast, attachments.getByMessages(messages.map { it.id }).groupBy { it.messageId },
            storage.userName, storage.userAvatarImagePath, storage.userAvatarColor,
            worlds.getBySession(sessionId)?.narratorName ?: "旁白", preferences.chatDensity.first())
    }
}
