package com.aichat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun LazyListState.isNearBottom(lastIndex: Int, thresholdPx: Int = 96): Boolean {
    if (lastIndex < 0) return true
    val visibleLast = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    if (visibleLast.index < lastIndex) return false
    val itemBottom = visibleLast.offset + visibleLast.size
    return itemBottom <= layoutInfo.viewportEndOffset + thresholdPx
}

@Composable
internal fun MarkdownText(markdown: String) {
    var blocks by remember { mutableStateOf<List<MarkdownBlock>?>(null) }
    LaunchedEffect(markdown) {
        blocks = withContext(Dispatchers.Default) { parseMarkdown(markdown) }
    }
    val current = blocks
    if (current == null) { Text(markdown); return }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        current.forEachIndexed { index, block -> when (block) {
            is MarkdownBlock.Body -> Text(block.text)
            is MarkdownBlock.Title -> {
                val size = listOf(24, 22, 20, 18, 17, 16)[block.level - 1]
                Text(block.text, Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)
                    .semantics { heading() }.testTag("markdown-heading-$index"),
                    fontSize = size.sp, lineHeight = (size * 1.3f).sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
            }
            is MarkdownBlock.CodeBlock -> Surface(color = MaterialTheme.colorScheme.inverseSurface) {
                Text(block.text, Modifier.fillMaxWidth().padding(10.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface, fontFamily = FontFamily.Monospace)
            }
            is MarkdownBlock.Table -> MarkdownTable(block, index)
        } }
    }
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, index: Int) {
    val columns = table.headers.size
    if (columns == 0) return
    val foreground = LocalContentColor.current
    val line = foreground.copy(alpha = 0.25f)
    BoxWithConstraints(Modifier.fillMaxWidth().border(1.dp, line).clipToBounds().testTag("markdown-table-$index")) {
        val cellWidth = (maxWidth / columns).coerceAtLeast(128.dp)
        Column(Modifier.horizontalScroll(rememberScrollState()).testTag("markdown-table-scroll-$index")
            .semantics { collectionInfo = CollectionInfo(table.rows.size + 1, columns) }) {
            (listOf(table.headers) + table.rows).forEachIndexed { rowIndex, cells ->
                Row(Modifier.height(IntrinsicSize.Min).then(if (rowIndex == 0)
                    Modifier.background(foreground.copy(alpha = 0.08f)) else Modifier)) {
                    cells.forEachIndexed { columnIndex, cell ->
                        Text(cell, Modifier.width(cellWidth).fillMaxHeight()
                            .testTag("markdown-cell-$index-$rowIndex-$columnIndex")
                            .semantics { collectionItemInfo = CollectionItemInfo(rowIndex, 1, columnIndex, 1) }
                            .drawBehind {
                                val stroke = 1.dp.toPx()
                                drawLine(line, Offset(size.width, 0f), Offset(size.width, size.height), stroke)
                                drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), stroke)
                            }.padding(horizontal = 10.dp, vertical = 8.dp),
                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else null,
                            textAlign = table.alignments[columnIndex])
                    }
                }
            }
        }
    }
}
