package com.aichat.app.domain

import com.aichat.app.data.AppLanguage
import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.Provider
import com.aichat.app.data.ReplyLengthPreference
import com.aichat.app.data.TokenLimitField
import com.aichat.app.data.isModelVisible
import com.aichat.app.pick

enum class ChatGenerationKind { NEW_REPLY, ANSWER_FROM_USER, ALTERNATIVE, CONTINUATION }

data class ChatGenerationRequest(
    val kind: ChatGenerationKind,
    val conversationId: String,
    val targetMessageId: String? = null,
    val baseVersionId: String? = null,
    val expectedRevision: Long,
    val sourceBranchId: String? = null,
    val sceneNote: com.aichat.app.data.SceneNote? = null,
)

data class ChatGenerationOptions(
    val replyLengthPreference: ReplyLengthPreference,
    val maxOutputTokens: Int?,
    val tokenLimitField: TokenLimitField,
) {
    fun resolvedTokenField(provider: Provider): TokenLimitField = when (tokenLimitField) {
        TokenLimitField.AUTO -> when (provider) {
            Provider.GROQ, Provider.CEREBRAS -> TokenLimitField.MAX_COMPLETION_TOKENS
            else -> TokenLimitField.MAX_TOKENS
        }
        else -> tokenLimitField
    }
}

data class EffectiveHistory(
    val promptHistory: List<MessageEntity>,
    val worldHistory: List<MessageEntity>,
    val includeSummary: Boolean,
)

object EffectiveHistoryResolver {
    fun resolve(
        conversation: ConversationEntity,
        messages: List<MessageEntity>,
        kind: ChatGenerationKind = ChatGenerationKind.NEW_REPLY,
        target: MessageEntity? = null,
    ): EffectiveHistory {
        val selected = messages.asSequence()
            // 私人註記、刪除標記與「不提供給 AI」都在這裡排除，後續的掃描深度與保留名額才正確。
            .filter { it.isModelVisible }
            .filter { message ->
                when (kind) {
                    ChatGenerationKind.NEW_REPLY -> true
                    ChatGenerationKind.ANSWER_FROM_USER, ChatGenerationKind.CONTINUATION ->
                        target == null || message.stableOrder <= target.stableOrder
                    ChatGenerationKind.ALTERNATIVE -> target == null || message.stableOrder < target.stableOrder
                }
            }
            .sortedBy { it.stableOrder }
            .toList()
        val cutoff = target?.stableOrder
        val ordered = messages.any { it.sortOrder > 0 }
        val summaryBoundary = if (ordered) conversation.summaryThroughOrder else conversation.summaryThroughAt
        val storedContextBoundary = if (ordered) conversation.contextStartOrder else conversation.contextStartAt
        val contextBoundary = if (cutoff != null && cutoff < storedContextBoundary) 0 else storedContextBoundary
        val includeSummary = conversation.summary.isNotBlank() &&
            (cutoff == null || summaryBoundary <= cutoff)
        val prompt = selected.filter {
            (it.stableOrder >= contextBoundary ||
                (target?.id == it.id && kind in setOf(ChatGenerationKind.ANSWER_FROM_USER, ChatGenerationKind.CONTINUATION))) &&
                (!includeSummary || it.stableOrder > summaryBoundary ||
                    (target?.id == it.id && kind in setOf(ChatGenerationKind.ANSWER_FROM_USER, ChatGenerationKind.CONTINUATION)))
        }
        return EffectiveHistory(prompt, selected, includeSummary)
    }
}

private val MessageEntity.stableOrder: Long get() = sortOrder.takeIf { it > 0 } ?: createdAt

data class StreamFinishInfo(val reason: String? = null) {
    val reachedLengthLimit: Boolean get() = reason.equals("length", ignoreCase = true)
}

internal fun ReplyLengthPreference.instruction(language: AppLanguage): String? = when (this) {
    ReplyLengthPreference.DEFAULT -> null
    ReplyLengthPreference.SHORT -> language.pick(
        "回覆篇幅偏好：優先簡潔，避免不必要的展開。",
        "回复篇幅偏好：优先简洁，避免不必要的展开。",
    )
    ReplyLengthPreference.MEDIUM -> language.pick(
        "回覆篇幅偏好：適量描述，兼顧對白與必要細節。",
        "回复篇幅偏好：适量描述，兼顾对白与必要细节。",
    )
    ReplyLengthPreference.DETAILED -> language.pick(
        "回覆篇幅偏好：提供較充分的細節、動作與情境描寫。",
        "回复篇幅偏好：提供较充分的细节、动作与情境描写。",
    )
}
