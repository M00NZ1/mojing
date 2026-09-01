package com.mojing.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val QUICK_EMOJIS: List<String> = listOf(
    "😀", "😃", "😄", "😁", "😅", "🤣", "😂", "🙂", "😉", "😊",
    "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛", "😜", "🤪",
    "🤔", "🤨", "😐", "😑", "😶", "🙄", "😏", "😣", "😥", "😮",
    "🤐", "😯", "😪", "😫", "🥱", "😴", "😌", "🤤",
    "😒", "😓", "😔", "😕", "🙃", "🤑", "😲", "☹️", "🙁", "😖",
    "😞", "😟", "😤", "😢", "😭", "😦", "😧", "😨", "😩", "🤯",
    "😬", "😰", "😱", "🥵", "🥶", "😳", "😵", "🥴", "😠",
    "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🙌", "👏", "🤝",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔", "💕", "💖",
    "🔥", "✨", "⭐", "🌟", "💫", "💯", "✅", "❌", "❓", "❗",
    "🎉", "🎊", "🎁", "🏆", "🥇", "🎯", "📝", "📌", "🔔", "💤",
).distinct()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    if (!visible) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "点击插入表情",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 48.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                items(
                    count = QUICK_EMOJIS.size,
                    key = { index -> index },
                ) { index ->
                    val emoji = QUICK_EMOJIS[index]
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                onEmojiSelected(emoji)
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}
