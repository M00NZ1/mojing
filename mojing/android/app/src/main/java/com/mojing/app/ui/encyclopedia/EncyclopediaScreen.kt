package com.mojing.app.ui.encyclopedia

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as lazyGridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mojing.app.data.local.entity.EncyclopediaEntity
import com.mojing.app.media.CharacterCardImageProcessor
import com.mojing.app.ui.character.components.CardCoverCropSheetHost
import com.mojing.app.ui.common.EmptyState
import com.mojing.app.ui.common.LlmKeySetupHintCard
import com.mojing.app.ui.navigation.MainAppBottomNavigation
import com.mojing.app.ui.navigation.returnToCreationHub
import com.mojing.app.ui.common.MoJingListTokens
import com.mojing.app.ui.common.SwipeRevealListRow
import com.mojing.app.ui.common.avatarImageModel
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.util.ContentDocumentReader
import com.mojing.app.util.ContentDocumentWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncyclopediaScreen(
    navController: NavHostController,
    onDetail: (Long) -> Unit,
    onWorldSettings: (Long) -> Unit = {},
    onSettingsClick: () -> Unit,
    onGenerationTasksClick: () -> Unit,
    viewModel: EncyclopediaListViewModel = hiltViewModel()
) {
    val encyclopedias by viewModel.encyclopedias.collectAsStateWithLifecycle()
    val listLayout by viewModel.encyclopediaListLayout.collectAsStateWithLifecycle()
    val hasPublicLlmKey by viewModel.hasPublicLlmKey.collectAsStateWithLifecycle()
    val pendingGenTasks by viewModel.pendingGenerationTaskCount.collectAsStateWithLifecycle()
    val coverGeneratingEncyclopediaIds by viewModel.coverGeneratingEncyclopediaIds.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.syncPublicLlmKeyFromStorage()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.syncPublicLlmKeyFromStorage()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var renameTarget by remember { mutableStateOf<EncyclopediaEntity?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<EncyclopediaEntity?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var isImportingDocument by remember { mutableStateOf(false) }
    var isExportingDocument by rememberSaveable { mutableStateOf(false) }
    var coverPickTarget by remember { mutableStateOf<EncyclopediaEntity?>(null) }
    var coverCropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var coverPendingEncId by remember { mutableStateOf<Long?>(null) }
    var isCoverTaskBusy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var createError by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    fun requestCoverGeneration(encyclopedia: EncyclopediaEntity) {
        val started = viewModel.generateEncyclopediaCoverAi(encyclopedia.id) { message ->
            scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.appendAndroidIfNeeded(message)) }
        }
        if (!started) {
            scope.launch { snackbarHostState.showSnackbar("这个百科库的封面正在生成中") }
        }
    }

    @Composable
    fun GenerateCoverMenuItem(encyclopedia: EncyclopediaEntity) {
        val isGenerating = encyclopedia.id in coverGeneratingEncyclopediaIds
        DropdownMenuItem(
            text = { Text(if (isGenerating) "生成中…" else "生成封面") },
            enabled = !isGenerating && !isCoverTaskBusy,
            onClick = { requestCoverGeneration(encyclopedia) },
        )
    }

    val coverPickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val enc = coverPickTarget
        coverPickTarget = null
        if (enc == null || uri == null || isCoverTaskBusy) return@rememberLauncherForActivityResult
        val encId = enc.id
        isCoverTaskBusy = true
        scope.launch {
            var keepBusyForCrop = false
            var decodedBitmap: Bitmap? = null
            try {
                withContext(Dispatchers.IO) {
                    decodedBitmap = CharacterCardImageProcessor.decodeBitmapFromUri(context, uri)
                }
                if (decodedBitmap != null) {
                    coverPendingEncId = encId
                    coverCropBitmap = decodedBitmap
                    keepBusyForCrop = true
                } else {
                    Toast.makeText(context, UserFacingStrings.cardImageProcessFailed(), Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    e.message ?: UserFacingStrings.cardImageProcessFailed(),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                if (!keepBusyForCrop) {
                    runCatching { decodedBitmap?.recycle() }
                    isCoverTaskBusy = false
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !isImportingDocument && isExportingDocument) {
            scope.launch {
                try {
                    val json = viewModel.exportJson()
                    ContentDocumentWriter.writeUtf8Text(context, uri, json)
                    Toast.makeText(context, UserFacingStrings.exportSuccess(), Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Toast.makeText(context, UserFacingStrings.exportWriteFailed(), Toast.LENGTH_SHORT).show()
                } finally {
                    isExportingDocument = false
                }
            }
        } else {
            isExportingDocument = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !isImportingDocument && !isExportingDocument) {
            isImportingDocument = true
            scope.launch {
                try {
                    val text = ContentDocumentReader.readUtf8Text(
                        context,
                        uri,
                        ContentDocumentReader.STRUCTURED_TEXT_IMPORT_MAX_BYTES,
                    )
                    if (text.isBlank()) {
                        Toast.makeText(context, UserFacingStrings.importReadFailed(), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val msg = viewModel.importJson(text)
                    Toast.makeText(context, UserFacingStrings.appendAndroidIfNeeded(msg), Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        e.message ?: UserFacingStrings.importReadFailed(),
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    isImportingDocument = false
                }
            }
        }
    }

    fun createEncyclopedia() {
        if (isCreating) return
        isCreating = true
        createError = null
        scope.launch {
            val result = viewModel.createNew()
            isCreating = false
            result.onSuccess { id ->
                Toast.makeText(context, UserFacingStrings.encyclopediaCreated(), Toast.LENGTH_SHORT).show()
                onDetail(id)
            }.onFailure { error ->
                val message = error.message ?: "百科创建失败，请重试"
                createError = message
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("世界") },
                navigationIcon = {
                    IconButton(onClick = { navController.returnToCreationHub() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回创作中心")
                    }
                },
                actions = {
                    TextButton(onClick = { navController.navigate(com.mojing.app.ui.navigation.Routes.WORKBENCH) }) { Text("工坊") }
                    IconButton(onClick = onGenerationTasksClick) {
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
                            Icon(
                                Icons.Filled.CloudSync,
                                contentDescription = "AI 生成任务",
                            )
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            enabled = !isImportingDocument && !isExportingDocument,
                        ) {
                            if (isImportingDocument || isExportingDocument) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.MoreVert, "更多")
                            }
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (isImportingDocument) "正在导入…" else "导入百科") },
                                enabled = !isImportingDocument && !isExportingDocument,
                                onClick = {
                                    showMoreMenu = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (isExportingDocument) "正在导出…" else "导出百科") },
                                enabled = !isImportingDocument && !isExportingDocument,
                                onClick = {
                                    showMoreMenu = false
                                    isExportingDocument = true
                                    exportLauncher.launch("mojing_encyclopedias.json")
                                },
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.toggleEncyclopediaListLayout() }) {
                        if (listLayout == "grid") {
                            Icon(Icons.AutoMirrored.Filled.ViewList, "切换为列表")
                        } else {
                            Icon(Icons.Default.GridView, "切换为网格")
                        }
                    }
                },
            )
        },
        bottomBar = {
            MainAppBottomNavigation(navController)
        },
        floatingActionButton = {
            if (encyclopedias.isNotEmpty()) {
                FloatingActionButton(onClick = { if (!isCreating) createEncyclopedia() }) {
                    if (isCreating) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Add, "新建百科")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (createError != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MoJingListTokens.rowStart, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            createError.orEmpty(),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = ::createEncyclopedia, enabled = !isCreating) { Text("重试") }
                    }
                }
            }
            if (encyclopedias.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = "还没有世界百科",
                    message = "创建百科后，可以整理世界规则、地点、势力、事件和人物关系。",
                    actionLabel = if (isCreating) "正在创建…" else "新建百科",
                    onAction = ::createEncyclopedia,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                if (!hasPublicLlmKey) {
                    LlmKeySetupHintCard(
                        message = "请先在设置填写 API Key",
                        onOpenSettings = onSettingsClick,
                        modifier = Modifier.padding(horizontal = MoJingListTokens.rowStart, vertical = 8.dp),
                    )
                }
                if (pendingGenTasks > 0) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MoJingListTokens.rowStart, vertical = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        ),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "$pendingGenTasks 个任务进行中",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onGenerationTasksClick) {
                                Text("查看任务")
                            }
                        }
                    }
                }
                if (listLayout == "grid") {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                    lazyGridItems(encyclopedias, key = { it.id }) { enc ->
                        SwipeRevealListRow(
                            swipeEnabled = false,
                            isPinned = enc.pinnedAt > 0,
                            onPinToggle = { viewModel.setEncyclopediaPinned(enc.id, enc.pinnedAt == 0L) },
                            onDelete = { deleteTarget = enc },
                            onClick = { onDetail(enc.id) },
                            menuExtras = {
                                DropdownMenuItem(text = { Text("世界设置") }, onClick = { onWorldSettings(enc.id) })
                                DropdownMenuItem(text = { Text("重命名") }, onClick = {
                                    renameTarget = enc; renameDraft = enc.name; renameError = null
                                })
                                DropdownMenuItem(
                                    text = { Text(if (isCoverTaskBusy) "正在处理封面…" else "从相册设置封面") },
                                    enabled = !isCoverTaskBusy && enc.id !in coverGeneratingEncyclopediaIds,
                                    onClick = {
                                        coverPickTarget = enc
                                        coverPickLauncher.launch("image/*")
                                    },
                                )
                                GenerateCoverMenuItem(enc)
                            },
                        ) {
                            EncyclopediaGridCard(enc = enc)
                        }
                    }
                }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 0.dp),
                    ) {
                    itemsIndexed(encyclopedias, key = { _, e -> e.id }) { index, enc ->
                        Column(Modifier.fillMaxWidth()) {
                            SwipeRevealListRow(
                                swipeEnabled = true,
                                isPinned = enc.pinnedAt > 0,
                                onPinToggle = { viewModel.setEncyclopediaPinned(enc.id, enc.pinnedAt == 0L) },
                                onDelete = { deleteTarget = enc },
                                onClick = { onDetail(enc.id) },
                                menuExtras = {
                                    DropdownMenuItem(text = { Text("世界设置") }, onClick = { onWorldSettings(enc.id) })
                                DropdownMenuItem(text = { Text("重命名") }, onClick = {
                                    renameTarget = enc; renameDraft = enc.name; renameError = null
                                })
                                    DropdownMenuItem(
                                        text = { Text(if (isCoverTaskBusy) "正在处理封面…" else "从相册设置封面") },
                                        enabled = !isCoverTaskBusy && enc.id !in coverGeneratingEncyclopediaIds,
                                        onClick = {
                                            coverPickTarget = enc
                                            coverPickLauncher.launch("image/*")
                                        },
                                    )
                                    GenerateCoverMenuItem(enc)
                                },
                            ) {
                                EncyclopediaListRowInner(enc = enc)
                            }
                            if (index < encyclopedias.lastIndex) {
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

    CardCoverCropSheetHost(
        bitmap = coverCropBitmap,
        requestToken = coverPendingEncId,
        onBitmapDisposed = { coverCropBitmap = null },
        onCropCancelled = { coverPendingEncId = null },
        onProcessingChanged = { isCoverTaskBusy = it },
        onCroppedPath = { id, path ->
            coverPendingEncId = null
            if (id != null) {
                Toast.makeText(context, viewModel.updateEncyclopediaCover(id, path), Toast.LENGTH_SHORT).show()
            } else {
                withContext(Dispatchers.IO) { File(path).delete() }
                Toast.makeText(context, "封面目标已失效，请重试", Toast.LENGTH_SHORT).show()
            }
        },
        onCroppedFailed = { ctx ->
            coverPendingEncId = null
            Toast.makeText(ctx, UserFacingStrings.cardImageProcessFailed(), Toast.LENGTH_SHORT).show()
        },
    )

    renameTarget?.let { enc ->
        AlertDialog(
            onDismissRequest = { if (!renaming) renameTarget = null },
            title = { Text("重命名百科") },
            text = {
                OutlinedTextField(value = renameDraft,
                    onValueChange = { renameDraft = it; renameError = null },
                    label = { Text("百科名称") }, singleLine = true,
                    enabled = !renaming, isError = renameError != null,
                    supportingText = { renameError?.let { Text(it) } })
            },
            confirmButton = {
                TextButton(enabled = !renaming && renameDraft.isNotBlank(), onClick = {
                    renaming = true
                    scope.launch {
                        try {
                            renameError = viewModel.rename(enc.id, renameDraft)
                            if (renameError == null) renameTarget = null
                        } finally { renaming = false }
                    }
                }) { Text(if (renaming) "保存中…" else "保存") }
            },
            dismissButton = { TextButton(enabled = !renaming, onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { enc ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确认删除") },
            text = {
                Text(
                    "确定要删除「${enc.name.ifBlank { "未命名百科库" }}」吗？\n\n" +
                        "这会同时删除该百科下的条目、时间线、关系，以及已绑定的角色。此操作不可撤销。",
                )
            },
            confirmButton = { TextButton(enabled = !deleting, onClick = {
                deleting = true
                scope.launch {
                    try {
                        val error = viewModel.delete(enc.id)
                        if (error == null) {
                            deleteTarget = null
                            Toast.makeText(context, UserFacingStrings.itemDeleted(enc.name.ifBlank { "未命名百科库" }), Toast.LENGTH_SHORT).show()
                        } else Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    } finally { deleting = false }
                }
            }) { Text(if (deleting) "删除中…" else "删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !deleting, onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun EncyclopediaListRowInner(enc: EncyclopediaEntity) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = {
            if (enc.coverImagePath.isNotBlank()) {
                AsyncImage(
                    model = avatarImageModel(context, enc.coverImagePath),
                    contentDescription = null,
                    modifier = Modifier
                        .size(MoJingListTokens.avatar)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(
                    modifier = Modifier.size(MoJingListTokens.avatar),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        },
        headlineContent = {
            Text(
                enc.name.ifBlank { "未命名百科库" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                val preview = enc.description.ifBlank { enc.worldPrompt }.trim()
                if (preview.isNotBlank()) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (enc.genreTags.isNotBlank()) {
                    Text(
                        enc.genreTags,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}

@Composable
private fun EncyclopediaGridCard(enc: EncyclopediaEntity) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            ) {
                if (enc.coverImagePath.isNotBlank()) {
                    AsyncImage(
                        model = avatarImageModel(context, enc.coverImagePath),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.MenuBook,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (enc.genreTags.isNotBlank()) {
                        Text(
                            enc.genreTags,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    enc.name.ifBlank { "未命名百科库" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val gridPreview = enc.description.ifBlank { enc.worldPrompt }.trim()
                if (gridPreview.isNotBlank()) {
                    Text(
                        gridPreview,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (enc.genreTags.isNotBlank()) {
                    Text(enc.genreTags, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
