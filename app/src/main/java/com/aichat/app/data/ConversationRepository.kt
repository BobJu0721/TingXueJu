package com.aichat.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.aichat.app.domain.ChatGenerationKind
import java.util.UUID

/**
 * 生成中的候選版本與待提交路線。
 *
 * 串流期間畫面預覽這條候選路線，原路線的資料完全不動；提交或回滾都以 [expectedRevision]
 * 確認期間沒有其他修改。
 */
data class GenerationDraft(
    val message: MessageEntity,
    val version: MessageVersionEntity,
    val branchId: String,
    val newMessage: Boolean,
    val createdBranch: Boolean,
    val previousBranchId: String,
    val expectedRevision: Long,
)

class ConversationRepository(private val dao: ChatDao) {
    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()
    fun observeBranches(conversationId: String): Flow<List<ConversationBranchEntity>> = dao.observeBranches(conversationId)
    fun observeActiveBranch(conversationId: String): Flow<ConversationBranchEntity?> = dao.observeActiveBranch(conversationId)

    /** 目前路線的訊息（已套用該路線的版本、順序與排除狀態）。 */
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        dao.observeRouteMessages(conversationId).map { rows -> rows.map(RouteMessageRow::resolved) }

    fun observeMessageVersions(conversationId: String): Flow<List<MessageVersionEntity>> =
        dao.observeRouteVersions(conversationId)

    fun observeGenerationContexts(conversationId: String): Flow<List<GenerationContextEntity>> =
        dao.observeRouteGenerationContexts(conversationId)

    /**
     * 目前路線的完整快照。以訊息查詢當觸發點，再於同一個交易內讀取版本與生成資料，
     * 所以介面拿到的三個清單一定屬於同一條路線、同一瞬間。
     */
    fun observeRouteSnapshot(conversationId: String): Flow<RouteSnapshot> =
        dao.observeRouteMessages(conversationId).map {
            val rows = dao.getRouteRows(conversationId)
            RouteSnapshot(
                messages = rows.messages.map(RouteMessageRow::resolved),
                versions = rows.versions,
                contexts = rows.contexts,
            )
        }

    suspend fun getRouteSnapshot(conversationId: String): RouteSnapshot {
        val rows = dao.getRouteRows(conversationId)
        return RouteSnapshot(
            messages = rows.messages.map(RouteMessageRow::resolved),
            versions = rows.versions,
            contexts = rows.contexts,
        )
    }

    suspend fun getConversation(id: String): ConversationEntity? = dao.getConversation(id)

    /**
     * 目前路線的聊天室狀態：摘要、裁切起點換成路線擁有的值。
     * 上層（PromptComposer、摘要流程、介面）因此不需要知道路線的存在。
     */
    suspend fun getActiveConversation(id: String): ConversationEntity? {
        val conversation = dao.getConversation(id) ?: return null
        val branch = dao.getActiveBranch(id) ?: return conversation
        return conversation.copy(
            summary = branch.summary,
            summaryThroughOrder = branch.summaryThroughOrder,
            contextStartOrder = branch.contextStartOrder,
            // 路線只以訂單順序記錄邊界；legacy 時間戳留在聊天室層級，不能讓它蓋掉路線狀態。
            summaryThroughAt = 0,
            contextStartAt = 0,
        )
    }

    /**
     * 寫入聊天室。新聊天室（或還沒有路線的舊列）會一併建立初始路線，讓路線關聯永遠是
     * 顯示與生成的唯一依據；已經有路線的聊天室不會被重建。
     */
    suspend fun upsertConversation(conversation: ConversationEntity) {
        val existing = dao.getConversation(conversation.id)
        val branchId = conversation.activeBranchId.takeIf { it.isNotBlank() }
            ?: existing?.activeBranchId?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        dao.upsertConversationWithInitialBranch(
            conversation.copy(activeBranchId = branchId),
            ConversationBranchEntity(
                id = branchId,
                conversationId = conversation.id,
                createdAt = conversation.createdAt,
                lastUsedAt = conversation.updatedAt,
            ),
        )
    }

    /**
     * 摘要與裁切狀態由路線擁有；這裡把 legacy 欄位還原成資料庫原值，避免寫入時把路線狀態
     * 回灌到聊天室層級。
     */
    suspend fun updateConversation(conversation: ConversationEntity) {
        val stored = dao.getConversation(conversation.id) ?: return
        dao.updateConversation(
            conversation.copy(
                summary = stored.summary,
                summaryThroughAt = stored.summaryThroughAt,
                summaryThroughOrder = stored.summaryThroughOrder,
                contextStartAt = stored.contextStartAt,
                contextStartOrder = stored.contextStartOrder,
            ),
        )
    }

    suspend fun deleteConversation(conversation: ConversationEntity) = dao.deleteConversation(conversation)

    suspend fun getActiveBranch(conversationId: String): ConversationBranchEntity? = dao.getActiveBranch(conversationId)

    suspend fun getGenerationSnapshot(conversationId: String): GenerationSnapshot? = dao.getGenerationSnapshot(conversationId)

    suspend fun saveChatInfo(
        conversationId: String,
        branchId: String,
        note: SceneNote,
        preference: ReplyLengthPreference,
        maxTokens: Int?,
        field: TokenLimitField,
    ): Boolean {
        require(maxTokens == null || maxTokens > 0)
        return dao.saveChatInfo(conversationId, branchId, note, preference, maxTokens, field)
    }

    suspend fun getBranches(conversationId: String): List<ConversationBranchEntity> = dao.getBranches(conversationId)

    suspend fun getBranchMessages(branchId: String): List<BranchMessageEntity> = dao.getBranchMessages(branchId)

    /** 指定路線的訊息內容，供比對與除錯使用（不套用目前選取的路線）。 */
    suspend fun getBranchContents(branchId: String): List<String> =
        dao.getBranchMessages(branchId).mapNotNull { dao.getMessageVersion(it.versionId)?.content }

    /** 指定路線中某則訊息的版本，依版本編號排序。 */
    suspend fun getBranchVersions(branchId: String, messageId: String): List<MessageVersionEntity> =
        dao.getBranchMessage(branchId, messageId)
            ?.let { dao.getMessageVersions(it.messageId) }
            .orEmpty()

    suspend fun getMessages(conversationId: String): List<MessageEntity> =
        dao.getRouteMessages(conversationId).map(RouteMessageRow::resolved)

    suspend fun getMessage(id: String): MessageEntity? = dao.getMessage(id)
    suspend fun getRouteMessage(conversationId: String, messageId: String): MessageEntity? =
        dao.getRouteMessage(conversationId, messageId)?.resolved()

    suspend fun getMessageVersions(messageId: String): List<MessageVersionEntity> = dao.getMessageVersions(messageId)
    suspend fun getMessageVersion(id: String): MessageVersionEntity? = dao.getMessageVersion(id)

    suspend fun createInitialMessage(
        conversationId: String,
        role: String,
        content: String,
        createdAt: Long = System.currentTimeMillis(),
    ): MessageEntity? {
        val messageId = UUID.randomUUID().toString()
        val versionId = UUID.randomUUID().toString()
        return dao.appendMessageToActiveBranch(
            conversationId,
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                role = role,
                content = content,
                createdAt = createdAt,
                currentVersionId = versionId,
            ),
            MessageVersionEntity(versionId, messageId, 1, content, createdAt),
            createdAt,
        )
    }

    /**
     * 作者手寫的旁白、角色台詞或私人註記：只加入目前路線尾端，不呼叫 API。
     *
     * 手寫角色台詞的署名快照存在版本上，角色庫日後改名或刪除都不會改寫既有台詞。
     */
    suspend fun appendAuthoredMessage(
        conversationId: String,
        kind: MessageKind,
        content: String,
        authorName: String? = null,
        authorCharacterId: String? = null,
        createdAt: Long = System.currentTimeMillis(),
        sourceBranchId: String? = null,
        expectedRevision: Long? = null,
    ): MessageEntity? {
        require(kind != MessageKind.CHAT) { "Use createInitialMessage for chat messages" }
        require(kind != MessageKind.AUTHORED_CHARACTER || !authorName.isNullOrBlank()) { "A speaker name is required" }
        val messageId = UUID.randomUUID().toString()
        val versionId = UUID.randomUUID().toString()
        return dao.appendMessageToActiveBranch(
            conversationId,
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                role = "user",
                content = content,
                createdAt = createdAt,
                currentVersionId = versionId,
                kind = kind,
            ),
            MessageVersionEntity(
                id = versionId,
                messageId = messageId,
                versionNumber = 1,
                content = content,
                createdAt = createdAt,
                authorName = authorName?.trim()?.takeIf(String::isNotEmpty),
                authorCharacterId = authorCharacterId,
            ),
            createdAt,
            expectedBranchId = sourceBranchId,
            expectedRevision = expectedRevision,
        )
    }

    /** 建立生成候選：暫存版本加上待提交路線，原路線保持完整。 */
    suspend fun prepareDraft(
        request: GenerationRequestFacts,
        source: MessageVersionSource,
        baseVersion: MessageVersionEntity?,
        initialContent: String,
    ): GenerationDraft? {
        val conversation = dao.getConversation(request.conversationId) ?: return null
        val activeBranchId = conversation.activeBranchId
        if (activeBranchId.isBlank()) return null
        val route = dao.getRouteMessages(request.conversationId)
        val target = request.targetMessageId?.let { id -> route.firstOrNull { it.message.id == id } }
        val now = System.currentTimeMillis()
        val newVersionId = UUID.randomUUID().toString()

        fun versionFor(messageId: String, number: Int) = MessageVersionEntity(
            id = newVersionId,
            messageId = messageId,
            versionNumber = number,
            content = initialContent,
            createdAt = now,
            source = source,
            baseVersionId = baseVersion?.id,
            status = MessageVersionStatus.DRAFT,
        )

        return when (request.kind) {
            ChatGenerationKind.NEW_REPLY -> {
                val messageId = UUID.randomUUID().toString()
                val version = versionFor(messageId, 1)
                val message = dao.appendMessageToActiveBranch(
                    request.conversationId,
                    MessageEntity(
                        id = messageId,
                        conversationId = request.conversationId,
                        role = "assistant",
                        content = initialContent,
                        createdAt = now,
                        currentVersionId = version.id,
                    ),
                    version,
                    now,
                ) ?: return null
                draft(message, version, activeBranchId, newMessage = true, createdBranch = false, previousBranchId = activeBranchId)
            }

            ChatGenerationKind.ALTERNATIVE, ChatGenerationKind.CONTINUATION -> {
                val message = target?.message ?: return null
                val version = versionFor(message.id, nextVersionNumber(message.id))
                val branchId = UUID.randomUUID().toString()
                if (!dao.forkBranchAtVersion(activeBranchId, message.id, version, branchId, insertVersion = true, legacyIncomplete = false, now = now,
                    preserveTrailingNotes = request.kind == ChatGenerationKind.CONTINUATION)) {
                    return null
                }
                draft(message, version, branchId, newMessage = false, createdBranch = true, previousBranchId = activeBranchId)
            }

            ChatGenerationKind.ANSWER_FROM_USER -> {
                val anchor = target ?: return null
                // 私人註記不算故事發言，跳過它才判斷得正確；手寫角色台詞也不是 AI 回覆。
                val following = route.firstOrNull {
                    it.routeSortOrder > anchor.routeSortOrder && it.message.kind != MessageKind.PRIVATE_NOTE
                }
                if (following != null && following.message.isAiReply && !following.message.deleted) {
                    val message = following.message
                    val version = versionFor(message.id, nextVersionNumber(message.id))
                    val branchId = UUID.randomUUID().toString()
                    if (!dao.forkBranchAtVersion(activeBranchId, message.id, version, branchId, insertVersion = true, legacyIncomplete = false, now = now)) {
                        return null
                    }
                    draft(message, version, branchId, newMessage = false, createdBranch = true, previousBranchId = activeBranchId)
                } else {
                    val messageId = UUID.randomUUID().toString()
                    val version = versionFor(messageId, 1)
                    // DAO 與草稿必須用同一個分支 ID；這裡原本誤傳 message.conversationId。
                    val newBranchId = UUID.randomUUID().toString()
                    val message = dao.forkBranchAppendingMessage(
                        parentBranchId = activeBranchId,
                        anchorMessageId = anchor.message.id,
                        message = MessageEntity(
                            id = messageId,
                            conversationId = request.conversationId,
                            role = "assistant",
                            content = initialContent,
                            createdAt = now,
                            currentVersionId = version.id,
                        ),
                        version = version,
                        newBranchId = newBranchId,
                        now = now,
                    ) ?: return null
                    draft(
                        message = message,
                        version = version,
                        branchId = newBranchId,
                        newMessage = true,
                        createdBranch = true,
                        previousBranchId = activeBranchId,
                    )
                }
            }
        }.copy(expectedRevision = dao.getConversation(request.conversationId)?.historyRevision ?: 0)
    }

    private fun draft(
        message: MessageEntity,
        version: MessageVersionEntity,
        branchId: String,
        newMessage: Boolean,
        createdBranch: Boolean,
        previousBranchId: String,
    ): GenerationDraft = GenerationDraft(
        message = message,
        version = version,
        branchId = branchId,
        newMessage = newMessage,
        createdBranch = createdBranch,
        previousBranchId = previousBranchId,
        expectedRevision = 0,
    )

    private suspend fun nextVersionNumber(messageId: String): Int =
        (dao.getMessageVersions(messageId).maxOfOrNull { it.versionNumber } ?: 0) + 1

    suspend fun updateDraft(draft: GenerationDraft, context: GenerationContextEntity?) =
        dao.updateDraft(draft.version, context, draft.message.id)

    suspend fun commitDraft(
        draft: GenerationDraft,
        status: MessageVersionStatus,
        context: GenerationContextEntity?,
        expectedRevision: Long,
        temporarySummary: String? = null,
        temporarySummaryThrough: Long = 0,
    ): Boolean = dao.commitDraft(
        draft.message.conversationId,
        draft.branchId,
        draft.message.id,
        draft.version.copy(status = status),
        context,
        expectedRevision,
        temporarySummary,
        temporarySummaryThrough,
        System.currentTimeMillis(),
    )

    /** 放棄候選：刪掉暫存版本與待提交路線，原路線原封不動。 */
    suspend fun rollbackDraft(draft: GenerationDraft) =
        dao.rollbackDraft(
            draft.message.conversationId,
            draft.branchId,
            draft.previousBranchId,
            draft.message.id,
            draft.version.id,
            draft.newMessage,
            draft.createdBranch,
            System.currentTimeMillis(),
        )

    /**
     * 新增版本（編輯、AI 人工編輯）並為它建立自己的後續路線。
     * 原版本與原後續完整保留，不會有任何隱含刪除。
     */
    suspend fun addEditedVersion(
        conversationId: String,
        messageId: String,
        content: String,
        expectedRevision: Long,
        authorName: String? = null,
        authorCharacterId: String? = null,
    ): Boolean {
        val conversation = dao.getConversation(conversationId) ?: return false
        if (conversation.historyRevision != expectedRevision) return false
        val activeBranchId = conversation.activeBranchId.takeIf { it.isNotBlank() } ?: return false
        val route = dao.getRouteMessages(conversationId)
        val target = route.firstOrNull { it.message.id == messageId } ?: return false
        val authored = target.message.kind == MessageKind.AUTHORED_CHARACTER
        val version = MessageVersionEntity(
            id = UUID.randomUUID().toString(),
            messageId = messageId,
            versionNumber = nextVersionNumber(messageId),
            content = content,
            createdAt = System.currentTimeMillis(),
            // 手寫內容是作者編輯，不是模型生成。
            source = if (target.message.role == "user" || target.message.kind.isAuthored) {
                MessageVersionSource.USER_EDIT
            } else {
                MessageVersionSource.AI_EDIT
            },
            baseVersionId = target.routeVersionId,
            // 改署名或改正文都建立新版本；沒給新署名就沿用這一版，舊署名的版本仍然保留。
            authorName = if (authored) authorName ?: target.routeAuthorName else target.routeAuthorName,
            authorCharacterId = if (authored) authorCharacterId ?: target.routeAuthorCharacterId else target.routeAuthorCharacterId,
        )
        return dao.forkBranchAtVersion(
            activeBranchId,
            messageId,
            version,
            UUID.randomUUID().toString(),
            insertVersion = true,
            legacyIncomplete = false,
            now = System.currentTimeMillis(),
        )
    }

    /**
     * 切換版本：找出「同一條前文版本序列」下擁有該版本的路線。
     * 已用過的路線取最近使用者，否則取最新建立的；都沒有才建立一條相容路線。
     */
    suspend fun selectVersion(conversationId: String, messageId: String, versionId: String, expectedRevision: Long): Boolean {
        val conversation = dao.getConversation(conversationId) ?: return false
        if (conversation.historyRevision != expectedRevision) return false
        val activeBranchId = conversation.activeBranchId.takeIf { it.isNotBlank() } ?: return false
        val version = dao.getMessageVersion(versionId) ?: return false
        if (version.messageId != messageId) return false
        val active = dao.getBranch(activeBranchId) ?: return false
        val activeRows = dao.getBranchMessages(activeBranchId)
        val activeRow = activeRows.firstOrNull { it.messageId == messageId }
        if (activeRow?.versionId == versionId) return true
        val prefix = prefixOf(activeRows, messageId)

        val matching = dao.findBranchesWithVersion(conversationId, messageId, versionId)
            .filter { it != activeBranchId }
            .mapNotNull { dao.getBranch(it) }
            .filter { branch ->
                branch.conversationId == conversationId &&
                    prefixOf(dao.getBranchMessages(branch.id), messageId) == prefix
            }

        val best = matching.maxWithOrNull(compareBy({ it.lastUsedAt }, { it.createdAt }))
        if (best != null) return dao.activateBranch(conversationId, best.id, System.currentTimeMillis())

        return dao.forkBranchAtVersion(
            activeBranchId,
            messageId,
            version,
            UUID.randomUUID().toString(),
            insertVersion = false,
            legacyIncomplete = true,
            now = System.currentTimeMillis(),
        )
    }

    /** 從分岔點往前推的共同前文（訊息與版本配對序列）。 */
    private fun prefixOf(rows: List<BranchMessageEntity>, messageId: String): List<Pair<String, String>> {
        val pivot = rows.firstOrNull { it.messageId == messageId }?.sortOrder ?: return emptyList()
        return rows.filter { it.sortOrder < pivot }.map { it.messageId to it.versionId }
    }

    /** 使用者明確刪除：刪掉這則訊息在所有路線的版本與生成資料。 */
    suspend fun deleteMessage(conversationId: String, messageId: String): Boolean =
        dao.deleteMessageEverywhere(conversationId, messageId, System.currentTimeMillis())

    /** 「不提供給 AI」只改目前路線的排除狀態。 */
    suspend fun setMessageExcluded(conversationId: String, messageId: String, excluded: Boolean): Boolean =
        dao.setExcludedInActiveBranch(conversationId, messageId, excluded, System.currentTimeMillis())

    suspend fun isForkPoint(conversationId: String, messageId: String): Boolean =
        dao.countBranchForksAt(conversationId, messageId) > 0

    suspend fun updateBranchSummary(branchId: String, summary: String, summaryThroughOrder: Long) =
        dao.setBranchSummary(branchId, summary, summaryThroughOrder)

    suspend fun updateBranchContextStart(branchId: String, order: Long) = dao.setBranchContextStart(branchId, order)

    suspend fun getConversationWorldSetIds(conversationId: String): List<String> =
        dao.getConversationWorldSetIds(conversationId)

    suspend fun replaceConversationWorldSets(conversationId: String, links: List<ConversationWorldSetEntity>) =
        dao.replaceConversationWorldSets(conversationId, links)

    /**
     * 上次執行中斷的草稿：有內容的恢復成可選的中斷版本，空草稿清掉且不取代原路線。
     */
    suspend fun recoverInterruptedDrafts() {
        dao.getDraftVersions().forEach { draft ->
            val message = dao.getMessage(draft.messageId) ?: return@forEach
            val base = draft.baseVersionId?.let { dao.getMessageVersion(it) }
            val resumed = if (draft.source == MessageVersionSource.CONTINUATION) {
                draft.content.length > base?.content.orEmpty().length
            } else {
                draft.content.isNotBlank()
            }
            if (resumed) {
                dao.updateMessageVersion(draft.copy(status = MessageVersionStatus.INTERRUPTED))
                if (base != null) dao.selectMessageVersion(draft.messageId, base.id, base.content)
            } else {
                val owningBranch = dao.getBranchByForkVersion(draft.id)
                val conversationId = message.conversationId
                val conversation = dao.getConversation(conversationId)
                val restoreTo = owningBranch?.sourceBranchId
                dao.deleteMessageVersion(draft.id)
                if (owningBranch != null) {
                    dao.deleteBranch(owningBranch.id)
                    if (conversation != null && restoreTo != null && conversation.activeBranchId == owningBranch.id) {
                        dao.updateConversation(conversation.copy(activeBranchId = restoreTo))
                    }
                }
                if (base == null && message.role == "assistant" && dao.getMessageVersions(message.id).isEmpty()) {
                    dao.deleteMessage(message.id)
                } else if (base != null) {
                    dao.selectMessageVersion(message.id, base.id, base.content)
                }
            }
        }
    }
}

/** [prepareDraft] 需要的請求欄位；避免 repository 直接相依 domain 的請求型別。 */
data class GenerationRequestFacts(
    val kind: ChatGenerationKind,
    val conversationId: String,
    val targetMessageId: String?,
)
