package com.mojing.app.ui.settings

import com.mojing.app.ui.common.MoJingTextField as OutlinedTextField
import com.mojing.app.ui.common.MoJingButton as Button

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
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
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            requestNavigation { navController.returnToSessionHome() }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回会话主页")
                    }
                },
            )
        },
        bottomBar = {
            MainAppBottomNavigation(
                navController = navController,
                onNavigateRequest = { action -> requestNavigation(action) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            SettingsSections(
                requestModelSection = requestModelSection,
                onModelRequestConsumed = onModelRequestConsumed,
                requestNavigation = ::requestNavigation,
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
                    3 -> UsageScreen(viewModel = hiltViewModel(), onPlatform = { platform ->
                        navController.navigate("usage/platform?platformId=${android.net.Uri.encode(platform.id)}&platformName=${android.net.Uri.encode(platform.name)}")
                    })
                }
            }
        }
    }

    if (showDiscardProfileDialog) {
        AlertDialog(
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
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            listOf("我的资料", "外观").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selectedSection == index,
                    onClick = { onSelectSection(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(label) }
            }
        }
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

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {

        Text("对话中的我", style = MaterialTheme.typography.titleMedium)
        Text(
            "角色会用这份资料称呼和理解你。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        HorizontalDivider()
        Text("形象", style = MaterialTheme.typography.titleSmall)

        AvatarEditor(
            avatarImagePath = userAvatarImagePath,
            avatarColor = avatarColor,
            onImageSelected = { path -> viewModel.updateUserAvatarImagePath(path) },
            onColorChanged = onAvatarColorChange,
            onImportingChanged = { isAvatarImporting = it },
        )

        ColorPickerField(selectedColor = avatarColor, onColorSelected = onAvatarColorChange)
        Text(
            "头像在选择后立即更新；名字、设定与颜色在保存后生效。",
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
    LaunchedEffect(Unit) { viewModel.loadCreationOptions() }
    var showAdvancedDefaults by remember { mutableStateOf(false) }
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
    val worldTemplates by viewModel.availableWorldTemplates.collectAsStateWithLifecycle()
    val encyclopedias by viewModel.availableEncyclopedias.collectAsStateWithLifecycle()
    val creationOptionsLoading by viewModel.creationOptionsLoading.collectAsStateWithLifecycle()
    val creationOptionsError by viewModel.creationOptionsError.collectAsStateWithLifecycle()

    val tempError = temp.toFloatOrNull()?.let { it !in 0f..2f } != false
    val maxTokensError = maxTokens.toIntOrNull()?.let { it !in 1..200_000 } != false
    val topPError = topP.toFloatOrNull()?.let { it !in 0f..1f } != false
    val memoryError = memoryCompact.toIntOrNull()?.let { it !in 20..10_000 } != false
    val uploadError = maxUpload.toIntOrNull()?.let { it !in 1..200 } != false
    val autoError = maxAuto.toIntOrNull()?.let { it !in 1..10 } != false
    val templateOptions = buildList {
        add("custom" to "自定义（不套模板）")
        worldTemplates.forEach { add(it.templateId to it.label.ifBlank { "未命名世界" }) }
        if (defaultWorldTemplateId.isNotBlank() && none { it.first == defaultWorldTemplateId }) {
            add(defaultWorldTemplateId to "原默认世界已不可用")
        }
    }.distinctBy { it.first }
    val encyclopediaOptions = buildList {
        add("" to "不预设")
        encyclopedias.forEach { add(it.id.toString() to it.name.ifBlank { "未命名百科" }) }
        if (defaultEncyclopediaIdForAi.isNotBlank() && none { it.first == defaultEncyclopediaIdForAi }) {
            add(defaultEncyclopediaIdForAi to "原默认百科已不可用")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("新故事默认设置", style = MaterialTheme.typography.titleMedium)
        Text(
            "修改会立即保存在本机，只影响之后新建的故事。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (creationOptionsLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        creationOptionsError?.let { message ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::loadCreationOptions) { Text("重新加载") }
                }
            }
        }
        CreationOptionPicker(
            label = "默认世界",
            selectedKey = defaultWorldTemplateId.ifBlank { "custom" },
            options = templateOptions,
            enabled = !creationOptionsLoading && creationOptionsError == null,
            onSelect = viewModel::updateDefaultWorldTemplateId,
        )
        CreationOptionPicker(
            label = "默认参考百科",
            selectedKey = defaultEncyclopediaIdForAi,
            options = encyclopediaOptions,
            enabled = !creationOptionsLoading && creationOptionsError == null,
            onSelect = viewModel::updateDefaultEncyclopediaIdForAi,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("旁白", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = defaultNarrator, onCheckedChange = { viewModel.updateDefaultNarratorEnabled(it) })
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("本回合选项", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = defaultChoice, onCheckedChange = { viewModel.updateDefaultChoiceGenerationEnabled(it) })
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("保持角色与世界规则", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "减少角色或世界设定被临时要求带偏的情况",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = defaultAnti, onCheckedChange = { viewModel.updateDefaultAntiCheatEnabled(it) })
        }
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
                supportingText = if (memoryError) { { Text("请输入 20 到 10000 之间的整数") } } else null,
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
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("新角色生成参数", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = temp, onValueChange = { viewModel.updateDefaultTemperature(it) }, label = { Text("温度") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = tempError, supportingText = if (tempError) { { Text("请输入 0 到 2 之间的数字") } } else null)
            OutlinedTextField(value = maxTokens, onValueChange = { viewModel.updateDefaultMaxTokens(it) }, label = { Text("最大回复长度（Token）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = maxTokensError, supportingText = if (maxTokensError) { { Text("请输入 1 到 200000 之间的整数") } } else null)
            OutlinedTextField(value = topP, onValueChange = { viewModel.updateDefaultTopP(it) }, label = { Text("Top P") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = topPError, supportingText = if (topPError) { { Text("请输入 0 到 1 之间的数字") } } else null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreationOptionPicker(
    label: String,
    selectedKey: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedKey }?.second ?: "请选择"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled),
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    },
                )
            }
        }
    }
}
