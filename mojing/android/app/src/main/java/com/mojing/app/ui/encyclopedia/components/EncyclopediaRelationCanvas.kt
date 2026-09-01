package com.mojing.app.ui.encyclopedia.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class GraphNode(val id: Long, val title: String)
data class GraphEdge(val source: Long, val target: Long, val label: String)

@Composable
fun EncyclopediaRelationCanvas(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    highlightId: Long?,
    onNodeTap: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var layoutSize by remember { mutableStateOf(Size.Zero) }
    val lineColor = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceVariant

    val positions = remember(nodes, layoutSize) {
        if (layoutSize.width <= 0f || layoutSize.height <= 0f) emptyMap()
        else {
            val cx = layoutSize.width / 2f
            val cy = layoutSize.height / 2f
            val r = minOf(layoutSize.width, layoutSize.height) * 0.32f
            val n = nodes.size.coerceAtLeast(1)
            nodes.mapIndexed { i, node ->
                val ang = (2 * Math.PI * i / n - Math.PI / 2).toFloat()
                node.id to Offset(cx + r * cos(ang), cy + r * sin(ang))
            }.toMap()
        }
    }

    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .onSizeChanged { layoutSize = it.toSize() }
                .pointerInput(layoutSize, nodes, edges, positions) {
                    if (layoutSize.width <= 0f) return@pointerInput
                    detectTapGestures { tap ->
                        var hit: Long? = null
                        for ((id, p) in positions) {
                            if (hypot(tap.x - p.x, tap.y - p.y) < 28f) {
                                hit = id
                                break
                            }
                        }
                        hit?.let { onNodeTap(it) }
                    }
                }
        ) {
            edges.forEach { e ->
                val a = positions[e.source] ?: return@forEach
                val b = positions[e.target] ?: return@forEach
                drawLine(color = lineColor, start = a, end = b, strokeWidth = 2f)
            }
            nodes.forEach { node ->
                val p = positions[node.id] ?: return@forEach
                val active = node.id == highlightId
                val rad = if (active) 22f else 18f
                drawCircle(color = surface, radius = rad, center = p)
                drawCircle(color = accent, radius = rad, center = p, style = Stroke(width = 2f))
            }
        }
        Text(
            "点击圆点打开条目；环形为示意布局。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
