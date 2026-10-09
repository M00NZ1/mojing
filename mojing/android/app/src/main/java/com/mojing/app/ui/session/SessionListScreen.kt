package com.mojing.app.ui.session

import com.mojing.app.ui.common.MoJingTopAppBar as TopAppBar
import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.ui.draw.clip
import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton
import com.mojing.app.ui.common.MoJingTonalButton as FilledTonalButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.navigation.NavHostController
import androidx.compose.ui.res.stringResource
import com.mojing.app.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.SessionWithListMeta
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.ui.chat.ChatMessageTextFormat
import com.mojing.app.ui.navigation.MainAppBottomNavigation
import com.mojing.app.ui.common.MoJingToggleRow
import com.mojing.app.ui.common.SwipeRevealListRow
import com.mojing.app.ui.common.isImeKeyboardOpen
import com.mojing.app.ui.util.UserFacingStrings
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.mojing.app.data.SessionSetupDraft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    navController: NavHostController,
    newSessionRequestId: Long?,
    onNewSessionRequestConsumed: (Long) -> Unit,
    newSessionTemplateId: Long?,
    onNewSessionTemplateConsumed: () -> Unit,
    onSessionClick: (Long) -> Unit,
    onCharactersClick: () -> Unit,
    onEncyclopediaClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onGenerationTasksClick: () -> Unit,
    viewModel: SessionViewModel = hiltViewModel()
) {
    val sessionLibraryState by viewModel.sessionLibraryState.collectAsStateWithLifecycle()
    val loadedLibrary = sessionLibraryState as? SessionLibraryUiState.Loaded
    val sessions = loadedLibrary?.sessions.orEmpty()
    val lastChatBranches by viewModel.lastChatBranches.collectAsStateWithLifecycle()
    val branchCardPreviews by viewModel.branchCardPreviews.collectAsStateWithLifecycle()
    val branchPreviewEpoch by viewModel.branchPreviewEpoch.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val pinnedOnly by viewModel.pinnedOnly.collectAsStateWithLifecycle()
    val recentFirst by viewModel.recentFirst.collectAsStateWithLifecycle()
    val pinningSessionIds by viewModel.pinningSessionIds.collectAsStateWithLifecycle()
    val pinFailure by viewModel.pinFailure.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(pinFailure) {
        val failure = pinFailure ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(failure.message, actionLabel = "重试", withDismissAction = true)
        viewModel.clearPinFailure(failure)
        if (result == SnackbarResult.ActionPerformed) viewModel.setSessionPinned(failure.sessionId, failure.pinned)
    }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val generatingSessions by com.mojing.app.ui.chat.RetainedChatSessions.running.collectAsStateWithLifecycle()
    val backgroundFailures by com.mojing.app.ui.chat.RetainedChatSessions.stores.failures.collectAsStateWithLifecycle()
    val pendingGenTasks by viewModel.pendingGenerationTaskCount.collectAsStateWithLifecycle()
    val hasPublicLlmKey by viewModel.hasPublicLlmKey.collectAsStateWithLifecycle()
    val isCreatingSession by viewModel.isCreatingSession.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.syncPublicLlmKeyFromStorage()
                viewModel.refreshBranchCardPreviews()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.syncPublicLlmKeyFromStorage()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var deleteTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteTargetTitle by rememberSaveable { mutableStateOf("") }
    val deletionState by viewModel.deletionState.collectAsStateWithLifecycle()
    var renameTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renameTargetTitle by rememberSaveable { mutableStateOf("") }
    val renameState by viewModel.renameState.collectAsStateWithLifecycle()
    LaunchedEffect(deletionState.sessionId, deletionState.completed) {
        if (deletionState.completed) {
            deleteTargetId = null
            Toast.makeText(context, "对话已删除", Toast.LENGTH_SHORT).show()
            viewModel.clearDeletionResult()
        }
    }
    LaunchedEffect(renameState.sessionId, renameState.completed) {
        if (renameState.completed && renameTargetId == renameState.sessionId) {
            renameTargetId = null
            viewModel.clearRenameResult()
        }
    }
    val setupScope = rememberCoroutineScope()
    var setupLoadRetry by remember { mutableIntStateOf(0) }
    var setupDraftLoaded by remember { mutableStateOf(false) }
    var setupSaveRunning by remember { mutableStateOf(false) }
    var setupDraftError by remember { mutableStateOf<String?>(null) }
    var setupWasPersisted by rememberSaveable { mutableStateOf(false) }
    var alreadyCreatedSetupId by remember { mutableStateOf<Long?>(null) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var creationRequestId by rememberSaveable { mutableStateOf("") }
    var newSessionFormEdited by rememberSaveable { mutableStateOf(false) }
    var suspendedNewSessionDraft by rememberSaveable { mutableStateOf(false) }
    var confirmDiscardNewSession by rememberSaveable { mutableStateOf(false) }
    var replacementTemplateId by rememberSaveable { mutableStateOf<Long?>(null) }
    var maxCharacterIdBeforeCreation by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectNewCharacterOnNextLoad by rememberSaveable { mutableStateOf(false) }
    var pendingTemplateId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dialogLoadRequestVersion by rememberSaveable { mutableIntStateOf(0) }
    var resetDialogFormOnNextLoad by rememberSaveable { mutableStateOf(true) }
    var isLoadingDialogData by remember { mutableStateOf(false) }
    var dialogLoadError by remember { mutableStateOf<String?>(null) }
    var dialogDataReady by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<WorldTemplateEntity?>(null) }
    var selectedEncyclopedia by remember { mutableStateOf<EncyclopediaEntity?>(null) }
    var compatibleCharacterCount by remember { mutableIntStateOf(0) }
    var characterSummaryReady by remember { mutableStateOf(false) }
    var characterSummaryLoading by remember { mutableStateOf(false) }
    var characterSummaryError by remember { mutableStateOf<String?>(null) }
    var characterSummaryRetry by remember { mutableIntStateOf(0) }
    var latestCharacterId by remember { mutableLongStateOf(0L) }
    var selectedCharacterIds by rememberSaveable(stateSaver = Saver<Set<Long>, ArrayList<Long>>(
        save = { ArrayList(it) }, restore = { it.toSet() },
    )) { mutableStateOf<Set<Long>>(emptySet()) }
    var initializeCharacterSelection by rememberSaveable { mutableStateOf(true) }
    var newSessionTitle by rememberSaveable { mutableStateOf("") }
    var selectedTemplateId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedEncId by rememberSaveable { mutableStateOf<Long?>(null) }
    var worldSelectionInitialized by rememberSaveable { mutableStateOf(false) }
    var narratorOn by rememberSaveable { mutableStateOf(false) }
    var narratorName by rememberSaveable { mutableStateOf("旁白") }
    var choiceOn by rememberSaveable { mutableStateOf(true) }
    var maxChoicesStr by rememberSaveable { mutableStateOf("3") }
    var antiCheatOn by rememberSaveable { mutableStateOf(true) }
    /** 新建对话：展示用上下文 token 上限（仅统计条），默认 100 万 */
    var displayContextLimitStr by rememberSaveable { mutableStateOf("1000000") }
    var showConversationOptions by rememberSaveable { mutableStateOf(false) }
    var showQuickStartReplay by remember { mutableStateOf(false) }
    val guideDismissed by viewModel.quickStartGuideDismissed.collectAsStateWithLifecycle()
    val isSessionHomeContentReady = sessionLibraryState is SessionLibraryUiState.Loaded &&
        (sessions.isNotEmpty() || guideDismissed != null)

    fun setupSnapshot() = SessionSetupDraft(
        creationRequestId, newSessionTitle, selectedTemplateId, selectedEncId, selectedCharacterIds.toList(),
        narratorOn, narratorName, choiceOn, maxChoicesStr, antiCheatOn, displayContextLimitStr,
        maxCharacterIdBeforeCreation, selectNewCharacterOnNextLoad, newSessionFormEdited, initializeCharacterSelection,
    )
    LaunchedEffect(setupLoadRetry) {
        setupDraftError = null
        try {
            val draft = viewModel.loadSessionSetupDraft()
            if (draft != null) {
                alreadyCreatedSetupId = viewModel.createdSessionForSetup(draft.requestId)
                creationRequestId = draft.requestId
                newSessionTitle = draft.title
                selectedTemplateId = draft.templateId
                selectedEncId = draft.encyclopediaId
                selectedCharacterIds = draft.characterIds.toSet()
                narratorOn = draft.narratorEnabled
                narratorName = draft.narratorName
                choiceOn = draft.choiceEnabled
                maxChoicesStr = draft.maxChoices
                antiCheatOn = draft.antiCheatEnabled
                displayContextLimitStr = draft.displayLimit
                maxCharacterIdBeforeCreation = draft.maxCharacterIdBeforeCreation
                selectNewCharacterOnNextLoad = draft.selectNewCharacter
                newSessionFormEdited = draft.formEdited
                initializeCharacterSelection = draft.initializeCharacterSelection
                worldSelectionInitialized = true
                resetDialogFormOnNextLoad = false
                suspendedNewSessionDraft = true
                setupWasPersisted = true
            }
            setupDraftLoaded = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { setupDraftError = "保留的对话设定读取失败，原设定仍保留，请重试" }
    }

    fun openNewSessionDialog(templateId: Long? = null) {
        if (!setupDraftLoaded || setupSaveRunning) {
            Toast.makeText(context, setupDraftError ?: "正在读取保留设定，请稍候", Toast.LENGTH_LONG).show()
            return
        }
        alreadyCreatedSetupId?.let { id ->
            viewModel.clearSessionSetupDraft(creationRequestId) {
                Toast.makeText(context, "对话已创建，暂存设定清理失败，可重新进入重试", Toast.LENGTH_LONG).show()
            }
            suspendedNewSessionDraft = false
            onSessionClick(id)
            return
        }
        if (suspendedNewSessionDraft && templateId == null) {
            suspendedNewSessionDraft = false
            dialogDataReady = false
            dialogLoadRequestVersion += 1
            showCreateDialog = true
            return
        }
        pendingTemplateId = templateId
        creationRequestId = UUID.randomUUID().toString()
        newSessionFormEdited = false
        suspendedNewSessionDraft = false
        confirmDiscardNewSession = false
        replacementTemplateId = null
        maxCharacterIdBeforeCreation = null
        selectNewCharacterOnNextLoad = false
        resetDialogFormOnNextLoad = true
        worldSelectionInitialized = false
        dialogDataReady = false
        showConversationOptions = false
        dialogLoadRequestVersion += 1
        showCreateDialog = true
    }

    fun closeNewSessionDialog() {
        if (setupWasPersisted) viewModel.clearSessionSetupDraft(creationRequestId) {
            Toast.makeText(context, "暂存设定清理失败，请重新进入故事库重试", Toast.LENGTH_LONG).show()
        }
        setupWasPersisted = false
        alreadyCreatedSetupId = null
        pendingTemplateId = null
        creationRequestId = ""
        newSessionFormEdited = false
        suspendedNewSessionDraft = false
        confirmDiscardNewSession = false
        replacementTemplateId = null
        maxCharacterIdBeforeCreation = null
        selectNewCharacterOnNextLoad = false
        resetDialogFormOnNextLoad = true
        isLoadingDialogData = false
        dialogLoadError = null
        dialogDataReady = false
        showConversationOptions = false
        focusManager.clearFocus()
        showCreateDialog = false
    }

    fun requestCloseNewSessionDialog() {
        when {
            setupSaveRunning -> Toast.makeText(context, "正在暂存设定，请稍候", Toast.LENGTH_SHORT).show()
            isCreatingSession -> Toast.makeText(context, "正在创建对话，请稍候", Toast.LENGTH_SHORT).show()
            newSessionFormEdited -> confirmDiscardNewSession = true
            else -> closeNewSessionDialog()
        }
    }

    fun suspendNewSessionForCharacters() {
        if (isCreatingSession || setupSaveRunning || !setupDraftLoaded) return
        val checkpoint = setupSnapshot().copy(maxCharacterIdBeforeCreation = latestCharacterId, selectNewCharacter = true)
        setupSaveRunning = true
        setupScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            try {
                viewModel.saveSessionSetupDraft(checkpoint)
                setupWasPersisted = true
                maxCharacterIdBeforeCreation = checkpoint.maxCharacterIdBeforeCreation
                selectNewCharacterOnNextLoad = true
                suspendedNewSessionDraft = true
                dialogDataReady = false
                focusManager.clearFocus()
                showCreateDialog = false
                onCharactersClick()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, "设定暂存失败，请重试", Toast.LENGTH_LONG).show() }
            finally { setupSaveRunning = false }
        }
    }

    LaunchedEffect(newSessionRequestId, setupDraftLoaded) {
        if (!setupDraftLoaded) return@LaunchedEffect
        newSessionRequestId?.let { requestId ->
            openNewSessionDialog()
            onNewSessionRequestConsumed(requestId)
        }
    }

    LaunchedEffect(newSessionTemplateId, setupDraftLoaded) {
        if (!setupDraftLoaded) return@LaunchedEffect
        newSessionTemplateId?.let { templateId ->
            if (suspendedNewSessionDraft) replacementTemplateId = templateId
            else openNewSessionDialog(templateId)
            onNewSessionTemplateConsumed()
        }
    }

    LaunchedEffect(showCreateDialog, dialogLoadRequestVersion, setupDraftLoaded) {
        if (showCreateDialog && setupDraftLoaded) {
            val requestedTemplateId = pendingTemplateId
            val d = viewModel.newSessionDialogDefaults()
            if (resetDialogFormOnNextLoad) {
                newSessionTitle = ""
                selectedTemplateId = null
                selectedEncId = null
                selectedTemplate = null
                selectedEncyclopedia = null
                compatibleCharacterCount = 0
                characterSummaryReady = false
                selectedCharacterIds = emptySet()
                initializeCharacterSelection = true
                narratorOn = d.narratorEnabled
                narratorName = "旁白"
                choiceOn = d.choiceEnabled
                maxChoicesStr = "3"
                antiCheatOn = d.antiCheatEnabled
                displayContextLimitStr = "1000000"
            }
            resetDialogFormOnNextLoad = false
            dialogDataReady = false
            characterSummaryReady = false
            isLoadingDialogData = true
            dialogLoadError = null
            try {
                val data = viewModel.loadNewSessionDialogData(
                    initializeWorld = !worldSelectionInitialized,
                    selectedTemplateId = selectedTemplateId,
                    selectedEncyclopediaId = selectedEncId,
                    requestedTemplateId = requestedTemplateId,
                    defaultTemplateId = d.defaultWorldTemplateId,
                )
                selectedTemplate = data.template
                selectedEncyclopedia = data.encyclopedia
                selectedTemplateId = data.templateId
                selectedEncId = data.encyclopediaId
                if (!worldSelectionInitialized) {
                    if (requestedTemplateId != null && !data.initialTemplateFound) {
                        Toast.makeText(context, "世界模板已不存在，已打开普通新对话", Toast.LENGTH_SHORT).show()
                    } else if (
                        requestedTemplateId == null &&
                        d.defaultWorldTemplateId.isNotBlank() &&
                        d.defaultWorldTemplateId != "custom" &&
                        !data.initialTemplateFound
                    ) {
                        Toast.makeText(context, "默认世界模板已不存在，本次不使用模板", Toast.LENGTH_SHORT).show()
                    }
                    worldSelectionInitialized = true
                }
                pendingTemplateId = null
                dialogDataReady = true
                isLoadingDialogData = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                isLoadingDialogData = false
                dialogLoadError = "新建对话资料读取失败，请重试"
            }
        }
    }

    LaunchedEffect(showCreateDialog, dialogDataReady, selectedEncId, characterSummaryRetry) {
        if (!showCreateDialog || !dialogDataReady) return@LaunchedEffect
        characterSummaryReady = false
        characterSummaryLoading = true
        characterSummaryError = null
        try {
            val summary = viewModel.loadNewSessionCharacterSummary(
                selectedEncId, selectedCharacterIds,
                maxCharacterIdBeforeCreation.takeIf { selectNewCharacterOnNextLoad },
            )
            compatibleCharacterCount = summary.count
            latestCharacterId = summary.maxId
            selectedCharacterIds = if (initializeCharacterSelection) {
                initializeCharacterSelection = false
                openingCharacterSelection(summary.onlyId?.let { setOf(it) } ?: emptySet(), null)
            } else summary.existingSelectedIds
            if (selectNewCharacterOnNextLoad) {
                if (selectedCharacterIds.isEmpty() && summary.newlyAvailableIds.size == 1) {
                    selectedCharacterIds = summary.newlyAvailableIds.toSet()
                }
                selectNewCharacterOnNextLoad = false
                maxCharacterIdBeforeCreation = null
            }
            characterSummaryReady = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            characterSummaryError = "角色读取失败，请重试"
        } finally {
            characterSummaryLoading = false
        }
    }

    val retainedSetupSnapshot = if (setupWasPersisted && showCreateDialog && dialogDataReady) setupSnapshot() else null
    LaunchedEffect(retainedSetupSnapshot) {
        if (retainedSetupSnapshot != null && setupWasPersisted && showCreateDialog && !setupSaveRunning && retainedSetupSnapshot == setupSnapshot()) {
            try { viewModel.saveSessionSetupDraft(retainedSetupSnapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, "设定暂存失败，请重试", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                        expandedHeight = 52.dp,
                title = {
                    Text("故事库", style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    BadgedBox(
                        badge = {
                            if (pendingGenTasks > 0) {
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text(
                                        if (pendingGenTasks > 99) "99+" else pendingGenTasks.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        },
                    ) {
                        IconButton(onClick = onGenerationTasksClick) {
                            Icon(Icons.Outlined.AutoStories, "生成记录")
                        }
                    }
                    if (isSessionHomeContentReady && sessions.isNotEmpty() && !isImeKeyboardOpen()) {
                        Button(
                            onClick = { openNewSessionDialog() },
                            modifier = Modifier.padding(end = 12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("新建对话", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                )
            )
        },
        bottomBar = {
            MainAppBottomNavigation(navController)
        },

    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            if (isSessionHomeContentReady || searchQuery.isNotBlank() || pinnedOnly) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearch(it) },
                    modifier = Modifier
                        .weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = MaterialTheme.shapes.medium,
                    placeholder = { Text("搜索故事标题", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Outlined.Search, "搜索对话标题") },
                    trailingIcon = if (searchQuery.isNotBlank()) {
                        {
                            IconButton(onClick = { viewModel.updateSearch("") }) {
                                Icon(Icons.Outlined.Clear, contentDescription = "清除标题搜索")
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
                TextButton(onClick = onGenerationTasksClick, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("生成记录", style = MaterialTheme.typography.labelMedium)
                }
                }
            }
            if (isSessionHomeContentReady && !hasPublicLlmKey) {
                StoryLibraryModelHint(
                    onOpenSettings = onSettingsClick,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            setupDraftError?.let { message ->
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { setupLoadRetry += 1 }) { Text("重试读取") }
                    }
                }
            }
            if (suspendedNewSessionDraft) {
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("新对话设定已保留", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(if (alreadyCreatedSetupId != null) "对话已创建，继续进入同一个故事。" else "角色准备好后，继续完成这次开局。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                if (newSessionFormEdited) confirmDiscardNewSession = true
                                else closeNewSessionDialog()
                            }) { Text("放弃设定") }
                            TextButton(onClick = { openNewSessionDialog() }) { Text(if (alreadyCreatedSetupId != null) "打开对话" else "继续设定") }
                        }
                    }
                }
            }
            if (isSessionHomeContentReady || searchQuery.isNotBlank()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.mojing.app.ui.common.MoJingFilterChip(!pinnedOnly, { viewModel.setLibraryOptions(pinnedOnly = false) }, { Text("全部") })
                    com.mojing.app.ui.common.MoJingFilterChip(pinnedOnly, { viewModel.setLibraryOptions(pinnedOnly = true) }, { Text("已置顶") })
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { sortMenuOpen = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(if (recentFirst) "最近更新" else "置顶优先", style = MaterialTheme.typography.labelMedium)
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("最近更新") }, onClick = { sortMenuOpen = false; viewModel.setLibraryOptions(recentFirst = true) })
                            DropdownMenuItem(text = { Text("置顶优先") }, onClick = { sortMenuOpen = false; viewModel.setLibraryOptions(recentFirst = false) })
                        }
                    }
                }
            }
            loadedLibrary?.takeIf { it.refreshError || it.refreshing }?.let { library ->
                val readFailed = library.refreshError
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = if (readFailed) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!readFailed) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (readFailed) "故事列表刷新失败" else "正在刷新故事…",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            if (readFailed && sessions.isNotEmpty()) Text(
                                "下方保留上次读取的故事。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (readFailed) TextButton(onClick = viewModel::retrySessionLibrary) { Text("重试") }
                    }
                }
            }

            if (
                sessionLibraryState is SessionLibraryUiState.Loading ||
                loadedLibrary != null && sessions.isEmpty() && guideDismissed == null && !loadedLibrary.refreshError
            ) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        Text(
                            "正在读取故事…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (sessionLibraryState is SessionLibraryUiState.Failed) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("无法读取对话", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "请重试。已有对话不会被改动。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = viewModel::retrySessionLibrary) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("重试")
                    }
                }
            } else if (sessions.isEmpty() && (searchQuery.isNotBlank() || pinnedOnly)) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(when {
                        pinnedOnly && searchQuery.isBlank() -> "还没有置顶故事"
                        pinnedOnly -> "没有符合搜索的置顶故事"
                        else -> UserFacingStrings.sessionSearchNoMatch()
                    }, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (sessions.isEmpty() && loadedLibrary?.pageIndex == 0) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (loadedLibrary?.refreshError == true) Text(
                        "上次读取时没有故事，刷新失败后无法确认最新列表。请重试。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (guideDismissed == false) {
                        com.mojing.app.ui.common.StoryFeatureCard(
                            eyebrow = "以墨为界，入境如梦。",
                            title = "写下你的下一幕",
                            description = "遇见角色，走进属于你的故事。",
                            action = "开始新对话",
                            onClick = { openNewSessionDialog() },
                        )
                        com.mojing.app.ui.common.WorkspaceSectionHeading(
                            "准备开场", "先认识角色，也可以从一片空白开始。",
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        com.mojing.app.ui.common.WorkspaceResourceCard(
                            "角色", "创建或导入人物，自由开启对话", "01", Icons.Outlined.Person, onCharactersClick)
                        com.mojing.app.ui.common.WorkspaceResourceCard(
                            "世界百科", "补充背景、地点与人物关系", "02", Icons.Outlined.Public, onEncyclopediaClick)
                        TextButton(onClick = { viewModel.dismissQuickStartGuide() }) {
                            Text("不再显示此卡片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (guideDismissed == true) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("开始你的第一段故事", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "选择角色与世界后进入对话，也可以先建立一个空白故事。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { openNewSessionDialog() }) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("新建对话")
                            }
                        }
                        OutlinedButton(onClick = { showQuickStartReplay = true }) {
                            Text("查看快速上手")
                        }
                    }
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(loadedLibrary?.pageIndex, loadedLibrary?.query) { listState.scrollToItem(0) }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(sessions, key = { _, r -> r.session.id }) { _, row ->
                        val rememberedBranchId = lastChatBranches?.get(row.session.id) ?: if (lastChatBranches == null) null else "main"
                        val branchPreview = branchCardPreviews[row.session.id]?.takeIf {
                            it.branchId == rememberedBranchId && it.sessionUpdatedAt == row.session.updatedAt
                        }
                        val isGenerating = row.session.id in generatingSessions
                        LaunchedEffect(row.session.id, rememberedBranchId, row.session.updatedAt, branchPreviewEpoch, isGenerating) {
                            if (isGenerating) viewModel.clearBranchCardPreview(row.session.id)
                            else if (rememberedBranchId != null && rememberedBranchId != "main") {
                                viewModel.loadBranchCardPreview(row.session.id, rememberedBranchId, row.session.updatedAt)
                            }
                        }
                        val openRename = {
                            viewModel.clearRenameResult()
                            renameTargetId = row.session.id
                            renameTargetTitle = row.session.title
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            SwipeRevealListRow(
                                swipeEnabled = true,
                                isPinned = row.session.pinnedAt > 0,
                                pinEnabled = row.session.id !in pinningSessionIds,
                                onPinToggle = { viewModel.setSessionPinned(row.session.id, row.session.pinnedAt == 0L) },
                                onDelete = { viewModel.clearDeletionResult(); deleteTargetId = row.session.id; deleteTargetTitle = row.session.title },
                                onClick = { onSessionClick(row.session.id) },
                                onRename = openRename,
                                showMenuButton = true,
                            ) {
                                SessionListRowInner(row = row, isGenerating = isGenerating,
                                    rememberedBranchId = rememberedBranchId, branchPreview = branchPreview,
                                    onPreviewRetry = {
                                        if (rememberedBranchId != null) viewModel.loadBranchCardPreview(
                                            row.session.id, rememberedBranchId, row.session.updatedAt, retry = true,
                                        )
                                    },
                                    backgroundFailure = backgroundFailures[row.session.id]?.message,
                                    onStop = { com.mojing.app.ui.chat.RetainedChatSessions.stores.stop(row.session.id) },
                                    onRename = openRename)
                            }
                        }
                    }
                    item(key = "story-library-pagination") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = viewModel::previousSessionLibraryPage,
                                enabled = loadedLibrary != null && loadedLibrary.pageIndex > 0 && !loadedLibrary.refreshing,
                            ) { Text("上一页") }
                            Text("第 ${(loadedLibrary?.pageIndex ?: 0) + 1} 页", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(
                                onClick = viewModel::nextSessionLibraryPage,
                                enabled = loadedLibrary?.hasMore == true && !loadedLibrary.refreshing,
                            ) { Text("下一页") }
                        }
                    }
                }
            }
        }
    }

    deleteTargetId?.let { id ->
        SessionDeleteDialog(
            title = deleteTargetTitle,
            busy = deletionState.sessionId == id && deletionState.running,
            error = deletionState.error.takeIf { deletionState.sessionId == id },
            onDelete = { viewModel.deleteSession(id) },
            onDismiss = { deleteTargetId = null; viewModel.clearDeletionResult() },
        )
    }
    renameTargetId?.let { id ->
        SessionRenameSheet(
            initialTitle = renameTargetTitle,
            saving = renameState.sessionId == id && renameState.running,
            error = renameState.error.takeIf { renameState.sessionId == id },
            onEdit = viewModel::clearRenameResult,
            onSave = { title -> viewModel.renameSession(id, title) },
            onDismiss = { renameTargetId = null; viewModel.clearRenameResult() },
        )
    }

    if (showCreateDialog) {
        var worldPickerOpen by remember { mutableStateOf(false) }
        var characterPickerOpen by remember { mutableStateOf(false) }
        val selectedWorldMissing = dialogDataReady && (
            selectedTemplateId != null && selectedTemplate?.id != selectedTemplateId ||
                selectedEncId != null && selectedEncyclopedia?.id != selectedEncId
            )
        val currentCreating by rememberUpdatedState(isCreatingSession || setupSaveRunning)
        val currentFormEdited by rememberUpdatedState(newSessionFormEdited)
        ModalBottomSheet(
        scrimColor = androidx.compose.material3.MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f),
            containerColor = MaterialTheme.colorScheme.surface,
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { next ->
                    if (next == SheetValue.Hidden && currentCreating) false
                    else if (next == SheetValue.Hidden && currentFormEdited) {
                        confirmDiscardNewSession = true
                        false
                    } else true
                }
            ),
            onDismissRequest = { requestCloseNewSessionDialog() },
        ) {
            val newSessionScroll = rememberScrollState()
            val newSessionImeOpen = isImeKeyboardOpen()
            val compactNewSession = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360 ||
                androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.9f).dp - 64.dp)
                    .imePadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("新建对话", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = { requestCloseNewSessionDialog() }, enabled = !setupSaveRunning && !isCreatingSession) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭新建对话")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(newSessionScroll)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                if (isCreatingSession) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "正在创建并保存对话…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (isLoadingDialogData) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "正在读取世界、百科和角色…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                dialogLoadError?.let { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(
                            onClick = {
                                resetDialogFormOnNextLoad = false
                                dialogLoadRequestVersion += 1
                            },
                            enabled = !setupSaveRunning && !isCreatingSession,
                        ) { Text("重试") }
                    }
                }
                OutlinedTextField(
                    value = newSessionTitle,
                    onValueChange = { newSessionTitle = it; newSessionFormEdited = true },
                    label = { Text("标题") },
                    singleLine = true,
                    enabled = !setupSaveRunning && !isCreatingSession,
                    modifier = Modifier.fillMaxWidth(),
                )
                com.mojing.app.ui.common.MoJingOptionRow("选择世界（可选）",
                    selectedEncyclopedia?.name ?: selectedTemplate?.label ?: if (selectedWorldMissing) "原选世界已不存在" else "选择世界",
                    { worldPickerOpen = true }, enabled = !setupSaveRunning && !isCreatingSession && dialogDataReady)
                if (selectedWorldMissing) Text(
                    "原选世界已不存在，请重新选择后创建",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                if (characterSummaryLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在读取可用角色…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                characterSummaryError?.let { message ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { characterSummaryRetry += 1 }) { Text("重试") }
                    }
                }
                if (characterSummaryReady && compatibleCharacterCount == 0) {
                    Text(
                        "当前世界没有可用角色。可以更换世界、去创建角色，或先进入空白对话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { suspendNewSessionForCharacters() },
                        enabled = !setupSaveRunning && !isCreatingSession && !isLoadingDialogData && !setupSaveRunning,
                    ) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("去创建角色")
                    }
                } else if (characterSummaryReady) {
                    com.mojing.app.ui.common.MoJingOptionRow("参与角色（可选）",
                        if (selectedCharacterIds.isEmpty()) "选择角色" else "${selectedCharacterIds.size} 人参与",
                        { characterPickerOpen = true }, enabled = !setupSaveRunning && !isCreatingSession && !isLoadingDialogData)
                }
                HorizontalDivider(Modifier.padding(top = 2.dp))
                TextButton(
                    onClick = { showConversationOptions = !showConversationOptions },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("对话设置", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "旁白、剧情选项与容量提示",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        if (showConversationOptions) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (showConversationOptions) "收起对话设置" else "展开对话设置",
                    )
                }
                if (showConversationOptions) {
                    Surface(Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoJingToggleRow("启用旁白", "回复中加入场景叙述", narratorOn,
                            { narratorOn = it; newSessionFormEdited = true }, enabled = !setupSaveRunning && !isCreatingSession)
                        if (narratorOn) {
                            OutlinedTextField(
                                value = narratorName,
                                onValueChange = { narratorName = it; newSessionFormEdited = true },
                                label = { Text("旁白名称") },
                                singleLine = true,
                                enabled = !setupSaveRunning && !isCreatingSession,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoJingToggleRow("生成剧情选项", "回复后给出可选行动", choiceOn,
                            { choiceOn = it; newSessionFormEdited = true }, enabled = !setupSaveRunning && !isCreatingSession)
                        if (choiceOn) {
                            OutlinedTextField(
                                value = maxChoicesStr,
                                onValueChange = { v ->
                                    if (v.isEmpty()) { maxChoicesStr = ""; newSessionFormEdited = true }
                                    else if (v.length <= 2 && v.all { it.isDigit() }) {
                                        maxChoicesStr = v
                                        newSessionFormEdited = true
                                    }
                                },
                                label = { Text("每轮最多选项") },
                                singleLine = true,
                                enabled = !setupSaveRunning && !isCreatingSession,
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoJingToggleRow("保持世界规则", "提醒角色遵守当前世界的限制与设定",
                            antiCheatOn, { antiCheatOn = it; newSessionFormEdited = true }, enabled = !setupSaveRunning && !isCreatingSession)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        OutlinedTextField(
                            value = displayContextLimitStr,
                            onValueChange = { v ->
                                if (v.length <= 10 && (v.isEmpty() || v.all { it.isDigit() })) {
                                    displayContextLimitStr = v
                                    newSessionFormEdited = true
                                }
                            },
                            label = { Text("聊天容量提示") },
                            supportingText = {
                                Text("只影响聊天页的容量进度显示，不改变模型的实际上限")
                            },
                            suffix = { Text("tokens") },
                            singleLine = true,
                            enabled = !setupSaveRunning && !isCreatingSession,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    }
                }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                Button(
                    onClick = {
                        val rawCap = displayContextLimitStr.trim()
                        val displayCap = if (rawCap.isEmpty()) {
                            1_000_000
                        } else {
                            rawCap.toLongOrNull()?.coerceIn(1_000L, 10_000_000L)?.toInt() ?: 1_000_000
                        }
                        if (selectedCharacterIds.isEmpty()) {
                            Toast.makeText(context, "请至少选择一名参与角色", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        viewModel.createSessionWithOptions(
                            title = newSessionTitle,
                            template = selectedTemplate,
                            encyclopediaId = selectedEncId,
                            narratorEnabled = narratorOn,
                            narratorName = narratorName,
                            choiceEnabled = choiceOn,
                            maxChoices = (maxChoicesStr.toIntOrNull() ?: 3).coerceIn(1, 8),
                            antiCheatEnabled = antiCheatOn,
                            displayContextTokenLimit = displayCap,
                            participantCharacterIds = selectedCharacterIds.toList(),
                            creationRequestId = creationRequestId.ifBlank { UUID.randomUUID().toString().also { creationRequestId = it } },
                            setupDraft = if (setupWasPersisted) setupSnapshot() else null,
                            onCreated = { id ->
                                Toast.makeText(context, UserFacingStrings.sessionCreated(), Toast.LENGTH_SHORT).show()
                                closeNewSessionDialog()
                                onSessionClick(id)
                            },
                            onBlocked = { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            },
                            onCreatedButNotOpened = {
                                closeNewSessionDialog()
                                Toast.makeText(context, "对话已创建，请从故事库打开", Toast.LENGTH_LONG).show()
                            },
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !setupSaveRunning && !isCreatingSession && dialogDataReady && characterSummaryReady && !selectedWorldMissing &&
                        compatibleCharacterCount > 0 && selectedCharacterIds.isNotEmpty(),
                ) { Text(if (isCreatingSession) "创建中…" else if (newSessionImeOpen || compactNewSession) "开始对话" else "创建并开始") }
                OutlinedButton(
                    onClick = {
                        viewModel.createNewSession(
                            creationRequestId = creationRequestId.ifBlank { UUID.randomUUID().toString().also { creationRequestId = it } },
                            setupDraft = if (setupWasPersisted) setupSnapshot() else null,
                            onCreated = { id ->
                                Toast.makeText(context, UserFacingStrings.blankSessionCreated(), Toast.LENGTH_SHORT).show()
                                closeNewSessionDialog()
                                onSessionClick(id)
                            },
                            onFailed = { message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            },
                            onCreatedButNotOpened = {
                                closeNewSessionDialog()
                                Toast.makeText(context, "对话已创建，请从故事库打开", Toast.LENGTH_LONG).show()
                            },
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !setupSaveRunning && !isCreatingSession && !isLoadingDialogData,
                ) { Text("空白对话") }
                }
            }
        }
        if (worldPickerOpen) NewSessionWorldPicker(
            loadPage = viewModel::loadNewSessionWorldPage,
            loadSelection = viewModel::loadNewSessionWorldSelection,
            selectedEncyclopediaId = selectedEncId,
            selectedTemplateId = selectedTemplateId,
            onSelect = { selection ->
                val encyclopediaId = selection.encyclopedia?.id
                if (encyclopediaId != selectedEncId) {
                    characterSummaryReady = false
                    characterSummaryError = null
                }
                selectedEncId = encyclopediaId
                selectedTemplateId = selection.template?.id
                selectedTemplate = selection.template
                selectedEncyclopedia = selection.encyclopedia
                worldSelectionInitialized = true
                newSessionFormEdited = true
                worldPickerOpen = false
            },
            onDismiss = { worldPickerOpen = false },
        )
        if (characterPickerOpen) NewSessionCharacterPicker(
            encyclopediaId = selectedEncId,
            loadPage = viewModel::loadNewSessionCharacterPage,
            selectedIds = selectedCharacterIds,
            onSelectionChange = { selectedCharacterIds = it; newSessionFormEdited = true },
            onDismiss = { characterPickerOpen = false },
        )
    }

    if (confirmDiscardNewSession) AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        onDismissRequest = { confirmDiscardNewSession = false },
        title = { Text("放弃本次对话设定？") },
        text = { Text("尚未创建对话，当前填写的标题和开局设定将被放弃。") },
        confirmButton = {
            TextButton(onClick = { closeNewSessionDialog() }) {
                Text("放弃设定", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDiscardNewSession = false }) { Text("继续编辑") } },
    )

    replacementTemplateId?.let { templateId ->
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { replacementTemplateId = null },
            title = { Text("改用所选世界开始新对话？") },
            text = { Text("当前保留的开局设定将被放弃。也可以继续原设定，稍后在表单里选择世界。") },
            confirmButton = {
                TextButton(onClick = {
                    closeNewSessionDialog()
                    openNewSessionDialog(templateId)
                }) { Text("重新开始", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { replacementTemplateId = null }) { Text("继续原设定") }
            },
        )
    }

    if (showQuickStartReplay) {
        ModalBottomSheet(
        scrimColor = androidx.compose.material3.MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f),onDismissRequest = { showQuickStartReplay = false }) {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 28.dp)) {
                Text("开始创作", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                QuickStartGuideSteps(
                    onCharacters = {
                        showQuickStartReplay = false
                        onCharactersClick()
                    },
                    onCreateSession = {
                        showQuickStartReplay = false
                        openNewSessionDialog()
                    },
                    onEncyclopedia = {
                        showQuickStartReplay = false
                        onEncyclopediaClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickStartGuideSteps(onCharacters: () -> Unit, onCreateSession: () -> Unit, onEncyclopedia: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("遇见角色，写下属于你的下一幕。", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onCreateSession, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("开始新对话")
        }
        com.mojing.app.ui.common.WorkspaceResourceCard(
            "角色", "创建或导入人物，自由开启对话", "01", Icons.Outlined.Person, onCharacters)
        com.mojing.app.ui.common.WorkspaceResourceCard(
            "世界百科", "补充背景、地点与人物关系", "02", Icons.Outlined.Public, onEncyclopedia)
    }
}

@Composable
fun SessionListRowInner(
    row: SessionWithListMeta,
    isGenerating: Boolean = false,
    onStop: (() -> Unit)? = null,
    backgroundFailure: String? = null,
    onRename: (() -> Unit)? = null,
    rememberedBranchId: String? = "main",
    branchPreview: BranchCardPreviewState? = null,
    onPreviewRetry: (() -> Unit)? = null,
) {
    val session = row.session
    val dateTimeFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val showingMain = rememberedBranchId == "main" || branchPreview?.fallsBackToMain == true
    val preview = ChatMessageTextFormat.sessionListPreview(
        rawPrefix = if (showingMain) row.lastMessagePreview.orEmpty() else branchPreview?.contentPrefix.orEmpty(),
        speakerType = if (showingMain) row.lastMessageSpeakerType else branchPreview?.speakerType,
        maxChars = 72,
    )
    val previewLabel = if (showingMain) "主线" else branchPreview?.label?.takeIf(String::isNotBlank) ?: "上次故事线"
    val meta = "主线 ${row.messageCount} 条 · ${row.participantCount} 角色"
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        com.mojing.app.ui.common.MoJingCoverImage(row.coverImagePath, Modifier.size(56.dp, 72.dp), "故事关联封面")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(session.title.ifBlank { "未命名对话" },
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!showingMain && rememberedBranchId != null) {
            Text(previewLabel, style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isGenerating) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.primary)
                Text("后台生成", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.primary)
                if (onStop != null) TextButton(onClick = onStop) { Text("停止") }
            }
        } else if (backgroundFailure != null) {
            Text("生成未完成 · $backgroundFailure", maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = colors.error)
        } else if (rememberedBranchId == null) {
            Text("正在读取续聊位置…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } else if (rememberedBranchId != "main" && branchPreview?.error == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("故事线预览读取失败", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.error)
                if (onPreviewRetry != null) TextButton(onClick = onPreviewRetry) { Text("重试") }
            }
        } else if (rememberedBranchId != "main" && (branchPreview == null || branchPreview.loading)) {
            Text("正在读取上次故事线…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } else {
            Text(preview.ifEmpty { "尚未开始，点开继续。" },
                style = MaterialTheme.typography.bodySmall, maxLines = 2,
                overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (session.pinnedAt > 0) {
                Icon(Icons.Outlined.PushPin, "已置顶", Modifier.size(12.dp), tint = colors.onSurfaceVariant)
            }
            Text("$meta · ${dateTimeFormat.format(Date(session.updatedAt))}",
                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        }
    }
}
