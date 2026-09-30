package com.aichat.app.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ConversationRepository(private val dao: ChatDao) {
    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> = dao.observeMessages(conversationId)
    fun observeMessageVersions(conversationId: String): Flow<List<MessageVersionEntity>> =
        dao.observeMessageVersions(conversationId)
    fun observeGenerationContexts(conversationId: String): Flow<List<GenerationContextEntity>> =
        dao.observeGenerationContexts(conversationId)

    suspend fun getConversation(id: String): ConversationEntity? = dao.getConversation(id)
    suspend fun upsertConversation(conversation: ConversationEntity) = dao.upsertConversation(conversation)
    suspend fun updateConversation(conversation: ConversationEntity) = dao.updateConversation(conversation)
    suspend fun deleteConversation(conversation: ConversationEntity) = dao.deleteConversation(conversation)

    suspend fun getMessages(conversationId: String): List<MessageEntity> = dao.getMessages(conversationId)
    suspend fun getMessage(id: String): MessageEntity? = dao.getMessage(id)
    suspend fun getMessageVersions(messageId: String): List<MessageVersionEntity> = dao.getMessageVersions(messageId)
    suspend fun getMessageVersion(id: String): MessageVersionEntity? = dao.getMessageVersion(id)
    suspend fun countMessagesAfter(message: MessageEntity): Int =
        dao.countMessagesAfter(message.conversationId, message.sortOrder)

    suspend fun createInitialMessage(
        conversationId: String,
        role: String,
        content: String,
        createdAt: Long = System.currentTimeMillis(),
    ): MessageEntity {
        val messageId = UUID.randomUUID().toString()
        val versionId = UUID.randomUUID().toString()
        val message = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            role = role,
            content = content,
            createdAt = createdAt,
            currentVersionId = versionId,
        )
        return dao.createCommittedMessageWithVersion(
            message,
            MessageVersionEntity(versionId, messageId, 1, content, createdAt),
            createdAt,
        )
    }

    suspend fun createDraft(
        conversationId: String,
        source: MessageVersionSource,
        target: MessageEntity? = null,
        baseVersion: MessageVersionEntity? = null,
        initialContent: String = "",
    ): Pair<MessageEntity, MessageVersionEntity> {
        val now = System.currentTimeMillis()
        val message = target ?: MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = "assistant",
            content = initialContent,
            createdAt = now,
        )
        val version = MessageVersionEntity(
            id = UUID.randomUUID().toString(),
            messageId = message.id,
            versionNumber = (dao.getMessageVersions(message.id).maxOfOrNull { it.versionNumber } ?: 0) + 1,
            content = initialContent,
            createdAt = now,
            source = source,
            baseVersionId = baseVersion?.id ?: target?.currentVersionId,
            status = MessageVersionStatus.DRAFT,
        )
        val inserted = dao.startDraft(if (target == null) message else null, version)
        return (target ?: inserted ?: error("Draft message was not created")) to version
    }

    suspend fun updateDraft(version: MessageVersionEntity, context: GenerationContextEntity?) =
        dao.updateDraft(version, context)

    suspend fun rollbackDraft(version: MessageVersionEntity, newMessage: Boolean) {
        val baseContent = version.baseVersionId?.let { dao.getMessageVersion(it)?.content }
        dao.rollbackDraft(version, baseContent, newMessage)
    }

    suspend fun commitDraft(
        message: MessageEntity,
        version: MessageVersionEntity,
        context: GenerationContextEntity?,
        expectedRevision: Long,
        cutoffOrder: Long?,
        temporarySummary: ConversationEntity? = null,
    ): Boolean = dao.commitDraft(
        message.conversationId,
        message,
        version,
        context,
        expectedRevision,
        cutoffOrder,
        temporarySummary,
        System.currentTimeMillis(),
    )

    suspend fun addEditedVersion(message: MessageEntity, content: String, expectedRevision: Long): Boolean {
        val version = MessageVersionEntity(
            id = UUID.randomUUID().toString(),
            messageId = message.id,
            versionNumber = (dao.getMessageVersions(message.id).maxOfOrNull { it.versionNumber } ?: 0) + 1,
            content = content,
            createdAt = System.currentTimeMillis(),
            source = if (message.role == "user") MessageVersionSource.USER_EDIT else MessageVersionSource.AI_EDIT,
            baseVersionId = message.currentVersionId,
        )
        return dao.applyVersionChange(
            message.conversationId,
            message,
            version,
            insertVersion = true,
            expectedRevision = expectedRevision,
            deleteAfter = true,
            now = System.currentTimeMillis(),
        )
    }

    suspend fun selectVersion(message: MessageEntity, version: MessageVersionEntity, expectedRevision: Long): Boolean =
        dao.applyVersionChange(
            message.conversationId,
            message,
            version,
            insertVersion = false,
            expectedRevision = expectedRevision,
            deleteAfter = true,
            now = System.currentTimeMillis(),
        )

    suspend fun deleteMessage(message: MessageEntity) =
        dao.deleteMessageAndInvalidate(message, System.currentTimeMillis())

    suspend fun setMessageExcluded(message: MessageEntity, excluded: Boolean) =
        dao.setExcludedAndInvalidate(message, excluded, System.currentTimeMillis())

    suspend fun getConversationWorldSetIds(conversationId: String): List<String> =
        dao.getConversationWorldSetIds(conversationId)
    suspend fun replaceConversationWorldSets(conversationId: String, links: List<ConversationWorldSetEntity>) =
        dao.replaceConversationWorldSets(conversationId, links)

    suspend fun recoverInterruptedDrafts() {
        dao.getDraftVersions().forEach { draft ->
            val message = dao.getMessage(draft.messageId) ?: return@forEach
            val base = draft.baseVersionId?.let { dao.getMessageVersion(it) }
            val hasNewContent = if (draft.source == MessageVersionSource.CONTINUATION) {
                draft.content.length > base?.content.orEmpty().length
            } else {
                draft.content.isNotBlank()
            }
            if (hasNewContent) {
                dao.recoverDraft(draft, base)
            } else {
                dao.rollbackDraft(draft, base?.content, base == null && message.role == "assistant")
            }
        }
    }
}
