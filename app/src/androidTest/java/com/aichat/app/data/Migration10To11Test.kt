package com.aichat.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Migration10To11Test {
    @Test fun realSchemaUpgradePreservesBranchesHistorySummaryAndDefaults() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "scene-migration.db"
        context.deleteDatabase(name)
        var room = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val repository = ConversationRepository(room.chatDao())
            repository.upsertConversation(ConversationEntity("c", "chat", 1, 1))
            val message = repository.createInitialMessage("c", "user", "old text")!!
            val branch = repository.getActiveBranch("c")!!
            repository.updateBranchSummary(branch.id, "old summary", 1)
            repository.addEditedVersion("c", message.id, "new text", repository.getConversation("c")!!.historyRevision)
            val ids = repository.getBranches("c").map { it.id }
            room.close()
            // Removing the added columns yields the actual v10 schema, including its indexes.
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("ALTER TABLE conversation_branches DROP COLUMN sceneNote")
                old.execSQL("ALTER TABLE conversation_branches DROP COLUMN sceneNoteEnabled")
                // v12 才加的欄位也要拿掉，這樣才真的是 v10 的形狀。
                old.execSQL("ALTER TABLE messages DROP COLUMN kind")
                old.execSQL("ALTER TABLE messages DROP COLUMN authorName")
                old.execSQL("ALTER TABLE message_versions DROP COLUMN authorName")
                old.execSQL("ALTER TABLE message_versions DROP COLUMN authorCharacterId")
                old.execSQL("DROP TABLE room_master_table")
                old.version = 10
            }
            room = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12).build()
            // 這裡驗的是 10 → 11 的結果；開啟 Room 會繼續把鏈跑到目前版本。
            assertEquals(12, room.openHelper.readableDatabase.version)
            val upgraded = ConversationRepository(room.chatDao())
            assertEquals(ids, upgraded.getBranches("c").map { it.id })
            upgraded.getBranches("c").forEach { assertEquals(SceneNote(), it.sceneNoteValue()) }
            assertEquals("old summary", room.chatDao().getBranch(branch.id)!!.summary)
            assertEquals(listOf("old text", "new text"), upgraded.getMessageVersions(message.id).map { it.content })
            assertEquals(listOf("new text"), upgraded.getMessages("c").map { it.content })
            val currentBranch = upgraded.getActiveBranch("c")!!.id
            val note = SceneNote("雨夜客棧\nEnglish\n\n保留換行\n", true)
            assertTrue(upgraded.saveChatInfo("c", currentBranch, note, ReplyLengthPreference.DEFAULT, null, TokenLimitField.AUTO))
            room.close()
            room = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(note, ConversationRepository(room.chatDao()).getActiveBranch("c")!!.sceneNoteValue())
        } finally { room.close(); context.deleteDatabase(name) }
    }
}
