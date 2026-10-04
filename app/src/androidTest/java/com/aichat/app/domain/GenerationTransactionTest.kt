package com.aichat.app.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.app.data.*
import com.aichat.app.network.AiApiClient
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.Buffer
import okio.Pipe
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationTransactionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun failedAlternativeKeepsOriginalAndFutureHistory() = runBlocking {
        val fixture = fixture(500, """{"error":{"message":"failed"}}""")
        val target = fixture.repository.getMessages("c")[1]
        val request = request(ChatGenerationKind.ALTERNATIVE, target, fixture.repository)
        assertTrue(runCatching { fixture.useCase(request, fixture.settings, "test") }.isFailure)
        val messages = fixture.repository.getMessages("c")
        assertEquals(listOf("question", "original", "future"), messages.map { it.content })
        assertEquals(1, fixture.repository.getMessageVersions(target.id).size)
        fixture.close()
    }

    @Test fun midStreamFailureRestoresOriginalVersionAndFutureHistory() = runBlocking {
        val fixture = fixture(200, sse(
            """{"choices":[{"delta":{"content":"temporary"}}]}""",
            """{"error":{"message":"midstream failure"}}""",
        ))
        val target = fixture.repository.getMessages("c")[1]
        assertTrue(runCatching {
            fixture.useCase(request(ChatGenerationKind.ALTERNATIVE, target, fixture.repository), fixture.settings, "test")
        }.isFailure)
        assertEquals(listOf("question", "original", "future"), fixture.repository.getMessages("c").map { it.content })
        assertEquals(1, fixture.repository.getMessageVersions(target.id).size)
        fixture.close()
    }

    @Test fun continuationWithReasoningOnlyDoesNotCreateDuplicateVersion() = runBlocking {
        val fixture = fixture(200, sse(
            """{"choices":[{"delta":{"reasoning_content":"thinking"}}]}""", "[DONE]",
        ), includeFuture = false)
        val target = fixture.repository.getMessages("c").last()
        assertTrue(runCatching {
            fixture.useCase(request(ChatGenerationKind.CONTINUATION, target, fixture.repository), fixture.settings, "test")
        }.isFailure)
        assertEquals("original", fixture.repository.getMessage(target.id)?.content)
        assertEquals(1, fixture.repository.getMessageVersions(target.id).size)
        fixture.close()
    }

    @Test fun successfulAlternativeKeepsTheOriginalRouteAndItsFutureHistory() = runBlocking {
        val fixture = fixture(200, sse("""{"choices":[{"delta":{"content":"replacement"}}]}""", "[DONE]"))
        val originalBranch = fixture.repository.getActiveBranch("c")!!.id
        val target = fixture.repository.getMessages("c")[1]
        fixture.useCase(request(ChatGenerationKind.ALTERNATIVE, target, fixture.repository), fixture.settings, "test")
        // 新路線只到新回答為止。
        assertEquals(listOf("question", "replacement"), fixture.repository.getMessages("c").map { it.content })
        // 舊回答與其後續完整留在原路線，沒有任何隱含刪除。
        assertEquals(listOf("question", "original", "future"), fixture.repository.getBranchContents(originalBranch))
        val versions = fixture.repository.getMessageVersions(target.id)
        assertEquals(2, versions.size)
        assertEquals(MessageVersionSource.REGENERATED, versions.last().source)
        assertEquals(versions.last().id, fixture.repository.getMessages("c").last().currentVersionId)
        fixture.close()
    }

    @Test fun continuationAppendsWithoutForcedSeparatorAndKeepsOriginalVersion() = runBlocking {
        val fixture = fixture(200, sse("""{"choices":[{"delta":{"content":" plus"}}]}""", "[DONE]"), includeFuture = false)
        val target = fixture.repository.getMessages("c").last()
        fixture.useCase(request(ChatGenerationKind.CONTINUATION, target, fixture.repository), fixture.settings, "test")
        val current = fixture.repository.getMessage(target.id)!!
        assertEquals("original plus", current.content)
        val versions = fixture.repository.getMessageVersions(target.id)
        assertEquals(listOf("original", "original plus"), versions.map { it.content })
        assertEquals(MessageVersionSource.CONTINUATION, versions.last().source)
        fixture.close()
    }

    @Test fun stoppingAfterTextSavesPartialAnswerOnItsOwnRoute() = runBlocking {
        val pipe = Pipe(8192)
        val fixture = fixture(200, "", pipe = pipe)
        val originalBranch = fixture.repository.getActiveBranch("c")!!.id
        val target = fixture.repository.getMessages("c")[1]
        val job = async(Dispatchers.Default) {
            fixture.useCase(request(ChatGenerationKind.ALTERNATIVE, target, fixture.repository), fixture.settings, "test")
        }
        try {
            val chunk = Buffer().writeUtf8(sse("""{"choices":[{"delta":{"content":" more"}}]}"""))
            pipe.sink.write(chunk, chunk.size)
            pipe.sink.flush()
            withTimeout(5_000) {
                while (fixture.repository.getMessage(target.id)?.content != " more") delay(10)
            }
            job.cancel()
            fixture.api.cancelActive()
            pipe.sink.close()
            job.join()

            assertEquals(listOf("question", " more"), fixture.repository.getMessages("c").map { it.content })
            assertEquals(listOf("question", "original", "future"), fixture.repository.getBranchContents(originalBranch))
            val versions = fixture.repository.getMessageVersions(target.id)
            assertEquals(listOf("original", " more"), versions.map { it.content })
            assertEquals(MessageVersionStatus.PARTIAL, versions.last().status)
        } finally {
            pipe.sink.close()
            job.cancelAndJoin()
            fixture.close()
        }
    }

    @Test fun stoppingBeforeTextRemovesDraftAndKeepsOriginalHistory() = runBlocking {
        val pipe = Pipe(8192)
        val fixture = fixture(200, "", pipe = pipe)
        val target = fixture.repository.getMessages("c")[1]
        val created = CompletableDeferred<String>()
        val job = async(Dispatchers.Default) {
            fixture.useCase(
                request(ChatGenerationKind.ALTERNATIVE, target, fixture.repository),
                fixture.settings,
                "test",
                onAssistantMessageCreated = { created.complete(it) },
            )
        }
        try {
            withTimeout(5_000) { created.await() }
            job.cancel()
            fixture.api.cancelActive()
            pipe.sink.close()
            job.join()
            assertEquals(listOf("question", "original", "future"), fixture.repository.getMessages("c").map { it.content })
            assertEquals(1, fixture.repository.getMessageVersions(target.id).size)
        } finally {
            pipe.sink.close()
            job.cancelAndJoin()
            fixture.close()
        }
    }

    // ---- 編輯後重發：新增 AI 訊息的分岔路徑 ----

    /**
     * 使用者回報的路徑：編輯自己的訊息後按 ⟳。
     *
     * 編輯後的路線只有編輯過的輸入，沒有緊接著的 AI 回覆，因此走
     * `forkBranchAppendingMessage()`；舊版在那裡先寫子資料（版本）才寫父資料（訊息），
     * `message_versions.messageId` 的外鍵當場失敗。
     */
    @Test fun resendingAfterEditAppendsAnswerWithoutForeignKeyError() = runBlocking {
        val fixture = fixture(200, sse("""{"choices":[{"delta":{"content":"看到小船"}}]}"""), includeFuture = false)
        val originalBranch = fixture.repository.getActiveBranch("c")!!.id
        val first = fixture.repository.getMessages("c").first()

        val revision = fixture.repository.getConversation("c")!!.historyRevision
        assertTrue(fixture.repository.addEditedVersion("c", first.id, "去海邊", revision))
        val editedBranch = fixture.repository.getActiveBranch("c")!!.id
        assertEquals(listOf("去海邊"), fixture.repository.getBranchContents(editedBranch))
        assertNotEquals(originalBranch, editedBranch)

        val target = fixture.repository.getMessages("c").first()
        fixture.useCase(
            request(ChatGenerationKind.ANSWER_FROM_USER, target, fixture.repository),
            fixture.settings,
            "test",
        )

        // 新路線：去海邊 → 看到小船
        assertEquals(listOf("去海邊", "看到小船"), fixture.repository.getMessages("c").map { it.content })
        // 編輯前的路線完整保留，沒有被刪除也沒有被改寫。
        assertEquals(listOf("question", "original"), fixture.repository.getBranchContents(originalBranch))

        // 新路線必須真的屬於這個聊天室，而且草稿分支與路線關聯一致。
        val active = fixture.repository.getActiveBranch("c")!!
        assertEquals("c", active.conversationId)
        assertTrue(fixture.repository.getBranchMessages(active.id).all { it.branchId == active.id })

        // 新回答的版本已完成，並帶有生成資料。
        val reply = fixture.repository.getMessages("c").last()
        val replyVersions = fixture.repository.getMessageVersions(reply.id)
        assertEquals(1, replyVersions.size)
        assertEquals(MessageVersionStatus.COMPLETE, replyVersions.single().status)
        assertNotNull(getGenerationContext(fixture.db, replyVersions.single().id))

        assertEquals("外鍵必須乾淨", emptyList<String>(), foreignKeyViolations(fixture.db))
        fixture.close()
    }

    @Test fun failedResendKeepsEditedBranchAndCanRetry() = runBlocking {
        val failing = fixture(500, """{"error":{"message":"failed"}}""", includeFuture = false)
        val originalBranch = failing.repository.getActiveBranch("c")!!.id
        val first = failing.repository.getMessages("c").first()
        val revision = failing.repository.getConversation("c")!!.historyRevision
        failing.repository.addEditedVersion("c", first.id, "去海邊", revision)
        val editedBranch = failing.repository.getActiveBranch("c")!!.id
        assertNotEquals(originalBranch, editedBranch)

        val target = failing.repository.getMessages("c").first()
        val failure = runCatching {
            failing.useCase(
                request(ChatGenerationKind.ANSWER_FROM_USER, target, failing.repository),
                failing.settings,
                "test",
            )
        }
        assertTrue("重發應該失敗", failure.isFailure)
        assertFalse(
            "失敗原因不應是外鍵錯誤：${failure.exceptionOrNull()}",
            (failure.exceptionOrNull()?.message ?: "").contains("FOREIGN KEY", ignoreCase = true),
        )

        // 回到編輯版，本次草稿清乾淨，編輯內容仍在。
        assertEquals(editedBranch, failing.repository.getActiveBranch("c")!!.id)
        assertEquals(listOf("去海邊"), failing.repository.getMessages("c").map { it.content })
        // 使用者的編輯沒有被撤銷。
        assertEquals(2, failing.repository.getMessageVersions(first.id).size)
        // 這次失敗的候選分支與新 AI 訊息都已清除，只剩原本與編輯兩條路線。
        assertEquals(2, failing.repository.getBranches("c").size)
        assertEquals(listOf("question", "original"), failing.repository.getBranchContents(originalBranch))
        assertEquals("外鍵必須乾淨", emptyList<String>(), foreignKeyViolations(failing.db))
        failing.close()
    }

    // ---- 未編輯直接重發：沿用緊接著的既有 AI 回覆 ----

    @Test fun resendingWithoutEditKeepsOriginalRouteAndAddsAnswerVersion() = runBlocking {
        val fixture = fixture(200, sse("""{"choices":[{"delta":{"content":"second answer"}}]}"""))
        val originalBranch = fixture.repository.getActiveBranch("c")!!.id
        val question = fixture.repository.getMessages("c").first()
        val answerId = fixture.repository.getMessages("c")[1].id

        fixture.useCase(
            request(ChatGenerationKind.ANSWER_FROM_USER, question, fixture.repository),
            fixture.settings,
            "test",
        )

        assertEquals(listOf("question", "second answer"), fixture.repository.getMessages("c").map { it.content })
        assertEquals(
            listOf("question", "original", "future"),
            fixture.repository.getBranchContents(originalBranch),
        )
        val versions = fixture.repository.getMessageVersions(answerId)
        assertEquals(listOf("original", "second answer"), versions.map { it.content })
        assertEquals("外鍵必須乾淨", emptyList<String>(), foreignKeyViolations(fixture.db))
        fixture.close()
    }

    private fun getGenerationContext(db: AppDatabase, versionId: String): GenerationContextEntity? =
        db.openHelper.readableDatabase
            .query("SELECT versionId FROM generation_contexts WHERE versionId = ?", arrayOf(versionId))
            .use { if (it.moveToFirst()) GenerationContextEntity(it.getString(0)) else null }

    private fun foreignKeyViolations(db: AppDatabase): List<String> =
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            buildList { while (cursor.moveToNext()) add("${cursor.getString(0)}:${cursor.getLong(1)}") }
        }

    private suspend fun request(kind: ChatGenerationKind, target: MessageEntity, repository: ConversationRepository): ChatGenerationRequest {
        val conversation = repository.getConversation("c")!!
        return ChatGenerationRequest(kind, "c", target.id, target.currentVersionId, conversation.historyRevision)
    }

    private suspend fun fixture(code: Int, body: String, includeFuture: Boolean = true, pipe: Pipe? = null): Fixture {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = ConversationRepository(db.chatDao())
        repository.upsertConversation(ConversationEntity("c", "chat", 1, 1))
        repository.createInitialMessage("c", "user", "question", 10)
        repository.createInitialMessage("c", "assistant", "original", 20)
        if (includeFuture) repository.createInitialMessage("c", "user", "future", 30)
        val api = AiApiClient(OkHttpClient.Builder().addInterceptor { chain ->
            val responseBody = if (pipe == null) body.toResponseBody("text/event-stream".toMediaType())
                else object : ResponseBody() {
                    private val buffered = pipe.source.buffer()
                    override fun contentType() = "text/event-stream".toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): BufferedSource = buffered
                }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(responseBody).build()
        }.build())
        return Fixture(
            db,
            repository,
            StreamConversationUseCase(repository, ProfileRepository(db.chatDao()), WorldInfoRepository(db.chatDao()), api),
            AppSettings(),
            api,
        )
    }

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

    private data class Fixture(
        val db: AppDatabase,
        val repository: ConversationRepository,
        val useCase: StreamConversationUseCase,
        val settings: AppSettings,
        val api: AiApiClient,
    ) {
        fun close() = db.close()
    }
}
