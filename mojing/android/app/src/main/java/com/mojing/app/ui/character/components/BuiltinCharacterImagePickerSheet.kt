package com.mojing.app.ui.character.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mojing.app.media.BuiltinCharacterImages
import com.mojing.app.media.BuiltinPresetCharacterImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuiltinCharacterImagePickerSheet(
    visible: Boolean,
    title: String,
    verticalCardPreview: Boolean,
    onDismiss: () -> Unit,
    onPickPreset: (String) -> Unit = {},
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var presets by remember { mutableStateOf<List<BuiltinPresetCharacterImage>>(emptyList()) }
    LaunchedEffect(visible) {
        if (visible) {
            presets = BuiltinCharacterImages.listLocalPresets(context)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .padding(bottom = 24.dp)
                .heightIn(max = 720.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (presets.isEmpty()) {
                Text(
                    "assets/character_presets/ 下还没有图片（支持 png / jpg / webp）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp),
                ) {
                    items(presets, key = { it.assetFileName }) { item: BuiltinPresetCharacterImage ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPickPreset(item.assetFileName)
                                    onDismiss()
                                },
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(Uri.parse(BuiltinCharacterImages.presetAssetUri(item.assetFileName)))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = item.label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(if (verticalCardPreview) 2f / 3f else 1f),
                                contentScale = ContentScale.Crop,
                            )
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
