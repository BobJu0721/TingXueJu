package com.aichat.app.data

import kotlinx.coroutines.flow.Flow

class ConversationRepository(private val dao: ChatDao) {
    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> = dao.observeMessages(conversationId)
    fun observeGenerationContexts(conversationId: String): Flow<List<GenerationContextEntity>> =
        dao.observeGenerationContexts(conversationId)

    suspend fun getConversation(id: String): ConversationEntity? = dao.getConversation(id)
    suspend fun upsertConversation(conversation: ConversationEntity) = dao.upsertConversation(conversation)
    suspend fun updateConversation(conversation: ConversationEntity) = dao.updateConversation(conversation)
    suspend fun deleteConversation(conversation: ConversationEntity) = dao.deleteConversation(conversation)

    suspend fun getMessages(conversationId: String): List<MessageEntity> = dao.getMessages(conversationId)
    suspend fun getMessage(id: String): MessageEntity? = dao.getMessage(id)
    suspend fun upsertMessage(message: MessageEntity) = dao.upsertMessage(message)
    suspend fun updateMessage(message: MessageEntity) = dao.updateMessage(message)
    suspend fun deleteMessage(id: String) = dao.deleteMessage(id)
    suspend fun deleteMessagesAfter(conversationId: String, createdAt: Long) =
        dao.deleteMessagesAfter(conversationId, createdAt)
    suspend fun deleteMessagesAtOrAfter(conversationId: String, createdAt: Long) =
        dao.deleteMessagesAtOrAfter(conversationId, createdAt)

    suspend fun upsertGenerationContext(context: GenerationContextEntity) = dao.upsertGenerationContext(context)
    suspend fun clearReasoningContent(messageId: String) = dao.clearReasoningContent(messageId)

    suspend fun getConversationWorldSetIds(conversationId: String): List<String> =
        dao.getConversationWorldSetIds(conversationId)
    suspend fun clearConversationWorldSets(conversationId: String) =
        dao.clearConversationWorldSets(conversationId)
    suspend fun addConversationWorldSets(links: List<ConversationWorldSetEntity>) =
        dao.addConversationWorldSets(links)
}
