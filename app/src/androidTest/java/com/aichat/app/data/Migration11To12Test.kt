package com.aichat.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v11 → v12：訊息種類與手寫角色署名。
 *
 * 這裡刻意先建立真正的 v9 資料庫、跑過 9→10 與 10→11 兩段真實遷移得到 v11，再用 Room 開啟
 * 讓 11→12 執行——因此 Room 的 schema 驗證會一併跑，手寫的欄位只要與實體推導出的 schema
 * 有差異就會失敗。
 */
@RunWith(AndroidJUnit4::class)
class Migration11To12Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-11-12.db"
    private lateinit var room: AppDatabase

    @Before fun before() { context.deleteDatabase(name) }

    @After fun after() {
        if (::room.isInitialized) room.close()
        context.deleteDatabase(name)
    }

    @Test fun existingMessagesBecomeChatAndContentSurvives() {
        createV9WithContent()
        open(11, object : SupportSQLiteOpenHelper.Callback(11) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_9_10.migrate(db)
                AppDatabase.MIGRATION_10_11.migrate(db)
            }
        }).close()

        room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_11_12)
            .build()
        val db = room.openHelper.readableDatabase

        assertEquals("遷移後資料庫版本應為 12", 12, db.version)

        // 既有訊息全部變成 CHAT，正文與原本的 user／assistant 都保留。
        db.query("SELECT id, role, content, kind, authorName FROM messages ORDER BY sortOrder").use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("m1", cursor.getString(0))
            assertEquals("user", cursor.getString(1))
            assertEquals("question", cursor.getString(2))
            assertEquals("CHAT", cursor.getString(3))
            assertEquals(null, cursor.getString(4))
            assertTrue(cursor.moveToNext())
            assertEquals("assistant", cursor.getString(1))
            assertEquals("original", cursor.getString(2))
            assertEquals("CHAT", cursor.getString(3))
        }

        // 版本與生成資料不受影響，新的署名欄位預設為空。
        db.query("SELECT id, content, authorName, authorCharacterId FROM message_versions ORDER BY id").use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("m1v1", cursor.getString(0))
            assertEquals("question", cursor.getString(1))
            assertEquals(null, cursor.getString(2))
            assertEquals(null, cursor.getString(3))
        }
        db.query("SELECT reasoningContent FROM generation_contexts WHERE versionId = 'm2v1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("thinking", cursor.getString(0))
        }

        // 既有路線與分支關聯完整保留。
        db.query("SELECT messageId, versionId FROM branch_messages WHERE branchId = 'branch-c' ORDER BY sortOrder").use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("m1", cursor.getString(0))
            assertEquals("m1v1", cursor.getString(1))
        }
    }

    @Test fun authoredKindAndAuthorNameRoundTripThroughRoom() {
        createV9WithContent()
        open(11, object : SupportSQLiteOpenHelper.Callback(11) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                AppDatabase.MIGRATION_9_10.migrate(db)
                AppDatabase.MIGRATION_10_11.migrate(db)
            }
        }).close()

        room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_11_12)
            .build()
        val dao = room.chatDao()

        kotlinx.coroutines.runBlocking {
            val message = MessageEntity(
                id = "n1",
                conversationId = "c",
                role = "user",
                content = "三日後，眾人抵達山門。",
                createdAt = 500,
                currentVersionId = "n1v1",
                sortOrder = 3,
                kind = MessageKind.NARRATION,
                authorName = null,
            )
            val version = MessageVersionEntity(
                id = "n1v1",
                messageId = "n1",
                versionNumber = 1,
                content = message.content,
                createdAt = 500,
            )
            dao.upsertMessage(message)
            dao.insertMessageVersion(version)
            dao.upsertBranchMessage(BranchMessageEntity("branch-c", "n1", "n1v1", 3, false))

            val stored = dao.getMessage("n1")!!
            assertEquals(MessageKind.NARRATION, stored.kind)
            assertEquals(MessageKind.NARRATION, dao.getRouteMessages("c").last().message.kind)

            // 手寫角色台詞的署名存在版本上，並會被路線查詢帶上來。
            val authored = MessageEntity(
                id = "a1",
                conversationId = "c",
                role = "user",
                content = "樓上的房間，今晚不能進去。",
                createdAt = 600,
                currentVersionId = "a1v1",
                sortOrder = 4,
                kind = MessageKind.AUTHORED_CHARACTER,
            )
            dao.upsertMessage(authored)
            dao.insertMessageVersion(
                MessageVersionEntity(
                    id = "a1v1",
                    messageId = "a1",
                    versionNumber = 1,
                    content = authored.content,
                    createdAt = 600,
                    authorName = "掌櫃",
                    authorCharacterId = "char-1",
                ),
            )
            dao.upsertBranchMessage(BranchMessageEntity("branch-c", "a1", "a1v1", 4, false))

            val resolved = dao.getRouteMessages("c").last().resolved()
            assertEquals(MessageKind.AUTHORED_CHARACTER, resolved.kind)
            assertEquals("掌櫃", resolved.authorName)
            assertEquals("【角色台詞：掌櫃】\n樓上的房間，今晚不能進去。", resolved.toPromptContent())
        }
    }

    private fun createV9WithContent() {
        val file = File(context.getDatabasePath(name).absolutePath)
        file.parentFile?.mkdirs()
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        Migration9To10Test.V9_SCHEMA.forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO conversations (id, title, createdAt, updatedAt, contextStartAt, characterId, personaId, summary, " +
                "summaryThroughAt, backgroundImagePath, messageBubbleOpacity, reasoningMode, contextStartOrder, " +
                "summaryThroughOrder, replyLengthPreference, maxOutputTokens, tokenLimitField, historyRevision) " +
                "VALUES ('c','chat',100,200,0,NULL,NULL,'',0,'',1.0,'AUTO',0,0,'DEFAULT',NULL,'AUTO',1)",
        )
        db.execSQL("INSERT INTO messages VALUES ('m1','c','user','question',100,'m1v1',1,0)")
        db.execSQL("INSERT INTO messages VALUES ('m2','c','assistant','original',100,'m2v1',2,0)")
        db.execSQL("INSERT INTO message_versions VALUES ('m1v1','m1',1,'question',100,'ORIGINAL',NULL,'COMPLETE')")
        db.execSQL("INSERT INTO message_versions VALUES ('m2v1','m2',1,'original',100,'ORIGINAL',NULL,'COMPLETE')")
        db.execSQL(
            "INSERT INTO generation_contexts (versionId, activatedWorldEntriesJson, reasoningContent, outputTokenCount, " +
                "tokenCountEstimated, generationElapsedMillis) VALUES ('m2v1','[]','thinking',42,0,2000)",
        )
        db.version = 9
        db.close()
    }

    private fun open(version: Int, callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build()
        return FrameworkSQLiteOpenHelperFactory().create(config).also { it.writableDatabase }
    }
}
