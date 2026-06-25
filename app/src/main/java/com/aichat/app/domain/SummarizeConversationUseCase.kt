package com.aichat.app.domain

import com.aichat.app.IMPORT_CHUNK_SIZE
import com.aichat.app.ManualSummaryMode
import com.aichat.app.conversationSummaryPlan
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.ConversationRepository
import com.aichat.app.mergeSummaryInstruction
import com.aichat.app.network.AiApiClient
import com.aichat.app.network.ApiChatMessage
import com.aichat.app.pick
import com.aichat.app.summarizeSystemInstruction
import java.io.IOException

class SummarizeConversationUseCase(
    private val conversationRepository: ConversationRepository,
    private val api: AiApiClient,
) {
    suspend operator fun invoke(
        conversationId: String,
        settings: AppSettings,
        key: String,
        keepRecentMessages: Int,
        mode: ManualSummaryMode,
    ): ConversationEntity? {
        val conversation = conversationRepository.getConversation(conversationId) ?: return null
        val history = conversationRepository.getMessages(conversationId).filter { it.content.isNotBlank() }
        val plan = conversationSummaryPlan(conversation, history, keepRecentMessages, mode)
        val older = plan.messagesToSummarize
        if (older.isEmpty()) {
            throw IOException(settings.language.pick(
                "\u76ee\u524d\u6c92\u6709\u8db3\u5920\u7684\u8f03\u65e9\u8a0a\u606f\u53ef\u4ee5\u6458\u8981\u3002",
                "\u76ee\u524d\u6ca1\u6709\u8db3\u591f\u7684\u8f83\u65e9\u6d88\u606f\u53ef\u4ee5\u6458\u8981\u3002",
            ))
        }
        val text = buildString {
            if (plan.existingSummary.isNotBlank()) appendLine("\u65e2\u6709\u6458\u8981\uff1a\n${plan.existingSummary}\n")
            older.forEach { appendLine("${it.role}: ${it.content}") }
        }
        val summaries = text.chunked(IMPORT_CHUNK_SIZE).map { chunk ->
            api.completeChat(settings, key, listOf(
                ApiChatMessage("system", settings.language.summarizeSystemInstruction()),
                ApiChatMessage("user", chunk),
            ))
        }
        val summary = if (summaries.size == 1) summaries.single() else api.completeChat(settings, key, listOf(
            ApiChatMessage("system", settings.language.mergeSummaryInstruction()),
            ApiChatMessage("user", summaries.joinToString("\n\n")),
        ))
        val updated = conversation.copy(summary = summary.trim(), summaryThroughAt = plan.summaryThroughAt)
        conversationRepository.updateConversation(updated)
        return updated
    }
}
