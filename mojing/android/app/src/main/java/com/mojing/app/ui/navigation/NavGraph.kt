package com.mojing.app.ui.navigation

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            ChatScreen(
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
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigateToModelSettings() },
            )
        }

        composable(Routes.WORKBENCH) {
            WorkbenchScreen(
                navController = navController,
                onEditTemplate = { navController.navigateSingleTop(Routes.templateEdit(it)) },
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
