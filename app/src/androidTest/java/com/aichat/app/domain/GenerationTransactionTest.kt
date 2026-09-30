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

    @Test fun successfulAlternativeCreatesVersionThenDeletesFuture() = runBlocking {
        val fixture = fixture(200, sse("""{"choices":[{"delta":{"content":"replacement"}}]}""", "[DONE]"))
        val target = fixture.repository.getMessages("c")[1]
        fixture.useCase(request(ChatGenerationKind.ALTERNATIVE, target, fixture.repository), fixture.settings, "test")
        val messages = fixture.repository.getMessages("c")
        assertEquals(listOf("question", "replacement"), messages.map { it.content })
        val versions = fixture.repository.getMessageVersions(target.id)
        assertEquals(2, versions.size)
        assertEquals(MessageVersionSource.REGENERATED, versions.last().source)
        assertEquals(versions.last().id, messages.last().currentVersionId)
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

    @Test fun stoppingAfterTextSavesPartialAlternativeAndAppliesConfirmedCutoff() = runBlocking {
        val pipe = Pipe(8192)
        val fixture = fixture(200, "", pipe = pipe)
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
