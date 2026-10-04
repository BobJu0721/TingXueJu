package com.aichat.app

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.app.data.AppDatabase
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.ConversationRepository
import com.aichat.app.data.Provider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 版本箭頭與操作入口必須經過 [ChatViewModel]。
 *
 * 直接呼叫 `ConversationRepository.selectVersion()` 通過，不代表使用者按下的箭頭有作用：
 * 畫面與按鈕過去讀的是 `messages` 的全域 legacy 欄位，和目前路線實際選定的版本是兩層資料，
 * 切回路線時全域欄位不會跟著更新，ViewModel 就會在呼叫 repository 之前提前 return。
 *
 * 因此這裡刻意**不**同步 legacy 欄位，讓真正的錯誤有機會出現。
 *
 * 「有沒有開始準備生成」用一個穩定的訊號觀察：把目前端點設成 HTTP，生成前一定會先跳出
 * 不安全連線確認（[ChatViewModel.showUnsafeHttpWarning]）。這樣不需要網路、不需要 API Key，
 * 也不需要碰 Android Keystore。
 */
@RunWith(AndroidJUnit4::class)
class ChatViewModelVersionSwitchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var container: AppContainer
    private lateinit var repository: ConversationRepository
    private lateinit var viewModel: ChatViewModel

    @Before fun before() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        container = AppContainer(context, db)
        repository = container.conversationRepository
        viewModel = ChatViewModel(container)
    }

    @After fun after() = runBlocking {
        viewModel.viewModelScope.cancel()
        // 還原成預設設定，避免留下測試用的端點。
        container.settingsRepository.save(AppSettings())
        db.close()
    }

    // ---- T2：箭頭來回切換 ----

    @Test fun arrowSwitchingThroughViewModelGoesBackAndForth() = runBlocking {
        val id = "t2"
        seedForestRoute(id)
        viewModel.selectConversation(id)
        val first = repository.getMessages(id).first()
        val originalBranch = repository.getActiveBranch(id)!!.id

        viewModel.requestEditMessage(first.id, "去海邊")
        awaitUntil { branchOf(id) != originalBranch }
        val editedBranch = branchOf(id)
        val versions = get { repository.getMessageVersions(first.id) }
        assertEquals(2, versions.size)
        val v1 = versions[0].id
        val v2 = versions[1].id

        // 切回第一版。這裡不先手動同步 legacy 欄位，錯誤才會暴露。
        viewModel.requestSelectVersion(first.id, v1)
        awaitUntil { branchOf(id) == originalBranch }
        assertEquals(FOREST, contents(id))

        viewModel.requestSelectVersion(first.id, v2)
        awaitUntil { branchOf(id) == editedBranch }
        assertEquals(listOf("去海邊"), contents(id))

        // 反覆來回，每一次都要一致。
        repeat(2) {
            viewModel.requestSelectVersion(first.id, v1)
            awaitUntil { branchOf(id) == originalBranch }
            assertEquals(FOREST, contents(id))
            viewModel.requestSelectVersion(first.id, v2)
            awaitUntil { branchOf(id) == editedBranch }
            assertEquals(listOf("去海邊"), contents(id))
        }
    }

    // ---- T5a：內容碰巧等於另一條路線時，仍必須接受編輯 ----

    @Test fun editIsNotIgnoredWhenTextMatchesAnotherRoute() = runBlocking {
        val id = "t5a"
        seedForestRoute(id)
        viewModel.selectConversation(id)
        val first = repository.getMessages(id).first()
        val originalBranch = repository.getActiveBranch(id)!!.id

        viewModel.requestEditMessage(first.id, "去海邊")
        awaitUntil { branchOf(id) != originalBranch }
        // 切回路線一：目前路線的正文是「去森林」，legacy 欄位仍停在「去海邊」。
        val v1 = get { repository.getMessageVersions(first.id) }.first().id
        viewModel.requestSelectVersion(first.id, v1)
        awaitUntil { branchOf(id) == originalBranch }
        assertEquals("去森林", get { repository.getRouteMessage(id, first.id) }!!.content)
        assertEquals("去海邊", get { repository.getMessage(first.id) }!!.content)

        // 把目前路線改成和另一條路線相同的文字：不能因為 legacy 相同就被忽略。
        viewModel.requestEditMessage(first.id, "去海邊")
        awaitUntil { get { repository.getMessageVersions(first.id) }.size == 3 }
        assertEquals(listOf("去海邊"), contents(id))
    }

    // ---- T5b：不在目前路線的訊息不得被操作 ----

    @Test fun messageOutsideCurrentRouteCannotBeOperatedOn() = runBlocking {
        val id = "t5b"
        seedForestRoute(id)
        viewModel.selectConversation(id)
        val first = repository.getMessages(id).first()
        val originalBranch = repository.getActiveBranch(id)!!.id
        val outsideRoute = repository.getMessages(id)[3]

        viewModel.requestEditMessage(first.id, "去海邊")
        awaitUntil { branchOf(id) != originalBranch }
        assertNull("第四則已不在目前路線", get { repository.getRouteMessage(id, outsideRoute.id) })

        // 對不在目前路線的訊息要求刪除：不得進入確認流程。
        viewModel.requestDeleteMessage(outsideRoute.id)
        Thread.sleep(300)
        assertNull("不在目前路線的訊息不應進入刪除確認", viewModel.pendingMutation.value)
        assertNotNull("該訊息本身仍在資料庫", get { repository.getMessage(outsideRoute.id) })

        // 正對照：目前路線上的訊息要求刪除時，確認流程必須出現。
        val inRoute = get { repository.getMessages(id) }.first()
        viewModel.requestDeleteMessage(inRoute.id)
        awaitUntil { viewModel.pendingMutation.value != null }
        viewModel.dismissPendingMutation()
    }

    // ---- T5c：排除狀態只依目前路線 ----

    @Test fun excludedStateIsReadFromTheCurrentRoute() = runBlocking {
        val id = "t5c"
        seedForestRoute(id)
        useUnsafeHttpEndpoint()
        viewModel.selectConversation(id)
        val messages = repository.getMessages(id)
        val excluded = messages[0]
        val allowed = messages[2]

        // 正對照：未排除的訊息會走到「準備生成」，因此跳出不安全連線確認。
        viewModel.requestAnswerFrom(allowed.id)
        awaitUntil { viewModel.showUnsafeHttpWarning.value }
        viewModel.dismissUnsafeHttp()

        // 在原路線排除第一則訊息；legacy 欄位不會被更新。
        viewModel.toggleMessageExcluded(excluded.id)
        awaitUntil { get { repository.getRouteMessage(id, excluded.id) }?.excluded == true }
        assertFalse("legacy 欄位刻意保持未排除", get { repository.getMessage(excluded.id) }!!.excluded)

        // 生成入口必須依目前路線拒絕：不應該走到準備生成。
        viewModel.requestAnswerFrom(excluded.id)
        Thread.sleep(400)
        assertFalse(
            "已排除的訊息不得進入生成流程",
            viewModel.showUnsafeHttpWarning.value,
        )
    }

    // ---- 輔助 ----

    private fun branchOf(id: String): String? = get { repository.getActiveBranch(id)?.id }

    private fun contents(id: String): List<String> = get { repository.getMessages(id).map { it.content } }

    private suspend fun useUnsafeHttpEndpoint() {
        container.settingsRepository.save(
            AppSettings(provider = Provider.CUSTOM, customBaseUrl = "http://127.0.0.1:9/v1"),
        )
        awaitUntil { viewModel.settings.value.usesUnsafeHttp }
    }

    /** 在同一個測試執行緒上讀取資料庫狀態。 */
    private fun <T> get(block: suspend () -> T): T = runBlocking { block() }

    private fun awaitUntil(timeoutMillis: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within ${timeoutMillis}ms")
    }

    private suspend fun seedForestRoute(id: String) {
        repository.upsertConversation(ConversationEntity(id, "chat", 1, 1))
        repository.createInitialMessage(id, "user", "去森林", 10)
        repository.createInitialMessage(id, "assistant", "遇到黑貓", 20)
        repository.createInitialMessage(id, "user", "跟著牠", 30)
        repository.createInitialMessage(id, "assistant", "抵達木屋", 40)
    }

    private companion object {
        val FOREST = listOf("去森林", "遇到黑貓", "跟著牠", "抵達木屋")
    }
}
