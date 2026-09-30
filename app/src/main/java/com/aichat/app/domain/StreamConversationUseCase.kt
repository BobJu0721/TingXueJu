package com.aichat.app.domain

import com.aichat.app.composePrompt
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ConversationRepository
import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.GenerationContextEntity
import com.aichat.app.data.MessageVersionEntity
import com.aichat.app.data.MessageVersionSource
import com.aichat.app.data.MessageVersionStatus
import com.aichat.app.data.ProfileRepository
import com.aichat.app.data.WorldInfoRepository
import com.aichat.app.network.AiApiClient
import com.aichat.app.network.validateReasoningMode
import com.aichat.app.pick
import com.aichat.app.toJsonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException

class StreamConversationUseCase(
    private val conversationRepository: ConversationRepository,
    private val profileRepository: ProfileRepository,
    private val worldInfoRepository: WorldInfoRepository,
    private val api: AiApiClient,
) {
    suspend operator fun invoke(
        request: ChatGenerationRequest,
        settings: AppSettings,
        key: String,
        onAssistantMessageCreated: (String) -> Unit = {},
        temporarySummary: ConversationEntity? = null,
    ): StreamFinishInfo {
        val storedConversation = conversationRepository.getConversation(request.conversationId) ?: return StreamFinishInfo()
        val conversation = temporarySummary?.takeIf { it.id == storedConversation.id } ?: storedConversation
        validateReasoningMode(settings, conversation.reasoningMode)
        if (conversation.historyRevision != request.expectedRevision) throw historyChanged(settings)
        val history = conversationRepository.getMessages(request.conversationId)
        val target = request.targetMessageId?.let { conversationRepository.getMessage(it) }
        if (request.kind != ChatGenerationKind.NEW_REPLY) {
            if (target == null || target.conversationId != request.conversationId ||
                target.currentVersionId != request.baseVersionId ||
                (request.kind == ChatGenerationKind.ANSWER_FROM_USER && target.role != "user") ||
                (request.kind in setOf(ChatGenerationKind.ALTERNATIVE, ChatGenerationKind.CONTINUATION) && target.role != "assistant") ||
                (request.kind == ChatGenerationKind.CONTINUATION && history.lastOrNull()?.id != target.id)
            ) throw historyChanged(settings)
        }
        if (target?.excluded == true) throw IOException(settings.language.pick(
            "已排除的訊息不能直接生成，請先恢復提供給 AI。",
            "已排除的消息不能直接生成，请先恢复提供给 AI。",
        ))
        val baseVersion = request.baseVersionId?.let { conversationRepository.getMessageVersion(it) }
            ?: target?.currentVersionId?.let { conversationRepository.getMessageVersion(it) }
        if (target != null && (baseVersion == null || baseVersion.messageId != target.id)) throw historyChanged(settings)
        val worldIds = conversationRepository.getConversationWorldSetIds(request.conversationId)
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
            request,
        )
        val source = when (request.kind) {
            ChatGenerationKind.ALTERNATIVE -> MessageVersionSource.REGENERATED
            ChatGenerationKind.CONTINUATION -> MessageVersionSource.CONTINUATION
            else -> MessageVersionSource.ORIGINAL
        }
        val existingTarget = target.takeIf { request.kind in setOf(ChatGenerationKind.ALTERNATIVE, ChatGenerationKind.CONTINUATION) }
        val initialContent = if (request.kind == ChatGenerationKind.CONTINUATION) baseVersion?.content.orEmpty() else ""
        val (assistant, initialDraft) = conversationRepository.createDraft(
            request.conversationId,
            source,
            target = existingTarget,
            baseVersion = baseVersion,
            initialContent = initialContent,
        )
        val newMessage = existingTarget == null
        val content = StringBuilder(initialContent)
        val addedContent = StringBuilder()
        val reasoningContent = StringBuilder()
        val activatedEntriesJson = toJsonStrings(prompt.activatedEntries.map { it.title })
        val throttle = StreamWriteThrottle(STREAM_WRITE_INTERVAL_NANOS)
        val meter = GenerationMeter()
        var draft = initialDraft
        var contentDirty = false
        var reasoningDirty = false
        var metricsDirty = false
        var finishInfo = StreamFinishInfo()

        fun context(): GenerationContextEntity {
            val metrics = meter.snapshot(addedContent.toString(), reasoningContent.toString())
            return GenerationContextEntity(
                versionId = draft.id,
                activatedWorldEntriesJson = activatedEntriesJson,
                reasoningContent = reasoningContent.toString(),
                outputTokenCount = metrics.tokens,
                tokenCountEstimated = metrics.estimated,
                generationElapsedMillis = metrics.elapsedMillis,
            )
        }

        suspend fun flush(force: Boolean = false) {
            if (!contentDirty && !reasoningDirty && !metricsDirty && !force) return
            if (!throttle.shouldWrite(System.nanoTime(), force)) return
            draft = draft.copy(content = content.toString())
            conversationRepository.updateDraft(draft, context())
            contentDirty = false
            reasoningDirty = false
            metricsDirty = false
        }

        suspend fun commit(status: MessageVersionStatus): Boolean {
            flush(force = true)
            draft = draft.copy(content = content.toString(), status = status)
            val cutoff = when (request.kind) {
                ChatGenerationKind.ANSWER_FROM_USER, ChatGenerationKind.ALTERNATIVE -> target?.sortOrder
                else -> null
            }
            return conversationRepository.commitDraft(
                assistant.copy(content = draft.content, currentVersionId = draft.id),
                draft,
                context(),
                request.expectedRevision,
                cutoff,
                temporarySummary,
            )
        }

        suspend fun finishCancelledGeneration() = withContext(NonCancellable) {
            if (addedContent.isBlank()) {
                conversationRepository.rollbackDraft(draft, newMessage)
            } else if (!commit(MessageVersionStatus.PARTIAL)) {
                conversationRepository.rollbackDraft(draft, newMessage)
            }
        }

        try {
            onAssistantMessageCreated(assistant.id)
            api.streamChat(
                settings = settings,
                apiKey = key,
                messages = prompt.messages,
                reasoningMode = conversation.reasoningMode,
                options = ChatGenerationOptions(
                    conversation.replyLengthPreference,
                    conversation.maxOutputTokens,
                    conversation.tokenLimitField,
                ),
                onToken = { token ->
                    content.append(token)
                    addedContent.append(token)
                    contentDirty = true
                    flush()
                },
                onReasoningToken = { token ->
                    reasoningContent.append(token)
                    reasoningDirty = true
                    flush()
                },
                onUsage = { completionTokens ->
                    meter.recordUsage(completionTokens)
                    metricsDirty = true
                    flush()
                },
                onFinish = { finishInfo = it },
            )
            currentCoroutineContext().ensureActive()
            if (addedContent.isBlank()) {
                throw IOException(
                    if (finishInfo.reachedLengthLimit && reasoningContent.isNotBlank()) settings.language.pick(
                        "已達輸出上限，但模型只回傳思考內容，沒有正文。",
                        "已达到输出上限，但模型只返回思考内容，没有正文。",
                    ) else settings.language.pick("API 沒有回傳文字內容。", "API 没有返回文字内容。"),
                )
            }
            if (!commit(MessageVersionStatus.COMPLETE)) throw historyChanged(settings)
            return finishInfo
        } catch (error: CancellationException) {
            finishCancelledGeneration()
            throw error
        } catch (error: Throwable) {
            if (!currentCoroutineContext().isActive) {
                finishCancelledGeneration()
                throw CancellationException("Generation stopped").apply { initCause(error) }
            }
            withContext(NonCancellable) { conversationRepository.rollbackDraft(draft, newMessage) }
            throw error
        }
    }

    private fun historyChanged(settings: AppSettings) = IOException(settings.language.pick(
        "生成期間對話已被修改，未覆寫原有歷史，請重新操作。",
        "生成期间对话已被修改，未覆盖原有历史，请重新操作。",
    ))

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
