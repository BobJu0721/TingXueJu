package com.aichat.app.domain

import com.aichat.app.composePrompt
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ConversationRepository
import com.aichat.app.data.GenerationContextEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.ProfileRepository
import com.aichat.app.data.WorldInfoRepository
import com.aichat.app.network.AiApiClient
import com.aichat.app.pick
import com.aichat.app.toJsonStrings
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.UUID

class StreamConversationUseCase(
    private val conversationRepository: ConversationRepository,
    private val profileRepository: ProfileRepository,
    private val worldInfoRepository: WorldInfoRepository,
    private val api: AiApiClient,
) {
    suspend operator fun invoke(conversationId: String, settings: AppSettings, key: String) {
        val conversation = conversationRepository.getConversation(conversationId) ?: return
        val history = conversationRepository.getMessages(conversationId)
        val worldIds = conversationRepository.getConversationWorldSetIds(conversationId)
        val worldSets = if (worldIds.isEmpty()) emptyList() else worldInfoRepository.getWorldSets(worldIds)
        val entries = if (worldIds.isEmpty()) emptyList() else worldInfoRepository.getWorldEntries(worldIds)
        val prompt = composePrompt(
            conversation,
            history,
            profileRepository.getProfile(conversation.characterId),
            profileRepository.getProfile(conversation.personaId),
            worldSets,
            entries,
            settings.language,
        )
        val assistant = MessageEntity(UUID.randomUUID().toString(), conversationId, "assistant", "", System.currentTimeMillis() + 1)
        conversationRepository.upsertMessage(assistant)
        var content = ""
        try {
            api.streamChat(settings, key, prompt.messages) { token ->
                content += token
                conversationRepository.upsertMessage(assistant.copy(content = content))
            }
            if (content.isBlank()) {
                throw IOException(settings.language.pick(
                    "\u0041\u0050\u0049 \u6c92\u6709\u56de\u50b3\u6587\u5b57\u5167\u5bb9\u3002",
                    "\u0041\u0050\u0049 \u6ca1\u6709\u8fd4\u56de\u6587\u5b57\u5185\u5bb9\u3002",
                ))
            }
            conversationRepository.upsertGenerationContext(
                GenerationContextEntity(assistant.id, toJsonStrings(prompt.activatedEntries.map { it.title })),
            )
        } catch (_: CancellationException) {
            if (content.isBlank()) conversationRepository.deleteMessage(assistant.id)
        } catch (error: Throwable) {
            conversationRepository.deleteMessage(assistant.id)
            throw error
        }
    }
}
