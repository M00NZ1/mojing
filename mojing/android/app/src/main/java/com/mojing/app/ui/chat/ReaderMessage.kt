package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Selected version only; preparation stays off the UI thread and the source is untouched. */
@Composable
internal fun ReaderMessage(message: MessageEntity, attachments: List<MessageAttachmentEntity>,
    onAction: (MessageAction) -> Unit = {}, canRetryMedia: Boolean = false, isGenerating: Boolean = false,
    canPlayMedia: Boolean = false,
    preparedParagraphs: List<String>? = null,
    onImageClick: (String) -> Unit) {
    var attempt by remember(message.id) { mutableIntStateOf(0) }
    val paragraphs by produceState<List<String>?>(preparedParagraphs, message.content, message.speakerType, message.structuredContentJson, preparedParagraphs, attempt) {
        if (preparedParagraphs != null && attempt == 0) { value = preparedParagraphs; return@produceState }
        value = null
        try {
            value = withContext(Dispatchers.Default) { prepareReaderParagraphs(message) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = emptyList() }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CharacterAttachmentChips(attachments, onImageClick)
        VoiceAttachmentControls(message, attachments, canRetryMedia, isGenerating, onAction, canPlay = canPlayMedia)
        when {
            paragraphs == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            paragraphs!!.isEmpty() && message.content.isNotBlank() -> TextButton(onClick = { attempt++ }) { Text("正文准备失败，重试") }
            else -> SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    paragraphs.orEmpty().forEachIndexed { index, paragraph ->
                        val chapterTitle = isReaderChapterTitle(message, index, paragraph)
                        val quote = paragraph.trimStart().startsWith(">") ||
                            (message.speakerType == "character" && paragraph.trimStart().startsWith("“"))
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (quote) Spacer(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                        Text(if (quote) paragraph.lines().joinToString("\n") { it.trimStart().removePrefix(">").trimStart() } else paragraph,
                            style = when {
                                chapterTitle -> MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 22.sp,
                                    lineHeight = 30.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = LocalChatReadingStyle.current.fontFamily,
                                )
                                message.speakerType == "narrator" -> LocalChatDensityMetrics.current.bodyTextStyle().copy(
                                    fontStyle = LocalChatReadingStyle.current.narratorFontStyle,
                                )
                                else -> LocalChatDensityMetrics.current.bodyTextStyle()
                            },
                            color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

/** Metadata identifies a named novel heading only when it is already the first source paragraph. */
internal fun isReaderChapterTitle(message: MessageEntity, index: Int, paragraph: String): Boolean {
    if (index != 0) return false
    val namedChapter = message.speakerType == "narrator" &&
        com.mojing.app.domain.story.NovelChapter.number(message.structuredContentJson) != null &&
        com.mojing.app.domain.story.NovelChapter.title(message.structuredContentJson)
            .trim().takeIf(String::isNotEmpty) == paragraph.trim()
    return namedChapter || (paragraph.length < 100 && paragraph.startsWith("第") &&
        (paragraph.contains("章") || paragraph.contains("节")))
}

/** Called off the main thread for the current bounded reading window or a row retry. */
internal fun prepareReaderParagraphs(message: MessageEntity): List<String> =
    (if (message.speakerType == "narrator" &&
        com.mojing.app.domain.story.NovelChapter.number(message.structuredContentJson) != null) {
        ChatMessageTextFormat.forBubbleDisplay(
            com.mojing.app.domain.engine.ConversationMessageText.forUserVisibleText(message),
        )
    } else ChatMessageTextFormat.visibleBody(message.content, message.speakerType))
        .split(Regex("\\n\\s*\\n")).filter(String::isNotBlank)
