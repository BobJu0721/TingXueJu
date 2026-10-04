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

    /** 新聊天室一定要有初始路線；已存在的路線不會被覆蓋，摘要與裁切狀態因此得以保留。 */
    @Transaction
    suspend fun upsertConversationWithInitialBranch(conversation: ConversationEntity, branch: ConversationBranchEntity) {
        upsertConversation(conversation)
        if (getBranch(branch.id) == null) upsertBranch(branch)
    }

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

    @Query("UPDATE messages SET currentVersionId = :versionId, content = :content WHERE id = :messageId")
    suspend fun selectMessageVersion(messageId: String, versionId: String, content: String)

    @Insert
    suspend fun insertMessageVersion(version: MessageVersionEntity)

    @Update
    suspend fun updateMessageVersion(version: MessageVersionEntity)

    @Query("DELETE FROM message_versions WHERE id = :versionId")
    suspend fun deleteMessageVersion(versionId: String)

    @Upsert
    suspend fun upsertGenerationContext(context: GenerationContextEntity)

    // ---- 路線：顯示與生成的唯一依據 ----

    @Query("SELECT * FROM conversation_branches WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeBranches(conversationId: String): Flow<List<ConversationBranchEntity>>

    @Query("SELECT * FROM conversation_branches WHERE id = :id")
    suspend fun getBranch(id: String): ConversationBranchEntity?

    @Query("SELECT * FROM conversation_branches WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getBranches(conversationId: String): List<ConversationBranchEntity>

    @Upsert
    suspend fun upsertBranch(branch: ConversationBranchEntity)

    @Query("UPDATE conversation_branches SET sceneNote = :text, sceneNoteEnabled = :enabled WHERE id = :branchId AND conversationId = :conversationId")
    suspend fun setSceneNote(conversationId: String, branchId: String, text: String, enabled: Boolean)

    @Transaction
    suspend fun saveChatInfo(
        conversationId: String,
        branchId: String,
        note: SceneNote,
        preference: ReplyLengthPreference,
        maxTokens: Int?,
        field: TokenLimitField,
    ): Boolean {
        val conversation = getConversation(conversationId) ?: return false
        val branch = getBranch(branchId) ?: return false
        if (conversation.activeBranchId != branchId || branch.conversationId != conversationId) return false
        val changed = branch.sceneNoteValue() != note || conversation.replyLengthPreference != preference ||
            conversation.maxOutputTokens != maxTokens || conversation.tokenLimitField != field
        if (changed) {
            setSceneNote(conversationId, branchId, note.text, note.enabled)
            updateConversation(conversation.copy(
                replyLengthPreference = preference,
                maxOutputTokens = maxTokens,
                tokenLimitField = field,
                updatedAt = System.currentTimeMillis(),
                historyRevision = conversation.historyRevision + 1,
            ))
        }
        return true
    }

    @Query("DELETE FROM conversation_branches WHERE id = :id")
    suspend fun deleteBranch(id: String)

    @Query("UPDATE conversation_branches SET lastUsedAt = :now WHERE id = :id")
    suspend fun touchBranch(id: String, now: Long)

    @Query("UPDATE conversation_branches SET summary = :summary, summaryThroughOrder = :throughOrder WHERE id = :id")
    suspend fun setBranchSummary(id: String, summary: String, throughOrder: Long)

    @Query("UPDATE conversation_branches SET contextStartOrder = :order WHERE id = :id")
    suspend fun setBranchContextStart(id: String, order: Long)

    @Query("SELECT * FROM branch_messages WHERE branchId = :branchId ORDER BY sortOrder ASC")
    suspend fun getBranchMessages(branchId: String): List<BranchMessageEntity>

    @Query("SELECT * FROM branch_messages WHERE branchId = :branchId AND messageId = :messageId")
    suspend fun getBranchMessage(branchId: String, messageId: String): BranchMessageEntity?

    @Query("SELECT b.* FROM conversation_branches b JOIN conversations c ON c.activeBranchId = b.id WHERE c.id = :conversationId")
    suspend fun getActiveBranch(conversationId: String): ConversationBranchEntity?

    @Transaction
    suspend fun getGenerationSnapshot(conversationId: String): GenerationSnapshot? {
        val conversation = getConversation(conversationId) ?: return null
        val branch = getActiveBranch(conversationId) ?: return null
        return GenerationSnapshot(conversation.copy(
            summary = branch.summary, summaryThroughOrder = branch.summaryThroughOrder,
            contextStartOrder = branch.contextStartOrder, summaryThroughAt = 0, contextStartAt = 0,
        ), branch, getRouteMessages(conversationId).map(RouteMessageRow::resolved))
    }

    @Query("SELECT b.* FROM conversation_branches b JOIN conversations c ON c.activeBranchId = b.id WHERE c.id = :conversationId")
    fun observeActiveBranch(conversationId: String): Flow<ConversationBranchEntity?>

    @Query("SELECT * FROM conversation_branches WHERE forkVersionId = :versionId LIMIT 1")
    suspend fun getBranchByForkVersion(versionId: String): ConversationBranchEntity?

    @Query("SELECT COUNT(*) FROM conversation_branches WHERE conversationId = :conversationId AND forkMessageId = :messageId")
    suspend fun countBranchForksAt(conversationId: String, messageId: String): Int

    @Query("UPDATE branch_messages SET excluded = :excluded WHERE branchId = :branchId AND messageId = :messageId")
    suspend fun setBranchExcluded(branchId: String, messageId: String, excluded: Boolean)

    @Query("DELETE FROM message_versions WHERE messageId = :messageId")
    suspend fun deleteVersionsForMessage(messageId: String)

    @Query("UPDATE messages SET content = '', currentVersionId = '', deleted = 1 WHERE id = :messageId")
    suspend fun markMessageDeleted(messageId: String)

    @Query("UPDATE branch_messages SET versionId = '' WHERE messageId = :messageId")
    suspend fun clearBranchVersion(messageId: String)

    @Transaction
    suspend fun updateDraft(version: MessageVersionEntity, context: GenerationContextEntity?, messageId: String) {
        updateMessageVersion(version)
        selectMessageVersion(messageId, version.id, version.content)
        if (context != null) upsertGenerationContext(context)
    }

    @Transaction
    suspend fun commitDraft(
        conversationId: String,
        branchId: String,
        messageId: String,
        version: MessageVersionEntity,
        context: GenerationContextEntity?,
        expectedRevision: Long,
        temporarySummary: String?,
        temporarySummaryThrough: Long,
        now: Long,
    ): Boolean {
        val conversation = getConversation(conversationId) ?: return false
        if (conversation.historyRevision != expectedRevision) return false
        val row = getBranchMessage(branchId, messageId) ?: return false
        if (row.versionId != version.id) return false
        updateMessageVersion(version)
        selectMessageVersion(messageId, version.id, version.content)
        if (context != null) upsertGenerationContext(context)
        // 自動摘要的臨時結果只套用在這次生成所屬的路線。
        if (temporarySummary != null) setBranchSummary(branchId, temporarySummary, temporarySummaryThrough)
        updateConversation(conversation.copy(updatedAt = now, historyRevision = conversation.historyRevision + 1))
        return true
    }

    @Transaction
    suspend fun rollbackDraft(
        conversationId: String,
        branchId: String,
        previousBranchId: String,
        messageId: String,
        versionId: String,
        newMessage: Boolean,
        createdBranch: Boolean,
        now: Long,
    ) {
        val conversation = getConversation(conversationId) ?: return
        // 只刪掉確實屬於這次草稿的候選分支（同一聊天室、而且分岔版本就是這個草稿版本）。
        // ID 對不上時保留資料，不依錯誤 ID 猜測式刪除；詳見修復指南的資料完整性要求。
        val candidate = if (createdBranch) {
            getBranch(branchId)?.takeIf { it.conversationId == conversationId && it.forkVersionId == versionId }
        } else {
            null
        }
        deleteMessageVersion(versionId)
        when {
            candidate != null -> deleteBranch(candidate.id)
            !createdBranch -> deleteBranchMessage(branchId, messageId)
        }
        if (newMessage) deleteMessage(messageId)
        val restore = if (candidate != null && previousBranchId.isNotBlank()) previousBranchId else conversation.activeBranchId
        if (!newMessage) {
            // 讓 legacy 欄位回到原路線選定的版本，不要留下指向已刪除版本的參照。
            val version = getBranchMessage(restore, messageId)?.versionId?.let { getMessageVersion(it) }
            if (version != null) selectMessageVersion(messageId, version.id, version.content)
        }
        if (restore != conversation.activeBranchId) {
            updateConversation(conversation.copy(activeBranchId = restore, updatedAt = now, historyRevision = conversation.historyRevision + 1))
        }
    }

    /** 刪除這則訊息在所有路線的版本與生成資料；分岔點保留結構標記，其他訊息不連帶刪除。 */
    @Transaction
    suspend fun deleteMessageEverywhere(conversationId: String, messageId: String, now: Long): Boolean {
        val message = getMessage(messageId) ?: return false
        if (message.conversationId != conversationId) return false
        val conversation = getConversation(conversationId) ?: return false
        getBranchMessagesForMessage(conversationId, messageId).forEach { row ->
            val branch = getBranch(row.branchId)
            if (branch != null && branch.summaryThroughOrder > 0 && row.sortOrder <= branch.summaryThroughOrder) {
                setBranchSummary(branch.id, "", 0)
            }
        }
        deleteVersionsForMessage(messageId)
        if (countBranchForksAt(conversationId, messageId) > 0) {
            markMessageDeleted(messageId)
            clearBranchVersion(messageId)
        } else {
            deleteMessage(messageId)
        }
        updateConversation(conversation.copy(updatedAt = now, historyRevision = conversation.historyRevision + 1))
        return true
    }

    @Transaction
    suspend fun setExcludedInActiveBranch(conversationId: String, messageId: String, excluded: Boolean, now: Long): Boolean {
        val conversation = getConversation(conversationId) ?: return false
        val branchId = conversation.activeBranchId.takeIf { it.isNotBlank() } ?: return false
        val row = getBranchMessage(branchId, messageId) ?: return false
        setBranchExcluded(branchId, messageId, excluded)
        val branch = getBranch(branchId)
        if (branch != null && branch.summaryThroughOrder > 0 && row.sortOrder <= branch.summaryThroughOrder) {
            setBranchSummary(branchId, "", 0)
        }
        updateConversation(conversation.copy(updatedAt = now, historyRevision = conversation.historyRevision + 1))
        return true
    }

    @Query("SELECT bm.branchId FROM branch_messages bm JOIN conversation_branches b ON b.id = bm.branchId WHERE b.conversationId = :conversationId AND bm.messageId = :messageId AND bm.versionId = :versionId")
    suspend fun findBranchesWithVersion(conversationId: String, messageId: String, versionId: String): List<String>

    @Query("SELECT bm.* FROM branch_messages bm JOIN conversation_branches b ON b.id = bm.branchId WHERE b.conversationId = :conversationId AND bm.messageId = :messageId")
    suspend fun getBranchMessagesForMessage(conversationId: String, messageId: String): List<BranchMessageEntity>

    @Upsert
    suspend fun upsertBranchMessage(row: BranchMessageEntity)

    @Query("DELETE FROM branch_messages WHERE branchId = :branchId AND messageId = :messageId")
    suspend fun deleteBranchMessage(branchId: String, messageId: String)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM branch_messages WHERE branchId = :branchId")
    suspend fun nextBranchOrder(branchId: String): Long

    /**
     * 目前路線的訊息，`sortOrder` 換成路線內順序、`content`／`currentVersionId`／`excluded`
     * 換成該路線選定的版本與排除狀態。上層不需要知道路線的存在。
     */
    @Query(
        "SELECT m.*, bm.versionId AS routeVersionId, bm.sortOrder AS routeSortOrder, bm.excluded AS routeExcluded, " +
            "COALESCE(v.content, '') AS routeContent, " +
            "v.authorName AS routeAuthorName, v.authorCharacterId AS routeAuthorCharacterId " +
            "FROM branch_messages bm " +
            "JOIN conversations c ON c.activeBranchId = bm.branchId " +
            "JOIN messages m ON m.id = bm.messageId " +
            "LEFT JOIN message_versions v ON v.id = bm.versionId " +
            "WHERE c.id = :conversationId ORDER BY bm.sortOrder ASC",
    )
    fun observeRouteMessages(conversationId: String): Flow<List<RouteMessageRow>>

    @Query(
        "SELECT m.*, bm.versionId AS routeVersionId, bm.sortOrder AS routeSortOrder, bm.excluded AS routeExcluded, " +
            "COALESCE(v.content, '') AS routeContent, " +
            "v.authorName AS routeAuthorName, v.authorCharacterId AS routeAuthorCharacterId " +
            "FROM branch_messages bm " +
            "JOIN conversations c ON c.activeBranchId = bm.branchId " +
            "JOIN messages m ON m.id = bm.messageId " +
            "LEFT JOIN message_versions v ON v.id = bm.versionId " +
            "WHERE c.id = :conversationId ORDER BY bm.sortOrder ASC",
    )
    suspend fun getRouteMessages(conversationId: String): List<RouteMessageRow>

    @Query(
        "SELECT m.*, bm.versionId AS routeVersionId, bm.sortOrder AS routeSortOrder, bm.excluded AS routeExcluded, " +
            "COALESCE(v.content, '') AS routeContent, " +
            "v.authorName AS routeAuthorName, v.authorCharacterId AS routeAuthorCharacterId " +
            "FROM branch_messages bm " +
            "JOIN conversations c ON c.activeBranchId = bm.branchId " +
            "JOIN messages m ON m.id = bm.messageId " +
            "LEFT JOIN message_versions v ON v.id = bm.versionId " +
            "WHERE c.id = :conversationId AND m.id = :messageId",
    )
    suspend fun getRouteMessage(conversationId: String, messageId: String): RouteMessageRow?

    /**
     * 目前路線的一次性快照：訊息、版本與生成資料在同一個交易內讀取，避免介面短暫看到
     * 混合不同路線的狀態。
     */
    @Transaction
    suspend fun getRouteRows(conversationId: String): RouteRows = RouteRows(
        messages = getRouteMessages(conversationId),
        versions = getRouteVersions(conversationId),
        contexts = getRouteGenerationContexts(conversationId),
    )

    /** 目前路線所有訊息的版本，供版本箭頭使用。 */
    @Query(
        "SELECT v.* FROM message_versions v " +
            "JOIN branch_messages bm ON bm.messageId = v.messageId " +
            "JOIN conversations c ON c.activeBranchId = bm.branchId " +
            "WHERE c.id = :conversationId ORDER BY bm.sortOrder ASC, v.versionNumber ASC",
    )
    suspend fun getRouteVersions(conversationId: String): List<MessageVersionEntity>

    /** 目前路線選定版本的生成資料（思考內容、世界命中、統計）。 */
    @Query(
        "SELECT g.* FROM generation_contexts g " +
            "JOIN branch_messages bm ON bm.versionId = g.versionId " +
            "JOIN conversations c ON c.activeBranchId = bm.branchId " +
            "WHERE c.id = :conversationId",
    )
    suspend fun getRouteGenerationContexts(conversationId: String): List<GenerationContextEntity>

    /** 目前路線所有訊息的版本，供版本箭頭使用。 */
    @Query(
        "SELECT v.* FROM message_versions v " +
            "JOIN branch_messages bm ON bm.messageId = v.messageId " +
            "JOIN conversations c ON c.activeBranchId = bm.branchId " +
            "WHERE c.id = :conversationId ORDER BY bm.sortOrder ASC, v.versionNumber ASC",
    )
    fun observeRouteVersions(conversationId: String): Flow<List<MessageVersionEntity>>

    /** 目前路線選定版本的生成資料（思考內容、世界命中、統計）。 */
    @Query(
        "SELECT g.* FROM generation_contexts g " +
            "JOIN branch_messages bm ON bm.versionId = g.versionId " +
            "JOIN conversations c ON c.activeBranchId = bm.branchId " +
            "WHERE c.id = :conversationId",
    )
    fun observeRouteGenerationContexts(conversationId: String): Flow<List<GenerationContextEntity>>

    @Transaction
    suspend fun appendMessageToActiveBranch(
        conversationId: String,
        message: MessageEntity,
        version: MessageVersionEntity,
        now: Long,
        expectedBranchId: String? = null,
        expectedRevision: Long? = null,
    ): MessageEntity? {
        val conversation = getConversation(conversationId) ?: return null
        val branchId = conversation.activeBranchId
        if (branchId.isBlank()) return null
        if (expectedBranchId != null && branchId != expectedBranchId) return null
        if (expectedRevision != null && conversation.historyRevision != expectedRevision) return null
        val ordered = message.copy(
            currentVersionId = version.id,
            content = version.content,
            sortOrder = nextMessageOrder(conversationId),
        )
        upsertMessage(ordered)
        insertMessageVersion(version)
        upsertBranchMessage(BranchMessageEntity(branchId, ordered.id, version.id, nextBranchOrder(branchId), false))
        updateConversation(conversation.copy(updatedAt = now, historyRevision = conversation.historyRevision + 1))
        return ordered
    }

    /**
     * 從 [parentBranchId] 分岔出一條新路線，止於 [messageId] 的 [version]。
     * 新路線只帶走共同前文，不帶走原路線在分岔點之後的後續。
     */
    @Transaction
    suspend fun forkBranchAtVersion(
        parentBranchId: String,
        messageId: String,
        version: MessageVersionEntity,
        newBranchId: String,
        insertVersion: Boolean,
        legacyIncomplete: Boolean,
        now: Long,
        preserveTrailingNotes: Boolean = false,
    ): Boolean {
        val parent = getBranch(parentBranchId) ?: return false
        val conversation = getConversation(parent.conversationId) ?: return false
        val rows = getBranchMessages(parentBranchId)
        val forkRow = rows.firstOrNull { it.messageId == messageId } ?: return false
        if (insertVersion) insertMessageVersion(version)
        val keepsSummary = parent.summary.isNotBlank() && parent.summaryThroughOrder in 1 until forkRow.sortOrder
        upsertBranch(
            ConversationBranchEntity(
                id = newBranchId,
                conversationId = parent.conversationId,
                sourceBranchId = parentBranchId,
                forkMessageId = messageId,
                forkVersionId = version.id,
                createdAt = now,
                lastUsedAt = now,
                summary = if (keepsSummary) parent.summary else "",
                summaryThroughOrder = if (keepsSummary) parent.summaryThroughOrder else 0,
                contextStartOrder = parent.contextStartOrder.takeIf { it in 1 until forkRow.sortOrder } ?: 0,
                legacyIncomplete = legacyIncomplete,
                sceneNote = parent.sceneNote,
                sceneNoteEnabled = parent.sceneNoteEnabled,
            ),
        )
        rows.filter { it.sortOrder < forkRow.sortOrder }
            .forEach { upsertBranchMessage(it.copy(branchId = newBranchId)) }
        upsertBranchMessage(BranchMessageEntity(newBranchId, messageId, version.id, forkRow.sortOrder, forkRow.excluded))
        if (preserveTrailingNotes) {
            rows.filter { it.sortOrder > forkRow.sortOrder }.forEach { row ->
                if (getMessage(row.messageId)?.kind == MessageKind.PRIVATE_NOTE) {
                    upsertBranchMessage(row.copy(branchId = newBranchId))
                }
            }
        }
        // messages 的 legacy 欄位跟著目前路線走，方便直接讀取單列的地方。
        selectMessageVersion(messageId, version.id, version.content)
        updateConversation(
            conversation.copy(activeBranchId = newBranchId, updatedAt = now, historyRevision = conversation.historyRevision + 1),
        )
        return true
    }

    /** 分岔出一條新路線，前文到 [anchorMessageId] 為止，再接上新的 [message]。 */
    @Transaction
    suspend fun forkBranchAppendingMessage(
        parentBranchId: String,
        anchorMessageId: String,
        message: MessageEntity,
        version: MessageVersionEntity,
        newBranchId: String,
        now: Long,
    ): MessageEntity? {
        val parent = getBranch(parentBranchId) ?: return null
        val conversation = getConversation(parent.conversationId) ?: return null
        val rows = getBranchMessages(parentBranchId)
        val anchor = rows.firstOrNull { it.messageId == anchorMessageId } ?: return null
        // 新訊息必須屬於這條路線的聊天室，而且版本要指向這個新訊息。
        if (message.conversationId != parent.conversationId) return null
        if (version.messageId != message.id) return null
        val ordered = message.copy(
            currentVersionId = version.id,
            content = version.content,
            sortOrder = nextMessageOrder(parent.conversationId),
        )
        // 先寫父資料（messages）再寫子資料（message_versions）。messageId 是指向 messages.id 的
        // 外鍵且沒有延遲檢查，順序顛倒會當場 FOREIGN KEY constraint failed。
        upsertMessage(ordered)
        insertMessageVersion(version)
        val keepsSummary = parent.summary.isNotBlank() && parent.summaryThroughOrder in 1..anchor.sortOrder
        upsertBranch(
            ConversationBranchEntity(
                id = newBranchId,
                conversationId = parent.conversationId,
                sourceBranchId = parentBranchId,
                forkMessageId = anchorMessageId,
                // 與 forkBranchAtVersion 一致：記錄這條路線建立時選定的版本。
                // 草稿回滾與中斷恢復都靠這個欄位找回自己的候選分支。
                forkVersionId = version.id,
                createdAt = now,
                lastUsedAt = now,
                summary = if (keepsSummary) parent.summary else "",
                summaryThroughOrder = if (keepsSummary) parent.summaryThroughOrder else 0,
                contextStartOrder = parent.contextStartOrder.takeIf { it in 1..anchor.sortOrder } ?: 0,
                legacyIncomplete = false,
                sceneNote = parent.sceneNote,
                sceneNoteEnabled = parent.sceneNoteEnabled,
            ),
        )
        rows.filter { it.sortOrder <= anchor.sortOrder }
            .forEach { upsertBranchMessage(it.copy(branchId = newBranchId)) }
        upsertBranchMessage(BranchMessageEntity(newBranchId, ordered.id, version.id, anchor.sortOrder + 1, false))
        updateConversation(
            conversation.copy(activeBranchId = newBranchId, updatedAt = now, historyRevision = conversation.historyRevision + 1),
        )
        return ordered
    }

    @Transaction
    suspend fun activateBranch(conversationId: String, branchId: String, now: Long): Boolean {
        val conversation = getConversation(conversationId) ?: return false
        val branch = getBranch(branchId) ?: return false
        if (branch.conversationId != conversationId) return false
        touchBranch(branchId, now)
        if (conversation.activeBranchId == branchId) return true
        updateConversation(
            conversation.copy(activeBranchId = branchId, updatedAt = now, historyRevision = conversation.historyRevision + 1),
        )
        return true
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
        ConversationBranchEntity::class,
        BranchMessageEntity::class,
    ],
    version = 12,
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

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN activeBranchId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversation_branches` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                        "`sourceBranchId` TEXT, `forkMessageId` TEXT, `forkVersionId` TEXT, `createdAt` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL, `summary` TEXT NOT NULL, `summaryThroughOrder` INTEGER NOT NULL DEFAULT 0, " +
                        "`contextStartOrder` INTEGER NOT NULL DEFAULT 0, `legacyIncomplete` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`), FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_branches_conversationId` ON `conversation_branches` (`conversationId`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversation_branches_conversationId_forkMessageId_forkVersionId` " +
                        "ON `conversation_branches` (`conversationId`, `forkMessageId`, `forkVersionId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `branch_messages` (`branchId` TEXT NOT NULL, `messageId` TEXT NOT NULL, " +
                        "`versionId` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `excluded` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`branchId`, `messageId`), " +
                        "FOREIGN KEY(`branchId`) REFERENCES `conversation_branches`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_branch_messages_branchId` ON `branch_messages` (`branchId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_branch_messages_messageId` ON `branch_messages` (`messageId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_branch_messages_branchId_sortOrder` ON `branch_messages` (`branchId`, `sortOrder`)")

                // 1. 每個聊天室建立初始路線，並把目前的摘要與裁切狀態搬過去（無訊息的聊天室也會得到空路線）。
                db.execSQL(
                    "INSERT INTO conversation_branches (id, conversationId, sourceBranchId, forkMessageId, forkVersionId, " +
                        "createdAt, lastUsedAt, summary, summaryThroughOrder, contextStartOrder, legacyIncomplete) " +
                        "SELECT 'branch-' || id, id, NULL, NULL, NULL, createdAt, updatedAt, summary, summaryThroughOrder, contextStartOrder, 0 FROM conversations",
                )
                db.execSQL("UPDATE conversations SET activeBranchId = 'branch-' || id")
                // 2. 初始路線按既有順序引用目前選中的版本，正文與思考仍共用 message_versions，不複製。
                db.execSQL(
                    "INSERT INTO branch_messages (branchId, messageId, versionId, sortOrder, excluded) " +
                        "SELECT 'branch-' || conversationId, id, currentVersionId, sortOrder, excluded FROM messages",
                )
                // 3. 尚存的未選版本建立相容路線：升級當下可用的共同前文 + 該版本，不接上現行版本的後續。
                //    這些路線沒有原後續資料，legacyIncomplete 標記起來，介面才知道要提示。
                db.execSQL(
                    "INSERT INTO conversation_branches (id, conversationId, sourceBranchId, forkMessageId, forkVersionId, " +
                        "createdAt, lastUsedAt, summary, summaryThroughOrder, contextStartOrder, legacyIncomplete) " +
                        "SELECT 'legacy-' || m.id || '-' || v.id, m.conversationId, 'branch-' || m.conversationId, m.id, v.id, " +
                        "v.createdAt, v.createdAt, '', 0, 0, 1 " +
                        "FROM message_versions v JOIN messages m ON m.id = v.messageId WHERE v.id != m.currentVersionId",
                )
                db.execSQL(
                    "INSERT INTO branch_messages (branchId, messageId, versionId, sortOrder, excluded) " +
                        "SELECT 'legacy-' || m.id || '-' || v.id, bm.messageId, bm.versionId, bm.sortOrder, bm.excluded " +
                        "FROM message_versions v JOIN messages m ON m.id = v.messageId " +
                        "JOIN branch_messages bm ON bm.branchId = 'branch-' || m.conversationId AND bm.sortOrder < m.sortOrder " +
                        "WHERE v.id != m.currentVersionId",
                )
                db.execSQL(
                    "INSERT INTO branch_messages (branchId, messageId, versionId, sortOrder, excluded) " +
                        "SELECT 'legacy-' || m.id || '-' || v.id, m.id, v.id, m.sortOrder, m.excluded " +
                        "FROM message_versions v JOIN messages m ON m.id = v.messageId WHERE v.id != m.currentVersionId",
                )
            }
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversation_branches ADD COLUMN sceneNote TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversation_branches ADD COLUMN sceneNoteEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v11 → v12：訊息種類與手寫角色署名。
         *
         * 既有訊息全部是 `CHAT`（保留原本的 `user`／`assistant`），正文、版本與生成資料原樣保留。
         * 署名放在版本上，所以角色日後改名或刪除都不會改寫既有台詞。
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'CHAT'")
                db.execSQL("ALTER TABLE messages ADD COLUMN authorName TEXT")
                db.execSQL("ALTER TABLE message_versions ADD COLUMN authorName TEXT")
                db.execSQL("ALTER TABLE message_versions ADD COLUMN authorCharacterId TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai-chat.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12).build().also { instance = it }
            }
    }
}
