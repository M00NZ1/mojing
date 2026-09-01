package com.mojing.app.ui.session

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
    var deleteTarget by remember { mutableStateOf<SessionWithListMeta?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingTemplateId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dialogLoadRequestVersion by rememberSaveable { mutableIntStateOf(0) }
    var resetDialogFormOnNextLoad by rememberSaveable { mutableStateOf(true) }
    var isLoadingDialogData by remember { mutableStateOf(false) }
    var dialogLoadError by remember { mutableStateOf<String?>(null) }
    var templates by remember { mutableStateOf<List<WorldTemplateEntity>>(emptyList()) }
    var encyclopedias by remember { mutableStateOf<List<EncyclopediaEntity>>(emptyList()) }
    var allBoundCharacters by remember { mutableStateOf<List<CharacterEntity>>(emptyList()) }
    var selectedCharacterIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
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
                encyclopedias = data.encyclopedias
                allBoundCharacters = data.boundCharacters
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
                title = { Text(stringResource(R.string.app_name)) },
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
                            Icon(Icons.Default.CloudSync, contentDescription = "AI 生成任务")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            MainAppBottomNavigation(navController)
        },
        floatingActionButton = {
            if (isSessionHomeContentReady && !isImeKeyboardOpen()) {
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MoJingListTokens.rowStart, vertical = 8.dp),
                    placeholder = { Text("按标题搜索对话…") },
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
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    "快速开始",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text("三步上手", style = MaterialTheme.typography.titleMedium)
                                QuickStartGuideSteps(
                                    onCharacters = onCharactersClick,
                                    onCreateSession = { openNewSessionDialog() },
                                    onEncyclopedia = onEncyclopediaClick,
                                )
                                TextButton(onClick = { viewModel.dismissQuickStartGuide() }) {
                                    Text("不再显示此卡片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
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
                    contentPadding = PaddingValues(vertical = 0.dp),
                ) {
                    itemsIndexed(filteredSessions, key = { _, r -> r.session.id }) { index, row ->
                        Column(Modifier.fillMaxWidth()) {
                            SwipeRevealListRow(
                                swipeEnabled = true,
                                isPinned = row.session.pinnedAt > 0,
                                onPinToggle = { viewModel.setSessionPinned(row.session.id, row.session.pinnedAt == 0L) },
                                onDelete = { deleteTarget = row },
                                onClick = { onSessionClick(row.session.id) },
                            ) {
                                SessionListRowInner(row = row)
                            }
                            if (index < filteredSessions.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = MoJingListTokens.dividerInset),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }

    deleteTarget?.let { row ->
        val session = row.session
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除「${session.title}」吗？此操作不可撤回。") },
            confirmButton = {
                TextButton(onClick = {
                    val title = session.title.ifBlank { "未命名对话" }
                    viewModel.deleteSession(session.id)
                    deleteTarget = null
                    Toast.makeText(context, UserFacingStrings.itemDeleted(title), Toast.LENGTH_SHORT).show()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    if (showCreateDialog) {
        ModalBottomSheet(
            onDismissRequest = {
                if (isCreatingSession) {
                    Toast.makeText(context, "正在创建对话，请稍候", Toast.LENGTH_SHORT).show()
                } else {
                    closeNewSessionDialog()
                }
            },
        ) {
            val newSessionScroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .imePadding()
                    .verticalScroll(newSessionScroll)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("新建对话", style = MaterialTheme.typography.titleLarge)
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
                Text("世界模板", style = MaterialTheme.typography.labelMedium)
                var tmplExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = tmplExpanded,
                    onExpandedChange = { tmplExpanded = it },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = selectedTemplate?.let { t ->
                            t.label.trim().ifBlank { t.templateId }
                        } ?: "无（会话内用默认模板）",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("世界模板") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tmplExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = tmplExpanded,
                        onDismissRequest = { tmplExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("无") },
                            onClick = {
                                selectedTemplate = null
                                tmplExpanded = false
                            },
                        )
                        templates.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.label.ifBlank { t.templateId }) },
                                onClick = {
                                    selectedTemplate = t
                                    tmplExpanded = false
                                },
                            )
                        }
                    }
                }
                Text("绑定百科", style = MaterialTheme.typography.labelMedium)
                var encExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = encExpanded,
                    onExpandedChange = { encExpanded = it },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = when (val id = selectedEncId) {
                            null -> "不绑定"
                            else -> encyclopedias.find { it.id == id }?.name?.ifBlank { null } ?: "百科 $id"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("百科") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = encExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = encExpanded,
                        onDismissRequest = { encExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("不绑定") },
                            onClick = {
                                selectedEncId = null
                                encExpanded = false
                            },
                        )
                        encyclopedias.forEach { enc ->
                            DropdownMenuItem(
                                text = { Text(enc.name.ifBlank { "百科 ${enc.id}" }) },
                                onClick = {
                                    selectedEncId = enc.id
                                    encExpanded = false
                                },
                            )
                        }
                    }
                }

                val selectableCharacters = remember(selectedEncId, allBoundCharacters) {
                    when (val id = selectedEncId) {
                        null -> allBoundCharacters
                        else -> allBoundCharacters.filter { it.boundEncyclopediaId == id }
                    }
                }
                LaunchedEffect(selectedEncId, allBoundCharacters) {
                    selectedCharacterIds = selectableCharacters.map { it.id }.toSet()
                }

                Text("参与角色", style = MaterialTheme.typography.labelMedium)
                if (allBoundCharacters.isEmpty()) {
                    Text(
                        "完整故事需要至少一名已绑定百科的角色；也可以先进入空白对话，稍后再添加。",
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
                        "默认全选；绑定百科时仅列出该库角色。",
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
                            value = "${selectedCharacterIds.size}/${selectableCharacters.size} 人参与",
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = narratorOn, onCheckedChange = { narratorOn = it })
                            Text("启用旁白", modifier = Modifier.padding(end = 8.dp))
                            OutlinedTextField(
                                value = narratorName,
                                onValueChange = { narratorName = it },
                                label = { Text("旁白名称") },
                                singleLine = true,
                                enabled = narratorOn,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = choiceOn, onCheckedChange = { choiceOn = it })
                            Text("生成剧情选项", modifier = Modifier.padding(end = 8.dp))
                            OutlinedTextField(
                                value = maxChoicesStr,
                                onValueChange = { v ->
                                    if (v.isEmpty()) maxChoicesStr = ""
                                    else if (v.length <= 2 && v.all { it.isDigit() }) maxChoicesStr = v
                                },
                                label = { Text("最多") },
                                singleLine = true,
                                enabled = choiceOn,
                                modifier = Modifier.width(88.dp),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = antiCheatOn, onCheckedChange = { antiCheatOn = it })
                            Column {
                                Text("保持世界规则", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "提醒角色遵守当前世界的限制与设定",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
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
                    modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCreatingSession && !isLoadingDialogData,
                ) { Text("先进入空白对话") }
                TextButton(
                    onClick = { closeNewSessionDialog() },
                    modifier = Modifier.align(Alignment.End),
                    enabled = !isCreatingSession,
                ) { Text("取消") }
            }
        }
    }

    if (showQuickStartReplay) {
        ModalBottomSheet(onDismissRequest = { showQuickStartReplay = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 28.dp)) {
                Text("三步上手", style = MaterialTheme.typography.titleLarge)
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
private fun GuideStepBlock(num: String, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(num, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun QuickStartGuideSteps(
    onCharacters: () -> Unit,
    onCreateSession: () -> Unit,
    onEncyclopedia: () -> Unit,
) {
    GuideStepBlock("1", "准备故事世界")
    FilledTonalButton(onClick = onEncyclopedia, modifier = Modifier.padding(start = 40.dp)) { Text("打开百科") }
    Spacer(Modifier.height(8.dp))
    GuideStepBlock("2", "创建并绑定角色")
    FilledTonalButton(onClick = onCharacters, modifier = Modifier.padding(start = 40.dp)) { Text("去创建角色") }
    Spacer(Modifier.height(8.dp))
    GuideStepBlock("3", "开始对话")
    FilledTonalButton(onClick = onCreateSession, modifier = Modifier.padding(start = 40.dp)) { Text("开始新对话") }
}

@Composable
fun SessionListRowInner(row: SessionWithListMeta) {
    val session = row.session
    val dateTimeFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val preview = ChatMessageTextFormat.preview(
        raw = row.lastMessagePreview.orEmpty(),
        speakerType = row.lastMessageSpeakerType,
        maxChars = 72,
    )
    val meta = "${row.messageCount} 条 · ${row.participantCount} 角色"
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = {
            Surface(
                modifier = Modifier.size(MoJingListTokens.avatar),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        session.title.take(1).ifBlank { "谈" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        headlineContent = {
            Text(
                session.title.ifBlank { "未命名对话" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                if (preview.isNotEmpty()) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                )
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    dateTimeFormat.format(Date(session.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (session.pinnedAt > 0) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "已置顶",
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}
