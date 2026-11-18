package com.aichat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aichat.app.data.*
import com.aichat.app.domain.*
import com.aichat.app.ui.theme.iosColorScheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BranchTreeListUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun node(id: String, parent: String?, children: List<String> = emptyList(), end: String? = null,
        text: String = "A long scene in the forest and at the seaside, with the white cat") = BranchTreeNode(
        id, parent, children, if (parent == null) emptyList() else listOf(BranchTreeRow("b", id, id, 1,
            false, false, if (id.length % 2 == 0) "assistant" else "user", MessageKind.CHAT, 1,
            MessageVersionStatus.COMPLETE, MessageVersionSource.ORIGINAL, text, null, true)),
        listOfNotNull(end), listOfNotNull(end))
    private fun fixture() = BranchTree(listOf(
        node("root", null, listOf("shared")), node("shared", "root", listOf("forest", "sea")),
        node("forest", "shared", listOf("black", "white")), node("black", "forest", end = "active"),
        node("white", "forest", end = "white"), node("sea", "shared", listOf("boat")), node("boat", "sea", end = "sea"),
    ).associateBy { it.id })
    private lateinit var state: BranchListState
    private var taps = 0
    private fun show(tree: BranchTree, dark: Boolean = false, font: Float = 1f) {
        rule.setContent { MaterialTheme(colorScheme = iosColorScheme(dark)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                state = rememberBranchListState("list-test")
                BranchTreeList(tree, "active", state, AppLanguage.TRADITIONAL_CHINESE, emptyMap(), { taps++ },
                    Modifier.requiredWidth(360.dp).height(500.dp))
            }
        } }
        rule.waitUntil(10000) { state.initializedActive == "active" && rule.onAllNodesWithTag("branch-list-row-black").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }
    private fun row(id: String) = rule.onNodeWithTag("branch-list-row-$id")
    private fun arrow(id: String) = rule.onNodeWithTag("branch-list-toggle-$id")
    private fun bounds(tag: String): Rect {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        return rule.runOnIdle {
            val c = node.layoutInfo.coordinates
            val points = listOf(Offset.Zero, Offset(c.size.width.toFloat(), 0f), Offset(0f, c.size.height.toFloat()),
                Offset(c.size.width.toFloat(), c.size.height.toFloat())).map(c::localToRoot)
            Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        }
    }
    private fun evidence(name: String, allowHorizontalOverflow: Boolean = false) {
        val dir = File(rule.activity.getExternalFilesDir(null), "branch-list-horizontal-qa").apply { mkdirs() }
        val list = bounds("branch-tree-list-viewport")
        val tags = rule.onAllNodes(SemanticsMatcher("list row or arrow") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.let { tag ->
                tag.startsWith("branch-list-row-") || tag.startsWith("branch-list-toggle-") }
        }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        val measured = tags.associateWith(::bounds)
        measured.forEach { (tag, rect) ->
            if (!allowHorizontalOverflow || tag.startsWith("branch-list-row-")) {
                assertTrue("$tag left clipped: $rect in $list", rect.left >= list.left - 1)
                assertTrue("$tag right clipped: $rect in $list", rect.right <= list.right + 1)
            }
        }
        File(dir, "$name.txt").writeText("viewport=$list horizontalOffsetPx=${state.horizontalOffset}\n" + measured.entries.joinToString("\n") { "${it.key}=${it.value}" })
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun initial360DpArrowsAndRowsHaveSeparateActions() {
        show(fixture())
        evidence("01-default-360dp")
        row("boat").assertDoesNotExist()
        arrow("sea").performTouchInput { click(center) }
        rule.waitForIdle()
        row("boat").assertExists()
        assertEquals(0, taps)
        evidence("02-expanded-360dp")
        arrow("sea").performClick()
        rule.waitForIdle()
        row("sea").performClick()
        rule.waitForIdle()
        assertEquals(0, taps)
        row("sea").performClick()
        assertEquals(1, taps)
        row("boat").performClick()
        assertEquals(2, taps)
        arrow("forest").performClick()
        rule.waitForIdle()
        row("black").assertDoesNotExist()
        rule.runOnIdle { state.reconcile(fixture(), "active") }
        row("black").assertDoesNotExist()
        row("forest").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("branch-list-current-black", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("branch-list-current-shared", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun deep360DpKeepsIndentingAndHorizontalGesturesReachBothSides() {
        val nodes = (0..14).map { i -> node(if (i == 0) "root" else "$i",
            if (i == 0) null else if (i == 1) "root" else "${i - 1}",
            if (i == 14) emptyList() else listOf("${i + 1}"), if (i == 14) "active" else null) }
        val tree = BranchTree(nodes.associateBy { it.id })
        rule.setContent { MaterialTheme(colorScheme = iosColorScheme(true)) {
            val d = LocalDensity.current
            Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) {
                state = rememberBranchListState("deep")
                BranchTreeList(tree, "active", state, AppLanguage.SIMPLIFIED_CHINESE, emptyMap(), { taps++ }, Modifier.requiredWidth(360.dp).height(500.dp))
            } } }
        } }
        rule.waitUntil(10000) { rule.onAllNodesWithTag("branch-list-row-14").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10000) { state.horizontalOffset > 0 && state.scrollTarget == null }
        rule.waitForIdle()
        rule.onNodeWithText("第 14 层").assertDoesNotExist()
        evidence("03-deep-dark-130percent-360dp", allowHorizontalOverflow = true)
        val density = rule.onNodeWithTag("branch-tree-list").fetchSemanticsNode().layoutInfo.density.density
        val toggle = bounds("branch-list-toggle-13")
        assertTrue(toggle.width >= 48 * density - 1)
        assertTrue(toggle.height >= 48 * density - 1)
        val viewport = bounds("branch-tree-list-viewport")
        val body = bounds("branch-list-content-14")
        assertTrue(body.left >= viewport.left && body.right <= viewport.right + 1)
        assertTrue(body.width >= 250f * density)
        assertEquals(20f * density, bounds("branch-list-content-14").left - bounds("branch-list-content-13").left, 1f)
        assertEquals(20f * density, bounds("branch-list-content-13").left - bounds("branch-list-content-12").left, 1f)
        val initialX = state.horizontalOffset
        val vertical = state.scroll.firstVisibleItemIndex to state.scroll.firstVisibleItemScrollOffset
        rule.onNodeWithTag("branch-tree-list-viewport").performTouchInput {
            swipe(Offset(width * 0.2f, height * 0.5f), Offset(width * 0.8f, height * 0.5f), 500)
        }
        rule.waitForIdle()
        assertTrue("Dragging right must reveal the left columns", state.horizontalOffset < initialX - 10)
        evidence("04-deep-panned-left-360dp", allowHorizontalOverflow = true)
        rule.onNodeWithTag("branch-tree-list-viewport").performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.5f), Offset(width * 0.2f, height * 0.5f), 500)
        }
        rule.waitForIdle()
        assertTrue("Dragging left must reveal deeper nodes", state.horizontalOffset > initialX * 0.8f)
        assertEquals(vertical, state.scroll.firstVisibleItemIndex to state.scroll.firstVisibleItemScrollOffset)
        assertEquals(0, taps)
    }

    @Test fun horizontalAndVerticalPositionSurviveRefreshRestoreAndReentry() {
        val nodes = (0..20).map { i -> node(if (i == 0) "root" else "$i",
            if (i == 0) null else if (i == 1) "root" else "${i - 1}",
            if (i == 20) emptyList() else listOf("${i + 1}"), "b$i") }
        var model by mutableStateOf(BranchTree(nodes.associateBy { it.id }))
        var showing by mutableStateOf(true)
        val restore = StateRestorationTester(rule)
        restore.setContent { MaterialTheme {
            val holder = rememberSaveableStateHolder()
            if (showing) holder.SaveableStateProvider("horizontal") {
                state = rememberBranchListState("horizontal")
                BranchTreeList(model, "b20", state, AppLanguage.TRADITIONAL_CHINESE, emptyMap(), { taps++ }, Modifier.size(360.dp, 400.dp))
            }
        } }
        rule.waitUntil(10000) { state.initializedActive == "b20" && state.horizontalOffset > 100 && state.scrollTarget == null }
        rule.onNodeWithTag("branch-tree-horizontal").performSemanticsAction(SemanticsActions.ScrollBy) { it(-100f, 0f) }
        rule.onNodeWithTag("branch-tree-list-viewport").performTouchInput {
            swipe(Offset(width * 0.6f, height * 0.4f), Offset(width * 0.6f, height * 0.75f), 500)
        }
        rule.waitForIdle()
        val x = state.horizontalOffset
        val vertical = state.scroll.firstVisibleItemIndex to state.scroll.firstVisibleItemScrollOffset
        assertEquals(0, taps)
        rule.runOnIdle { model = model.copy(nodes = model.nodes.mapValues { (_, n) -> n.copy(rows = n.rows.map { it.copy(snippet = "refreshed") }) }) }
        rule.waitForIdle()
        assertEquals(x, state.horizontalOffset, 0.1f)
        assertEquals(vertical, state.scroll.firstVisibleItemIndex to state.scroll.firstVisibleItemScrollOffset)
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(x, state.horizontalOffset, 0.1f)
        assertEquals(vertical, state.scroll.firstVisibleItemIndex to state.scroll.firstVisibleItemScrollOffset)
        rule.runOnIdle { showing = false }
        rule.waitForIdle()
        rule.runOnIdle { showing = true }
        rule.waitForIdle()
        assertEquals(x, state.horizontalOffset, 0.1f)
        assertEquals(vertical, state.scroll.firstVisibleItemIndex to state.scroll.firstVisibleItemScrollOffset)
    }

    @Test fun veryDeepIndentationDoesNotCreateAnOversizedAndroidLayout() {
        val nodes = (0..20000).map { i -> node(if (i == 0) "root" else "$i",
            if (i == 0) null else if (i == 1) "root" else "${i - 1}",
            if (i == 20000) emptyList() else listOf("${i + 1}"), if (i == 20000) "active" else null) }
        val model = BranchTree(nodes.associateBy { it.id })
        rule.setContent { MaterialTheme {
            state = rememberBranchListState("very-deep")
            BranchTreeList(model, "active", state, AppLanguage.TRADITIONAL_CHINESE, emptyMap(), {}, Modifier.size(360.dp, 400.dp))
        } }
        rule.waitUntil(15000) { state.initializedActive == "active" && state.scrollTarget == null && state.horizontalOffset > 100000 }
        rule.waitForIdle()
        val viewport = bounds("branch-tree-list-viewport")
        val current = bounds("branch-list-content-20000")
        assertTrue(current.left >= viewport.left - 1 && current.right <= viewport.right + 1)
        val density = rule.onNodeWithTag("branch-tree-list").fetchSemanticsNode().layoutInfo.density.density
        assertEquals(20f * density, current.left - bounds("branch-list-content-19999").left, 1f)
        assertTrue(state.scroll.layoutInfo.visibleItemsInfo.size < 20)
    }

    @Test fun thousandRowsAreVirtualizedAndStateSurvivesRefreshAndRestoration() {
        val children = (0 until 1000).map { "leaf$it" }
        var model by mutableStateOf(BranchTree((listOf(node("root", null, children)) +
            children.mapIndexed { i, id -> node(id, "root", end = "b$i") }).associateBy { it.id }))
        var active by mutableStateOf("b0")
        var showing by mutableStateOf(true)
        val restore = StateRestorationTester(rule)
        restore.setContent { MaterialTheme {
            val holder = rememberSaveableStateHolder()
            if (showing) holder.SaveableStateProvider("large") {
                state = rememberBranchListState("large")
                BranchTreeList(model, active, state, AppLanguage.TRADITIONAL_CHINESE, emptyMap(), { taps++ }, Modifier.size(360.dp, 400.dp))
            }
        } }
        rule.waitUntil(10000) { state.initializedActive == "b0" && state.scroll.layoutInfo.totalItemsCount == 1001 }
        val matcher = SemanticsMatcher("list row") { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("branch-list-row-") }
        assertTrue(rule.onAllNodes(matcher).fetchSemanticsNodes().size < 20)
        rule.onNodeWithTag("branch-tree-list").performScrollToIndex(500)
        rule.waitForIdle()
        val before = rule.runOnIdle { state.scroll.firstVisibleItemIndex to state.scroll.firstVisibleItemScrollOffset }
        rule.runOnIdle { model = model.copy(nodes = model.nodes.mapValues { (_, n) -> n.copy(rows = n.rows.map { it.copy(snippet = "refreshed") }) }) }
        rule.waitForIdle()
        assertEquals(before, rule.runOnIdle { state.scroll.firstVisibleItemIndex to state.scroll.firstVisibleItemScrollOffset })
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(before, rule.runOnIdle { state.scroll.firstVisibleItemIndex to state.scroll.firstVisibleItemScrollOffset })
        rule.runOnIdle { showing = false }
        rule.waitForIdle()
        rule.runOnIdle { showing = true }
        rule.waitForIdle()
        assertEquals(before, rule.runOnIdle { state.scroll.firstVisibleItemIndex to state.scroll.firstVisibleItemScrollOffset })
        assertTrue("root" in state.expanded)
        assertEquals(0, taps)
        rule.runOnIdle { active = "b900" }
        rule.waitUntil(10000) { rule.onAllNodesWithTag("branch-list-row-leaf900").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        row("leaf900").assertIsDisplayed()
        rule.runOnIdle { state.toggle(model, "root") }
        rule.waitUntil(10000) { state.scroll.layoutInfo.totalItemsCount == 1 }
        row("root").assertIsDisplayed()
        assertEquals(0, state.scroll.firstVisibleItemIndex)
        assertTrue(rule.onAllNodes(matcher).fetchSemanticsNodes().size < 20)
    }
}
