package com.aichat.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aichat.app.data.AppLanguage
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.MessageVersionEntity
import com.aichat.app.ui.theme.iosColorScheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MarkdownUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var taps = 0
    private var actions by mutableStateOf(false)
    private val source = "# 森林旅記\n\n## 任務紀錄\n\n" +
        "| 角色 | 地點 | 任務 | 結果 |\n| :--- | :---: | ---: | --- |\n" +
        "| **白貓** | 森林 | `調查` | 任務完成 |\n" +
        "| 旅人 | 海邊 | " + "尋找失落的故事線索。".repeat(8) + " | 等待後續 |\n\n結尾段落"

    private fun show(dark: Boolean = false, user: Boolean = false, font: Float = 1f, text: String = source) {
        rule.setContent { MaterialTheme(colorScheme = iosColorScheme(dark)) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, font)) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        Column(Modifier.requiredWidth(360.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                            val message = MessageEntity("md", "md-chat", if (user) "user" else "assistant", text, 1, currentVersionId = "md-v1")
                            MessageBubble(message = message,
                                versions = listOf(MessageVersionEntity("md-v1", "md", 1, text, 1)),
                                generationContext = null, language = AppLanguage.TRADITIONAL_CHINESE,
                                bubbleOpacity = 1f, characterName = "AI", characterSeed = "markdown",
                                isGenerating = false, canContinue = false, highlighted = false,
                                historyActionsEnabled = true, actionsVisible = actions,
                                onToggleActions = { taps++; actions = !actions }, onEdit = { _, _ -> },
                                onSelectVersion = { _, _ -> }, onGenerateAlternative = {}, onAiReplyFrom = {},
                                onEditAuthored = {}, onAnswerFrom = {}, onContinue = {}, onDelete = {}, onToggleExcluded = {})
                        }
                    }
                }
            }
        } }
        rule.waitUntil(10000) { rule.onAllNodesWithTag("markdown-table-2", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
    private fun bounds(tag: String): Rect {
        val n = node(tag).fetchSemanticsNode()
        return rule.runOnIdle {
            val c = n.layoutInfo.coordinates
            val points = listOf(Offset.Zero, Offset(c.size.width.toFloat(), 0f), Offset(0f, c.size.height.toFloat()),
                Offset(c.size.width.toFloat(), c.size.height.toFloat())).map(c::localToRoot)
            Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        }
    }

    private fun screenshot(name: String) {
        val dir = File(rule.activity.getExternalFilesDir(null), "markdown-qa").apply { mkdirs() }
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun layout(tag: String): TextLayoutResult {
        val result = ArrayList<TextLayoutResult>()
        node(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    @Test fun headingsAndTableScrollInsideRealBubbleWithoutMisclicking() {
        show()
        node("markdown-heading-0").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertTrue(layout("markdown-heading-0").layoutInput.style.fontSize.value > layout("markdown-heading-1").layoutInput.style.fontSize.value)
        assertEquals("森林旅記", layout("markdown-heading-0").layoutInput.text.text)
        assertEquals(bounds("markdown-cell-2-2-0").height, bounds("markdown-cell-2-2-2").height, 1f)
        screenshot("01-light-heading-table")
        rule.onNodeWithText("森林旅記", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, taps)
        rule.onNodeWithText("森林旅記", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(2, taps)
        val before = bounds("markdown-cell-2-0-3").left
        repeat(3) { node("markdown-table-2").performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.5f), Offset(width * 0.2f, height * 0.5f), 500)
        } }
        rule.waitForIdle()
        assertTrue(bounds("markdown-cell-2-0-3").left < before - 20)
        assertTrue(bounds("markdown-cell-2-0-3").right <= bounds("markdown-table-2").right + 1)
        assertEquals("Horizontal scroll must not toggle bubble actions", 2, taps)
        screenshot("02-table-panned-right")
        rule.onNodeWithText("森林旅記", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        rule.onNodeWithContentDescription("複製").performClick()
        val clipboard = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        rule.runOnIdle { assertEquals(source, clipboard.primaryClip!!.getItemAt(0).text.toString()) }
    }

    @Test fun darkLargeFontUserBubbleKeepsCellsReadableAndUnclippedVertically() {
        show(dark = true, user = true, font = 1.3f)
        for (row in 0..2) for (column in 0..3) {
            assertFalse("Cell $row/$column clips text", layout("markdown-cell-2-$row-$column").hasVisualOverflow)
        }
        screenshot("03-dark-user-130percent")
        assertEquals(bounds("markdown-cell-2-2-0").height, bounds("markdown-cell-2-2-2").height, 1f)
    }

    @Test fun streamingCanTurnPartialTextIntoTableAndUpdateExistingCells() {
        var text by mutableStateOf("# 片段\n\n| A | B |")
        rule.setContent { MaterialTheme {
            Column(Modifier.width(300.dp)) { MarkdownText(text) }
        } }
        rule.waitUntil(10000) { rule.onAllNodesWithTag("markdown-heading-0").fetchSemanticsNodes().isNotEmpty() }
        node("markdown-table-1").assertDoesNotExist()
        rule.runOnIdle { text += "\n| --- | --- |\n| **第一欄** | 尚未完成" }
        rule.waitUntil(10000) { rule.onAllNodesWithText("尚未完成", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        node("markdown-table-1").assertExists()
        rule.runOnIdle { text += "的內容 |\n| 第二列 | 已完成 |" }
        rule.waitUntil(10000) { rule.onAllNodesWithText("已完成", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("尚未完成的內容", layout("markdown-cell-1-1-1").layoutInput.text.text)
    }
}
