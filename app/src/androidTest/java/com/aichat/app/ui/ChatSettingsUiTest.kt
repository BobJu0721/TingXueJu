package com.aichat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.aichat.app.*
import com.aichat.app.data.*
import com.aichat.app.ui.theme.iosColorScheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ChatSettingsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: AppDatabase
    private lateinit var repo: ConversationRepository
    private lateinit var vm: ChatViewModel
    private var returned = 0
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(rule.activity, AppDatabase::class.java).build()
        val container = AppContainer(rule.activity, db)
        repo = container.conversationRepository
        repo.upsertConversation(ConversationEntity("ui", "settings", 1, 1))
        repeat(35) { repo.createInitialMessage("ui", "user", "needle item $it") }
        vm = ChatViewModel(container)
        vm.selectConversation("ui")
        withTimeout(5000) { while (vm.selectedConversation.value == null) delay(20) }
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); db.close() }

    @Test fun replyInvalidValuesDirtyBackAndRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { MaterialTheme(colorScheme = iosColorScheme(false)) {
            ChatReplySettingsScreen(vm, AppLanguage.TRADITIONAL_CHINESE) { returned++ }
        } }
        rule.onNodeWithText("最大輸出 Token（選填）").performTextInput("2147483648")
        rule.onNodeWithText("儲存並返回").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("2147483648").assertExists()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("尚未儲存回覆設定").assertIsDisplayed()
        rule.onNodeWithText("繼續編輯").performClick()
        rule.onNodeWithText("最大輸出 Token（選填）").performTextReplacement("42")
        rule.onNodeWithText("儲存並返回").assertIsEnabled().assertIsDisplayed().performClick()
        rule.waitUntil(10000) { returned == 1 || vm.error.value != null }
        assertNull(vm.error.value?.toString(), vm.error.value)
        assertEquals(1, returned)
        runBlocking { assertEquals(42, repo.getConversation("ui")!!.maxOutputTokens); assertEquals(SceneNote(), repo.getActiveBranch("ui")!!.sceneNoteValue()) }
    }

    @Test fun personaDraftDiscardDoesNotApplyAndSaveIsAtomic() = runBlocking {
        db.chatDao().upsertProfile(ProfileEntity("p", ProfileType.PERSONA, "Traveller", summary = "A guest", createdAt = 1, updatedAt = 1))
        rule.setContent { MaterialTheme(colorScheme = iosColorScheme(false)) {
            ChatStoryScreen(vm, AppLanguage.TRADITIONAL_CHINESE) { returned++ }
        } }
        rule.onNodeWithText("Traveller").performScrollTo().performClick()
        assertNull(repo.getConversation("ui")!!.personaId)
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("捨棄變更").performClick()
        assertNull(repo.getConversation("ui")!!.personaId)
        assertEquals(1, returned)
    }

    @Test fun menuHasSixActionsAndSearchSurvivesChildReturn() {
        var child by mutableStateOf(false)
        rule.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme(colorScheme = iosColorScheme(false)) {
                if (child) holder.SaveableStateProvider("reply") {
                    ChatReplySettingsScreen(vm, AppLanguage.TRADITIONAL_CHINESE) { child = false }
                } else holder.SaveableStateProvider("chat") {
                    ChatScreen(vm, AppLanguage.TRADITIONAL_CHINESE) { returned++ }
                }
            }
        }
        rule.onNodeWithContentDescription("更多對話功能").performClick()
        listOf("搜尋對話", "劇情設定", "對話分支", "對話記憶", "回覆設定", "外觀設定").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithContentDescription("對話資訊").assertDoesNotExist()
        rule.onNodeWithText("搜尋對話").performClick()
        rule.onNodeWithText("搜尋目前分支的訊息…").performTextInput("item 3")
        rule.waitUntil(5000) { vm.searchState.value.hits.size == 6 }
        rule.onNodeWithText("結果").performClick()
        rule.onAllNodesWithText("needle item 30").filterToOne(hasAnyAncestor(isPopup())).performClick()
        assertTrue(vm.searchExpanded.value)
        rule.onNode(isPopup()).assertDoesNotExist()
        rule.onNodeWithContentDescription("下一則").performClick()
        rule.runOnIdle { vm.setInput("unsent draft"); child = true }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("item 3").assertExists()
        assertEquals("unsent draft", vm.input.value)
        assertTrue(vm.searchExpanded.value)
        rule.onNodeWithContentDescription("關閉搜尋").performClick()
        assertTrue(vm.searchState.value.hits.isEmpty())
        assertFalse(vm.searchExpanded.value)
    }

    @Test fun simplifiedDarkLargeFontMemoryAndAppearanceAreIndependent() {
        var appearance by mutableStateOf(false)
        rule.setContent { MaterialTheme(colorScheme = iosColorScheme(true)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
            if (appearance) ChatAppearanceScreen(vm, AppLanguage.SIMPLIFIED_CHINESE) { returned++ }
            else ChatMemoryScreen(vm, AppLanguage.SIMPLIFIED_CHINESE) { returned++ }
            }
        } }
        rule.onNodeWithText("当前摘要").assertExists()
        rule.onNodeWithText("保存并返回").assertDoesNotExist()
        rule.runOnIdle { appearance = true }
        rule.onNodeWithText("更改会自动保存。").assertExists()
        rule.onNodeWithText("最大输出 Token（选填）").assertDoesNotExist()
        rule.onNodeWithText("保存并返回").assertDoesNotExist()
    }

    @Test fun readingAnchorAndInputSurviveChildPageWithoutJumpingToEnd() {
        var child by mutableStateOf(false)
        rule.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme(colorScheme = iosColorScheme(false)) {
                if (child) holder.SaveableStateProvider("appearance") {
                    ChatAppearanceScreen(vm, AppLanguage.TRADITIONAL_CHINESE) { child = false }
                } else holder.SaveableStateProvider("chat") { ChatScreen(vm, AppLanguage.TRADITIONAL_CHINESE) { returned++ } }
            }
        }
        rule.waitUntil(5000) {
            rule.onAllNodesWithTag("chat-message-list").fetchSemanticsNodes().firstOrNull()
                ?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.value()?.let { it > 0f } == true
        }
        rule.onNodeWithTag("chat-message-list").performScrollToNode(hasText("needle item 8"))
        rule.waitForIdle()
        val before = rule.onNodeWithTag("chat-message-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        rule.runOnIdle { vm.setInput("draft survives"); child = true }
        rule.onNodeWithText("變更會自動儲存。").assertExists()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()
        val after = rule.onNodeWithTag("chat-message-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, after, 0.02f)
        assertEquals("draft survives", vm.input.value)
    }
}
