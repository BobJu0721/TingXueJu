package com.aichat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.aichat.app.*
import com.aichat.app.data.*
import com.aichat.app.ui.theme.iosColorScheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class BranchTreeUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: AppDatabase
    private lateinit var chat: ChatViewModel
    private lateinit var tree: BranchTreeViewModel
    private lateinit var repo: ConversationRepository
    private var returned = 0
    private var showingTree by mutableStateOf(true)

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(rule.activity, AppDatabase::class.java).build()
        val container = AppContainer(rule.activity, db)
        repo = container.conversationRepository
        db.chatDao().upsertConversation(ConversationEntity("ui-tree", "Story routes", 1, 1, activeBranchId = "forest"))
        val contents = listOf(
            "forest" to listOf("Departure", "Go to forest", "Meet black cat", "Arrive at cabin"),
            "sea" to listOf("Departure", "Go to sea", "Meet fisherman", "Board the boat"),
            "white" to listOf("Departure", "Go to forest", "Meet white cat", "Follow white cat"),
        )
        val messageIds = listOf("start", "choice", "reply", "finish")
        val versions = HashMap<String, String>()
        val versionNumbers = HashMap<String, Int>()
        messageIds.forEachIndexed { i, m -> db.chatDao().upsertMessage(MessageEntity(m, "ui-tree", if (i % 2 == 0) "assistant" else "user", "", i.toLong(), sortOrder = i + 1L)) }
        contents.forEachIndexed { b, (id, text) ->
            db.chatDao().upsertBranch(ConversationBranchEntity(id, "ui-tree", createdAt = b + 1L, lastUsedAt = 1, sceneNote = "$id note"))
            text.forEachIndexed { i, content ->
                val key = "${messageIds[i]}:$content"
                val v = versions[key] ?: "version-${versions.size}".also { versionId ->
                    versions[key] = versionId
                    val number = (versionNumbers[messageIds[i]] ?: 0) + 1; versionNumbers[messageIds[i]] = number
                    db.chatDao().insertMessageVersion(MessageVersionEntity(versionId, messageIds[i], number, content, 1))
                }
                db.chatDao().upsertBranchMessage(BranchMessageEntity(id, messageIds[i], v, i + 1L))
            }
        }
        chat = ChatViewModel(container); tree = BranchTreeViewModel(container)
        chat.selectConversation("ui-tree"); chat.setInput("unsent input")
        Unit
    }
    @After fun cleanup() { chat.viewModelScope.cancel(); tree.viewModelScope.cancel(); db.close() }
    private fun show() {
        rule.setContent { MaterialTheme(colorScheme = iosColorScheme(false)) {
            if (showingTree) ChatBranchScreen(tree, chat, AppLanguage.TRADITIONAL_CHINESE) { returned++; showingTree = false }
            else ChatScreen(chat, AppLanguage.TRADITIONAL_CHINESE) { returned++ }
        } }
        rule.waitUntil(10000) { tree.state.value.tree != null }
        rule.onNodeWithContentDescription("適合畫面").performClick()
        rule.waitForIdle()
    }
    private fun screenshot(name: String) {
        val dir = File(rule.activity.getExternalFilesDir(null), "branch-tree-qa").apply { mkdirs() }
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun graphPreviewAndExactSwitchPreserveOtherRoutes() = runBlocking {
        show()
        screenshot("01-branches")
        rule.onNodeWithText("目前使用", substring = true).assertExists()
        val collapsed = rule.onNodeWithText("Go to sea", substring = true).fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { click(collapsed.center) }
        rule.waitUntil(5000) { rule.onAllNodesWithText("路線 2", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("適合畫面").performClick()
        screenshot("04-expanded")
        // Real coordinate injection: transform/culling must agree with the semantic node's visible bounds.
        val sea = rule.onNodeWithText("路線 2", substring = true).fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { click(sea.center) }
        rule.waitUntil(5000) { tree.preview.value?.page != null }
        rule.onAllNodesWithText("Go to sea", substring = true).onLast().assertExists()
        assertEquals("forest", repo.getActiveBranch("ui-tree")!!.id)
        rule.onAllNodes(isRoot()).onLast().performTouchInput {
            swipe(Offset(center.x, height * 0.65f), Offset(center.x, height * 0.15f), 500)
        }
        rule.onNodeWithText("切換到這條路線").assertIsDisplayed()
        screenshot("02-preview")
        rule.onNodeWithText("切換到這條路線").performClick()
        rule.waitUntil(5000) { returned == 1 }
        assertEquals("sea", repo.getActiveBranch("ui-tree")!!.id)
        assertEquals("unsent input", chat.input.value)
        rule.waitUntil(5000) { chat.branchLocation.value == null }
        rule.onNodeWithText("Go to sea").assertIsDisplayed()
        rule.onNodeWithText("Meet black cat").assertDoesNotExist()
        assertEquals(listOf("Departure", "Go to forest", "Meet black cat", "Arrive at cabin"), repo.getBranchContents("forest"))
    }

    @Test fun transformsDoNotClickAndRestorationKeepsGraphViewport() {
        val restore = StateRestorationTester(rule)
        restore.setContent { MaterialTheme(colorScheme = iosColorScheme(true)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                ChatBranchScreen(tree, chat, AppLanguage.SIMPLIFIED_CHINESE) { returned++ }
            }
        } }
        rule.waitUntil(10000) { tree.state.value.tree != null }
        rule.onNodeWithContentDescription("适合画面").performClick()
        rule.onNodeWithTag("branch-tree-canvas").performTouchInput { swipe(center, center + Offset(50f, 60f), 500) }
        assertNull(tree.preview.value)
        rule.onNodeWithContentDescription("放大").performClick()
        rule.waitForIdle()
        val before = rule.onNodeWithTag("branch-node-root").fetchSemanticsNode().boundsInRoot
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        val after = rule.onNodeWithTag("branch-node-root").fetchSemanticsNode().boundsInRoot
        assertEquals(before.left, after.left, 2f); assertEquals(before.top, after.top, 2f)
        rule.onNodeWithContentDescription("定位当前路线").performClick()
        screenshot("03-dark-transformed")
    }

    @Test fun returningFromPreviewDoesNotSwitchOrCloseSearch() = runBlocking {
        chat.openSearch(); chat.setSearchQuery("cat")
        show()
        rule.runOnIdle { tree.select("white", "reply") }
        rule.waitUntil(5000) { tree.preview.value?.page != null }
        rule.onAllNodesWithText("Meet white cat").onLast().assertExists()
        rule.runOnIdle { tree.closePreview() }
        assertEquals("forest", repo.getActiveBranch("ui-tree")!!.id)
        assertTrue(chat.searchExpanded.value)
        assertEquals("cat", chat.searchState.value.query)
    }

    @Test fun thousandBranchesAreViewportCulledAndPinchDoesNotOpenPreview() = runBlocking {
        val routes = (0 until 1000).map { BranchTreeRoute("b$it", it.toLong(), null, null, null, false) }
        val rows = routes.map { route -> BranchTreeRow(route.id, route.id, route.id, 1, false, false,
            "user", MessageKind.CHAT, 1, MessageVersionStatus.COMPLETE, MessageVersionSource.ORIGINAL, "short route", null, true) }
        val model = withContext(Dispatchers.Default) { com.aichat.app.domain.buildBranchTree(BranchTreeSnapshot("c", "test", "b0", 0, routes, rows)) }
        val nodes = model.visible(model.nodes.keys, "b0")
        var taps = 0
        rule.setContent { MaterialTheme {
            BranchTreeCanvas(nodes, nodes.associate { it.id to "Route ${it.branchIds.size}" }, "b0", "root", 0 to "current",
                onNode = { taps++ }, modifier = Modifier.size(280.dp, 360.dp))
        } }
        val matcher = SemanticsMatcher("tree node") { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag].startsWith("branch-node-") }
        rule.waitUntil(5000) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodes(matcher).fetchSemanticsNodes().size < 40)
        rule.onNodeWithTag("branch-tree-canvas").performTouchInput {
            pinch(center - Offset(30f, 0f), center + Offset(30f, 0f), center - Offset(70f, 0f), center + Offset(70f, 0f), 500)
        }
        rule.waitForIdle()
        assertEquals(0, taps)
        assertTrue(rule.onAllNodes(matcher).fetchSemanticsNodes().size < 40)
    }
}
