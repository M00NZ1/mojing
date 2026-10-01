package com.mojing.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mojing.app.ui.theme.MoJingDesignTokens

enum class MoJingVisualStatus { Complete, Generating, Waiting, Stopped, Failure }

/** Presentation only; status owners and persisted values stay unchanged. */
@Composable
fun MoJingVisualStatus.color(): Color {
    val scheme = MaterialTheme.colorScheme
    if (scheme.background != MoJingDesignTokens.background) return when (this) {
        MoJingVisualStatus.Complete -> scheme.secondary
        MoJingVisualStatus.Generating -> scheme.primary
        MoJingVisualStatus.Waiting, MoJingVisualStatus.Stopped -> scheme.onSurfaceVariant
        MoJingVisualStatus.Failure -> scheme.error
    }
    return when (this) {
        MoJingVisualStatus.Complete -> MoJingDesignTokens.success
        MoJingVisualStatus.Generating -> MoJingDesignTokens.generating
        MoJingVisualStatus.Waiting -> MoJingDesignTokens.textTertiary
        MoJingVisualStatus.Stopped -> MoJingDesignTokens.warning
        MoJingVisualStatus.Failure -> MoJingDesignTokens.danger
    }
}
