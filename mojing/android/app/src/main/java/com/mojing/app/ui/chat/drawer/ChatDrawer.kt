package com.mojing.app.ui.chat.drawer
import androidx.compose.ui.draw.clip

import com.mojing.app.ui.common.MoJingIcon as Icon
import com.mojing.app.ui.common.MoJingFilterChip as FilterChip
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.AutoAwesome
import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.data.local.entity.SessionEventNodeEntity
import com.mojing.app.data.local.entity.SessionMemorySegmentEntity
import com.mojing.app.data.local.entity.SessionParticipantEntity
import com.mojing.app.data.local.entity.SessionWorldCredentialDraft
import com.mojing.app.data.local.entity.SessionWorldEntity
import com.mojing.app.data.local.entity.MessageBookmarkEntity
import com.mojing.app.data.local.entity.SessionMemoryCorrectionEntity
import com.mojing.app.ui.chat.MemoryCorrectionPromptTrace
import com.mojing.app.ui.chat.ContextMemoryStatus
import com.google.gson.Gson
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDrawer(
    participants: List<SessionParticipantEntity>,
    world: SessionWorldEntity? = null,
    encyclopediaFoundation: String = "",
    encyclopediaFoundationLoaded: Boolean = true,
    encyclopediaFoundationLoading: Boolean = false,
    encyclopediaFoundationLoadError: String? = null,
    onOpenEncyclopediaFoundation: () -> Unit = {},
    onRetryEncyclopediaFoundation: () -> Unit = {},
    contextMemoryText: String = "",
    contextMemoryLoaded: Boolean = true,
    contextMemoryLoading: Boolean = false,
    contextMemoryLoadError: String? = null,
    contextMemoryClearError: String? = null,
    onOpenContextMemory: () -> Unit = {},
    onRetryContextMemory: () -> Unit = {},
    onRetryClearContextMemory: (String) -> Unit = {},
    contextMemoryStatus: ContextMemoryStatus = ContextMemoryStatus.IDLE,
    memoryOperationRunning: Boolean = false,
    manualCompactionRunning: Boolean = false,
    manualCompactionChunk: Int? = null,
    memorySegments: List<SessionMemorySegmentEntity> = emptyList(),
    memoryCorrections: List<SessionMemoryCorrectionEntity> = emptyList(),
    memoryCorrectionsLoaded: Boolean = true,
    memoryCorrectionsLoading: Boolean = false,
    memoryCorrectionsHasMore: Boolean = false,
    memoryCorrectionsLoadError: String? = null,
    onOpenMemoryCorrections: () -> Unit = {},
    onLoadMoreMemoryCorrections: () -> Unit = {},
    memoryCorrectionPromptTrace: MemoryCorrectionPromptTrace? = null,
    currentBranchId: String = "main",
    drawerOpen: Boolean = true,
    sessionReady: Boolean = true,
    isGenerating: Boolean = false,
    eventNodes: List<SessionEventNodeEntity> = emptyList(),
    eventNodesLoaded: Boolean = true,
    eventNodesHasMore: Boolean = false,
    eventNodesLoadingMore: Boolean = false,
    eventNodesLoadError: String? = null,
    eventQuery: String = "",
    eventResolvedFilter: Boolean? = null,
    onEventQueryChange: (String) -> Unit = {},
    onEventResolvedFilterChange: (Boolean?) -> Unit = {},
    eventNodesHasNewer: Boolean = false,
    onResetEventWindow: () -> Unit = {},
    onLoadMoreEventNodes: () -> Unit = {},
    onOpenEvents: () -> Unit = {},
    eventBusyIds: Set<Long> = emptySet(),
    eventActionErrors: Map<Long, String> = emptyMap(),
    characterNames: Map<Long, String> = emptyMap(),
    characterAvatars: Map<Long, String> = emptyMap(),
    characterSummaries: Map<Long, String> = emptyMap(),
    onOpenCharacterState: (Long) -> Unit = {},
    bookmarks: List<MessageBookmarkEntity> = emptyList(),
    bookmarksLoaded: Boolean = true,
    bookmarksHasMore: Boolean = false,
    bookmarksLoadingMore: Boolean = false,
    bookmarksLoadError: String? = null,
    bookmarkQuery: String = "",
    onBookmarkQueryChange: (String) -> Unit = {},
    bookmarksHasNewer: Boolean = false,
    onResetBookmarkWindow: () -> Unit = {},
    bookmarkBusyIds: Set<Long> = emptySet(),
    bookmarkLocatingId: Long? = null,
    bookmarkPreviews: Map<Long, String> = emptyMap(),
    bookmarkNoteDrafts: Map<Long, String> = emptyMap(),
    bookmarkNoteErrors: Map<Long, String> = emptyMap(),
    bookmarkNoteSavingIds: Set<Long> = emptySet(),
    onJumpToBookmark: (Long) -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    onBookmarkNoteDraftChange: (Long, String) -> Unit = { _, _ -> },
    onSaveBookmarkNote: (Long, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onOpenBookmarks: () -> Unit = {},
    onLoadMoreBookmarks: () -> Unit,
    onToggleMute: (Long) -> Unit,
    onUpdateTalkativeness: (Long, Float, (Boolean) -> Unit) -> Unit,
    participantTalkativenessSaving: Map<Long, Float> = emptyMap(),
    participantMuteSaving: Set<Long> = emptySet(),
    participantRemoving: Set<Long> = emptySet(),
    onRemoveParticipant: (Long) -> Unit,
    onAddParticipant: () -> Unit,
    speakerTurnMode: String = "auto",
    onSpeakerTurnModeChange: (String) -> Unit,
    onClose: () -> Unit,
    onWorldSettingChanged: (String, Boolean) -> Unit,
    onSaveSessionWorldCredentials: (SessionWorldCredentialDraft) -> Unit,
    onWorldCredentialFieldsDirty: (Boolean) -> Unit,
    worldCredentialFieldsDirty: Boolean = false,
    worldCredentialsSaving: Boolean = false,
    worldSettingSaving: Boolean = false,
    onToggleEventResolved: (Long) -> Unit,
    onDeleteEventNode: (Long) -> Unit,
    onJumpToMemorySource: (Long, (Boolean) -> Unit) -> Boolean,
    onAddMemoryCorrection: (String, Long?) -> Unit,
    onEditMemoryCorrection: (SessionMemoryCorrectionEntity) -> Unit,
    onDeleteMemoryCorrection: (SessionMemoryCorrectionEntity) -> Unit,
    onRebuildContextMemory: () -> Unit,
    onClearContextMemory: (String) -> Unit,
    onContinueStorySummary: () -> Unit,
    onStopStorySummary: () -> Unit,
    onEditMemorySummary: (SessionMemorySegmentEntity, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onDeleteMemorySummary: (SessionMemorySegmentEntity) -> Unit = {},
    allowSessionThinkMax: Boolean = false,
    sessionThinkMaxEnabled: Boolean = false,
    sessionThinkMaxSaving: Boolean = false,
    sessionThinkMaxSaveError: String? = null,
    characterForcesThinkMax: Boolean = false,
    onSessionThinkMax: (Boolean) -> Unit,
    memorySegmentsHasMore: Boolean = false,
    memorySegmentsLoaded: Boolean = false,
    memorySegmentsLoading: Boolean = false,
    memorySegmentsLoadingMore: Boolean = false,
    memorySegmentsLoadError: String? = null,
    onLoadMoreMemorySummaries: () -> Unit = {},
    memorySummariesHasNewer: Boolean = false,
    onResetMemorySummaryWindow: () -> Unit = {},
    onOpenMemorySummaries: () -> Unit = {},
    memorySummaryEditSavedId: Long? = null,
    memorySummaryEditSavedText: String? = null,
    onResolveMemorySummaryEditor: suspend (Long, String) -> SessionMemorySegmentEntity? = { _, _ -> null },
    sessionId: Long? = null,
) {
    var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var pendingTab by rememberSaveable { mutableStateOf<Int?>(null) }
    // A source request may itself switch lines; it belongs to this open drawer until completion.
    var sourceOpeningId by remember(drawerOpen) { mutableStateOf<Long?>(null) }
    var sourceFailedId by remember(currentBranchId, drawerOpen) { mutableStateOf<Long?>(null) }
    var sourceError by remember(currentBranchId, drawerOpen) { mutableStateOf<String?>(null) }
    var sourceActive by remember(drawerOpen) { mutableStateOf(drawerOpen) }
    DisposableEffect(drawerOpen) { onDispose { sourceActive = false } }
    fun openSource(messageId: Long) {
        if (sourceOpeningId != null) return
        sourceOpeningId = messageId
        sourceFailedId = null
        sourceError = null
        if (!onJumpToMemorySource(messageId, result@{ opened ->
                if (!sourceActive || sourceOpeningId != messageId) return@result
                if (opened) onClose()
                else {
                    sourceOpeningId = null
                    sourceFailedId = messageId
                    sourceError = "原文不可用或加载失败"
                }
            })) {
            sourceOpeningId = null
            sourceFailedId = messageId
            sourceError = "当前正在生成或加载历史，请稍后重试"
        }
    }
    val tabs = listOf("角色", "世界", "记忆", "事件", "书签")

    LaunchedEffect(selectedTab, drawerOpen, sessionReady, currentBranchId,
        world?.encyclopediaId, world?.worldPrompt,
        encyclopediaFoundationLoaded) {
        if (selectedTab == 1 && drawerOpen && sessionReady) onOpenEncyclopediaFoundation()
        if (selectedTab == 3 && drawerOpen && sessionReady) onOpenEvents()
        if (selectedTab == 4 && drawerOpen && sessionReady) onOpenBookmarks()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(48.dp))
            Text("对话信息", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭") }
        }
        HorizontalDivider()
        com.mojing.app.ui.common.MoJingSegmentedTabs(tabs, selectedTab, { index ->
            if (index != selectedTab) {
                if (selectedTab == 1 && worldCredentialFieldsDirty) pendingTab = index
                else selectedTab = index
            }
        })
        if (selectedTab == 2 || selectedTab == 3) {
            if (sourceOpeningId != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在定位原文…", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sourceFailedId?.let { messageId ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sourceError.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { openSource(messageId) }) { Text("重试") }
                }
            }
        }
        when (selectedTab) {
            0 -> ParticipantsTab(
                participants,
                characterNames,
                onToggleMute,
                onUpdateTalkativeness,
                onRemoveParticipant,
                onAddParticipant,
                speakerTurnMode,
                onSpeakerTurnModeChange,
                isGenerating,
                characterAvatars = characterAvatars,
                characterSummaries = characterSummaries,
                onOpenCharacterState = onOpenCharacterState,
                participantTalkativenessSaving = participantTalkativenessSaving,
                participantMuteSaving = participantMuteSaving,
                participantRemoving = participantRemoving,
                world = world, onOpenWorld = { selectedTab = 1 },
            )
            1 -> WorldConfigTab(
                world = world,
                encyclopediaFoundation = encyclopediaFoundation,
                foundationLoaded = encyclopediaFoundationLoaded,
                foundationLoading = encyclopediaFoundationLoading,
                foundationLoadError = encyclopediaFoundationLoadError,
                onRetryFoundation = onRetryEncyclopediaFoundation,
                onWorldSettingChanged = onWorldSettingChanged,
                onSaveSessionWorldCredentials = onSaveSessionWorldCredentials,
                worldCredentialsSaving = worldCredentialsSaving,
                worldSettingSaving = worldSettingSaving,
                onCredentialFieldsDirty = onWorldCredentialFieldsDirty,
                allowSessionThinkMax = allowSessionThinkMax,
                sessionThinkMaxEnabled = sessionThinkMaxEnabled,
                sessionThinkMaxSaving = sessionThinkMaxSaving,
                sessionThinkMaxSaveError = sessionThinkMaxSaveError,
                characterForcesThinkMax = characterForcesThinkMax,
                onSessionThinkMax = onSessionThinkMax,
                isGenerating = isGenerating,
            )
            2 -> MemoryTab(
                memorySegments,
                memoryCorrections,
                memoryCorrectionPromptTrace,
                currentBranchId,
                isGenerating,
                onRebuildContextMemory,
                onClearContextMemory,
                ::openSource,
                onAddMemoryCorrection,
                onEditMemoryCorrection,
                onDeleteMemoryCorrection,
                contextMemoryText,
                memoryOperationRunning,
                contextMemoryStatus,
                manualCompactionRunning,
                manualCompactionChunk,
                onContinueStorySummary,
                onStopStorySummary,
                onEditMemorySummary = onEditMemorySummary,
                onResolveMemorySummaryEditor = onResolveMemorySummaryEditor,
                memorySummaryEditSavedId = memorySummaryEditSavedId,
                memorySummaryEditSavedText = memorySummaryEditSavedText,
                onDeleteMemorySummary = onDeleteMemorySummary,
                contextMemoryLoaded = contextMemoryLoaded,
                contextMemoryClearError = contextMemoryClearError,
                onRetryClearContextMemory = onRetryClearContextMemory,
                contextMemoryLoading = contextMemoryLoading,
                contextMemoryLoadError = contextMemoryLoadError,
                onOpenContextMemory = onOpenContextMemory,
                onRetryContextMemory = onRetryContextMemory,
                hasOlderSummaries = memorySegmentsHasMore,
                summariesLoaded = memorySegmentsLoaded,
                summariesLoading = memorySegmentsLoading,
                olderSummariesLoading = memorySegmentsLoadingMore,
                olderSummariesError = memorySegmentsLoadError,
                onLoadOlderSummaries = onLoadMoreMemorySummaries,
                summariesHasNewer = memorySummariesHasNewer,
                onResetSummaryWindow = onResetMemorySummaryWindow,
                onOpenSummaries = onOpenMemorySummaries,
                sourceNavigationBusy = sourceOpeningId != null,
                correctionsLoaded = memoryCorrectionsLoaded,
                correctionsLoading = memoryCorrectionsLoading,
                correctionsHasMore = memoryCorrectionsHasMore,
                correctionsLoadError = memoryCorrectionsLoadError,
                drawerOpen = drawerOpen,
                sessionReady = sessionReady,
                sessionId = sessionId,
                onOpenCorrections = onOpenMemoryCorrections,
                onLoadMoreCorrections = onLoadMoreMemoryCorrections,
            )
            3 -> TimelineTab(
                eventNodes,
                onToggleEventResolved,
                onDeleteEventNode,
                ::openSource,
                busyIds = eventBusyIds,
                actionErrors = eventActionErrors,
                currentBranchId = currentBranchId,
                loaded = eventNodesLoaded,
                sessionReady = sessionReady,
                query = eventQuery,
                resolvedFilter = eventResolvedFilter,
                onQueryChange = onEventQueryChange,
                onResolvedFilterChange = onEventResolvedFilterChange,
                hasNewerEvents = eventNodesHasNewer,
                onResetWindow = onResetEventWindow,
                hasOlderEvents = eventNodesHasMore,
                olderEventsLoading = eventNodesLoadingMore,
                olderEventsError = eventNodesLoadError,
                onLoadOlderEvents = onLoadMoreEventNodes,
                sourceNavigationBusy = sourceOpeningId != null,
            )
            4 -> BookmarksTab(
                bookmarks, bookmarkPreviews, bookmarkNoteDrafts, bookmarkNoteErrors, bookmarkNoteSavingIds,
                onJumpToBookmark, onRemoveBookmark, onBookmarkNoteDraftChange, onSaveBookmarkNote,
                bookmarkBusyIds, bookmarkLocatingId, bookmarksLoaded, bookmarksHasMore, bookmarksLoadingMore,
                bookmarksLoadError, onLoadMoreBookmarks,
                query = bookmarkQuery,
                onQueryChange = onBookmarkQueryChange,
                hasNewer = bookmarksHasNewer,
                onResetWindow = onResetBookmarkWindow,
                sessionReady = sessionReady,
                sessionId = sessionId,
            )
        }
    }
    pendingTab?.let { target ->
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { pendingTab = null },
            title = { Text("世界配置尚未保存") },
            text = { Text(if (worldCredentialsSaving) "线路正在保存，请稍候再切换资料。" else "继续编辑，或放弃本次修改后切换资料。") },
            confirmButton = {
                TextButton(onClick = { pendingTab = null }) { Text("继续编辑") }
            },
            dismissButton = {
                TextButton(enabled = !worldCredentialsSaving, onClick = {
                    pendingTab = null
                    onWorldCredentialFieldsDirty(false)
                    selectedTab = target
                }) { Text("放弃修改并切换") }
            },
        )
    }
}

@Composable
fun ParticipantsTab(
    participants: List<SessionParticipantEntity>,
    characterNames: Map<Long, String> = emptyMap(),
    onToggleMute: (Long) -> Unit,
    onUpdateTalkativeness: (Long, Float, (Boolean) -> Unit) -> Unit,
    onRemoveParticipant: (Long) -> Unit,
    onAddParticipant: () -> Unit,
    speakerTurnMode: String = "auto",
    onSpeakerTurnModeChange: (String) -> Unit,
    isGenerating: Boolean = false,
    characterAvatars: Map<Long, String> = emptyMap(),
    characterSummaries: Map<Long, String> = emptyMap(),
    onOpenCharacterState: (Long) -> Unit = {},
    world: SessionWorldEntity? = null,
    onOpenWorld: () -> Unit = {},
    participantTalkativenessSaving: Map<Long, Float> = emptyMap(),
    participantMuteSaving: Set<Long> = emptySet(),
    participantRemoving: Set<Long> = emptySet(),
) {
    var schedulingExpanded by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "controls") {
            TextButton(onClick = { schedulingExpanded = !schedulingExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text("角色 (${participants.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Outlined.Tune, "发言设置")
            }
            if (schedulingExpanded) {
            Column(Modifier.fillMaxWidth()) {
                Text("发言调度", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                if (isGenerating) {
                    Text(
                        "回复生成期间暂不可调整参与角色和发言方式",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = speakerTurnMode != "manual",
                        onClick = { onSpeakerTurnModeChange("auto") },
                        enabled = !isGenerating,
                        label = { Text("按发言率") },
                    )
                    FilterChip(
                        selected = speakerTurnMode == "manual",
                        onClick = { onSpeakerTurnModeChange("manual") },
                        enabled = !isGenerating,
                        label = { Text("手动指定") },
                    )
                }
                if (speakerTurnMode == "manual") {
                    Text(
                        "发送前在输入栏上方点选要让谁回复；不选则只发送你的消息。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                TextButton(
                    onClick = onAddParticipant,
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                ) {
                    Icon(Icons.Outlined.PersonAdd, null)
                    Spacer(Modifier.width(8.dp))
                    Text("添加角色到对话")
                }
                HorizontalDivider()
            }
            }
        }
        if (participants.isEmpty()) {
            item(key = "empty") {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("请先添加至少一个角色", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onAddParticipant, enabled = !isGenerating) { Text("添加角色到对话") }
                    }
                }
            }
        } else {
            items(participants, key = { it.id }) { p ->
                var expanded by rememberSaveable(p.id) { mutableStateOf(false) }
                val name = participantDisplayName(p.characterId, characterNames)
                val strategyLabel = participantSpeakerStrategyLabel(p.speakerStrategy)
                var talkativenessDraft by remember(p.id) { mutableFloatStateOf(p.talkativeness) }
                val pendingTalkativeness = participantTalkativenessSaving[p.id]
                val isSavingTalkativeness = pendingTalkativeness != null
                val isSavingMute = p.id in participantMuteSaving
                val isRemoving = p.id in participantRemoving
                val isSavingParticipant = isSavingTalkativeness || isSavingMute || isRemoving
                LaunchedEffect(p.talkativeness, pendingTalkativeness) {
                    talkativenessDraft = pendingTalkativeness ?: p.talkativeness
                }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.mojing.app.ui.common.MoJingCoverImage(characterAvatars[p.characterId], Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape), name,
                                shape = androidx.compose.foundation.shape.CircleShape, person = true)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    characterSummaries[p.characterId]?.takeIf { it.isNotBlank() } ?: buildString {
                                        if (speakerTurnMode == "manual") {
                                            append("等待手动选择")
                                        } else {
                                            append("$strategyLabel · 发言率 ${(talkativenessDraft * 100).toInt()}%")
                                        }
                                        if (isSavingTalkativeness) append(" · 保存中…")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { onOpenCharacterState(p.characterId) },
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = "查看角色状态:${p.characterId}"
                                },
                            ) {
                                Icon(Icons.Outlined.AutoAwesome, "$name 角色状态", modifier = Modifier.size(21.dp))
                            }
                            IconButton(onClick = { expanded = !expanded }) { Icon(
                                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "$name 发言设置") }
                            if (expanded) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (p.muted) "暂停" else "参与",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Switch(
                                    checked = !p.muted,
                                    onCheckedChange = { onToggleMute(p.id) },
                                    enabled = !isGenerating && !isSavingParticipant,
                                    modifier = Modifier.semantics {
                                        contentDescription = "$name 发言状态"
                                        stateDescription = if (p.muted) "已暂停" else "参与中"
                                    },
                                )
                            }
                            IconButton(
                                onClick = { onRemoveParticipant(p.id) },
                                enabled = !isGenerating && !isSavingParticipant,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.PersonRemove,
                                    "从对话移除$name",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                            }
                        }
                        if (expanded && speakerTurnMode != "manual") {
                            Slider(
                                value = talkativenessDraft,
                                onValueChange = { talkativenessDraft = it },
                                onValueChangeFinished = {
                                    if (!isSavingTalkativeness) {
                                        onUpdateTalkativeness(p.id, talkativenessDraft) { saved ->
                                            if (!saved) talkativenessDraft = p.talkativeness
                                        }
                                    }
                                },
                                valueRange = 0.05f..1f,
                                enabled = !isSavingParticipant && !isGenerating,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = "$name 发言率" },
                            )
                            if (isSavingTalkativeness) {
                                Text("发言率保存中…", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (isSavingMute) {
                            Text("参与状态保存中…", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isRemoving) {
                            Text("正在移除角色…", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        world?.let { currentWorld ->
            item(key = "world-summary") {
                Surface(onClick = onOpenWorld, Modifier.fillMaxWidth().padding(16.dp),
                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("世界设定", style = MaterialTheme.typography.titleSmall)
                        Text(currentWorld.worldPrompt.ifBlank { "查看当前故事的世界与叙事设置" }, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "story-style") {
                com.mojing.app.ui.common.MoJingOptionRow("叙事设置", currentWorld.gameplayMode,
                    onOpenWorld, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
internal fun rememberWorldRouteField(worldId: Long, persisted: String): MutableState<String> {
    // Saved inputs do not validate rememberSaveable keys after recreation. Carry the
    // persisted baseline too, so a different world or an external save resets the draft.
    val saver = androidx.compose.runtime.saveable.listSaver<MutableState<String>, Any>(
        save = { listOf(worldId, persisted, it.value) },
        restore = { saved ->
            mutableStateOf(if (saved[0] == worldId && saved[1] == persisted) saved[2] as String else persisted)
        },
    )
    return rememberSaveable(worldId, persisted, saver = saver) { mutableStateOf(persisted) }
}

@Composable
fun WorldConfigTab(
    world: SessionWorldEntity?,
    encyclopediaFoundation: String = "",
    foundationLoaded: Boolean = true,
    foundationLoading: Boolean = false,
    foundationLoadError: String? = null,
    onRetryFoundation: () -> Unit = {},
    onWorldSettingChanged: (String, Boolean) -> Unit,
    onSaveSessionWorldCredentials: (SessionWorldCredentialDraft) -> Unit,
    onCredentialFieldsDirty: (Boolean) -> Unit,
    worldCredentialsSaving: Boolean = false,
    worldSettingSaving: Boolean = false,
    allowSessionThinkMax: Boolean = false,
    sessionThinkMaxEnabled: Boolean = false,
    sessionThinkMaxSaving: Boolean = false,
    sessionThinkMaxSaveError: String? = null,
    characterForcesThinkMax: Boolean = false,
    onSessionThinkMax: (Boolean) -> Unit,
    isGenerating: Boolean = false,
) {
    if (world == null) {
        Text(
            "未绑定世界数据",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        return
    }
    val w = world
    var llmKey by rememberWorldRouteField(w.id, w.sessionLlmApiKey)
    var llmBase by rememberWorldRouteField(w.id, w.sessionLlmBaseUrl)
    var imgKey by rememberWorldRouteField(w.id, w.sessionImageApiKey)
    var imgBase by rememberWorldRouteField(w.id, w.sessionImageBaseUrl)
    var imgModel by rememberWorldRouteField(w.id, w.sessionImageModel)
    var voiceKey by rememberWorldRouteField(w.id, w.sessionVoiceApiKey)
    var voiceBase by rememberWorldRouteField(w.id, w.sessionVoiceBaseUrl)
    var voiceModel by rememberWorldRouteField(w.id, w.sessionVoiceModel)
    var voiceSpeech by rememberWorldRouteField(w.id, w.sessionVoiceSpeechVoice)
    var voicePreset by rememberWorldRouteField(w.id, w.sessionVoicePresetPrefixModel)

    val credentialDirty =
        llmKey.trim() != w.sessionLlmApiKey.trim() ||
            llmBase.trim() != w.sessionLlmBaseUrl.trim() ||
            imgKey.trim() != w.sessionImageApiKey.trim() ||
            imgBase.trim() != w.sessionImageBaseUrl.trim() ||
            imgModel.trim() != w.sessionImageModel.trim() ||
            voiceKey.trim() != w.sessionVoiceApiKey.trim() ||
            voiceBase.trim() != w.sessionVoiceBaseUrl.trim() ||
            voiceModel.trim() != w.sessionVoiceModel.trim() ||
            voiceSpeech.trim() != w.sessionVoiceSpeechVoice.trim() ||
            voicePreset.trim() != w.sessionVoicePresetPrefixModel.trim()
    LaunchedEffect(credentialDirty) { onCredentialFieldsDirty(credentialDirty) }

    fun emitSave() {
        onSaveSessionWorldCredentials(
            SessionWorldCredentialDraft(
                sessionLlmApiKey = llmKey,
                sessionLlmBaseUrl = llmBase,
                sessionImageApiKey = imgKey,
                sessionImageBaseUrl = imgBase,
                sessionImageModel = imgModel,
                sessionVoiceApiKey = voiceKey,
                sessionVoiceBaseUrl = voiceBase,
                sessionVoiceModel = voiceModel,
                sessionVoiceSpeechVoice = voiceSpeech,
                sessionVoicePresetPrefixModel = voicePreset,
            ),
        )
    }

    var routeDetailsOpen by rememberSaveable(w.id) { mutableStateOf(false) }
    var foundationExpanded by rememberSaveable(w.id, w.encyclopediaId, w.templateId) { mutableStateOf(false) }
    val worldScrollState = key(w.id, w.encyclopediaId, w.templateId) { rememberScrollState() }

    // Do not measure restored scroll against the temporary, shorter world body.
    // Keep only the existing scroll state while the authoritative foundation is read.
    if (w.encyclopediaId != null && !foundationLoaded) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (foundationLoadError != null) {
                Text(foundationLoadError, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetryFoundation, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("重试读取")
                }
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在读取百科基础设定…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(worldScrollState)
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = if (routeDetailsOpen) 92.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isGenerating) {
            Text(
                "可先编辑线路草稿；回复完成或停止后再保存和调整本场玩法",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (credentialDirty && !routeDetailsOpen) {
            AssistChip(onClick = {}, enabled = false, label = { Text("未保存") })
        }
        Text("本场玩法", style = MaterialTheme.typography.titleMedium)
        if (worldSettingSaving) {
            Text("正在保存…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            w.gameplayMode,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("思考 / Max", style = MaterialTheme.typography.labelMedium)
        if (characterForcesThinkMax) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("本会话", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = true, onCheckedChange = {}, enabled = false)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("本会话思考/Max", style = MaterialTheme.typography.bodyMedium)
                    if (sessionThinkMaxSaving) {
                        Text("正在保存…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(
                    checked = sessionThinkMaxEnabled,
                    modifier = Modifier.semantics { contentDescription = "本会话思考/Max开关" },
                    onCheckedChange = onSessionThinkMax,
                    enabled = !isGenerating && !worldSettingSaving && !worldCredentialsSaving && !sessionThinkMaxSaving && (allowSessionThinkMax || sessionThinkMaxEnabled),
                )
            }
            sessionThinkMaxSaveError?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { onSessionThinkMax(!sessionThinkMaxEnabled) },
                    enabled = !isGenerating && !worldSettingSaving && !worldCredentialsSaving && !sessionThinkMaxSaving,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("重试保存")
                }
            }
        }
        if (w.encyclopediaId != null && (!foundationLoaded || foundationLoadError != null)) {
            if (foundationLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在读取百科基础设定…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (foundationLoadError != null) {
                Text(foundationLoadError, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetryFoundation, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("重试读取")
                }
            }
        }
        val sceneText = remember(w.worldPrompt, encyclopediaFoundation) {
            listOf(w.worldPrompt.trim(), encyclopediaFoundation.trim())
                .filter(String::isNotBlank).distinct().joinToString("\n\n")
        }
        if (sceneText.isNotBlank()) {
            Text("本场基础设定", style = MaterialTheme.typography.titleSmall)
            Text(sceneText, maxLines = if (foundationExpanded) Int.MAX_VALUE else 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { foundationExpanded = !foundationExpanded }) { Text(if (foundationExpanded) "收起设定" else "展开设定") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("旁白解说")
                if (w.narratorEnabled) {
                    Text("旁白：${w.narratorName}", style = MaterialTheme.typography.labelSmall)
                }
            }
            Switch(
                checked = w.narratorEnabled,
                modifier = Modifier.semantics { contentDescription = "旁白解说开关" },
                onCheckedChange = { onWorldSettingChanged("narratorEnabled", it) },
                enabled = !isGenerating && !worldSettingSaving && !worldCredentialsSaving,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("每轮选项")
                if (w.choiceGenerationEnabled) {
                    Text("最多 ${w.maxChoiceCount} 项", style = MaterialTheme.typography.labelSmall)
                }
            }
            Switch(
                checked = w.choiceGenerationEnabled,
                modifier = Modifier.semantics { contentDescription = "每轮选项开关" },
                onCheckedChange = { onWorldSettingChanged("choiceGenerationEnabled", it) },
                enabled = !isGenerating && !worldSettingSaving && !worldCredentialsSaving,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("反作弊", modifier = Modifier.weight(1f))
            Switch(
                checked = w.antiCheatEnabled,
                modifier = Modifier.semantics { contentDescription = "反作弊开关" },
                onCheckedChange = { onWorldSettingChanged("antiCheatEnabled", it) },
                enabled = !isGenerating && !worldSettingSaving && !worldCredentialsSaving,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("自动沉淀百科")
                if (w.autoSedimentEnabled) {
                    Text("新事实存入百科，可查看与确认", style = MaterialTheme.typography.labelSmall)
                }
            }
            Switch(
                checked = w.autoSedimentEnabled,
                modifier = Modifier.semantics { contentDescription = "自动沉淀百科开关" },
                onCheckedChange = { onWorldSettingChanged("autoSedimentEnabled", it) },
                enabled = !isGenerating && !worldSettingSaving && !worldCredentialsSaving,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("回复内自动配图", modifier = Modifier.weight(1f))
            Switch(
                checked = w.autoCharacterImageGen,
                modifier = Modifier.semantics { contentDescription = "回复内自动配图开关" },
                onCheckedChange = { onWorldSettingChanged("autoCharacterImageGen", it) },
                enabled = !isGenerating && !worldSettingSaving && !worldCredentialsSaving,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("回复内自动配音", modifier = Modifier.weight(1f))
            Switch(
                checked = w.autoCharacterSpeech,
                modifier = Modifier.semantics { contentDescription = "回复内自动配音开关" },
                onCheckedChange = { onWorldSettingChanged("autoCharacterSpeech", it) },
                enabled = !isGenerating && !worldSettingSaving && !worldCredentialsSaving,
            )
        }

        HorizontalDivider()
        TextButton(
            onClick = { routeDetailsOpen = !routeDetailsOpen },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Tune, null)
            Spacer(Modifier.width(8.dp))
            Text("专用线路", modifier = Modifier.weight(1f))
            Icon(if (routeDetailsOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (routeDetailsOpen) {
            Text("对话 / 旁白 / 记忆", style = MaterialTheme.typography.labelMedium)
            Text("此处覆盖 Key 和地址，模型使用公共模型设置；需要独立模型时，在输入框下方选择平台和模型。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = llmKey,
                onValueChange = { llmKey = it },
                label = { Text("对话 API Key 覆盖") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !worldCredentialsSaving,
            )
            OutlinedTextField(
                value = llmBase,
                onValueChange = { llmBase = it },
                label = { Text("对话 URL") },
                placeholder = { Text("与上方 Key 配套填写；两项均空时继承") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !worldCredentialsSaving,
            )
            Text("生图", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = imgKey,
                onValueChange = { imgKey = it },
                label = { Text("配图 API Key 覆盖") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !worldCredentialsSaving,
            )
            OutlinedTextField(
                value = imgBase,
                onValueChange = { imgBase = it },
                label = { Text("配图 URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !worldCredentialsSaving,
            )
            OutlinedTextField(
                value = imgModel,
                onValueChange = { imgModel = it },
                label = { Text("生图模型 id 覆盖") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !worldCredentialsSaving,
            )
            Text("朗读引擎和音色请在对话输入框下方的「语音」中选择。", style = MaterialTheme.typography.bodySmall)
        }

    }
    if (routeDetailsOpen) {
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (worldCredentialsSaving) "正在保存线路…" else if (credentialDirty) "线路草稿未保存" else "线路配置已保存",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (credentialDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { emitSave() },
                    enabled = !isGenerating && !worldSettingSaving && !worldCredentialsSaving,
                ) { Text("保存线路") }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryTab(
    segments: List<SessionMemorySegmentEntity>,
    corrections: List<SessionMemoryCorrectionEntity>,
    promptTrace: MemoryCorrectionPromptTrace?,
    currentBranchId: String,
    isGenerating: Boolean,
    onRebuildContextMemory: () -> Unit,
    onClearContextMemory: (String) -> Unit,
    onJumpToSource: (Long) -> Unit,
    onAddCorrection: (String, Long?) -> Unit,
    onEditCorrection: (SessionMemoryCorrectionEntity) -> Unit,
    onDeleteCorrection: (SessionMemoryCorrectionEntity) -> Unit,
    contextMemoryText: String = "",
    memoryOperationRunning: Boolean = false,
    contextMemoryStatus: ContextMemoryStatus = ContextMemoryStatus.IDLE,
    manualCompactionRunning: Boolean = false,
    manualCompactionChunk: Int? = null,
    onContinueStorySummary: () -> Unit,
    onStopStorySummary: () -> Unit,
    onEditMemorySummary: (SessionMemorySegmentEntity, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onDeleteMemorySummary: (SessionMemorySegmentEntity) -> Unit = {},
    contextMemoryLoaded: Boolean = true,
    contextMemoryLoading: Boolean = false,
    contextMemoryLoadError: String? = null,
    contextMemoryClearError: String? = null,
    onOpenContextMemory: () -> Unit = {},
    onRetryContextMemory: () -> Unit = {},
    hasOlderSummaries: Boolean = false,
    summariesLoaded: Boolean = true,
    summariesLoading: Boolean = false,
    olderSummariesLoading: Boolean = false,
    olderSummariesError: String? = null,
    onLoadOlderSummaries: () -> Unit = {},
    summariesHasNewer: Boolean = false,
    onResetSummaryWindow: () -> Unit = {},
    onOpenSummaries: () -> Unit = {},
    sourceNavigationBusy: Boolean = false,
    correctionsLoaded: Boolean = true,
    correctionsLoading: Boolean = false,
    correctionsHasMore: Boolean = false,
    correctionsLoadError: String? = null,
    drawerOpen: Boolean = true,
    sessionReady: Boolean = true,
    onOpenCorrections: () -> Unit = {},
    onLoadMoreCorrections: () -> Unit = {},
    onRetryClearContextMemory: (String) -> Unit = {},
    memorySummaryEditSavedId: Long? = null,
    memorySummaryEditSavedText: String? = null,
    onResolveMemorySummaryEditor: suspend (Long, String) -> SessionMemorySegmentEntity? = { _, _ -> null },
    sessionId: Long? = null,
) {
    var memoryUiBranchId by rememberSaveable { mutableStateOf(currentBranchId) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var showCorrectionTrace by remember(currentBranchId) { mutableStateOf(false) }
    var clearTargetBranchId by remember(currentBranchId) { mutableStateOf<String?>(null) }
    var editingSegmentId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editOriginalSummary by rememberSaveable { mutableStateOf("") }
    var editText by rememberSaveable { mutableStateOf("") }
    var submittedSummary by rememberSaveable { mutableStateOf<String?>(null) }
    // A recreated child chat briefly reports main before its saved branch is ready.
    // Keep the saved scope through that phase; never edit an inherited summary.
    LaunchedEffect(sessionReady, currentBranchId) {
        if (sessionReady && memoryUiBranchId != currentBranchId) {
            memoryUiBranchId = currentBranchId
            section = 0
            editingSegmentId = null
            editOriginalSummary = ""
            editText = ""
            submittedSummary = null
        }
    }
    var restoredEditSegment by remember(editingSegmentId, currentBranchId) { mutableStateOf<SessionMemorySegmentEntity?>(null) }
    var editorRestoreError by remember(editingSegmentId, currentBranchId) { mutableStateOf<String?>(null) }
    var editorRestoreAttempt by remember(editingSegmentId, currentBranchId) { mutableIntStateOf(0) }
    val loadedEditTarget = segments.firstOrNull {
        sessionReady && memoryUiBranchId == currentBranchId && summariesLoaded &&
            it.id == editingSegmentId && it.branchId == currentBranchId
    }
    LaunchedEffect(editingSegmentId, currentBranchId, sessionReady, summariesLoaded, editorRestoreAttempt, loadedEditTarget != null) {
        val id = editingSegmentId
        if (id != null && sessionReady && summariesLoaded && memoryUiBranchId == currentBranchId && loadedEditTarget == null) {
            editorRestoreError = null
            try {
                restoredEditSegment = onResolveMemorySummaryEditor(id, currentBranchId)
                if (restoredEditSegment == null) editorRestoreError = "这段摘要已不可编辑，输入仍保留"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { editorRestoreError = "摘要读取失败，输入仍保留，请重试" }
        }
    }
    val editTarget = (loadedEditTarget ?: restoredEditSegment?.takeIf {
        sessionReady && summariesLoaded && memoryUiBranchId == currentBranchId &&
            it.id == editingSegmentId && it.branchId == currentBranchId
    })?.copy(summary = editOriginalSummary)
    LaunchedEffect(memorySummaryEditSavedId, memorySummaryEditSavedText, memoryOperationRunning, editingSegmentId) {
        if (!memoryOperationRunning && submittedSummary != null &&
            memorySummaryEditSavedId == editingSegmentId && memorySummaryEditSavedText == submittedSummary) {
            editingSegmentId = null
            submittedSummary = null
        }
    }
    var deleteTarget by remember(currentBranchId) { mutableStateOf<SessionMemorySegmentEntity?>(null) }
    val currentCorrectionTrace = promptTrace?.takeIf { it.branchId == currentBranchId }
    LaunchedEffect(currentBranchId, section, drawerOpen, sessionReady) {
        if (section == 0 && drawerOpen && sessionReady) onOpenContextMemory()
        if (section == 1 && drawerOpen && sessionReady) onOpenCorrections()
        if (section == 2 && drawerOpen && sessionReady) onOpenSummaries()
    }
    // Keep memory reading intent untouched while the saved child scope is
    // still being restored; loaded rows may briefly belong to the previous line.
    if ((section == 1 || section == 2 || (section == 0 && sessionId != null && sessionId > 0L)) &&
        (!sessionReady || memoryUiBranchId != currentBranchId)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(when (section) {
                1 -> "正在读取当前故事线用户纠正…"
                2 -> "正在读取当前故事线摘要…"
                else -> "正在读取当前故事线长期记忆…"
            },
                style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    if (section == 2 && !summariesLoaded) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (summariesLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(olderSummariesError ?: "正在读取当前故事线摘要…")
            if (!summariesLoading && olderSummariesError != null)
                TextButton(onClick = onOpenSummaries) { Text("重试读取") }
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("长期记忆", if (correctionsLoaded) "用户纠正 ${corrections.size}${if (correctionsHasMore) "+" else ""}" else "用户纠正", "自动摘要")
                .forEachIndexed { index, label ->
                FilterChip(selected = section == index, onClick = { section = index }, label = { Text(label) })
            }
        }
        HorizontalDivider()
        key(currentBranchId, section) {
        val memoryListState = androidx.compose.foundation.lazy.rememberLazyListState()
        val memoryListScope = rememberCoroutineScope()
        LazyColumn(state = memoryListState, modifier = Modifier.fillMaxWidth()) {
            if (section == 0) item {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("长期记忆", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onRebuildContextMemory,
                            enabled = !isGenerating && !memoryOperationRunning,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "重建当前会话记忆")
                            Spacer(Modifier.width(8.dp))
                            Text("重建记忆")
                        }
                        OutlinedButton(
                            onClick = { clearTargetBranchId = currentBranchId },
                            enabled = !isGenerating && !memoryOperationRunning,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空当前会话记忆")
                            Spacer(Modifier.width(8.dp))
                            Text("清空记忆")
                        }
                    }

                    if (memoryOperationRunning && !manualCompactionRunning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    if (contextMemoryStatus.message.isNotEmpty() && !memoryOperationRunning) {
                        Text(
                            text = contextMemoryStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    contextMemoryClearError?.let { error ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            TextButton(
                                onClick = { onRetryClearContextMemory(currentBranchId) },
                                enabled = !isGenerating && !memoryOperationRunning,
                            ) { Text("重试清空") }
                        }
                    }
                    if (!contextMemoryLoaded) {
                        if (contextMemoryLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在读取长期记忆…", modifier = Modifier.padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (contextMemoryLoadError != null) {
                            Text(contextMemoryLoadError, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetryContextMemory, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("重试读取")
                            }
                        }
                    } else {
                        if (contextMemoryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (contextMemoryLoadError != null) {
                            Text(contextMemoryLoadError, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetryContextMemory, enabled = !contextMemoryLoading,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("重试读取") }
                        }
                        ExpandableMemoryText(contextMemoryText.ifBlank { "暂无长期记忆，可从当前故事线重建。" },
                            collapsedLines = 6,
                            restorationKey = sessionId?.takeIf { it > 0L }?.let { "context:$it:$currentBranchId" })
                    }
                }
            }
            if (section == 1 && !correctionsLoaded) item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (correctionsLoadError == null) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(if (correctionsLoading) "正在读取用户纠正…" else "准备读取用户纠正…",
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(correctionsLoadError, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onOpenCorrections, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("重试加载")
                        }
                    }
                }
            }
            if (section == 1 && correctionsLoaded) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("用户纠正", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Button(
                            enabled = !isGenerating,
                            onClick = { onAddCorrection("", null) },
                        ) { Text("新增") }
                    }
                    Text(
                        "纠正会优先注入后续角色回复和旁白。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                if (corrections.isEmpty()) {
                    item { Text("暂无用户纠正", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(corrections, key = { "correction:${it.id}" }) { correction ->
                        Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(if (correction.branchId == null) "整个对话" else "仅当前故事线",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                ExpandableMemoryText(correction.content,
                                    restorationKey = "correction:${correction.sessionId}:$currentBranchId:${correction.id}")
                                correction.sourceMessageId?.let { sourceId ->
                                    TextButton(
                                        enabled = !isGenerating && !sourceNavigationBusy,
                                        onClick = { onJumpToSource(sourceId) },
                                    ) { Text("查看来源") }
                                }
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(enabled = !isGenerating, onClick = { onEditCorrection(correction) }) { Text("编辑") }
                                    TextButton(enabled = !isGenerating, onClick = { onDeleteCorrection(correction) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        TextButton(onClick = { showCorrectionTrace = !showCorrectionTrace }) {
                            Text(if (showCorrectionTrace) "收起提示依据" else "查看最近一次提示依据")
                        }
                        if (showCorrectionTrace) {
                            if (currentCorrectionTrace == null) {
                                Text(
                                    "本故事线尚未构造可追踪的角色或旁白请求",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            } else {
                                val responderType = if (currentCorrectionTrace.responderType == "narrator") "旁白" else "角色"
                                Text(
                                    "$responderType · ${currentCorrectionTrace.responderLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Text("本轮采用 ${currentCorrectionTrace.corrections.size} 条用户纠正",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (currentCorrectionTrace.corrections.isEmpty()) {
                                    Text("本轮未采用用户纠正", modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
                if (showCorrectionTrace && currentCorrectionTrace != null) {
                    items(count = currentCorrectionTrace.corrections.size,
                        key = { "correction-trace:$it" }) { index ->
                        val correction = currentCorrectionTrace.corrections[index]
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Text("${index + 1}. ${if (correction.branchId == null) "全会话" else "本分支"} · ${correction.content}",
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (correction.sourceMessageId != null) Text("已关联原文",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    if (showCorrectionTrace && currentCorrectionTrace != null) Text(
                        "用户纠正在自动记忆之前注入；这里只表示本机已构造提示，不代表模型已成功回复。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    HorizontalDivider()
                }
                if (correctionsHasMore || correctionsLoading || correctionsLoadError != null) item {
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (correctionsLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        correctionsLoadError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        if (!correctionsLoading && (correctionsHasMore || correctionsLoadError != null)) {
                            TextButton(onClick = onLoadMoreCorrections, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (correctionsLoadError == null) "加载较早纠正" else "重试加载")
                            }
                        }
                    }
                }
            }
            if (section == 2) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Text("自动摘要", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "按当前故事线从原文整理。完成一批后才会保存摘要。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (segments.isNotEmpty()) Text(
                            "已显示 ${segments.size} 段${if (hasOlderSummaries) " · 可继续查看更早摘要" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (summariesHasNewer) TextButton(
                            enabled = !isGenerating && !memoryOperationRunning && !summariesLoading && !olderSummariesLoading,
                            onClick = { memoryListScope.launch { memoryListState.scrollToItem(0); onResetSummaryWindow() } },
                        ) { Text("回到最近摘要") }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onContinueStorySummary,
                                enabled = !isGenerating && !memoryOperationRunning,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("继续整理")
                            }
                            if (manualCompactionRunning) {
                                TextButton(onClick = onStopStorySummary, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("停止")
                                }
                            }
                        }
                        if (manualCompactionRunning) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                            Text(
                                manualCompactionChunk?.let { "正在整理第 $it 段，停止后可继续" } ?: "正在检查待整理的对话…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                if (!summariesLoaded) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            if (summariesLoading) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("正在读取摘要…", modifier = Modifier.padding(top = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (olderSummariesError != null) {
                                Text(olderSummariesError, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onOpenSummaries) { Text("重试读取") }
                            }
                        }
                    }
                } else if (segments.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            if (olderSummariesError == null) Text("暂无摘要。对话积累后会自动整理，也可点“继续整理”。原文始终保留。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else {
                                Text(olderSummariesError, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onLoadOlderSummaries, enabled = !summariesLoading) { Text("重试读取") }
                            }
                        }
                    }
                } else {
                    items(segments, key = { "summary:${it.id}" }) { segment ->
                        val inherited = segment.branchId != currentBranchId
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("剧情摘要", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    if (inherited) Text("继承自 ${segment.branchId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(segment.emotionalTone, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                                ExpandableMemoryText(segment.summary,
                                    restorationKey = "summary:${segment.sessionId}:$currentBranchId:${segment.id}")
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(
                                        enabled = !inherited && !isGenerating && !memoryOperationRunning,
                                        onClick = {
                                            editingSegmentId = segment.id
                                            editOriginalSummary = segment.summary
                                            editText = segment.summary
                                            submittedSummary = null
                                        },
                                    ) { Text("编辑") }
                                    TextButton(
                                        enabled = !inherited && !isGenerating && !memoryOperationRunning,
                                        onClick = { deleteTarget = segment },
                                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                }
                                if (inherited) Text("继承摘要不能直接修改；可用“纠正这段记忆”建立当前线修正。",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(
                                    enabled = !isGenerating,
                                    onClick = { onAddCorrection(segment.summary, segment.startMessageId.takeIf { it > 0L }) },
                                ) { Text("纠正这段记忆") }
                                val facts = try { Gson().fromJson(segment.keyFactsJson, List::class.java).orEmpty() } catch (_: Exception) { emptyList<Any>() }
                                facts.take(3).forEach { fact -> ExpandableMemoryText("• $fact", collapsedLines = 2) }
                                val source = segment.sourceReference()
                                Spacer(Modifier.height(4.dp))
                                if (source == null) {
                                    Text(
                                        "旧记忆未记录原文范围",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(source.label, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        val endMessageId = source.endMessageId
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            if (endMessageId == null) {
                                                TextButton(enabled = !isGenerating && !sourceNavigationBusy,
                                                    onClick = { onJumpToSource(source.startMessageId) }) { Text("查看原文") }
                                            } else {
                                                TextButton(enabled = !isGenerating && !sourceNavigationBusy,
                                                    onClick = { onJumpToSource(source.startMessageId) }) { Text("查看起始原文") }
                                                TextButton(enabled = !isGenerating && !sourceNavigationBusy,
                                                    onClick = { onJumpToSource(endMessageId) }) { Text("查看结束原文") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (hasOlderSummaries || olderSummariesLoading || olderSummariesError != null) item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (olderSummariesError != null) Text(olderSummariesError, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onLoadOlderSummaries,
                                enabled = !olderSummariesLoading && !summariesLoading &&
                                    !isGenerating && !memoryOperationRunning,
                                modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (olderSummariesLoading) "正在读取更早摘要…" else if (olderSummariesError != null) "重试读取" else "查看更早摘要")
                            }
                        }
                    }
                }
            }
        }
        clearTargetBranchId?.let { targetBranchId ->
            AlertDialog(
                onDismissRequest = { if (!memoryOperationRunning) clearTargetBranchId = null },
                title = { Text("清空长期记忆") },
                text = { Text("确认清空“${if (targetBranchId == "main") "主线剧情" else "当前故事线"}”的长期记忆？此操作不会删除原始对话。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            clearTargetBranchId = null
                            onClearContextMemory(targetBranchId)
                        },
                        enabled = !isGenerating && !memoryOperationRunning,
                    ) { Text("确认清空") }
                },
                dismissButton = {
                    TextButton(onClick = { clearTargetBranchId = null }, enabled = !memoryOperationRunning) { Text("取消") }
                },
            )
        }
        if (editingSegmentId != null && sessionReady && summariesLoaded && memoryUiBranchId == currentBranchId) {
            ModalBottomSheet(
                onDismissRequest = { if (!memoryOperationRunning) editingSegmentId = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                sheetMaxWidth = 720.dp,
            ) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("编辑自动摘要", style = MaterialTheme.typography.titleLarge)
                    if (editTarget == null) {
                        Text(editorRestoreError ?: "正在恢复摘要…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (editorRestoreError != null) TextButton(onClick = { editorRestoreAttempt++ }) { Text("重试读取") }
                    }
                    com.mojing.app.ui.common.MoJingWritingField(
                        value = editText, onValueChange = { editText = it }, label = "摘要内容",
                        placeholder = "输入摘要内容", enabled = !memoryOperationRunning,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !memoryOperationRunning, onClick = { editingSegmentId = null }) { Text("取消") }
                        TextButton(enabled = editTarget != null && editText.trim().isNotEmpty() && !isGenerating && !memoryOperationRunning,
                            onClick = { val segment = editTarget ?: return@TextButton; val text = editText.trim(); submittedSummary = text; onEditMemorySummary(segment, text) { success ->
                                if (success && editingSegmentId == segment.id) editingSegmentId = null
                            } }) {
                            Text(if (memoryOperationRunning) "保存中…" else "保存")
                        }
                    }
                }
            }
        }
        deleteTarget?.let { segment ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除这段自动摘要？") },
                text = { Text("将删除这段摘要及其后续自动摘要，原始对话仍会保留，之后可以继续整理重建。") },
                confirmButton = {
                    TextButton(enabled = !isGenerating && !memoryOperationRunning,
                        onClick = { deleteTarget = null; onDeleteMemorySummary(segment) }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
            )
        }
        }
    }
}

@Composable
fun TimelineTab(
    events: List<SessionEventNodeEntity>,
    onToggleResolved: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onJumpToSource: (Long) -> Unit,
    busyIds: Set<Long> = emptySet(),
    actionErrors: Map<Long, String> = emptyMap(),
    currentBranchId: String = "main",
    loaded: Boolean = true,
    hasOlderEvents: Boolean = false,
    olderEventsLoading: Boolean = false,
    olderEventsError: String? = null,
    onLoadOlderEvents: () -> Unit = {},
    sourceNavigationBusy: Boolean = false,
    query: String = "",
    resolvedFilter: Boolean? = null,
    onQueryChange: (String) -> Unit = {},
    onResolvedFilterChange: ((Boolean?) -> Unit)? = null,
    hasNewerEvents: Boolean = false,
    onResetWindow: () -> Unit = {},
    sessionReady: Boolean = true,
) {
    // The initial branch is temporary until session restoration has completed.
    if (!sessionReady) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text("正在加载当前故事线事件…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    var deleteTarget by rememberSaveable(currentBranchId) { mutableStateOf<Long?>(null) }
    var selectedFilter by remember(currentBranchId) { mutableStateOf(0) }
    val effectiveFilter = if (onResolvedFilterChange == null) selectedFilter else
        when (resolvedFilter) { null -> 0; false -> 1; true -> 2 }
    val visibleEvents = remember(events, effectiveFilter) {
        events.filter { effectiveFilter == 0 || it.resolved == (effectiveFilter == 2) }
            .sortedWith(compareByDescending<SessionEventNodeEntity> { it.createdAt }.thenByDescending { it.id })
    }
    val eventListState = key(currentBranchId, effectiveFilter, query) {
        androidx.compose.foundation.lazy.rememberLazyListState()
    }
    val eventListScope = rememberCoroutineScope()
    val timeFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()) }
    val pendingDelete = events.firstOrNull { it.id == deleteTarget && it.branchId == currentBranchId }
    LaunchedEffect(loaded, deleteTarget, pendingDelete?.id) {
        if (loaded && pendingDelete == null) deleteTarget = null
    }
    pendingDelete?.let { event ->
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = { if (event.id !in busyIds) deleteTarget = null },
            title = { Text("删除这条事件？") },
            text = {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text("${event.title}\n\n仅删除事件记录，原对话与百科资料保留。")
                    actionErrors[event.id]?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
                }
            },
            confirmButton = { TextButton(enabled = event.id !in busyIds, onClick = { onDelete(event.id) }) { Text(if (event.id in busyIds) "正在删除…" else if (actionErrors[event.id] != null) "重试删除" else "删除事件") } },
            dismissButton = { TextButton(enabled = event.id !in busyIds, onClick = { deleteTarget = null }) { Text("保留事件") } },
        )
    }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(value = query, onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            inputModifier = Modifier.semantics { contentDescription = "搜索故事线事件" },
            placeholder = { Text("搜索事件标题或描述") }, singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, "清除事件搜索") } }
            } else null,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "待跟进", "已解决").forEachIndexed { index, label ->
                FilterChip(selected = effectiveFilter == index, onClick = {
                    if (onResolvedFilterChange == null) selectedFilter = index
                    else onResolvedFilterChange(when (index) { 1 -> false; 2 -> true; else -> null })
                }, label = { Text(label) })
            }
        }
        HorizontalDivider()
        if (hasNewerEvents) TextButton(onClick = {
            onResetWindow()
            eventListScope.launch { eventListState.scrollToItem(0) }
        }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("回到最近事件")
        }
        if (!loaded) {

        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (olderEventsError == null) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("正在加载当前故事线事件…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(olderEventsError, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onLoadOlderEvents, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试加载") }
            }
        }

        } else {
    if (visibleEvents.isEmpty()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                if (query.isNotBlank()) "没有匹配的事件，可修改搜索词或切换分类。"
                else if (effectiveFilter != 0) "当前故事线暂无此类事件。"
                else "当前故事线暂无事件。对话推进后会自动整理。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EventPaginationFooter(hasOlderEvents, olderEventsLoading, olderEventsError, onLoadOlderEvents)
    } else {
        androidx.compose.runtime.key(currentBranchId, effectiveFilter, query) {
        LazyColumn(state = eventListState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(visibleEvents, key = { it.id }) { event ->
                val busy = event.id in busyIds
                val inherited = event.branchId != currentBranchId
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (event.eventType) { "action" -> Icons.Outlined.DirectionsRun; "discovery" -> Icons.Outlined.Search; "relationship_change" -> Icons.Outlined.Favorite; else -> Icons.Outlined.Circle }, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(event.title, style = MaterialTheme.typography.titleSmall)
                                Text(if (event.resolved) "已解决" else "待跟进", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    timeFormat.format(java.util.Date(event.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("重要度 ${event.importance.coerceIn(1, 5)}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (inherited) Text("继承事件 · 本线可改状态，删除请回来源线", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        actionErrors[event.id]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (event.description.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            ExpandableMemoryText(event.description, collapsedLines = 3,
                                restorationKey = "event:${event.sessionId}:$currentBranchId:${event.id}")
                        }
                        val source = event.sourceReference()
                        if (source == null) {
                            Text(
                                "旧事件未记录原文",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            TextButton(enabled = !sourceNavigationBusy, onClick = { onJumpToSource(source.startMessageId) }) {
                                Text(source.label)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(enabled = !busy, onClick = { onToggleResolved(event.id) }) {
                                Text(if (event.resolved) "标为未解决" else "标为已解决")
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(enabled = !busy && !inherited, onClick = { deleteTarget = event.id }) {
                                Icon(Icons.Outlined.DeleteOutline, "删除事件")
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
            item(key = "event-pagination") {
                EventPaginationFooter(hasOlderEvents, olderEventsLoading, olderEventsError, onLoadOlderEvents)
            }
        }
        }
    }
        }
    }
}

@Composable
private fun EventPaginationFooter(
    hasOlderEvents: Boolean,
    loading: Boolean,
    error: String?,
    onLoadOlderEvents: () -> Unit,
) {
    if (!hasOlderEvents && !loading && error == null) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text("正在加载较早事件…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (hasOlderEvents || error != null) {
                TextButton(onClick = onLoadOlderEvents, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (error == null) "继续加载较早事件" else "重试加载较早事件")
                }
            }
        }
    }
}

@Composable
internal fun ExpandableMemoryText(text: String, collapsedLines: Int = 4, restorationKey: String? = null) {
    var expanded by if (restorationKey == null) remember(text) { mutableStateOf(false) }
        else rememberMemoryTextExpansion(restorationKey, text, collapsedLines)
    var overflowing by remember(text, collapsedLines) { mutableStateOf(false) }
    Text(text, style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
    )
    if (expanded || overflowing) TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "收起" else "展开全文")
    }
}

@Composable
internal fun rememberMemoryTextExpansion(scope: String, text: String, collapsedLines: Int): MutableState<Boolean> {
    // Keep only reading intent in saved state. Inputs alone are not validated on
    // restoration, so match the scope and content fingerprint before expanding.
    val fingerprint = remember(text, collapsedLines) {
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ":$collapsedLines"
    }
    val saver = androidx.compose.runtime.saveable.listSaver<MutableState<Boolean>, Any>(
        save = { listOf(scope, fingerprint, it.value) },
        restore = { saved ->
            mutableStateOf(saved[0] == scope && saved[1] == fingerprint && saved[2] == true)
        },
    )
    return rememberSaveable(scope, fingerprint, saver = saver) { mutableStateOf(false) }
}
