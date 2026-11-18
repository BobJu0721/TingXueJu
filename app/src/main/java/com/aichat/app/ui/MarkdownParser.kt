package com.aichat.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

internal sealed interface MarkdownBlock {
    data class Body(val text: AnnotatedString) : MarkdownBlock
    data class Title(val level: Int, val text: AnnotatedString) : MarkdownBlock
    data class CodeBlock(val text: String) : MarkdownBlock
    data class Table(val headers: List<AnnotatedString>, val alignments: List<TextAlign>,
        val rows: List<List<AnnotatedString>>) : MarkdownBlock
}

private val markdownParser: Parser = Parser.builder()
    .enabledBlockTypes(setOf(Heading::class.java, FencedCodeBlock::class.java))
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    .extensions(listOf(TablesExtension.create()))
    .build()

internal fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    if (markdown.isBlank()) return emptyList()
    return try {
        val document = markdownParser.parse(markdown)
        val lines = markdown.lines()
        document.children().map { node ->
            when (node) {
                is Heading -> MarkdownBlock.Title(node.level, inlineText(node, lines))
                is FencedCodeBlock -> MarkdownBlock.CodeBlock(node.literal.removeSuffix("\n"))
                is TableBlock -> {
                    val head = node.children().first { it is TableHead }.children().first()
                    val cells = head.children().filterIsInstance<TableCell>()
                    val alignments = cells.map { cell -> when (cell.alignment) {
                        TableCell.Alignment.LEFT -> TextAlign.Left
                        TableCell.Alignment.CENTER -> TextAlign.Center
                        TableCell.Alignment.RIGHT -> TextAlign.Right
                        else -> TextAlign.Start
                    } }
                    val rows = node.children().filterNot { it is TableHead }.flatMap { it.children() }.map { row ->
                        val values = row.children().map { inlineText(it, lines) }
                        List(cells.size) { values.getOrElse(it) { AnnotatedString("") } }
                    }
                    MarkdownBlock.Table(cells.map { inlineText(it, lines) }, alignments, rows)
                }
                is Paragraph -> MarkdownBlock.Body(inlineText(node, lines))
                else -> MarkdownBlock.Body(AnnotatedString(sourceText(node, lines)))
            }
        }
    } catch (_: IllegalArgumentException) {
        // A malformed or resource-limited document must remain readable, not break the chat.
        listOf(MarkdownBlock.Body(AnnotatedString(markdown)))
    } catch (_: IllegalStateException) {
        listOf(MarkdownBlock.Body(AnnotatedString(markdown)))
    }
}

private fun Node.children(): List<Node> = buildList {
    var child = firstChild
    while (child != null) { add(child); child = child.next }
}

private fun sourceText(node: Node, lines: List<String>): String = node.sourceSpans.joinToString("\n") { span ->
    val line = lines.getOrElse(span.lineIndex) { "" }
    val start = span.columnIndex.coerceIn(0, line.length)
    line.substring(start, (start + span.length).coerceAtMost(line.length))
}

private fun inlineText(parent: Node, lines: List<String>): AnnotatedString = buildAnnotatedString {
    val pending = ArrayDeque<Pair<Node, Boolean>>()
    parent.children().asReversed().forEach { pending.add(it to false) }
    while (pending.isNotEmpty()) {
        val (node, closing) = pending.removeLast()
        if (closing) { pop(); continue }
        when (node) {
            is Text -> append(node.literal)
            is Code -> { pushStyle(SpanStyle(fontFamily = FontFamily.Monospace)); append(node.literal); pop() }
            is StrongEmphasis -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                pending.add(node to true)
                node.children().asReversed().forEach { pending.add(it to false) }
            }
            is SoftLineBreak, is HardLineBreak -> append('\n')
            // Keep unsupported markup literal; do not hide links, HTML, images or their destinations.
            else -> append(sourceText(node, lines))
        }
    }
}
