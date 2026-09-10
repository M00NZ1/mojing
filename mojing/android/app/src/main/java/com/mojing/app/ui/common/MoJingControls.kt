package com.mojing.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

@Composable
fun MoJingTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null, placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null, suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = MaterialTheme.shapes.medium, colors: TextFieldColors = fieldColors(),
) {
    OutlinedTextField(value, onValueChange, modifier, enabled, readOnly, textStyle,
        label, placeholder, leadingIcon, trailingIcon, prefix, suffix, supportingText, isError,
        visualTransformation, keyboardOptions, keyboardActions, singleLine, maxLines, minLines,
        interactionSource, shape, colors)
}

@Composable
fun MoJingTextField(
    value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null, placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null, suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = MaterialTheme.shapes.medium, colors: TextFieldColors = fieldColors(),
) {
    OutlinedTextField(value, onValueChange, modifier, enabled, readOnly, textStyle,
        label, placeholder, leadingIcon, trailingIcon, prefix, suffix, supportingText, isError,
        visualTransformation, keyboardOptions, keyboardActions, singleLine, maxLines, minLines,
        interactionSource, shape, colors)
}

@Composable
fun MoJingButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick, modifier.heightIn(min = 48.dp), enabled, shape, colors,
        elevation, border, contentPadding, interactionSource, content)
}

@Composable
fun MoJingOutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled, shape, colors,
        elevation, border, contentPadding, interactionSource, content)
}

@Composable
fun MoJingTonalButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(onClick, modifier.heightIn(min = 48.dp), enabled, shape, colors,
        elevation, border, contentPadding, interactionSource, content)
}
