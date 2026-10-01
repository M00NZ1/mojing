package com.mojing.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Composable
fun MoJingFilterChip(
    selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.extraSmall,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(
        containerColor = if (MaterialTheme.colorScheme.background == com.mojing.app.ui.theme.MoJingDesignTokens.background)
            com.mojing.app.ui.theme.MoJingDesignTokens.chipBackground else MaterialTheme.colorScheme.surfaceContainerLow,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary,
    ),
    elevation: SelectableChipElevation? = null,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    FilterChip(selected, onClick, { ProvideTextStyle(MaterialTheme.typography.labelMedium) { label() } },
        modifier, enabled, leadingIcon, trailingIcon, shape, colors, elevation, border, interactionSource)
}
