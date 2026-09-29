package com.mojing.app.ui.chat.drawer

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDrawer(
    participants: List<SessionParticipantEntity>,
    world: SessionWorldEntity? = null,
    encyclopediaFoundation: String = "",
    contextMemoryText: String = "",
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
    onLoadMoreEventNodes: () -> Unit = {},
    onOpenEvents: () -> Unit = {},
    eventBusyIds: Set<Long> = emptySet(),
    eventActionErrors: Map<Long, String> = emptyMap(),
    characterNames: Map<Long, String> = emptyMap(),
    bookmarks: List<MessageBookmarkEntity> = emptyList(),
    bookmarksLoaded: Boolean = true,
    bookmarksHasMore: Boolean = false,
    bookmarksLoadingMore: Boolean = false,
    bookmarksLoadError: String? = null,
    bookmarkBusyIds: Set<Long> = emptySet(),
    bookmarkLocatingId: Long? = null,
    bookmarkPreviews: Map<Long, String> = emptyMap(),
    onJumpToBookmark: (Long) -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    onOpenBookmarks: () -> Unit = {},
    onLoadMoreBookmarks: () -> Unit,
    onToggleMute: (Long) -> Unit,
    onUpdateTalkativeness: (Long, Float, (Boolean) -> Unit) -> Unit,
    onRemoveParticipant: (Long) -> Unit,
    onAddParticipant: () -> Unit,
    speakerTurnMode: String = "auto",
    onSpeakerTurnModeChange: (String) -> Unit,
    onClose: () -> Unit,
    onWorldSettingChanged: (String, Boolean) -> Unit,
    onSaveSessionWorldCredentials: (SessionWorldCredentialDraft) -> Unit,
    onWorldCredentialFieldsDirty: (Boolean) -> Unit,
    worldCredentialFieldsDirty: Boolean = false,
    onToggleEventResolved: (Long) -> Unit,
    onDeleteEventNode: (Long) -> Unit,
    onJumpToMemorySource: (Long, (Boolean) -> Unit) -> Boolean,
    onAddMemoryCorrection: (String, Long?) -> Unit,
    onEditMemoryCorrection: (SessionMemoryCorrectionEntity) -> Unit,
    onDeleteMemoryCorrection: (SessionMemoryCorrectionEntity) -> Unit,
    onRebuildContextMemory: () -> Unit,
    onClearContextMemory: () -> Unit,
    onContinueStorySummary: () -> Unit,
    onStopStorySummary: () -> Unit,
    allowSessionThinkMax: Boolean = false,
    sessionThinkMaxEnabled: Boolean = false,
    characterForcesThinkMax: Boolean = false,
    onSessionThinkMax: (Boolean) -> Unit,
    memorySegmentsHasMore: Boolean = false,
    memorySegmentsLoaded: Boolean = false,
    memorySegmentsLoading: Boolean = false,
    memorySegmentsLoadingMore: Boolean = false,
    memorySegmentsLoadError: String? = null,
    onLoadMoreMemorySummaries: () -> Unit = {},
    onOpenMemorySummaries: () -> Unit = {},
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var pendingTab by remember { mutableStateOf<Int?>(null) }
    var sourceOpeningId by remember(currentBranchId, drawerOpen) { mutableStateOf<Long?>(null) }
    var sourceFailedId by remember(currentBranchId, drawerOpen) { mutableStateOf<Long?>(null) }
    var sourceError by remember(currentBranchId, drawerOpen) { mutableStateOf<String?>(null) }
    var sourceActive by remember(currentBranchId, drawerOpen) { mutableStateOf(drawerOpen) }
    DisposableEffect(currentBranchId, drawerOpen) { onDispose { sourceActive = false } }
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

    LaunchedEffect(selectedTab, drawerOpen, sessionReady, currentBranchId) {
        if (selectedTab == 3 && drawerOpen && sessionReady) onOpenEvents()
        if (selectedTab == 4 && drawerOpen && sessionReady) onOpenBookmarks()
    }

    Column(modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("会话资料", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭") }
        }
        HorizontalDivider()
        PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = {
                    if (index != selectedTab) {
                        if (selectedTab == 1 && worldCredentialFieldsDirty) pendingTab = index
                        else selectedTab = index
                    }
                }, text = { Text(title, maxLines = 1) })
            }
        }
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
            )
            1 -> WorldConfigTab(
                world = world,
                encyclopediaFoundation = encyclopediaFoundation,
                onWorldSettingChanged = onWorldSettingChanged,
                onSaveSessionWorldCredentials = onSaveSessionWorldCredentials,
                onCredentialFieldsDirty = onWorldCredentialFieldsDirty,
                allowSessionThinkMax = allowSessionThinkMax,
                sessionThinkMaxEnabled = sessionThinkMaxEnabled,
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
                hasOlderSummaries = memorySegmentsHasMore,
                summariesLoaded = memorySegmentsLoaded,
                summariesLoading = memorySegmentsLoading,
                olderSummariesLoading = memorySegmentsLoadingMore,
                olderSummariesError = memorySegmentsLoadError,
                onLoadOlderSummaries = onLoadMoreMemorySummaries,
                onOpenSummaries = onOpenMemorySummaries,
                sourceNavigationBusy = sourceOpeningId != null,
                correctionsLoaded = memoryCorrectionsLoaded,
                correctionsLoading = memoryCorrectionsLoading,
                correctionsHasMore = memoryCorrectionsHasMore,
                correctionsLoadError = memoryCorrectionsLoadError,
                drawerOpen = drawerOpen,
                sessionReady = sessionReady,
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
                hasOlderEvents = eventNodesHasMore,
                olderEventsLoading = eventNodesLoadingMore,
                olderEventsError = eventNodesLoadError,
                onLoadOlderEvents = onLoadMoreEventNodes,
                sourceNavigationBusy = sourceOpeningId != null,
            )
            4 -> BookmarksTab(
                bookmarks, bookmarkPreviews, onJumpToBookmark, onRemoveBookmark,
                bookmarkBusyIds, bookmarkLocatingId, bookmarksLoaded, bookmarksHasMore, bookmarksLoadingMore,
                bookmarksLoadError, onLoadMoreBookmarks,
            )
        }
    }
    pendingTab?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingTab = null },
            title = { Text("世界配置尚未保存") },
            text = { Text("继续编辑，或放弃本次修改后切换资料。") },
            confirmButton = {
                TextButton(onClick = { pendingTab = null }) { Text("继续编辑") }
            },
            dismissButton = {
                TextButton(onClick = {
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
) {
    LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "controls") {
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
                    Icon(Icons.Default.PersonAdd, null)
                    Spacer(Modifier.width(8.dp))
                    Text("添加角色到对话")
                }
                HorizontalDivider()
            }
        }
        if (participants.isEmpty()) {
            item(key = "empty") {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("请先添加至少一个角色", color = MaterialTheme.colorScheme.error)
                        Text("点击上方按钮选择角色加入对话", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            items(participants, key = { it.id }) { p ->
                val name = participantDisplayName(p.characterId, characterNames)
                val strategyLabel = participantSpeakerStrategyLabel(p.speakerStrategy)
                var talkativenessDraft by remember(p.id) { mutableFloatStateOf(p.talkativeness) }
                var isSavingTalkativeness by remember(p.id) { mutableStateOf(false) }
                LaunchedEffect(p.talkativeness) {
                    if (!isSavingTalkativeness) talkativenessDraft = p.talkativeness
                }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Person, null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
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
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (p.muted) "暂停" else "参与",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Switch(
                                    checked = !p.muted,
                                    onCheckedChange = { onToggleMute(p.id) },
                                    enabled = !isGenerating,
                                    modifier = Modifier.semantics {
                                        contentDescription = "$name 发言状态"
                                        stateDescription = if (p.muted) "已暂停" else "参与中"
                                    },
                                )
                            }
                            IconButton(
                                onClick = { onRemoveParticipant(p.id) },
                                enabled = !isGenerating,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Default.PersonRemove,
                                    "从对话移除$name",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (speakerTurnMode != "manual") {
                            Slider(
                                value = talkativenessDraft,
                                onValueChange = { talkativenessDraft = it },
                                onValueChangeFinished = {
                                    if (!isSavingTalkativeness) {
                                        isSavingTalkativeness = true
                                        onUpdateTalkativeness(p.id, talkativenessDraft) { saved ->
                                            isSavingTalkativeness = false
                                            if (!saved) talkativenessDraft = p.talkativeness
                                        }
                                    }
                                },
                                valueRange = 0.05f..1f,
                                enabled = !isSavingTalkativeness && !isGenerating,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = "$name 发言率" },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WorldConfigTab(
    world: SessionWorldEntity?,
    encyclopediaFoundation: String = "",
    onWorldSettingChanged: (String, Boolean) -> Unit,
    onSaveSessionWorldCredentials: (SessionWorldCredentialDraft) -> Unit,
    onCredentialFieldsDirty: (Boolean) -> Unit,
    allowSessionThinkMax: Boolean = false,
    sessionThinkMaxEnabled: Boolean = false,
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
    var llmKey by remember(w.id, w.sessionLlmApiKey) { mutableStateOf(w.sessionLlmApiKey) }
    var llmBase by remember(w.id, w.sessionLlmBaseUrl) { mutableStateOf(w.sessionLlmBaseUrl) }
    var imgKey by remember(w.id, w.sessionImageApiKey) { mutableStateOf(w.sessionImageApiKey) }
    var imgBase by remember(w.id, w.sessionImageBaseUrl) { mutableStateOf(w.sessionImageBaseUrl) }
    var imgModel by remember(w.id, w.sessionImageModel) { mutableStateOf(w.sessionImageModel) }
    var voiceKey by remember(w.id, w.sessionVoiceApiKey) { mutableStateOf(w.sessionVoiceApiKey) }
    var voiceBase by remember(w.id, w.sessionVoiceBaseUrl) { mutableStateOf(w.sessionVoiceBaseUrl) }
    var voiceModel by remember(w.id, w.sessionVoiceModel) { mutableStateOf(w.sessionVoiceModel) }
    var voiceSpeech by remember(w.id, w.sessionVoiceSpeechVoice) { mutableStateOf(w.sessionVoiceSpeechVoice) }
    var voicePreset by remember(w.id, w.sessionVoicePresetPrefixModel) { mutableStateOf(w.sessionVoicePresetPrefixModel) }

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

    var routeDetailsOpen by remember(w.id) { mutableStateOf(false) }
    var voiceDetailsOpen by remember(w.id) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
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
                }
                Switch(
                    checked = sessionThinkMaxEnabled,
                    onCheckedChange = onSessionThinkMax,
                    enabled = !isGenerating && (allowSessionThinkMax || sessionThinkMaxEnabled),
                )
            }
        }
        val sceneText = listOf(w.worldPrompt.trim(), encyclopediaFoundation.trim())
            .filter(String::isNotBlank).distinct().joinToString("\n\n")
        if (sceneText.isNotBlank()) {
            var expanded by remember(w.encyclopediaId, w.templateId) { mutableStateOf(false) }
            Text("本场基础设定", style = MaterialTheme.typography.titleSmall)
            Text(sceneText, maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起设定" else "展开设定") }
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
                onCheckedChange = { onWorldSettingChanged("narratorEnabled", it) },
                enabled = !isGenerating,
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
                onCheckedChange = { onWorldSettingChanged("choiceGenerationEnabled", it) },
                enabled = !isGenerating,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("反作弊", modifier = Modifier.weight(1f))
            Switch(
                checked = w.antiCheatEnabled,
                onCheckedChange = { onWorldSettingChanged("antiCheatEnabled", it) },
                enabled = !isGenerating,
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
                onCheckedChange = { onWorldSettingChanged("autoSedimentEnabled", it) },
                enabled = !isGenerating,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("回复内自动配图", modifier = Modifier.weight(1f))
            Switch(
                checked = w.autoCharacterImageGen,
                onCheckedChange = { onWorldSettingChanged("autoCharacterImageGen", it) },
                enabled = !isGenerating,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("回复内自动配音", modifier = Modifier.weight(1f))
            Switch(
                checked = w.autoCharacterSpeech,
                onCheckedChange = { onWorldSettingChanged("autoCharacterSpeech", it) },
                enabled = !isGenerating,
            )
        }

        HorizontalDivider()
        TextButton(
            onClick = { routeDetailsOpen = !routeDetailsOpen },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Tune, null)
            Spacer(Modifier.width(8.dp))
            Text("专用线路", modifier = Modifier.weight(1f))
            Icon(if (routeDetailsOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
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
            )
            OutlinedTextField(
                value = llmBase,
                onValueChange = { llmBase = it },
                label = { Text("对话 URL") },
                placeholder = { Text("与上方 Key 配套填写；两项均空时继承") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text("生图", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = imgKey,
                onValueChange = { imgKey = it },
                label = { Text("配图 API Key 覆盖") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = imgBase,
                onValueChange = { imgBase = it },
                label = { Text("配图 URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = imgModel,
                onValueChange = { imgModel = it },
                label = { Text("生图模型 id 覆盖") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
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
                    if (credentialDirty) "线路草稿未保存" else "线路配置已保存",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (credentialDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { emitSave() },
                    enabled = !isGenerating,
                ) { Text("保存线路") }
            }
        }
    }
    }
}

@Composable
fun MemoryTab(
    segments: List<SessionMemorySegmentEntity>,
    corrections: List<SessionMemoryCorrectionEntity>,
    promptTrace: MemoryCorrectionPromptTrace?,
    currentBranchId: String,
    isGenerating: Boolean,
    onRebuildContextMemory: () -> Unit,
    onClearContextMemory: () -> Unit,
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
    hasOlderSummaries: Boolean = false,
    summariesLoaded: Boolean = true,
    summariesLoading: Boolean = false,
    olderSummariesLoading: Boolean = false,
    olderSummariesError: String? = null,
    onLoadOlderSummaries: () -> Unit = {},
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
) {
    var section by remember(currentBranchId) { mutableIntStateOf(0) }
    var showCorrectionTrace by remember(currentBranchId) { mutableStateOf(false) }
    val currentCorrectionTrace = promptTrace?.takeIf { it.branchId == currentBranchId }
    LaunchedEffect(currentBranchId, section, drawerOpen, sessionReady) {
        if (section == 1 && drawerOpen && sessionReady) onOpenCorrections()
        if (section == 2 && drawerOpen && sessionReady) onOpenSummaries()
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
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
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
                            Icon(Icons.Default.Refresh, contentDescription = "重建当前会话记忆")
                            Spacer(Modifier.width(8.dp))
                            Text("重建记忆")
                        }
                        OutlinedButton(
                            onClick = onClearContextMemory,
                            enabled = !isGenerating && !memoryOperationRunning,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空当前会话记忆")
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
                    ExpandableMemoryText(contextMemoryText.ifBlank { "暂无长期记忆，可从当前故事线重建。" }, collapsedLines = 6)
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
                        Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.surface) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(if (correction.branchId == null) "整个对话" else "仅当前故事线",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                ExpandableMemoryText(correction.content)
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
                                Icon(Icons.Default.Refresh, contentDescription = null)
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
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("剧情摘要", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(segment.emotionalTone, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                                ExpandableMemoryText(segment.summary)
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            source.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(enabled = !isGenerating && !sourceNavigationBusy, onClick = { onJumpToSource(source.messageId) }) {
                                            Text("查看原文")
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
) {
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
        return
    }
    var deleteTarget by remember(currentBranchId) { mutableStateOf<Long?>(null) }
    var selectedFilter by remember(currentBranchId) { mutableStateOf(0) }
    val visibleEvents = remember(events, selectedFilter) {
        events.filter { selectedFilter == 0 || it.resolved == (selectedFilter == 2) }
            .sortedWith(compareByDescending<SessionEventNodeEntity> { it.createdAt }.thenByDescending { it.id })
    }
    val timeFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()) }
    val pendingDelete = events.firstOrNull { it.id == deleteTarget }
    LaunchedEffect(pendingDelete?.id) { if (pendingDelete == null) deleteTarget = null }
    pendingDelete?.let { event ->
        AlertDialog(
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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val moreMark = if (hasOlderEvents) "+" else ""
            listOf("全部 ${events.size}$moreMark", "待跟进 ${events.count { !it.resolved }}$moreMark", "已解决 ${events.count { it.resolved }}$moreMark").forEachIndexed { index, label ->
                FilterChip(selected = selectedFilter == index, onClick = { selectedFilter = index }, label = { Text(label) })
            }
        }
        HorizontalDivider()
    if (visibleEvents.isEmpty()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                if (events.isEmpty()) "当前故事线暂无事件。对话推进后会自动整理，可从事件返回原文。"
                else if (hasOlderEvents) "当前已加载范围暂无此类事件，可继续加载较早事件。"
                else "当前分类暂无事件，可切换分类查看。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EventPaginationFooter(hasOlderEvents, olderEventsLoading, olderEventsError, onLoadOlderEvents)
    } else {
        androidx.compose.runtime.key(currentBranchId, selectedFilter) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(visibleEvents, key = { it.id }) { event ->
                val busy = event.id in busyIds
                val inherited = event.branchId != currentBranchId
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (event.eventType) { "action" -> Icons.Default.DirectionsRun; "discovery" -> Icons.Default.Search; "relationship_change" -> Icons.Default.Favorite; else -> Icons.Default.Circle }, null, modifier = Modifier.size(16.dp))
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
                            ExpandableMemoryText(event.description, collapsedLines = 3)
                        }
                        val source = event.sourceReference()
                        if (source == null) {
                            Text(
                                "旧事件未记录原文",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            TextButton(enabled = !sourceNavigationBusy, onClick = { onJumpToSource(source.messageId) }) {
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
                                Icon(Icons.Default.DeleteOutline, "删除事件")
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
            if (hasOlderEvents) {
                TextButton(onClick = onLoadOlderEvents, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (error == null) "继续加载较早事件" else "重试加载较早事件")
                }
            }
        }
    }
}

@Composable
internal fun ExpandableMemoryText(text: String, collapsedLines: Int = 4) {
    var expanded by remember(text) { mutableStateOf(false) }
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
