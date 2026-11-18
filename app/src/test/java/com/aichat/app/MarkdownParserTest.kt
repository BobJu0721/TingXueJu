package com.aichat.app

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.aichat.app.ui.MarkdownBlock
import com.aichat.app.ui.parseMarkdown
import org.junit.Assert.*
import org.junit.Test

class MarkdownParserTest {
    @Test fun sixAtxHeadingLevelsAndClosingHashesAreParsed() {
        val blocks = parseMarkdown((1..6).joinToString("\n\n") { "${"#".repeat(it)} 標題 $it ###" })
        assertEquals((1..6).toList(), blocks.map { (it as MarkdownBlock.Title).level })
        assertEquals((1..6).map { "標題 $it" }, blocks.map { (it as MarkdownBlock.Title).text.text })
    }

    @Test fun setextHeadingsAndOrdinaryHashesRemainDistinct() {
        val blocks = parseMarkdown("大標題\n===\n\n小標題\n---\n\n#hashtag\n####### not a heading")
        assertEquals(1, (blocks[0] as MarkdownBlock.Title).level)
        assertEquals(2, (blocks[1] as MarkdownBlock.Title).level)
        assertEquals("#hashtag\n####### not a heading", (blocks[2] as MarkdownBlock.Body).text.text)
    }

    @Test fun headingsRetainBoldAndInlineCodeIncludingNestedCode() {
        val text = (parseMarkdown("## 使用 **bold `code`**").single() as MarkdownBlock.Title).text
        assertEquals("使用 bold code", text.text)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test fun tablesSupportAllAlignmentsAndUnevenRows() {
        val table = parseMarkdown("| A | B | C |\n| :--- | :---: | ---: |\n| one | two |\n| x | y | z | ignored |").single() as MarkdownBlock.Table
        assertEquals(listOf(TextAlign.Left, TextAlign.Center, TextAlign.Right), table.alignments)
        assertEquals(listOf("A", "B", "C"), table.headers.map { it.text })
        assertEquals(listOf("one", "two", ""), table.rows[0].map { it.text })
        assertEquals(listOf("x", "y", "z"), table.rows[1].map { it.text })
    }

    @Test fun tablesWithoutOuterPipesRetainChineseAndInlineStyles() {
        val table = parseMarkdown("角色 | 地點\n--- | ---\n**白貓** | `森林`").single() as MarkdownBlock.Table
        assertEquals(listOf("白貓", "森林"), table.rows.single().map { it.text })
        assertEquals(FontWeight.Bold, table.rows.single()[0].spanStyles.single().item.fontWeight)
        assertEquals(FontFamily.Monospace, table.rows.single()[1].spanStyles.single().item.fontFamily)
    }

    @Test fun escapedPipesDoNotCreateExtraColumnsEvenInsideCode() {
        val table = parseMarkdown("| A | B |\n| --- | --- |\n| left\\|right | `a\\|b` |").single() as MarkdownBlock.Table
        assertEquals(listOf("left|right", "a|b"), table.rows.single().map { it.text })
    }

    @Test fun malformedAndStreamingTablesRemainReadableUntilDelimiterExists() {
        val partial = "| A | B |\n| --- |"
        assertTrue(parseMarkdown(partial).none { it is MarkdownBlock.Table })
        assertTrue((parseMarkdown(partial).single() as MarkdownBlock.Body).text.text.contains("| A | B |"))
        val complete = "$partial --- |\n| value | growing"
        assertEquals(listOf("value", "growing"), (parseMarkdown(complete).single() as MarkdownBlock.Table).rows.single().map { it.text })
        assertTrue(parseMarkdown("A | B\nnot | delimiter").none { it is MarkdownBlock.Table })
    }

    @Test fun fencedCodeNeverTurnsItsHeadingsOrPipesIntoRenderedBlocks() {
        val body = "# literal\n| A | B |\n| --- | --- |\n| x | y |"
        val blocks = parseMarkdown("```markdown\n$body\n```\n\n# Actual title")
        assertEquals(body, (blocks[0] as MarkdownBlock.CodeBlock).text)
        assertEquals("Actual title", (blocks[1] as MarkdownBlock.Title).text.text)
        assertEquals(body, (parseMarkdown("```\n$body").single() as MarkdownBlock.CodeBlock).text)
    }

    @Test fun inlineCodeDoesNotTurnLiteralHashesIntoHeadings() {
        val text = (parseMarkdown("`# literal` and **bold**").single() as MarkdownBlock.Body).text
        assertEquals("# literal and bold", text.text)
        assertTrue(text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun unsupportedMarkupStaysLiteralInsteadOfDisappearing() {
        val source = "*斜體* [連結](https://example.com) ![圖](https://example.com/a.png) <b>HTML</b> ~~刪除~~"
        assertEquals(source, (parseMarkdown(source).single() as MarkdownBlock.Body).text.text)
        assertEquals("- 項目\n> 引用", (parseMarkdown("- 項目\n> 引用").single() as MarkdownBlock.Body).text.text)
    }

    @Test fun crlfAndBlankInputAreHandled() {
        assertTrue(parseMarkdown(" \n\t").isEmpty())
        val blocks = parseMarkdown("# 標題\r\n\r\nA | B\r\n--- | ---\r\n中文 | English\r\n")
        assertEquals("標題", (blocks[0] as MarkdownBlock.Title).text.text)
        assertEquals(listOf("中文", "English"), (blocks[1] as MarkdownBlock.Table).rows.single().map { it.text })
    }
}
