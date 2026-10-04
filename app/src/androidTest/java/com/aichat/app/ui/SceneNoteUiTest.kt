package com.aichat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.aichat.app.AppContainer
import com.aichat.app.ChatViewModel
import com.aichat.app.data.*
import com.aichat.app.ui.theme.iosColorScheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class SceneNoteUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: AppDatabase
    private lateinit var vm: ChatViewModel
    private lateinit var repo: ConversationRepository
    private var returned = 0

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(rule.activity, AppDatabase::class.java).build()
        val container = AppContainer(rule.activity, db)
        repo = container.conversationRepository
        repo.upsertConversation(ConversationEntity("ui", "scene", 1, 1))
        vm = ChatViewModel(container)
        vm.selectConversation("ui")
        withTimeout(5000) { while (vm.selectedConversation.value == null) delay(25) }
        vm.openChatInfo()
        withTimeout(5000) { while (vm.chatInfoBranch.value == null) delay(25) }
    }

    @After fun cleanup() { vm.viewModelScope.cancel(); db.close() }

    @Test fun traditionalLightUnsavedBackOffersContinueAndDiscard() {
        rule.setContent { MaterialTheme(colorScheme = iosColorScheme(false)) {
            ChatInfoScreen(vm, AppLanguage.TRADITIONAL_CHINESE) { returned++ }
        } }
        rule.onNodeWithText("劇情提示內容").performScrollTo().performTextInput("雨夜客棧\nDo not reveal.")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("本場劇情提示尚未儲存。").assertIsDisplayed()
        rule.onNodeWithText("繼續編輯").performClick()
        rule.onNodeWithText("雨夜客棧\nDo not reveal.").assertExists()
        assertEquals(0, returned)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("捨棄變更").performClick()
        assertEquals(1, returned)
        runBlocking { assertEquals(SceneNote(), repo.getActiveBranch("ui")!!.sceneNoteValue()) }
    }

    @Test fun simplifiedDarkLargeFontLongTextCanBeSavedAndRestored() {
        val note = (1..35).joinToString("\n") { "場景 $it Scene direction" }
        rule.setContent { MaterialTheme(colorScheme = iosColorScheme(true)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                ChatInfoScreen(vm, AppLanguage.SIMPLIFIED_CHINESE) { returned++ }
            }
        } }
        rule.onNodeWithContentDescription("启用本场剧情提示").performScrollTo().performClick()
        rule.onNodeWithText("剧情提示内容").performScrollTo().performClick().performTextInput(note)
        rule.onNodeWithText("保存并返回").assertIsDisplayed()
        rule.onNodeWithText("保存并返回").performClick()
        rule.waitUntil(5000) { returned == 1 }
        runBlocking {
            assertEquals(SceneNote(note, true), repo.getActiveBranch("ui")!!.sceneNoteValue())
            vm.loadChatInfo("ui")
        }
        rule.waitUntil(5000) { vm.chatInfoBranch.value?.sceneNote == note }
        rule.onNodeWithText(note).assertExists()
    }
}
