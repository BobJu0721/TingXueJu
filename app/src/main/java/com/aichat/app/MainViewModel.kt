package com.aichat.app

import com.aichat.app.data.AppLanguage
import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.network.ApiException
import java.io.IOException

enum class Screen {
    CONVERSATIONS, CHAT, SETTINGS, MODELS, CHARACTERS, LIBRARY, PROFILE_EDIT,
    WORLD_SETS, WORLD_SET_EDIT, NEW_CHAT, CHAT_INFO,
}
enum class ErrorKind { GENERAL, CONTEXT_LENGTH }
enum class PendingAction { SEND, RETRY, RESEND_FROM_MESSAGE }
enum class ImportTarget { CHARACTER, PERSONA, WORLD_SET }
enum class ManualSummaryMode { UN_SUMMARIZED, REBUILD_ALL }

data class UiError(
    val title: String,
    val message: String,
    val suggestion: String,
    val kind: ErrorKind = ErrorKind.GENERAL,
)

data class PendingDocumentImport(
    val target: ImportTarget,
    val document: ImportedDocument,
) {
    val estimatedCalls: Int
        get() = ((document.text.length + IMPORT_CHUNK_SIZE - 1) / IMPORT_CHUNK_SIZE).coerceAtLeast(1) + 1
}

data class WorldTemplate(
    val name: String,
    val categories: List<String>,
)

internal fun mapError(error: Throwable, title: String, language: AppLanguage): UiError = when (error) {
    is ApiException -> when {
        error.isContextLengthError -> UiError(language.pick("上下文過長", "上下文过长"), language.pick("API 回報 ${error.statusCode}：${error.message}", "API 返回 ${error.statusCode}：${error.message}"), language.pick("可裁切舊訊息並重試，或建立新對話。", "可裁切旧消息并重试，或建立新对话。"), ErrorKind.CONTEXT_LENGTH)
        error.statusCode == 401 || error.statusCode == 403 -> UiError(language.pick("API Key 無效", "API Key 无效"), language.pick("API 回報 ${error.statusCode}：${error.message}", "API 返回 ${error.statusCode}：${error.message}"), language.pick("請檢查 Key、模型與供應商設定。", "请检查 Key、模型与供应商设置。"))
        error.statusCode == 429 -> UiError(language.pick("額度不足或請求過快", "额度不足或请求过快"), language.pick("API 回報 429：${error.message}", "API 返回 429：${error.message}"), language.pick("請稍後再試，或切換模型與供應商。", "请稍后再试，或切换模型与供应商。"))
        else -> UiError(title, language.pick("API 回報 ${error.statusCode}：${error.message}", "API 返回 ${error.statusCode}：${error.message}"), language.pick("請檢查模型與供應商設定。", "请检查模型与供应商设置。"))
    }
    is IOException -> UiError(title, error.message ?: language.pick("網路連線失敗。", "网络连接失败。"), language.pick("請檢查網路與 API 設定。", "请检查网络与 API 设置。"))
    else -> UiError(title, error.message ?: language.pick("發生未知錯誤。", "发生未知错误。"), language.pick("請稍後再試。", "请稍后再试。"))
}

data class ConversationSummaryPlan(
    val messagesToSummarize: List<MessageEntity>,
    val existingSummary: String,
    val summaryThroughAt: Long,
)

fun conversationSummaryPlan(
    conversation: ConversationEntity,
    messages: List<MessageEntity>,
    keepRecentMessages: Int,
    mode: ManualSummaryMode,
): ConversationSummaryPlan {
    val keepCount = keepRecentMessages.coerceIn(1, 100)
    val nonBlank = messages.filter { it.content.isNotBlank() }.sortedBy { it.createdAt }
    val candidates = when (mode) {
        ManualSummaryMode.UN_SUMMARIZED -> nonBlank.filter { it.createdAt > conversation.summaryThroughAt }
        ManualSummaryMode.REBUILD_ALL -> nonBlank
    }
    val messagesToSummarize = candidates.dropLast(keepCount)
    val summaryThroughAt = messagesToSummarize.lastOrNull()?.createdAt ?: conversation.summaryThroughAt
    val existingSummary = when (mode) {
        ManualSummaryMode.UN_SUMMARIZED -> conversation.summary
        ManualSummaryMode.REBUILD_ALL -> ""
    }
    return ConversationSummaryPlan(messagesToSummarize, existingSummary, summaryThroughAt)
}

internal val WORLD_TEMPLATE_CATEGORIES = listOf(
    "時代科技",
    "主要舞台",
    "核心衝突",
    "勢力陣營",
    "力量資源",
    "社會規則/禁忌",
    "角色相關歷史",
)

internal val DEFAULT_WORLD_TEMPLATES = listOf(
    WorldTemplate("奇幻世界模板", WORLD_TEMPLATE_CATEGORIES),
    WorldTemplate("科幻世界模板", WORLD_TEMPLATE_CATEGORIES),
    WorldTemplate("現代都市模板", WORLD_TEMPLATE_CATEGORIES),
    WorldTemplate("架空史詩模板", WORLD_TEMPLATE_CATEGORIES),
)
