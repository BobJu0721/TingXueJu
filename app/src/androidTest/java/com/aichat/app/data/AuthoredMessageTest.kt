package com.aichat.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.app.domain.ChatGenerationKind
import com.aichat.app.domain.EffectiveHistoryResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 手動創作訊息：旁白、指定角色台詞與私人註記。
 *
 * 只加入紀錄、不呼叫 API；署名跟著版本保存；私人註記完全不進模型可見的內容，也不佔有效
 * 對話名額。
 */
@RunWith(AndroidJUnit4::class)
class AuthoredMessageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: ConversationRepository

    @Before fun before() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = ConversationRepository(db.chatDao())
    }

    @After fun after() = db.close()

    @Test fun authoredMessageIsRecordedWithoutCreatingACandidate() = runBlocking {
        seed("c")
        val branchBefore = repository.getActiveBranch("c")!!.id
        val countBefore = repository.getMessages("c").size

        val narration = repository.appendAuthoredMessage("c", MessageKind.NARRATION, "三日後，眾人抵達山門。")!!

        val messages = repository.getMessages("c")
        assertEquals(countBefore + 1, messages.size)
        // 沒有切換路線、也沒有建立候選分支。
        assertEquals(branchBefore, repository.getActiveBranch("c")!!.id)
        assertEquals("只加入紀錄，不建立候選路線", 1, repository.getBranches("c").size)
        // 只有一個版本，而且是完成狀態，不是生成草稿。
        val versions = repository.getMessageVersions(narration.id)
        assertEquals(1, versions.size)
        assertEquals(MessageVersionStatus.COMPLETE, versions.single().status)
        assertEquals(MessageVersionSource.ORIGINAL, versions.single().source)
        // 手寫內容沒有生成資料。
        assertTrue(db.chatDao().getRouteGenerationContexts("c").isEmpty())
    }

    @Test fun authoredCharacterKeepsItsSignatureAfterTheProfileIsRenamed() = runBlocking {
        seed("c")
        val dao = db.chatDao()
        val profile = ProfileEntity(id = "char-1", type = ProfileType.CHARACTER, name = "掌櫃", createdAt = 1, updatedAt = 1)
        dao.upsertProfile(profile)

        val line = repository.appendAuthoredMessage(
            "c",
            MessageKind.AUTHORED_CHARACTER,
            "樓上的房間，今晚不能進去。",
            authorName = "掌櫃",
            authorCharacterId = "char-1",
        )!!
        assertEquals("掌櫃", repository.getRouteMessage("c", line.id)!!.authorName)
        assertEquals("【角色台詞：掌櫃】\n樓上的房間，今晚不能進去。", repository.getRouteMessage("c", line.id)!!.toPromptContent())

        // 角色庫改名後，既有台詞的署名不變。
        dao.upsertProfile(profile.copy(name = "客棧老闆", updatedAt = 2))
        assertEquals("掌櫃", repository.getRouteMessage("c", line.id)!!.authorName)
        assertEquals("【角色台詞：掌櫃】\n樓上的房間，今晚不能進去。", repository.getRouteMessage("c", line.id)!!.toPromptContent())
    }

    @Test fun privateNoteIsInvisibleToTheModelAndDoesNotCountAsContent() = runBlocking {
        seed("c")
        val assistant = repository.getMessages("c").last()
        repository.appendAuthoredMessage("c", MessageKind.PRIVATE_NOTE, "備忘：讓掌櫃與使者是舊識")

        val messages = repository.getMessages("c")
        // 尾端是私人註記，但可以續寫的仍然是那則 AI 回覆。
        assertTrue(messages.last().kind == MessageKind.PRIVATE_NOTE)
        assertEquals(assistant.id, messages.lastOrNull { it.isModelVisible }!!.id)

        val effective = EffectiveHistoryResolver.resolve(
            repository.getActiveConversation("c")!!,
            messages,
            ChatGenerationKind.NEW_REPLY,
        )
        assertTrue(effective.promptHistory.none { it.kind == MessageKind.PRIVATE_NOTE })
        assertTrue(effective.worldHistory.none { it.kind == MessageKind.PRIVATE_NOTE })
        // 私人註記不佔有效對話名額：使用者 + AI 兩則。
        assertEquals(2, effective.promptHistory.size)
    }

    @Test fun editingAuthoredContentKeepsOldSignatureAndOriginalRoute() = runBlocking {
        seed("c")
        val line = repository.appendAuthoredMessage(
            "c",
            MessageKind.AUTHORED_CHARACTER,
            "第一版台詞",
            authorName = "掌櫃",
            authorCharacterId = "char-1",
        )!!
        val branchBefore = repository.getActiveBranch("c")!!.id

        assertTrue(
            repository.addEditedVersion(
                "c",
                line.id,
                "第二版台詞",
                repository.getConversation("c")!!.historyRevision,
                authorName = "使者",
            ),
        )

        val versions = repository.getMessageVersions(line.id)
        assertEquals(listOf("掌櫃", "使者"), versions.map { it.authorName })
        assertEquals("使者", repository.getRouteMessage("c", line.id)!!.authorName)
        // 原路線與原署名版本都保留。
        assertNotEquals(branchBefore, repository.getActiveBranch("c")!!.id)
        val originalVersionId = db.chatDao().getBranchMessage(branchBefore, line.id)?.versionId
        assertEquals("掌櫃", originalVersionId?.let { repository.getMessageVersion(it)?.authorName })
    }

    private suspend fun seed(id: String) {
        repository.upsertConversation(ConversationEntity(id, "chat", 1, 1))
        repository.createInitialMessage(id, "user", "去森林", 10)
        repository.createInitialMessage(id, "assistant", "遇到黑貓", 20)
    }
}
