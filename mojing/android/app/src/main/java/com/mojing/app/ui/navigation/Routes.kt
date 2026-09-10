package com.mojing.app.ui.navigation

import androidx.navigation.NavType
import androidx.navigation.navArgument

internal fun chatRouteArguments() = listOf(
    navArgument("sessionId") { type = NavType.LongType },
    navArgument("sourceMessageId") { type = NavType.LongType; defaultValue = 0L },
    navArgument("sourceBranchId") { type = NavType.StringType; defaultValue = "" },
)

object Routes {
    const val SESSION_LIST = "sessions"
    const val CHAT = "chat/{sessionId}?sourceMessageId={sourceMessageId}&sourceBranchId={sourceBranchId}"
    const val CREATION_HUB = "create"
    const val CHARACTER_LIST = "characters"
    const val CHARACTER_EDIT = "characters/edit/{characterId}"
    const val ENCYCLOPEDIA_LIST = "encyclopedias"
    const val ENCYCLOPEDIA_DETAIL = "encyclopedias/{encId}"
    const val ENTRY_EDIT = "encyclopedias/{encId}/entries/{entryId}"
    const val WORKBENCH = "workbench"
    const val TEMPLATE_EDIT = "workbench/edit/{templateId}"
    const val SETTINGS = "settings"
    const val GENERATION_TASKS = "generation_tasks"
    const val STORY_SIMULATION = "story_simulation"

    fun chat(sessionId: Long) = "chat/$sessionId"
    fun chatSource(sessionId: Long, messageId: Long, branchId: String) =
        "chat/$sessionId?sourceMessageId=$messageId&sourceBranchId=${android.net.Uri.encode(branchId)}"
    fun characterEdit(id: Long) = "characters/edit/$id"
    fun encyclopediaDetail(encId: Long) = "encyclopedias/$encId"
    fun entryEdit(encId: Long, entryId: Long) = "encyclopedias/$encId/entries/$entryId"
    fun templateEdit(templateId: Long) = "workbench/edit/$templateId"
}

internal fun validatedRouteId(value: Long?, allowZero: Boolean = false): Long? =
    value?.takeIf { if (allowZero) it >= 0L else it > 0L }
