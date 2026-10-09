package com.mojing.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.media.newmedia.SpeechPlaybackControl

/**
 * Compact controls for the single speech playback owner.
 *
 * The bar deliberately receives an immutable snapshot and callbacks only. It
 * does not start, stop, or otherwise own playback itself.
 */
@Composable
fun SpeechPlaybackBar(
    snapshot: SpeechPlaybackControl.Snapshot,
    voiceRequestLabel: String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshot.phase == SpeechPlaybackControl.Phase.IDLE) return

    val status = when (snapshot.phase) {
        SpeechPlaybackControl.Phase.PREPARING -> "准备朗读"
        SpeechPlaybackControl.Phase.PLAYING -> "正在朗读"
        SpeechPlaybackControl.Phase.PAUSED -> "已暂停"
        SpeechPlaybackControl.Phase.IDLE -> return
    }
    val hasSegmentPosition = snapshot.segmentIndex > 0 && snapshot.segmentCount > 0
    val canPause = snapshot.phase == SpeechPlaybackControl.Phase.PREPARING ||
        snapshot.phase == SpeechPlaybackControl.Phase.PLAYING
    val canResume = snapshot.phase == SpeechPlaybackControl.Phase.PAUSED

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (hasSegmentPosition) {
                    Text(
                        text = "第${snapshot.segmentIndex}/${snapshot.segmentCount}段",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (snapshot.resumedFromSegmentStart) {
                    Text(
                        text = "本段从头继续",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (voiceRequestLabel.isNotBlank()) {
                    Text(
                        text = voiceRequestLabel,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(
                modifier = Modifier.size(48.dp),
                enabled = canPause,
                onClick = onPause,
            ) {
                Icon(Icons.Outlined.Pause, contentDescription = "暂停朗读")
            }
            IconButton(
                modifier = Modifier.size(48.dp),
                enabled = canResume,
                onClick = onResume,
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = "继续朗读")
            }
            IconButton(
                modifier = Modifier.size(48.dp),
                enabled = true,
                onClick = onStop,
            ) {
                Icon(Icons.Outlined.Stop, contentDescription = "停止朗读")
            }
        }
    }
}
