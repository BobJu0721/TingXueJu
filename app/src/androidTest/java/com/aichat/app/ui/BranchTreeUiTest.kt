package com.aichat.app.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.flow.first
import com.aichat.app.network.AiApiClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    private fun show(manualFit: Boolean = false, fixedWidth: Boolean = false) {
        rule.setContent { MaterialTheme(colorScheme = iosColorScheme(false)) {
            Box(if (fixedWidth) Modifier.requiredWidth(360.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                if (showingTree) ChatBranchScreen(tree, chat, AppLanguage.TRADITIONAL_CHINESE) { returned++; showingTree = false }
                else ChatScreen(chat, AppLanguage.TRADITIONAL_CHINESE) { returned++ }
            }
        } }
        rule.waitUntil(10000) { tree.state.value.tree != null }
        if (manualFit) rule.onNodeWithContentDescription("適合畫面").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithTag("branch-tree-list").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }
    @Composable private fun GraphHarness() {
        val state by tree.state.collectAsState()
        val expanded by tree.expanded.collectAsState()
        var command by rememberSaveable { mutableIntStateOf(0) }
        var action by rememberSaveable { mutableStateOf("current") }
        val nodes = state.tree?.visible(expanded, "forest").orEmpty()
        Column {
            BranchTreeCanvas(nodes, nodes.associate { it.id to "Route\nSummary\nSaved" }, "forest",
                state.tree?.currentFocus("forest") ?: "root", command to action, {}, Modifier.fillMaxWidth().weight(1f))
            Row {
                listOf("out" to "縮小", "in" to "放大", "fit" to "適合畫面", "current" to "定位目前路線").forEach { (a, label) ->
                    TextButton({ action = a; command++ }, Modifier.semantics { contentDescription = label }) { Text(label) }
                }
            }
        }
    }
    // Map all four corners without ancestor clipping; boundsInRoot alone can hide overflow.
    private fun fullBounds(tag: String): Rect {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        return rule.runOnIdle {
            val c = node.layoutInfo.coordinates
            val points = listOf(Offset.Zero, Offset(c.size.width.toFloat(), 0f),
                Offset(0f, c.size.height.toFloat()), Offset(c.size.width.toFloat(), c.size.height.toFloat())).map(c::localToRoot)
            Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        }
    }
    private fun graphBounds(ids: Collection<String>) = ids.associateWith { fullBounds("branch-node-$it") }
    private fun assertSameBounds(expected: Map<String, Rect>, actual: Map<String, Rect>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (id, a) ->
            val b = actual.getValue(id)
            assertEquals("$id left", a.left, b.left, 2f); assertEquals("$id top", a.top, b.top, 2f)
            assertEquals("$id right", a.right, b.right, 2f); assertEquals("$id bottom", a.bottom, b.bottom, 2f)
        }
    }
    private fun evidence(name: String, canvas: Rect, bounds: Map<String, Rect>, extra: String = "") {
        val text = "canvas=$canvas\n$extra\n" + bounds.entries.joinToString("\n") { "${it.key}=${it.value}" }
        println("$name\n$text")
        val dir = File(rule.activity.getExternalFilesDir(null), "branch-tree-qa").apply { mkdirs() }
        File(dir, "$name.txt").writeText(text)
    }
    private fun screenshot(name: String) {
        val dir = File(rule.activity.getExternalFilesDir(null), "branch-tree-qa").apply { mkdirs() }
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun initial360DpFitsEveryVisibleRouteWithoutToolbarAndMatchesManualFit() {
        tree.open("ui-tree")
        rule.setContent { MaterialTheme { Box(Modifier.requiredWidth(360.dp).fillMaxHeight()) { GraphHarness() } } }
        rule.waitUntil(10000) { tree.state.value.tree != null }
        val model = tree.state.value.tree!!
        val expected = model.visible(tree.expanded.value, "forest").map { it.id }
        rule.waitUntil(10000) { expected.all { rule.onAllNodesWithTag("branch-node-$it").fetchSemanticsNodes().isNotEmpty() } }
        rule.waitForIdle()
        val canvas = fullBounds("branch-tree-canvas")
        val density = rule.onNodeWithTag("branch-tree-canvas").fetchSemanticsNode().layoutInfo.density.density
        assertEquals(360f * density, canvas.width, 1f)
        val initial = graphBounds(expected)
        initial.forEach { (id, b) ->
            assertTrue("$id left clipped: $b in $canvas", b.left >= canvas.left - 1f)
            assertTrue("$id right clipped: $b in $canvas", b.right <= canvas.right + 1f)
            assertTrue("$id top clipped: $b in $canvas", b.top >= canvas.top - 1f)
            assertTrue("$id bottom clipped: $b in $canvas", b.bottom <= canvas.bottom + 1f)
        }
        val initialScale = initial.getValue("root").width / (148f * density)
        assertTrue("Must exercise fit below old minimum: $initialScale", initialScale in 0.4f..0.599f)
        evidence("08-initial-360dp", canvas, initial, "density=$density scale=$initialScale nodes=${expected.size}")
        screenshot("08-initial-360dp")
        rule.onNodeWithContentDescription("適合畫面").performClick()
        rule.waitForIdle()
        assertSameBounds(initial, graphBounds(expected))

        rule.onNodeWithTag("branch-tree-canvas").performTouchInput { swipe(center, center + Offset(45f, 60f), 500) }
        rule.waitForIdle()
        assertEquals(initial.getValue("root").width, fullBounds("branch-node-root").width, 1f)
        assertNull(tree.preview.value)
        rule.onNodeWithContentDescription("放大").performClick()
        rule.waitForIdle()
        assertEquals(initialScale + 0.2f, fullBounds("branch-node-root").width / (148f * density), 0.01f)
        repeat(4) { rule.onNodeWithContentDescription("縮小").performClick() }
        rule.waitForIdle()
        assertEquals(0.4f, fullBounds("branch-node-root").width / (148f * density), 0.01f)
        rule.onNodeWithContentDescription("定位目前路線").performClick()
        rule.waitForIdle()
        val focus = fullBounds("branch-node-${model.currentFocus("forest")}")
        assertEquals(canvas.center.x, focus.center.x, 2f); assertEquals(canvas.center.y, focus.center.y, 2f)
        val beforePinch = fullBounds("branch-node-${model.currentFocus("forest")}").width
        rule.onNodeWithTag("branch-tree-canvas").performTouchInput {
            pinch(start0 = center - Offset(80f, 0f), end0 = center - Offset(25f, 0f),
                start1 = center + Offset(80f, 0f), end1 = center + Offset(25f, 0f), durationMillis = 500)
        }
        rule.waitForIdle()
        assertEquals(beforePinch, fullBounds("branch-node-${model.currentFocus("forest")}").width, 1f)
        rule.onNodeWithTag("branch-tree-canvas").performTouchInput {
            pinch(start0 = center - Offset(25f, 0f), end0 = center - Offset(100f, 0f),
                start1 = center + Offset(25f, 0f), end1 = center + Offset(100f, 0f), durationMillis = 500)
        }
        rule.waitForIdle()
        val zoomed = fullBounds("branch-node-${model.currentFocus("forest")}").width / (148f * density)
        assertTrue("Pinch must zoom in: $zoomed", zoomed > 0.4f && zoomed <= 1.61f)
        rule.onNodeWithContentDescription("定位目前路線").performClick()
        repeat(8) { rule.onNodeWithContentDescription("放大").performClick() }
        rule.waitForIdle()
        assertEquals(1.6f, fullBounds("branch-node-${model.currentFocus("forest")}").width / (148f * density), 0.01f)
        assertNull(tree.preview.value)
    }

    @Test fun fiveLeaf360DpTreeReportsMinimumScaleClippingAndRefreshKeepsViewport() {
        val leaves = (0 until 5).map { "leaf$it" }
        val nodes = listOf(com.aichat.app.domain.BranchTreeNode("root", null, leaves, emptyList(), leaves, emptyList())) +
            leaves.map { com.aichat.app.domain.BranchTreeNode(it, "root", emptyList(), emptyList(), listOf(it), listOf(it)) }
        var labels by mutableStateOf(nodes.associate { it.id to "Route\nSummary\nSaved" })
        var command by mutableStateOf(0 to "current")
        var taps = 0
        rule.setContent { MaterialTheme {
            BranchTreeCanvas(nodes, labels, "leaf2", "root", command, { taps++ }, Modifier.size(360.dp, 500.dp))
        } }
        val ids = nodes.map { it.id }
        rule.waitUntil(10000) { ids.all { rule.onAllNodesWithTag("branch-node-$it").fetchSemanticsNodes().isNotEmpty() } }
        rule.waitForIdle()
        val canvas = fullBounds("branch-tree-canvas")
        val density = rule.onNodeWithTag("branch-tree-canvas").fetchSemanticsNode().layoutInfo.density.density
        val initial = graphBounds(ids)
        assertEquals(360f * density, canvas.width, 1f)
        assertEquals(0.4f, initial.getValue("root").width / (148f * density), 0.01f)
        assertTrue(initial.getValue("leaf0").left < canvas.left)
        assertTrue(initial.getValue("leaf4").right > canvas.right)
        evidence("09-wide-360dp", canvas, initial, "density=$density layoutWidthDp=1044 scaledLayoutWidthDp=417.6 scale=0.4")
        screenshot("09-wide-360dp")
        rule.onNodeWithTag("branch-tree-canvas").performTouchInput { swipe(center, center + Offset(40f, 55f), 500) }
        rule.waitForIdle()
        val moved = graphBounds(ids)
        assertEquals(initial.getValue("root").width, moved.getValue("root").width, 1f)
        assertTrue(kotlin.math.abs(moved.getValue("root").left - initial.getValue("root").left) > 5f)
        rule.runOnIdle { labels = labels.mapValues { it.value.replace("Summary", "New summary that wraps across several lines") } }
        rule.waitForIdle()
        assertSameBounds(moved, graphBounds(ids))
        rule.runOnIdle { command = 1 to "fit" }
        rule.waitForIdle()
        assertSameBounds(initial, graphBounds(ids))
        assertEquals(0, taps)
    }

    @Test fun graphPreviewAndExactSwitchPreserveOtherRoutes() = runBlocking {
        show(fixedWidth = true)
        screenshot("01-branches")
        rule.onNodeWithText("目前", substring = false).assertExists()
        val collapsed = rule.onNodeWithText("Go to sea", substring = true).fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { click(collapsed.center) }
        rule.waitUntil(5000) { rule.onAllNodesWithText("路線 2", substring = true).fetchSemanticsNodes().isNotEmpty() }
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
                GraphHarness()
            }
        } }
        rule.runOnIdle { tree.open("ui-tree") }
        rule.waitUntil(10000) { tree.state.value.tree != null }
        rule.onNodeWithContentDescription("適合畫面").performClick()
        rule.onNodeWithTag("branch-tree-canvas").performTouchInput { swipe(center, center + Offset(50f, 60f), 500) }
        assertNull(tree.preview.value)
        rule.onNodeWithContentDescription("放大").performClick()
        rule.waitForIdle()
        val before = rule.onNodeWithTag("branch-node-root").fetchSemanticsNode().boundsInRoot
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        val after = rule.onNodeWithTag("branch-node-root").fetchSemanticsNode().boundsInRoot
        assertEquals(before.left, after.left, 2f); assertEquals(before.top, after.top, 2f)
        rule.onNodeWithContentDescription("定位目前路線").performClick()
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

    @Test fun sharedSectionOpensRouteChooserAndPageHasNoGraphControls() = runBlocking {
        show(fixedWidth = true)
        rule.onNodeWithTag("branch-tree-canvas").assertDoesNotExist()
        listOf("適合畫面", "放大", "縮小", "定位目前路線").forEach {
            rule.onNodeWithContentDescription(it).assertDoesNotExist()
        }
        rule.onNodeWithTag("branch-tree-list").performScrollToNode(hasTestTag("branch-list-row-root"))
        rule.onNodeWithTag("branch-list-row-root").performClick()
        rule.onNodeWithText("選擇路線").assertIsDisplayed()
        assertNull(tree.preview.value)
        assertEquals("forest", repo.getActiveBranch("ui-tree")!!.id)
        rule.onAllNodesWithText("預覽").onLast().performClick()
        rule.waitUntil(5000) { tree.preview.value?.page != null }
        assertEquals("white", tree.preview.value?.branchId)
        assertEquals("forest", repo.getActiveBranch("ui-tree")!!.id)
        screenshot("10-list-route-preview-360dp")
        rule.runOnIdle { tree.closePreview() }
        rule.onNodeWithTag("branch-list-row-root").assertIsDisplayed()
        assertEquals("unsent input", chat.input.value)
    }

    @Test fun darkLargeTextLongTitleKeepsListControlsWithin360Dp() = runBlocking {
        repo.updateConversation(repo.getConversation("ui-tree")!!.copy(title = "森林與海邊白貓的漫長故事聊天室".repeat(10)))
        rule.setContent { MaterialTheme(colorScheme = iosColorScheme(true)) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) {
                Box(Modifier.requiredWidth(360.dp).fillMaxHeight()) {
                    ChatBranchScreen(tree, chat, AppLanguage.SIMPLIFIED_CHINESE) {}
                }
            }
        } }
        rule.waitUntil(10000) { tree.state.value.tree != null && rule.onAllNodesWithText("Go to sea", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val viewport = fullBounds("branch-tree-list")
        val density = rule.onNodeWithTag("branch-tree-list").fetchSemanticsNode().layoutInfo.density.density
        assertEquals(360f * density, viewport.width, 1f)
        tree.state.value.tree!!.nodes.keys.forEach { id ->
            if (rule.onAllNodesWithTag("branch-list-row-$id").fetchSemanticsNodes().isNotEmpty()) {
                val row = fullBounds("branch-list-row-$id")
                assertTrue(row.left >= viewport.left - 1 && row.right <= viewport.right + 1)
            }
        }
        screenshot("11-list-dark-long-title-130percent")
    }

    @Test fun singleRouteAndEmptyConversationDoNotRenderTree() = runBlocking {
        repo.upsertConversation(ConversationEntity("single", "Only route", 1, 1))
        repo.createInitialMessage("single", "user", "hello")
        repo.upsertConversation(ConversationEntity("empty", "Empty chat", 1, 1))
        chat.selectConversation("single")
        rule.setContent { MaterialTheme { ChatBranchScreen(tree, chat, AppLanguage.TRADITIONAL_CHINESE) {} } }
        rule.waitUntil(10000) { tree.state.value.snapshot?.conversationId == "single" }
        rule.onNodeWithText("編輯訊息、重新生成或續寫就會產生分支").assertIsDisplayed()
        rule.onNodeWithTag("branch-tree-list").assertDoesNotExist()
        rule.runOnIdle { chat.selectConversation("empty") }
        rule.waitUntil(10000) { tree.state.value.snapshot?.conversationId == "empty" }
        rule.onNodeWithText("開始對話後，這裡會顯示故事路線").assertIsDisplayed()
        rule.onNodeWithTag("branch-tree-list").assertDoesNotExist()
    }

    @Test fun generationBusyAllowsBrowsingButDisablesSwitchWithoutRealApi() = runBlocking {
        val gate = CountDownLatch(1)
        val calls = AtomicInteger()
        val api = AiApiClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            gate.await(20, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"draft\"}}]}\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build())
        val container = AppContainer(rule.activity, db, api)
        val oldSettings = container.settingsRepository.settings.first()
        val secrets = rule.activity.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE)
        val oldSecrets = secrets.all
        try {
            container.settingsRepository.save(AppSettings(provider = Provider.CUSTOM, customBaseUrl = "https://branch-list.invalid/v1", model = "fake"))
            container.secretStore.put(Provider.CUSTOM, "test-only-key")
            chat.viewModelScope.cancel(); tree.viewModelScope.cancel()
            chat = ChatViewModel(container); tree = BranchTreeViewModel(container)
            chat.selectConversation("ui-tree")
            withTimeout(5000) { while (chat.selectedConversation.value == null || chat.settings.value.provider != Provider.CUSTOM) delay(25) }
            chat.openDraftReply(); chat.generateDraftReply()
            withTimeout(5000) { while (calls.get() == 0 || chat.draftReply.value?.generating != true) delay(25) }
            show(fixedWidth = true)
            val sea = tree.state.value.tree!!.nodes.values.first { it.rows.any { row -> row.snippet == "Go to sea" } }
            rule.onNodeWithTag("branch-list-row-${sea.id}").performClick()
            rule.waitForIdle()
            assertNull(tree.preview.value)
            rule.onNodeWithText("路線 2", substring = true).performClick()
            rule.waitUntil(5000) { tree.preview.value?.page != null }
            rule.onNodeWithText("切換到這條路線").assertIsNotEnabled()
            assertEquals("forest", repo.getActiveBranch("ui-tree")!!.id)
        } finally {
            gate.countDown(); chat.closeDraftReply()
            container.settingsRepository.save(oldSettings)
            secrets.edit().clear().apply { oldSecrets.forEach { (key, value) -> putString(key, value as String) } }.commit()
        }
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
            pinch(start0 = center - Offset(30f, 0f), end0 = center - Offset(70f, 0f),
                start1 = center + Offset(30f, 0f), end1 = center + Offset(70f, 0f), durationMillis = 500)
        }
        rule.waitForIdle()
        assertEquals(0, taps)
        assertTrue(rule.onAllNodes(matcher).fetchSemanticsNodes().size < 40)
    }
}
