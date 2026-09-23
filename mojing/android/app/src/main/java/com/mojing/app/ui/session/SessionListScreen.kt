package com.mojing.app.ui.session

import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.navigation.NavHostController
import androidx.compose.ui.res.stringResource
import com.mojing.app.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.data.local.entity.SessionWithListMeta
import com.mojing.app.data.local.entity.WorldTemplateEntity
import com.mojing.app.ui.chat.ChatMessageTextFormat
import com.mojing.app.ui.navigation.MainAppBottomNavigation
import com.mojing.app.ui.common.LlmKeySetupHintCard
import com.mojing.app.ui.common.MoJingListTokens
import com.mojing.app.ui.common.MoJingToggleRow
import com.mojing.app.ui.common.SwipeRevealListRow
import com.mojing.app.ui.common.isImeKeyboardOpen
import com.mojing.app.ui.util.UserFacingStrings
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

internal fun findRequestedWorldTemplate(
    templates: List<WorldTemplateEntity>,
    templateId: Long?,
): WorldTemplateEntity? = templateId?.let { id -> templates.firstOrNull { it.id == id } }

internal fun findDefaultWorldTemplate(
    templates: List<WorldTemplateEntity>,
    templateId: String,
): WorldTemplateEntity? = templateId.trim()
    .takeUnless { it.isEmpty() || it == "custom" }
    ?.let { id -> templates.firstOrNull { it.templateId == id } }

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
    val sessions = (sessionLibraryState as? SessionLibraryUiState.Loaded)?.sessions.orEmpty()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val generatingSessions by com.mojing.app.ui.chat.RetainedChatSessions.running.collectAsStateWithLifecycle()
    val backgroundFailures by com.mojing.app.ui.chat.RetainedChatSessions.stores.failures.collectAsStateWithLifecycle()
    val pendingGenTasks by viewModel.pendingGenerationTaskCount.collectAsStateWithLifecycle()
    val hasPublicLlmKey by viewModel.hasPublicLlmKey.collectAsStateWithLifecycle()
    val isCreatingSession by viewModel.isCreatingSession.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.syncPublicLlmKeyFromStorage()
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
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingTemplateId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dialogLoadRequestVersion by rememberSaveable { mutableIntStateOf(0) }
    var resetDialogFormOnNextLoad by rememberSaveable { mutableStateOf(true) }
    var isLoadingDialogData by remember { mutableStateOf(false) }
    var dialogLoadError by remember { mutableStateOf<String?>(null) }
    var worldMappings by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var templates by remember { mutableStateOf<List<WorldTemplateEntity>>(emptyList()) }
    var encyclopedias by remember { mutableStateOf<List<EncyclopediaEntity>>(emptyList()) }
    var allBoundCharacters by remember { mutableStateOf<List<CharacterEntity>>(emptyList()) }
    var selectedCharacterIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var initializeCharacterSelection by remember { mutableStateOf(true) }
    var newSessionTitle by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf<WorldTemplateEntity?>(null) }
    var selectedEncId by remember { mutableStateOf<Long?>(null) }
    var narratorOn by remember { mutableStateOf(false) }
    var narratorName by remember { mutableStateOf("旁白") }
    var choiceOn by remember { mutableStateOf(true) }
    var maxChoicesStr by remember { mutableStateOf("3") }
    var antiCheatOn by remember { mutableStateOf(true) }
    /** 新建对话：展示用上下文 token 上限（仅统计条），默认 100 万 */
    var displayContextLimitStr by remember { mutableStateOf("1000000") }
    var showConversationOptions by rememberSaveable { mutableStateOf(false) }
    var showQuickStartReplay by remember { mutableStateOf(false) }
    val guideDismissed by viewModel.quickStartGuideDismissed.collectAsStateWithLifecycle()
    val isSessionHomeContentReady = sessionLibraryState is SessionLibraryUiState.Loaded &&
        (sessions.isNotEmpty() || guideDismissed != null)

    fun openNewSessionDialog(templateId: Long? = null) {
        pendingTemplateId = templateId
        resetDialogFormOnNextLoad = true
        showConversationOptions = false
        dialogLoadRequestVersion += 1
        showCreateDialog = true
    }

    fun closeNewSessionDialog() {
        pendingTemplateId = null
        resetDialogFormOnNextLoad = true
        isLoadingDialogData = false
        dialogLoadError = null
        showConversationOptions = false
        focusManager.clearFocus()
        showCreateDialog = false
    }

    LaunchedEffect(newSessionRequestId) {
        newSessionRequestId?.let { requestId ->
            openNewSessionDialog()
            onNewSessionRequestConsumed(requestId)
        }
    }

    LaunchedEffect(newSessionTemplateId) {
        newSessionTemplateId?.let { templateId ->
            openNewSessionDialog(templateId)
            onNewSessionTemplateConsumed()
        }
    }

    LaunchedEffect(showCreateDialog, dialogLoadRequestVersion) {
        if (showCreateDialog) {
            val requestedTemplateId = pendingTemplateId
            val d = viewModel.newSessionDialogDefaults()
            if (resetDialogFormOnNextLoad) {
                newSessionTitle = ""
                selectedTemplate = null
                selectedEncId = null
                templates = emptyList()
                encyclopedias = emptyList()
                allBoundCharacters = emptyList()
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
            isLoadingDialogData = true
            dialogLoadError = null
            try {
                val data = viewModel.loadNewSessionDialogData()
                templates = data.templates
                worldMappings = data.worldMappings
                encyclopedias = data.encyclopedias
                allBoundCharacters = data.boundCharacters
                if (initializeCharacterSelection) {
                    selectedCharacterIds = openingCharacterSelection(data.boundCharacters.map { it.id }.toSet(), null)
                    initializeCharacterSelection = false
                }
                selectedTemplate = findRequestedWorldTemplate(data.templates, requestedTemplateId)
                    ?: if (requestedTemplateId == null) {
                        findDefaultWorldTemplate(data.templates, d.defaultWorldTemplateId)
                    } else {
                        null
                    }
                if (requestedTemplateId != null && selectedTemplate == null) {
                    Toast.makeText(context, "世界模板已不存在，已打开普通新对话", Toast.LENGTH_SHORT).show()
                } else if (
                    requestedTemplateId == null &&
                    d.defaultWorldTemplateId.isNotBlank() &&
                    d.defaultWorldTemplateId != "custom" &&
                    selectedTemplate == null
                ) {
                    Toast.makeText(context, "默认世界模板已不存在，本次不使用模板", Toast.LENGTH_SHORT).show()
                }
                selectedTemplate?.let { template ->
                    data.worldMappings[template.id]?.let { selectedEncId = it; selectedTemplate = null }
                }
                pendingTemplateId = null
                isLoadingDialogData = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                isLoadingDialogData = false
                dialogLoadError = "新建对话资料读取失败，请重试"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("故事库", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (sessions.isEmpty()) "对话与长篇创作" else "${sessions.size} 个故事",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                            Icon(Icons.Default.AutoAwesome, contentDescription = "生成记录")
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
        floatingActionButton = {
            if (isSessionHomeContentReady && sessions.isNotEmpty() && !isImeKeyboardOpen()) {
                FloatingActionButton(
                    onClick = { openNewSessionDialog() },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, "新建对话")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            if (
                isSessionHomeContentReady &&
                (sessions.isNotEmpty() || searchQuery.isNotBlank())
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearch(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    placeholder = { Text("搜索故事标题…") },
                    leadingIcon = { Icon(Icons.Default.Search, "搜索对话标题") },
                    trailingIcon = if (searchQuery.isNotBlank()) {
                        {
                            IconButton(onClick = { viewModel.updateSearch("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "清除标题搜索")
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
            }
            if (isSessionHomeContentReady && !hasPublicLlmKey) {
                LlmKeySetupHintCard(
                    message = "请先在设置填写 API Key",
                    onOpenSettings = onSettingsClick,
                    modifier = Modifier.padding(horizontal = MoJingListTokens.rowStart, vertical = 4.dp),
                )
            }

            if (
                sessionLibraryState is SessionLibraryUiState.Loading ||
                sessionLibraryState is SessionLibraryUiState.Loaded && sessions.isEmpty() && guideDismissed == null
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
                        Icons.Default.ErrorOutline,
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
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("重试")
                    }
                }
            } else if (sessions.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (searchQuery.isNotBlank()) {
                        Text(
                            UserFacingStrings.sessionSearchEmptyLibrary(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                            "角色", "创建或导入人物，自由开启对话", "01", Icons.Default.Person, onCharactersClick)
                        com.mojing.app.ui.common.WorkspaceResourceCard(
                            "世界百科", "补充背景、地点与人物关系", "02", Icons.Default.Public, onEncyclopediaClick)
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
                                Icon(Icons.Default.Add, contentDescription = null)
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
                val filteredSessions = if (searchQuery.isBlank()) sessions else sessions.filter {
                    it.session.title.contains(searchQuery, ignoreCase = true)
                }
                if (filteredSessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            UserFacingStrings.sessionSearchNoMatch(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    item(key = "story-library-heading") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(if (searchQuery.isBlank()) "故事列表" else "搜索结果", style = MaterialTheme.typography.titleMedium)
                            }
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text("${filteredSessions.size}", modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                    itemsIndexed(filteredSessions, key = { _, r -> r.session.id }) { index, row ->
                        val openRename = {
                            viewModel.clearRenameResult()
                            renameTargetId = row.session.id
                            renameTargetTitle = row.session.title
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            ),
                        ) {
                            SwipeRevealListRow(
                                swipeEnabled = true,
                                isPinned = row.session.pinnedAt > 0,
                                onPinToggle = { viewModel.setSessionPinned(row.session.id, row.session.pinnedAt == 0L) },
                                onDelete = { viewModel.clearDeletionResult(); deleteTargetId = row.session.id; deleteTargetTitle = row.session.title },
                                onClick = { onSessionClick(row.session.id) },
                                onRename = openRename,
                            ) {
                                SessionListRowInner(row = row, isGenerating = row.session.id in generatingSessions,
                                    backgroundFailure = backgroundFailures[row.session.id]?.message,
                                    onStop = { com.mojing.app.ui.chat.RetainedChatSessions.stores.stop(row.session.id) },
                                    onRename = openRename)
                            }
                        }
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
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismissRequest = {
                if (isCreatingSession) {
                    Toast.makeText(context, "正在创建对话，请稍候", Toast.LENGTH_SHORT).show()
                } else {
                    closeNewSessionDialog()
                }
            },
        ) {
            val newSessionScroll = rememberScrollState()
            val selectableCharacters = remember(selectedEncId, allBoundCharacters) {
                when (val id = selectedEncId) {
                    null -> allBoundCharacters
                    else -> allBoundCharacters.filter { it.boundEncyclopediaId <= 0L || it.boundEncyclopediaId == id }
                }
            }
            LaunchedEffect(selectedEncId, allBoundCharacters) {
                selectedCharacterIds = openingCharacterSelection(selectableCharacters.map { it.id }.toSet(), selectedCharacterIds)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .imePadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AutoStories, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("新建对话", style = MaterialTheme.typography.titleLarge)
                        Text("设定这一幕，然后开始书写", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { closeNewSessionDialog() }, enabled = !isCreatingSession) {
                        Icon(Icons.Default.Close, contentDescription = "关闭新建对话")
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
                Text("故事信息", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
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
                            enabled = !isCreatingSession,
                        ) { Text("重试") }
                    }
                }
                OutlinedTextField(
                    value = newSessionTitle,
                    onValueChange = { newSessionTitle = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                var worldExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = worldExpanded, onExpandedChange = { worldExpanded = it }) {
                    OutlinedTextField(
                        value = encyclopedias.firstOrNull { it.id == selectedEncId }?.name
                            ?: selectedTemplate?.label ?: "不绑定世界",
                        onValueChange = {}, readOnly = true, singleLine = true,
                        label = { Text("世界") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(worldExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = worldExpanded, onDismissRequest = { worldExpanded = false }) {
                        DropdownMenuItem(text = { Text("不绑定世界") }, onClick = { selectedTemplate = null; selectedEncId = null; worldExpanded = false })
                        encyclopedias.forEach { enc ->
                            DropdownMenuItem(text = { Text(enc.name) }, onClick = { selectedTemplate = null; selectedEncId = enc.id; worldExpanded = false })
                        }
                        templates.filter { it.id !in worldMappings && it.templateId != "custom" }.forEach { template ->
                            DropdownMenuItem(text = { Text("${template.label} · 旧资料") }, onClick = { selectedTemplate = template; selectedEncId = null; worldExpanded = false })
                        }
                    }
                }

                Text("参与角色", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                if (allBoundCharacters.isEmpty()) {
                    Text(
                        "选择角色开始故事，也可以先进入空白对话，稍后再添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            closeNewSessionDialog()
                            onCharactersClick()
                        },
                        enabled = !isCreatingSession && !isLoadingDialogData,
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("去创建角色")
                    }
                } else if (selectableCharacters.isEmpty()) {
                    Text(
                        "当前百科没有可用角色。可以更换百科、去创建角色，或先进入空白对话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            closeNewSessionDialog()
                            onCharactersClick()
                        },
                        enabled = !isCreatingSession && !isLoadingDialogData,
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("去创建角色")
                    }
                } else {
                    Text(
                        "百科提供世界设定；未绑定百科的角色也可参与。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    var charExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = charExpanded,
                        onExpandedChange = { charExpanded = it },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = if (selectedCharacterIds.isEmpty()) "选择参与角色" else "${selectedCharacterIds.size}/${selectableCharacters.size} 人参与",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("参与角色") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = charExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            singleLine = true,
                        )
                        ExposedDropdownMenu(
                            expanded = charExpanded,
                            onDismissRequest = { charExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (selectedCharacterIds.size == selectableCharacters.size) "全不选" else "全选") },
                                onClick = {
                                    selectedCharacterIds =
                                        if (selectedCharacterIds.size == selectableCharacters.size) {
                                            emptySet()
                                        } else {
                                            selectableCharacters.map { it.id }.toSet()
                                        }
                                },
                            )
                            selectableCharacters.forEach { ch ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = ch.id in selectedCharacterIds,
                                                onCheckedChange = null,
                                            )
                                            Text(ch.name.ifBlank { "未命名" })
                                        }
                                    },
                                    onClick = {
                                        selectedCharacterIds = selectedCharacterIds.toMutableSet().apply {
                                            if (ch.id in this) remove(ch.id) else add(ch.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
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
                        if (showConversationOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showConversationOptions) "收起对话设置" else "展开对话设置",
                    )
                }
                if (showConversationOptions) {
                    Surface(Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoJingToggleRow("启用旁白", "回复中加入场景叙述", narratorOn,
                            { narratorOn = it }, enabled = !isCreatingSession)
                        if (narratorOn) {
                            OutlinedTextField(
                                value = narratorName,
                                onValueChange = { narratorName = it },
                                label = { Text("旁白名称") },
                                singleLine = true,
                                enabled = !isCreatingSession,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoJingToggleRow("生成剧情选项", "回复后给出可选行动", choiceOn,
                            { choiceOn = it }, enabled = !isCreatingSession)
                        if (choiceOn) {
                            OutlinedTextField(
                                value = maxChoicesStr,
                                onValueChange = { v ->
                                    if (v.isEmpty()) maxChoicesStr = ""
                                    else if (v.length <= 2 && v.all { it.isDigit() }) maxChoicesStr = v
                                },
                                label = { Text("每轮最多选项") },
                                singleLine = true,
                                enabled = !isCreatingSession,
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MoJingToggleRow("保持世界规则", "提醒角色遵守当前世界的限制与设定",
                            antiCheatOn, { antiCheatOn = it }, enabled = !isCreatingSession)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        OutlinedTextField(
                            value = displayContextLimitStr,
                            onValueChange = { v ->
                                if (v.length <= 10 && (v.isEmpty() || v.all { it.isDigit() })) {
                                    displayContextLimitStr = v
                                }
                            },
                            label = { Text("聊天容量提示") },
                            supportingText = {
                                Text("只影响聊天页的容量进度显示，不改变模型的实际上限")
                            },
                            suffix = { Text("tokens") },
                            singleLine = true,
                            enabled = !isCreatingSession,
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
                            onCreated = { id ->
                                Toast.makeText(context, UserFacingStrings.sessionCreated(), Toast.LENGTH_SHORT).show()
                                closeNewSessionDialog()
                                onSessionClick(id)
                            },
                            onBlocked = { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            },
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isCreatingSession && !isLoadingDialogData && dialogLoadError == null &&
                        selectableCharacters.isNotEmpty() && selectedCharacterIds.isNotEmpty(),
                ) { Text(if (isCreatingSession) "创建中…" else "创建并开始") }
                OutlinedButton(
                    onClick = {
                        viewModel.createNewSession(
                            onCreated = { id ->
                                Toast.makeText(context, UserFacingStrings.blankSessionCreated(), Toast.LENGTH_SHORT).show()
                                closeNewSessionDialog()
                                onSessionClick(id)
                            },
                            onFailed = { message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            },
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isCreatingSession && !isLoadingDialogData,
                ) { Text("空白对话") }
                }
            }
        }
    }

    if (showQuickStartReplay) {
        ModalBottomSheet(onDismissRequest = { showQuickStartReplay = false }) {
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
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("开始新对话")
        }
        com.mojing.app.ui.common.WorkspaceResourceCard(
            "角色", "创建或导入人物，自由开启对话", "01", Icons.Default.Person, onCharacters)
        com.mojing.app.ui.common.WorkspaceResourceCard(
            "世界百科", "补充背景、地点与人物关系", "02", Icons.Default.Public, onEncyclopedia)
    }
}

@Composable
fun SessionListRowInner(row: SessionWithListMeta, isGenerating: Boolean = false, onStop: (() -> Unit)? = null, backgroundFailure: String? = null, onRename: (() -> Unit)? = null) {
    val session = row.session
    val dateTimeFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val preview = ChatMessageTextFormat.preview(
        raw = row.lastMessagePreview.orEmpty(),
        speakerType = row.lastMessageSpeakerType,
        maxChars = 72,
    )
    val meta = "${row.messageCount} 条 · ${row.participantCount} 角色"
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(Modifier.size(50.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(17.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 2.dp) {
            Box(contentAlignment = Alignment.Center) {
                Text(session.title.trim().take(1).ifBlank { "墨" }, style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(session.title.ifBlank { "未命名对话" }, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (session.pinnedAt > 0) {
                    Icon(Icons.Default.PushPin, "已置顶", Modifier.padding(start = 6.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                if (onRename != null) {
                    IconButton(onClick = onRename) {
                        Icon(Icons.Default.Edit, contentDescription = "重命名${session.title.ifBlank { "对话" }}",
                            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (isGenerating) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary)
                        Text("后台生成", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (onStop != null) TextButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("停止") }
            }
            if (!isGenerating && backgroundFailure != null) Text("生成未完成 · $backgroundFailure",
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            if (!isGenerating && backgroundFailure == null && preview.isNotEmpty()) Text(preview, style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(dateTimeFormat.format(Date(session.updatedAt)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
