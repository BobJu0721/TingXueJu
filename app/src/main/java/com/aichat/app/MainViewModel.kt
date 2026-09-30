package com.aichat.app

import com.aichat.app.data.AppLanguage
import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.Provider
import com.aichat.app.network.ApiException
import com.aichat.app.network.UnsupportedReasoningModeException
import java.io.IOException

enum class Screen {
    CONVERSATIONS, CHAT, SETTINGS, MODELS, CHARACTERS, LIBRARY, PROFILE_EDIT,
    WORLD_SETS, WORLD_SET_EDIT, NEW_CHAT, CHAT_INFO, API_SETTINGS,
}
enum class ErrorKind { GENERAL, CONTEXT_LENGTH, MODEL_SELECTION, CHAT_OPTIONS }
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
    is UnsupportedReasoningModeException -> UiError(
        language.pick("思考設定不受支援", "思考设置不受支持"), error.message.orEmpty(),
        language.pick("請開啟對話頂部的模型選擇頁，改選可用的思考模式後再試。", "请打开对话顶部的模型选择页，改选可用的思考模式后再试。"),
        ErrorKind.MODEL_SELECTION,
    )
    is ApiException -> mapApiError(error, title, language)
    is IOException -> UiError(title, error.message ?: language.pick("網路連線失敗。", "网络连接失败。"), language.pick("請檢查網路與 API 設定。", "请检查网络与 API 设置。"))
    else -> UiError(title, error.message ?: language.pick("發生未知錯誤。", "发生未知错误。"), language.pick("請稍後再試。", "请稍后再试。"))
}

private fun mapApiError(error: ApiException, title: String, language: AppLanguage): UiError {
    val details = buildString {
        append(if (error.isStreamError) language.pick("串流回報錯誤（HTTP ${error.statusCode}）：", "串流返回错误（HTTP ${error.statusCode}）：")
            else language.pick("API 回報 ${error.statusCode}：", "API 返回 ${error.statusCode}："))
        append(error.message)
        error.provider?.let { append("\n" + language.pick("供應商：", "供应商：") + it.label) }
        error.internalCode?.let { append("\n" + language.pick("錯誤碼：", "错误码：") + it) }
        error.requestId?.let { append("\nRequest ID: $it") }
    }
    fun result(traditionalTitle: String, simplifiedTitle: String, traditionalHint: String, simplifiedHint: String,
        kind: ErrorKind = ErrorKind.GENERAL) = UiError(language.pick(traditionalTitle, simplifiedTitle), details,
        language.pick(traditionalHint, simplifiedHint), kind)
    val cloudflare = error.provider == Provider.CLOUDFLARE
    return when {
        error.isContextLengthError -> result("上下文過長", "上下文过长", "可裁切舊訊息並重試，或建立新對話。", "可裁切旧消息并重试，或建立新对话。", ErrorKind.CONTEXT_LENGTH)
        error.isReasoningParameterError -> result("模型不接受思考參數", "模型不接受思考参数", "請至模型選擇頁改用自動模式，或選擇支援此設定的模型。", "请至模型选择页改用自动模式，或选择支持此设置的模型。", ErrorKind.MODEL_SELECTION)
        error.isTokenLimitParameterError -> result("模型不接受 Token 上限參數", "模型不接受 Token 上限参数", "請至對話資訊修改最大輸出 Token 或參數欄位。", "请至对话信息修改最大输出 Token 或参数字段。", ErrorKind.CHAT_OPTIONS)
        error.statusCode == 401 -> result("API Key 無效", "API Key 无效", "請檢查 Key 與供應商設定。", "请检查 Key 与供应商设置。")
        error.statusCode == 402 -> result("帳務或額度限制", "账务或额度限制", "請至供應商的 Billing 頁面檢查帳務狀態與可用額度。", "请至供应商的 Billing 页面检查账务状态与可用额度。")
        cloudflare && error.internalCode == "5035" -> result("模型需要付費方案", "模型需要付费方案", "此模型需要 Workers Paid 或適用的 AI Gateway 預付額度；也可自行選擇免費方案可用的模型。", "此模型需要 Workers Paid 或适用的 AI Gateway 预付额度；也可自行选择免费方案可用的模型。")
        cloudflare && error.internalCode == "5016" -> result("需要同意模型條款", "需要同意模型条款", "請至 Cloudflare 確認並同意該模型的使用條款後再試。", "请至 Cloudflare 确认并同意该模型的使用条款后再试。")
        cloudflare && error.internalCode in setOf("5018", "3041", "3023") -> result("帳號無法存取模型", "账号无法访问模型", "請檢查帳號的模型存取權限，必要時聯絡 Cloudflare 支援。", "请检查账号的模型访问权限，必要时联系 Cloudflare 支持。")
        error.statusCode == 403 -> result("權限或方案限制", "权限或方案限制", "請檢查帳號權限、方案與模型是否可用。", "请检查账号权限、方案与模型是否可用。")
        cloudflare && error.internalCode == "3036" -> result("每日免費額度已用完", "每日免费额度已用完", "請等待每日額度重設，或自行檢查供應商方案。", "请等待每日额度重置，或自行检查供应商方案。")
        cloudflare && error.internalCode == "3040" -> result("供應商容量不足", "供应商容量不足", "目前沒有可用推理容量，請稍後再試。", "目前没有可用推理容量，请稍后再试。")
        error.statusCode == 429 && (error.internalCode == "insufficient_quota" || error.message.contains("quota", true)) -> result("可用額度不足", "可用额度不足", "請檢查供應商可用額度及重設時間。", "请检查供应商可用额度及重置时间。")
        error.statusCode == 429 && (error.internalCode == "rate_limit_exceeded" || error.message.contains("rate limit", true)) -> result("請求過快", "请求过快", "請降低請求頻率，等待供應商限制重設後再試。", "请降低请求频率，等待供应商限制重置后再试。")
        error.statusCode == 429 -> result("額度不足或請求過快", "额度不足或请求过快", "請稍後再試，或檢查供應商額度與限制。", "请稍后再试，或检查供应商额度与限制。")
        error.statusCode >= 500 || error.isStreamError -> result("供應商生成失敗", "供应商生成失败", "請保留錯誤詳情與 Request ID，稍後再試或聯絡供應商。", "请保留错误详情与 Request ID，稍后再试或联系供应商。")
        else -> UiError(title, details, language.pick("請檢查模型與供應商設定。", "请检查模型与供应商设置。"))
    }
}

data class ConversationSummaryPlan(
    val messagesToSummarize: List<MessageEntity>,
    val existingSummary: String,
    val summaryThroughAt: Long,
    val summaryThroughOrder: Long = 0,
)

fun conversationSummaryPlan(
    conversation: ConversationEntity,
    messages: List<MessageEntity>,
    keepRecentMessages: Int,
    mode: ManualSummaryMode,
): ConversationSummaryPlan {
    val keepCount = keepRecentMessages.coerceIn(1, 100)
    val nonBlank = messages.filter { it.content.isNotBlank() && !it.excluded }.sortedBy { it.stableOrder }
    val candidates = when (mode) {
        ManualSummaryMode.UN_SUMMARIZED -> nonBlank.filter {
            if (conversation.summaryThroughOrder > 0) it.stableOrder > conversation.summaryThroughOrder
            else it.createdAt > conversation.summaryThroughAt
        }
        ManualSummaryMode.REBUILD_ALL -> nonBlank
    }
    val messagesToSummarize = candidates.dropLast(keepCount)
    val summaryThroughAt = messagesToSummarize.lastOrNull()?.createdAt ?: conversation.summaryThroughAt
    val summaryThroughOrder = messagesToSummarize.lastOrNull()?.stableOrder ?: conversation.summaryThroughOrder
    val existingSummary = when (mode) {
        ManualSummaryMode.UN_SUMMARIZED -> conversation.summary
        ManualSummaryMode.REBUILD_ALL -> ""
    }
    return ConversationSummaryPlan(messagesToSummarize, existingSummary, summaryThroughAt, summaryThroughOrder)
}

private val MessageEntity.stableOrder: Long get() = sortOrder.takeIf { it > 0 } ?: createdAt

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
