package com.mojing.app.ui.chat

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz

import com.mojing.app.ui.common.MoJingCenterAlignedTopAppBar as TopAppBar
import com.mojing.app.ui.common.MoJingIcon as Icon
import com.mojing.app.ui.common.MoJingFilterChip as FilterChip
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import com.mojing.app.data.local.entity.contextSelectionKey
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val messageEditDraftController: MessageEditDraftController = hiltViewModel()
    val messageEditDraftState by messageEditDraftController.state.collectAsStateWithLifecycle()
    val speechActive by viewModel.speechActive.collectAsStateWithLifecycle()
    val speechPlayback by viewModel.speechPlayback.collectAsStateWithLifecycle()
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
    val visibleDisplayLines = state.displayLines
    val stableSessionTitle = remember(state.sessionTitle) {
        state.sessionTitle.lineSequence().firstOrNull().orEmpty().trim()
    }
    val listState = rememberLazyListState()
    var stickToBottom by remember { mutableStateOf(true) }
    var followGeneration by remember(sessionId, state.currentBranchId) { mutableStateOf(false) }
    var hasManualReadingPosition by remember(sessionId, state.currentBranchId) { mutableStateOf(false) }
    val messageViewport by remember(listState) { androidx.compose.runtime.derivedStateOf { listState.layoutInfo.viewportSize } }
    val readingState by rememberUpdatedState(state)
    val manualScrollConnection = remember(sessionId, state.currentBranchId) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    followGeneration = false
                    hasManualReadingPosition = true
                    val current = readingState
                    val visibleKeys = listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
                    current.displayLines.firstOrNull { it.stableKey in visibleKeys }?.selectedMessage()?.id?.let { anchor ->
                        viewModel.rememberMessageReadingPosition(current.currentBranchId,
                            current.messages.lastOrNull()?.id, current.messages.size, anchor)
                    }
                }
                return Offset.Zero
            }
        }
    }
    var hasAutoPositionedInitially by remember(sessionId, state.currentBranchId) { mutableStateOf(false) }
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
    val drawerState = rememberWidthAwareDrawerState(LocalConfiguration.current.screenWidthDp)
    val scope = rememberCoroutineScope()
    var showAddParticipant by rememberSaveable(sessionId) { mutableStateOf(false) }
    val isAddingParticipant = state.participantAdding
    val participantSubmitError = state.participantAddError
    LaunchedEffect(state.participantAddedId, state.participantAdding) {
        if (state.participantAddedId != null && !state.participantAdding &&
            viewModel.state.value.participantAddedId == state.participantAddedId) {
            showAddParticipant = false
            viewModel.clearParticipantAddFeedback()
        }
    }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var editingMessage by remember { mutableStateOf<com.mojing.app.data.local.entity.MessageEntity?>(null) }
    var editRecoveryMessageId by rememberSaveable(sessionId) { mutableStateOf<Long?>(null) }
    var editRecoveryBranchId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var editContent by remember { mutableStateOf("") }
    var editOriginalBody by remember { mutableStateOf("") }
    var editPreparingMessage by remember(sessionId, state.currentBranchId) {
        mutableStateOf<com.mojing.app.data.local.entity.MessageEntity?>(null)
    }
    var editPreparationJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(sessionId, state.currentBranchId) {
        onDispose { editPreparationJob?.cancel() }
    }
    var editSaving by remember { mutableStateOf(false) }
    var editFailure by remember { mutableStateOf<String?>(null) }
    var editCommitted by rememberSaveable(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId, state.currentBranchId, state.messages, editRecoveryMessageId, editRecoveryBranchId) {
        val recoveryId = editRecoveryMessageId ?: return@LaunchedEffect
        val recoveryBranch = editRecoveryBranchId ?: return@LaunchedEffect
        if (editingMessage == null && recoveryBranch == state.currentBranchId) {
            state.messages.firstOrNull { it.id == recoveryId }?.let { target ->
                editingMessage = target
                messageEditDraftController.open(sessionId, recoveryBranch, recoveryId)
            }
        }
    }
    LaunchedEffect(sessionId, state.currentBranchId) {
        if (editSaving || editCommitted) return@LaunchedEffect
        val target = editingMessage ?: return@LaunchedEffect
        if (target.sessionId == sessionId && messageEditDraftState.scope?.branchId == state.currentBranchId) return@LaunchedEffect
        // A branch/session change invalidates the visible editor target. Keep
        // its latest durable draft, then close the editor before the new scope
        // can submit the old message against the new branch.
        if (messageEditDraftController.dismissRetaining()) {
            editingMessage = null
            editRecoveryMessageId = null
            editRecoveryBranchId = null
            editContent = ""
            editOriginalBody = ""
            editSaving = false
            editFailure = null
            editCommitted = false
        }
    }
    var recallMessage by remember(sessionId, state.currentBranchId) { mutableStateOf<com.mojing.app.data.local.entity.MessageEntity?>(null) }
    var showImageGenDialog by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var readingMode by rememberSaveable(sessionId) { mutableStateOf(false) }
    var latestRequested by remember(sessionId) { mutableStateOf(false) }
    var latestLoadAttempted by remember(sessionId) { mutableStateOf(false) }
    // Prepare only the loaded window before measuring reader rows: tiny async placeholders
    // would otherwise clamp a saved paragraph offset or move an explicit tail jump.
    val preparedReaderWindow by produceState<Pair<List<ChatDisplayLine>, Map<Long, List<String>>>?>(
        null, readingMode, visibleDisplayLines,
    ) {
        value = null
        if (readingMode) {
            val paragraphs = withContext(Dispatchers.Default) {
                visibleDisplayLines.associate { line ->
                    val message = line.selectedMessage()
                    message.id to try { prepareReaderParagraphs(message) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                }
            }
            value = visibleDisplayLines to paragraphs
        }
    }
    val readerParagraphs = preparedReaderWindow?.takeIf { it.first == visibleDisplayLines }?.second

    var showContents by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showRenameSession by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showSearchDialog by rememberSaveable(sessionId) { mutableStateOf(false) }
    var isImportingTextChat by remember(sessionId) { mutableStateOf(false) }
    val bundleProgress by viewModel.mediaBundleProgress.collectAsStateWithLifecycle()
    val bundleNotice by viewModel.mediaBundleNotice.collectAsStateWithLifecycle()
    val isImportingChat = isImportingTextChat || bundleProgress?.kind == "import"
    var showBundleImportInfo by rememberSaveable(sessionId) { mutableStateOf(false) }
    var pendingBundleImportBranch by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var mediaBundleImportInterrupted by rememberSaveable(sessionId) { mutableStateOf(false) }
    var pendingBundleExportBranch by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var mediaBundleExportInterrupted by rememberSaveable(sessionId) { mutableStateOf(false) }
    var importStage by remember(sessionId) { mutableStateOf("") }
    var importCount by remember(sessionId) { mutableIntStateOf(0) }
    var importStopping by remember(sessionId) { mutableStateOf(false) }
    var importJob by remember(sessionId) { mutableStateOf<Job?>(null) }
    var isAddingAttachment by remember(sessionId) { mutableStateOf(false) }
    // Only the document picker survives recreation; its file-writing coroutine does not.
    var pendingChatExportPicker by rememberSaveable(sessionId) { mutableStateOf(false) }
    var pendingNovelExportPicker by rememberSaveable(sessionId) { mutableStateOf(false) }
    var exportWriteInterrupted by rememberSaveable(sessionId) { mutableStateOf(false) }
    var isExportingFile by remember(sessionId) { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val exportBusy = pendingChatExportPicker || pendingNovelExportPicker || pendingBundleExportBranch != null ||
        isExportingFile || bundleProgress?.kind == "export"
    val requestExportNavigation = com.mojing.app.ui.common.rememberExportNavigationGuard(
        exporting = { isExportingFile || bundleProgress?.kind == "export" },
        onStopExport = { exportJob?.cancelAndJoin(); viewModel.stopMediaBundleAndJoin() },
    )
    var savingGalleryMessageId by remember(sessionId) { mutableStateOf<Long?>(null) }
    var showBranchOverview by rememberSaveable(sessionId) { mutableStateOf(false) }
    var branchMenuExpanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var topActionsMenuExpanded by remember { mutableStateOf(false) }
    var worldCredentialFieldsDirty by remember { mutableStateOf(false) }
    var showUnsavedWorldDialog by rememberSaveable(sessionId) { mutableStateOf(false) }
    var correctionDialogOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    var correctionEditingId by rememberSaveable(sessionId) { mutableStateOf<Long?>(null) }
    var correctionDraft by rememberSaveable(sessionId) { mutableStateOf("") }
    var correctionSourceMessageId by rememberSaveable(sessionId) { mutableStateOf<Long?>(null) }
    var correctionScopeBranchId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var correctionPendingDelete by remember { mutableStateOf<SessionMemoryCorrectionEntity?>(null) }
    var inputFieldValue by rememberSaveable(sessionId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = state.inputText,
                selection = TextRange(state.inputText.length),
            ),
        )
    }
    var pendingInputText by remember(sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId, state.inputText, state.isReady) {
        if (!state.isReady) return@LaunchedEffect
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

    // Consume only the restored marker. Clearing it must not cancel this suspended Snackbar.
    LaunchedEffect(sessionId) {
        if (exportWriteInterrupted && !isExportingFile) {
            exportWriteInterrupted = false
            snackbarHostState.showSnackbar("上次导出已中断，文件可能不完整，请重新导出")
        }
    }

    // The screen cancels media work on disposal; a recreated session can have a new VM.
    // Consume the restored marker without using it as this suspended Snackbar's effect key.
    LaunchedEffect(sessionId) {
        if (mediaBundleImportInterrupted && bundleProgress == null) {
            mediaBundleImportInterrupted = false
            snackbarHostState.showSnackbar("上次媒体包导入已中断，请重新选择文件核对；已保存记录不会重复写入")
        }
    }

    LaunchedEffect(sessionId) {
        if (mediaBundleExportInterrupted && bundleProgress == null) {
            mediaBundleExportInterrupted = false
            snackbarHostState.showSnackbar("上次媒体包导出已中断，目标文件可能不完整，请重新导出")
        }
    }

    var correctionEditorBranchId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var correctionSaveRequestId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var correctionSaveError by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    val correctionReceipt by viewModel.correctionSaveReceipt.collectAsStateWithLifecycle()
    val correctionSaving = correctionSaveRequestId != null

    LaunchedEffect(state.currentBranchId, state.isReady) {
        if (!state.isReady) return@LaunchedEffect
        if (correctionDialogOpen && correctionScopeBranchId != null &&
            correctionEditorBranchId != null && correctionEditorBranchId != state.currentBranchId) {
            correctionEditingId = null
            correctionScopeBranchId = state.currentBranchId
        }
        correctionEditorBranchId = state.currentBranchId
    }
    LaunchedEffect(correctionSaveRequestId, correctionReceipt, state.isReady) {
        val requestId = correctionSaveRequestId ?: return@LaunchedEffect
        if (!state.isReady) return@LaunchedEffect
        val receipt = correctionReceipt?.takeIf { it.requestId == requestId }
        if (receipt != null) {
            correctionSaveRequestId = null
            if (receipt.saved) {
                correctionDialogOpen = false
                correctionEditingId = null
                correctionDraft = ""
                correctionSourceMessageId = null
                correctionSaveError = null
            } else correctionSaveError = "保存未完成，填写的内容已保留，请重试。"
        } else if (!viewModel.knowsCorrectionEditorRequest(requestId)) {
            correctionSaveRequestId = null
            correctionSaveError = "上次保存已中断，请先核对用户纠正列表；填写内容已保留。"
        }
    }

    fun showGenerationLockedMessage() {
        scope.launch { snackbarHostState.showSnackbar("当前正在生成，请等待完成或先停止生成") }
    }

    fun dismissKeyboard() {
        hideImeKeyboard(keyboardController, focusManager)
    }

    suspend fun saveMessageImagesToGallery(messageId: Long, expectedBranchId: String? = null) {
        if (savingGalleryMessageId != null) {
            snackbarHostState.showSnackbar("图片正在保存，请稍候")
            return
        }
        savingGalleryMessageId = messageId
        try {
            val result = viewModel.saveMessageImagesToGallery(messageId, expectedBranchId)
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
            state.worldCredentialsSaving -> scope.launch { snackbarHostState.showSnackbar("正在保存线路，请稍候") }
            worldCredentialFieldsDirty -> showUnsavedWorldDialog = true
            bundleProgress?.kind == "import" -> scope.launch {
                viewModel.stopMediaBundleAndJoin()
                onBack()
            }
            else -> requestExportNavigation(onBack)
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

    val mediaLaunchers = rememberChatInputMediaLaunchers(
        sessionId = sessionId,
        currentBranchId = { viewModel.state.value.currentBranchId },
        isCurrentBranch = viewModel::isCurrentChatBranch,
        onSpeechText = { text, branchId ->
            if (viewModel.isCurrentChatBranch(branchId)) {
                val updated = insertTextAtSelection(
                    text = inputFieldValue.text,
                    selection = inputFieldValue.selection,
                    insertion = text,
                )
                if (viewModel.updateInput(updated.text, expectedBranchId = branchId)) {
                    inputFieldValue = updated
                    pendingInputText = updated.text
                }
            }
        },
        onSpeechUnavailable = { scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.speechRecognitionUnavailable()) } },
        onMicPermissionDenied = { scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.micPermissionRequired()) } },
        onLaunchFailure = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
        onImagePicked = { uri, request ->
            if (isAddingAttachment) return@rememberChatInputMediaLaunchers
            isAddingAttachment = true
            scope.launch {
                var copiedPath: String? = null
                try {
                    withContext(Dispatchers.IO) {
                        copiedPath = ChatAttachmentFiles.copyUriToSessionFile(
                            context, uri, request.sessionId, viewModel.maxAttachmentBytes(),
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    val queued = copiedPath?.let { viewModel.queueLocalImageAttachment(it, request.branchId) } == true
                    if (queued) copiedPath = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(e.message ?: UserFacingStrings.imageSaveFailed())
                } finally {
                    copiedPath?.let { abandonedPath ->
                        withContext(NonCancellable + Dispatchers.IO) {
                            ChatAttachmentFiles.deleteOwnedPendingFile(context, request.sessionId, abandonedPath)
                        }
                    }
                    isAddingAttachment = false
                }
            }
        },
        onGalleryPermissionResult = { messageId, branchId, granted ->
            if (granted) scope.launch { saveMessageImagesToGallery(messageId, branchId) }
            else scope.launch { snackbarHostState.showSnackbar("需要存储权限才能保存到系统相册") }
        },
    )
    val tavernImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (isImportingChat) return@rememberLauncherForActivityResult
        isImportingTextChat = true
        importStage = "正在检查文件"
        importCount = 0
        importStopping = false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val notice = try {
                val result = viewModel.importTavernChatStream(
                    openReader = {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)
                            ?: throw IllegalStateException("无法读取文件，请重新选择")
                    },
                    onProgress = { stage, count -> importStage = stage; importCount = count },
                )
                if (result.duplicate) "该聊天记录已导入当前故事线，未重复写入"
                else if (result.refreshFailed) "已导入 ${result.importedCount} 条，列表刷新未完成，请重新打开对话"
                else "已导入聊天记录 ${result.importedCount} 条"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                e.message ?: "导入失败"
            } finally {
                isImportingTextChat = false
                importStage = ""
                importJob = null
            }
            snackbarHostState.showSnackbar(notice)
        }
        importJob = job
        job.start()
    }
    val exportNovelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val requested = pendingNovelExportPicker
        pendingNovelExportPicker = false
        if (requested && uri != null && !isExportingFile && !isImportingChat) {
            isExportingFile = true
            exportWriteInterrupted = true
            exportJob = scope.launch {
                val notice = try {
                    ContentDocumentWriter.writeStream(context, uri) { viewModel.exportNovel(it) }
                    "小说已导出"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { UserFacingStrings.documentWriteFailed(e.message) }
                finally {
                    exportWriteInterrupted = false
                    isExportingFile = false
                }
                snackbarHostState.showSnackbar(notice)
            }
        }
    }
    val exportChatLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val requested = pendingChatExportPicker
        pendingChatExportPicker = false
        if (uri == null || !requested || isExportingFile || isImportingChat) return@rememberLauncherForActivityResult
        isExportingFile = true
        exportWriteInterrupted = true
        exportJob = scope.launch {
            val notice = try {
                ContentDocumentWriter.writeStream(context, uri) { os ->
                    viewModel.exportMainBranchJson(os)
                }
                "聊天记录已导出"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                UserFacingStrings.documentWriteFailed(e.message)
            } finally {
                exportWriteInterrupted = false
                isExportingFile = false
            }
            snackbarHostState.showSnackbar(notice)
        }
    }

    val bundleImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val branchId = pendingBundleImportBranch
        pendingBundleImportBranch = null
        if (uri != null && branchId != null && !isImportingChat && !exportBusy) {
            if (viewModel.importMainBranchMediaBundle(uri, branchId)) mediaBundleImportInterrupted = true
        }
    }
    val bundleExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val branchId = pendingBundleExportBranch
        pendingBundleExportBranch = null
        if (uri != null && branchId != null && !isImportingChat && !isExportingFile && bundleProgress == null) {
            if (viewModel.exportMainBranchMediaBundle(uri, branchId)) mediaBundleExportInterrupted = true
        }
    }
    if (showBundleImportInfo) {
        AlertDialog(
            shape = RoundedCornerShape(16.dp),
            onDismissRequest = { showBundleImportInfo = false },
            title = { Text("导入主线记录与媒体") },
            text = { Text("记录将追加到当前故事线。请先添加与来源同名的参与角色；同名角色需唯一。媒体包不包含世界、其它故事线、记忆或角色卡，仅供墨境 Android 使用。") },
            confirmButton = { TextButton(onClick = {
                showBundleImportInfo = false
                pendingBundleImportBranch = state.currentBranchId.ifBlank { "main" }
                try { bundleImportLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
                catch (_: Exception) {
                    pendingBundleImportBranch = null
                    scope.launch { snackbarHostState.showSnackbar("无法打开文件选择器，请重试") }
                }
            }) { Text("选择媒体包") } },
            dismissButton = { TextButton(onClick = { showBundleImportInfo = false }) { Text("取消") } },
        )
    }

    LaunchedEffect(bundleNotice) {
        bundleNotice?.let { notice ->
            mediaBundleImportInterrupted = false
            mediaBundleExportInterrupted = false
            snackbarHostState.showSnackbar(notice)
            viewModel.clearMediaBundleNotice(notice)
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

    LaunchedEffect(state.isGenerating, stickToBottom, showSearchDialog, state.focusedMessageId) {
        if (showSearchDialog || state.focusedMessageId != null) {
            followGeneration = false
        } else if (state.isGenerating) {
            if (stickToBottom) followGeneration = true
        } else if (followGeneration) {
            withFrameNanos { }
            if (followGeneration) {
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0) listState.scrollToItem(total - 1)
                followGeneration = false
            }
        }
    }

    LaunchedEffect(state.streamingText.length, followGeneration, showSearchDialog) {
        if (!followGeneration || showSearchDialog || state.streamingText.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        if (followGeneration) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) listState.scrollToItem(total - 1)
        }
    }

    LaunchedEffect(visibleDisplayLines.size, state.streamingText.length, stickToBottom) {
        if (!state.isReady || !hasAutoPositionedInitially || state.focusedMessageId != null ||
            !stickToBottom || state.streamingText.isNotEmpty() || state.hasNewerMessages ||
            (state.historyWindowRestored && !hasAutoPositionedInitially)) return@LaunchedEffect
        val total = listState.layoutInfo.totalItemsCount
        if (visibleDisplayLines.isNotEmpty() && total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    LaunchedEffect(sessionId, state.currentBranchId, state.isReady, state.focusedMessageId, visibleDisplayLines.size, messageViewport, readerParagraphs) {
        if (!state.isReady || (readingMode && readerParagraphs == null) ||
            (hasAutoPositionedInitially && hasManualReadingPosition) || visibleDisplayLines.isEmpty()) return@LaunchedEffect
        if (state.historyWindowRestored && state.focusedMessageId == null) {
            hasManualReadingPosition = true
            // LazyListState restores the reader's position once the same bounded rows arrive.
            hasAutoPositionedInitially = true
            return@LaunchedEffect
        }
        if (state.focusedMessageId != null) {
            hasManualReadingPosition = true
            // Cross-branch bookmark or search navigation owns the scroll position.
            hasAutoPositionedInitially = true
            return@LaunchedEffect
        }
        // Ready rows can precede the first LazyColumn measurement after recreation.
        // Wait for that measurement instead of consuming initial positioning on an empty layout.
        val total = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.scrollToItem(total - 1)
        hasAutoPositionedInitially = true
    }

    LaunchedEffect(isImeOpen, visibleDisplayLines.size, stickToBottom) {
        if (!isImeOpen || !stickToBottom || state.streamingText.isNotEmpty()) return@LaunchedEffect
        delay(80)
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    LaunchedEffect(state.isReady, state.focusedMessageId, visibleDisplayLines, readerParagraphs) {
        if (!state.isReady || (readingMode && readerParagraphs == null)) return@LaunchedEffect
        val messageId = state.focusedMessageId ?: return@LaunchedEffect
        val index = visibleDisplayLines.indexOfFirst { line ->
            line.variants.any { it.id == messageId }
        }
        if (index >= 0) {
            val listIndex = index + if (state.hasOlderMessages) 1 else 0
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > listIndex }
            listState.scrollToItem(listIndex)
            viewModel.clearFocusedMessage()
        }
    }

    LaunchedEffect(latestRequested, readingMode, state.currentBranchId, state.hasNewerMessages, state.isLoadingHistory, state.isGenerating, visibleDisplayLines, readerParagraphs) {
        if (!latestRequested || state.isLoadingHistory || (readingMode && readerParagraphs == null)) return@LaunchedEffect
        if (!state.isGenerating && !latestLoadAttempted) {
            latestLoadAttempted = true
            if (!viewModel.returnToLatestMessages()) latestRequested = false
            return@LaunchedEffect
        }
        if (state.hasNewerMessages) {
            if (!state.isGenerating) latestRequested = false
        } else {
            val total = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            listState.scrollToItem(total - 1)
            latestRequested = false
        }
    }

    com.mojing.app.ui.chat.contents.StoryContentsSheet(
        novelTitle = stableSessionTitle,
        busy = state.isGenerating || exportBusy,
        saving = state.novelMetadataSaving,
        saveError = state.novelMetadataError,
        onEditStart = viewModel::clearNovelMetadataError,
        onRenameNovel = viewModel::renameNovel,
        onNextChapter = viewModel::requestNextChapter,
        onForkChapter = viewModel::requestChapterFork,
        onLoadForkInput = viewModel::loadChapterForkInput,
        onSaveForkInput = viewModel::saveChapterForkInput,
        onLoadChapterInput = { viewModel.loadChapterInput(state.currentBranchId) },
        onSaveChapterInput = { title, direction, synchronous ->
            viewModel.saveChapterInput(state.currentBranchId, title, direction, synchronous)
        },
        onRenameChapter = { messageId, branchId, sourceBranchId, title, onSuccess ->
            viewModel.renameChapter(messageId, title, branchId, sourceBranchId, onSuccess)
        },
        onExport = {
            pendingNovelExportPicker = true
            try { exportNovelLauncher.launch("novel_${sessionId}.txt") }
            catch (e: Exception) {
                pendingNovelExportPicker = false
                scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.documentWriteFailed(e.message)) }
            }
        },
        visible = showContents && state.isReady,
        sessionId = sessionId,
        branchId = state.currentBranchId,
        onOpenMessage = viewModel::openMessageInHistoryWithResult,
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




    DisposableEffect(viewModel) {
        viewModel.attachSpeechScreen()
        onDispose {
            viewModel.detachSpeechScreen()
            viewModel.cancelMediaBundle()
            viewModel.cancelPendingAutoNarrator()
        }
    }

    LaunchedEffect(state.contextBudgetError) {
        val message = state.contextBudgetError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message, withDismissAction = true,
            duration = androidx.compose.material3.SnackbarDuration.Indefinite)
        viewModel.clearContextBudgetError(message)
    }

    LaunchedEffect(state.error) {
        val e = state.error ?: return@LaunchedEffect
        UsbSessionLog.e("StateError", e)
        snackbarHostState.showSnackbar(e)
        viewModel.clearError()
    }

    LaunchedEffect(state.speechRetryNotice?.token) {
        val notice = state.speechRetryNotice ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = notice.message,
            actionLabel = "重试",
            withDismissAction = true,
            duration = androidx.compose.material3.SnackbarDuration.Indefinite,
        )
        when (result) {
            androidx.compose.material3.SnackbarResult.ActionPerformed -> viewModel.retryFailedSpeech(notice.token)
            androidx.compose.material3.SnackbarResult.Dismissed -> viewModel.dismissSpeechRetry(notice.token)
        }
    }

    LaunchedEffect(state.imageRetryNotice?.token) {
        val notice = state.imageRetryNotice ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = notice.message,
            actionLabel = "重试配图",
            withDismissAction = true,
            duration = androidx.compose.material3.SnackbarDuration.Indefinite,
        )
        when (result) {
            androidx.compose.material3.SnackbarResult.ActionPerformed -> viewModel.retryFailedImage(notice.token)
            androidx.compose.material3.SnackbarResult.Dismissed -> viewModel.dismissImageRetry(notice.token)
        }
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

    LaunchedEffect(state.currentBranchId, state.characterStatePanel?.branchId) {
        if (state.characterStatePanel?.branchId != null &&
            state.characterStatePanel?.branchId != state.currentBranchId) {
            viewModel.closeCharacterState()
        }
    }

    if (!showSearchDialog || !state.isReady) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.requiredWidth(LocalConfiguration.current.screenWidthDp.dp),
                drawerShape = androidx.compose.ui.graphics.RectangleShape,
                drawerContainerColor = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    Column {
                        if (state.isLoadingHistory) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在加载历史消息…", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        ChatDrawer(
                            sessionId = sessionId,
                            participants = state.participants,
                            world = state.world,
                            memorySegments = state.memorySegments,
                            memorySegmentsLoaded = state.memorySegmentsLoaded,
                            memorySegmentsLoading = state.memorySegmentsLoading,
                            memorySegmentsHasMore = state.memorySegmentsHasMore,
                            memorySegmentsLoadingMore = state.memorySegmentsLoadingMore,
                            memorySegmentsLoadError = state.memorySegmentsLoadError,
                            onLoadMoreMemorySummaries = viewModel::loadMoreMemorySummaries,
                            memorySummariesHasNewer = state.memorySegmentsBeforeId != null || state.memorySegmentsWindowSize > 16,
                            onResetMemorySummaryWindow = viewModel::resetMemorySummaryWindow,
                            onOpenMemorySummaries = viewModel::loadMemorySummariesIfNeeded,
                            contextMemoryText = state.contextMemoryText,
                            contextMemoryLoaded = state.contextMemoryLoaded,
                            contextMemoryLoading = state.contextMemoryLoading,
                            contextMemoryLoadError = state.contextMemoryLoadError,
                            onOpenContextMemory = { viewModel.loadContextMemoryIfNeeded() },
                            onRetryContextMemory = { viewModel.loadContextMemoryIfNeeded(force = true) },
                            contextMemoryStatus = state.contextMemoryStatus,
                            encyclopediaFoundation = state.encyclopediaFoundation,
                            encyclopediaFoundationLoaded = state.encyclopediaFoundationLoaded,
                            encyclopediaFoundationLoading = state.encyclopediaFoundationLoading,
                            encyclopediaFoundationLoadError = state.encyclopediaFoundationLoadError,
                            onOpenEncyclopediaFoundation = { viewModel.loadEncyclopediaFoundationIfNeeded() },
                            onRetryEncyclopediaFoundation = { viewModel.loadEncyclopediaFoundationIfNeeded(force = true) },
                            memoryOperationRunning = state.memoryOperationRunning,
                            memorySummaryEditSavedId = state.memorySummaryEditSavedId,
                            memorySummaryEditSavedText = state.memorySummaryEditSavedText,
                            onResolveMemorySummaryEditor = viewModel::resolveMemorySummaryEditor,
                            manualCompactionRunning = state.manualCompactionRunning,
                            manualCompactionChunk = state.manualCompactionChunk,
                            memoryCorrections = state.memoryCorrections,
                            memoryCorrectionsLoaded = state.memoryCorrectionsLoaded,
                            memoryCorrectionsLoading = state.memoryCorrectionsLoading,
                            memoryCorrectionsHasMore = state.memoryCorrectionsHasMore,
                            memoryCorrectionsLoadError = state.memoryCorrectionsLoadError,
                            onOpenMemoryCorrections = viewModel::loadMemoryCorrectionsIfNeeded,
                            onLoadMoreMemoryCorrections = viewModel::loadMoreMemoryCorrections,
                            memoryCorrectionPromptTrace = state.lastMemoryCorrectionPromptTrace,
                            currentBranchId = state.currentBranchId,
                            drawerOpen = drawerState.isOpen,
                            sessionReady = state.isReady,
                            isGenerating = state.isGenerating,
                            eventNodes = state.eventNodes,
                            eventNodesLoaded = state.eventNodesLoaded,
                            eventNodesHasMore = state.eventNodesHasMore,
                            eventNodesLoadingMore = state.eventNodesLoadingMore,
                            eventNodesLoadError = state.eventNodesLoadError,
                            eventQuery = state.eventQuery,
                            eventResolvedFilter = state.eventResolvedFilter,
                            onEventQueryChange = viewModel::updateEventQuery,
                            onEventResolvedFilterChange = viewModel::updateEventResolvedFilter,
                            eventNodesHasNewer = state.eventNodesBeforeId != null,
                            onResetEventWindow = viewModel::resetEventWindow,
                            onLoadMoreEventNodes = viewModel::loadMoreEventNodes,
                            onOpenEvents = viewModel::loadEventNodesIfNeeded,
                            characterNames = state.characterNames,
                            characterAvatars = state.characterAvatars,
                            characterSummaries = state.characterSummaries,
                            onOpenCharacterState = { characterId ->
                                viewModel.openCharacterState(characterId, state.currentBranchId)
                            },
                            bookmarks = state.bookmarks,
                            bookmarksLoaded = state.bookmarksLoaded,
                            bookmarksHasMore = state.bookmarksHasMore,
                            bookmarksLoadingMore = state.bookmarksLoadingMore,
                            bookmarksLoadError = state.bookmarksLoadError,
                            bookmarkQuery = state.bookmarkQuery,
                            onBookmarkQueryChange = viewModel::updateBookmarkQuery,
                            bookmarksHasNewer = state.bookmarksBeforeId != null,
                            onResetBookmarkWindow = viewModel::resetBookmarkWindow,
                            bookmarkBusyIds = state.bookmarkBusyIds,
                            bookmarkLocatingId = state.bookmarkLocatingId,
                            bookmarkPreviews = state.bookmarkPreviews,
                            bookmarkNoteDrafts = state.bookmarkNoteDrafts,
                            bookmarkNoteErrors = state.bookmarkNoteErrors,
                            bookmarkNoteSavingIds = state.bookmarkNoteSavingIds,
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
                            onBookmarkNoteDraftChange = viewModel::updateBookmarkNoteDraft,
                            onSaveBookmarkNote = { bookmarkId, note, onResult ->
                                viewModel.saveBookmarkNote(bookmarkId, note, onResult)
                            },
                            onOpenBookmarks = viewModel::loadBookmarksIfNeeded,
                            onLoadMoreBookmarks = viewModel::loadMoreBookmarks,
                            onToggleMute = { viewModel.toggleMute(it) },
                            onUpdateTalkativeness = { id, value, onResult ->
                                viewModel.updateParticipantTalkativeness(id, value, onResult)
                            },
                            participantTalkativenessSaving = state.participantTalkativenessSaving,
                            participantMuteSaving = state.participantMuteSaving,
                            participantRemoving = state.participantRemoving,
                            speakerTurnMode = speakerTurnMode,
                            onSpeakerTurnModeChange = { mode ->
                                if (viewModel.updateSpeakerTurnMode(mode)) {
                                    speakerTurnMode = mode
                                }
                            },
                            onRemoveParticipant = { viewModel.removeParticipant(it) },
                            onAddParticipant = {
                                viewModel.clearParticipantAddFeedback()
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
                            worldCredentialsSaving = state.worldCredentialsSaving,
                            worldSettingSaving = state.worldSettingSaving,
                            onToggleEventResolved = { viewModel.toggleEventNodeResolved(it) },
                            eventBusyIds = state.eventBusyIds,
                            eventActionErrors = state.eventActionErrors,
                            onDeleteEventNode = { viewModel.deleteEventNode(it) },
                            onJumpToMemorySource = { messageId, onResult ->
                                if (state.isGenerating) {
                                    showGenerationLockedMessage()
                                    false
                                } else viewModel.openMemorySourceInHistory(messageId, onResult)
                            },
                            onAddMemoryCorrection = { content, sourceId ->
                                correctionEditingId = null
                                correctionDraft = content
                                correctionSourceMessageId = sourceId
                                correctionScopeBranchId = state.currentBranchId
                                correctionEditorBranchId = state.currentBranchId
                                correctionSaveError = null
                                correctionDialogOpen = true
                            },
                            onEditMemoryCorrection = { correction ->
                                correctionEditingId = correction.id
                                correctionDraft = correction.content
                                correctionSourceMessageId = correction.sourceMessageId
                                correctionScopeBranchId = correction.branchId
                                correctionEditorBranchId = state.currentBranchId
                                correctionSaveError = null
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
                            onContinueStorySummary = {
                                viewModel.continueCurrentStorySummary { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            },
                            onStopStorySummary = {
                                viewModel.stopCurrentStorySummary()
                                scope.launch { snackbarHostState.showSnackbar("已停止整理，稍后可继续") }
                            },
                            onEditMemorySummary = { segment, summary, onResult ->
                                viewModel.editMemorySummary(segment, summary) { success, message ->
                                    onResult(success)
                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                }
                            },
                            onDeleteMemorySummary = { segment ->
                                viewModel.deleteMemorySummary(segment) { message ->
                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                }
                            },
                            onClearContextMemory = { branchId ->
                                if (state.isGenerating) {
                                    showGenerationLockedMessage()
                                } else {
                                    viewModel.clearCurrentContextMemory(branchId) { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                }
                            },
                            contextMemoryClearError = state.contextMemoryClearError,
                            onRetryClearContextMemory = { branchId ->
                                if (state.isGenerating) {
                                    showGenerationLockedMessage()
                                } else {
                                    viewModel.clearCurrentContextMemory(branchId) { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                }
                            },
                            allowSessionThinkMax = state.allowSessionThinkMax,
                            sessionThinkMaxEnabled = state.sessionThinkMaxEnabled,
                            sessionThinkMaxSaving = state.sessionThinkMaxSaving,
                            sessionThinkMaxSaveError = state.sessionThinkMaxSaveError,
                            characterForcesThinkMax = state.characterForcesThinkMax,
                            onSessionThinkMax = { enabled ->
                                viewModel.setSessionThinkMax(enabled) { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            }
                        )
                    }
                    if (drawerState.isOpen) {
                        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                    }
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                val compactHeader = LocalConfiguration.current.screenWidthDp < 400
                Column(Modifier.fillMaxWidth()) {
                    TopAppBar(
                        expandedHeight = 52.dp,
                        title = {
                            Text(stableSessionTitle.ifBlank { "对话" }, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                dismissKeyboard()
                                requestLeave()
                            }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel)
                            }
                        },
                        actions = {
                            if (readingMode && state.world?.gameplayMode == "小说创作") {
                                IconButton(onClick = { dismissKeyboard(); showContents = true }) {
                                    Icon(Icons.Outlined.FormatListBulleted, "小说目录")
                                }
                            }
                            if (readingMode) IconButton(onClick = { readingMode = false }) {
                                Icon(Icons.Outlined.Edit, "退出阅读模式")
                            }
                        },
                    )
                    androidx.compose.animation.AnimatedVisibility(visible = !readingMode,
                        enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                            TextButton(onClick = { dismissKeyboard(); readingMode = !readingMode },
                                contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Icon(if (readingMode) Icons.Outlined.Edit else Icons.Outlined.MenuBook,
                                    if (readingMode) "退出阅读模式" else "阅读模式")
                                Spacer(Modifier.width(4.dp))
                                Text(if (readingMode) "对话" else "阅读模式", style = MaterialTheme.typography.labelSmall)
                            }
                            if (!compactHeader && state.world?.gameplayMode == "小说创作") {
                                TextButton(onClick = { dismissKeyboard(); showContents = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Icon(Icons.Outlined.FormatListBulleted, "小说目录")
                                    Spacer(Modifier.width(4.dp)); Text("目录", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Box {
                                BranchSelector(
                                    expanded = branchMenuExpanded && state.isReady,
                                    onDismiss = { branchMenuExpanded = false },
                                    branches = listOf("main" to "主线") +
                                        state.branches.map { it.branchId to (it.label.ifEmpty { it.branchId }) },
                                    currentBranch = state.currentBranchId,
                                    onSelect = { branchId ->
                                        branchMenuExpanded = false
                                        if (isImportingChat) {
                                            scope.launch { snackbarHostState.showSnackbar("请先完成或停止聊天记录导入") }
                                        } else if (state.isGenerating) {
                                            showGenerationLockedMessage()
                                        } else if (branchId == "CREATE_NEW") {
                                            viewModel.createBranch(state.messages.lastOrNull()?.id ?: 0L)
                                        } else {
                                            viewModel.switchBranch(branchId)
                                        }
                                    },
                                    onShowBranchOverview = {
                                        if (isImportingChat) scope.launch { snackbarHostState.showSnackbar("请先完成或停止聊天记录导入") }
                                        else if (state.isGenerating) showGenerationLockedMessage()
                                        else showBranchOverview = true
                                    },
                                )
                                TextButton(onClick = {
                                    dismissKeyboard()
                                    topActionsMenuExpanded = true
                                }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Icon(Icons.Outlined.MoreHoriz, contentDescription = "会话菜单")
                                    Spacer(Modifier.width(4.dp)); Text("更多", style = MaterialTheme.typography.labelSmall)
                                }
                                DropdownMenu(
                                    expanded = topActionsMenuExpanded,
                                    onDismissRequest = { topActionsMenuExpanded = false },
                                ) {
                                    if (compactHeader && state.world?.gameplayMode == "小说创作") {
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(Icons.Outlined.FormatListBulleted, null) },
                                            text = { Text("小说目录") },
                                            onClick = {
                                                topActionsMenuExpanded = false
                                                dismissKeyboard()
                                                showContents = true
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                        text = { Text(if (state.world?.gameplayMode == "小说创作") "修改小说标题" else "重命名对话") },
                                        onClick = {
                                            topActionsMenuExpanded = false
                                            dismissKeyboard()
                                            viewModel.clearNovelMetadataError()
                                            showRenameSession = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(state.branchNavigationLabel ?: "故事线") },
                                        leadingIcon = { Icon(Icons.Outlined.AccountTree, null) },
                                        enabled = state.branchNavigationLabel == null && !isImportingChat,
                                        onClick = {
                                            topActionsMenuExpanded = false
                                            if (state.isGenerating) showGenerationLockedMessage()
                                            else branchMenuExpanded = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
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
                                        leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
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
                                            !exportBusy,
                                        onClick = {
                                            topActionsMenuExpanded = false
                                            tavernImportLauncher.launch(
                                                arrayOf("application/json", "text/plain", "text/*", "*/*"),
                                            )
                                        },
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Outlined.FileUpload, null) },
                                        text = { Text(if (exportBusy) "正在导出文件…" else "导出主线聊天记录…") },
                                        enabled = !exportBusy && !isImportingChat,
                                        onClick = {
                                            topActionsMenuExpanded = false
                                            pendingChatExportPicker = true
                                            try { exportChatLauncher.launch("chat_${sessionId}_${System.currentTimeMillis()}.json") }
                                            catch (e: Exception) {
                                                pendingChatExportPicker = false
                                                scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.documentWriteFailed(e.message)) }
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Outlined.FileUpload, null) },
                                        text = { Text("导出主线记录与媒体…") },
                                        enabled = !exportBusy && !isImportingChat && !state.isGenerating,
                                        onClick = {
                                            topActionsMenuExpanded = false
                                            pendingBundleExportBranch = state.currentBranchId.ifBlank { "main" }
                                            try { bundleExportLauncher.launch("chat_media_${sessionId}_${System.currentTimeMillis()}.zip") }
                                            catch (_: Exception) {
                                                pendingBundleExportBranch = null
                                                scope.launch { snackbarHostState.showSnackbar("无法打开文件选择器，请重试") }
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                                        text = { Text("导入主线记录与媒体…") },
                                        enabled = !exportBusy && !isImportingChat && !state.isGenerating,
                                        onClick = { topActionsMenuExpanded = false; showBundleImportInfo = true },
                                    )
                                }
                            }
                            TextButton(onClick = {
                                dismissKeyboard()
                                scope.launch {
                                    if (drawerState.isClosed) drawerState.open() else drawerState.close()
                                }
                            }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Icon(Icons.Outlined.Info, "会话设置与资料")
                                Spacer(Modifier.width(4.dp)); Text("信息", style = MaterialTheme.typography.labelSmall)
                            }
                    }
                    }
                    if (speechActive) {
                        SpeechPlaybackBar(
                            snapshot = speechPlayback,
                            voiceRequestLabel = state.speechVoiceRequestLabel,
                            onPause = viewModel::pauseSpeaking,
                            onResume = viewModel::resumeSpeaking,
                            onStop = viewModel::stopSpeaking,
                        )
                    }
                    if (isImportingChat || bundleProgress != null) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .padding(start = 16.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    bundleProgress?.let {
                                        if (it.stopping) "正在停止…" else "${it.stage} · ${it.count} 项"
                                    } ?: if (importStopping) "正在停止导入…" else "$importStage · $importCount 条",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                TextButton(enabled = if (bundleProgress != null) bundleProgress?.stopping != true else !importStopping, onClick = {
                                    if (bundleProgress != null) viewModel.cancelMediaBundle()
                                    else {
                                        importStopping = true
                                        importJob?.cancel()
                                        scope.launch { snackbarHostState.showSnackbar("导入已停止；如恰好完成提交，请在当前故事线核对结果") }
                                    }
                                }) { Text("停止") }
                            }
                        }
                    }
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
            snackbarHost = {
                if (!drawerState.isOpen) SnackbarHost(snackbarHostState)
            },
            bottomBar = {
                if (state.isReady && !state.sessionNotFound && !readingMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
                    color = MaterialTheme.colorScheme.surface,
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
                        state.savedImageNotice?.takeIf { it.branchId == state.currentBranchId }?.let {
                            SavedImageNoticeCard(
                                busy = state.isLoadingHistory || state.isGenerating,
                                onOpen = { viewModel.showSavedImage() },
                            )
                        }
                        state.quotingMessage?.let { q ->
                            QuoteDraftPreview(
                                text = state.quotingSnippet ?: "正在准备引用…",
                                speakerLabel = when (q.speakerType) {
                                    "user" -> state.userDisplayName.ifBlank { "我" }
                                    "narrator" -> state.world?.narratorName?.ifBlank { "旁白" } ?: "旁白"
                                    else -> state.characterNames[q.characterId].orEmpty().ifBlank { "角色" }
                                },
                                onCancel = { viewModel.setQuotingMessage(null) },
                            )
                        }
                        state.replyRecovery?.let { recovery ->
                            ReplyRecoveryCard(
                                notice = recovery,
                                busy = state.replyRecoveryBusy,
                                error = state.replyRecoveryError,
                                clipboardManager = clipboardManager,
                                onKeep = viewModel::keepRecoveredReply,
                                onDiscard = viewModel::discardRecoveredReply,
                            )
                        }
                        if (state.hasNewerMessages) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "正在查看较早消息，先回到最新再续聊",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    TextButton(
                                        enabled = !state.isLoadingHistory && !state.isGenerating,
                                        onClick = {
                                            latestLoadAttempted = false
                                            latestRequested = true
                                        },
                                    ) { Text(if (state.isLoadingHistory) "加载中…" else "回到最新") }
                                }
                            }
                        }
                        InputBar(
                            modelSelector = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) {
                                        ChatInputModelSelector(modelLabel, state.isGenerating) {
                                            dismissKeyboard(); showModelPicker = true
                                        }
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Surface(onClick = { dismissKeyboard(); showVoicePicker = true },
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Outlined.Mic, "选择语音", Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("语音", style = MaterialTheme.typography.labelMedium)
                                            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp))
                                        }
                                    }
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
                            onSend = {
                                if (isImportingChat) scope.launch { snackbarHostState.showSnackbar("请先完成或停止聊天记录导入") }
                                else viewModel.sendMessage()
                            },
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
                                if (isImportingChat) scope.launch { snackbarHostState.showSnackbar("请先完成或停止聊天记录导入") }
                                else if (state.isGenerating) showGenerationLockedMessage()
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
                            isListening = mediaLaunchers.speechListening,
                            isImeOpen = isImeOpen,
                            onVoiceClick = {
                                if (state.isGenerating) {
                                    showGenerationLockedMessage()
                                } else {
                                    mediaLaunchers.launchSpeech()
                                }
                            },
                            onImageGenClick = {
                                if (isImportingChat) scope.launch { snackbarHostState.showSnackbar("请先完成或停止聊天记录导入") }
                                else if (state.isGenerating) showGenerationLockedMessage()
                                else showImageGenDialog = true
                            },
                            onAttachImageClick = {
                                if (state.isGenerating) showGenerationLockedMessage()
                                else if (isAddingAttachment) {
                                    scope.launch { snackbarHostState.showSnackbar("正在添加图片，请稍候") }
                                }
                                else mediaLaunchers.launchImage()
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
                rowHorizontal = 20.dp, narratorHorizontal = 20.dp, bubbleMaxWidth = 680.dp,
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
            } else if (readingMode && readerParagraphs == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("正在准备阅读正文…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (state.displayLines.isEmpty() && !state.hasOlderMessages &&
                !state.hasNewerMessages && state.streamingText.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("开始新对话", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "在下方输入消息，开始与角色对话",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                    val showSidebar = maxWidth > 840.dp
                    if (showSidebar) {
                        Surface(Modifier.width(300.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { Text("故事线", style = MaterialTheme.typography.titleSmall) }
                                items((listOf("main" to "主线") + state.branches.map { it.branchId to it.label.ifBlank { it.branchId } }).distinctBy { it.first }, key = { it.first }) { (id, label) ->
                                    TextButton(onClick = { viewModel.switchBranch(id) }, enabled = !state.isGenerating && !isImportingChat) {
                                        Text(label, color = if (id == state.currentBranchId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                item { HorizontalDivider(); Text("当前窗口", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall) }
                                items(visibleDisplayLines, key = { it.selectedMessage().id }) { line ->
                                    val message = line.selectedMessage()
                                    TextButton(onClick = {
                                        viewModel.openMessageInHistoryWithResult(message.id) { success ->
                                            if (!success) scope.launch { snackbarHostState.showSnackbar("消息暂时无法定位，请重试") }
                                        }
                                    }, enabled = !state.isGenerating && !isImportingChat) {
                                        Text(ChatMessageTextFormat.visibleBody(message.content.take(256), message.speakerType).take(64), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.padding(start = if (showSidebar) 316.dp else 0.dp).align(Alignment.TopCenter).widthIn(max = if (readingMode) 680.dp else androidx.compose.ui.unit.Dp.Infinity).fillMaxSize().nestedScroll(manualScrollConnection).semantics { contentDescription = "对话正文" },
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
                            msg.contextSelectionKey() !in state.excludedContextKeys &&
                            msg.speakerType == "user"
                        val canRegenerate = ReplyRegenerationPolicy.canRegenerate(
                            messages = state.messages,
                            target = msg,
                            hasNewerMessages = state.hasNewerMessages,
                        ) && msg.contextSelectionKey() !in state.excludedContextKeys
                        val charId = msg.characterId ?: 0L
                        val anchors = state.branchAnchorsByMessageId[msg.id].orEmpty()
                        val canReturnMain = state.currentBranchId != "main" &&
                            state.branches.any {
                                it.branchId == state.currentBranchId && it.sourceMessageId == msg.id
                            }
                        MessageLineBlock(
                            readingMode = readingMode,
                            preparedReaderParagraphs = readerParagraphs?.get(msg.id),
                            line = line,
                            messageAttachments = state.messageAttachments,
                            avatarPath = state.characterAvatars[charId] ?: "",
                            avatarColor = state.characterColors[charId] ?: "#F97316",
                            cardImagePath = state.characterCardImages[charId].orEmpty(),
                            userAvatarImagePath = state.userAvatarImagePath,
                            userAvatarColor = state.userAvatarColor,
                            userDisplayName = state.userDisplayName,
                            bookmarkedMessageIds = state.bookmarkedMessageIds,
                            excludedContextKeys = state.excludedContextKeys,
                            currentChoiceMessageId = state.roundChoiceMessageId,
                            canContinueReply = canContinueReply,
                            canRegenerate = canRegenerate,
                            isGenerating = state.isGenerating,
                            isSavingImages = savingGalleryMessageId != null || mediaLaunchers.galleryPermissionPending,
                            senderLabel = meta.senderLabel,
                            showSenderHeader = meta.showSenderHeader,
                            timeText = meta.timeText,
                            onAction = { action ->
                                if (state.isGenerating && action !is MessageAction.Copy &&
                                    action !is MessageAction.ToggleBookmark &&
                                    action !is MessageAction.Speak &&
                                    action !is MessageAction.PlayVoiceAttachments &&
                                    action !is MessageAction.SaveImages
                                ) {
                                    showGenerationLockedMessage()
                                } else {
                                    when (action) {
                                    is MessageAction.Recall -> { recallMessage = action.message }
                                    is MessageAction.Edit -> {
                                        editPreparationJob?.cancel()
                                        editPreparingMessage = null
                                        editSaving = false
                                        editFailure = null
                                        editCommitted = false
                                        val target = action.message
                                            val branchAtStart = state.currentBranchId
                                        if (target.content.length <= ChatMessageTextFormat.ASYNC_BODY_CHAR_THRESHOLD) {
                                            val body = ChatMessageTextFormat.visibleBody(target.content, target.speakerType)
                                            editOriginalBody = body
                                            editContent = body
                                            editingMessage = target
                                                editRecoveryMessageId = target.id
                                                editRecoveryBranchId = branchAtStart
                                                messageEditDraftController.open(sessionId, branchAtStart, target.id)
                                        } else {
                                            editPreparingMessage = target
                                            editPreparationJob = scope.launch {
                                                try {
                                                    val body = withContext(Dispatchers.Default) {
                                                        ChatMessageTextFormat.visibleBody(target.content, target.speakerType)
                                                    }
                                                    if (editPreparingMessage === target &&
                                                        viewModel.state.value.currentBranchId == branchAtStart) {
                                                        editOriginalBody = body
                                                        editContent = body
                                                        editingMessage = target
                                                        editRecoveryMessageId = target.id
                                                        editRecoveryBranchId = branchAtStart
                                                        messageEditDraftController.open(sessionId, branchAtStart, target.id)
                                                        editPreparingMessage = null
                                                    }
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (_: Exception) {
                                                    if (editPreparingMessage === target &&
                                                        viewModel.state.value.currentBranchId == branchAtStart) {
                                                        editPreparingMessage = null
                                                        snackbarHostState.showSnackbar("长消息准备失败，请重试编辑")
                                                    }
                                                } finally {
                                                    if (editPreparationJob === currentCoroutineContext()[Job]) {
                                                        editPreparationJob = null
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    is MessageAction.Copy -> {
                                        scope.launch {
                                            val copyText = withContext(Dispatchers.Default) {
                                                ChatMessageTextFormat.forClipboard(action.message.content, action.message.speakerType)
                                            }
                                            if (copyText.isBlank()) {
                                                snackbarHostState.showSnackbar(UserFacingStrings.messageHasNoCopyableText())
                                            } else {
                                                clipboardManager.setText(AnnotatedString(copyText))
                                                snackbarHostState.showSnackbar(UserFacingStrings.copiedToClipboard())
                                            }
                                        }
                                    }
                                    is MessageAction.SaveImages -> {
                                        when {
                                            savingGalleryMessageId != null || mediaLaunchers.galleryPermissionPending -> {
                                                scope.launch { snackbarHostState.showSnackbar("图片正在保存，请稍候") }
                                            }
                                            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                                ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                                ) != PackageManager.PERMISSION_GRANTED -> {
                                                mediaLaunchers.requestGalleryPermission(action.message.id)
                                            }
                                            else -> scope.launch { saveMessageImagesToGallery(action.message.id) }
                                        }
                                    }
                                    is MessageAction.RetryAutoImage -> {
                                        if (!viewModel.retryAutoCharacterImage(action.message.id)) {
                                            scope.launch { snackbarHostState.showSnackbar("配图当前无法重试，请稍后再试") }
                                        }
                                    }
                                    is MessageAction.RetryAutoVoice -> {
                                        if (!viewModel.retryAutoCharacterVoice(action.message.id)) {
                                            scope.launch { snackbarHostState.showSnackbar("配音当前无法重试，请稍后再试") }
                                        }
                                    }
                                    is MessageAction.ToggleBookmark -> {
                                        viewModel.handleMessageAction(action)
                                        scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.bookmarkUpdated()) }
                                    }
                                    is MessageAction.SetContextExcluded -> {
                                        viewModel.setMessageContextExcluded(action.message.id, action.excluded) { saved ->
                                            if (saved) scope.launch {
                                                snackbarHostState.showSnackbar(if (action.excluded)
                                                    "已从当前故事线的后续上下文排除" else "已恢复到当前故事线的后续上下文")
                                            }
                                        }
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
                    if (!readingMode && shouldShowRoundChoices(isImeOpen, state.roundChoiceOptions, state.isGenerating)) {
                        item(key = "round-choices") {
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
                    }
                    item(key = "chat-end") { Spacer(Modifier.height(1.dp)) }
                    }
                    if ((!stickToBottom && !followGeneration) || state.hasNewerMessages) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                if (state.isGenerating) followGeneration = true
                                latestLoadAttempted = false
                                latestRequested = true
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).semantics {
                                // Material3 clears the extended FAB text subtree's semantics.
                                contentDescription = if (state.isLoadingHistory) "加载中" else "回到最新"
                            },
                            icon = { Icon(Icons.Outlined.KeyboardArrowDown, null) },
                            text = { Text(if (state.isLoadingHistory) "加载中" else "回到最新") },
                        )
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
            onOpenInChat = viewModel::openMessageInHistoryInBranch,
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

    if (showAddParticipant && state.isReady) {
        AddParticipantDialog(
            loadPage = viewModel::loadAddParticipantPage,
            isSubmitting = isAddingParticipant,
            submitError = participantSubmitError,
            onDismiss = {
                if (!isAddingParticipant) showAddParticipant = false
            },
            onSelect = { characterId ->
                if (!isAddingParticipant) {
                    viewModel.addParticipant(characterId)
                }
            }
        )
    }

    if (showUnsavedWorldDialog && state.isReady) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { showUnsavedWorldDialog = false },
            title = { Text("本场线路未保存") },
            text = {
                Text(if (state.worldCredentialsSaving) "线路正在保存，请稍候再退出。" else "本场线路未保存。你可以回到侧栏保存，也可以放弃本次修改后退出。")
            },
            confirmButton = {
                TextButton(enabled = !state.worldCredentialsSaving, onClick = {
                    showUnsavedWorldDialog = false
                    requestExportNavigation {
                        worldCredentialFieldsDirty = false
                        onBack()
                    }
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

    state.bookmarkReadOnlyId?.let { messageId ->
        val message = state.bookmarkReadOnlyMessage
        val body = message?.let { ChatMessageTextFormat.visibleBody(it.content, it.speakerType)
            .ifBlank { "（这条消息没有文字正文）" } }
        val sourceLabel = when {
            message == null -> null
            message.branchId == state.currentBranchId -> "当前故事线"
            message.branchId == "main" -> "主线"
            else -> state.branches.firstOrNull { it.branchId == message.branchId }?.label ?: "原故事线"
        }
        BookmarkReadOnlyDialog(
            sessionId = state.sessionId, readingBranchId = state.bookmarkReadOnlyBranchId.orEmpty(),
            messageId = messageId, body = body, sourceLabel = sourceLabel,
            loading = !state.isReady || state.bookmarkReadOnlyLoading, error = state.bookmarkReadOnlyError,
            onDismiss = viewModel::closeBookmarkedReadOnlyMessage,
            onRetry = viewModel::retryBookmarkedReadOnlyMessage,
            onCopy = { body?.let { clipboardManager.setText(AnnotatedString(it)) } },
        )
    }

    if (correctionDialogOpen && state.isReady) {
        ChatPromptSheet(
            onDismiss = { correctionDialogOpen = false },
            dismissEnabled = !correctionSaving,
            title = if (correctionEditingId == null) "新增用户纠正" else "编辑用户纠正",
            editor = {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(enabled = !correctionSaving,
                        selected = correctionScopeBranchId == null,
                        onClick = { correctionScopeBranchId = null }, label = { Text("整个对话") })
                    FilterChip(enabled = !correctionSaving,
                        selected = correctionScopeBranchId != null,
                        onClick = { correctionScopeBranchId = state.currentBranchId }, label = { Text("仅当前故事线") })
                }
                com.mojing.app.ui.common.MoJingWritingField(
                    value = correctionDraft,
                    onValueChange = { correctionDraft = it.take(2000) },
                    enabled = !correctionSaving,
                    label = "纠正内容",
                    placeholder = "写下需要修正的事实或角色认知",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                Text("${correctionDraft.length}/2000", Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            val requestId = java.util.UUID.randomUUID().toString()
                            correctionSaveRequestId = requestId
                            correctionSaveError = null
                            viewModel.saveMemoryCorrectionFromEditor(
                                requestId = requestId,
                                correctionId = correctionEditingId,
                                content = correctionDraft,
                                branchId = correctionScopeBranchId,
                                sourceMessageId = correctionSourceMessageId,
                            )
                        }
                    },
                ) { Text(if (correctionSaving) "正在保存…" else "保存纠正") }
            },
        )
    }

    correctionPendingDelete?.let { correction ->
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
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

    if (editPreparingMessage != null) {
        val cancelPreparation = {
            editPreparationJob?.cancel()
            editPreparationJob = null
            editPreparingMessage = null
        }
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = cancelPreparation,
            title = { Text("正在打开编辑器") },
            text = { Text("长消息正在准备，请稍候") },
            confirmButton = { TextButton(onClick = cancelPreparation) { Text("取消") } },
        )
    }

    val messageBeingEdited = editingMessage
    if (messageBeingEdited != null) {
        val editScope = messageEditDraftState.scope
        val editScopeMatchesVisibleTarget = editScope != null && editScope.sessionId == sessionId &&
            editScope.branchId == state.currentBranchId &&
            editScope.messageId == messageBeingEdited.id &&
            messageBeingEdited.sessionId == sessionId
        val closeEditor: () -> Unit = {
            editingMessage = null
            editRecoveryMessageId = null
            editRecoveryBranchId = null
            editContent = ""
            editOriginalBody = ""
        }
        val discardEditor: () -> Unit = {
            if (!messageEditDraftState.dirty && !messageEditDraftState.hasRecoverableDraft) {
                closeEditor()
            } else {
                scope.launch {
                    if (messageEditDraftController.discard(messageEditDraftState.revision)) {
                        closeEditor()
                    }
                }
            }
        }
        val retainEditor: () -> Unit = {
            scope.launch {
                if (messageEditDraftController.dismissRetaining()) closeEditor()
            }
        }
        MessageEditDialog(
            content = messageEditDraftState.content,
            onContentChange = messageEditDraftController::update,
            isUser = messageBeingEdited.speakerType == "user",
            hasChanges = messageEditDraftState.dirty,
            canSave = editScopeMatchesVisibleTarget && !state.isGenerating && !messageEditDraftState.isLoading &&
                messageEditDraftState.target != null && messageEditDraftState.content.trim().let { candidate ->
                    candidate.isNotEmpty() && candidate != messageEditDraftState.originalBody.trim()
                },
            saving = editSaving,
            failure = editFailure ?: messageEditDraftState.error,
            committed = editCommitted,
            loading = messageEditDraftState.isLoading,
            sourceMissing = messageEditDraftState.sourceMissing,
            recoverableDraft = messageEditDraftState.recoverableContent != null,
            onRetainDraft = retainEditor,
            onRestoreDraft = messageEditDraftController::restoreRecoveredDraft,
            canRetryLoad = messageEditDraftState.target == null && !messageEditDraftState.sourceMissing &&
                (messageEditDraftState.error != null),
            onRetryLoad = {
                val scope = messageEditDraftState.scope
                messageEditDraftController.open(
                    sessionId = scope?.sessionId ?: sessionId,
                    branchId = scope?.branchId ?: state.currentBranchId,
                    messageId = scope?.messageId ?: messageBeingEdited.id,
                )
            },
            onRetryCleanup = {
                scope.launch {
                    val cleared = messageEditDraftController.markSaved(messageEditDraftState.revision)
                    if (cleared) {
                        editCommitted = false
                        editFailure = null
                        closeEditor()
                    } else {
                        editFailure = "消息已编辑，但草稿清理失败；请重试。"
                    }
                }
            },
            onCopyDraft = {
                clipboardManager.setText(AnnotatedString(messageEditDraftState.content))
                scope.launch { snackbarHostState.showSnackbar("草稿已复制") }
            },
            onSave = {
                if (!editSaving && !editCommitted && editScopeMatchesVisibleTarget) {
                    editSaving = true
                    editFailure = null
                    scope.launch {
                        if (!messageEditDraftController.flush()) {
                            editSaving = false
                            editFailure = messageEditDraftController.state.value.error
                                ?: "编辑草稿尚未写入磁盘，请重试"
                            return@launch
                        }
                        val expectedRevision = messageEditDraftController.state.value.revision
                        val content = messageEditDraftController.state.value.content
                        viewModel.editMessage(messageBeingEdited.id, content, onFailure = { message, committed ->
                            if (editingMessage?.id == messageBeingEdited.id) {
                                editSaving = false
                                editFailure = message
                                editCommitted = committed
                            }
                        }) {
                            if (editingMessage?.id == messageBeingEdited.id) {
                                scope.launch {
                                    val cleared = messageEditDraftController.markSaved(expectedRevision)
                                    editSaving = false
                                    if (cleared) {
                                        closeEditor()
                                    } else {
                                        editCommitted = true
                                        editFailure = "消息已编辑，但草稿清理失败；当前编辑已锁定。"
                                    }
                                }
                            }
                        }
                    }
                }
            },
            onDismiss = discardEditor,
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

    LaunchedEffect(showBranchOverview, state.isReady) {
        if (showBranchOverview && state.isReady) {
            viewModel.loadBranchSourcePreviews()
            try { kotlinx.coroutines.awaitCancellation() }
            finally { viewModel.cancelBranchSourcePreviews() }
        }
    }
    BranchOverviewBottomSheet(
        visible = showBranchOverview && state.isReady,
        branches = state.branches,
        sourcePreviews = state.branchSourcePreviews,
        sourcePreviewsLoading = state.branchSourcePreviewsLoading,
        sourcePreviewsError = state.branchSourcePreviewsError,
        onRetryPreviews = viewModel::loadBranchSourcePreviews,
        currentBranchId = state.currentBranchId,
        onSelectBranch = { branchId, onResult ->
            if (isImportingChat) {
                onResult("请先完成或停止聊天记录导入")
            } else if (state.isGenerating) {
                onResult("当前正在生成，请先停止或等待完成后再切换故事线")
            } else viewModel.switchBranch(branchId, onResult)
        },
        onDismiss = { showBranchOverview = false },
    )

    state.characterStatePanel?.let { panel ->
        CharacterStateSheet(
            panel = panel,
            characterName = state.characterNames[panel.characterId].orEmpty(),
            onDismiss = viewModel::closeCharacterState,
            onRetry = { viewModel.openCharacterState(panel.characterId, panel.branchId) },
            onClear = viewModel::clearCharacterState,
        )
    }


}

@Composable
private fun ReplyRecoveryCard(
    notice: ReplyRecoveryNotice,
    busy: Boolean,
    error: String?,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    var expanded by remember(notice.token, notice.issue, notice.text) { mutableStateOf(false) }
    val text = notice.text?.trim().orEmpty()
    val canCopy = text.isNotEmpty()
    val canKeep = notice.issue.isNullOrBlank() && notice.token != null && canCopy && !busy
    val hasMoreText = text.length > 260 || text.take(260).lineSequence().count() > 4

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "上次生成中断",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = notice.speakerLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            notice.branchLabel?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "故事线：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                )
            }
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = if (expanded) {
                        Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                    maxLines = if (expanded) 64 else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasMoreText) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        enabled = !busy,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(if (expanded) "收起" else "展开阅读")
                    }
                }
            } else {
                Text(
                    text = notice.issue ?: "没有可显示的中断内容",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.86f),
                )
            }
            notice.issue?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "暂时无法直接保留：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canCopy) {
                    TextButton(
                        onClick = { clipboardManager.setText(AnnotatedString(text)) },
                    ) { Text("复制") }
                }
                TextButton(onClick = onDiscard, enabled = !busy) {
                    Text(if (notice.issue.isNullOrBlank()) "丢弃" else "丢弃记录")
                }
                if (notice.issue.isNullOrBlank()) {
                    TextButton(onClick = onKeep, enabled = canKeep) { Text("保留为消息") }
                }
            }
        }
    }
}

/** 历史占位、仅标签无正文等：不在列表中占位，避免「仅含自动配图」单独一条气泡。 */
internal fun shouldShowCharacterBubbleLine(m: MessageEntity, attachments: List<MessageAttachmentEntity>): Boolean {
    if (m.speakerType != "character") return true
    if (attachments.isNotEmpty()) return true
    val c = m.content.trim()
    if (c.isEmpty()) return false
    if (c == "（本条仅含自动配图/语音指令）") return false
    // Most history is ordinary prose. Avoid normalizing and parsing every loaded line on the UI thread.
    if ('<' !in c) return true
    val isStructured = StructuredParser.isStructured(m.content)
    if (!isStructured) return ChatMessageTextFormat.forBubbleDisplay(m.content).isNotBlank()
    val reply = StructuredParser.parse(m.content)
    if (reply.narrations.isNotEmpty() || reply.thoughts.isNotEmpty() ||
        reply.speeches.isNotEmpty() || reply.choices.isNotEmpty()) return true
    return ChatMessageTextFormat.forBubbleDisplay(StructuredParser.stripTags(m.content)).isNotBlank()
}

/** Suggestions are optional transcript content; typing leaves the reading viewport available. */
internal fun shouldShowRoundChoices(
    isImeOpen: Boolean,
    choices: List<String>,
    isGenerating: Boolean,
): Boolean = choices.isNotEmpty() && !isGenerating && !isImeOpen

internal data class ChatMediaRequest(
    val sessionId: Long,
    val branchId: String,
)

internal data class ChatInputMediaLaunchers(
    val speechListening: Boolean,
    val galleryPermissionPending: Boolean,
    val launchSpeech: () -> Unit,
    val launchImage: () -> Unit,
    val requestGalleryPermission: (Long) -> Unit,
)

@Composable
internal fun rememberChatInputMediaLaunchers(
    sessionId: Long,
    currentBranchId: () -> String,
    isCurrentBranch: (String) -> Boolean,
    hasMicrophonePermission: (() -> Boolean)? = null,
    isSpeechRecognitionAvailable: (() -> Boolean)? = null,
    onLaunchFailure: (String) -> Unit = {},
    onSpeechText: (String, String) -> Unit,
    onSpeechUnavailable: () -> Unit,
    onMicPermissionDenied: () -> Unit,
    onImagePicked: (android.net.Uri, ChatMediaRequest) -> Unit,
    onGalleryPermissionResult: (Long, String, Boolean) -> Unit,
): ChatInputMediaLaunchers {
    val context = LocalContext.current
    val microphonePermissionGranted = hasMicrophonePermission ?: {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val speechAvailable = isSpeechRecognitionAvailable ?: { NativeSpeechRecognizer.isSpeechRecognitionResolvable(context) }
    var speechSessionId by rememberSaveable(sessionId) { mutableStateOf(sessionId) }
    var imageSessionId by rememberSaveable(sessionId) { mutableStateOf(sessionId) }
    var gallerySessionId by rememberSaveable(sessionId) { mutableStateOf(sessionId) }
    var speechBranchId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var imageBranchId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var galleryMessageId by rememberSaveable(sessionId) { mutableStateOf<Long?>(null) }
    var galleryBranchId by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var speechListening by rememberSaveable(sessionId) { mutableStateOf(false) }

    lateinit var speechLauncher: ActivityResultLauncher<Intent>
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val branchId = speechBranchId
        if (speechSessionId != sessionId || branchId == null || !isCurrentBranch(branchId)) {
            speechBranchId = null
            speechListening = false
        } else if (granted) {
            val started = tryLaunchSpeechRecognition(context, speechLauncher, onSpeechUnavailable, speechAvailable)
            if (started) speechListening = true else speechBranchId = null
        } else {
            speechBranchId = null
            speechListening = false
            onMicPermissionDenied()
        }
    }
    speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val branchId = speechBranchId
        val accepted = speechSessionId == sessionId && branchId != null && isCurrentBranch(branchId)
        speechBranchId = null
        speechListening = false
        if (accepted && result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { onSpeechText(it, branchId!!) }
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val branchId = imageBranchId
        val accepted = imageSessionId == sessionId && branchId != null && isCurrentBranch(branchId)
        imageBranchId = null
        if (accepted && uri != null) onImagePicked(uri, ChatMediaRequest(sessionId, branchId!!))
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val messageId = galleryMessageId
        val branchId = galleryBranchId
        val accepted = gallerySessionId == sessionId && messageId != null && branchId != null && isCurrentBranch(branchId)
        galleryMessageId = null
        galleryBranchId = null
        if (accepted) onGalleryPermissionResult(messageId!!, branchId!!, granted)
    }

    return ChatInputMediaLaunchers(
        speechListening = speechListening,
        galleryPermissionPending = galleryMessageId != null,
        launchSpeech = {
            if (speechBranchId == null) {
                val branchId = currentBranchId()
                speechSessionId = sessionId
                speechBranchId = branchId
                if (!speechAvailable()) {
                    speechBranchId = null
                    onSpeechUnavailable()
                } else if (microphonePermissionGranted()) {
                    val started = tryLaunchSpeechRecognition(context, speechLauncher, onSpeechUnavailable, speechAvailable)
                    if (started) speechListening = true else speechBranchId = null
                } else {
                    try {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } catch (_: Exception) {
                        speechBranchId = null
                        onLaunchFailure("无法请求麦克风权限，请重试")
                    }
                }
            }
        },
        launchImage = {
            if (imageBranchId == null) {
                imageSessionId = sessionId
                imageBranchId = currentBranchId()
                try {
                    imageLauncher.launch("image/*")
                } catch (_: Exception) {
                    imageBranchId = null
                    onLaunchFailure("无法打开图片选择器，请重试")
                }
            }
        },
        requestGalleryPermission = { messageId ->
            if (galleryMessageId == null) {
                gallerySessionId = sessionId
                galleryMessageId = messageId
                galleryBranchId = currentBranchId()
                try {
                    galleryLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } catch (_: Exception) {
                    galleryMessageId = null
                    galleryBranchId = null
                    onLaunchFailure("无法请求存储权限，请重试")
                }
            }
        },
    )
}

private fun tryLaunchSpeechRecognition(
    context: android.content.Context,
    speechLauncher: ActivityResultLauncher<Intent>,
    onUnavailable: () -> Unit,
    isAvailable: () -> Boolean = { NativeSpeechRecognizer.isSpeechRecognitionResolvable(context) },
): Boolean {
    if (!isAvailable()) {
        onUnavailable()
        return false
    }
    return try {
        speechLauncher.launch(NativeSpeechRecognizer.createIntent())
        true
    } catch (_: Exception) {
        onUnavailable()
        false
    }
}
