package com.mojing.app.ui.common

import com.mojing.app.ui.common.MoJingIcon as Icon
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage

@Composable
fun ImagePreviewDialog(imageUrl: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var revision by remember(imageUrl) { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        com.mojing.app.ui.theme.DialogSystemBarAppearance(Color.Black)
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("image_preview"),
            color = Color.Black,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                key(imageUrl, revision) {
                    SubcomposeAsyncImage(
                        model = remember(imageUrl, revision) { avatarImageModel(context, imageUrl) },
                        contentDescription = "图片预览",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 48.dp),
                        contentScale = ContentScale.Fit,
                        loading = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.White)
                            }
                        },
                        error = {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("图片无法读取", color = Color.White)
                                TextButton(
                                    onClick = { revision++ },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                                ) {
                                    Text("重新加载")
                                }
                            }
                        },
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp),
                ) {
                    Icon(Icons.Outlined.Close, "关闭图片预览", tint = Color.White)
                }
            }
        }
    }
}
