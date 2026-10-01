package com.mojing.app.ui.navigation

import com.mojing.app.ui.common.MoJingTopAppBar as TopAppBar
import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mojing.app.ui.character.CharacterEditScreen
import com.mojing.app.ui.character.CharacterListScreen
import com.mojing.app.ui.chat.ChatScreen
import com.mojing.app.ui.creation.CreationHubScreen
import com.mojing.app.ui.encyclopedia.EncyclopediaDetailScreen
import com.mojing.app.ui.encyclopedia.EncyclopediaScreen
import com.mojing.app.ui.encyclopedia.EntryEditScreen
import com.mojing.app.ui.session.SessionListScreen
import com.mojing.app.ui.generation.GenerationTaskListScreen
import com.mojing.app.ui.settings.SettingsScreen
import com.mojing.app.ui.story.StorySimulationScreen
import com.mojing.app.ui.workbench.TemplateEditScreen
import com.mojing.app.ui.workbench.WorkbenchScreen
import com.mojing.app.ui.world.WorldSettingsScreen

@Composable
private fun InvalidRouteRedirect(
    navController: NavHostController,
    fallbackRoute: String,
) {
    LaunchedEffect(navController, fallbackRoute) {
        navController.navigateToMainTab(fallbackRoute)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun InvalidUsageRouteRedirect(
    navController: NavHostController,
    notice: String,
) {
    LaunchedEffect(navController, notice) {
        navController.navigate("usage?notice=${android.net.Uri.encode(notice)}") {
            launchSingleTop = true
            navController.currentBackStackEntry?.destination?.id?.let { invalidDestinationId ->
                popUpTo(invalidDestinationId) { inclusive = true }
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun UsageSummaryDestination(
    navController: NavHostController,
    notice: String,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                        expandedHeight = 52.dp,
                title = { Text("用量汇总") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateToMainTab(Routes.SETTINGS) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回设置")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ) {
                Text(
                    notice,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Box(Modifier.weight(1f)) {
                com.mojing.app.ui.settings.usage.UsageScreen(
                    viewModel = androidx.hilt.navigation.compose.hiltViewModel(),
                    onBack = { navController.navigateToMainTab(Routes.SETTINGS) },
                )
            }
        }
    }
}

@Composable
internal fun NavGraph(
    navController: NavHostController = rememberNavController(),
    externalNavigationRequest: ExternalNavigationRequest? = null,
    onExternalNavigationConsumed: (Long) -> Unit = {},
    onThemeChanged: (String) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
) {
    var requestedWorldTemplateId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(externalNavigationRequest) {
        val request = externalNavigationRequest ?: return@LaunchedEffect
        when (val target = request.target) {
            ExternalNavigationTarget.NewSession -> {
                navController.navigateToExternalRoot(Routes.SESSION_LIST)
                return@LaunchedEffect
            }
            ExternalNavigationTarget.Characters -> navController.navigateToExternalRoot(Routes.CHARACTER_LIST)
            ExternalNavigationTarget.Encyclopedia -> navController.navigateToExternalRoot(Routes.ENCYCLOPEDIA_LIST)
            is ExternalNavigationTarget.Chat -> navController.navigateToExternalRoot(Routes.chat(target.sessionId))
        }
        onExternalNavigationConsumed(request.id)
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SESSION_LIST,
        enterTransition = NavAnimations.enterTransition(),
        exitTransition = NavAnimations.exitTransition(),
        popEnterTransition = NavAnimations.popEnterTransition(),
        popExitTransition = NavAnimations.popExitTransition()
    ) {
        composable(Routes.SESSION_LIST) {
            SessionListScreen(
                navController = navController,
                newSessionRequestId = externalNavigationRequest
                    ?.takeIf { it.target == ExternalNavigationTarget.NewSession }
                    ?.id,
                onNewSessionRequestConsumed = onExternalNavigationConsumed,
                newSessionTemplateId = requestedWorldTemplateId,
                onNewSessionTemplateConsumed = { requestedWorldTemplateId = null },
                onSessionClick = { sessionId -> navController.navigateSingleTop(Routes.chat(sessionId)) },
                onCharactersClick = { navController.navigateToMainTab(Routes.CHARACTER_LIST) },
                onEncyclopediaClick = { navController.navigateToMainTab(Routes.ENCYCLOPEDIA_LIST) },
                onSettingsClick = { navController.navigateToModelSettings() },
                onGenerationTasksClick = { navController.navigateSingleTop(Routes.GENERATION_TASKS) },
            )
        }

        composable(Routes.CREATION_HUB) {
            CreationHubScreen(navController)
        }

        composable(Routes.STORY_SIMULATION) {
            StorySimulationScreen(
                navController = navController,
                onOpenSession = { sessionId ->
                    navController.navigate(Routes.chat(sessionId)) {
                        launchSingleTop = true
                        popUpTo(Routes.STORY_SIMULATION) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.GENERATION_TASKS) {
            GenerationTaskListScreen(onBack = { navController.popBackStack() }, onOpenResult = { target ->
                val route = when (target) {
                    is com.mojing.app.domain.generation.GenerationResultTarget.Character -> Routes.characterEdit(target.id)
                    is com.mojing.app.domain.generation.GenerationResultTarget.World -> Routes.templateEdit(target.id)
                    is com.mojing.app.domain.generation.GenerationResultTarget.Encyclopedia -> Routes.encyclopediaDetail(target.id)
                }
                navController.navigateSingleTop(route)
            })
        }

        composable(
            Routes.CHAT,
            arguments = chatRouteArguments()
        ) { entry ->
            val sessionId = validatedRouteId(
                entry.arguments?.takeIf { it.containsKey("sessionId") }?.getLong("sessionId"),
            )
            if (sessionId == null) {
                InvalidRouteRedirect(navController, Routes.SESSION_LIST)
                return@composable
            }
            val chatModel = com.mojing.app.ui.chat.retainedChatViewModel(entry, sessionId)
            ChatScreen(
                viewModel = chatModel,
                sessionId = sessionId,
                backLabel = if ((entry.arguments?.getLong("sourceMessageId") ?: 0L) > 0L) "返回百科" else "返回会话主页",
                onBack = {
                    if ((entry.arguments?.getLong("sourceMessageId") ?: 0L) > 0L) navController.popBackStack()
                    else navController.returnToSessionHome()
                },
            )
        }

        composable(Routes.CHARACTER_LIST) {
            CharacterListScreen(
                navController = navController,
                onEdit = { navController.navigateSingleTop(Routes.characterEdit(it)) },
                onChat = { navController.navigateSingleTop(Routes.chat(it)) },
                onSettingsClick = { navController.navigateToModelSettings() }
            )
        }

        composable(
            Routes.CHARACTER_EDIT,
            arguments = listOf(navArgument("characterId") { type = NavType.LongType })
        ) { entry ->
            val charId = validatedRouteId(
                entry.arguments?.takeIf { it.containsKey("characterId") }?.getLong("characterId"),
                allowZero = true,
            )
            if (charId == null) {
                InvalidRouteRedirect(navController, Routes.CHARACTER_LIST)
                return@composable
            }
            CharacterEditScreen(
                characterId = charId,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigateToModelSettings() },
            )
        }

        composable(Routes.ENCYCLOPEDIA_LIST) {
            EncyclopediaScreen(
                navController = navController,
                onDetail = { navController.navigateSingleTop(Routes.encyclopediaDetail(it)) },
                onWorldSettings = { navController.navigateSingleTop(Routes.worldSettings(it)) },
                onSettingsClick = { navController.navigateToModelSettings() },
                onGenerationTasksClick = { navController.navigateSingleTop(Routes.GENERATION_TASKS) },
            )
        }

        composable(
            Routes.ENCYCLOPEDIA_DETAIL,
            arguments = listOf(navArgument("encId") { type = NavType.LongType })
        ) { entry ->
            val encId = validatedRouteId(
                entry.arguments?.takeIf { it.containsKey("encId") }?.getLong("encId"),
            )
            if (encId == null) {
                InvalidRouteRedirect(navController, Routes.ENCYCLOPEDIA_LIST)
                return@composable
            }
            EncyclopediaDetailScreen(
                encyclopediaId = encId,
                onEditEntry = { entryId -> navController.navigateSingleTop(Routes.entryEdit(encId, entryId)) },
                onBack = { navController.popBackStack() },
                onOpenGenerationTasks = { navController.navigateSingleTop(Routes.GENERATION_TASKS) },
                onOpenSettings = { navController.navigateToModelSettings() },
            )
        }

        composable(
            Routes.ENTRY_EDIT,
            arguments = listOf(
                navArgument("encId") { type = NavType.LongType },
                navArgument("entryId") { type = NavType.LongType }
            )
        ) { entry ->
            val encId = validatedRouteId(
                entry.arguments?.takeIf { it.containsKey("encId") }?.getLong("encId"),
            )
            val entryId = validatedRouteId(
                entry.arguments?.takeIf { it.containsKey("entryId") }?.getLong("entryId"),
                allowZero = true,
            )
            if (encId == null || entryId == null) {
                InvalidRouteRedirect(navController, Routes.ENCYCLOPEDIA_LIST)
                return@composable
            }
            EntryEditScreen(
                encyclopediaId = encId,
                entryId = entryId,
                onOpenSource = { source -> navController.navigateSingleTop(Routes.chatSource(source.sessionId, source.messageId, source.branchId)) },
                onOpenSavedEntry = { savedId -> navController.navigate(Routes.entryEdit(encId, savedId)) {
                    popUpTo(entry.destination.id) { inclusive = true }
                    launchSingleTop = true
                } },
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigateToModelSettings() },
            )
        }

        composable(Routes.WORKBENCH) {
            WorkbenchScreen(
                navController = navController,
                onEditTemplate = { navController.navigateSingleTop(Routes.templateEdit(it)) },
                onOpenCanonical = { navController.navigateSingleTop(Routes.encyclopediaDetail(it)) },
                onStartChat = { templateId ->
                    requestedWorldTemplateId = templateId
                    navController.navigateToMainTab(Routes.SESSION_LIST)
                },
                onSettingsClick = { navController.navigateToModelSettings() }
            )
        }

        composable(
            Routes.TEMPLATE_EDIT,
            arguments = listOf(navArgument("templateId") { type = NavType.LongType })
        ) { entry ->
            val templateId = validatedRouteId(
                entry.arguments?.takeIf { it.containsKey("templateId") }?.getLong("templateId"),
                allowZero = true,
            )
            if (templateId == null) {
                InvalidRouteRedirect(navController, Routes.WORKBENCH)
                return@composable
            }
            TemplateEditScreen(
                templateId = templateId,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigateToModelSettings() },
            )
        }

        composable(
            Routes.WORLD_SETTINGS,
            arguments = listOf(navArgument("worldId") { type = NavType.LongType }),
        ) { entry ->
            val worldId = validatedRouteId(entry.arguments?.getLong("worldId"))
            if (worldId == null) InvalidRouteRedirect(navController, Routes.ENCYCLOPEDIA_LIST)
            else WorldSettingsScreen(worldId = worldId, onBack = { navController.popBackStack() })
        }

        composable("usage?notice={notice}", arguments = listOf(
            navArgument("notice") { type = NavType.StringType; defaultValue = "请重新选择平台或模型。" },
        )) { entry ->
            UsageSummaryDestination(
                navController = navController,
                notice = entry.arguments?.getString("notice").orEmpty().ifBlank { "请重新选择平台或模型。" },
            )
        }
        composable("usage/platform?platformId={platformId}&platformName={platformName}", arguments = listOf(
            navArgument("platformId") { type = NavType.StringType; defaultValue = "" },
            navArgument("platformName") { type = NavType.StringType; defaultValue = "" },
        )) { entry ->
            val platformId = entry.arguments?.getString("platformId").orEmpty()
            val platformName = entry.arguments?.getString("platformName").orEmpty()
            if (platformId.isBlank()) {
                InvalidUsageRouteRedirect(navController, "平台信息已失效，请重新选择平台。")
            } else {
                com.mojing.app.ui.settings.usage.UsageScreen(viewModel = androidx.hilt.navigation.compose.hiltViewModel(),
                    platformId = platformId, platformName = platformName, onBack = { navController.popBackStack() },
                    onModel = { model -> navController.navigate("usage/model?platformId=${android.net.Uri.encode(platformId)}&platformName=${android.net.Uri.encode(platformName)}&modelName=${android.net.Uri.encode(model.name)}") })
            }
        }
        composable("usage/model?platformId={platformId}&platformName={platformName}&modelName={modelName}", arguments = listOf(
            navArgument("platformId") { type = NavType.StringType; defaultValue = "" },
            navArgument("platformName") { type = NavType.StringType; defaultValue = "" },
            navArgument("modelName") { type = NavType.StringType; defaultValue = "" },
        )) { entry ->
            val platformId = entry.arguments?.getString("platformId").orEmpty()
            val platformName = entry.arguments?.getString("platformName").orEmpty()
            val modelName = entry.arguments?.getString("modelName").orEmpty()
            if (platformId.isBlank() || modelName.isBlank()) {
                InvalidUsageRouteRedirect(navController, "平台或模型信息已失效，请重新选择。")
            } else {
                com.mojing.app.ui.settings.usage.UsageScreen(viewModel = androidx.hilt.navigation.compose.hiltViewModel(),
                    platformId = platformId, platformName = platformName,
                    modelName = modelName, onBack = { navController.popBackStack() })
            }
        }
        composable(Routes.SETTINGS) { entry ->
            val modelRequested by entry.savedStateHandle
                .getStateFlow(SETTINGS_MODEL_REQUEST, false).collectAsStateWithLifecycle()
            SettingsScreen(
                requestModelSection = modelRequested,
                onModelRequestConsumed = { entry.savedStateHandle[SETTINGS_MODEL_REQUEST] = false },
                navController = navController,
                onThemeChanged = onThemeChanged,
                onFontScaleChanged = onFontScaleChanged,
            )
        }
    }
}
