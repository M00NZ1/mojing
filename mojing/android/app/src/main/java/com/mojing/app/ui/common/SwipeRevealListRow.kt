package com.mojing.app.ui.common

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    onRename: (() -> Unit)? = null,
    menuExtras: (@Composable (dismissMenu: () -> Unit) -> Unit)? = null,
    showMenuButton: Boolean = false,
    pinEnabled: Boolean = true,
    clickEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var offsetPx by remember { mutableStateOf(0f) }
    LaunchedEffect(swipeEnabled, clickEnabled) {
        if (!swipeEnabled || !clickEnabled) offsetPx = 0f
        if (!clickEnabled) menuExpanded = false
    }

    val cardShape = MaterialTheme.shapes.small
    val pinBg = MaterialTheme.colorScheme.primary
    val delBg = MaterialTheme.colorScheme.errorContainer

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
    ) {
        val rowWidth = maxWidth
        val actionW = remember(maxWidth) {
            (maxWidth * 0.22f).coerceIn(68.dp, 96.dp)
        }
        val revealWidth = with(LocalDensity.current) { (actionW * if (showPinAction) 2 else 1).toPx() }
        Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape).clip(cardShape)) {
                Row(
                    modifier = Modifier
                        .matchParentSize()
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
                                .clickable(enabled = pinEnabled && clickEnabled, interactionSource = pinInteraction, indication = null) {
                                    onPinToggle()
                                    offsetPx = 0f
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                Icon(Icons.Outlined.PushPin, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                                Text(
                                    if (isPinned) "取消置顶" else "置顶",
                                    color = MaterialTheme.colorScheme.onPrimary,
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
                            .clickable(enabled = clickEnabled, interactionSource = delInteraction, indication = null) {
                                onDelete()
                                offsetPx = 0f
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                            Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(22.dp))
                            Text("删除", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(offsetPx.roundToInt(), 0) }
                        .width(rowWidth)
                        .heightIn(min = 72.dp)
                        .draggable(
                            orientation = Orientation.Horizontal,
                            enabled = swipeEnabled && clickEnabled,
                            state = rememberDraggableState { delta ->
                                offsetPx = (offsetPx + delta).coerceIn(-revealWidth, 0f)
                            },
                            onDragStopped = { velocity ->
                                offsetPx = when {
                                    velocity > 400f -> 0f
                                    velocity < -400f -> -revealWidth
                                    offsetPx < -revealWidth / 2 -> -revealWidth
                                    else -> 0f
                                }
                            },
                        )
                        .clip(cardShape)
                        .background(MaterialTheme.colorScheme.surface, cardShape)
                        .combinedClickable(
                            enabled = clickEnabled,
                            onClick = {
                                if (offsetPx != 0f) {
                                    offsetPx = 0f
                                } else {
                                    onClick()
                                }
                            },
                            onLongClick = { menuExpanded = true },
                        ),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f)) { content() }
                        if (showMenuButton) IconButton(enabled = clickEnabled, onClick = { menuExpanded = true; offsetPx = 0f }) {
                            Icon(Icons.Outlined.MoreHoriz, "更多操作")
                        }
                    }
                }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = DpOffset(12.dp, 0.dp),
        ) {
            if (showPinAction) {
                DropdownMenuItem(
                    text = { Text(if (isPinned) "取消置顶" else "置顶") },
                    enabled = pinEnabled && clickEnabled,
                    onClick = {
                        menuExpanded = false
                        offsetPx = 0f
                        onPinToggle()
                    },
                )
            }
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    enabled = clickEnabled,
                    onClick = {
                        menuExpanded = false
                        offsetPx = 0f
                        onRename()
                    },
                )
            }
            menuExtras?.invoke { menuExpanded = false; offsetPx = 0f }
            DropdownMenuItem(
                text = { Text("删除") },
                enabled = clickEnabled,
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
            )
        }
    }
}
