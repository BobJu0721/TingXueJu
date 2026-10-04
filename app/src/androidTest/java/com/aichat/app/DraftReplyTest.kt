package com.aichat.app

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aichat.app.data.*
import com.aichat.app.domain.*
import com.aichat.app.network.AiApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList

class DraftReplyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var container: AppContainer
    private lateinit var repo: ConversationRepository
    private lateinit var vm: ChatViewModel
    private lateinit var previousSettings: AppSettings
    private var previousSecrets: Map<String, *> = emptyMap<String, String>()
    private val payloads = CopyOnWriteArrayList<JSONObject>()
    private val calls = CopyOnWriteArrayList<Call>()
    private var code = 200
    private var body = sse("user draft")
    private var pipe: Pipe? = null
    private val settings = AppSettings(provider = Provider.CUSTOM, customBaseUrl = "https://draft.invalid/v1", model = "test")

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val api = AiApiClient(OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            payloads.add(JSONObject(buffer.readUtf8()))
            calls.add(chain.call())
            val responseBody = pipe?.let { channel -> object : ResponseBody() {
                private val source = channel.source.buffer()
                override fun contentType() = "text/event-stream".toMediaType()
                override fun contentLength() = -1L
                override fun source(): BufferedSource = source
            } } ?: body.toResponseBody("text/event-stream".toMediaType())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("test").body(responseBody).build()
        }.build())
        container = AppContainer(context, db, api)
        previousSettings = container.settingsRepository.settings.first()
        previousSecrets = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE).all
        container.settingsRepository.save(settings)
        container.secretStore.put(Provider.CUSTOM, "test-only-key")
        repo = container.conversationRepository
        repo.upsertConversation(ConversationEntity("c", "story", 1, 1))
        repo.createInitialMessage("c", "user", "rainy inn")
        repo.createInitialMessage("c", "assistant", "welcome")
        vm = ChatViewModel(container)
        vm.selectConversation("c")
        await { vm.selectedConversation.value?.id == "c" && vm.settings.value.provider == Provider.CUSTOM }
        Unit
    }

    @After fun cleanup() = runBlocking {
        vm.closeDraftReply()
        pipe?.sink?.close()
        vm.viewModelScope.cancel()
        container.settingsRepository.save(previousSettings)
        context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE).edit().clear().apply {
            previousSecrets.forEach { (key, value) -> putString(key, value as String) }
        }.commit()
        db.close()
    }

    @Test fun independentPromptUsesPersonaBackgroundSceneAndExcludesPrivateNotes() = runBlocking {
        db.chatDao().upsertProfile(ProfileEntity("char", ProfileType.CHARACTER, "innkeeper", createdAt = 1, updatedAt = 1, background = "keeps an inn"))
        db.chatDao().upsertProfile(ProfileEntity("persona", ProfileType.PERSONA, "traveler", createdAt = 1, updatedAt = 1, background = "seeks shelter"))
        repo.updateConversation(repo.getConversation("c")!!.copy(characterId = "char", personaId = "persona"))
        repo.appendAuthoredMessage("c", MessageKind.PRIVATE_NOTE, "secret_trigger do not reveal")
        repo.appendAuthoredMessage("c", MessageKind.NARRATION, "three days later")
        repo.appendAuthoredMessage("c", MessageKind.AUTHORED_CHARACTER, "do not enter", "keeper")
        val branch = repo.getActiveBranch("c")!!
        repo.saveChatInfo("c", branch.id, SceneNote("海邊場景", true), ReplyLengthPreference.DEFAULT, null, TokenLimitField.AUTO)
        db.chatDao().upsertWorldSet(WorldSetEntity("w", "world", createdAt = 1, updatedAt = 1, overview = "world overview"))
        db.chatDao().upsertWorldEntry(WorldEntryEntity("e", "w", "private lore", "[\"secret_trigger\"]", "must not activate"))
        repo.replaceConversationWorldSets("c", listOf(ConversationWorldSetEntity("c", "w")))
        vm.setInput("my existing draft")
        vm.openDraftReply()
        vm.updateDraftInstruction("politely refuse")
        val before = repo.getGenerationSnapshot("c")!!
        vm.generateDraftReply()
        await { vm.draftReply.value?.content == "user draft" && vm.draftReply.value?.generating == false }
        assertEquals(1, payloads.size)
        val messages = payloads.single().getJSONArray("messages")
        val system = messages.getJSONObject(0).getString("content")
        assertTrue(system.contains("你正在協助使用者撰寫下一則訊息"))
        assertFalse(system.contains("請自然地延續對話"))
        assertTrue(system.contains("對話對象：innkeeper"))
        assertTrue(system.contains("使用者身份：traveler"))
        assertTrue(system.contains("海邊場景"))
        assertTrue(system.contains("world overview"))
        assertFalse(payloads.single().toString().contains("secret_trigger"))
        assertFalse(payloads.single().toString().contains("must not activate"))
        assertTrue(messages.toString().contains("【旁白】"))
        assertTrue(messages.toString().contains("【角色台詞：keeper】"))
        assertTrue(messages.getJSONObject(messages.length() - 1).getString("content").contains("my existing draft"))
        assertTrue(messages.getJSONObject(messages.length() - 1).getString("content").contains("politely refuse"))
        assertEquals(before, repo.getGenerationSnapshot("c"))
        assertEquals("my existing draft", vm.input.value)
    }

    @Test fun noPersonaKeepsAllEffectiveHistoryAndSummary() = runBlocking {
        repeat(45) { repo.createInitialMessage("c", "user", "visible $it") }
        val branch = repo.getActiveBranch("c")!!
        repo.updateBranchSummary(branch.id, "older events", 1)
        vm.openDraftReply(); vm.generateDraftReply()
        await { vm.draftReply.value?.generating == false && payloads.isNotEmpty() }
        val messages = payloads.single().getJSONArray("messages")
        assertEquals(48, messages.length()) // Summary, 46 visible messages, and the explicit drafting request.
        assertTrue(messages.getJSONObject(0).getString("content").contains("older events"))
        assertFalse(messages.getJSONObject(0).getString("content").contains("## 使用者身份"))
    }

    @Test fun replacementRequiresConfirmationAndStillDoesNotSend() = runBlocking {
        vm.setInput("original"); vm.openDraftReply(); vm.generateDraftReply()
        await { vm.draftReply.value?.content == "user draft" && vm.draftReply.value?.generating == false }
        vm.acceptDraftReply()
        await { vm.draftReply.value?.replacementInput == "original" }
        assertEquals("original", vm.input.value)
        vm.dismissDraftReplacement()
        assertEquals("original", vm.input.value)
        vm.acceptDraftReply(); await { vm.draftReply.value?.replacementInput != null }
        vm.acceptDraftReply(true); await { vm.draftReply.value == null }
        assertEquals("user draft", vm.input.value)
        assertEquals(2, repo.getMessages("c").size)
        assertEquals(1, payloads.size)
    }

    @Test fun acceptingIntoEmptyInputOnlyFillsAndRegenerationIsOneRequestEach() = runBlocking {
        vm.openDraftReply(); vm.generateDraftReply()
        await { vm.draftReply.value?.content == "user draft" && vm.draftReply.value?.generating == false }
        body = sse("second draft")
        vm.generateDraftReply()
        await { vm.draftReply.value?.content == "second draft" && vm.draftReply.value?.generating == false }
        assertEquals(2, payloads.size)
        vm.acceptDraftReply(); await { vm.draftReply.value == null }
        assertEquals("second draft", vm.input.value)
        assertEquals(2, repo.getMessages("c").size)
    }

    @Test fun changedInputRequiresAnotherConfirmation() = runBlocking {
        vm.setInput("first"); vm.openDraftReply(); vm.generateDraftReply()
        await { vm.draftReply.value?.generating == false && payloads.isNotEmpty() }
        vm.acceptDraftReply(); await { vm.draftReply.value?.replacementInput == "first" }
        vm.setInput("new input"); vm.acceptDraftReply(true)
        await { vm.draftReply.value?.replacementInput == "new input" }
        assertEquals("new input", vm.input.value)
    }

    @Test fun changedBranchRejectsOldPreviewAndNextDraftUsesItsScene() = runBlocking {
        vm.setInput("original"); vm.openDraftReply(); vm.generateDraftReply()
        await { vm.draftReply.value?.generating == false && payloads.isNotEmpty() }
        val first = repo.getMessages("c").first()
        repo.addEditedVersion("c", first.id, "sea", repo.getConversation("c")!!.historyRevision)
        val branch = repo.getActiveBranch("c")!!
        repo.saveChatInfo("c", branch.id, SceneNote("sea direction", true), ReplyLengthPreference.DEFAULT, null, TokenLimitField.AUTO)
        vm.acceptDraftReply()
        await { vm.draftReply.value?.error != null }
        assertEquals("original", vm.input.value)
        vm.generateDraftReply()
        await { payloads.size == 2 && vm.draftReply.value?.generating == false }
        assertEquals(branch.id, vm.draftReply.value?.branchId)
        assertTrue(payloads.last().getJSONArray("messages").getJSONObject(0).getString("content").contains("sea direction"))
    }

    @Test fun failureDoesNotOverwriteInputOrRetryOrSummarize() = runBlocking {
        code = 400
        body = """{"error":{"message":"maximum context length exceeded"}}"""
        vm.setInput("original"); vm.openDraftReply()
        val before = repo.getGenerationSnapshot("c")
        vm.generateDraftReply()
        await { vm.draftReply.value?.error != null && vm.draftReply.value?.generating == false }
        assertEquals("original", vm.input.value)
        assertEquals(1, payloads.size)
        assertEquals(before, repo.getGenerationSnapshot("c"))
    }

    @Test fun emptyReplyIsReportedAsError() = runBlocking {
        body = "data: [DONE]\n\n"
        vm.openDraftReply(); vm.generateDraftReply()
        await { vm.draftReply.value?.error != null && vm.draftReply.value?.generating == false }
        assertEquals("", vm.input.value)
    }

    @Test fun stoppingKeepsPartialPreviewAndOriginalInputAndBlocksConcurrentMutations() = runBlocking {
        pipe = Pipe(8192)
        vm.setInput("original"); vm.openDraftReply(); vm.generateDraftReply()
        await { payloads.isNotEmpty() }
        pipe!!.sink.buffer().apply { writeUtf8(delta("partial")); flush() }
        await { vm.draftReply.value?.content == "partial" }
        val before = repo.getGenerationSnapshot("c")
        vm.send()
        vm.appendAuthoredMessage(MessageKind.NARRATION, "should not append")
        vm.manuallySummarizeConversation(ManualSummaryMode.UN_SUMMARIZED, 1)
        assertEquals(before, repo.getGenerationSnapshot("c"))
        assertEquals(1, payloads.size)
        vm.stopDraftReply(); pipe!!.sink.close()
        await { vm.draftReply.value?.generating == false }
        assertTrue(vm.draftReply.value!!.incomplete)
        assertEquals("partial", vm.draftReply.value!!.content)
        assertEquals("original", vm.input.value)
        assertEquals(before, repo.getGenerationSnapshot("c"))
    }

    @Test fun switchingConversationCancelsAndLateCallbacksCannotPopulateNewPreview() = runBlocking {
        pipe = Pipe(8192)
        vm.openDraftReply(); vm.generateDraftReply(); await { payloads.isNotEmpty() }
        repo.upsertConversation(ConversationEntity("other", "other", 1, 1))
        vm.selectConversation("other")
        pipe!!.sink.close()
        await { vm.selectedConversation.value?.id == "other" && vm.draftReply.value == null }
        delay(100)
        vm.openDraftReply()
        assertEquals("other", vm.draftReply.value?.conversationId)
        assertEquals("", vm.draftReply.value?.content)
        assertTrue(calls.single().isCanceled())
    }

    @Test fun closingIdlePreviewDoesNotCancelNormalChatRequest() = runBlocking {
        pipe = Pipe(8192)
        vm.openDraftReply(); vm.setInput("next"); vm.send()
        await { payloads.isNotEmpty() && vm.isStreaming.value }
        vm.closeDraftReply()
        assertFalse(calls.single().isCanceled())
        pipe!!.sink.buffer().apply { writeUtf8(sse("answer")); flush(); close() }
        await { !vm.isStreaming.value }
        assertNull(vm.error.value)
        assertEquals("answer", repo.getMessages("c").last().content)
    }

    @Test fun continuingAiRetainsTrailingPrivateNotesOnBothRoutes() = runBlocking {
        val answer = repo.getMessages("c").last()
        val original = repo.getActiveBranch("c")!!.id
        val note = repo.appendAuthoredMessage("c", MessageKind.PRIVATE_NOTE, "private memory")!!
        container.streamConversationUseCase(ChatGenerationRequest(ChatGenerationKind.CONTINUATION, "c", answer.id,
            answer.currentVersionId, repo.getConversation("c")!!.historyRevision), settings, "test")
        val messages = repo.getMessages("c")
        assertEquals(note.id, messages.last().id)
        assertEquals("welcomeuser draft", messages.first { it.id == answer.id }.content)
        assertEquals(listOf("rainy inn", "welcome", "private memory"), repo.getBranchContents(original))
        assertFalse(payloads.single().toString().contains("private memory"))
    }

    @Test fun staleAuthoredAppendDoesNotWriteToAnotherRoute() = runBlocking {
        val before = repo.getGenerationSnapshot("c")!!
        val first = repo.getMessages("c").first()
        repo.addEditedVersion("c", first.id, "sea", before.conversation.historyRevision)
        assertNull(repo.appendAuthoredMessage("c", MessageKind.NARRATION, "wrong route",
            sourceBranchId = before.branch.id, expectedRevision = before.conversation.historyRevision))
        assertEquals(listOf("sea"), repo.getMessages("c").map { it.content })
    }

    @Test fun httpDraftRequiresConfirmationAndClosingClearsPendingWarning() = runBlocking {
        container.settingsRepository.save(settings.copy(customBaseUrl = "http://draft.invalid/v1"))
        await { vm.settings.value.usesUnsafeHttp }
        vm.openDraftReply(); vm.generateDraftReply()
        assertTrue(vm.showUnsafeHttpWarning.value)
        assertEquals(0, payloads.size)
        vm.closeDraftReply()
        assertFalse(vm.showUnsafeHttpWarning.value)
        vm.openDraftReply(); vm.generateDraftReply(); vm.confirmUnsafeHttp()
        await { vm.draftReply.value?.generating == false && payloads.isNotEmpty() }
        assertEquals(1, payloads.size)
        assertEquals("user draft", vm.draftReply.value?.content)
    }

    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(10000) {
        while (!predicate()) delay(25)
    }

    private fun delta(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\n"
    private fun sse(text: String) = delta(text) + "data: [DONE]\n\n"
}
