package com.mojing.app.ui.character

import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.mojing.app.data.local.entity.CharacterEntity
import com.mojing.app.ui.common.EmptyState
import com.mojing.app.ui.navigation.MainAppBottomNavigation
import com.mojing.app.ui.navigation.returnToCreationHub
import com.mojing.app.ui.common.MoJingListTokens
import com.mojing.app.ui.common.SwipeRevealListRow
import com.mojing.app.ui.common.avatarImageModel
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.util.ContentDocumentReader
import com.mojing.app.util.ContentDocumentWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterListScreen(
    navController: NavHostController,
    onEdit: (Long) -> Unit,
    onChat: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: CharacterListViewModel = hiltViewModel()
) {
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val listLayout by viewModel.characterListLayout.collectAsStateWithLifecycle()
    val filterEnc by viewModel.filterEncyclopediaId.collectAsStateWithLifecycle()
    val encOptions by viewModel.encyclopedias.collectAsStateWithLifecycle()
    val startingCharacterId by viewModel.startingCharacterId.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<CharacterEntity?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    var isImportingDocument by remember { mutableStateOf(false) }
    var isExportingDocument by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.refreshEncyclopediaFilterOptions() }

    val encNameById = remember(encOptions) {
        encOptions.associate { it.id to it.name.ifBlank { "百科 ${it.id}" } }
    }

    fun launchCreateCharacter() {
        viewModel.createNew(filterEnc ?: 0L) { id -> onEdit(id) }
        Toast.makeText(context, UserFacingStrings.characterDraftCreated(), Toast.LENGTH_SHORT).show()
    }

    fun startChat(character: CharacterEntity) {
        if (character.boundEncyclopediaId <= 0L) {
            Toast.makeText(context, "请先为角色绑定一个百科", Toast.LENGTH_SHORT).show()
            onEdit(character.id)
            return
        }
        viewModel.startChat(
            characterId = character.id,
            onCreated = onChat,
            onNeedsEncyclopedia = {
                Toast.makeText(context, "请先为角色绑定一个百科", Toast.LENGTH_SHORT).show()
                onEdit(character.id)
            },
            onFailed = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
        )
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
                    val bytes = ContentDocumentReader.readBytes(
                        context,
                        uri,
                        ContentDocumentReader.CHARACTER_IMPORT_MAX_BYTES,
                    )
                    if (bytes.isEmpty()) {
                        Toast.makeText(context, UserFacingStrings.importReadFailed(), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val msg = viewModel.importFromDocument(bytes, uri.lastPathSegment)
                    // Room Flow 会自动刷新列表；清除旧筛选，确保新角色立即可见。
                    viewModel.setEncyclopediaFilter(null)
                    val message = UserFacingStrings.appendAndroidIfNeeded(msg)
                    Toast.makeText(
                        context,
                        message,
                        if (msg.startsWith("导入异常")) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                    ).show()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("角色管理") },
                navigationIcon = {
                    IconButton(onClick = { navController.returnToCreationHub() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回创作中心")
                    }
                },
                actions = {
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
                                text = {
                                    Text(if (isImportingDocument) "正在导入…" else "导入（便携包 / JSON / TXT / Word / PNG）")
                                },
                                enabled = !isImportingDocument && !isExportingDocument,
                                onClick = {
                                    showMoreMenu = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/png", "*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (isExportingDocument) "正在导出…" else "导出角色") },
                                enabled = !isImportingDocument && !isExportingDocument,
                                onClick = {
                                    showMoreMenu = false
                                    isExportingDocument = true
                                    exportLauncher.launch("mojing_characters.json")
                                },
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.toggleCharacterListLayout() }
                    ) {
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
            if (characters.isNotEmpty() || filterEnc != null) {
                FloatingActionButton(onClick = { launchCreateCharacter() }) {
                    Icon(Icons.Default.Add, "新建角色")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("筛选", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                ExposedDropdownMenuBox(
                    expanded = filterMenuExpanded,
                    onExpandedChange = { filterMenuExpanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        readOnly = true,
                        value = when (val fid = filterEnc) {
                            null -> "全部角色"
                            else -> encOptions.find { it.id == fid }?.name?.ifBlank { null } ?: "百科 $fid"
                        },
                        onValueChange = {},
                        label = { Text("百科") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = filterMenuExpanded) },
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = filterMenuExpanded,
                        onDismissRequest = { filterMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部角色") },
                            onClick = {
                                viewModel.setEncyclopediaFilter(null)
                                filterMenuExpanded = false
                            },
                        )
                        encOptions.forEach { enc ->
                            DropdownMenuItem(
                                text = { Text(enc.name.ifBlank { "百科 ${enc.id}" }) },
                                onClick = {
                                    viewModel.setEncyclopediaFilter(enc.id)
                                    filterMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Box(modifier = Modifier.weight(1f)) {
                if (characters.isEmpty()) {
                    val selectedEncyclopedia = filterEnc?.let(encNameById::get)
                    EmptyState(
                        icon = Icons.Default.PersonAdd,
                        title = if (filterEnc == null) "还没有角色" else "这个百科还没有角色",
                        message = if (filterEnc == null) {
                            "创建角色档案后，可以在这里编辑人设、形象和对话参数。"
                        } else {
                            "新建角色会自动绑定到「${selectedEncyclopedia ?: "当前百科"}」，也可以查看全部角色。"
                        },
                        actionLabel = if (filterEnc == null) "新建角色" else "查看全部角色",
                        onAction = if (filterEnc == null) {
                            ::launchCreateCharacter
                        } else {
                            { viewModel.setEncyclopediaFilter(null) }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (listLayout == "grid") {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(characters, key = { it.id }) { character ->
                            SwipeRevealListRow(
                                swipeEnabled = true,
                                isPinned = character.pinnedAt > 0,
                                onPinToggle = { viewModel.setCharacterPinned(character.id, character.pinnedAt == 0L) },
                                onDelete = { deleteTarget = character },
                                onClick = { onEdit(character.id) },
                                menuExtras = {
                                    DropdownMenuItem(
                                        text = { Text(if (character.favorite) "取消收藏" else "收藏") },
                                        onClick = { viewModel.toggleFavorite(character.id) },
                                    )
                                },
                            ) {
                                CharacterGridCard(
                                    character = character,
                                    encyclopediaLabel = encNameById[character.boundEncyclopediaId],
                                    startEnabled = startingCharacterId == null,
                                    isStarting = startingCharacterId == character.id,
                                    onStartChat = { startChat(character) },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 0.dp),
                    ) {
                        itemsIndexed(characters, key = { _, c -> c.id }) { index, character ->
                            Column(Modifier.fillMaxWidth()) {
                                SwipeRevealListRow(
                                    swipeEnabled = true,
                                    isPinned = character.pinnedAt > 0,
                                    onPinToggle = { viewModel.setCharacterPinned(character.id, character.pinnedAt == 0L) },
                                    onDelete = { deleteTarget = character },
                                    onClick = { onEdit(character.id) },
                                    menuExtras = {
                                        DropdownMenuItem(
                                            text = { Text(if (character.favorite) "取消收藏" else "收藏") },
                                            onClick = { viewModel.toggleFavorite(character.id) },
                                        )
                                    },
                                ) {
                                    CharacterListRowInner(
                                        character = character,
                                        encyclopediaLabel = encNameById[character.boundEncyclopediaId],
                                        startEnabled = startingCharacterId == null,
                                        isStarting = startingCharacterId == character.id,
                                        onStartChat = { startChat(character) },
                                    )
                                }
                                if (index < characters.lastIndex) {
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

    deleteTarget?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确认删除") },
            text = {
                Text(
                    "确定要删除「${c.name.ifBlank { "未命名角色" }}」吗？\n\n" +
                        "该角色的本地配置和角色档案会一并删除；已产生的聊天记录不会自动删除。此操作不可撤销。",
                )
            },
            confirmButton = { TextButton(onClick = { val deletedName = c.name; viewModel.delete(c.id); deleteTarget = null; Toast.makeText(context, UserFacingStrings.itemDeleted(deletedName.ifBlank { "未命名角色" }), Toast.LENGTH_SHORT).show() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun CharacterListRowInner(
    character: CharacterEntity,
    encyclopediaLabel: String? = null,
    startEnabled: Boolean,
    isStarting: Boolean,
    onStartChat: () -> Unit,
) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = {
            if (character.avatarImagePath.isNotBlank()) {
                AsyncImage(
                    model = avatarImageModel(context, character.avatarImagePath),
                    contentDescription = "头像",
                    modifier = Modifier
                        .size(MoJingListTokens.avatar)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                val circleBg = try {
                    androidx.compose.ui.graphics.Color(AndroidColor.parseColor(character.avatarColor))
                } catch (_: Exception) {
                    MaterialTheme.colorScheme.primaryContainer
                }
                Surface(
                    modifier = Modifier.size(MoJingListTokens.avatar),
                    shape = CircleShape,
                    color = circleBg,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            character.name.take(1).ifBlank { "?" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.contentColorFor(circleBg),
                        )
                    }
                }
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    character.name.ifBlank { "未命名角色" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (character.favorite) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Star, "收藏", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        },
        supportingContent = {
            Column {
                if (character.boundEncyclopediaId > 0L) {
                    Text(
                        "所属百科：${encyclopediaLabel ?: "百科 ${character.boundEncyclopediaId}"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        "未绑定百科（聊天前需先绑定）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    character.personaPrompt.take(72).ifEmpty { "未设定人设" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onStartChat, enabled = startEnabled) {
                if (isStarting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = if (character.boundEncyclopediaId > 0L) "开始对话" else "绑定百科后开始对话",
                    )
                }
            }
        },
    )
}

@Composable
private fun CharacterGridCard(
    character: CharacterEntity,
    encyclopediaLabel: String? = null,
    startEnabled: Boolean,
    isStarting: Boolean,
    onStartChat: () -> Unit,
) {
    val context = LocalContext.current
    val mainPath = character.cardImagePath.ifBlank { character.avatarImagePath }
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
            ) {
                when {
                    mainPath.isNotEmpty() -> AsyncImage(
                        model = avatarImageModel(context, mainPath),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    else -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = try { androidx.compose.ui.graphics.Color(AndroidColor.parseColor(character.avatarColor)) } catch (_: Exception) { MaterialTheme.colorScheme.primary }
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text((character.name.ifBlank { "?" }).take(1), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
                if (character.favorite) {
                    Icon(
                        Icons.Default.Star,
                        "收藏",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(22.dp)
                    )
                }
                if (character.cardImagePath.isNotBlank() && character.avatarImagePath.isNotBlank() && character.cardImagePath != character.avatarImagePath) {
                    AsyncImage(
                        model = avatarImageModel(context, character.avatarImagePath),
                        contentDescription = "头像",
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(36.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    character.name.ifBlank { "未命名角色" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (character.boundEncyclopediaId > 0L) {
                    Text(
                        encyclopediaLabel ?: "百科 ${character.boundEncyclopediaId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                } else {
                    Text(
                        "未绑定百科（聊天前需先绑定）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                    )
                }
                Text(
                    character.personaPrompt.take(42).ifEmpty { "未设定人设" },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onStartChat,
                    enabled = startEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    if (isStarting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("创建中…")
                    } else {
                        Text(if (character.boundEncyclopediaId > 0L) "开始对话" else "先绑定百科")
                    }
                }
            }
        }
    }
}
