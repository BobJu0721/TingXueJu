package com.aichat.app

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.aichat.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import kotlin.system.measureTimeMillis

class BranchTreeDataTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ConversationRepository
    private lateinit var container: AppContainer
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        container = AppContainer(context, db); repo = container.conversationRepository
        repo.upsertConversation(ConversationEntity("c", "story", 1, 1))
        val parent = repo.getActiveBranch("c")!!
        db.chatDao().upsertBranch(parent.copy(id = "forest", sceneNote = "forest note", summary = "forest memory"))
        db.chatDao().upsertBranch(parent.copy(id = "sea", sourceBranchId = "forest", sceneNote = "sea note", summary = "sea memory"))
        repeat(65) { i ->
            val m = MessageEntity("m$i", "c", "user", "LEGACY WRONG", i.toLong(), sortOrder = i + 1L)
            db.chatDao().upsertMessage(m)
            for (b in listOf("forest", "sea")) {
                val v = MessageVersionEntity("$b-v$i", m.id, if (b == "forest") 1 else 2, "$b message $i", i.toLong())
                db.chatDao().insertMessageVersion(v)
                db.chatDao().upsertBranchMessage(BranchMessageEntity(b, m.id, v.id, i + 1L))
            }
        }
        db.chatDao().activateBranch("c", "forest", 2)
        Unit
    }
    @After fun cleanup() { db.close() }

    @Test fun projectionsUseRouteVersionsAndReadingNeverWrites() = runBlocking {
        val before = repo.getConversation("c")!!
        val snapshot = repo.getBranchTree("c")!!
        assertEquals(130, snapshot.rows.size)
        assertTrue(snapshot.rows.all { it.snippet.startsWith(it.branchId) })
        assertEquals(before, repo.getConversation("c"))
        val first = repo.getBranchPreview("c", "sea", "m40")!!
        assertEquals(20, first.start); assertEquals(45, first.rows.size)
        assertTrue(first.rows.all { it.content.startsWith("sea") })
        assertNull(repo.getBranchPreview("other", "sea", null))
    }

    @Test fun exactSwitchKeepsIdsHistoriesNotesAndSummaryAndNoopRevision() = runBlocking {
        val snapshot = repo.getBranchTree("c")!!
        val branches = repo.getBranches("c").map { it.id }
        assertEquals(BranchSwitchResult.SUCCESS, repo.switchToBranch("c", "sea", snapshot.activeBranchId, snapshot.revision))
        assertEquals("sea", repo.getActiveBranch("c")!!.id)
        assertEquals("sea note", repo.getActiveBranch("c")!!.sceneNote)
        assertEquals("sea memory", repo.getActiveConversation("c")!!.summary)
        assertEquals(branches, repo.getBranches("c").map { it.id })
        assertEquals(65, repo.getBranchMessages("forest").size)
        assertEquals(130, repo.getBranchTree("c")!!.rows.size)
        val now = repo.getConversation("c")!!
        assertEquals(BranchSwitchResult.SUCCESS, repo.switchToBranch("c", "sea", "sea", now.historyRevision))
        assertEquals(now, repo.getConversation("c"))
        repo.createInitialMessage("c", "user", "sea extension")
        assertEquals(65, repo.getBranchMessages("forest").size)
    }

    @Test fun staleCrossConversationMissingAndDraftTargetsAreRejected() = runBlocking {
        val s = repo.getBranchTree("c")!!
        assertEquals(BranchSwitchResult.HISTORY_CHANGED, repo.switchToBranch("c", "sea", "forest", s.revision - 1))
        assertEquals(BranchSwitchResult.TARGET_MISSING, repo.switchToBranch("other", "sea", "forest", s.revision))
        assertEquals(BranchSwitchResult.TARGET_MISSING, repo.switchToBranch("c", "missing", "forest", s.revision))
        val v = db.chatDao().getMessageVersion("sea-v1")!!
        db.chatDao().updateMessageVersion(v.copy(status = MessageVersionStatus.DRAFT))
        assertEquals(BranchSwitchResult.INCOMPLETE, repo.switchToBranch("c", "sea", "forest", s.revision))
        for (status in listOf(MessageVersionStatus.PARTIAL, MessageVersionStatus.INTERRUPTED)) {
            db.chatDao().updateMessageVersion(v.copy(status = status))
            assertEquals(BranchSwitchResult.SUCCESS, repo.switchToBranch("c", "sea", "forest", repo.getConversation("c")!!.historyRevision))
            db.chatDao().activateBranch("c", "forest", 3)
        }
    }

    @Test fun brokenVersionRejectedButDeletedMarkerNeverLeaks() = runBlocking {
        val row = repo.getBranchMessages("sea").first()
        db.chatDao().upsertBranchMessage(row.copy(versionId = "sea-v1"))
        val mismatch = repo.getBranchTree("c")!!.rows.first { it.branchId == "sea" && it.messageId == row.messageId }
        assertFalse(mismatch.valid)
        assertEquals("", mismatch.snippet)
        assertEquals("", repo.getBranchPreview("c", "sea", row.messageId)!!.rows.first().content)
        db.chatDao().upsertBranchMessage(row.copy(versionId = "missing"))
        val s = repo.getBranchTree("c")!!
        assertEquals(BranchSwitchResult.INCOMPLETE, repo.switchToBranch("c", "sea", "forest", s.revision))
        db.chatDao().upsertMessage(db.chatDao().getMessage(row.messageId)!!.copy(deleted = true, content = "SECRET"))
        assertEquals("", repo.getBranchTree("c")!!.rows.first { it.branchId == "sea" && it.messageId == row.messageId }.snippet)
        assertEquals("", repo.getBranchPreview("c", "sea", row.messageId)!!.rows.first().content)
        assertEquals(BranchSwitchResult.SUCCESS, repo.switchToBranch("c", "sea", "forest", s.revision))
    }

    @Test fun viewModelSwitchPreservesInputClosesSearchAndEmitsExactIdentity() = runBlocking {
        val vm = ChatViewModel(container)
        try {
            vm.selectConversation("c"); vm.setInput("unsent draft"); vm.openSearch(); vm.setSearchQuery("forest")
            val s = repo.getBranchTree("c")!!
            val result = CompletableDeferred<BranchSwitchResult>()
            withContext(Dispatchers.Main) { vm.switchToBranch(s, "sea", "m40") { result.complete(it) } }
            assertEquals(BranchSwitchResult.SUCCESS, withTimeout(5000) { result.await() })
            assertEquals("unsent draft", vm.input.value)
            assertFalse(vm.searchExpanded.value)
            assertEquals("sea", vm.branchLocation.value!!.branchId)
            assertEquals("m40", vm.branchLocation.value!!.messageId)
            vm.consumeBranchLocation("wrong")
            assertNotNull(vm.branchLocation.value)
            vm.consumeBranchLocation(vm.branchLocation.value!!.requestId)
            assertNull(vm.branchLocation.value)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun stalePreviewCannotOverwriteNewSelectionAndPrivateSnippetsHidden() = runBlocking {
        val m = db.chatDao().getMessage("m1")!!
        db.chatDao().upsertMessage(m.copy(kind = MessageKind.PRIVATE_NOTE))
        assertTrue(repo.getBranchTree("c")!!.rows.filter { it.messageId == "m1" }.all { it.snippet.isEmpty() })
        val vm = BranchTreeViewModel(container)
        try {
            vm.open("c")
            withTimeout(5000) { vm.state.first { !it.loading } }
            vm.select("forest", "m1"); vm.select("sea", "m40")
            val preview = withTimeout(5000) { vm.preview.first { it?.loading == false } }!!
            assertEquals("sea", preview.branchId)
            assertTrue(preview.page!!.rows.all { it.content.startsWith("sea") })
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun largeDatabaseSnapshotsBuildWithoutMainThreadWork() = runBlocking {
        for ((case, branches, length) in listOf(Triple("long", 1, 10_000), Triple("wide", 100, 1000), Triple("short", 1000, 3))) {
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    val sql = db.openHelper.writableDatabase
                    sql.execSQL("INSERT INTO conversations (id,title,createdAt,updatedAt,contextStartAt,summary,summaryThroughAt,backgroundImagePath,messageBubbleOpacity,reasoningMode,activeBranchId) VALUES (?, ?, 1, 1, 0, '', 0, '', 1, 'AUTO', ?)",
                        arrayOf(case, case, "$case-b0"))
                    val message = sql.compileStatement("INSERT INTO messages (id,conversationId,role,content,createdAt,sortOrder) VALUES (?, ?, 'user', '', 1, ?)")
                    val version = sql.compileStatement("INSERT INTO message_versions (id,messageId,versionNumber,content,createdAt,source,status) VALUES (?, ?, 1, 'bounded story text', 1, 'ORIGINAL', 'COMPLETE')")
                    repeat(length) { m ->
                        message.bindString(1, "$case-m$m"); message.bindString(2, case); message.bindLong(3, m + 1L); message.executeInsert()
                        version.bindString(1, "$case-v$m"); version.bindString(2, "$case-m$m"); version.executeInsert()
                    }
                    val branch = sql.compileStatement("INSERT INTO conversation_branches (id,conversationId,createdAt,lastUsedAt,summary) VALUES (?, ?, ?, 1, '')")
                    val link = sql.compileStatement("INSERT INTO branch_messages (branchId,messageId,versionId,sortOrder,excluded) VALUES (?, ?, ?, ?, 0)")
                    repeat(branches) { b ->
                        branch.bindString(1, "$case-b$b"); branch.bindString(2, case); branch.bindLong(3, b + 1L); branch.executeInsert()
                        repeat(length) { m ->
                            link.bindString(1, "$case-b$b"); link.bindString(2, "$case-m$m"); link.bindString(3, "$case-v$m"); link.bindLong(4, m + 1L); link.executeInsert()
                        }
                    }
                    message.close(); version.close(); branch.close(); link.close()
                }
            }
            withContext(Dispatchers.Default) {
                val elapsed = measureTimeMillis {
                    val snapshot = repo.getBranchTree(case)!!
                    assertEquals(branches * length, snapshot.rows.size)
                    val tree = com.aichat.app.domain.buildBranchTree(snapshot)
                    assertEquals(branches, tree.nodes.values.sumOf { it.terminalBranchIds.size })
                    com.aichat.app.ui.layoutBranchTree(tree.visible(tree.nodes.keys, snapshot.activeBranchId), tree.nodes.mapValues { 100f })
                }
                android.util.Log.i("BranchTreeBenchmark", "${android.os.Build.MODEL} API ${android.os.Build.VERSION.SDK_INT}: $branches routes ${branches * length} relations read+build+layout ${elapsed}ms")
            }
        }
    }
}
