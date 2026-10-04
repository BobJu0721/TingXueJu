package com.aichat.app.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.aichat.app.*
import com.aichat.app.data.*
import com.aichat.app.network.AiApiClient
import com.aichat.app.ui.theme.iosColorScheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

class DraftReplyUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: AppDatabase
    private lateinit var vm: ChatViewModel
    private lateinit var container: AppContainer
    private lateinit var previousSettings: AppSettings
    private var previousSecrets: Map<String, *> = emptyMap<String, String>()
    private val requests = AtomicInteger()

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(rule.activity, AppDatabase::class.java).build()
        val api = AiApiClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"Suggested reply\"}}]}\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build())
        container = AppContainer(rule.activity, db, api)
        previousSettings = container.settingsRepository.settings.first()
        previousSecrets = rule.activity.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE).all
        container.settingsRepository.save(AppSettings(provider = Provider.CUSTOM, customBaseUrl = "https://ui-draft.invalid/v1", model = "test"))
        container.secretStore.put(Provider.CUSTOM, "test-only-key")
        container.conversationRepository.upsertConversation(ConversationEntity("c", "test", 1, 1))
        vm = ChatViewModel(container)
        vm.selectConversation("c")
        withTimeout(5000) { while (vm.selectedConversation.value == null || vm.settings.value.provider != Provider.CUSTOM) delay(25) }
        Unit
    }

    @After fun cleanup() = runBlocking {
        vm.viewModelScope.cancel()
        container.settingsRepository.save(previousSettings)
        rule.activity.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE).edit().clear().apply {
            previousSecrets.forEach { (key, value) -> putString(key, value as String) }
        }.commit()
        db.close()
    }

    private fun chat() { rule.setContent { MaterialTheme(colorScheme = iosColorScheme(false)) {
        ChatScreen(vm, AppLanguage.TRADITIONAL_CHINESE) {}
    } } }

    @Test fun creationMenuAddsNarrationWithoutCallingApiOrReplacingInput() {
        vm.setInput("original input")
        chat()
        rule.onNodeWithContentDescription("創作").performTouchInput { click() }
        rule.onNodeWithText("旁白", useUnmergedTree = true).performClick()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("three days later")
        rule.onNodeWithText("加入紀錄").performClick()
        rule.waitUntil(5000) { runBlocking { container.conversationRepository.getMessages("c").isNotEmpty() } }
        assertEquals("original input", vm.input.value)
        assertEquals(0, requests.get())
        rule.onNodeWithText("three days later").assertExists()
    }

    @Test fun draftEntryPreviewReplacementCancelAndAcceptAreUsable() {
        vm.setInput("original input")
        chat()
        rule.onNodeWithContentDescription("創作").performTouchInput { click() }
        rule.onNodeWithText("幫我擬回覆").performClick()
        rule.onNodeWithText("目前的輸入草稿（作為參考）").assertIsDisplayed()
        assertEquals(0, requests.get())
        rule.onNodeWithText("這次想表達的意思（選填）").performTextInput("be polite")
        rule.onNodeWithText("產生草稿").performClick()
        rule.waitUntil(5000) { vm.draftReply.value?.generating == false && requests.get() == 1 }
        rule.onNodeWithText("Suggested reply").assertIsDisplayed()
        rule.onNodeWithText("填入輸入框").performClick()
        rule.onNodeWithText("取代輸入草稿？").assertIsDisplayed()
        rule.onNodeWithText("保留原稿").performClick()
        assertEquals("original input", vm.input.value)
        rule.onNodeWithText("填入輸入框").performClick()
        rule.onNodeWithText("取代", substring = false).performClick()
        rule.waitUntil(5000) { vm.draftReply.value == null }
        assertEquals("Suggested reply", vm.input.value)
        assertEquals(1, requests.get())
        runBlocking { assertTrue(container.conversationRepository.getMessages("c").isEmpty()) }
    }

    @Test fun simplifiedDarkLargeTextPartialPreviewKeepsControlsReachable() {
        var closed = false
        val state = DraftReplyState(existingInput = "Original input", content = "Long preview\n".repeat(50), incomplete = true)
        rule.setContent { MaterialTheme(colorScheme = iosColorScheme(true)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                DraftReplyDialog(state, AppLanguage.SIMPLIFIED_CHINESE, {}, {}, {}, { closed = true }, {}, {})
            }
        } }
        rule.onNodeWithText("尚未完成").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("再拟一版").assertIsDisplayed()
        rule.onNodeWithText("填入输入框").assertIsDisplayed()
        rule.onNodeWithText("取消").performTouchInput { click() }
        assertTrue(closed)
    }
}
