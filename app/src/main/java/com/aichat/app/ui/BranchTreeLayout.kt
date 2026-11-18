package com.aichat.app.ui

import com.aichat.app.domain.BranchTreeNode

internal const val BRANCH_TREE_NODE_WIDTH = 148f
internal const val BRANCH_TREE_HORIZONTAL_GAP = 64f
internal const val BRANCH_TREE_VERTICAL_GAP = 26f

data class TreeRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val centerX get() = x + width / 2
    fun intersects(left: Float, top: Float, right: Float, bottom: Float) =
        x + width >= left && x <= right && y + height >= top && y <= bottom
}
data class TreeLayout(val bounds: Map<String, TreeRect>, val width: Float, val height: Float)

/** Coordinates are dp. Postorder subtree widths guarantee that sibling subtrees never overlap. */
fun layoutBranchTree(nodes: List<BranchTreeNode>, heights: Map<String, Float>, nodeWidth: Float = BRANCH_TREE_NODE_WIDTH): TreeLayout {
    if (nodes.isEmpty()) return TreeLayout(emptyMap(), 0f, 0f)
    val byId = nodes.associateBy { it.id }
    val widths = HashMap<String, Float>()
    val depth = HashMap<String, Int>(); depth[nodes.first().id] = 0
    nodes.forEach { node -> node.children.filter { it in byId }.forEach { depth[it] = depth.getValue(node.id) + 1 } }
    val levels = nodes.groupBy { depth.getValue(it.id) }.toSortedMap()
    val ys = HashMap<Int, Float>(); var y = 24f
    levels.forEach { (d, layer) -> ys[d] = y; y += layer.maxOf { heights[it.id] ?: 100f } + BRANCH_TREE_VERTICAL_GAP }
    nodes.asReversed().forEach { node ->
        val children = node.children.filter { it in byId }
        widths[node.id] = maxOf(nodeWidth, children.sumOf { widths.getValue(it).toDouble() }.toFloat() + (children.size - 1).coerceAtLeast(0) * BRANCH_TREE_HORIZONTAL_GAP)
    }
    val left = HashMap<String, Float>(); left[nodes.first().id] = 24f
    val bounds = LinkedHashMap<String, TreeRect>()
    nodes.forEach { node ->
        val x = left.getValue(node.id); val width = widths.getValue(node.id)
        bounds[node.id] = TreeRect(x + (width - nodeWidth) / 2, ys.getValue(depth.getValue(node.id)), nodeWidth, heights[node.id] ?: 100f)
        val children = node.children.filter { it in byId }
        val total = children.sumOf { widths.getValue(it).toDouble() }.toFloat() + (children.size - 1).coerceAtLeast(0) * BRANCH_TREE_HORIZONTAL_GAP
        var childX = x + (width - total) / 2
        children.forEach { left[it] = childX; childX += widths.getValue(it) + BRANCH_TREE_HORIZONTAL_GAP }
    }
    return TreeLayout(bounds, widths.getValue(nodes.first().id) + 48f, y)
}
