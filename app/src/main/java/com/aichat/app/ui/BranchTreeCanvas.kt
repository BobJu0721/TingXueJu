package com.aichat.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.aichat.app.data.AppLanguage
import com.aichat.app.domain.BranchTreeNode
import com.aichat.app.pick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun BranchTreeCanvas(
    nodes: List<BranchTreeNode>, labels: Map<String, String>, activeBranch: String, focusId: String,
    command: Pair<Int, String>, onNode: (BranchTreeNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyMedium
    var layout by remember { mutableStateOf(TreeLayout(emptyMap(), 0f, 0f)) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }
    var panY by rememberSaveable { mutableFloatStateOf(0f) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var consumedCommand by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(nodes, labels, density, style) {
        layout = withContext(Dispatchers.Default) {
            val heights = nodes.associate { node ->
                val measured = measurer.measure(labels[node.id].orEmpty(), style = style,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = with(density) { 176.dp.roundToPx() }))
                node.id to maxOf(80f, with(density) { measured.size.height.toDp().value } + 24f)
            }
            layoutBranchTree(nodes, heights)
        }
    }
    fun center(id: String) {
        val rect = layout.bounds[id] ?: return
        panX = viewport.width / (2 * density.density) - rect.centerX * scale
        panY = viewport.height / (2 * density.density) - (rect.y + rect.height / 2) * scale
    }
    LaunchedEffect(layout, viewport, command) {
        if (viewport == IntSize.Zero || layout.bounds.isEmpty()) return@LaunchedEffect
        if (!initialized) { center(focusId); initialized = true }
        if (command.first > consumedCommand) {
            when (command.second) {
                "current" -> center(focusId)
                "fit" -> {
                    scale = minOf(viewport.width / density.density / layout.width,
                        viewport.height / density.density / layout.height).coerceIn(0.6f, 1.6f)
                    panX = (viewport.width / density.density - layout.width * scale) / 2
                    panY = (viewport.height / density.density - layout.height * scale) / 2
                }
                else -> {
                    val next = (scale + if (command.second == "in") 0.2f else -0.2f).coerceIn(0.6f, 1.6f)
                    val cx = viewport.width / density.density / 2; val cy = viewport.height / density.density / 2
                    panX = cx - (cx - panX) * next / scale; panY = cy - (cy - panY) * next / scale
                    scale = next
                }
            }
            consumedCommand = command.first
        }
    }
    val byId = remember(nodes) { nodes.associateBy { it.id } }
    val primary = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.outlineVariant
    val visible = remember(layout, viewport, panX, panY, scale, density) {
        val left = (-panX - 100f) / scale; val top = (-panY - 100f) / scale
        val right = (viewport.width / density.density - panX + 100f) / scale
        val bottom = (viewport.height / density.density - panY + 100f) / scale
        layout.bounds.filterValues { it.intersects(left, top, right, bottom) }.keys
    }
    val latestScale by rememberUpdatedState(scale)
    Box(modifier.testTag("branch-tree-canvas").clipToBounds().onSizeChanged { viewport = it }.pointerInput(density) {
        detectTransformGestures { centroid, pan, zoom, _ ->
            val old = latestScale
            val next = (old * zoom).coerceIn(0.6f, 1.6f)
            val c = centroid / density.density
            panX = c.x - (c.x - panX) * next / old + pan.x / density.density
            panY = c.y - (c.y - panY) * next / old + pan.y / density.density
            scale = next
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            fun point(x: Float, y: Float) = Offset((x * scale + panX) * density.density, (y * scale + panY) * density.density)
            nodes.forEach { parent ->
                val a = layout.bounds[parent.id] ?: return@forEach
                parent.children.forEach childLoop@ { id ->
                    val b = layout.bounds[id] ?: return@childLoop
                    val from = point(a.centerX, a.y + a.height); val to = point(b.centerX, b.y)
                    if (maxOf(from.y, to.y) < 0 || minOf(from.y, to.y) > size.height) return@childLoop
                    val child = byId[id]
                    val path = Path().apply { moveTo(from.x, from.y); cubicTo(from.x, (from.y + to.y) / 2,
                        to.x, (from.y + to.y) / 2, to.x, to.y) }
                    drawPath(path, if (child?.branchIds?.contains(activeBranch) == true) primary else neutral,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(with(density) { 2.dp.toPx() }))
                }
            }
        }
        nodes.forEach { node ->
            val rect = layout.bounds[node.id]
            if (rect != null && node.id in visible) key(node.id) {
                val current = activeBranch in node.branchIds
                Surface(onClick = { onNode(node) },
                    modifier = Modifier.testTag("branch-node-${node.id}").offset { IntOffset(((rect.x * scale + panX) * density.density).roundToInt(),
                        ((rect.y * scale + panY) * density.density).roundToInt()) }
                        .requiredSize(rect.width.dp, rect.height.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(if (current) 2.dp else 1.dp,
                        if (current) primary else neutral)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center) {
                        labels[node.id].orEmpty().lines().take(3).forEach { line ->
                            Text(line, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
