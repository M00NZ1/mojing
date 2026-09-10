package com.mojing.app.ui.character.components

import com.mojing.app.ui.common.MoJingButton as Button
import com.mojing.app.ui.common.MoJingOutlinedButton as OutlinedButton

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mojing.app.util.UsbSessionLog
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_ZOOM_FACTOR = 6f

private fun clampCropPan(vwI: Int, vhI: Int, bw: Int, bh: Int, s: Float, tl: Offset): Offset {
    if (vwI <= 0 || vhI <= 0) return tl
    val px = tl.x.coerceIn(vwI - bw * s, 0f)
    val py = tl.y.coerceIn(vhI - bh * s, 0f)
    return Offset(px, py)
}

/**
 * 全屏 2∶3 裁切：单指拖动平移，双指缩放（或右侧 +/-）；九宫格参考线；确定后由调用方按视口参数落盘。
 * 顶栏、底栏为浮层，不占 Column 纵向堆叠，避免小屏/大字体系下按钮被顶出屏幕。
 */
@Composable
fun CardImageCropSheet(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onConfirm: (scale: Float, topLeftXPx: Float, topLeftYPx: Float, viewportWPx: Int, viewportHPx: Int) -> Unit,
) {
    val density = LocalDensity.current
    val topInsetDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val bottomInsetDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val topBarH = 48.dp
    val bottomBarMinH = 52.dp

    var viewportPx by remember { mutableStateOf(IntSize.Zero) }
    var topLeft by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var minScale by remember { mutableFloatStateOf(1f) }
    var cropInitialized by remember(bitmap) { mutableStateOf(false) }

    val bw = bitmap.width
    val bh = bitmap.height

    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    Dialog(
        onDismissRequest = {
            UsbSessionLog.i("CardImageCrop", "cancel")
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                        .padding(
                            top = topInsetDp + topBarH,
                            bottom = bottomInsetDp + bottomBarMinH,
                        ),
                ) {
                    val aw = constraints.maxWidth.toFloat()
                    val ah = constraints.maxHeight.toFloat()
                    if (aw <= 0f || ah <= 0f || bw <= 0 || bh <= 0) {
                        Text(
                            "无法计算裁切区域",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        val frameAspect = 2f / 3f
                        val (vw, vh) = if (aw / ah > frameAspect) {
                            val h = ah
                            val w = h * frameAspect
                            w to h
                        } else {
                            val w = aw
                            val h = w / frameAspect
                            w to h
                        }
                        val vwDp = with(density) { vw.toDp() }
                        val vhDp = with(density) { vh.toDp() }

                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(vwDp, vhDp)
                                .clip(RoundedCornerShape(12.dp))
                                .onSizeChanged { viewportPx = it },
                        ) {
                            val vwI = viewportPx.width
                            val vhI = viewportPx.height

                            LaunchedEffect(viewportPx.width, viewportPx.height, bw, bh) {
                                val w = viewportPx.width
                                val h = viewportPx.height
                                if (w <= 0 || h <= 0 || bw <= 0 || bh <= 0) return@LaunchedEffect
                                if (cropInitialized) return@LaunchedEffect
                                val s0 = max(w.toFloat() / bw, h.toFloat() / bh)
                                minScale = s0
                                scale = s0
                                topLeft = Offset(
                                    (w - bw * s0) / 2f,
                                    (h - bh * s0) / 2f,
                                )
                                cropInitialized = true
                            }

                            if (vwI > 0 && vhI > 0) {
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .pointerInput(viewportPx.width, viewportPx.height, minScale, bw, bh) {
                                            detectTransformGestures { centroid, pan, zoom, _ ->
                                                val oldS = scale
                                                val newS = (oldS * zoom).coerceIn(minScale, minScale * MAX_ZOOM_FACTOR)
                                                val bx = (centroid.x - topLeft.x) / oldS
                                                val by = (centroid.y - topLeft.y) / oldS
                                                var tl = Offset(
                                                    centroid.x - bx * newS,
                                                    centroid.y - by * newS,
                                                )
                                                tl += pan
                                                scale = newS
                                                topLeft = clampCropPan(vwI, vhI, bw, bh, newS, tl)
                                            }
                                        },
                                ) {
                                    val dstW = bw * scale
                                    val dstH = bh * scale
                                    drawImage(
                                        image = imageBitmap,
                                        srcOffset = IntOffset.Zero,
                                        srcSize = IntSize(bw, bh),
                                        dstOffset = IntOffset(
                                            topLeft.x.roundToInt(),
                                            topLeft.y.roundToInt(),
                                        ),
                                        dstSize = IntSize(
                                            dstW.roundToInt().coerceAtLeast(1),
                                            dstH.roundToInt().coerceAtLeast(1),
                                        ),
                                        filterQuality = FilterQuality.High,
                                    )
                                    val grid = Color.White.copy(alpha = 0.45f)
                                    val strokeW = 1.5f
                                    val tw = size.width / 3f
                                    val th = size.height / 3f
                                    for (i in 1..2) {
                                        drawLine(
                                            color = grid,
                                            start = Offset(tw * i, 0f),
                                            end = Offset(tw * i, size.height),
                                            strokeWidth = strokeW,
                                        )
                                        drawLine(
                                            color = grid,
                                            start = Offset(0f, th * i),
                                            end = Offset(size.width, th * i),
                                            strokeWidth = strokeW,
                                        )
                                    }
                                    drawRect(
                                        color = Color.White.copy(alpha = 0.28f),
                                        topLeft = Offset.Zero,
                                        size = size,
                                        style = Stroke(width = strokeW),
                                    )
                                }
                            }
                        }
                    }
                }

                // 顶栏浮层（不占中间裁切纵向预算）
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shadowElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = topInsetDp)
                            .height(topBarH),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            UsbSessionLog.i("CardImageCrop", "cancel")
                            onDismiss()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                        Text(
                            "2∶3 封面 · 拖动 / 双指缩放",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        )
                    }
                }

                // 右侧缩放（补充双指）
                if (viewportPx.width > 0 && viewportPx.height > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(
                                end = 4.dp,
                                bottom = bottomInsetDp + bottomBarMinH + 8.dp,
                            ),
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 2.dp,
                        shadowElevation = 2.dp,
                    ) {
                        ColumnWithZoom(
                            onZoomIn = {
                                val newS = (scale * 1.12f).coerceIn(minScale, minScale * MAX_ZOOM_FACTOR)
                                val cx = viewportPx.width / 2f
                                val cy = viewportPx.height / 2f
                                val bx = (cx - topLeft.x) / scale
                                val by = (cy - topLeft.y) / scale
                                scale = newS
                                topLeft = clampCropPan(
                                    viewportPx.width,
                                    viewportPx.height,
                                    bw,
                                    bh,
                                    newS,
                                    Offset(cx - bx * newS, cy - by * newS),
                                )
                            },
                            onZoomOut = {
                                val newS = (scale / 1.12f).coerceIn(minScale, minScale * MAX_ZOOM_FACTOR)
                                val cx = viewportPx.width / 2f
                                val cy = viewportPx.height / 2f
                                val bx = (cx - topLeft.x) / scale
                                val by = (cy - topLeft.y) / scale
                                scale = newS
                                topLeft = clampCropPan(
                                    viewportPx.width,
                                    viewportPx.height,
                                    bw,
                                    bh,
                                    newS,
                                    Offset(cx - bx * newS, cy - by * newS),
                                )
                            },
                        )
                    }
                }

                // 底栏浮层
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f),
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = bottomInsetDp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                UsbSessionLog.i("CardImageCrop", "cancel")
                                onDismiss()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        ) {
                            Text("取消", maxLines = 1)
                        }
                        Button(
                            onClick = {
                                val vw = viewportPx.width
                                val vh = viewportPx.height
                                if (vw <= 0 || vh <= 0) return@Button
                                UsbSessionLog.i(
                                    "CardImageCrop",
                                    "confirm vw=$vw vh=$vh scale=$scale tl=(${topLeft.x},${topLeft.y})",
                                )
                                onConfirm(scale, topLeft.x, topLeft.y, vw, vh)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        ) {
                            Text("确定", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnWithZoom(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onZoomIn, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Add, contentDescription = "放大")
        }
        IconButton(onClick = onZoomOut, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Remove, contentDescription = "缩小")
        }
    }
}
