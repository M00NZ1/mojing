package com.mojing.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun BranchSelector(
    expanded: Boolean,
    onDismiss: () -> Unit,
    branches: List<Pair<String, String>>,
    currentBranch: String,
    onSelect: (String) -> Unit,
    onShowBranchOverview: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("从当前位置创建故事线") },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
            onClick = {
                onSelect("CREATE_NEW")
                onDismiss()
            },
        )
        if (onShowBranchOverview != null) {
            DropdownMenuItem(
                text = { Text("查看全部故事线") },
                leadingIcon = { Icon(Icons.Default.AccountTree, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onShowBranchOverview()
                },
            )
        }
        HorizontalDivider()
        branches.distinctBy { it.first }.forEach { (id, label) ->
            val isCurrent = id == currentBranch
            DropdownMenuItem(
                text = {
                    Text(
                        storyLineDisplayLabel(id, label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = if (isCurrent) {
                    {
                        Text(
                            "当前",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    null
                },
                onClick = {
                    if (!isCurrent) onSelect(id)
                    onDismiss()
                },
            )
        }
    }
}
