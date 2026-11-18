package com.aichat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.aichat.app.pick
import com.aichat.app.data.*
import com.aichat.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val BRANCH_LIST_INDENT = 20f
private const val BRANCH_LIST_BODY_MIN_WIDTH = 260f

// Shared parent links retain every guide without copying a depth-sized array for each row.
internal class BranchListGuides(val parent: BranchListGuides?, val continues: Boolean) {
    val depth: Int = (parent?.depth ?: 0) + 1

    fun asFlags(): List<Boolean> {
        val result = ArrayList<Boolean>(depth)
        var guide: BranchListGuides? = this
        while (guide != null) { result.add(guide.continues); guide = guide.parent }
        result.reverse()
        return result
    }
}

internal data class BranchListRow(
    val nodeId: String,
    val depth: Int,
    val guides: BranchListGuides?,
    val hasChildren: Boolean,
    val expanded: Boolean,
    val hiddenDescendantCount: Int,
    val isCurrent: Boolean,
)

internal fun branchDescendantCounts(tree: BranchTree): Map<String, Int> {
    val order = ArrayList<String>()
    val pending = ArrayDeque<String>()
    pending.add(tree.rootId)
    while (pending.isNotEmpty()) {
        val node = tree.nodes[pending.removeLast()] ?: continue
        order.add(node.id)
        node.children.forEach(pending::add)
    }
    val counts = HashMap<String, Int>()
    order.asReversed().forEach { id ->
        counts[id] = tree.nodes.getValue(id).children.sumOf { 1 + (counts[it] ?: 0) }
    }
    return counts
}

internal fun branchCurrentAncestors(tree: BranchTree, active: String): Set<String> {
    val ancestors = LinkedHashSet<String>()
    var parent = tree.nodes.values.firstOrNull { active in it.terminalBranchIds }?.parentId
    while (parent != null && ancestors.add(parent)) parent = tree.nodes[parent]?.parentId
    return ancestors
}

internal fun flattenBranchList(tree: BranchTree, expanded: Set<String>, active: String, counts: Map<String, Int>): List<BranchListRow> {
    data class Visit(val id: String, val depth: Int, val guides: BranchListGuides?)
    val pending = ArrayDeque<Visit>()
    pending.add(Visit(tree.rootId, 0, null))
    val result = ArrayList<BranchListRow>()
    while (pending.isNotEmpty()) {
        val visit = pending.removeLast()
        val node = tree.nodes[visit.id] ?: continue
        val open = node.children.isNotEmpty() && node.id in expanded
        result.add(BranchListRow(node.id, visit.depth, visit.guides, node.children.isNotEmpty(), open,
            if (open) 0 else counts[node.id] ?: 0, active in node.terminalBranchIds))
        if (open) for (i in node.children.indices.reversed()) {
            pending.add(Visit(node.children[i], visit.depth + 1,
                BranchListGuides(visit.guides, i != node.children.lastIndex)))
        }
    }
    return result
}

internal enum class BranchSpeakerTone { AI, USER, NEUTRAL }
internal data class BranchListText(val title: String, val summary: String, val statuses: String, val tone: BranchSpeakerTone)

internal fun branchListText(node: BranchTreeNode, language: AppLanguage, routeNumbers: Map<String, Int>): BranchListText {
    val first = node.rows.firstOrNull()
    val speaker = first?.let { treeSpeaker(it.role, it.kind, it.authorName, language) }.orEmpty()
    val title = when {
        node.parentId == null -> language.pick("對話開始", "对话开始")
        node.rows.size > 1 -> (if (node.branchIds.size > 1) language.pick("共用區段", "共用区段") else language.pick("連續對話", "连续对话")) +
            " · $speaker · ${node.rows.size}" + language.pick(" 則", " 则")
        else -> speaker + " v${first?.versionNumber ?: "?"}"
    } + if (node.terminalBranchIds.isNotEmpty()) {
        " · " + node.terminalBranchIds.joinToString(" / ") { language.pick("路線", "路线") + " ${routeNumbers[it] ?: "?"}" }
    } else ""
    val summary = if (node.rows.any { it.deleted }) language.pick("此分岔訊息已刪除", "此分岔消息已删除")
        else node.rows.filter { it.kind != MessageKind.PRIVATE_NOTE }.let { rows ->
            listOfNotNull(rows.firstOrNull()?.snippet, rows.lastOrNull()?.snippet?.takeIf { rows.size > 1 }).joinToString(" … ")
        }.ifBlank { if (node.rows.isNotEmpty()) language.pick("私人註記 ${node.rows.size} 則", "私人注记 ${node.rows.size} 则") else "" }
    val statuses = buildList {
        if (node.rows.any { it.status == MessageVersionStatus.DRAFT }) add(language.pick("生成中", "生成中"))
        if (node.rows.any { !it.valid && !it.deleted }) add(language.pick("資料不完整", "数据不完整"))
        if (node.rows.any { it.status == MessageVersionStatus.PARTIAL || it.status == MessageVersionStatus.INTERRUPTED }) add(language.pick("部分完成／已中斷", "部分完成／已中断"))
        val notes = node.rows.count { it.kind == MessageKind.PRIVATE_NOTE && !it.deleted }
        if (notes > 0) add(language.pick("註記 $notes 則", "注记 $notes 则"))
    }.joinToString(" · ")
    val tone = when {
        node.rows.size != 1 || first == null -> BranchSpeakerTone.NEUTRAL
        first.kind != MessageKind.CHAT -> BranchSpeakerTone.NEUTRAL
        first.role == "assistant" -> BranchSpeakerTone.AI
        first.role == "user" -> BranchSpeakerTone.USER
        else -> BranchSpeakerTone.NEUTRAL
    }
    return BranchListText(title, summary.replace('\n', ' '), statuses, tone)
}

@Stable
internal class BranchListState(val scroll: LazyListState, expanded: Set<String> = emptySet(), active: String? = null,
    horizontalOffset: Float = 0f) {
    var expanded by mutableStateOf(expanded)
    var initializedActive by mutableStateOf(active)
    var scrollTarget by mutableStateOf<String?>(null)
    var centerTarget by mutableStateOf(false)
    var horizontalOffset by mutableFloatStateOf(horizontalOffset)

    fun reconcile(tree: BranchTree, active: String) {
        val valid = expanded.intersect(tree.nodes.keys)
        if (initializedActive != active) {
            expanded = valid + branchCurrentAncestors(tree, active)
            initializedActive = active
            scrollTarget = tree.nodes.values.firstOrNull { active in it.terminalBranchIds }?.id
            centerTarget = true
        } else if (valid != expanded) expanded = valid
    }

    fun toggle(tree: BranchTree, nodeId: String) {
        if (nodeId in expanded) {
            // If the reading anchor disappears, keep the collapsing parent on screen.
            var anchor = scroll.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
            while (anchor != null && anchor != nodeId) anchor = tree.nodes[anchor]?.parentId
            if (anchor == nodeId) { scrollTarget = nodeId; centerTarget = false }
            expanded = expanded - nodeId
        } else expanded = expanded + nodeId
    }
}

@Composable
internal fun rememberBranchListState(conversationId: String): BranchListState {
    val scroll = rememberLazyListState()
    return rememberSaveable(conversationId, saver = listSaver<BranchListState, Any>(
        save = { listOf(it.initializedActive.orEmpty(), it.horizontalOffset) + it.expanded },
        restore = {
            val offset = it.getOrNull(1) as? Number
            BranchListState(scroll, it.drop(if (offset == null) 1 else 2).filterIsInstance<String>().toSet(),
                (it.first() as String).takeIf(String::isNotEmpty), offset?.toFloat() ?: 0f)
        },
    )) { BranchListState(scroll) }
}

@Composable
internal fun BranchTreeList(tree: BranchTree, active: String, state: BranchListState, language: AppLanguage,
    routeNumbers: Map<String, Int>, onNode: (BranchTreeNode) -> Unit, modifier: Modifier = Modifier) {
    var counts by remember(tree) { mutableStateOf<Map<String, Int>?>(null) }
    LaunchedEffect(tree) {
        counts = withContext(Dispatchers.Default) { branchDescendantCounts(tree) }
    }
    LaunchedEffect(tree, active) { state.reconcile(tree, active) }
    var rows by remember { mutableStateOf(emptyList<BranchListRow>()) }
    LaunchedEffect(tree, state.expanded, active, counts) {
        val descendantCounts = counts ?: return@LaunchedEffect
        val expanded = state.expanded
        rows = withContext(Dispatchers.Default) { flattenBranchList(tree, expanded, active, descendantCounts) }
    }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.background).clipToBounds().testTag("branch-tree-list-viewport")) {
        val innerWidth = (maxWidth - 20.dp).coerceAtLeast(0.dp)
        val innerWidthPx = with(density) { innerWidth.toPx() }
        val indentPx = with(density) { BRANCH_LIST_INDENT.dp.toPx() }
        val minBodyPx = with(density) { BRANCH_LIST_BODY_MIN_WIDTH.dp.toPx() }
        val maxHorizontal = ((rows.maxOfOrNull { it.depth } ?: 0) * indentPx + minBodyPx - innerWidthPx).coerceAtLeast(0f)
        fun scrollHorizontal(delta: Float): Boolean {
            val next = (state.horizontalOffset + delta).coerceIn(0f, maxHorizontal)
            val changed = next != state.horizontalOffset
            state.horizontalOffset = next
            return changed
        }
        val horizontal = rememberScrollableState { delta ->
            val before = state.horizontalOffset
            scrollHorizontal(-delta)
            before - state.horizontalOffset
        }
        LaunchedEffect(maxHorizontal, rows.isNotEmpty()) {
            if (rows.isNotEmpty()) state.horizontalOffset = state.horizontalOffset.coerceIn(0f, maxHorizontal)
        }
        LaunchedEffect(rows, state.scrollTarget, innerWidthPx) {
            val target = state.scrollTarget ?: return@LaunchedEffect
            val index = rows.indexOfFirst { it.nodeId == target }
            if (index >= 0 && innerWidthPx > 0) {
                state.scroll.scrollToItem(index)
                withFrameNanos { }
                if (state.centerTarget) {
                    val info = state.scroll.layoutInfo
                    val item = info.visibleItemsInfo.firstOrNull { it.key == target }
                    val offset = ((info.viewportEndOffset - info.viewportStartOffset - (item?.size ?: 0)) / 2).coerceAtLeast(0)
                    state.scroll.scrollToItem(index, -offset)
                }
                val left = rows[index].depth * indentPx
                val right = left + (innerWidthPx - left).coerceAtLeast(minBodyPx)
                state.horizontalOffset = when {
                    left < state.horizontalOffset -> left
                    right > state.horizontalOffset + innerWidthPx -> right - innerWidthPx
                    else -> state.horizontalOffset
                }.coerceIn(0f, maxHorizontal)
                state.scrollTarget = null
            }
        }
        Box(Modifier.fillMaxSize().testTag("branch-tree-horizontal")
            .scrollable(horizontal, Orientation.Horizontal)
            .semantics {
                horizontalScrollAxisRange = ScrollAxisRange({ state.horizontalOffset }, { maxHorizontal })
                scrollBy { x, _ -> scrollHorizontal(x) }
            }) {
            LazyColumn(Modifier.fillMaxSize().testTag("branch-tree-list"), state = state.scroll,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 24.dp)) {
                items(rows, key = { it.nodeId }, contentType = { "branch-row" }) { row ->
                    val node = tree.nodes[row.nodeId] ?: return@items
                    val text = remember(node, language, routeNumbers) { branchListText(node, language, routeNumbers) }
                    val bodyWidth = (innerWidth - (row.depth * BRANCH_LIST_INDENT).dp).coerceAtLeast(BRANCH_LIST_BODY_MIN_WIDTH.dp)
                    BranchTreeListRow(row, text, language, bodyWidth, { state.horizontalOffset }, onToggle = { state.toggle(tree, row.nodeId) },
                        onClick = { if (row.hasChildren && !row.expanded) state.toggle(tree, row.nodeId) else onNode(node) })
                }
            }
        }
    }
}

@Composable
private fun BranchTreeListRow(row: BranchListRow, text: BranchListText, language: AppLanguage, bodyWidth: Dp,
    horizontalOffset: () -> Float, onToggle: () -> Unit, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val smallText = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.sp)
    val line = colors.onSurface.copy(alpha = 0.16f)
    val tone = when (text.tone) {
        BranchSpeakerTone.AI -> colors.primary
        BranchSpeakerTone.USER -> if (colors.surface.luminance() < 0.5f) Color(0xFF63D98A) else Color(0xFF238447)
        BranchSpeakerTone.NEUTRAL -> colors.onSurfaceVariant
    }
    Box(Modifier.fillMaxWidth().testTag("branch-list-row-${row.nodeId}")
        .clip(RoundedCornerShape(8.dp))
        .background(if (row.isCurrent) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
        .drawBehind {
            val step = BRANCH_LIST_INDENT.dp.toPx()
            val offset = horizontalOffset()
            var guide = row.guides
            while (guide != null) {
                val x = (guide.depth - 1) * step + 4.dp.toPx() - offset
                if (guide === row.guides) {
                    drawLine(line, Offset(x, 0f), Offset(x, if (guide.continues) size.height else size.height / 2), 1.dp.toPx())
                    drawLine(line, Offset(x, size.height / 2), Offset(row.depth * step - offset, size.height / 2), 1.dp.toPx())
                } else if (guide.continues && x in 0f..size.width) drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                if (x < 0f) break
                guide = guide.parent
            }
        }.clickable(onClick = onClick)) {
    Row(Modifier.offset { IntOffset((row.depth * BRANCH_LIST_INDENT.dp.toPx() - horizontalOffset()).roundToInt(), 0) }
        .requiredWidth(bodyWidth).heightIn(min = 56.dp).testTag("branch-list-content-${row.nodeId}").padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (row.hasChildren) {
            val action = if (row.expanded) language.pick("收合子節點", "收起子节点")
                else language.pick("展開 ${row.hiddenDescendantCount} 個節點", "展开 ${row.hiddenDescendantCount} 个节点")
            IconButton(onToggle, Modifier.size(48.dp).testTag("branch-list-toggle-${row.nodeId}")) {
                Icon(if (row.expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, action,
                    Modifier.size(20.dp), tint = colors.primary)
            }
        } else Spacer(Modifier.width(48.dp))
        Box(Modifier.size(7.dp).background(tone, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Text(text.title, color = colors.onSurface, style = smallText.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text.summary.ifEmpty { " " }, style = smallText, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (text.statuses.isNotEmpty()) Text(text.statuses, style = smallText, color = colors.onSurfaceVariant)
        }
        if (row.isCurrent || row.hiddenDescendantCount > 0) {
            Spacer(Modifier.width(4.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (row.isCurrent) Text(language.pick("目前", "目前"), Modifier.testTag("branch-list-current-${row.nodeId}"),
                    color = colors.primary, style = smallText, fontWeight = FontWeight.SemiBold)
                if (row.hiddenDescendantCount > 0) Text("+${row.hiddenDescendantCount}",
                    Modifier.semantics { contentDescription = language.pick("展開 ${row.hiddenDescendantCount} 個節點", "展开 ${row.hiddenDescendantCount} 个节点") },
                    style = smallText, color = colors.onSurfaceVariant)
            }
        }
    }
    }
}
