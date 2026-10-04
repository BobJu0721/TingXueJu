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
import okio.Buffer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SceneNoteTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var container: AppContainer
    private lateinit var repo: ConversationRepository
    private lateinit var vm: ChatViewModel
    private lateinit var oldSettings: AppSettings
    private var oldSecrets: Map<String, *> = emptyMap<String, String>()
    private val payloads = CopyOnWriteArrayList<JSONObject>()
    private var fail = false
    private var lengthErrorOnce = false
    @Volatile private var streamGate: CountDownLatch? = null
    private val settings = AppSettings(provider = Provider.CUSTOM, customBaseUrl = "https://scene-test.invalid/v1", model = "test")
    private val inn = SceneNote("雨夜客棧\nKeep the secret.", true)

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val api = AiApiClient(OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val payload = JSONObject(buffer.readUtf8())
            payloads.add(payload)
            val stream = payload.optBoolean("stream")
            if (stream) streamGate?.await(8, TimeUnit.SECONDS)
            val contextError = stream && lengthErrorOnce
            if (contextError) lengthErrorOnce = false
            val code = if (contextError) 400 else if (fail) 500 else 200
            val body = when {
                contextError -> """{"error":{"message":"maximum context length exceeded","code":"context_length_exceeded"}}"""
                fail -> """{"error":{"message":"test failure"}}"""
                stream -> "data: {\"choices\":[{\"delta\":{\"content\":\"new answer\"}}]}\n\ndata: [DONE]\n\n"
                else -> JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message",
                    JSONObject().put("content", "{}")))).toString()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
        }.build())
        container = AppContainer(context, db, api)
        oldSettings = container.settingsRepository.settings.first()
        oldSecrets = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE).all
        container.settingsRepository.save(settings)
        container.secretStore.put(Provider.CUSTOM, "test-only-key")
        repo = container.conversationRepository
        vm = ChatViewModel(container)
        repo.upsertConversation(ConversationEntity("c", "chat", 1, 1))
        repo.createInitialMessage("c", "user", "question")
        repo.createInitialMessage("c", "assistant", "answer")
        Unit
    }

    @After fun cleanup() = runBlocking {
        streamGate?.countDown()
        vm.viewModelScope.cancel()
        container.settingsRepository.save(oldSettings)
        context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE).edit().clear().apply {
            oldSecrets.forEach { (key, value) -> putString(key, value as String) }
        }.commit()
        db.close()
    }

    private suspend fun save(note: SceneNote, branch: String? = null): Boolean =
        repo.saveChatInfo("c", branch ?: repo.getActiveBranch("c")!!.id, note, ReplyLengthPreference.DEFAULT, null, TokenLimitField.AUTO)

    @Test fun atomicSavePreservesSummaryAndDoesNotTouchLastUsedOrNoOpRevision() = runBlocking {
        val branch = repo.getActiveBranch("c")!!
        repo.updateBranchSummary(branch.id, "summary", 1)
        val before = repo.getConversation("c")!!
        assertTrue(repo.saveChatInfo("c", branch.id, inn, ReplyLengthPreference.DETAILED, 500, TokenLimitField.MAX_TOKENS))
        val after = repo.getConversation("c")!!
        assertEquals(before.historyRevision + 1, after.historyRevision)
        assertEquals(inn, repo.getActiveBranch("c")!!.sceneNoteValue())
        assertEquals("summary", repo.getActiveBranch("c")!!.summary)
        assertEquals(branch.lastUsedAt, repo.getActiveBranch("c")!!.lastUsedAt)
        assertTrue(repo.saveChatInfo("c", branch.id, inn, ReplyLengthPreference.DETAILED, 500, TokenLimitField.MAX_TOKENS))
        assertEquals(after.historyRevision, repo.getConversation("c")!!.historyRevision)
        assertEquals(inn, ConversationRepository(db.chatDao()).getActiveBranch("c")!!.sceneNoteValue())
    }

    @Test fun editingInheritsNoteRegardlessOfSummaryAndBranchesStayIndependent() = runBlocking {
        save(inn)
        val first = repo.getMessages("c").first()
        val original = repo.getActiveBranch("c")!!.id
        repo.updateBranchSummary(original, "summary", 2)
        assertTrue(repo.addEditedVersion("c", first.id, "beach", repo.getConversation("c")!!.historyRevision))
        assertEquals(inn, repo.getActiveBranch("c")!!.sceneNoteValue())
        assertEquals("", repo.getActiveBranch("c")!!.summary)
        save(SceneNote("海邊", true))
        val versions = repo.getMessageVersions(first.id)
        assertTrue(repo.selectVersion("c", first.id, versions.first().id, repo.getConversation("c")!!.historyRevision))
        assertEquals(inn, repo.getActiveBranch("c")!!.sceneNoteValue())
        assertTrue(repo.selectVersion("c", first.id, versions.last().id, repo.getConversation("c")!!.historyRevision))
        assertEquals(SceneNote("海邊", true), repo.getActiveBranch("c")!!.sceneNoteValue())
    }

    @Test fun allGenerationKindsUseSourceNoteAndCandidateInheritsIt() = runBlocking {
        for (kind in ChatGenerationKind.entries) {
            save(inn)
            val target = when (kind) {
                ChatGenerationKind.NEW_REPLY -> null
                ChatGenerationKind.ANSWER_FROM_USER -> repo.getMessages("c").first()
                else -> repo.getMessages("c").last()
            }
            container.streamConversationUseCase(ChatGenerationRequest(kind, "c", target?.id, target?.currentVersionId,
                repo.getConversation("c")!!.historyRevision), settings, "test")
            assertEquals(inn, repo.getActiveBranch("c")!!.sceneNoteValue())
            val messages = payloads.last().getJSONArray("messages")
            val system = messages.getJSONObject(0).getString("content")
            assertTrue(system.endsWith(inn.text))
            assertEquals(1, Regex("## 本場劇情提示").findAll(system).count())
            if (kind == ChatGenerationKind.CONTINUATION) assertTrue(system.contains("只輸出新增內容"))
            for (i in 1 until messages.length()) assertFalse(messages.getJSONObject(i).getString("content").contains(inn.text))
        }
    }

    @Test fun appendingAnswerAfterEditAlsoInheritsNote() = runBlocking {
        save(inn)
        val first = repo.getMessages("c").first()
        repo.addEditedVersion("c", first.id, "beach", repo.getConversation("c")!!.historyRevision)
        val edited = repo.getMessages("c").first()
        container.streamConversationUseCase(ChatGenerationRequest(ChatGenerationKind.ANSWER_FROM_USER, "c",
            edited.id, edited.currentVersionId, repo.getConversation("c")!!.historyRevision), settings, "test")
        assertEquals(inn, repo.getActiveBranch("c")!!.sceneNoteValue())
    }

    @Test fun disabledNoteIsRetainedButNeverSent() = runBlocking {
        save(inn.copy(enabled = false))
        container.streamConversationUseCase(ChatGenerationRequest(ChatGenerationKind.NEW_REPLY, "c",
            expectedRevision = repo.getConversation("c")!!.historyRevision), settings, "test")
        assertFalse(payloads.last().toString().contains("本場劇情提示"))
        assertEquals(inn.text, repo.getActiveBranch("c")!!.sceneNote)
    }

    @Test fun failedGenerationRestoresOriginalNoteAndBranch() = runBlocking {
        save(inn)
        val branch = repo.getActiveBranch("c")!!.id
        val target = repo.getMessages("c").last()
        fail = true
        assertTrue(runCatching { container.streamConversationUseCase(ChatGenerationRequest(ChatGenerationKind.ALTERNATIVE,
            "c", target.id, target.currentVersionId, repo.getConversation("c")!!.historyRevision), settings, "test") }.isFailure)
        assertEquals(branch, repo.getActiveBranch("c")!!.id)
        assertEquals(inn, repo.getActiveBranch("c")!!.sceneNoteValue())
    }

    @Test fun rollbackAndRecoveryKeepSourceNotes() = runBlocking {
        save(inn)
        val source = repo.getActiveBranch("c")!!.id
        val target = repo.getMessages("c").last()
        val base = repo.getMessageVersion(target.currentVersionId)!!
        val facts = GenerationRequestFacts(ChatGenerationKind.ALTERNATIVE, "c", target.id)
        val draft = repo.prepareDraft(facts, MessageVersionSource.REGENERATED, base, "")!!
        assertEquals(inn, repo.getActiveBranch("c")!!.sceneNoteValue())
        repo.rollbackDraft(draft)
        assertEquals(source, repo.getActiveBranch("c")!!.id)
        val interrupted = repo.prepareDraft(facts, MessageVersionSource.REGENERATED, base, "partial")!!
        repo.updateDraft(interrupted, null)
        repo.recoverInterruptedDrafts()
        assertEquals(inn, db.chatDao().getBranch(source)!!.sceneNoteValue())
        assertEquals(inn, db.chatDao().getBranch(interrupted.branchId)!!.sceneNoteValue())
    }

    @Test fun staleSaveRejectsBothSceneAndReplyOptions() = runBlocking {
        val source = repo.getActiveBranch("c")!!.id
        val first = repo.getMessages("c").first()
        repo.addEditedVersion("c", first.id, "new", repo.getConversation("c")!!.historyRevision)
        assertFalse(repo.saveChatInfo("c", source, inn, ReplyLengthPreference.SHORT, 42, TokenLimitField.MAX_TOKENS))
        assertEquals(SceneNote(), db.chatDao().getBranch(source)!!.sceneNoteValue())
        assertEquals(ReplyLengthPreference.DEFAULT, repo.getConversation("c")!!.replyLengthPreference)
        assertFalse(repo.saveChatInfo("c", "missing", inn, ReplyLengthPreference.SHORT, null, TokenLimitField.AUTO))
    }

    @Test fun summaryAndImportTasksNeverReceiveSceneNote() = runBlocking {
        save(inn)
        container.summarizeConversationUseCase("c", settings, "test", 1, ManualSummaryMode.UN_SUMMARIZED)
        container.organizeProfileUseCase("character source", ProfileType.CHARACTER, settings, "test")
        container.organizeProfileUseCase("persona source", ProfileType.PERSONA, settings, "test")
        container.organizeWorldSetUseCase("world source", settings, "test")
        assertTrue(payloads.isNotEmpty())
        payloads.forEach { assertFalse(it.toString().contains("本場劇情提示")); assertFalse(it.toString().contains("Keep the secret")) }
    }

    @Test fun viewModelSwitchOpenSaveAndGenerateUsesCorrectNote() = runBlocking {
        vm.selectConversation("c")
        await { vm.settings.value.provider == Provider.CUSTOM && vm.selectedConversation.value?.id == "c" }
        val first = repo.getMessages("c").first()
        val old = repo.getActiveBranch("c")!!.id
        vm.requestEditMessage(first.id, "beach")
        await { repo.getActiveBranch("c")!!.id != old }
        vm.openChatInfo()
        await { vm.chatInfoBranch.value?.id == repo.getActiveBranch("c")!!.id }
        var saved = false
        vm.saveChatInfo("c", vm.chatInfoBranch.value!!.id, inn, ReplyLengthPreference.DETAILED, 400, TokenLimitField.AUTO) { saved = true }
        await { saved && !vm.isSavingChatInfo.value }
        assertTrue(payloads.isEmpty())
        vm.requestAnswerFrom(first.id)
        await { payloads.isNotEmpty() && !vm.isStreaming.value }
        assertNull(vm.error.value)
        assertTrue(payloads.last().getJSONArray("messages").getJSONObject(0).getString("content").endsWith(inn.text))
        vm.requestSelectVersion(first.id, repo.getMessageVersions(first.id).first().id)
        await { repo.getActiveBranch("c")!!.id == old }
        vm.openChatInfo()
        await { vm.chatInfoBranch.value?.id == old }
        assertEquals(SceneNote(), vm.chatInfoBranch.value!!.sceneNoteValue())
    }

    @Test fun viewModelStaleSaveReportsErrorAndDoesNotReturn() = runBlocking {
        vm.selectConversation("c")
        await { vm.selectedConversation.value?.id == "c" }
        vm.openChatInfo()
        await { vm.chatInfoBranch.value != null }
        val branch = vm.chatInfoBranch.value!!.id
        val first = repo.getMessages("c").first()
        repo.addEditedVersion("c", first.id, "new", repo.getConversation("c")!!.historyRevision)
        var returned = false
        vm.saveChatInfo("c", branch, inn, ReplyLengthPreference.SHORT, null, TokenLimitField.AUTO) { returned = true }
        await { !vm.isSavingChatInfo.value }
        assertFalse(returned)
        assertNotNull(vm.error.value)
        assertEquals(SceneNote(), db.chatDao().getBranch(branch)!!.sceneNoteValue())
    }

    @Test fun automaticSummaryRetryRetainsSceneSnapshotAndSummaryIsClean() = runBlocking {
        save(inn)
        repeat(12) { repo.createInitialMessage("c", if (it % 2 == 0) "user" else "assistant", "history $it") }
        vm.selectConversation("c")
        await { vm.settings.value.provider == Provider.CUSTOM && vm.selectedConversation.value != null }
        lengthErrorOnce = true
        vm.setInput("next")
        vm.send()
        await { payloads.size >= 3 && !vm.isStreaming.value }
        assertNull(vm.error.value)
        val streams = payloads.filter { it.optBoolean("stream") }
        assertEquals(2, streams.size)
        streams.forEach { assertTrue(it.getJSONArray("messages").getJSONObject(0).getString("content").endsWith(inn.text)) }
        payloads.filter { !it.optBoolean("stream") }.forEach { assertFalse(it.toString().contains("Keep the secret")) }
    }

    @Test fun savingDuringGenerationIsBlockedAndStoppingKeepsNote() = runBlocking {
        save(inn)
        vm.selectConversation("c")
        await { vm.settings.value.provider == Provider.CUSTOM && vm.selectedConversation.value != null }
        val source = repo.getActiveBranch("c")!!.id
        val gate = CountDownLatch(1)
        streamGate = gate
        vm.setInput("next")
        vm.send()
        await { payloads.isNotEmpty() && vm.isStreaming.value }
        var returned = false
        vm.saveChatInfo("c", source, SceneNote("changed", true), ReplyLengthPreference.SHORT, null, TokenLimitField.AUTO) { returned = true }
        assertFalse(vm.isSavingChatInfo.value)
        assertFalse(returned)
        assertEquals(inn, db.chatDao().getBranch(source)!!.sceneNoteValue())
        vm.stopStreaming()
        gate.countDown()
        await { !vm.isStreaming.value }
        assertEquals(inn, repo.getActiveBranch("c")!!.sceneNoteValue())
    }

    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(8000) {
        while (!predicate()) delay(25)
    }
}
