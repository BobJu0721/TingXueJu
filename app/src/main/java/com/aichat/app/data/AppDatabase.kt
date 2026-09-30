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
import androidx.room.Transaction
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

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sortOrder ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sortOrder ASC")
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: String): MessageEntity?

    @Query("SELECT message_versions.* FROM message_versions JOIN messages ON messages.id = message_versions.messageId WHERE messages.conversationId = :conversationId ORDER BY messages.sortOrder, message_versions.versionNumber")
    fun observeMessageVersions(conversationId: String): Flow<List<MessageVersionEntity>>

    @Query("SELECT * FROM message_versions WHERE messageId = :messageId ORDER BY versionNumber")
    suspend fun getMessageVersions(messageId: String): List<MessageVersionEntity>

    @Query("SELECT * FROM message_versions WHERE id = :id")
    suspend fun getMessageVersion(id: String): MessageVersionEntity?

    @Query("SELECT * FROM message_versions WHERE status = 'DRAFT'")
    suspend fun getDraftVersions(): List<MessageVersionEntity>

    @Query("SELECT generation_contexts.* FROM generation_contexts JOIN messages ON messages.currentVersionId = generation_contexts.versionId WHERE messages.conversationId = :conversationId")
    fun observeGenerationContexts(conversationId: String): Flow<List<GenerationContextEntity>>

    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)

    @Upsert
    suspend fun upsertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM messages WHERE conversationId = :conversationId")
    suspend fun nextMessageOrder(conversationId: String): Long

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND sortOrder > :sortOrder")
    suspend fun countMessagesAfter(conversationId: String, sortOrder: Long): Int

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND sortOrder > :sortOrder AND id != :keepMessageId")
    suspend fun deleteMessagesAfterOrder(conversationId: String, sortOrder: Long, keepMessageId: String = "")

    @Query("UPDATE messages SET currentVersionId = :versionId, content = :content WHERE id = :messageId")
    suspend fun selectMessageVersion(messageId: String, versionId: String, content: String)

    @Query("UPDATE messages SET excluded = :excluded WHERE id = :messageId")
    suspend fun setMessageExcluded(messageId: String, excluded: Boolean)

    @Insert
    suspend fun insertMessageVersion(version: MessageVersionEntity)

    @Update
    suspend fun updateMessageVersion(version: MessageVersionEntity)

    @Query("DELETE FROM message_versions WHERE id = :versionId")
    suspend fun deleteMessageVersion(versionId: String)

    @Upsert
    suspend fun upsertGenerationContext(context: GenerationContextEntity)

    @Transaction
    suspend fun createMessageWithVersion(message: MessageEntity, version: MessageVersionEntity) {
        upsertMessage(message.copy(currentVersionId = version.id, content = version.content))
        insertMessageVersion(version)
    }

    @Transaction
    suspend fun createCommittedMessageWithVersion(message: MessageEntity, version: MessageVersionEntity, now: Long): MessageEntity {
        val ordered = message.copy(sortOrder = nextMessageOrder(message.conversationId))
        createMessageWithVersion(ordered, version)
        getConversation(message.conversationId)?.let {
            updateConversation(it.copy(updatedAt = now, historyRevision = it.historyRevision + 1))
        }
        return ordered
    }

    @Transaction
    suspend fun startDraft(message: MessageEntity?, version: MessageVersionEntity): MessageEntity? {
        if (message == null && getMessage(version.messageId)?.currentVersionId != version.baseVersionId) {
            error("The selected version changed before generation started")
        }
        val ordered = message?.copy(
            currentVersionId = version.id,
            content = version.content,
            sortOrder = nextMessageOrder(message.conversationId),
        )
        if (ordered != null) upsertMessage(ordered)
        insertMessageVersion(version)
        selectMessageVersion(version.messageId, version.id, version.content)
        return ordered
    }

    @Transaction
    suspend fun updateDraft(version: MessageVersionEntity, context: GenerationContextEntity?) {
        if (getMessage(version.messageId)?.currentVersionId != version.id) return
        updateMessageVersion(version)
        selectMessageVersion(version.messageId, version.id, version.content)
        if (context != null) upsertGenerationContext(context)
    }

    @Transaction
    suspend fun rollbackDraft(version: MessageVersionEntity, baseContent: String?, newMessage: Boolean) {
        val current = getMessage(version.messageId) ?: return
        if (newMessage) {
            if (current.currentVersionId == version.id) deleteMessage(version.messageId)
            else deleteMessageVersion(version.id)
        } else {
            val baseId = version.baseVersionId ?: return
            if (current.currentVersionId == version.id) {
                selectMessageVersion(version.messageId, baseId, baseContent.orEmpty())
            }
            deleteMessageVersion(version.id)
        }
    }

    @Transaction
    suspend fun recoverDraft(version: MessageVersionEntity, base: MessageVersionEntity?) {
        updateMessageVersion(version.copy(status = MessageVersionStatus.INTERRUPTED))
        if (base != null) selectMessageVersion(version.messageId, base.id, base.content)
    }

    @Transaction
    suspend fun applyVersionChange(
        conversationId: String,
        message: MessageEntity,
        version: MessageVersionEntity,
        insertVersion: Boolean,
        expectedRevision: Long,
        deleteAfter: Boolean,
        now: Long,
    ): Boolean {
        val conversation = getConversation(conversationId) ?: return false
        if (conversation.historyRevision != expectedRevision) return false
        val current = getMessage(message.id) ?: return false
        if (current.conversationId != conversationId || current.currentVersionId != message.currentVersionId ||
            version.messageId != message.id || version.status == MessageVersionStatus.DRAFT
        ) return false
        if (insertVersion) insertMessageVersion(version)
        selectMessageVersion(message.id, version.id, version.content)
        if (deleteAfter) deleteMessagesAfterOrder(conversationId, message.sortOrder, message.id)
        updateConversation(
            conversation.copy(
                updatedAt = now,
                historyRevision = conversation.historyRevision + 1,
                summary = if (message.sortOrder <= conversation.summaryThroughOrder) "" else conversation.summary,
                summaryThroughAt = if (message.sortOrder <= conversation.summaryThroughOrder) 0 else conversation.summaryThroughAt,
                summaryThroughOrder = if (message.sortOrder <= conversation.summaryThroughOrder) 0 else conversation.summaryThroughOrder,
                contextStartAt = if (message.sortOrder < conversation.contextStartOrder) 0 else conversation.contextStartAt,
                contextStartOrder = if (message.sortOrder < conversation.contextStartOrder) 0 else conversation.contextStartOrder,
            ),
        )
        return true
    }

    @Transaction
    suspend fun commitDraft(
        conversationId: String,
        message: MessageEntity,
        version: MessageVersionEntity,
        context: GenerationContextEntity?,
        expectedRevision: Long,
        cutoffOrder: Long?,
        temporarySummary: ConversationEntity?,
        now: Long,
    ): Boolean {
        val conversation = getConversation(conversationId) ?: return false
        if (conversation.historyRevision != expectedRevision) return false
        if (version.messageId != message.id || getMessage(message.id)?.currentVersionId != version.id) return false
        updateMessageVersion(version)
        selectMessageVersion(message.id, version.id, version.content)
        if (context != null) upsertGenerationContext(context)
        if (cutoffOrder != null) deleteMessagesAfterOrder(conversationId, cutoffOrder, message.id)
        val invalidatesSummary = cutoffOrder != null && cutoffOrder <= conversation.summaryThroughOrder
        val resetsContext = cutoffOrder != null && cutoffOrder < conversation.contextStartOrder
        updateConversation(
            conversation.copy(
                updatedAt = now,
                historyRevision = conversation.historyRevision + 1,
                summary = temporarySummary?.summary ?: if (invalidatesSummary) "" else conversation.summary,
                summaryThroughAt = temporarySummary?.summaryThroughAt ?: if (invalidatesSummary) 0 else conversation.summaryThroughAt,
                summaryThroughOrder = temporarySummary?.summaryThroughOrder ?: if (invalidatesSummary) 0 else conversation.summaryThroughOrder,
                contextStartAt = if (resetsContext) 0 else conversation.contextStartAt,
                contextStartOrder = if (resetsContext) 0 else conversation.contextStartOrder,
            ),
        )
        return true
    }

    @Transaction
    suspend fun deleteMessageAndInvalidate(message: MessageEntity, now: Long) {
        val conversation = getConversation(message.conversationId) ?: return
        deleteMessage(message.id)
        updateConversation(
            conversation.copy(
                updatedAt = now,
                historyRevision = conversation.historyRevision + 1,
                summary = if (message.sortOrder <= conversation.summaryThroughOrder) "" else conversation.summary,
                summaryThroughAt = if (message.sortOrder <= conversation.summaryThroughOrder) 0 else conversation.summaryThroughAt,
                summaryThroughOrder = if (message.sortOrder <= conversation.summaryThroughOrder) 0 else conversation.summaryThroughOrder,
            ),
        )
    }

    @Transaction
    suspend fun setExcludedAndInvalidate(message: MessageEntity, excluded: Boolean, now: Long) {
        val conversation = getConversation(message.conversationId) ?: return
        setMessageExcluded(message.id, excluded)
        updateConversation(
            conversation.copy(
                updatedAt = now,
                historyRevision = conversation.historyRevision + 1,
                summary = if (message.sortOrder <= conversation.summaryThroughOrder) "" else conversation.summary,
                summaryThroughAt = if (message.sortOrder <= conversation.summaryThroughOrder) 0 else conversation.summaryThroughAt,
                summaryThroughOrder = if (message.sortOrder <= conversation.summaryThroughOrder) 0 else conversation.summaryThroughOrder,
            ),
        )
    }

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

    @Upsert
    suspend fun upsertWorldEntries(entries: List<WorldEntryEntity>)

    @Transaction
    suspend fun upsertWorldSetWithEntries(worldSet: WorldSetEntity, entries: List<WorldEntryEntity>) {
        upsertWorldSet(worldSet)
        if (entries.isNotEmpty()) upsertWorldEntries(entries)
    }

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

    @Transaction
    suspend fun replaceConversationWorldSets(conversationId: String, links: List<ConversationWorldSetEntity>) {
        clearConversationWorldSets(conversationId)
        if (links.isNotEmpty()) addConversationWorldSets(links)
    }
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
        MessageVersionEntity::class,
    ],
    version = 9,
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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN messageBubbleOpacity REAL NOT NULL DEFAULT 1.0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generation_contexts ADD COLUMN reasoningContent TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN reasoningMode TEXT NOT NULL DEFAULT 'AUTO'")
            }
        }
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generation_contexts ADD COLUMN outputTokenCount INTEGER")
                db.execSQL("ALTER TABLE generation_contexts ADD COLUMN tokenCountEstimated INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE generation_contexts ADD COLUMN generationElapsedMillis INTEGER")
            }
        }
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN currentVersionId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN excluded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS message_versions (id TEXT NOT NULL, messageId TEXT NOT NULL, versionNumber INTEGER NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, source TEXT NOT NULL, baseVersionId TEXT, status TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_versions_messageId ON message_versions(messageId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_message_versions_messageId_versionNumber ON message_versions(messageId, versionNumber)")
                db.execSQL("INSERT INTO message_versions (id, messageId, versionNumber, content, createdAt, source, baseVersionId, status) SELECT id || '-v1', id, 1, content, createdAt, 'ORIGINAL', NULL, 'COMPLETE' FROM messages")
                db.execSQL("UPDATE messages SET currentVersionId = id || '-v1'")
                db.execSQL("UPDATE messages SET sortOrder = (SELECT COUNT(*) FROM messages AS earlier WHERE earlier.conversationId = messages.conversationId AND (earlier.createdAt < messages.createdAt OR (earlier.createdAt = messages.createdAt AND earlier.rowid <= messages.rowid)))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_messages_conversationId_sortOrder ON messages(conversationId, sortOrder)")
                db.execSQL("ALTER TABLE conversations ADD COLUMN contextStartOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN summaryThroughOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN replyLengthPreference TEXT NOT NULL DEFAULT 'DEFAULT'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN maxOutputTokens INTEGER")
                db.execSQL("ALTER TABLE conversations ADD COLUMN tokenLimitField TEXT NOT NULL DEFAULT 'AUTO'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN historyRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE conversations SET contextStartOrder = CASE WHEN contextStartAt = 0 THEN 0 ELSE COALESCE((SELECT MIN(sortOrder) FROM messages WHERE messages.conversationId = conversations.id AND messages.createdAt >= conversations.contextStartAt), (SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM messages WHERE messages.conversationId = conversations.id)) END")
                db.execSQL("UPDATE conversations SET summaryThroughOrder = CASE WHEN summaryThroughAt = 0 THEN 0 ELSE COALESCE((SELECT MAX(sortOrder) FROM messages WHERE messages.conversationId = conversations.id AND messages.createdAt <= conversations.summaryThroughAt), 0) END")
                db.execSQL("CREATE TABLE IF NOT EXISTS generation_contexts_new (versionId TEXT NOT NULL, activatedWorldEntriesJson TEXT NOT NULL, reasoningContent TEXT NOT NULL, outputTokenCount INTEGER, tokenCountEstimated INTEGER NOT NULL DEFAULT 1, generationElapsedMillis INTEGER, PRIMARY KEY(versionId), FOREIGN KEY(versionId) REFERENCES message_versions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO generation_contexts_new (versionId, activatedWorldEntriesJson, reasoningContent, outputTokenCount, tokenCountEstimated, generationElapsedMillis) SELECT messages.currentVersionId, generation_contexts.activatedWorldEntriesJson, generation_contexts.reasoningContent, generation_contexts.outputTokenCount, generation_contexts.tokenCountEstimated, generation_contexts.generationElapsedMillis FROM generation_contexts JOIN messages ON messages.id = generation_contexts.messageId")
                db.execSQL("DROP TABLE generation_contexts")
                db.execSQL("ALTER TABLE generation_contexts_new RENAME TO generation_contexts")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_generation_contexts_versionId ON generation_contexts(versionId)")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai-chat.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build().also { instance = it }
            }
    }
}
