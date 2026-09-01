package com.mojing.app.ui.navigation

internal object ExternalNavigationContract {
    const val EXTRA_NAVIGATE_TO = "navigate_to"
    const val EXTRA_SESSION_ID = "session_id"
    const val DESTINATION_CHAT = "chat"
    const val DESTINATION_ENCYCLOPEDIA = "encyclopedia"
}

internal sealed interface ExternalNavigationTarget {
    data object NewSession : ExternalNavigationTarget
    data object Characters : ExternalNavigationTarget
    data object Encyclopedia : ExternalNavigationTarget
    data class Chat(val sessionId: Long) : ExternalNavigationTarget
}

internal data class ExternalNavigationRequest(
    val id: Long,
    val target: ExternalNavigationTarget,
)

internal fun parseExternalNavigationTarget(
    deepLinkHost: String?,
    navigateTo: String?,
    sessionId: Long?,
): ExternalNavigationTarget? = when ((deepLinkHost ?: navigateTo)?.trim()?.lowercase()) {
    "new_chat", "new_session" -> ExternalNavigationTarget.NewSession
    "characters" -> ExternalNavigationTarget.Characters
    ExternalNavigationContract.DESTINATION_ENCYCLOPEDIA, "encyclopedias" -> ExternalNavigationTarget.Encyclopedia
    ExternalNavigationContract.DESTINATION_CHAT -> sessionId?.takeIf { it > 0L }?.let(ExternalNavigationTarget::Chat)
    else -> null
}
