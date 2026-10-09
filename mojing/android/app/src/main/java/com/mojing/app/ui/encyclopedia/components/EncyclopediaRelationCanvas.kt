package com.mojing.app.ui.encyclopedia.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mojing.app.ui.common.MoJingCoverImage
import kotlin.math.cos
import kotlin.math.sin

// Image paths are optional and originate from this relation page's endpoint projection.
data class GraphNode(val id: Long, val title: String, val imagePath: String = "")
data class GraphEdge(val source: Long, val target: Long, val label: String)

@Composable
fun EncyclopediaRelationCanvas(
    nodes: List<GraphNode>, edges: List<GraphEdge>, highlightId: Long?,
    onNodeTap: (Long) -> Unit, modifier: Modifier = Modifier,
) {
    val visibleNodes = remember(nodes) { nodes.take(12) }
    val visibleIds = remember(visibleNodes) { visibleNodes.map { it.id }.toSet() }
    val visibleEdges = remember(edges, visibleIds) { edges.filter { it.source in visibleIds && it.target in visibleIds } }
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelLineHeight = with(density) { labelStyle.lineHeight.toDp().value }
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val dense = visibleNodes.size > 6 || (visibleNodes.size > 3 && (maxWidth < 360.dp || density.fontScale > 1.2f))
            val columns = (maxWidth.value / 88f).toInt().coerceIn(1, 3)
            val rowHeight = 56f + 2f * labelLineHeight
            val radius = minOf(maxWidth.value * 0.34f, 112f)
            val graphHeight = if (dense) (((visibleNodes.size + columns - 1) / columns) * rowHeight).dp else (2f * radius + rowHeight + 24f).dp
            val nodeWidth = if (dense) (maxWidth.value / columns - 8f).dp else 80.dp
            val positions = remember(visibleNodes, maxWidth, dense, columns, rowHeight, radius) {
                val center = Offset(maxWidth.value / 2f, radius + 36f)
                visibleNodes.mapIndexed { index, node ->
                    val angle = (2 * Math.PI * index / visibleNodes.size.coerceAtLeast(1)).toFloat()
                    node.id to if (dense) Offset((index % columns + 0.5f) * maxWidth.value / columns, index / columns * rowHeight + 24f)
                        else Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
                }.toMap()
            }
            Box(Modifier.fillMaxWidth().height(graphHeight)) {
                Canvas(Modifier.fillMaxSize()) {
                    visibleEdges.forEach { edge ->
                        val start = positions[edge.source] ?: return@forEach
                        val end = positions[edge.target] ?: return@forEach
                        drawLine(lineColor, Offset(start.x.dp.toPx(), start.y.dp.toPx()),
                            Offset(end.x.dp.toPx(), end.y.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                    }
                }
                // Dense edge labels would cover endpoint actions; the complete directed list follows the graph.
                if (!dense) visibleEdges.forEach { edge ->
                    val start = positions[edge.source] ?: return@forEach
                    val end = positions[edge.target] ?: return@forEach
                    val labelLeft = (start.x + end.x) / 2f - 36f
                    val labelTop = (start.y + end.y) / 2f - 12f
                    val coversNode = positions.values.any { position ->
                        labelLeft < position.x + nodeWidth.value / 2f && labelLeft + 72f > position.x - nodeWidth.value / 2f &&
                            labelTop < position.y - 24f + rowHeight && labelTop + labelLineHeight + 8f > position.y - 24f
                    }
                    if (coversNode) return@forEach
                    Surface(Modifier.offset(labelLeft.dp, labelTop.dp).width(72.dp),
                        color = MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.extraSmall) {
                        Text(edge.label, Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                visibleNodes.forEach { node ->
                    val position = positions[node.id] ?: return@forEach
                    Column(Modifier.offset((position.x - nodeWidth.value / 2f).dp, (position.y - 24).dp).width(nodeWidth)
                        .clickable { onNodeTap(node.id) }
                        .clearAndSetSemantics {
                            contentDescription = "选择条目：${node.title}"
                            selected = node.id == highlightId
                            role = Role.Button
                            onClick(label = "选择条目") { onNodeTap(node.id); true }
                        },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(Modifier.size(48.dp), shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(if (node.id == highlightId) 2.dp else 1.dp,
                                if (node.id == highlightId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                            if (node.imagePath.isNotBlank()) MoJingCoverImage(node.imagePath, Modifier.fillMaxSize())
                            else Box(contentAlignment = Alignment.Center) {
                                Text(node.title.take(1), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Text(node.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Text(if (nodes.size > visibleNodes.size) "图中显示本页前12个条目，其余关系可在下方查看" else "点击条目查看详情，关系类型与方向见下方列表",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
    }
}
