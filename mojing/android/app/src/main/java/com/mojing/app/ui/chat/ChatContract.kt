package com.mojing.app.ui.chat

import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.SessionBranchEntity
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.MessageBookmarkEntity
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity

data class SavedImageNotice(val messageId: Long, val branchId: String)

data class BranchAnchor(val branchId: String, val label: String)

data class MemoryCorrectionPromptTrace(
    val branchId: String,
    val responderLabel: String,
    val responderType: String,
    val corrections: List<SessionMemoryCorrectionEntity>,
)

sealed class MessageAction {
    data class Speak(val message: MessageEntity) : MessageAction()
    data class Quote(val message: MessageEntity) : MessageAction()
    data class Copy(val message: MessageEntity) : MessageAction()
    data class SaveImages(val message: MessageEntity) : MessageAction()
    data class Edit(val message: MessageEntity) : MessageAction()
    data class ContinueReply(val message: MessageEntity) : MessageAction()
    data class Regenerate(val message: MessageEntity) : MessageAction()
    data class Recall(val message: MessageEntity) : MessageAction()
    data class SelectChoice(
        val choice: String,
        val sourceMessageId: Long,
    ) : MessageAction()
    data class ToggleBookmark(val message: MessageEntity) : MessageAction()
    data class SetContextExcluded(val message: MessageEntity, val excluded: Boolean) : MessageAction()
    data class CreateBranch(val message: MessageEntity) : MessageAction()
    data class SwitchToBranch(val branchId: String) : MessageAction()
}

object ChatContract {
    sealed class Intent {
        data class SendMessage(val content: String) : Intent()
        data class SelectChoice(val choice: String) : Intent()
        data object StopGeneration : Intent()
        data object RequestNarrator : Intent()
        data class SwitchBranch(val branchId: String) : Intent()
        data class DeleteMessage(val messageId: Long) : Intent()
        data class EditMessage(val messageId: Long, val newContent: String) : Intent()
        data class RegenerateMessage(val messageId: Long) : Intent()
        data object ToggleDrawer : Intent()
        data class PlayVoice(val text: String) : Intent()
        data class CopyMessage(val content: String) : Intent()
        data class AddParticipant(val characterId: Long) : Intent()
        data class ToggleMute(val participantId: Long) : Intent()
        data class RemoveParticipant(val participantId: Long) : Intent()
    }

    data class State(
        val sessionId: Long = 0,
        val sessionTitle: String = "",
        val messages: List<MessageEntity> = emptyList(),
        val displayLines: List<ChatDisplayLine> = emptyList(),
        val hasOlderMessages: Boolean = false,
        val hasNewerMessages: Boolean = false,
        val isLoadingHistory: Boolean = false,
        val focusedMessageId: Long? = null,
        val messageAttachments: Map<Long, List<MessageAttachmentEntity>> = emptyMap(),
        val streamingText: String = "",
        val isGenerating: Boolean = false,
        val lastRequestModel: String? = null,
        val lastRequestPlatform: String? = null,
        val modelSelectionSaving: Boolean = false,
        val modelSelectionError: String? = null,
        val voiceSelectionSaving: Boolean = false,
        val voiceSelectionError: String? = null,
        val novelMetadataSaving: Boolean = false,
        val novelMetadataError: String? = null,
        val memoryCompactionChunk: Int? = null,
        /** 会话创建时配置的展示用上下文上限（tokens），仅用于聊天页统计条 */
        val displayContextTokenLimit: Int = 1_000_000,
        /** 本对话纳入上下文的消息正文 token 估算之和（仅展示） */
        val conversationTokenEstimate: Int = 0,
        val currentBranchId: String = "main",
        val branches: List<SessionBranchEntity> = emptyList(),
        val participants: List<SessionParticipantEntity> = emptyList(),
        val currentChoices: List<String> = emptyList(),
        val isDrawerOpen: Boolean = false,
        val error: String? = null,
        val inputText: String = "",
        val narratorGuidance: String = "",
        val imagePrompt: String = "",
        val savedImageNotice: SavedImageNotice? = null,
        val pendingLocalImagePaths: List<String> = emptyList(),
        val isReady: Boolean = false,
        /** 首次读取本机会话失败；与普通聊天操作错误分离，以便页面持续提供重试。 */
        val initialLoadError: String? = null,
        val world: SessionWorldEntity? = null,
        val encyclopediaFoundation: String = "",
        val contextMemoryText: String = "",
        val contextMemoryStatus: ContextMemoryStatus = ContextMemoryStatus.IDLE,
        val memoryOperationRunning: Boolean = false,
        val memorySegments: List<SessionMemorySegmentEntity> = emptyList(),
        val memoryCorrections: List<SessionMemoryCorrectionEntity> = emptyList(),
        val lastMemoryCorrectionPromptTrace: MemoryCorrectionPromptTrace? = null,
        val eventNodes: List<SessionEventNodeEntity> = emptyList(),
        val eventBusyIds: Set<Long> = emptySet(),
        val eventActionErrors: Map<Long, String> = emptyMap(),
        val characterNames: Map<Long, String> = emptyMap(),
        val characterAvatars: Map<Long, String> = emptyMap(),
        /** 竖版封面本地路径；对话气泡只显示头像，点此路径用于「点头像看封面」 */
        val characterCardImages: Map<Long, String> = emptyMap(),
        val characterColors: Map<Long, String> = emptyMap(),
        val bookmarks: List<MessageBookmarkEntity> = emptyList(),
        val bookmarkLocatingId: Long? = null,
        val bookmarkBusyIds: Set<Long> = emptySet(),
        val bookmarkedMessageIds: Set<Long> = emptySet(),
        val excludedContextKeys: Set<String> = emptySet(),
        val bookmarkPreviews: Map<Long, String> = emptyMap(),
        val searchResults: List<MessageEntity> = emptyList(),
        val searchPreviews: Map<Long, String> = emptyMap(),
        val isSearchingMessages: Boolean = false,
        val completedSearchQuery: String = "",
        val searchHasOlder: Boolean = false,
        val searchBeforeId: Long = Long.MAX_VALUE,
        val searchExactMatch: Boolean = false,
        val searchError: String? = null,
        /** 当前用户在加密偏好中的展示信息（与设置「个人资料」一致） */
        val userDisplayName: String = "",
        val userAvatarImagePath: String = "",
        val userAvatarColor: String = "#53C7A8",
        /** 与 Web 设置「允许对话页思考/Max」一致（本机 SecureStorage） */
        val allowSessionThinkMax: Boolean = false,
        /** 当前会话是否启用思考/Max（Room） */
        val sessionThinkMaxEnabled: Boolean = false,
        /** 当前对话首位角色是否在资料中固定开启思考/Max（开启后无需再开本会话开关） */
        val characterForcesThinkMax: Boolean = false,
        /** 与 Web `data-chat-density` 对齐：`comfortable` | `compact` | `reader` */
        val chatDensity: String = "comfortable",
        /** 与 Web `speaker-plan` / 流式 `speaker_plan` 说明一致：本轮本地选人结果一行摘要 */
        val speakerPlanSummary: String? = null,
        /** 本轮尚未开始回复的角色名（多角色连播间隙可继续发用户消息） */
        val pendingRoundSpeakers: List<String> = emptyList(),
        /** 手动模式下，下一句指定回复的角色 id；null 表示只发用户消息 */
        val manualReplyCharacterId: Long? = null,
        /** 路由中的会话已被删除或从未存在；页面应提示后返回会话主页。 */
        val sessionNotFound: Boolean = false,
        val quotingMessage: MessageEntity? = null,
        /** 每轮选项（模型生成或世界模板） */
        val roundChoiceOptions: List<String> = emptyList(),
        /** [roundChoiceOptions] 所属的当前尾部回复；用于拒绝历史消息中的同名旧选项。 */
        val roundChoiceMessageId: Long? = null,
        /** 从某条消息分出的剧情线，用于消息上切换分支 */
        val branchAnchorsByMessageId: Map<Long, List<BranchAnchor>> = emptyMap(),
        /** 分支源消息可能不在当前窗口；预览按主键单独读取。 */
        val branchSourcePreviews: Map<Long, String> = emptyMap(),
    )

    sealed class Effect {
        data class ShowToast(val message: String) : Effect()
        data object ScrollToBottom : Effect()
        data class CopiedToClipboard(val content: String) : Effect()
    }
}
