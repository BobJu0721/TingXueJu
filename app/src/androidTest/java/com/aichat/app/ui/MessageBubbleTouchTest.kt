package com.aichat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.app.data.AppLanguage
import com.aichat.app.data.GenerationContextEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.MessageVersionEntity
import com.aichat.app.ui.theme.iosColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Touch regression coverage for [MessageBubble].
 *
 * Interactions are injected as real touches, never through semantic actions: `performClick()` would
 * invoke the click action directly and bypass the hit test that the bubble's outer rounded-corner
 * clip layer performs, so it cannot detect this class of defect.
 *
 * Injection is anchored on the target node's visible centre, so every touch provably lands on the
 * body text of the bubble rather than on its padding.
 *
 * The defect only appears once the bubble is much taller than the viewport and the touch lands far
 * from the clip layer's origin, so the deep-scroll cases below are the ones that matter.
 */
@RunWith(AndroidJUnit4::class)
class MessageBubbleTouchTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    // ---- 短泡泡：基準行為，修正前後都必須通過 ----

    @Test
    fun shortBubbleTogglesActionsOnEachCoordinateTap() {
        val state = BubbleState()
        val content = shortContent()
        rule.setContent {
            Host {
                TestBubble(aiMessage(content), content, "", state.actionsVisible, state::toggle)
            }
        }

        rule.tapContent("正文段落 C1")
        assertEquals("第一次點擊應展開操作列", 1, state.toggleCount)
        assertTrue(state.actionsVisible)

        rule.tapContent("正文段落 C1")
        assertEquals("第二次點擊應收起操作列", 2, state.toggleCount)
        assertTrue(!state.actionsVisible)
    }

    // ---- 長思考展開後捲到正文 ----

    @Test
    fun contentTapTogglesAfterReasoningIsExpanded() {
        val state = BubbleState()
        val content = moderateContent()
        val reasoning = reasoningLines(80)
        rule.setContent {
            Host {
                TestBubble(aiMessage(content), content, reasoning, state.actionsVisible, state::toggle)
            }
        }

        rule.expandReasoning()
        rule.tapContent("正文段落 C1")
        assertEquals(1, state.toggleCount)

        rule.tapContent("正文段落 C1")
        assertEquals("展開的思考內容不應吃掉正文的第二次點擊", 2, state.toggleCount)
        assertTrue(!state.actionsVisible)
    }

    // ---- 超高泡泡：本次缺陷的主要重現條件 ----

    @Test
    fun veryTallBubbleContentTapStillTogglesActions() {
        val state = BubbleState()
        val content = contentLines(400)
        val reasoning = reasoningLines(400)
        rule.setContent {
            Host {
                TestBubble(aiMessage(content), content, reasoning, state.actionsVisible, state::toggle)
            }
        }

        rule.expandReasoning()
        rule.tapContent("正文段落 C400")
        assertEquals("超高泡泡的正文觸點必須送達點擊回呼", 1, state.toggleCount)
        assertTrue(state.actionsVisible)

        rule.tapContent("正文段落 C400")
        assertEquals(2, state.toggleCount)
        assertTrue(!state.actionsVisible)
    }

    @Test
    fun veryTallBubbleStillTogglesWhenActionsWereAlreadyVisible() {
        val state = BubbleState(initiallyVisible = true)
        val content = contentLines(400)
        val reasoning = reasoningLines(400)
        rule.setContent {
            Host {
                TestBubble(aiMessage(content), content, reasoning, state.actionsVisible, state::toggle)
            }
        }

        rule.expandReasoning()
        rule.tapContent("正文段落 C400")
        assertEquals("操作列已展開時，捲到正文後仍應能點擊收起", 1, state.toggleCount)
        assertTrue(!state.actionsVisible)
    }

    // ---- 使用者超長訊息：明確捲到後段，讓觸點遠離 clip layer 原點 ----

    @Test
    fun veryTallUserBubbleTogglesFromItsLaterSection() {
        val state = BubbleState()
        val content = contentLines(800)
        val scrollState = ScrollState(0)
        rule.setContent {
            Host(scrollState = scrollState) {
                TestBubble(userMessage(content), content, "", state.actionsVisible, state::toggle)
            }
        }

        rule.scrollTo(scrollState, USER_BUBBLE_SCROLL_DEPTH)
        rule.nodeWithText("正文段落 C400").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals("使用者超長訊息後段仍必須可點擊", 1, state.toggleCount)
        assertTrue(state.actionsVisible)
    }

    // ---- 多則訊息：只影響目標泡泡 ----

    @Test
    fun tapOnlyTogglesTheTargetBubble() {
        val first = BubbleState()
        val second = BubbleState()
        val firstContent = "第一則訊息的正文內容。"
        val secondContent = "第二則訊息的正文內容。"
        rule.setContent {
            Host {
                TestBubble(aiMessage(firstContent, id = "m-first"), firstContent, "", first.actionsVisible, first::toggle)
                TestBubble(aiMessage(secondContent, id = "m-second"), secondContent, reasoningLines(120), second.actionsVisible, second::toggle)
            }
        }

        rule.tapContent("第一則訊息的正文內容")
        assertEquals(1, first.toggleCount)
        assertEquals("不應誤觸鄰近泡泡", 0, second.toggleCount)
    }

    // ---- 思考區自己的操作不得連帶切換操作列 ----

    @Test
    fun reasoningHeaderAndCopyKeepTheirOwnBehaviour() {
        val state = BubbleState()
        val content = moderateContent()
        val reasoning = reasoningLines(40)
        rule.setContent {
            Host {
                TestBubble(aiMessage(content), content, reasoning, state.actionsVisible, state::toggle)
            }
        }

        rule.tapNode(rule.nodeWithText("思考過程"))
        assertEquals("思考標題只切換思考區，不應切換訊息操作列", 0, state.toggleCount)
        rule.nodeWithText("推論段落 R40").assertExists()

        rule.tapNode(rule.onNodeWithContentDescription("複製思考內容", useUnmergedTree = true))
        assertEquals("複製思考內容不應切換訊息操作列", 0, state.toggleCount)
    }

    // ---- 長按：超高泡泡的觸控也必須送達 ----

    /**
     * 修正前，超高泡泡的長按會被外層裁切完全吃掉（回呼 0 次）；修正後必須送達同一顆泡泡。
     *
     * 這裡只驗證「送達」，不規定長按的語意。落在正文上的長按會被 `SelectionContainer` 接手
     * 做選字，但選取工具列是 Compose 之外的平台視窗，不會形成額外的 Compose root，因此測試端
     * 無法觀察選取是否真的啟動。**長按選字仍待人工驗收**，不因為本測試通過就視為已驗證。
     */
    @Test
    fun longPressReachesVeryTallBubble() {
        val state = BubbleState()
        val content = contentLines(400)
        val reasoning = reasoningLines(400)
        rule.setContent {
            Host {
                TestBubble(aiMessage(content), content, reasoning, state.actionsVisible, state::toggle)
            }
        }

        rule.expandReasoning()
        val body = rule.nodeWithText("正文段落 C400")
        body.performScrollTo()
        body.performTouchInput { longClick() }
        rule.waitForIdle()

        println(
            "[MessageBubbleTouch] long press: toggles=${state.toggleCount} roots=${rule.rootCount()}",
        )
        assertEquals("修正前超高泡泡的長按會完全掉觸控", 1, state.toggleCount)
    }

    // ---- 深度掃描：產生「捲到多深開始掉觸控」的證據 ----

    @Test
    fun reportTouchDeliveryAcrossScrollDepth() {
        var taps by mutableIntStateOf(0)
        val content = contentLines(800)
        val scrollState = ScrollState(0)
        rule.setContent {
            Host(scrollState = scrollState) {
                TestBubble(aiMessage(content), content, "", false, { taps++ })
            }
        }

        val evidence = StringBuilder()
        var topDelivered = false
        for (depth in SCROLL_DEPTHS) {
            rule.runOnIdle {
                taps = 0
                scrollState.dispatchRawDelta((depth - scrollState.value).toFloat())
            }
            rule.waitForIdle()
            val actualDepth = rule.runOnIdle { scrollState.value }
            rule.nodeWithText("正文段落 C400").performTouchInput { click() }
            rule.waitForIdle()
            val delivered = taps == 1
            if (depth == 0) topDelivered = delivered
            evidence.append("scroll=$actualDepth delivered=$delivered\n")
        }
        println("[MessageBubbleTouch] depth probe:\n$evidence")
        assertTrue("捲動最淺時必須可點擊，否則測試本身無效", topDelivered)
    }

    /** 觀測用：選取工具列等彈出視窗會形成額外的 Compose root。 */
    private fun ComposeContentTestRule.rootCount(): Int =
        runCatching { onAllNodes(isRoot()).fetchSemanticsNodes().size }.getOrDefault(-1)

    private companion object {
        const val USER_BUBBLE_SCROLL_DEPTH = 20_000

        /** 由淺到深，涵蓋已重現缺陷的深層區域座標範圍。 */
        val SCROLL_DEPTHS = listOf(0, 2_000, 4_000, 6_000, 8_000, 10_000, 12_000, 16_000, 20_000, 24_000)
    }
}

// ---- 測試替身 ----

private const val CONVERSATION_ID = "touch-test-conversation"

private class BubbleState(initiallyVisible: Boolean = false) {
    var actionsVisible by mutableStateOf(initiallyVisible)
    var toggleCount by mutableIntStateOf(0)

    fun toggle() {
        toggleCount++
        actionsVisible = !actionsVisible
    }
}

private fun aiMessage(content: String, id: String = "m-ai"): MessageEntity = MessageEntity(
    id = id,
    conversationId = CONVERSATION_ID,
    role = "assistant",
    content = content,
    createdAt = 1_000L,
    currentVersionId = "$id-v1",
)

private fun userMessage(content: String): MessageEntity = MessageEntity(
    id = "m-user",
    conversationId = CONVERSATION_ID,
    role = "user",
    content = content,
    createdAt = 2_000L,
    currentVersionId = "m-user-v1",
)

private fun contentLines(count: Int): String =
    (1..count).joinToString("\n") { "正文段落 C$it 用來說明這段回覆的內容。" }

private fun reasoningLines(count: Int): String =
    (1..count).joinToString("\n") { "推論段落 R$it 用來說明模型的思考內容。" }

private fun shortContent(): String = "正文段落 C1 用來說明這段回覆的內容。"

private fun moderateContent(): String = (1..6).joinToString("\n") { "正文段落 C$it 用來說明這段回覆的內容。" }

/** 一律提供捲動容器：`performScrollTo()` 需要有 Scroll 語意的父節點，深層捲動也是重現缺陷的條件。 */
@Composable
private fun Host(
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = remember { iosColorScheme(dark = false) }) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) { content() }
    }
}

@Composable
private fun TestBubble(
    message: MessageEntity,
    content: String,
    reasoning: String,
    actionsVisible: Boolean,
    onToggleActions: () -> Unit,
) {
    val version = MessageVersionEntity(
        id = message.currentVersionId,
        messageId = message.id,
        versionNumber = 1,
        content = content,
        createdAt = message.createdAt,
    )
    MessageBubble(
        message = message,
        versions = listOf(version),
        generationContext = GenerationContextEntity(
            versionId = version.id,
            reasoningContent = reasoning,
        ),
        language = AppLanguage.TRADITIONAL_CHINESE,
        bubbleOpacity = 1f,
        characterName = "AI",
        characterSeed = "seed",
        isGenerating = false,
        canContinue = false,
        highlighted = false,
        historyActionsEnabled = true,
        actionsVisible = actionsVisible,
        onToggleActions = onToggleActions,
        onEdit = { _, _ -> },
        onSelectVersion = { _, _ -> },
        onGenerateAlternative = { },
        onAnswerFrom = { },
        onContinue = { },
        onDelete = { },
        onToggleExcluded = { },
    )
}

// ---- 觸控注入工具 ----

/**
 * 泡泡上的 `clickable` 會合併子節點語意，合併後的節點等同整顆泡泡，直接對它注入座標會點到
 * 泡泡中心而不是目標文字。因此一律在未合併樹中定位，並把觸點錨定在該節點的可見中心。
 */
private fun ComposeContentTestRule.nodeWithText(marker: String): SemanticsNodeInteraction =
    onNodeWithText(marker, substring = true, useUnmergedTree = true)

private fun ComposeContentTestRule.tapNode(node: SemanticsNodeInteraction) {
    node.performScrollTo()
    node.performTouchInput { click() }
    waitForIdle()
}

private fun ComposeContentTestRule.tapContent(marker: String) {
    tapNode(nodeWithText(marker))
}

private fun ComposeContentTestRule.scrollTo(state: ScrollState, depth: Int) {
    runOnIdle { state.dispatchRawDelta((depth - state.value).toFloat()) }
    waitForIdle()
}

private fun ComposeContentTestRule.expandReasoning() {
    val header = nodeWithText("思考過程")
    header.performScrollTo()
    header.performTouchInput { click() }
    waitForIdle()
    nodeWithText("推論段落 R1").assertExists()
}
