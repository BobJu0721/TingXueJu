package com.aichat.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v9 → v10 遷移。
 *
 * 這裡刻意用「真正的 v9 DDL」（取自實機 ai-chat.db 的 sqlite_master）建立舊資料庫，再用
 * Room 開啟，因此 Room 的 schema 驗證會一併執行：手寫的 CREATE TABLE 只要與實體推導出的
 * schema 有任何差異，`onValidateSchema` 就會直接讓測試失敗。
 */
@RunWith(AndroidJUnit4::class)
class Migration9To10Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-9-10.db"
    private lateinit var room: AppDatabase

    @Before fun before() { context.deleteDatabase(name) }

    @After fun after() {
        if (::room.isInitialized) room.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migratesToBranchesWithoutLosingVersionsContextsOrSummary() {
        createV9Database()

        room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12)
            .build()
        val db = room.openHelper.readableDatabase

        // 開啟 Room 會把遷移鏈一路跑到目前版本，後續版本的欄位一併驗證。
        assertEquals("遷移後資料庫版本應為 12", 12, db.version)

        // 每個聊天室都有初始路線，沒有訊息的聊天室也一樣。
        db.query("SELECT id, activeBranchId FROM conversations ORDER BY id").use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("c", cursor.getString(0))
            assertEquals("branch-c", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("c2", cursor.getString(0))
            assertEquals("branch-c2", cursor.getString(1))
        }

        // 原本的摘要與裁切狀態搬到初始路線。
        db.query("SELECT summary, summaryThroughOrder, contextStartOrder, legacyIncomplete FROM conversation_branches WHERE id = 'branch-c'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("舊摘要", cursor.getString(0))
            assertEquals(2L, cursor.getLong(1))
            assertEquals(1L, cursor.getLong(2))
            assertEquals(0, cursor.getInt(3))
        }

        // 初始路線按既有順序引用目前選中的版本。
        db.query("SELECT messageId, versionId, sortOrder FROM branch_messages WHERE branchId = 'branch-c' ORDER BY sortOrder").use { cursor ->
            assertEquals(3, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("m1", cursor.getString(0))
            assertEquals("m1v1", cursor.getString(1))
            assertEquals(1L, cursor.getLong(2))
            assertTrue(cursor.moveToNext())
            assertEquals("m2", cursor.getString(0))
            assertEquals("m2v1", cursor.getString(1))
            assertEquals(2L, cursor.getLong(2))
            assertTrue(cursor.moveToNext())
            assertEquals("m3", cursor.getString(0))
            assertEquals("m3v1", cursor.getString(1))
            assertEquals(3L, cursor.getLong(2))
        }

        // 無訊息的聊天室有有效的空路線。
        db.query("SELECT COUNT(*) FROM branch_messages WHERE branchId = 'branch-c2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        // 未選版本建立相容路線：共同前文 + 該版本，不接上現行版本的後續。
        db.query(
            "SELECT conversationId, sourceBranchId, forkMessageId, forkVersionId, summary, legacyIncomplete " +
                "FROM conversation_branches WHERE id = 'legacy-m2-m2v2'",
        ).use { cursor ->
            assertTrue("未選版本應建立相容路線", cursor.moveToFirst())
            assertEquals("c", cursor.getString(0))
            assertEquals("branch-c", cursor.getString(1))
            assertEquals("m2", cursor.getString(2))
            assertEquals("m2v2", cursor.getString(3))
            assertEquals("", cursor.getString(4))
            assertEquals(1, cursor.getInt(5))
        }
        db.query("SELECT messageId, versionId, sortOrder FROM branch_messages WHERE branchId = 'legacy-m2-m2v2' ORDER BY sortOrder").use { cursor ->
            assertEquals("相容路線只到分岔版本為止", 2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("m1", cursor.getString(0))
            assertEquals("m1v1", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("m2", cursor.getString(0))
            assertEquals("m2v2", cursor.getString(1))
        }

        // 既有的正文、版本與生成統計完整保留。
        db.query("SELECT COUNT(*) FROM message_versions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("所有版本都要保留", 4, cursor.getInt(0))
        }
        db.query("SELECT activatedWorldEntriesJson, reasoningContent, outputTokenCount, tokenCountEstimated, generationElapsedMillis FROM generation_contexts WHERE versionId = 'm2v1'").use { cursor ->
            assertTrue("生成資料要保留", cursor.moveToFirst())
            assertEquals("[\"world\"]", cursor.getString(0))
            assertEquals("thinking", cursor.getString(1))
            assertEquals(42L, cursor.getLong(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals(2000L, cursor.getLong(4))
        }
        db.query("SELECT outputTokenCount FROM generation_contexts WHERE versionId = 'm2v2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7L, cursor.getLong(0))
        }

        // messages 的 legacy 欄位原封不動，刪除標記預設為 0。
        db.query("SELECT id, currentVersionId, excluded, deleted FROM messages ORDER BY sortOrder").use { cursor ->
            assertEquals(3, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("m1v1", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals(0, cursor.getInt(3))
        }
    }

    private fun createV9Database() {
        val file = File(context.getDatabasePath(name).absolutePath)
        file.parentFile?.mkdirs()
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        V9_SCHEMA.forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO conversations (id, title, createdAt, updatedAt, contextStartAt, characterId, personaId, summary, " +
                "summaryThroughAt, backgroundImagePath, messageBubbleOpacity, reasoningMode, contextStartOrder, " +
                "summaryThroughOrder, replyLengthPreference, maxOutputTokens, tokenLimitField, historyRevision) " +
                "VALUES ('c','chat',100,200,100,NULL,NULL,'舊摘要',111,'',1.0,'AUTO',1,2,'DEFAULT',NULL,'AUTO',5)",
        )
        db.execSQL(
            "INSERT INTO conversations (id, title, createdAt, updatedAt, contextStartAt, characterId, personaId, summary, " +
                "summaryThroughAt, backgroundImagePath, messageBubbleOpacity, reasoningMode, contextStartOrder, " +
                "summaryThroughOrder, replyLengthPreference, maxOutputTokens, tokenLimitField, historyRevision) " +
                "VALUES ('c2','empty',300,300,0,NULL,NULL,'',0,'',1.0,'AUTO',0,0,'DEFAULT',NULL,'AUTO',0)",
        )
        db.execSQL("INSERT INTO messages VALUES ('m1','c','user','question',100,'m1v1',1,0)")
        db.execSQL("INSERT INTO messages VALUES ('m2','c','assistant','original',100,'m2v1',2,0)")
        db.execSQL("INSERT INTO messages VALUES ('m3','c','user','follow up',100,'m3v1',3,1)")
        db.execSQL("INSERT INTO message_versions VALUES ('m1v1','m1',1,'question',100,'ORIGINAL',NULL,'COMPLETE')")
        db.execSQL("INSERT INTO message_versions VALUES ('m2v1','m2',1,'original',100,'ORIGINAL',NULL,'COMPLETE')")
        db.execSQL("INSERT INTO message_versions VALUES ('m2v2','m2',2,'regenerated',150,'REGENERATED','m2v1','COMPLETE')")
        db.execSQL("INSERT INTO message_versions VALUES ('m3v1','m3',1,'follow up',100,'ORIGINAL',NULL,'COMPLETE')")
        db.execSQL(
            "INSERT INTO generation_contexts (versionId, activatedWorldEntriesJson, reasoningContent, outputTokenCount, " +
                "tokenCountEstimated, generationElapsedMillis) VALUES ('m2v1','[\"world\"]','thinking',42,0,2000)",
        )
        db.execSQL(
            "INSERT INTO generation_contexts (versionId, activatedWorldEntriesJson, reasoningContent, outputTokenCount, " +
                "tokenCountEstimated, generationElapsedMillis) VALUES ('m2v2','[]','',7,0,900)",
        )
        db.version = 9
        db.close()
        assertNotNull("v9 測試資料庫應建立成功", file)
    }

    internal companion object {
        /** 取自實機 v9 `ai-chat.db` 的 sqlite_master。 */
        val V9_SCHEMA = listOf(
            "CREATE TABLE `conversations` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `contextStartAt` INTEGER NOT NULL, `characterId` TEXT, `personaId` TEXT, " +
                "`summary` TEXT NOT NULL, `summaryThroughAt` INTEGER NOT NULL, `backgroundImagePath` TEXT NOT NULL, " +
                "`messageBubbleOpacity` REAL NOT NULL, reasoningMode TEXT NOT NULL DEFAULT 'AUTO', " +
                "contextStartOrder INTEGER NOT NULL DEFAULT 0, summaryThroughOrder INTEGER NOT NULL DEFAULT 0, " +
                "replyLengthPreference TEXT NOT NULL DEFAULT 'DEFAULT', maxOutputTokens INTEGER, " +
                "tokenLimitField TEXT NOT NULL DEFAULT 'AUTO', historyRevision INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            "CREATE TABLE `messages` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, currentVersionId TEXT NOT NULL DEFAULT '', " +
                "sortOrder INTEGER NOT NULL DEFAULT 0, excluded INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE message_versions (id TEXT NOT NULL, messageId TEXT NOT NULL, versionNumber INTEGER NOT NULL, " +
                "content TEXT NOT NULL, createdAt INTEGER NOT NULL, source TEXT NOT NULL, baseVersionId TEXT, " +
                "status TEXT NOT NULL, PRIMARY KEY(id), " +
                "FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE TABLE \"generation_contexts\" (versionId TEXT NOT NULL, activatedWorldEntriesJson TEXT NOT NULL, " +
                "reasoningContent TEXT NOT NULL, outputTokenCount INTEGER, " +
                "tokenCountEstimated INTEGER NOT NULL DEFAULT 1, generationElapsedMillis INTEGER, PRIMARY KEY(versionId), " +
                "FOREIGN KEY(versionId) REFERENCES message_versions(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE TABLE `profiles` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                "`personality` TEXT NOT NULL, `background` TEXT NOT NULL, `exampleDialogue` TEXT NOT NULL, `greeting` TEXT NOT NULL, " +
                "`alternateGreetingsJson` TEXT NOT NULL, `extraInstructions` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `world_sets` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `scanDepth` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `overview` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE `world_entries` (`id` TEXT NOT NULL, `worldSetId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`keywordsJson` TEXT NOT NULL, `content` TEXT NOT NULL, `alwaysInclude` INTEGER NOT NULL, " +
                "`enabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`worldSetId`) REFERENCES `world_sets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE `conversation_world_sets` (`conversationId` TEXT NOT NULL, `worldSetId` TEXT NOT NULL, " +
                "PRIMARY KEY(`conversationId`, `worldSetId`), " +
                "FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`worldSetId`) REFERENCES `world_sets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX `index_conversation_world_sets_conversationId` ON `conversation_world_sets` (`conversationId`)",
            "CREATE INDEX `index_conversation_world_sets_worldSetId` ON `conversation_world_sets` (`worldSetId`)",
            "CREATE INDEX index_generation_contexts_versionId ON generation_contexts(versionId)",
            "CREATE INDEX index_message_versions_messageId ON message_versions(messageId)",
            "CREATE UNIQUE INDEX index_message_versions_messageId_versionNumber ON message_versions(messageId, versionNumber)",
            "CREATE INDEX `index_messages_conversationId` ON `messages` (`conversationId`)",
            "CREATE UNIQUE INDEX index_messages_conversationId_sortOrder ON messages(conversationId, sortOrder)",
            "CREATE INDEX `index_world_entries_worldSetId` ON `world_entries` (`worldSetId`)",
        )
    }
}
