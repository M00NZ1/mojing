package com.mojing.app.ui.chat

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.media.AndroidTts
import com.mojing.app.media.NativeSpeechRecognizer
import com.mojing.app.data.local.entity.MessageEntity
import com.mojing.app.data.local.entity.MessageAttachmentEntity
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity
import com.mojing.app.domain.engine.StructuredParser
import com.mojing.app.ui.chat.components.ChatContextUsageStrip
import com.mojing.app.ui.common.hideImeKeyboard
import com.mojing.app.ui.common.isImeKeyboardOpen
import com.mojing.app.ui.chat.drawer.AddParticipantDialog
import com.mojing.app.ui.chat.drawer.ChatDrawer
import com.mojing.app.ui.session.SessionRenameSheet
import com.mojing.app.util.ChatAttachmentFiles
import com.mojing.app.util.ContentDocumentWriter
import com.mojing.app.util.UsbSessionLog
import com.mojing.app.ui.util.UserFacingStrings
import kotlin.text.Charsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: Long,
    onBack: () -> Unit,
    backLabel: String = "返回会话主页",
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var voiceChoice by remember(sessionId) { mutableStateOf(viewModel.currentVoiceChoice()) }
    var showVoicePicker by remember { mutableStateOf(false) }
    if (showVoicePicker) com.mojing.app.ui.common.VoiceChoicePicker(
        choice = voiceChoice, allowInherit = true,
        inheritLabel = "跟随全局语音设置",
        description = "用于当前对话；已单独配置语音的角色优先使用角色设置",
        saving = state.voiceSelectionSaving,
        saveError = state.voiceSelectionError,
        onPreviewStart = viewModel::stopSpeaking,
        onSelected = { choice -> viewModel.selectVoiceChoice(choice) { voiceChoice = choice; showVoicePicker = false } },
        onDismiss = { showVoicePicker = false },
    )
    val billingViewModel: com.mojing.app.ui.settings.usage.BillingDisplayViewModel = hiltViewModel()
    val billingState by billingViewModel.state.collectAsStateWithLifecycle()
    val modelLabel by viewModel.modelSelectionLabel.collectAsStateWithLifecycle()
    var showModelPicker by remember { mutableStateOf(false) }
    if (showModelPicker) {
        ChatModelPicker(viewModel.availableModelPlatforms(), onDismiss = { showModelPicker = false },
            selectedModel = viewModel.currentChatModelSelection(),
            onFollowSettings = { viewModel.followConfiguredChatModels { showModelPicker = false } },
            selectedLabel = modelLabel, lastRequestModel = state.lastRequestModel,
            lastRequestPlatform = state.lastRequestPlatform, isGenerating = state.isGenerating,
            isSaving = state.modelSelectionSaving, saveError = state.modelSelectionError) { platform, model ->
            viewModel.selectChatModel(platform, model) { showModelPicker = false }
        }
    }
    val isImeOpen = isImeKeyboardOpen()
    val visibleDisplayLines = remember(state.displayLines, state.messageAttachments) {
        state.displayLines.filter { line ->
            shouldShowCharacterBubbleLine(
                line.selectedMessage(),
                state.messageAttachments[line.selectedMessage().id].orEmpty(),
            )
        }
    }
    val stableSessionTitle = remember(state.sessionTitle) {
        state.sessionTitle.lineSequence().firstOrNull().orEmpty().trim()
    }
    val listState = rememberLazyListState()
    var stickToBottom by remember { mutableStateOf(true) }
    var hasAutoPositionedInitially by remember(sessionId) { mutableStateOf(false) }
    var speakerTurnMode by remember { mutableStateOf(viewModel.currentSpeakerTurnMode()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val backgroundFailures by RetainedChatSessions.stores.failures.collectAsStateWithLifecycle()
    val backgroundFailure = backgroundFailures[sessionId]
    LaunchedEffect(backgroundFailure?.token) {
        val notice = backgroundFailure ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice.message, actionLabel = "知道了", withDismissAction = true,
            duration = androidx.compose.material3.SnackbarDuration.Indefinite)
        RetainedChatSessions.stores.dismissFailure(sessionId, notice.token)
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAddParticipant by remember { mutableStateOf(false) }
    var isAddingParticipant by remember(sessionId) { mutableStateOf(false) }
    var allCharacters by remember { mutableStateOf<List<com.mojing.app.data.local.entity.CharacterEntity>>(emptyList()) }
    var isLoadingParticipants by remember(sessionId) { mutableStateOf(false) }
    var participantLoadError by remember(sessionId) { mutableStateOf<String?>(null) }
    var participantLoadRevision by remember(sessionId) { mutableStateOf(0) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var editingMessage by remember { mutableStateOf<com.mojing.app.data.local.entity.MessageEntity?>(null) }
    var editContent by remember { mutableStateOf("") }
    var editSaving by remember { mutableStateOf(false) }
    var editFailure by remember { mutableStateOf<String?>(null) }
    var editCommitted by remember { mutableStateOf(false) }
    var recallMessage by remember(sessionId, state.currentBranchId) { mutableStateOf<com.mojing.app.data.local.entity.MessageEntity?>(null) }
    var showImageGenDialog by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var readingMode by rememberSaveable(sessionId) { mutableStateOf(false) }
    var latestRequested by remember(sessionId) { mutableStateOf(false) }
    var latestLoadAttempted by remember(sessionId) { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showRenameSession by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var isImportingChat by remember(sessionId) { mutableStateOf(false) }
    var isAddingAttachment by remember(sessionId) { mutableStateOf(false) }
    var isExportingChat by rememberSaveable(sessionId) { mutableStateOf(false) }
    var savingGalleryMessageId by remember(sessionId) { mutableStateOf<Long?>(null) }
    var pendingGalleryPermissionMessageId by remember(sessionId) { mutableStateOf<Long?>(null) }
    var showBranchOverview by remember { mutableStateOf(false) }
    var branchMenuExpanded by remember { mutableStateOf(false) }
    var topActionsMenuExpanded by remember { mutableStateOf(false) }
    var speechListening by remember { mutableStateOf(false) }
    var worldCredentialFieldsDirty by remember { mutableStateOf(false) }
    var showUnsavedWorldDialog by remember { mutableStateOf(false) }
    var correctionDialogOpen by remember { mutableStateOf(false) }
    var correctionEditing by remember { mutableStateOf<SessionMemoryCorrectionEntity?>(null) }
    var correctionDraft by remember { mutableStateOf("") }
    var correctionSourceMessageId by remember { mutableStateOf<Long?>(null) }
    var correctionScopeBranchId by remember { mutableStateOf<String?>(null) }
    var correctionPendingDelete by remember { mutableStateOf<SessionMemoryCorrectionEntity?>(null) }
    var inputFieldValue by remember(sessionId) {
        mutableStateOf(
            TextFieldValue(
                text = state.inputText,
                selection = TextRange(state.inputText.length),
            ),
        )
    }
    var pendingInputText by remember(sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId, state.inputText) {
        when {
            inputFieldValue.text == state.inputText -> pendingInputText = null
            pendingInputText == inputFieldValue.text -> Unit
            else -> {
                inputFieldValue = TextFieldValue(
                    text = state.inputText,
                    selection = TextRange(state.inputText.length),
                )
                pendingInputText = null
            }
        }
    }

    LaunchedEffect(state.sessionNotFound) {
        if (state.sessionNotFound) {
            Toast.makeText(context, "对话不存在或已删除", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }

    LaunchedEffect(state.currentBranchId) {
        if (correctionDialogOpen && correctionScopeBranchId != null) {
            correctionEditing = null
            correctionScopeBranchId = state.currentBranchId
        }
    }

    fun showGenerationLockedMessage() {
        scope.launch { snackbarHostState.showSnackbar("当前正在生成，请等待完成或先停止生成") }
    }

    fun dismissKeyboard() {
        hideImeKeyboard(keyboardController, focusManager)
    }

    suspend fun saveMessageImagesToGallery(messageId: Long) {
        if (savingGalleryMessageId != null) {
            snackbarHostState.showSnackbar("图片正在保存，请稍候")
            return
        }
        savingGalleryMessageId = messageId
        try {
            val result = viewModel.saveMessageImagesToGallery(messageId)
            val message = when {
                result.requestedCount == 0 -> "这条消息没有可保存的图片"
                result.failedCount == 0 -> "已保存到系统相册${if (result.savedCount > 1) "（${result.savedCount} 张）" else ""}"
                result.savedCount == 0 -> "图片保存失败，请检查存储空间或权限"
                else -> "已保存 ${result.savedCount} 张，${result.failedCount} 张失败"
            }
            snackbarHostState.showSnackbar(message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            snackbarHostState.showSnackbar(e.message ?: "图片保存失败")
        } finally {
            savingGalleryMessageId = null
        }
    }

    fun requestLeave() {
        when {
            worldCredentialFieldsDirty -> showUnsavedWorldDialog = true
            else -> onBack()
        }
    }

    BackHandler {
        when {
            isImeOpen -> dismissKeyboard()
            showUnsavedWorldDialog -> showUnsavedWorldDialog = false
            showImageGenDialog -> showImageGenDialog = false
            showSearchDialog -> {
                showSearchDialog = false
            }
            showEmojiPicker -> showEmojiPicker = false
            showBranchOverview -> showBranchOverview = false
            drawerState.isOpen -> scope.launch { drawerState.close() }
            else -> requestLeave()
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        speechListening = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) viewModel.appendVoiceText(spoken)
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val started = tryLaunchSpeechRecognition(context, speechLauncher) {
                scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.speechRecognitionUnavailable()) }
            }
            if (started) speechListening = true
        } else {
            scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.micPermissionRequired()) }
        }
    }
    val imagePickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (isAddingAttachment) return@rememberLauncherForActivityResult
        isAddingAttachment = true
        scope.launch {
            var copiedPath: String? = null
            try {
                withContext(Dispatchers.IO) {
                    copiedPath = ChatAttachmentFiles.copyUriToSessionFile(
                        context,
                        uri,
                        sessionId,
                        viewModel.maxAttachmentBytes(),
                    )
                }
                currentCoroutineContext().ensureActive()
                copiedPath?.let(viewModel::queueLocalImageAttachment)
                copiedPath = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.message ?: UserFacingStrings.imageSaveFailed())
            } finally {
                copiedPath?.let { abandonedPath ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        ChatAttachmentFiles.deleteOwnedPendingFile(context, sessionId, abandonedPath)
                    }
                }
                isAddingAttachment = false
            }
        }
    }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val messageId = pendingGalleryPermissionMessageId
        pendingGalleryPermissionMessageId = null
        if (granted && messageId != null) {
            scope.launch { saveMessageImagesToGallery(messageId) }
        } else if (messageId != null) {
            scope.launch { snackbarHostState.showSnackbar("需要存储权限才能保存到系统相册") }
        }
    }
    val tavernImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (isImportingChat) return@rememberLauncherForActivityResult
        isImportingChat = true
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw IllegalStateException("无法读取文件")
                }
                val result = viewModel.importTavernChatText(text)
                snackbarHostState.showSnackbar(
                    if (result.duplicate) "该聊天记录已导入当前故事线，未重复写入"
                    else "已导入聊天记录 ${result.importedCount} 条",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(e.message ?: "导入失败")
            } finally {
                isImportingChat = false
            }
        }
    }
    val exportNovelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            isExportingChat = true
            try {
                ContentDocumentWriter.writeStream(context, uri) { viewModel.exportNovel(it) }
                snackbarHostState.showSnackbar("小说已导出")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { snackbarHostState.showSnackbar(UserFacingStrings.documentWriteFailed(e.message)) }
            finally { isExportingChat = false }
        }
    }
    val exportChatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            isExportingChat = false
            return@rememberLauncherForActivityResult
        }
        if (!isExportingChat || isImportingChat) {
            isExportingChat = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                ContentDocumentWriter.writeStream(context, uri) { os ->
                    viewModel.exportMainBranchJson(os)
                }
                snackbarHostState.showSnackbar("聊天记录已导出")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(UserFacingStrings.documentWriteFailed(e.message))
            } finally {
                isExportingChat = false
            }
        }
    }

    LaunchedEffect(
        showAddParticipant,
        state.world?.encyclopediaId,
        state.participants,
        participantLoadRevision,
    ) {
        if (showAddParticipant) {
            isLoadingParticipants = true
            participantLoadError = null
            try {
                val raw = viewModel.getAllCharacters()
                val enc = state.world?.encyclopediaId?.takeIf { it > 0L }
                val existingIds = state.participants.map { it.characterId }.toSet()
                allCharacters = raw
                    .asSequence()
                    .filter { it.id !in existingIds }
                    .filter { enc == null || it.boundEncyclopediaId <= 0L || it.boundEncyclopediaId == enc }
                    .toList()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                allCharacters = emptyList()
                participantLoadError = "角色列表加载失败，请重试"
            } finally {
                isLoadingParticipants = false
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val tail = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount == 0 || (last == info.totalItemsCount - 1 &&
                tail != null && tail.offset + tail.size <= info.viewportEndOffset)
        }.collect { stickToBottom = it }
    }

    LaunchedEffect(visibleDisplayLines.size, state.streamingText.length, stickToBottom) {
        if (!stickToBottom || state.streamingText.isNotEmpty()) return@LaunchedEffect
        val total = listState.layoutInfo.totalItemsCount
        if (visibleDisplayLines.isNotEmpty() && total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    LaunchedEffect(sessionId, visibleDisplayLines.size) {
        if (hasAutoPositionedInitially) return@LaunchedEffect
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            hasAutoPositionedInitially = true
            listState.scrollToItem(total - 1)
        }
    }

    LaunchedEffect(isImeOpen, visibleDisplayLines.size, stickToBottom) {
        if (!isImeOpen || !stickToBottom || state.streamingText.isNotEmpty()) return@LaunchedEffect
        delay(80)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    LaunchedEffect(state.isReady, state.focusedMessageId, visibleDisplayLines) {
        if (!state.isReady) return@LaunchedEffect
        val messageId = state.focusedMessageId ?: return@LaunchedEffect
        val index = visibleDisplayLines.indexOfFirst { line ->
            line.variants.any { it.id == messageId }
        }
        if (index >= 0) {
            val listIndex = index + if (state.hasOlderMessages) 1 else 0
            listState.scrollToItem(listIndex)
            viewModel.clearFocusedMessage()
        }
    }

    LaunchedEffect(latestRequested, state.hasNewerMessages, state.isLoadingHistory, state.isGenerating, visibleDisplayLines) {
        if (!latestRequested || state.isLoadingHistory) return@LaunchedEffect
        if (state.hasNewerMessages) {
            if (!state.isGenerating) {
                if (latestLoadAttempted) {
                    latestRequested = false
                } else {
                    latestLoadAttempted = true
                    if (!viewModel.returnToLatestMessages()) latestRequested = false
                }
            }
        } else {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
            latestRequested = false
        }
    }

    com.mojing.app.ui.chat.contents.StoryContentsSheet(
        novelTitle = stableSessionTitle,
        busy = state.isGenerating || isExportingChat,
        saving = state.novelMetadataSaving,
        saveError = state.novelMetadataError,
        onEditStart = viewModel::clearNovelMetadataError,
        onRenameNovel = viewModel::renameNovel,
        onNextChapter = { title, direction -> viewModel.requestNarrator(guidance = direction, nextChapter = true, chapterTitle = title) },
        onRenameChapter = viewModel::renameChapter,
        onExport = { exportNovelLauncher.launch("novel_${sessionId}.txt") },
        visible = showContents,
        sessionId = sessionId,
        branchId = state.currentBranchId,
        onOpenMessage = { messageId -> viewModel.openMessageInHistory(messageId) },
        onDismiss = { showContents = false },
    )

    if (showRenameSession) {
        val isNovel = state.world?.gameplayMode == "小说创作"
        SessionRenameSheet(
            initialTitle = state.sessionTitle,
            saving = state.novelMetadataSaving,
            error = state.novelMetadataError,
            onEdit = viewModel::clearNovelMetadataError,
            onSave = { title ->
                if (isNovel) viewModel.renameNovel(title) { showRenameSession = false }
                else viewModel.renameSessionTitle(title) { showRenameSession = false }
            },
            onDismiss = { showRenameSession = false; viewModel.clearNovelMetadataError() },
            heading = if (isNovel) "修改小说标题" else "重命名对话",
            fieldLabel = if (isNovel) "小说标题" else "对话名称",
        )
    }




    DisposableEffect(viewModel) { onDispose { viewModel.stopSpeaking() } }

    LaunchedEffect(state.error) {
        val e = state.error ?: return@LaunchedEffect
        UsbSessionLog.e("StateError", e)
        snackbarHostState.showSnackbar(e)
        viewModel.clearError()
    }

    LifecycleResumeEffect(sessionId) {
        voiceChoice = viewModel.currentVoiceChoice()
        viewModel.refreshModelSelection()
        viewModel.refreshParticipantCharacterMeta()
        onPauseOrDispose { }
    }

    LaunchedEffect(drawerState) {
        var prevOpen = drawerState.isOpen
        snapshotFlow { drawerState.isOpen }.collect { open ->
            if (prevOpen && !open && worldCredentialFieldsDirty) {
                showUnsavedWorldDialog = true
            }
            prevOpen = open
        }
    }

    if (!showSearchDialog) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Box(Modifier.fillMaxSize()) {
                    Column {
                        if (state.isLoadingHistory) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在加载历史消息…", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        ChatDrawer(
                            participants = state.participants,
                            world = state.world,
                            memorySegments = state.memorySegments,
                            contextMemoryText = state.contextMemoryText,
                            contextMemoryStatus = state.contextMemoryStatus,
                            encyclopediaFoundation = state.encyclopediaFoundation,
                            memoryOperationRunning = state.memoryOperationRunning,
                            memoryCorrections = state.memoryCorrections,
                            memoryCorrectionPromptTrace = state.lastMemoryCorrectionPromptTrace,
                            currentBranchId = state.currentBranchId,
                            isGenerating = state.isGenerating,
                            eventNodes = state.eventNodes,
                            characterNames = state.characterNames,
                            bookmarks = state.bookmarks,
                            bookmarkBusyIds = state.bookmarkBusyIds,
                            bookmarkLocatingId = state.bookmarkLocatingId,
                            bookmarkPreviews = state.bookmarkPreviews,
                            onJumpToBookmark = { mid ->
                                if (state.isGenerating) {
                                    showGenerationLockedMessage()
                                } else {
                                    viewModel.openBookmarkedMessage(mid) {
                                        scope.launch { drawerState.close() }
                                    }
                                }
                            },
                            onRemoveBookmark = { mid -> viewModel.removeBookmark(mid) },
                            onToggleMute = { viewModel.toggleMute(it) },
                            onUpdateTalkativeness = { id, value, onResult ->
                                viewModel.updateParticipantTalkativeness(id, value, onResult)
                            },
                            speakerTurnMode = speakerTurnMode,
                            onSpeakerTurnModeChange = { mode ->
                                if (viewModel.updateSpeakerTurnMode(mode)) {
                                    speakerTurnMode = mode
                                }
                            },
                            onRemoveParticipant = { viewModel.removeParticipant(it) },
                            onAddParticipant = {
                                isLoadingParticipants = true
                                participantLoadError = null
                                showAddParticipant = true
                            },
                            onClose = { scope.launch { drawerState.close() } },
                            onWorldSettingChanged = { key, value -> viewModel.updateWorldSetting(key, value) },
                            onSaveSessionWorldCredentials = { draft ->
                                viewModel.saveSessionWorldCredentials(draft) { saved ->
                                    if (saved) {
                                        worldCredentialFieldsDirty = false
                                        scope.launch { snackbarHostState.showSnackbar("已保存本场线路覆盖") }
                                    }
                                }
                            },
                            onWorldCredentialFieldsDirty = { worldCredentialFieldsDirty = it },
                            worldCredentialFieldsDirty = worldCredentialFieldsDirty,
                            onToggleEventResolved = { viewModel.toggleEventNodeResolved(it) },
                            eventBusyIds = state.eventBusyIds,
                            eventActionErrors = state.eventActionErrors,
                            onDeleteEventNode = { viewModel.deleteEventNode(it) },
                            onJumpToMemorySource = { messageId ->
                                if (state.isGenerating) {
                                    showGenerationLockedMessage()
                                } else if (viewModel.openMessageInHistory(messageId)) {
                                    scope.launch { drawerState.close() }
                                }
                            },
                            onAddMemoryCorrection = { content, sourceId ->
                                correctionEditing = null
                                correctionDraft = content
                                correctionSourceMessageId = sourceId
                                correctionScopeBranchId = state.currentBranchId
                                correctionDialogOpen = true
                            },
                            onEditMemoryCorrection = { correction ->
                                correctionEditing = correction
                                correctionDraft = correction.content
                                correctionSourceMessageId = correction.sourceMessageId
                                correctionScopeBranchId = correction.branchId
                                correctionDialogOpen = true
                            },
                            onDeleteMemoryCorrection = { correction ->
                                if (state.isGenerating) showGenerationLockedMessage()
                                else correctionPendingDelete = correction
                            },
                            onRebuildContextMemory = {
                                if (state.isGenerating) {
                                    showGenerationLockedMessage()
                                } else {
                                    viewModel.rebuildCurrentContextMemory { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                }
                            },
                            onClearContextMemory = {
                                if (state.isGenerating) {
                                    showGenerationLockedMessage()
                                } else {
                                    viewModel.clearCurrentContextMemory { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                }
                            },
                            allowSessionThinkMax = state.allowSessionThinkMax,
                            sessionThinkMaxEnabled = state.sessionThinkMaxEnabled,
                            characterForcesThinkMax = state.characterForcesThinkMax,
                            onSessionThinkMax = { enabled ->
                                viewModel.setSessionThinkMax(enabled) { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            }
                        )
                    }
                    SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Column(Modifier.fillMaxWidth()) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(stableSessionTitle.ifBlank { "对话" }, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text("已加载约 ${state.conversationTokenEstimate} Token",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                dismissKeyboard()
                                requestLeave()
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, backLabel)
                            }
                        },
                        actions = {
                            IconButton(onClick = { dismissKeyboard(); readingMode = !readingMode }) {
                                Icon(if (readingMode) Icons.Default.Edit else Icons.Default.MenuBook,
                                    if (readingMode) "退出阅读模式" else "阅读模式")
                            }
                            if (state.world?.gameplayMode == "小说创作") {
                                IconButton(onClick = { dismissKeyboard(); showContents = true }) {
                                    Icon(Icons.Default.FormatListBulleted, "小说目录")
                                }
                            }
                            Box {
                                BranchSelector(
                                    expanded = branchMenuExpanded,
                                    onDismiss = { branchMenuExpanded = false },
                                    branches = listOf("main" to "主线") +
                                        state.branches.map { it.branchId to (it.label.ifEmpty { it.branchId }) },
                                    currentBranch = state.currentBranchId,
                                    onSelect = { branchId ->
                                        branchMenuExpanded = false
                                        if (state.isGenerating) {
                                            showGenerationLockedMessage()
                                        } else if (branchId == "CREATE_NEW") {
                                            viewModel.createBranch(state.messages.lastOrNull()?.id ?: 0L)
                                        } else {
                                            viewModel.switchBranch(branchId)
                                        }
                                    },
                                    onShowBranchOverview = {
                                        if (state.isGenerating) showGenerationLockedMessage()
                                        else showBranchOverview = true
                                    },
                                )
                                IconButton(onClick = {
                                    dismissKeyboard()
                                    topActionsMenuExpanded = true
                                }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "会话菜单")
                                }
                                DropdownMenu(
                                    expanded = topActionsMenuExpanded,
                                    onDismissRequest = { topActionsMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        text = { Text(if (state.world?.gameplayMode == "小说创作") "修改小说标题" else "重命名对话") },
                                        onClick = {
                                            topActionsMenuExpanded = false
                                            dismissKeyboard()
                                            viewModel.clearNovelMetadataError()
                                            showRenameSession = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("故事线") },
                                        leadingIcon = { Icon(Icons.Default.AccountTree, null) },
                                        onClick = {
                                            topActionsMenuExpanded = false
                                            if (state.isGenerating) showGenerationLockedMessage()
                                            else branchMenuExpanded = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Default.Search, null) },
                                        text = { Text("搜索消息") },
                                        onClick = {
                                            topActionsMenuExpanded = false
                                            dismissKeyboard()
                                            showSearchDialog = true
                                        },
                                    )
                                    DropdownMenuItem(text = { Text("停止朗读") }, onClick = {
                                        topActionsMenuExpanded = false; viewModel.stopSpeaking()
                                    })
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                        text = {
                                            Text(
                                                when {
                                                    isImportingChat -> "正在导入聊天记录…"
                                                    state.participants.isEmpty() -> "导入聊天记录（需先添加角色）"
                                                    state.isGenerating -> "导入聊天记录（请等待生成结束）"
                                                    else -> "导入聊天记录…"
                                                },
                                            )
                                        },
                                        enabled = state.participants.isNotEmpty() &&
                                            !state.isGenerating &&
                                            !isImportingChat &&
                                            !isExportingChat,
                                        onClick = {
                                            topActionsMenuExpanded = false
                                            tavernImportLauncher.launch(
                                                arrayOf("application/json", "text/plain", "text/*", "*/*"),
                                            )
                                        },
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                                        text = { Text(if (isExportingChat) "正在导出主线聊天记录…" else "导出主线聊天记录…") },
                                        enabled = !isExportingChat && !isImportingChat,
                                        onClick = {
                                            topActionsMenuExpanded = false
                                            isExportingChat = true
                                            exportChatLauncher.launch("chat_${sessionId}_${System.currentTimeMillis()}.json")
                                        },
                                    )
                                }
                            }
                            IconButton(onClick = {
                                dismissKeyboard()
                                scope.launch {
                                    if (drawerState.isClosed) drawerState.open() else drawerState.close()
                                }
                            }) {
                                Icon(Icons.Default.Tune, "会话设置与资料")
                            }
                        }
                    )
                    (state.speakerPlanSummary ?: state.pendingRoundSpeakers.takeIf { it.isNotEmpty() }?.let {
                        "待回复：${it.joinToString("、")}"
                    })?.takeIf { state.isGenerating && !readingMode }?.let { line ->
                        Text(
                            text = line,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                        )
                    }

                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (state.isReady && !state.sessionNotFound && !readingMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        if (!isImeOpen && speakerTurnMode == "manual" && state.participants.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                state.participants.filter { !it.muted }.forEach { p ->
                                    val name = state.characterNames[p.characterId] ?: "角色"
                                    FilterChip(
                                        selected = state.manualReplyCharacterId == p.characterId,
                                        onClick = {
                                            val next = if (state.manualReplyCharacterId == p.characterId) null else p.characterId
                                            viewModel.setManualReplyCharacterId(next)
                                        },
                                        label = { Text(name) },
                                    )
                                }
                            }
                        }
                        if (shouldShowRoundChoices(isImeOpen, state.roundChoiceOptions, state.isGenerating)) {
                            val sourceMessageId = state.roundChoiceMessageId
                            RoundChoicesRow(
                                choices = state.roundChoiceOptions,
                                onSelect = { choice ->
                                    if (sourceMessageId != null) {
                                        viewModel.handleMessageAction(
                                            MessageAction.SelectChoice(choice, sourceMessageId),
                                        )
                                    }
                                },
                            )
                        }
                        state.savedImageNotice?.takeIf { it.branchId == state.currentBranchId }?.let {
                            SavedImageNoticeCard(
                                busy = state.isLoadingHistory || state.isGenerating,
                                onOpen = { viewModel.showSavedImage() },
                            )
                        }
                        state.quotingMessage?.let { q ->
                            QuoteDraftPreview(
                                text = ChatMessageTextFormat.quoteSnippet(q.content, 120, q.speakerType),
                                speakerLabel = when (q.speakerType) {
                                    "user" -> state.userDisplayName.ifBlank { "我" }
                                    "narrator" -> state.world?.narratorName?.ifBlank { "旁白" } ?: "旁白"
                                    else -> state.characterNames[q.characterId].orEmpty().ifBlank { "角色" }
                                },
                                onCancel = { viewModel.setQuotingMessage(null) },
                            )
                        }
                        InputBar(
                            modelSelector = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) {
                                        ChatInputModelSelector(modelLabel, state.isGenerating) {
                                            dismissKeyboard(); showModelPicker = true
                                        }
                                    }
                                    TextButton(onClick = { dismissKeyboard(); showVoicePicker = true }) { Text("语音") }
                                }
                            },
                            narratorGuidance = state.narratorGuidance,
                            onNarratorGuidanceChange = viewModel::updateNarratorGuidance,
                            value = inputFieldValue,
                            onValueChange = { value ->
                                inputFieldValue = value
                                pendingInputText = value.text
                                viewModel.updateInput(value.text)
                            },
                            onSend = { viewModel.sendMessage() },
                            onStop = { viewModel.stopGeneration() },
                            isGenerating = state.isGenerating,
                            isAddingAttachment = isAddingAttachment,
                            generationModeLabel = when {
                                state.isGenerating && state.memoryCompactionChunk != null -> "整理记忆 · 第 ${state.memoryCompactionChunk} 段"
                                state.isGenerating && state.streamingText.isNotBlank() && state.streamingText.length < 12 -> "角色回复"
                                state.isGenerating && state.streamingText.isBlank() -> "处理中"
                                else -> null
                            },
                            onRequestNarrator = { guidance ->
                                if (state.isGenerating) showGenerationLockedMessage()
                                else viewModel.submitNarratorGuidance(guidance)
                            },
                            onInsertMacro = { macro ->
                                val updated = insertTextAtSelection(
                                    text = inputFieldValue.text,
                                    selection = inputFieldValue.selection,
                                    insertion = macro,
                                )
                                inputFieldValue = updated
                                pendingInputText = updated.text
                                viewModel.updateInput(updated.text)
                            },
                            pendingAttachmentCount = state.pendingLocalImagePaths.size,
                            onClearPendingAttachments = { viewModel.clearPendingAttachments() },
                            isListening = speechListening,
                            isImeOpen = isImeOpen,
                            onVoiceClick = {
                                if (state.isGenerating) {
                                    showGenerationLockedMessage()
                                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    val started = tryLaunchSpeechRecognition(context, speechLauncher) {
                                        scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.speechRecognitionUnavailable()) }
                                    }
                                    if (started) speechListening = true
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onImageGenClick = {
                                if (state.isGenerating) showGenerationLockedMessage()
                                else showImageGenDialog = true
                            },
                            onAttachImageClick = {
                                if (state.isGenerating) showGenerationLockedMessage()
                                else if (isAddingAttachment) {
                                    scope.launch { snackbarHostState.showSnackbar("正在添加图片，请稍候") }
                                }
                                else imagePickLauncher.launch("image/*")
                            },
                            onOpenEmoji = {
                                dismissKeyboard()
                                showEmojiPicker = true
                            },
                            onPreviewSpeak = { viewModel.previewSpeakInput() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                }
            }
        ) { padding ->
            val densityMetrics = if (readingMode) ChatDensityMode.Reader.toMetrics().copy(
                rowHorizontal = 12.dp, narratorHorizontal = 12.dp, bubbleMaxWidth = 720.dp,
            ) else ChatDensityMode.fromStorage(state.chatDensity).toMetrics()
            CompositionLocalProvider(LocalChatDensityMetrics provides densityMetrics, LocalBillingCurrencyState provides billingState,
                LocalReplyUsageLookup provides remember(billingViewModel) { { id -> billingViewModel.observeRecord(id) } }) {
            val initialLoadError = state.initialLoadError
            if (initialLoadError != null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("无法打开对话", style = MaterialTheme.typography.titleMedium)
                        Text(
                            initialLoadError,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = viewModel::retryInitialization) { Text("重试") }
                        TextButton(onClick = ::requestLeave) { Text(backLabel) }
                    }
                }
            } else if (!state.isReady) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("正在加载对话…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (state.displayLines.isEmpty() && state.streamingText.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("开始新对话", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "在下方输入消息，开始与角色对话",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                val decoratedLines = remember(
                    visibleDisplayLines,
                    state.characterNames,
                    state.userDisplayName,
                    state.world?.narratorName,
                ) {
                    visibleDisplayLines.decorateChatLineList(
                        characterNames = state.characterNames,
                        userDisplayName = state.userDisplayName,
                        narratorName = state.world?.narratorName.orEmpty(),
                    )
                }
                LaunchedEffect(state.isGenerating) {
                    if (state.isGenerating) {
                        dismissKeyboard()
                    }
                }
                Box(Modifier.fillMaxSize().padding(padding)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = densityMetrics.listContentVertical)
                    ) {
                    if (state.hasOlderMessages) {
                        item(key = "history-load-older") {
                            TextButton(
                                enabled = !state.isLoadingHistory && !state.isGenerating,
                                onClick = {
                                    if (state.isGenerating) showGenerationLockedMessage()
                                    else viewModel.loadOlderMessages()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (state.isLoadingHistory) "正在加载更早消息…" else "加载更早消息")
                            }
                        }
                    }
                    items(
                        decoratedLines,
                        key = { it.line.stableKey },
                        contentType = { it.line.selectedMessage().speakerType },
                    ) { meta ->
                        val line = meta.line
                        val msg = line.selectedMessage()
                        val canContinueReply = !state.hasNewerMessages &&
                            state.messages.lastOrNull()?.id == msg.id &&
                            msg.speakerType == "user"
                        val canRegenerate = ReplyRegenerationPolicy.canRegenerate(
                            messages = state.messages,
                            target = msg,
                            hasNewerMessages = state.hasNewerMessages,
                        )
                        val charId = msg.characterId ?: 0L
                        val anchors = state.branchAnchorsByMessageId[msg.id].orEmpty()
                        val canReturnMain = state.currentBranchId != "main" &&
                            state.branches.any {
                                it.branchId == state.currentBranchId && it.sourceMessageId == msg.id
                            }
                        MessageLineBlock(
                            line = line,
                            messageAttachments = state.messageAttachments,
                            avatarPath = state.characterAvatars[charId] ?: "",
                            avatarColor = state.characterColors[charId] ?: "#F97316",
                            cardImagePath = state.characterCardImages[charId].orEmpty(),
                            userAvatarImagePath = state.userAvatarImagePath,
                            userAvatarColor = state.userAvatarColor,
                            userDisplayName = state.userDisplayName,
                            bookmarkedMessageIds = state.bookmarkedMessageIds,
                            currentChoiceMessageId = state.roundChoiceMessageId,
                            canContinueReply = canContinueReply,
                            canRegenerate = canRegenerate,
                            isGenerating = state.isGenerating,
                            isSavingImages = savingGalleryMessageId != null || pendingGalleryPermissionMessageId != null,
                            senderLabel = meta.senderLabel,
                            showSenderHeader = meta.showSenderHeader,
                            timeText = meta.timeText,
                            onAction = { action ->
                                if (state.isGenerating && action !is MessageAction.Copy &&
                                    action !is MessageAction.ToggleBookmark &&
                                    action !is MessageAction.Speak &&
                                    action !is MessageAction.SaveImages
                                ) {
                                    showGenerationLockedMessage()
                                } else {
                                    when (action) {
                                    is MessageAction.Recall -> { recallMessage = action.message }
                                    is MessageAction.Edit -> {
                                        editSaving = false
                                        editFailure = null
                                        editCommitted = false
                                        editingMessage = action.message
                                        editContent = ChatMessageTextFormat.visibleBody(action.message.content, action.message.speakerType)
                                    }
                                    is MessageAction.Copy -> {
                                        val copyText = ChatMessageTextFormat.forClipboard(action.message.content, action.message.speakerType)
                                        if (copyText.isBlank()) {
                                            scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.messageHasNoCopyableText()) }
                                        } else {
                                            clipboardManager.setText(AnnotatedString(copyText))
                                            scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.copiedToClipboard()) }
                                        }
                                    }
                                    is MessageAction.SaveImages -> {
                                        when {
                                            savingGalleryMessageId != null || pendingGalleryPermissionMessageId != null -> {
                                                scope.launch { snackbarHostState.showSnackbar("图片正在保存，请稍候") }
                                            }
                                            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                                ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                                ) != PackageManager.PERMISSION_GRANTED -> {
                                                pendingGalleryPermissionMessageId = action.message.id
                                                galleryPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                            }
                                            else -> scope.launch { saveMessageImagesToGallery(action.message.id) }
                                        }
                                    }
                                    is MessageAction.ToggleBookmark -> {
                                        viewModel.handleMessageAction(action)
                                        scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.bookmarkUpdated()) }
                                    }
                                    is MessageAction.Quote -> viewModel.handleMessageAction(action)
                                    is MessageAction.CreateBranch -> {
                                        viewModel.handleMessageAction(action)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("已创建分支，可在顶栏「分支」切换")
                                        }
                                    }
                                    is MessageAction.SwitchToBranch -> viewModel.handleMessageAction(action)
                                        else -> viewModel.handleMessageAction(action)
                                    }
                                }
                            },
                            onSelectSwipeVersion = { gid, mid, onResult ->
                                if (state.isGenerating) {
                                    showGenerationLockedMessage()
                                    onResult(false)
                                } else viewModel.selectSwipeVariant(gid, mid, onResult)
                            },
                            currentBranchId = state.currentBranchId,
                            branchAnchors = anchors,
                            canReturnToMain = canReturnMain,
                        )
                    }
                    if (state.hasNewerMessages) {
                        item(key = "history-load-newer") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                TextButton(
                                    enabled = !state.isLoadingHistory && !state.isGenerating,
                                    onClick = {
                                        if (state.isGenerating) showGenerationLockedMessage()
                                        else viewModel.loadNewerMessages()
                                    },
                                ) { Text("加载较新消息") }
                                TextButton(
                                    enabled = !state.isLoadingHistory && !state.isGenerating,
                                    onClick = {
                                        if (state.isGenerating) showGenerationLockedMessage()
                                        else viewModel.returnToLatestMessages()
                                    },
                                ) { Text("回到最新") }
                            }
                        }
                    }
                    if (state.streamingText.isNotEmpty()) {
                        item(key = "streaming") {
                            StreamingText(text = state.streamingText)
                        }
                    }
                    item(key = "chat-end") { Spacer(Modifier.height(1.dp)) }
                    }
                    if (!stickToBottom || state.hasNewerMessages) {
                        ExtendedFloatingActionButton(
                            onClick = { latestLoadAttempted = false; latestRequested = true },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            icon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                            text = { Text(if (state.isLoadingHistory) "加载中" else "回到最新") },
                        )
                    }
                    if (readingMode) {
                        FilledTonalIconButton(onClick = { readingMode = false },
                            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                            Icon(Icons.Default.Edit, "继续对话")
                        }
                    }

                }
            }
            }
        }
    }

    } else {
        com.mojing.app.ui.chat.search.SearchScreen(
            sessionId = sessionId, branchId = state.currentBranchId,
            onBack = { showSearchDialog = false },
        )
    }

    recallMessage?.let { target ->
        RecallMessageDialog(
            content = target.content,
            speakerType = target.speakerType,
            loadImpact = { viewModel.previewMessageRecall(target.id) },
            onConfirm = { result -> viewModel.deleteMessage(target.id, result) },
            onDismiss = { recallMessage = null },
        )
    }

    if (showAddParticipant) {
        AddParticipantDialog(
            availableCharacters = allCharacters,
            isLoading = isLoadingParticipants,
            loadError = participantLoadError,
            isSubmitting = isAddingParticipant,
            onRetry = { participantLoadRevision++ },
            onDismiss = {
                if (!isAddingParticipant) showAddParticipant = false
            },
            onSelect = { characterId ->
                if (!isAddingParticipant) {
                    isAddingParticipant = true
                    viewModel.addParticipant(characterId) { added ->
                        isAddingParticipant = false
                        if (added) showAddParticipant = false
                    }
                }
            }
        )
    }

    if (showUnsavedWorldDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedWorldDialog = false },
            title = { Text("本场线路未保存") },
            text = {
                Text("本场线路未保存。你可以回到侧栏保存，也可以放弃本次修改后退出。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedWorldDialog = false
                    worldCredentialFieldsDirty = false
                    onBack()
                }) { Text("退出并放弃") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showUnsavedWorldDialog = false }) { Text("继续编辑") }
                    TextButton(onClick = {
                        showUnsavedWorldDialog = false
                        scope.launch { drawerState.open() }
                    }) { Text("回去保存") }
                }
            },
        )
    }

    if (showImageGenDialog) {
        ImagePromptDialog(
            prompt = state.imagePrompt,
            onPromptChange = viewModel::updateImagePrompt,
            busy = state.isGenerating,
            onDismiss = { showImageGenDialog = false },
            onGenerate = {
                if (viewModel.generateAndAttachUserMessage(state.imagePrompt.trim())) {
                    showImageGenDialog = false
                }
            },
        )
    }

    if (correctionDialogOpen) {
        var correctionSaving by remember { mutableStateOf(false) }
        var correctionSaveError by remember { mutableStateOf<String?>(null) }
        ChatPromptSheet(
            onDismiss = { correctionDialogOpen = false },
            dismissEnabled = !correctionSaving,
            title = if (correctionEditing == null) "新增用户纠正" else "编辑用户纠正",
            editor = {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(enabled = !correctionSaving,
                        selected = correctionScopeBranchId == null,
                        onClick = { correctionScopeBranchId = null }, label = { Text("整个对话") })
                    FilterChip(enabled = !correctionSaving,
                        selected = correctionScopeBranchId != null,
                        onClick = { correctionScopeBranchId = state.currentBranchId }, label = { Text("仅当前故事线") })
                }
                OutlinedTextField(
                    value = correctionDraft,
                    onValueChange = { correctionDraft = it.take(2000) },
                    enabled = !correctionSaving,
                    label = { Text("纠正内容") },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    minLines = 3,
                    supportingText = { Text("${correctionDraft.length}/2000") },
                )
                if (correctionSourceMessageId != null) Text("已关联原文，可在记忆面板中查看。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            actions = {
                correctionSaveError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (state.isGenerating) Text("生成结束后可保存纠正", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                com.mojing.app.ui.common.MoJingButton(
                    enabled = !state.isGenerating && !correctionSaving && correctionDraft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (!correctionSaving) {
                            correctionSaving = true
                            correctionSaveError = null
                            viewModel.saveMemoryCorrection(
                                correctionId = correctionEditing?.id,
                                content = correctionDraft,
                                branchId = correctionScopeBranchId,
                                sourceMessageId = correctionSourceMessageId,
                            ) { success ->
                                correctionSaving = false
                                if (success) {
                                    correctionDialogOpen = false
                                    correctionEditing = null
                                    correctionDraft = ""
                                    correctionSourceMessageId = null
                                } else correctionSaveError = "保存未完成，填写的内容已保留，请重试。"
                            }
                        }
                    },
                ) { Text(if (correctionSaving) "正在保存…" else "保存纠正") }
            },
        )
    }

    correctionPendingDelete?.let { correction ->
        AlertDialog(
            onDismissRequest = { correctionPendingDelete = null },
            title = { Text("删除用户纠正？") },
            text = { Text("删除后不会影响自动摘要，也不会清空其他记忆。") },
            confirmButton = {
                TextButton(
                    enabled = !state.isGenerating,
                    onClick = {
                        correctionPendingDelete = null
                        viewModel.deleteMemoryCorrection(correction.id)
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { correctionPendingDelete = null }) { Text("取消") } },
        )
    }

    val messageBeingEdited = editingMessage
    if (messageBeingEdited != null) {
        MessageEditDialog(
            content = editContent,
            onContentChange = { editContent = it },
            isUser = messageBeingEdited.speakerType == "user",
            hasChanges = editContent != ChatMessageTextFormat.visibleBody(messageBeingEdited.content, messageBeingEdited.speakerType),
            canSave = !state.isGenerating && ChatMessageTextFormat.hasEditChanges(
                messageBeingEdited.content, messageBeingEdited.speakerType, editContent),
            saving = editSaving,
            failure = editFailure,
            committed = editCommitted,
            onSave = {
                if (!editSaving && !editCommitted) {
                    editSaving = true
                    editFailure = null
                    viewModel.editMessage(messageBeingEdited.id, editContent, onFailure = { message, committed ->
                        if (editingMessage?.id == messageBeingEdited.id) {
                            editSaving = false
                            editFailure = message
                            editCommitted = committed
                        }
                    }) {
                        if (editingMessage?.id == messageBeingEdited.id) {
                            editSaving = false
                            editingMessage = null
                        }
                    }
                }
            },
            onDismiss = { editingMessage = null },
        )
    }

    EmojiPickerBottomSheet(
        visible = showEmojiPicker,
        onDismiss = { showEmojiPicker = false },
        onEmojiSelected = { emoji ->
            val updated = insertTextAtSelection(
                text = inputFieldValue.text,
                selection = inputFieldValue.selection,
                insertion = emoji,
            )
            inputFieldValue = updated
            pendingInputText = updated.text
            viewModel.updateInput(updated.text)
        },
    )

    BranchOverviewBottomSheet(
        visible = showBranchOverview,
        branches = state.branches,
        sourcePreviews = state.branchSourcePreviews,
        currentBranchId = state.currentBranchId,
        onSelectBranch = {
            if (state.isGenerating) showGenerationLockedMessage()
            else viewModel.switchBranch(it)
        },
        onDismiss = { showBranchOverview = false },
    )


}

/** 历史占位、仅标签无正文等：不在列表中占位，避免「仅含自动配图」单独一条气泡。 */
private fun shouldShowCharacterBubbleLine(m: MessageEntity, attachments: List<MessageAttachmentEntity>): Boolean {
    if (m.speakerType != "character") return true
    if (attachments.isNotEmpty()) return true
    val c = m.content.trim()
    if (c.isEmpty()) return false
    if (c == "（本条仅含自动配图/语音指令）") return false
    val reply = StructuredParser.parse(m.content)
    val structuredRenderable = StructuredParser.isStructured(m.content) &&
        (reply.narrations.isNotEmpty() || reply.thoughts.isNotEmpty() ||
            reply.speeches.isNotEmpty() || reply.choices.isNotEmpty())
    if (structuredRenderable) return true
    val raw = if (StructuredParser.isStructured(m.content)) StructuredParser.stripTags(m.content) else m.content
    return ChatMessageTextFormat.forBubbleDisplay(raw).isNotBlank()
}

/** 当前回合选项是输入动作，即使输入法仍打开也必须保持可见。 */
@Suppress("UNUSED_PARAMETER")
internal fun shouldShowRoundChoices(
    isImeOpen: Boolean,
    choices: List<String>,
    isGenerating: Boolean,
): Boolean = choices.isNotEmpty() && !isGenerating

private fun tryLaunchSpeechRecognition(
    context: android.content.Context,
    speechLauncher: ActivityResultLauncher<Intent>,
    onUnavailable: () -> Unit,
): Boolean {
    if (!NativeSpeechRecognizer.isSpeechRecognitionResolvable(context)) {
        onUnavailable()
        return false
    }
    return try {
        speechLauncher.launch(NativeSpeechRecognizer.createIntent())
        true
    } catch (_: ActivityNotFoundException) {
        onUnavailable()
        false
    }
}
