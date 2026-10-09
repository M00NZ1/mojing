package com.mojing.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    errorBorderColor = MaterialTheme.colorScheme.error,
    errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoJingTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall,
    label: @Composable (() -> Unit)? = null, placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null, suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = MaterialTheme.shapes.small, colors: TextFieldColors = fieldColors(),
    inputModifier: Modifier = Modifier,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    FieldFrame(modifier, label, enabled, isError) {
    Box((if (label == null) modifier else Modifier.fillMaxWidth()).heightIn(min = 48.dp).padding(vertical = 2.dp),
        contentAlignment = Alignment.TopStart, propagateMinConstraints = true) {
        BasicTextField(
            value = value, onValueChange = onValueChange,
            modifier = inputModifier.fillMaxWidth().heightIn(min = if (minLines > 1) 112.dp else 44.dp),
            enabled = enabled, readOnly = readOnly,
            textStyle = textStyle.copy(color = if (textStyle.color != Color.Unspecified) textStyle.color
                else if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant),
            cursorBrush = SolidColor(if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
            visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines, interactionSource = source,
            decorationBox = { inner ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value, innerTextField = inner, enabled = enabled, singleLine = singleLine,
                    visualTransformation = visualTransformation, interactionSource = source, isError = isError,
                    label = null, placeholder = placeholder ?: label?.let { fallback ->
                        { Box(Modifier.clearAndSetSemantics {}) { fallback() } }
                    }, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
                    prefix = prefix, suffix = suffix, supportingText = supportingText, colors = colors,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    container = { OutlinedTextFieldDefaults.Container(enabled = enabled, isError = isError,
                        interactionSource = source, colors = colors, shape = shape,
                        focusedBorderThickness = 1.5.dp, unfocusedBorderThickness = 1.dp) },
                )
            },
        )
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoJingTextField(
    value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall,
    label: @Composable (() -> Unit)? = null, placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null, suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = MaterialTheme.shapes.small, colors: TextFieldColors = fieldColors(),
    inputModifier: Modifier = Modifier,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    FieldFrame(modifier, label, enabled, isError) {
    Box((if (label == null) modifier else Modifier.fillMaxWidth()).heightIn(min = 48.dp).padding(vertical = 2.dp),
        contentAlignment = Alignment.TopStart, propagateMinConstraints = true) {
        BasicTextField(
            value = value, onValueChange = onValueChange,
            modifier = inputModifier.fillMaxWidth().heightIn(min = if (minLines > 1) 112.dp else 44.dp),
            enabled = enabled, readOnly = readOnly,
            textStyle = textStyle.copy(color = if (textStyle.color != Color.Unspecified) textStyle.color
                else if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant),
            cursorBrush = SolidColor(if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
            visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines, interactionSource = source,
            decorationBox = { inner ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value.text, innerTextField = inner, enabled = enabled, singleLine = singleLine,
                    visualTransformation = visualTransformation, interactionSource = source, isError = isError,
                    label = null, placeholder = placeholder ?: label?.let { fallback ->
                        { Box(Modifier.clearAndSetSemantics {}) { fallback() } }
                    }, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
                    prefix = prefix, suffix = suffix, supportingText = supportingText, colors = colors,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    container = { OutlinedTextFieldDefaults.Container(enabled = enabled, isError = isError,
                        interactionSource = source, colors = colors, shape = shape,
                        focusedBorderThickness = 1.5.dp, unfocusedBorderThickness = 1.dp) },
                )
            },
        )
    }
    }
}

/** Stable labels stay above the writing surface, including while the keyboard is open. */
@Composable
private fun FieldFrame(
    modifier: Modifier,
    label: @Composable (() -> Unit)?,
    enabled: Boolean,
    isError: Boolean,
    content: @Composable () -> Unit,
) {
    if (label == null) {
        content()
        return
    }
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                isError -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
            ProvideTextStyle(MaterialTheme.typography.labelMedium.copy(color = color)) { label() }
        content()
    }
}

@Composable
fun MoJingToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ).heightIn(min = 48.dp).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun MoJingButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.buttonColors(
        disabledContainerColor = if (MaterialTheme.colorScheme.background == com.mojing.app.ui.theme.MoJingDesignTokens.background) com.mojing.app.ui.theme.MoJingDesignTokens.disabledBackground else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        disabledContentColor = if (MaterialTheme.colorScheme.background == com.mojing.app.ui.theme.MoJingDesignTokens.background) com.mojing.app.ui.theme.MoJingDesignTokens.textTertiary else MaterialTheme.colorScheme.onSurfaceVariant),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.99f else 1f,
        tween(durationMillis = 110, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)), label = "buttonPress")
    val buttonColors = if (pressed && colors.containerColor == com.mojing.app.ui.theme.MoJingDesignTokens.primary)
        colors.copy(containerColor = com.mojing.app.ui.theme.MoJingDesignTokens.primaryPressed) else colors
    Button(onClick, modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }, enabled, shape ?: MaterialTheme.shapes.small, buttonColors,
        elevation, border, contentPadding, source, content)
}

@Composable
fun MoJingOutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.99f else 1f,
        tween(durationMillis = 110, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)), label = "buttonPress")
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }, enabled, shape ?: MaterialTheme.shapes.small, colors,
        elevation, border, contentPadding, source, content)
}

@Composable
fun MoJingTonalButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.99f else 1f,
        tween(durationMillis = 110, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)), label = "buttonPress")
    FilledTonalButton(onClick, modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }, enabled, shape ?: MaterialTheme.shapes.small, colors,
        elevation, border, contentPadding, source, content)
}
