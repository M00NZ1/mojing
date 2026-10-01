package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RoundChoicesRow(
    choices: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (choices.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            "可选行动",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        choices.forEach { choice ->
            ChoiceCard(choice = choice, onClick = { onSelect(choice) })
        }
    }
}
