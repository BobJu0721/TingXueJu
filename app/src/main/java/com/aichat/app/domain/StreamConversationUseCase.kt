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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
        val content = StringBuilder()
        val reasoningContent = StringBuilder()
        val activatedEntriesJson = toJsonStrings(prompt.activatedEntries.map { it.title })
        val throttle = StreamWriteThrottle(STREAM_WRITE_INTERVAL_NANOS)
        var contentDirty = false
        var reasoningDirty = false

        suspend fun flush(force: Boolean = false, includeActivatedEntries: Boolean = false) {
            if (!contentDirty && !reasoningDirty && !includeActivatedEntries) return
            if (!throttle.shouldWrite(System.nanoTime(), force)) return
            val message = if (contentDirty || includeActivatedEntries) assistant.copy(content = content.toString()) else null
            val context = if (reasoningDirty || includeActivatedEntries) {
                GenerationContextEntity(
                    messageId = assistant.id,
                    activatedWorldEntriesJson = if (includeActivatedEntries) activatedEntriesJson else "[]",
                    reasoningContent = reasoningContent.toString(),
                )
            } else {
                null
            }
            conversationRepository.upsertStreamingState(message, context)
            contentDirty = false
            reasoningDirty = false
        }

        try {
            api.streamChat(
                settings = settings,
                apiKey = key,
                messages = prompt.messages,
                reasoningMode = conversation.reasoningMode,
                onToken = { token ->
                    content.append(token)
                    contentDirty = true
                    flush()
                },
                onReasoningToken = { token ->
                    reasoningContent.append(token)
                    reasoningDirty = true
                    flush()
                },
            )
            flush(force = true, includeActivatedEntries = true)
            if (content.isBlank()) {
                throw IOException(settings.language.pick(
                    "\u0041\u0050\u0049 \u6c92\u6709\u56de\u50b3\u6587\u5b57\u5167\u5bb9\u3002",
                    "\u0041\u0050\u0049 \u6ca1\u6709\u8fd4\u56de\u6587\u5b57\u5185\u5bb9\u3002",
                ))
            }
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                if (content.isBlank()) {
                    conversationRepository.deleteMessage(assistant.id)
                } else {
                    flush(force = true, includeActivatedEntries = true)
                }
            }
        } catch (error: Throwable) {
            conversationRepository.deleteMessage(assistant.id)
            throw error
        }
    }

    private companion object {
        const val STREAM_WRITE_INTERVAL_NANOS = 50_000_000L
    }
}

internal class StreamWriteThrottle(private val intervalNanos: Long) {
    private var lastWriteNanos: Long? = null

    fun shouldWrite(nowNanos: Long, force: Boolean = false): Boolean {
        val last = lastWriteNanos
        if (!force && last != null && nowNanos - last < intervalNanos) return false
        lastWriteNanos = nowNanos
        return true
    }
}
