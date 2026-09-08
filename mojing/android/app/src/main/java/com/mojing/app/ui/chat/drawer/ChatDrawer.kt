package com.mojing.app.ui.chat.drawer

import androidx.compose.foundation.layout.*
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
import com.google.gson.Gson

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDrawer(
    participants: List<SessionParticipantEntity>,
    world: SessionWorldEntity? = null,
    memorySegments: List<SessionMemorySegmentEntity> = emptyList(),
    memoryCorrections: List<SessionMemoryCorrectionEntity> = emptyList(),
    memoryCorrectionPromptTrace: MemoryCorrectionPromptTrace? = null,
    currentBranchId: String = "main",
    isGenerating: Boolean = false,
    eventNodes: List<SessionEventNodeEntity> = emptyList(),
    characterNames: Map<Long, String> = emptyMap(),
    bookmarks: List<MessageBookmarkEntity> = emptyList(),
    bookmarkPreviews: Map<Long, String> = emptyMap(),
    onJumpToBookmark: (Long) -> Unit,
    onRemoveBookmark: (Long) -> Unit,
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
    onJumpToMemorySource: (Long) -> Unit,
    onAddMemoryCorrection: (String, Long?) -> Unit,
    onEditMemoryCorrection: (SessionMemoryCorrectionEntity) -> Unit,
    onDeleteMemoryCorrection: (SessionMemoryCorrectionEntity) -> Unit,
    onRebuildContextMemory: () -> Unit,
    onClearContextMemory: () -> Unit,
    allowSessionThinkMax: Boolean = false,
    sessionThinkMaxEnabled: Boolean = false,
    characterForcesThinkMax: Boolean = false,
    onSessionThinkMax: (Boolean) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var pendingTab by remember { mutableStateOf<Int?>(null) }
    val tabs = listOf("角色", "世界", "记忆", "事件", "书签")

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
                onJumpToMemorySource,
                onAddMemoryCorrection,
                onEditMemoryCorrection,
                onDeleteMemoryCorrection,
            )
            3 -> TimelineTab(
                eventNodes,
                onToggleEventResolved,
                onDeleteEventNode,
                onJumpToMemorySource,
            )
            4 -> BookmarksTab(bookmarks, bookmarkPreviews, onJumpToBookmark, onRemoveBookmark)
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
    var llmKey by remember(w.id, w.updatedAt) { mutableStateOf(w.sessionLlmApiKey) }
    var llmBase by remember(w.id, w.updatedAt) { mutableStateOf(w.sessionLlmBaseUrl) }
    var imgKey by remember(w.id, w.updatedAt) { mutableStateOf(w.sessionImageApiKey) }
    var imgBase by remember(w.id, w.updatedAt) { mutableStateOf(w.sessionImageBaseUrl) }
    var imgModel by remember(w.id, w.updatedAt) { mutableStateOf(w.sessionImageModel) }
    var voiceKey by remember(w.id, w.updatedAt) { mutableStateOf(w.sessionVoiceApiKey) }
    var voiceBase by remember(w.id, w.updatedAt) { mutableStateOf(w.sessionVoiceBaseUrl) }
    var voiceModel by remember(w.id, w.updatedAt) { mutableStateOf(w.sessionVoiceModel) }
    var voiceSpeech by remember(w.id, w.updatedAt) { mutableStateOf(w.sessionVoiceSpeechVoice) }
    var voicePreset by remember(w.id, w.updatedAt) { mutableStateOf(w.sessionVoicePresetPrefixModel) }

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

    var voiceDetailsOpen by remember(w.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("本场专用线路", style = MaterialTheme.typography.titleMedium)
        if (isGenerating) {
            Text(
                "可先编辑线路草稿；回复完成或停止后再保存和调整本场玩法",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (credentialDirty) {
            AssistChip(onClick = {}, enabled = false, label = { Text("未保存") })
        }
        Text("对话 / 旁白 / 记忆", style = MaterialTheme.typography.labelMedium)
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
            placeholder = { Text("空则使用角色或设置") },
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
        Button(
            onClick = { emitSave() },
            enabled = !isGenerating,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存本场线路") }

        HorizontalDivider()
        Text("本场玩法", style = MaterialTheme.typography.titleMedium)
        Text(
            "${w.gameplayMode} · 模板 ${w.templateId}",
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

        TextButton(
            onClick = { voiceDetailsOpen = !voiceDetailsOpen },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (voiceDetailsOpen) "收起" else "朗读覆盖")
        }
        if (voiceDetailsOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = voiceKey,
                    onValueChange = { voiceKey = it },
                    label = { Text("朗读 API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = voiceBase,
                    onValueChange = { voiceBase = it },
                    label = { Text("朗读 URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = voiceModel,
                    onValueChange = { voiceModel = it },
                    label = { Text("TTS 模型") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = voiceSpeech,
                    onValueChange = { voiceSpeech = it },
                    label = { Text("音色 voice") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = voicePreset,
                    onValueChange = { voicePreset = it },
                    label = { Text("音色前缀模型") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
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
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onRebuildContextMemory,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "重建当前会话记忆")
                Spacer(Modifier.width(8.dp))
                Text("重建记忆")
            }
            OutlinedButton(
                onClick = onClearContextMemory,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "清空当前会话记忆")
                Spacer(Modifier.width(8.dp))
                Text("清空记忆")
            }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                val currentTrace = promptTrace?.takeIf { it.branchId == currentBranchId }
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("最近一次提示依据", style = MaterialTheme.typography.titleMedium)
                    if (currentTrace == null) {
                        Text(
                            "本故事线尚未构造可追踪的角色或旁白请求",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        val responderType = if (currentTrace.responderType == "narrator") "旁白" else "角色"
                        Text(
                            "$responderType · ${currentTrace.responderLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (currentTrace.corrections.isEmpty()) {
                            Text("本轮未采用用户纠正", modifier = Modifier.padding(top = 6.dp))
                        } else {
                            currentTrace.corrections.forEachIndexed { index, correction ->
                                Text(
                                    "${index + 1}. ${if (correction.branchId == null) "全会话" else "本分支"} · ${correction.content}",
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                correction.sourceMessageId?.let {
                                    Text(
                                        "已关联原文",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Text(
                            "用户纠正在自动记忆之前注入；这里只表示本机已构造提示，不代表模型已成功回复。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                HorizontalDivider()
            }
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
                items(corrections, key = { it.id }) { correction ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            AssistChip(
                                onClick = {}, enabled = false,
                                label = { Text(if (correction.branchId == null) "整个对话" else "仅当前故事线") },
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(correction.content)
                            correction.sourceMessageId?.let { sourceId ->
                                TextButton(
                                    enabled = !isGenerating,
                                    onClick = { onJumpToSource(sourceId) },
                                ) { Text("查看来源") }
                            }
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(enabled = !isGenerating, onClick = { onEditCorrection(correction) }) { Text("编辑") }
                                TextButton(enabled = !isGenerating, onClick = { onDeleteCorrection(correction) }) { Text("删除") }
                            }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("自动摘要", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            if (segments.isEmpty()) {
                item { Text("对话积累后会自动整理摘要。暂未整理或整理失败时，原文仍完整保留，后续对话会再次尝试。", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(segments, key = { it.id }) { segment ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("剧情摘要", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(segment.emotionalTone, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(segment.summary, style = MaterialTheme.typography.bodyMedium)
                            TextButton(
                                enabled = !isGenerating,
                                onClick = { onAddCorrection(segment.summary, segment.startMessageId.takeIf { it > 0L }) },
                            ) { Text("纠正这段记忆") }
                            val facts = try { Gson().fromJson(segment.keyFactsJson, List::class.java).orEmpty() } catch (_: Exception) { emptyList<Any>() }
                            facts.take(3).forEach { fact -> Text("• $fact", style = MaterialTheme.typography.labelSmall) }
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
                                    TextButton(enabled = !isGenerating, onClick = { onJumpToSource(source.messageId) }) {
                                        Text("查看原文")
                                    }
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
) {
    if (events.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("当前故事线暂无事件。对话推进后会自动整理，可从事件返回原文。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(events.sortedWith(compareBy({ it.createdAt }, { it.id })), key = { it.id }) { event ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (event.eventType) { "action" -> Icons.Default.DirectionsRun; "discovery" -> Icons.Default.Search; "relationship_change" -> Icons.Default.Favorite; else -> Icons.Default.Circle }, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(event.title, style = MaterialTheme.typography.titleMedium)
                                Text(if (event.resolved) "已解决" else "待跟进", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                        .format(java.util.Date(event.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            repeat(event.importance.coerceIn(1, 5)) { Text("★", color = MaterialTheme.colorScheme.primary) }
                        }
                        if (event.description.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(event.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3) }
                        val source = event.sourceReference()
                        if (source == null) {
                            Text(
                                "旧事件未记录原文",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            TextButton(onClick = { onJumpToSource(source.messageId) }) {
                                Text(source.label)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { onToggleResolved(event.id) }) {
                                Text(if (event.resolved) "标为未解决" else "标为已解决")
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { onDelete(event.id) }) {
                                Icon(Icons.Default.Delete, "删除事件", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddParticipantDialog(
    availableCharacters: List<com.mojing.app.data.local.entity.CharacterEntity>,
    isLoading: Boolean = false,
    loadError: String? = null,
    isSubmitting: Boolean = false,
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text("选择角色") },
        text = {
            if (isSubmitting) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在添加角色…")
                }
            } else if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在加载角色…")
                }
            } else if (loadError != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(loadError, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("重新加载") }
                }
            } else if (availableCharacters.isEmpty()) {
                Text("暂无可用的角色，请先创建角色。", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(availableCharacters, key = { it.id }) { character ->
                        TextButton(
                            onClick = { onSelect(character.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(character.name, modifier = Modifier.weight(1f))
                            if (character.favorite) Icon(Icons.Default.Star, "收藏", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("取消") }
        }
    )
}
