package com.aichat.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: String): MessageEntity?

    @Query("SELECT * FROM generation_contexts WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)")
    fun observeGenerationContexts(conversationId: String): Flow<List<GenerationContextEntity>>

    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND createdAt > :createdAt")
    suspend fun deleteMessagesAfter(conversationId: String, createdAt: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND createdAt >= :createdAt")
    suspend fun deleteMessagesAtOrAfter(conversationId: String, createdAt: Long)

    @Upsert
    suspend fun upsertGenerationContext(context: GenerationContextEntity)

    @Query("SELECT * FROM profiles WHERE type = :type ORDER BY updatedAt DESC")
    fun observeProfiles(type: ProfileType): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfile(id: String?): ProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: ProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: ProfileEntity)

    @Query("SELECT * FROM world_sets ORDER BY updatedAt DESC")
    fun observeWorldSets(): Flow<List<WorldSetEntity>>

    @Query("SELECT * FROM world_sets WHERE id = :id")
    suspend fun getWorldSet(id: String): WorldSetEntity?

    @Query("SELECT * FROM world_sets WHERE id IN (:ids)")
    suspend fun getWorldSets(ids: List<String>): List<WorldSetEntity>

    @Upsert
    suspend fun upsertWorldSet(worldSet: WorldSetEntity)

    @Delete
    suspend fun deleteWorldSet(worldSet: WorldSetEntity)

    @Query("SELECT * FROM world_entries WHERE worldSetId = :worldSetId ORDER BY sortOrder ASC, title ASC")
    fun observeWorldEntries(worldSetId: String): Flow<List<WorldEntryEntity>>

    @Query("SELECT * FROM world_entries WHERE worldSetId IN (:worldSetIds) ORDER BY sortOrder ASC, title ASC")
    suspend fun getWorldEntries(worldSetIds: List<String>): List<WorldEntryEntity>

    @Query("SELECT worldSetId, COUNT(*) AS count FROM world_entries GROUP BY worldSetId")
    fun observeWorldEntryCounts(): Flow<List<WorldEntryCount>>

    @Upsert
    suspend fun upsertWorldEntry(entry: WorldEntryEntity)

    @Delete
    suspend fun deleteWorldEntry(entry: WorldEntryEntity)

    @Query("SELECT worldSetId FROM conversation_world_sets WHERE conversationId = :conversationId")
    fun observeConversationWorldSetIds(conversationId: String): Flow<List<String>>

    @Query("SELECT worldSetId FROM conversation_world_sets WHERE conversationId = :conversationId")
    suspend fun getConversationWorldSetIds(conversationId: String): List<String>

    @Query("DELETE FROM conversation_world_sets WHERE conversationId = :conversationId")
    suspend fun clearConversationWorldSets(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addConversationWorldSets(links: List<ConversationWorldSetEntity>)
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ProfileEntity::class,
        WorldSetEntity::class,
        WorldEntryEntity::class,
        ConversationWorldSetEntity::class,
        GenerationContextEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN characterId TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN personaId TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN summaryThroughAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS profiles (id TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, summary TEXT NOT NULL, personality TEXT NOT NULL, background TEXT NOT NULL, exampleDialogue TEXT NOT NULL, greeting TEXT NOT NULL, alternateGreetingsJson TEXT NOT NULL, extraInstructions TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS world_sets (id TEXT NOT NULL, name TEXT NOT NULL, scanDepth INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS world_entries (id TEXT NOT NULL, worldSetId TEXT NOT NULL, title TEXT NOT NULL, keywordsJson TEXT NOT NULL, content TEXT NOT NULL, alwaysInclude INTEGER NOT NULL, enabled INTEGER NOT NULL, sortOrder INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(worldSetId) REFERENCES world_sets(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_world_entries_worldSetId ON world_entries(worldSetId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS conversation_world_sets (conversationId TEXT NOT NULL, worldSetId TEXT NOT NULL, PRIMARY KEY(conversationId, worldSetId), FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(worldSetId) REFERENCES world_sets(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_world_sets_conversationId ON conversation_world_sets(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_world_sets_worldSetId ON conversation_world_sets(worldSetId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS generation_contexts (messageId TEXT NOT NULL, activatedWorldEntriesJson TEXT NOT NULL, PRIMARY KEY(messageId), FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_generation_contexts_messageId ON generation_contexts(messageId)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN backgroundImagePath TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE world_sets ADD COLUMN overview TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai-chat.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
    }
}
