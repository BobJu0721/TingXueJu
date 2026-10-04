package com.aichat.app.domain

import com.aichat.app.composePrompt
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ConversationRepository
import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.GenerationContextEntity
import com.aichat.app.data.GenerationRequestFacts
import com.aichat.app.data.MessageVersionSource
import com.aichat.app.data.acceptsAiReply
import com.aichat.app.data.canRegenerate
import com.aichat.app.data.isModelVisible
import com.aichat.app.data.MessageVersionStatus
import com.aichat.app.data.ProfileRepository
import com.aichat.app.data.WorldInfoRepository
import com.aichat.app.data.sceneNoteValue
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

/**
 * 串流生成。
 *
 * 生成候選時先建立暫存版本與待提交路線並切換過去，畫面因此能預覽串流，而**原路線的資料
 * 完全不動**。只有正常完成（或停止時已有新增正文）才會提交；失敗、空結果與中斷都會回滾到
 * 原路線，不會覆寫或刪除任何既有的版本與後續對話。
 */
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
        val snapshot = conversationRepository.getGenerationSnapshot(request.conversationId) ?: return StreamFinishInfo()
        val stored = snapshot.conversation
        validateReasoningMode(settings, stored.reasoningMode)
        if (stored.historyRevision != request.expectedRevision) throw historyChanged(settings)
        val sourceBranch = snapshot.branch
        if (request.sourceBranchId != null && sourceBranch.id != request.sourceBranchId) throw historyChanged(settings)
        val sceneNote = request.sceneNote ?: sourceBranch.sceneNoteValue()
        val active = snapshot.conversation
        val conversation = temporarySummary?.takeIf { it.id == active.id } ?: active
        val history = snapshot.messages
        val target = request.targetMessageId?.let { id -> history.firstOrNull { it.id == id } }
        if (request.kind != ChatGenerationKind.NEW_REPLY) {
            if (target == null ||
                target.deleted ||
                target.currentVersionId != request.baseVersionId ||
                // 只有真正的使用者訊息或作者手寫的故事內容可以「讓 AI 接話」。
                (request.kind == ChatGenerationKind.ANSWER_FROM_USER && !target.acceptsAiReply) ||
                // 只有模型生成的回覆能被覆寫或續寫，手寫角色台詞不算 AI 回覆。
                (request.kind in setOf(ChatGenerationKind.ALTERNATIVE, ChatGenerationKind.CONTINUATION) && !target.canRegenerate) ||
                // 尾端的私人註記不算故事發言，跳過它才找得到真正的最後一則。
                (request.kind == ChatGenerationKind.CONTINUATION &&
                    history.lastOrNull { it.isModelVisible }?.id != target.id)
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
            sceneNote,
        )
        val source = when (request.kind) {
            ChatGenerationKind.ALTERNATIVE -> MessageVersionSource.REGENERATED
            ChatGenerationKind.CONTINUATION -> MessageVersionSource.CONTINUATION
            else -> MessageVersionSource.ORIGINAL
        }
        val initialContent = if (request.kind == ChatGenerationKind.CONTINUATION) baseVersion?.content.orEmpty() else ""
        val latest = conversationRepository.getConversation(request.conversationId) ?: throw historyChanged(settings)
        if (latest.historyRevision != request.expectedRevision || latest.activeBranchId != sourceBranch.id) throw historyChanged(settings)
        var draft = conversationRepository.prepareDraft(
            GenerationRequestFacts(request.kind, request.conversationId, request.targetMessageId),
            source,
            baseVersion,
            initialContent,
        ) ?: throw historyChanged(settings)

        val content = StringBuilder(initialContent)
        val addedContent = StringBuilder()
        val reasoningContent = StringBuilder()
        val activatedEntriesJson = toJsonStrings(prompt.activatedEntries.map { it.title })
        val throttle = StreamWriteThrottle(STREAM_WRITE_INTERVAL_NANOS)
        val meter = GenerationMeter()
        var contentDirty = false
        var reasoningDirty = false
        var metricsDirty = false
        var finishInfo = StreamFinishInfo()

        fun context(): GenerationContextEntity {
            val metrics = meter.snapshot(addedContent.toString(), reasoningContent.toString())
            return GenerationContextEntity(
                versionId = draft.version.id,
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
            draft = draft.copy(version = draft.version.copy(content = content.toString()))
            conversationRepository.updateDraft(draft, context())
            contentDirty = false
            reasoningDirty = false
            metricsDirty = false
        }

        suspend fun commit(status: MessageVersionStatus): Boolean {
            flush(force = true)
            draft = draft.copy(version = draft.version.copy(content = content.toString(), status = status))
            return conversationRepository.commitDraft(
                draft,
                status,
                context(),
                draft.expectedRevision,
                temporarySummary = temporarySummary?.summary,
                temporarySummaryThrough = temporarySummary?.summaryThroughOrder ?: 0,
            )
        }

        suspend fun abandon() = withContext(NonCancellable) { conversationRepository.rollbackDraft(draft) }

        suspend fun finishCancelledGeneration() = withContext(NonCancellable) {
            if (addedContent.isBlank()) abandon() else if (!commit(MessageVersionStatus.PARTIAL)) abandon()
        }

        try {
            onAssistantMessageCreated(draft.message.id)
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
            abandon()
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
