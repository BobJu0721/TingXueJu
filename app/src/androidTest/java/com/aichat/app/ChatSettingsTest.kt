package com.aichat.app

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aichat.app.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ChatSettingsTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ConversationRepository
    private lateinit var vm: ChatViewModel
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val container = AppContainer(context, db)
        repo = container.conversationRepository
        vm = ChatViewModel(container)
        repo.upsertConversation(ConversationEntity("c", "test", 1, 1))
        repo.createInitialMessage("c", "user", "original needle")
        repo.upsertConversation(ConversationEntity("other", "other", 2, 2))
        repo.createInitialMessage("other", "user", "other needle")
        Unit
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); db.close() }

    @Test fun independentSavesPreserveOtherSettingsSummaryAndNoopRevision() = runBlocking {
        val baseline = repo.getChatSettings("c")!!
        val reply = ReplySettings(ReplyLengthPreference.DETAILED, 42, TokenLimitField.MAX_TOKENS)
        assertTrue(repo.saveReplySettings(baseline, reply))
        repo.updateBranchSummary(baseline.branchId, "saved summary", 1)
        val story = baseline.story.copy(sceneNote = SceneNote("雨夜\nKeep it", true))
        assertTrue(repo.saveStorySettings(baseline, story))
        val fresh = repo.getChatSettings("c")!!
        assertEquals(reply, fresh.reply)
        assertEquals(story, fresh.story)
        assertEquals("saved summary", repo.getActiveBranch("c")!!.summary)
        val revision = repo.getConversation("c")!!.historyRevision
        assertTrue(repo.saveStorySettings(fresh, story))
        assertTrue(repo.saveReplySettings(fresh, reply))
        assertEquals(revision, repo.getConversation("c")!!.historyRevision)
        assertEquals(1, repo.getActiveBranch("c")!!.lastUsedAt)
    }

    @Test fun deletedResourcesRejectEntireStoryAndKeepReply() = runBlocking {
        val baseline = repo.getChatSettings("c")!!
        for (draft in listOf(baseline.story.copy(sceneNote = SceneNote("new", true), personaId = "missing"),
            baseline.story.copy(sceneNote = SceneNote("new", true), worldSetIds = setOf("missing")))) {
            try { repo.saveStorySettings(baseline, draft); fail("must reject") } catch (_: IllegalArgumentException) { }
            assertEquals(baseline, repo.getChatSettings("c"))
        }
    }

    @Test fun appearanceWritesDoNotInvalidateSettingsOrOverwriteHistory() = runBlocking {
        val baseline = repo.getChatSettings("c")!!
        val revision = repo.getConversation("c")!!.historyRevision
        repo.replaceBackground("c", "path-one")
        repo.setBubbleOpacity("c", 0.6f)
        assertEquals(revision, repo.getConversation("c")!!.historyRevision)
        assertTrue(repo.saveStorySettings(baseline, baseline.story.copy(sceneNote = SceneNote("new", true))))
        assertEquals("path-one", repo.getConversation("c")!!.backgroundImagePath)
        assertEquals(0.6f, repo.getConversation("c")!!.messageBubbleOpacity)
        assertTrue(repo.saveReplySettings(baseline, baseline.reply.copy(maxTokens = 300)))
        assertEquals("new", repo.getActiveBranch("c")!!.sceneNote)
    }

    @Test fun conflictingSameFieldsAndChangedBranchAreRejected() = runBlocking {
        val baseline = repo.getChatSettings("c")!!
        assertTrue(repo.saveStorySettings(baseline, baseline.story.copy(sceneNote = SceneNote("new", true))))
        assertFalse(repo.saveStorySettings(baseline, baseline.story.copy(sceneNote = SceneNote("stale", true))))
        assertTrue(repo.saveReplySettings(baseline, baseline.reply.copy(maxTokens = 42)))
        assertFalse(repo.saveReplySettings(baseline, baseline.reply.copy(maxTokens = 43)))
        val fresh = repo.getChatSettings("c")!!
        db.chatDao().upsertBranch(repo.getActiveBranch("c")!!.copy(id = "new-branch"))
        db.chatDao().updateConversation(repo.getConversation("c")!!.copy(activeBranchId = "new-branch"))
        assertFalse(repo.saveStorySettings(fresh, fresh.story))
        // Reply preferences are conversation-scoped, so a branch switch alone is not a conflict.
        assertTrue(repo.saveReplySettings(fresh, fresh.reply.copy(maxTokens = 44)))
        assertFalse(repo.saveStorySettings(fresh.copy(branchId = repo.getActiveBranch("other")!!.id), fresh.story))
    }

    @Test fun deletedConversationCannotBeSavedOrRecreated() = runBlocking {
        val baseline = repo.getChatSettings("c")!!
        repo.deleteConversation(repo.getConversation("c")!!)
        assertFalse(repo.saveStorySettings(baseline, baseline.story))
        assertFalse(repo.saveReplySettings(baseline, baseline.reply))
        assertNull(repo.getConversation("c"))
    }

    @Test fun summaryResultCannotCrossBranchesOrHistoryRevisions() = runBlocking {
        val branch = repo.getActiveBranch("c")!!
        val revision = repo.getConversation("c")!!.historyRevision
        repo.updateBranchSummary(branch.id, "old", 0)
        repo.createInitialMessage("c", "user", "new history")
        assertFalse(repo.saveBranchSummary("c", branch.id, revision, "stale", 1))
        assertEquals("old", repo.getActiveBranch("c")!!.summary)
        assertFalse(repo.saveBranchSummary("other", branch.id, revision, "wrong", 1))
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(6000) { while (!predicate()) delay(25) }

    @Test fun searchStaysScopedAcrossConversationAndBranchChanges() = runBlocking {
        vm.selectConversation("c")
        vm.openSearch()
        vm.setSearchQuery("needle")
        await { vm.searchState.value.hits.size == 1 }
        assertEquals("c", vm.searchState.value.conversationId)
        assertTrue(vm.searchState.value.hits.single().snippet.contains("original"))
        val branch = repo.getActiveBranch("c")!!
        db.chatDao().upsertBranch(branch.copy(id = "empty-route"))
        db.chatDao().updateConversation(repo.getConversation("c")!!.copy(activeBranchId = "empty-route"))
        await { vm.searchState.value.branchId == "empty-route" && vm.searchState.value.hits.isEmpty() }
        assertEquals("needle", vm.searchState.value.query)
        vm.selectConversation("other")
        assertFalse(vm.searchExpanded.value)
        vm.openSearch()
        vm.setSearchQuery("needle")
        await { vm.searchState.value.conversationId == "other" && vm.searchState.value.hits.size == 1 }
        assertTrue(vm.searchState.value.hits.single().snippet.contains("other"))
    }

    @Test fun streamChangesRecomputeWithoutRequestingRepeatedScroll() = runBlocking {
        vm.selectConversation("c"); vm.openSearch(); vm.setSearchQuery("needle")
        await { vm.searchState.value.hits.size == 1 }
        val location = vm.searchState.value.locating
        val revision = vm.searchState.value.revision
        val message = repo.getMessages("c").single()
        val version = db.chatDao().getMessageVersion(message.currentVersionId)!!
        db.chatDao().updateMessageVersion(version.copy(content = "needle needle changed"))
        await { vm.searchState.value.revision > revision }
        assertEquals(location, vm.searchState.value.locating)
        vm.nextSearchResult()
        assertEquals(location + 1, vm.searchState.value.locating)
        vm.closeSearch(); vm.openSearch(); vm.setSearchQuery("changed")
        await { vm.searchState.value.hits.size == 1 }
        assertTrue(vm.searchState.value.locating > location + 1)
    }

    @Test fun backgroundFailureKeepsOldFileAndOrderedSelectionsKeepLatest() = runBlocking {
        vm.selectConversation("c")
        await { vm.selectedConversation.value?.id == "c" }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val old = java.io.File(context.cacheDir, "background-test-old").apply { writeText("old") }
        val first = java.io.File(context.cacheDir, "background-test-first").apply { writeText("first") }
        val last = java.io.File(context.cacheDir, "background-test-last").apply { writeText("last") }
        try {
            repo.replaceBackground("c", old.absolutePath)
            vm.setConversationBackground(android.net.Uri.fromFile(java.io.File(context.cacheDir, "missing-background-test")), "c")
            await { vm.error.value != null && !vm.isSavingAppearance.value }
            assertTrue(old.exists())
            assertEquals(old.absolutePath, repo.getConversation("c")!!.backgroundImagePath)
            vm.clearError()
            vm.setConversationBackground(android.net.Uri.fromFile(first), "c")
            vm.setConversationBackground(android.net.Uri.fromFile(last), "c")
            withTimeout(6000) {
                while (repo.getConversation("c")!!.backgroundImagePath.let { it == old.absolutePath || !java.io.File(it).exists() || java.io.File(it).readText() != "last" }) delay(25)
            }
            assertNull(vm.error.value)
            assertFalse(old.exists())
            java.io.File(repo.getConversation("c")!!.backgroundImagePath).delete()
        } finally { old.delete(); first.delete(); last.delete() }
        Unit
    }
}
