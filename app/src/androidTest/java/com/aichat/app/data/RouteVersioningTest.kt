package com.aichat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.app.domain.ChatGenerationKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 版本與後續對話的完整保存。
 *
 * 每一條路線都有自己的後續對話；編輯、重發、生成另一版、續寫與切換版本都只會**新增**路線，
 * 任何未明確刪除的輸入、回答與後續都必須能回到對應版本查看。
 */
@RunWith(AndroidJUnit4::class)
class RouteVersioningTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: ConversationRepository

    @Before fun before() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = ConversationRepository(db.chatDao())
    }

    @After fun after() = db.close()

    // ---- 驗收範例：改第一句之後，原本四則紀錄都必須恢復 ----

    @Test fun editingFirstMessageKeepsForestRouteAndSwitchingBackRestoresIt() = runBlocking {
        seedForestRoute()
        val first = repository.getMessages("c").first()
        val forestBranch = repository.getActiveBranch("c")!!.id

        val applied = repository.addEditedVersion("c", first.id, "去海邊", revision())
        assertTrue(applied)
        val beachBranch = repository.getActiveBranch("c")!!.id
        assertNotEquals(forestBranch, beachBranch)

        // 新路線只有編輯後的訊息，不帶走原本的後續。
        assertEquals(listOf("去海邊"), repository.getBranchContents(beachBranch))
        // 原路線四則紀錄完整保留。
        assertEquals(listOf("去森林", "遇到黑貓", "跟著牠", "抵達木屋"), repository.getBranchContents(forestBranch))

        // 切回第一版：森林、黑貓、跟隨與木屋都要回來。
        val versions = repository.getMessageVersions(first.id)
        assertEquals(2, versions.size)
        assertTrue(repository.selectVersion("c", first.id, versions.first().id, revision()))
        assertEquals(forestBranch, repository.getActiveBranch("c")!!.id)
        assertEquals(listOf("去森林", "遇到黑貓", "跟著牠", "抵達木屋"), repository.getMessages("c").map { it.content })

        // 再切到第二版，回到海邊路線。
        assertTrue(repository.selectVersion("c", first.id, versions.last().id, revision()))
        assertEquals(beachBranch, repository.getActiveBranch("c")!!.id)
        assertEquals(listOf("去海邊"), repository.getMessages("c").map { it.content })
    }

    // ---- 生成另一版／人工編輯：每個版本都保留自己的後續 ----

    @Test fun regeneratedAnswerKeepsItsOwnFollowUpsAndTheOriginalOnes() = runBlocking {
        seedForestRoute()
        val answer = repository.getMessages("c")[1]
        val forestBranch = repository.getActiveBranch("c")!!.id

        assertTrue(repository.addEditedVersion("c", answer.id, "遇到白貓", revision()))
        val whiteBranch = repository.getActiveBranch("c")!!.id
        assertEquals(listOf("去森林", "遇到白貓"), repository.getBranchContents(whiteBranch))
        assertEquals(listOf("去森林", "遇到黑貓", "跟著牠", "抵達木屋"), repository.getBranchContents(forestBranch))

        val versions = repository.getMessageVersions(answer.id)
        assertEquals(2, versions.size)
        assertTrue(repository.selectVersion("c", answer.id, versions.first().id, revision()))
        assertEquals(listOf("去森林", "遇到黑貓", "跟著牠", "抵達木屋"), repository.getMessages("c").map { it.content })
    }

    // ---- 使用者訊息自己也保留多版本 ----

    @Test fun userMessageVersionsEachKeepTheirOwnRoute() = runBlocking {
        seedForestRoute()
        val first = repository.getMessages("c").first()
        val originalBranch = repository.getActiveBranch("c")!!.id

        repository.addEditedVersion("c", first.id, "去海邊", revision())
        val beachBranch = repository.getActiveBranch("c")!!.id
        assertTrue(repository.selectVersion("c", first.id, repository.getMessageVersions(first.id).first().id, revision()))
        assertEquals(originalBranch, repository.getActiveBranch("c")!!.id)

        // 在舊路線繼續聊天，只會追加到這條路線。
        repository.createInitialMessage("c", "user", "在森林過夜")
        assertEquals(listOf("去森林", "遇到黑貓", "跟著牠", "抵達木屋", "在森林過夜"), repository.getBranchContents(originalBranch))
        assertEquals(listOf("去海邊"), repository.getBranchContents(beachBranch))
    }

    // ---- 多層分岔：上層與下層反覆切換不得混線 ----

    @Test fun multiLevelForksSwitchBackAndForthWithoutLosingRecords() = runBlocking {
        seedForestRoute()
        val messages = repository.getMessages("c")
        val firstMessage = messages[0]
        val answer = messages[1]

        repository.addEditedVersion("c", firstMessage.id, "去海邊", revision())
        val beachBranch = repository.getActiveBranch("c")!!.id
        repository.createInitialMessage("c", "assistant", "看到海豚")
        assertEquals(listOf("去海邊", "看到海豚"), repository.getBranchContents(beachBranch))

        // 在海邊路線上再對第一句分岔一次。
        repository.addEditedVersion("c", firstMessage.id, "去山上", revision())
        val mountainBranch = repository.getActiveBranch("c")!!.id
        assertEquals(listOf("去山上"), repository.getBranchContents(mountainBranch))

        val firstVersions = repository.getMessageVersions(firstMessage.id)
        assertEquals(3, firstVersions.size)

        // 上層切回第一版，再對回答分岔，兩層都要記住自己的選擇。
        assertTrue(repository.selectVersion("c", firstMessage.id, firstVersions[0].id, revision()))
        assertEquals(listOf("去森林", "遇到黑貓", "跟著牠", "抵達木屋"), repository.getMessages("c").map { it.content })
        repository.addEditedVersion("c", answer.id, "遇到灰狼", revision())
        val wolfBranch = repository.getActiveBranch("c")!!.id
        assertEquals(listOf("去森林", "遇到灰狼"), repository.getBranchContents(wolfBranch))

        // 來回切換三條路線，內容不得互相污染。
        assertTrue(repository.selectVersion("c", firstMessage.id, firstVersions[1].id, revision()))
        assertEquals(beachBranch, repository.getActiveBranch("c")!!.id)
        assertEquals(listOf("去海邊", "看到海豚"), repository.getMessages("c").map { it.content })

        assertTrue(repository.selectVersion("c", firstMessage.id, firstVersions[2].id, revision()))
        assertEquals(mountainBranch, repository.getActiveBranch("c")!!.id)
        assertEquals(listOf("去山上"), repository.getMessages("c").map { it.content })

        assertTrue(repository.selectVersion("c", firstMessage.id, firstVersions[0].id, revision()))
        assertEquals(
            listOf("去森林", "遇到黑貓", "跟著牠", "抵達木屋"),
            repository.getBranchContents(repository.getBranches("c").first { it.forkMessageId == null }.id),
        )
    }

    // ---- 排除狀態只屬於目前路線 ----

    @Test fun exclusionOnlyAffectsTheCurrentRoute() = runBlocking {
        seedForestRoute()
        val first = repository.getMessages("c").first()
        val forestBranch = repository.getActiveBranch("c")!!.id
        repository.addEditedVersion("c", first.id, "去海邊", revision())
        val beachBranch = repository.getActiveBranch("c")!!.id

        // 在海邊路線排除第一則訊息。
        val beachFirst = repository.getMessages("c").first()
        assertTrue(repository.setMessageExcluded("c", beachFirst.id, true))
        assertTrue(repository.getMessages("c").first().excluded)
        // 森林路線不受影響。
        assertEquals(
            listOf(false, false, false, false),
            repository.getBranchMessages(forestBranch).map { it.excluded },
        )
        assertTrue(repository.getBranchMessages(beachBranch).first().excluded)
    }

    // ---- 摘要與裁切只屬於目前路線 ----

    @Test fun summaryAndContextCutOnlyAffectTheCurrentRoute() = runBlocking {
        seedForestRoute()
        val first = repository.getMessages("c").first()
        val forestBranch = repository.getActiveBranch("c")!!.id
        repository.updateBranchSummary(forestBranch, "森林摘要", 2)
        repository.updateBranchContextStart(forestBranch, 2)

        repository.addEditedVersion("c", first.id, "去海邊", revision())
        val beachBranch = repository.getActiveBranch("c")!!.id
        // 分岔點落在摘要範圍內，新路線不繼承摘要。
        val beach = repository.getActiveBranch("c")!!
        assertEquals("", beach.summary)
        assertEquals(0L, beach.summaryThroughOrder)
        assertEquals(0L, beach.contextStartOrder)

        repository.updateBranchSummary(beachBranch, "海邊摘要", 1)
        // 原路線摘要不因新路線而改變。
        val forest = repository.getBranches("c").first { it.id == forestBranch }
        assertEquals("森林摘要", forest.summary)
        assertEquals(2L, forest.summaryThroughOrder)
        assertEquals(2L, forest.contextStartOrder)
    }

    // ---- 全路線刪除：所有版本清除，其他訊息與分岔入口保留 ----

    @Test fun deletingMessageClearsEveryVersionAndKeepsForkEntry() = runBlocking {
        seedForestRoute()
        val first = repository.getMessages("c").first()
        val forestBranch = repository.getActiveBranch("c")!!.id
        repository.addEditedVersion("c", first.id, "去海邊", revision())
        val beachBranch = repository.getActiveBranch("c")!!.id
        assertEquals(2, repository.getMessageVersions(first.id).size)

        assertTrue(repository.deleteMessage("c", first.id))

        // 所有版本與生成資料都清掉。
        assertTrue(repository.getMessageVersions(first.id).isEmpty())
        // 分岔點保留結構標記，其他路線不會失去入口。
        val tombstone = repository.getMessage(first.id)!!
        assertTrue(tombstone.deleted)
        assertEquals("", tombstone.content)
        assertTrue(repository.isForkPoint("c", first.id))
        assertEquals(listOf(first.id), repository.getBranchMessages(beachBranch).map { it.messageId })
        // 原路線其他訊息保留。
        assertEquals(4, repository.getBranchMessages(forestBranch).size)
    }

    @Test fun deletingNonForkMessageRemovesItFromEveryRoute() = runBlocking {
        seedForestRoute()
        val target = repository.getMessages("c")[2]
        val branchId = repository.getActiveBranch("c")!!.id
        assertTrue(repository.deleteMessage("c", target.id))
        assertEquals(listOf("去森林", "遇到黑貓", "抵達木屋"), repository.getBranchContents(branchId))
        assertEquals(3, repository.getBranchMessages(branchId).size)
    }

    // ---- 選定版本不會因為切換上層而遺失 ----

    @Test fun activeRouteAndSelectionSurviveReopeningTheDatabase() = runBlocking {
        seedForestRoute()
        val first = repository.getMessages("c").first()
        repository.addEditedVersion("c", first.id, "去海邊", revision())
        val beachBranch = repository.getActiveBranch("c")!!.id

        // 重新讀取（等同重啟 App 後重新觀察）。
        val reopened = repository.getActiveBranch("c")
        assertNotNull(reopened)
        assertEquals(beachBranch, reopened!!.id)
        assertEquals(listOf("去海邊"), repository.getMessages("c").map { it.content })
        assertNotNull(repository.getMessage(first.id)!!.deleted.let { it })
        assertFalse(repository.getConversation("c")!!.activeBranchId.isBlank())
    }

    // ---- 路線快照不得混入其他路線的內容 ----

    @Test fun routeSnapshotNeverMixesContentFromAnotherRoute() = runBlocking {
        seedForestRoute()
        val first = repository.getMessages("c").first()
        repository.addEditedVersion("c", first.id, "去海邊", revision())

        val beach = repository.getRouteSnapshot("c")
        assertEquals(listOf("去海邊"), beach.messages.map { it.content })
        assertEquals("兩版第一句都算這則訊息的版本", 2, beach.versions.size)
        assertFalse(beach.messages.any { it.content == "抵達木屋" })
        assertTrue("新路線沒有生成資料", beach.contexts.isEmpty())

        val forestVersion = repository.getMessageVersions(first.id).first().id
        assertTrue(repository.selectVersion("c", first.id, forestVersion, revision()))

        val forest = repository.getRouteSnapshot("c")
        assertEquals(listOf("去森林", "遇到黑貓", "跟著牠", "抵達木屋"), forest.messages.map { it.content })
        assertFalse(forest.messages.any { it.content == "去海邊" })
        assertEquals("只有原路線的版本", 5, forest.versions.size)
    }

    // ---- 程序中斷：有正文的草稿變成可選的中斷版本，空草稿清掉且不取代原路線 ----

    @Test fun interruptedDraftWithContentIsRecoveredAsSelectableVersion() = runBlocking {
        seedForestRoute()
        val branchId = repository.getActiveBranch("c")!!.id
        val draft = repository.prepareDraft(
            GenerationRequestFacts(ChatGenerationKind.NEW_REPLY, "c", null),
            MessageVersionSource.ORIGINAL,
            null,
            "",
        )!!
        repository.updateDraft(draft.copy(version = draft.version.copy(content = "中斷的部分回覆")), null)

        repository.recoverInterruptedDrafts()

        assertEquals(MessageVersionStatus.INTERRUPTED, repository.getMessageVersion(draft.version.id)?.status)
        assertEquals("中斷的部分回覆", repository.getMessageVersion(draft.version.id)?.content)
        // 草稿仍留在原路線上，不能被清掉。
        assertEquals(5, repository.getBranchMessages(branchId).size)
    }

    @Test fun emptyDraftIsCleanedUpAndOriginalRouteIsRestored() = runBlocking {
        seedForestRoute()
        val originalBranch = repository.getActiveBranch("c")!!.id
        val target = repository.getMessages("c").last()
        val base = repository.getMessageVersion(target.currentVersionId)!!
        val draft = repository.prepareDraft(
            GenerationRequestFacts(ChatGenerationKind.CONTINUATION, "c", target.id),
            MessageVersionSource.CONTINUATION,
            base,
            base.content,
        )!!
        // 候選路線已切換過去，畫面才能預覽。
        assertNotEquals(originalBranch, repository.getActiveBranch("c")!!.id)

        repository.recoverInterruptedDrafts()

        // 空草稿清掉，回到原路線，沒有多出任何版本。
        assertEquals(originalBranch, repository.getActiveBranch("c")!!.id)
        assertEquals(1, repository.getMessageVersions(target.id).size)
        assertEquals(listOf("去森林", "遇到黑貓", "跟著牠", "抵達木屋"), repository.getMessages("c").map { it.content })
    }

    private suspend fun revision(): Long = repository.getConversation("c")!!.historyRevision
    private suspend fun seedForestRoute() {
        repository.upsertConversation(ConversationEntity("c", "chat", 1, 1))
        repository.createInitialMessage("c", "user", "去森林", 10)
        repository.createInitialMessage("c", "assistant", "遇到黑貓", 20)
        repository.createInitialMessage("c", "user", "跟著牠", 30)
        repository.createInitialMessage("c", "assistant", "抵達木屋", 40)
    }
}
