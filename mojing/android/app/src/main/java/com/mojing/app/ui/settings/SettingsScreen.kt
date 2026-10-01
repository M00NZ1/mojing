package com.mojing.app.ui.settings

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton
import com.mojing.app.ui.common.MoJingToggleRow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.mojing.app.ui.character.components.AvatarEditor
import com.mojing.app.ui.character.components.ColorPickerField
import com.mojing.app.ui.navigation.MainAppBottomNavigation
import com.mojing.app.ui.navigation.returnToSessionHome
import com.mojing.app.ui.common.hideImeKeyboard
import com.mojing.app.ui.common.isImeKeyboardOpen
import com.mojing.app.ui.util.UserFacingStrings
import com.mojing.app.ui.settings.usage.UsageScreen
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavHostController,
    onThemeChanged: (String) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    requestModelSection: Boolean = false,
    onModelRequestConsumed: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    var personalizationSection by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeOpen = isImeKeyboardOpen()
    val profileSaving by viewModel.profileSaving.collectAsStateWithLifecycle()
    val storedProfileName by viewModel.userName.collectAsStateWithLifecycle()
    val storedProfileDescription by viewModel.userDescription.collectAsStateWithLifecycle()
    val storedProfileColor by viewModel.userAvatarColor.collectAsStateWithLifecycle()
    var profileName by rememberSaveable { mutableStateOf(storedProfileName) }
    var profileDescription by rememberSaveable { mutableStateOf(storedProfileDescription) }
    var profileColor by rememberSaveable { mutableStateOf(storedProfileColor) }
    val profileDirty = profileName.trim() != storedProfileName ||
        profileDescription != storedProfileDescription ||
        profileColor != storedProfileColor
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showDiscardProfileDialog by remember { mutableStateOf(false) }

    fun requestNavigation(action: () -> Unit) {
        if (profileSaving) return
        focusManager.clearFocus()
        if (profileDirty) {
            pendingNavigation = action
            showDiscardProfileDialog = true
        } else {
            action()
        }
    }

    BackHandler {
        when {
            isImeOpen -> hideImeKeyboard(keyboardController, focusManager)
            showDiscardProfileDialog -> {
                showDiscardProfileDialog = false
                pendingNavigation = null
            }
            else -> requestNavigation { navController.returnToSessionHome() }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            MainAppBottomNavigation(
                navController = navController,
                onNavigateRequest = { action -> requestNavigation(action) },
            )
        },
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
            SettingsSections(
                requestModelSection = requestModelSection,
                onModelRequestConsumed = onModelRequestConsumed,
                requestNavigation = ::requestNavigation,
                onBack = { requestNavigation { navController.returnToSessionHome() } },
            ) { selectedTab ->
                when (selectedTab) {
                    0 -> ConnectionSettingsTab(viewModel = viewModel, snackbarHostState = snackbarHostState)
                    1 -> DefaultsTab(viewModel)
                    2 -> PersonalizationTab(
                        viewModel = viewModel,
                        snackbarHostState = snackbarHostState,
                        onThemeChanged = onThemeChanged,
                        onFontScaleChanged = onFontScaleChanged,
                        selectedSection = personalizationSection,
                        onSelectSection = { section ->
                            if (personalizationSection != section) requestNavigation { personalizationSection = section }
                        },
                        profileName = profileName,
                        onProfileNameChange = { profileName = it },
                        profileDescription = profileDescription,
                        onProfileDescriptionChange = { profileDescription = it },
                        profileColor = profileColor,
                        onProfileColorChange = { profileColor = it },
                        profileDirty = profileDirty,
                        onProfileSaved = { name, _, _ ->
                            if (profileName.trim() == name) profileName = name
                        },
                    )
                    4 -> AppUpdateTab()
                    3 -> UsageScreen(viewModel = hiltViewModel(), onPlatform = { platform ->
                        navController.navigate("usage/platform?platformId=${android.net.Uri.encode(platform.id)}&platformName=${android.net.Uri.encode(platform.name)}")
                    })
                }
            }
            }
        }
    }

    if (showDiscardProfileDialog) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            onDismissRequest = {
                showDiscardProfileDialog = false
                pendingNavigation = null
            },
            title = { Text("放弃未保存的资料？") },
            text = { Text("名字、描述或头像颜色的修改尚未保存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val action = pendingNavigation
                        profileName = storedProfileName
                        profileDescription = storedProfileDescription
                        profileColor = storedProfileColor
                        pendingNavigation = null
                        showDiscardProfileDialog = false
                        action?.invoke()
                    },
                ) { Text("放弃修改") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDiscardProfileDialog = false
                        pendingNavigation = null
                    },
                ) { Text("继续编辑") }
            },
        )
    }
}

@Composable
private fun PersonalizationTab(
    viewModel: SettingsViewModel,
    snackbarHostState: SnackbarHostState,
    onThemeChanged: (String) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    selectedSection: Int,
    onSelectSection: (Int) -> Unit,
    profileName: String,
    onProfileNameChange: (String) -> Unit,
    profileDescription: String,
    onProfileDescriptionChange: (String) -> Unit,
    profileColor: String,
    onProfileColorChange: (String) -> Unit,
    profileDirty: Boolean,
    onProfileSaved: (String, String, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        com.mojing.app.ui.common.MoJingSectionTabs(
            listOf("我的资料", "外观"), selectedSection, onSelectSection,
        )
        Box(modifier = Modifier.weight(1f)) {
            if (selectedSection == 0) {
                ProfileTab(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                    name = profileName,
                    onNameChange = onProfileNameChange,
                    description = profileDescription,
                    onDescriptionChange = onProfileDescriptionChange,
                    avatarColor = profileColor,
                    onAvatarColorChange = onProfileColorChange,
                    isDirty = profileDirty,
                    onSaved = onProfileSaved,
                )
            } else {
                AppearanceTab(viewModel, onThemeChanged, onFontScaleChanged)
            }
        }
    }
}


@Composable
fun ProfileTab(
    viewModel: SettingsViewModel,
    snackbarHostState: SnackbarHostState,
    name: String,
    onNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    avatarColor: String,
    onAvatarColorChange: (String) -> Unit,
    isDirty: Boolean,
    onSaved: (String, String, String) -> Unit,
) {
    val profileSaving by viewModel.profileSaving.collectAsStateWithLifecycle()
    val profileSaveError by viewModel.profileSaveError.collectAsStateWithLifecycle()
    val userAvatarImagePath by viewModel.userAvatarImagePath.collectAsStateWithLifecycle()
    var isAvatarImporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSectionHeader(
            title = "对话中的我",
            description = "角色会用这份资料称呼和理解你。",
        )

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("名字") },
            placeholder = { Text("对话中希望使用的称呼") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = name.isBlank(),
            supportingText = if (name.isBlank()) { { Text("请输入对话中使用的名字") } } else null,
        )

        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text("个人设定") },
            placeholder = { Text("写下身份、性格、经历或希望角色了解的内容") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 10,
        )

        SettingsDividerLabel("形象")

        AvatarEditor(
            avatarImagePath = userAvatarImagePath,
            avatarColor = avatarColor,
            onImageSelected = { path -> viewModel.updateUserAvatarImagePath(path) },
            onColorChanged = onAvatarColorChange,
            onImportingChanged = { isAvatarImporting = it },
        )

        ColorPickerField(selectedColor = avatarColor, onColorSelected = onAvatarColorChange)
        Text(
            "头像选择后立即更新；名字、设定与颜色在保存后生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ProfileSaveActions(profileSaving, isDirty, !isAvatarImporting && name.isNotBlank(), profileSaveError) {
            val normalizedName = name.trim()
            viewModel.updateProfile(normalizedName, description, avatarColor) {
                onSaved(normalizedName, description, avatarColor)
                scope.launch { snackbarHostState.showSnackbar(UserFacingStrings.profileSaved()) }
            }
        }
    }
}

@Composable
fun DefaultsTab(viewModel: SettingsViewModel) {
    LaunchedEffect(Unit) { viewModel.loadSelectedCreationDefaults() }
    var showAdvancedDefaults by remember { mutableStateOf(false) }
    var showWorldPicker by remember { mutableStateOf(false) }
    var showEncyclopediaPicker by remember { mutableStateOf(false) }
    val temp by viewModel.defaultTemperature.collectAsStateWithLifecycle()
    val maxTokens by viewModel.defaultMaxTokens.collectAsStateWithLifecycle()
    val topP by viewModel.defaultTopP.collectAsStateWithLifecycle()
    val defaultWorldTemplateId by viewModel.defaultWorldTemplateId.collectAsStateWithLifecycle()
    val defaultNarrator by viewModel.defaultNarratorEnabled.collectAsStateWithLifecycle()
    val defaultChoice by viewModel.defaultChoiceGenerationEnabled.collectAsStateWithLifecycle()
    val defaultAnti by viewModel.defaultAntiCheatEnabled.collectAsStateWithLifecycle()
    val memoryCompact by viewModel.memoryCompactThreshold.collectAsStateWithLifecycle()
    val maxUpload by viewModel.maxUploadMb.collectAsStateWithLifecycle()
    val maxAuto by viewModel.maxAutoSpeakers.collectAsStateWithLifecycle()
    val defaultEncyclopediaIdForAi by viewModel.defaultEncyclopediaIdForAi.collectAsStateWithLifecycle()
    val defaultLabels by viewModel.creationDefaultLabels.collectAsStateWithLifecycle()

    val tempError = temp.toFloatOrNull()?.let { it !in 0f..2f } != false
    val maxTokensError = maxTokens.toIntOrNull()?.let { it !in 1..200_000 } != false
    val topPError = topP.toFloatOrNull()?.let { it !in 0f..1f } != false
    val memoryError = memoryCompact.toIntOrNull()?.let { it !in 10..2000 } != false
    val uploadError = maxUpload.toIntOrNull()?.let { it !in 1..200 } != false
    val autoError = maxAuto.toIntOrNull()?.let { it !in 1..10 } != false
    val worldLabel = when {
        defaultWorldTemplateId.isBlank() || defaultWorldTemplateId == "custom" -> "自定义（不套模板）"
        defaultLabels.loading -> "正在读取默认世界…"
        defaultLabels.worldMissing -> "原默认世界已不可用"
        defaultLabels.world != null -> defaultLabels.world.orEmpty()
        else -> "已选世界 · $defaultWorldTemplateId"
    }
    val encyclopediaLabel = when {
        defaultEncyclopediaIdForAi.isBlank() -> "不预设"
        defaultLabels.loading -> "正在读取默认百科…"
        defaultLabels.encyclopediaMissing -> "原默认百科已不可用"
        defaultLabels.encyclopedia != null -> defaultLabels.encyclopedia.orEmpty()
        else -> "已选百科 · $defaultEncyclopediaIdForAi"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSectionHeader(
            title = "新故事默认设置",
            description = "修改会立即保存在本机，只影响之后新建的故事。",
        )
        if (defaultLabels.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        defaultLabels.error?.let { message ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::loadSelectedCreationDefaults) { Text("重新加载") }
                }
            }
        }
        OutlinedButton(onClick = { showWorldPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text("默认世界 · $worldLabel", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = { showEncyclopediaPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text("默认参考百科 · $encyclopediaLabel", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        SettingsDividerLabel("对话方式")
        MoJingToggleRow("旁白", "回复时包含场景旁白", defaultNarrator, viewModel::updateDefaultNarratorEnabled)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MoJingToggleRow("本回合选项", "在回复后提供可选行动", defaultChoice, viewModel::updateDefaultChoiceGenerationEnabled)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MoJingToggleRow(
            "保持角色与世界规则",
            "减少角色或世界设定被临时要求带偏的情况",
            defaultAnti,
            viewModel::updateDefaultAntiCheatEnabled,
        )
        SettingsDividerLabel("参与角色")
        OutlinedTextField(
            value = maxAuto,
            onValueChange = { viewModel.updateMaxAutoSpeakers(it) },
            label = { Text("每轮自动发言角色上限") },
            placeholder = { Text("2") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = autoError,
            supportingText = if (autoError) { { Text("请输入 1 到 10 之间的整数") } } else null,
        )

        ExpandableSettingsCard(
            title = "更多本机与生成设置",
            expanded = showAdvancedDefaults,
            onToggle = { showAdvancedDefaults = !showAdvancedDefaults },
        ) {
            OutlinedTextField(
                value = memoryCompact,
                onValueChange = { viewModel.updateMemoryCompactThreshold(it) },
                label = { Text("自动整理记忆的消息间隔") },
                placeholder = { Text("120") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = memoryError,
                supportingText = if (memoryError) { { Text("请输入 10 到 2000 之间的整数") } }
                    else { { Text("长篇整理分段进行，每轮回复前最多处理 4 段，后续继续。") } },
            )
            OutlinedTextField(
                value = maxUpload,
                onValueChange = { viewModel.updateMaxUploadMb(it) },
                label = { Text("单个文件上传上限（MB）") },
                placeholder = { Text("20") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = uploadError,
                supportingText = if (uploadError) { { Text("请输入 1 到 200 之间的整数") } } else null,
            )
            SettingsDividerLabel("新角色生成参数")
            OutlinedTextField(value = temp, onValueChange = { viewModel.updateDefaultTemperature(it) }, label = { Text("温度") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = tempError, supportingText = if (tempError) { { Text("请输入 0 到 2 之间的数字") } } else null)
            OutlinedTextField(value = maxTokens, onValueChange = { viewModel.updateDefaultMaxTokens(it) }, label = { Text("最大回复长度（Token）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = maxTokensError, supportingText = if (maxTokensError) { { Text("请输入 1 到 200000 之间的整数") } } else null)
            OutlinedTextField(value = topP, onValueChange = { viewModel.updateDefaultTopP(it) }, label = { Text("Top P") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = topPError, supportingText = if (topPError) { { Text("请输入 0 到 1 之间的数字") } } else null)
        }
    }
    if (showWorldPicker) CreationDefaultPicker(
        title = "默认世界", searchLabel = "世界模板", noSelectionLabel = "自定义（不套模板）",
        selectedKey = defaultWorldTemplateId.ifBlank { "custom" },
        loadPage = viewModel::loadDefaultWorldPage,
        onSelect = viewModel::selectDefaultWorld,
        onDismiss = { showWorldPicker = false },
    )
    if (showEncyclopediaPicker) CreationDefaultPicker(
        title = "默认参考百科", searchLabel = "百科", noSelectionLabel = "不预设",
        selectedKey = defaultEncyclopediaIdForAi,
        loadPage = viewModel::loadDefaultEncyclopediaPage,
        onSelect = viewModel::selectDefaultEncyclopedia,
        onDismiss = { showEncyclopediaPicker = false },
    )
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsDividerLabel(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
