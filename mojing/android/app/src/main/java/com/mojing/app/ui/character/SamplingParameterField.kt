package com.mojing.app.ui.character

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.mojing.app.ui.common.MoJingTextField

internal fun samplingParameterError(value: String, integer: Boolean = false): String? = when {
    value.isBlank() -> "请输入数值"
    integer && (value.trim().toIntOrNull()?.let { it > 0 } != true) -> "请输入正整数"
    !integer && (value.trim().toFloatOrNull()?.isFinite() != true) -> "请输入有效数字"
    else -> null
}

@Composable
internal fun SamplingParameterField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    integer: Boolean = false,
    signed: Boolean = false,
    description: String? = null,
) {
    val error = samplingParameterError(value, integer)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    MoJingTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        // 部分数字键盘不提供负号，带正负值的参数使用完整键盘。
        keyboardOptions = KeyboardOptions(keyboardType = when {
            signed -> KeyboardType.Text
            integer -> KeyboardType.Number
            else -> KeyboardType.Decimal
        }, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            focusManager.clearFocus()
            keyboard?.hide()
        }),
        isError = error != null,
        supportingText = (error ?: description)?.let { hint -> { Text(hint) } },
    )
}
