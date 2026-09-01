package com.mojing.app.ui.character.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerField(
    selectedColor: String,
    onColorSelected: (String) -> Unit
) {
    val presetColors = listOf(
        "#F97316", "#EF4444", "#EC4899", "#A855F7", "#6C5CE7",
        "#3B82F6", "#06B6D4", "#10B981", "#84CC16", "#FACC15",
        "#F59E0B", "#78716C", "#6366F1", "#14B8A6", "#E11D48"
    )

    Column {
        Text("头像颜色", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(try { Color(android.graphics.Color.parseColor(selectedColor)) } catch (_: Exception) { Color.Gray })
        )

        Spacer(Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presetColors.forEach { colorHex ->
                val color = try { Color(android.graphics.Color.parseColor(colorHex)) } catch (_: Exception) { Color.Gray }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (selectedColor == colorHex) 3.dp else 0.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                        .clickable { onColorSelected(colorHex) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = selectedColor,
            onValueChange = onColorSelected,
            label = { Text("自定义色号") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("#RRGGBB") }
        )
    }
}
