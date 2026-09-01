package com.mojing.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 列表行左滑露出「置顶 / 删除」；长按菜单备用。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SwipeRevealListRow(
    swipeEnabled: Boolean,
    showPinAction: Boolean = true,
    isPinned: Boolean,
    onPinToggle: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuExtras: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            value != SwipeToDismissBoxValue.StartToEnd
        },
    )

    val pinBg = Color(0xFF3D4FA8)
    val delBg = Color(0xFFC62828)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        val actionW = remember(maxWidth) {
            (maxWidth * 0.22f).coerceIn(68.dp, 96.dp)
        }
        SwipeToDismissBox(
            modifier = Modifier.fillMaxWidth(),
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = swipeEnabled,
            backgroundContent = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showPinAction) {
                        val pinInteraction = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .width(actionW)
                                .fillMaxHeight()
                                .background(pinBg)
                                .clickable(interactionSource = pinInteraction, indication = null) {
                                    onPinToggle()
                                    scope.launch { dismissState.snapTo(SwipeToDismissBoxValue.Settled) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                Icon(Icons.Default.PushPin, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                Text(
                                    if (isPinned) "取消置顶" else "置顶",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    val delInteraction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .width(actionW)
                            .fillMaxHeight()
                            .background(delBg)
                            .clickable(interactionSource = delInteraction, indication = null) {
                                onDelete()
                                scope.launch { dismissState.snapTo(SwipeToDismissBoxValue.Settled) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                            Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Text("删除", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            },
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .combinedClickable(
                            onClick = {
                                if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                    scope.launch { dismissState.snapTo(SwipeToDismissBoxValue.Settled) }
                                } else {
                                    onClick()
                                }
                            },
                            onLongClick = { menuExpanded = true },
                        ),
                ) {
                    content()
                }
            },
        )

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = DpOffset(12.dp, 0.dp),
        ) {
            if (showPinAction) {
                DropdownMenuItem(
                    text = { Text(if (isPinned) "取消置顶" else "置顶") },
                    onClick = {
                        menuExpanded = false
                        scope.launch { dismissState.snapTo(SwipeToDismissBoxValue.Settled) }
                        onPinToggle()
                    },
                )
            }
            menuExtras?.invoke()
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
            )
        }
    }
}
