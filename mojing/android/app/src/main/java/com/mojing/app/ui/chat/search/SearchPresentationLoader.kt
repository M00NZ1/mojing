package com.mojing.app.ui.chat.search

import com.mojing.app.data.SecureStorage
import com.mojing.app.data.local.dao.*
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.prefs.UiPreferencesRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

data class SearchCharacter(val name: String, val avatar: String, val color: String, val card: String)
data class SearchSpeakerLabels(
    val characterNames: Map<Long, String> = emptyMap(),
    val userName: String = "我",
    val narratorName: String = "旁白",
) {
    fun forMessage(message: MessageEntity): String = when (message.speakerType) {
        "user" -> userName.ifBlank { "我" }
        "narrator" -> narratorName.ifBlank { "旁白" }
        else -> message.characterId?.let(characterNames::get)?.takeIf(String::isNotBlank) ?: "角色"
    }
}

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
    /** Result cards need names, not full character prompts, credentials, attachments or world text. */
    suspend fun loadSpeakerLabels(sessionId: Long, messages: List<MessageEntity>): SearchSpeakerLabels {
        val characterIds = messages.filter { it.speakerType != "user" && it.speakerType != "narrator" }
            .mapNotNull { it.characterId }.distinct()
        val names = if (characterIds.isEmpty()) emptyMap()
            else characters.getNamesByIds(characterIds).associate { it.id to it.name }
        return SearchSpeakerLabels(names, storage.userName,
            worlds.getNarratorNameBySession(sessionId) ?: "旁白")
    }

    suspend fun load(sessionId: Long, messages: List<MessageEntity>): SearchPresentation {
        val cast = messages.mapNotNull { it.characterId }.distinct().mapNotNull { id ->
            characters.getById(id)?.let { id to SearchCharacter(it.name, it.avatarImagePath, it.avatarColor, it.cardImagePath) }
        }.toMap()
        return SearchPresentation(cast, attachments.getByMessages(messages.map { it.id }).groupBy { it.messageId },
            storage.userName, storage.userAvatarImagePath, storage.userAvatarColor,
            worlds.getBySession(sessionId)?.narratorName ?: "旁白", preferences.chatDensity.first())
    }
}
