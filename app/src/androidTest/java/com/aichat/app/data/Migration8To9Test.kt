package com.aichat.app.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class Migration8To9Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-8-9.db"

    @Before fun before() { context.deleteDatabase(name) }
    @After fun after() { context.deleteDatabase(name) }

    @Test fun migrationPreservesMessagesVersionsContextsAndStableOrder() {
        open(8, object : SupportSQLiteOpenHelper.Callback(8) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, contextStartAt INTEGER NOT NULL, characterId TEXT, personaId TEXT, summary TEXT NOT NULL, summaryThroughAt INTEGER NOT NULL, backgroundImagePath TEXT NOT NULL, messageBubbleOpacity REAL NOT NULL, reasoningMode TEXT NOT NULL)")
                db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE generation_contexts (messageId TEXT NOT NULL PRIMARY KEY, activatedWorldEntriesJson TEXT NOT NULL, reasoningContent TEXT NOT NULL, outputTokenCount INTEGER, tokenCountEstimated INTEGER NOT NULL DEFAULT 1, generationElapsedMillis INTEGER, FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE)")
                db.execSQL("INSERT INTO conversations VALUES ('c','chat',100,100,100,NULL,NULL,'summary',100,'',1.0,'AUTO')")
                db.execSQL("INSERT INTO messages VALUES ('m1','c','user','question',100)")
                db.execSQL("INSERT INTO messages VALUES ('m2','c','assistant','answer',100)")
                db.execSQL("INSERT INTO generation_contexts VALUES ('m2','[\"world\"]','thinking',42,0,2000)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).close()

        val helper = open(9, object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_8_9.migrate(db)
            }
        })
        val db = helper.writableDatabase
        db.query("SELECT id, currentVersionId, sortOrder, excluded FROM messages ORDER BY sortOrder").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("m1", cursor.getString(0))
            assertEquals("m1-v1", cursor.getString(1))
            assertEquals(1L, cursor.getLong(2))
            assertEquals(0, cursor.getInt(3))
            assertTrue(cursor.moveToNext())
            assertEquals("m2", cursor.getString(0))
            assertEquals(2L, cursor.getLong(2))
        }
        db.query("SELECT messageId, content, versionNumber, status FROM message_versions ORDER BY messageId").use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("question", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals("COMPLETE", cursor.getString(3))
        }
        db.query("SELECT versionId, reasoningContent, outputTokenCount FROM generation_contexts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("m2-v1", cursor.getString(0))
            assertEquals("thinking", cursor.getString(1))
            assertEquals(42L, cursor.getLong(2))
        }
        db.query("SELECT contextStartOrder, summaryThroughOrder, replyLengthPreference, tokenLimitField, historyRevision FROM conversations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(2L, cursor.getLong(1))
            assertEquals("DEFAULT", cursor.getString(2))
            assertEquals("AUTO", cursor.getString(3))
            assertEquals(0L, cursor.getLong(4))
        }
        helper.close()
    }

    @Test fun versionTransactionsTruncateAtomicallyAndFailedDraftRollsBack() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = ConversationRepository(db.chatDao())
        repository.upsertConversation(ConversationEntity("c", "chat", 1, 1))
        val first = repository.createInitialMessage("c", "user", "one", 10)
        repository.createInitialMessage("c", "assistant", "two", 20)
        assertEquals(1, repository.countMessagesAfter(first))

        val revision = repository.getConversation("c")!!.historyRevision
        assertTrue(repository.addEditedVersion(first, "one edited", revision))
        assertEquals(listOf("one edited"), repository.getMessages("c").map { it.content })
        assertEquals(2, repository.getMessageVersions(first.id).size)

        val current = repository.getMessage(first.id)!!
        val base = repository.getMessageVersion(current.currentVersionId)!!
        val beforeDraft = current.currentVersionId
        val (_, draft) = repository.createDraft("c", MessageVersionSource.REGENERATED, current, base, "")
        repository.updateDraft(draft.copy(content = "failed preview"), GenerationContextEntity(draft.id, reasoningContent = "temp"))
        repository.rollbackDraft(draft.copy(content = "failed preview"), newMessage = false)
        val restored = repository.getMessage(first.id)!!
        assertEquals(beforeDraft, restored.currentVersionId)
        assertEquals("one edited", restored.content)
        assertNull(repository.getMessageVersion(draft.id))

        repository.setMessageExcluded(restored, true)
        assertTrue(repository.getMessage(first.id)!!.excluded)
        repository.deleteMessage(repository.getMessage(first.id)!!)
        assertTrue(repository.getMessages("c").isEmpty())
        db.close()
    }

    @Test fun interruptedDraftRemainsAvailableWithoutReplacingOriginalSelection() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = ConversationRepository(db.chatDao())
        repository.upsertConversation(ConversationEntity("c", "chat", 1, 1))
        val original = repository.createInitialMessage("c", "assistant", "answer")
        val (_, draft) = repository.createDraft(
            "c", MessageVersionSource.CONTINUATION, original,
            repository.getMessageVersion(original.currentVersionId), "answer",
        )
        repository.updateDraft(draft.copy(content = "answer more"), null)
        repository.recoverInterruptedDrafts()
        assertEquals(original.currentVersionId, repository.getMessage(original.id)?.currentVersionId)
        assertEquals("answer", repository.getMessage(original.id)?.content)
        assertEquals(MessageVersionStatus.INTERRUPTED, repository.getMessageVersion(draft.id)?.status)
        assertEquals("answer more", repository.getMessageVersion(draft.id)?.content)
        db.close()
    }

    private fun open(version: Int, callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build()
        return FrameworkSQLiteOpenHelperFactory().create(config).also { it.writableDatabase }
    }
}
