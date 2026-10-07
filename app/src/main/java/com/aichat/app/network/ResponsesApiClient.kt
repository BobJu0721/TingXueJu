package com.aichat.app.network

import com.aichat.app.data.AppSettings
import com.aichat.app.data.Provider
import com.aichat.app.data.ReasoningMode
import com.aichat.app.domain.ChatGenerationOptions
import com.aichat.app.domain.StreamFinishInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * OpenAI Responses API 傳輸路徑（ZEN 專用，muse-spark-*）。
 *
 * completions 那套在這裡一個字都用不上：端點是 /v1/responses、body 用 input
 * 陣列、事件是 response.output_text.delta / reasoning 摘要事件、usage 藏在
 * response.completed 裡。payload 形狀照 pi-ai 的 openai-responses 實作
 * （buildParams）組裝，free-lane 門票照插件的 ensureResponsesFreeLaneShape。
 */
internal class ResponsesApiClient(
    private val client: OkHttpClient,
    private val requestMutex: Mutex,
    private val requestBuilder: (
        settings: AppSettings,
        apiKey: String,
        url: String,
        firstUserText: String?,
    ) -> okhttp3.Request.Builder,
) {
    @Volatile
    private var activeCall: Call? = null

    fun cancelActive() {
        activeCall?.cancel()
    }

    suspend fun stream(
        settings: AppSettings,
        apiKey: String,
        messages: List<ApiChatMessage>,
        reasoningMode: ReasoningMode,
        options: ChatGenerationOptions? = null,
        firstUserText: String? = null,
        onToken: suspend (String) -> Unit,
        onReasoningToken: suspend (String) -> Unit = {},
        onUsage: suspend (Long) -> Unit = {},
        onFinish: suspend (StreamFinishInfo) -> Unit = {},
    ) = requestMutex.withLock { withContext(Dispatchers.IO) {
        validateReasoningMode(settings, reasoningMode)
        val payload = responsesPayload(settings, messages, reasoningMode, options, firstUserText)
        val request = requestBuilder(settings, apiKey, "${settings.resolvedBaseUrl}/responses", firstUserText)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    throw response.apiError(settings, body)
                }
                val source = response.body?.source() ?: throw IOException("伺服器沒有回傳內容")
                val thinkTags = ThinkTagStreamParser()
                var finishSent = false

                suspend fun emitText(text: RoutedStreamText) {
                    if (text.content.isNotEmpty()) onToken(text.content)
                    if (text.reasoning.isNotEmpty()) onReasoningToken(text.reasoning)
                }

                while (!source.exhausted()) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith(":")) continue
                    if (!line.startsWith("event:")) continue
                    val event = line.removePrefix("event:").trim()
                    val dataLine = source.readUtf8Line() ?: break
                    if (!dataLine.startsWith("data:")) continue
                    val data = dataLine.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val parsed = parseResponsesEvent(event, data, settings, response.requestId())
                    parsed.usage?.let { onUsage(it) }
                    if (parsed.reasoning.isNotEmpty()) {
                        emitText(thinkTags.accept(parsed.reasoning))
                    }
                    if (parsed.content.isNotEmpty()) {
                        emitText(thinkTags.accept(parsed.content))
                    }
                    if (parsed.finished && !finishSent) {
                        finishSent = true
                        onFinish(StreamFinishInfo(parsed.finishReason))
                    }
                    if (parsed.failed != null) throw parsed.failed
                }
                emitText(thinkTags.finish())
                if (!finishSent) onFinish(StreamFinishInfo(null))
            }
        } finally {
            activeCall = null
        }
    } }

    internal fun responsesPayload(
        settings: AppSettings,
        messages: List<ApiChatMessage>,
        reasoningMode: ReasoningMode,
        options: ChatGenerationOptions? = null,
        firstUserText: String? = null,
    ): JSONObject {
        // muse-spark 全系需要 developer 角色承載系統指示（pi-ai convertResponsesMessages 同規則）。
        val instructionRole = if (isResponsesModel(settings.model)) "developer" else "system"
        val input = JSONArray()
        messages.forEach { message ->
            when (message.role) {
                // assistant 歷史必須回放成 output_text 的 message 項；input_text 只能出現在 user 身上。
                "assistant" -> input.put(JSONObject()
                    .put("type", "message")
                    .put("role", "assistant")
                    .put("status", "completed")
                    .put("content", JSONArray().put(JSONObject()
                        .put("type", "output_text")
                        .put("text", message.content)
                        .put("annotations", JSONArray()))))
                "system" -> input.put(JSONObject()
                    .put("role", instructionRole)
                    .put("content", message.content))
                else -> {
                    val content = JSONArray().put(JSONObject()
                        .put("type", "input_text")
                        .put("text", message.content))
                    input.put(JSONObject().put("role", message.role).put("content", content))
                }
            }
        }
        return JSONObject()
            .put("model", settings.model)
            .put("input", input)
            .put("stream", true)
            .put("store", false)
            .put("prompt_cache_key", ZenCacheKey.forRequest(firstUserText))
            .put("tools", JSONArray().apply {
                put(ZenDisguise.responsesGateTool("bash"))
                put(ZenDisguise.responsesGateTool("read"))
            })
            .put("tool_choice", "auto")
            .apply {
                applyResponsesReasoning(settings, reasoningMode)
                applyResponsesLimit(settings, options)
            }
    }

    private fun JSONObject.applyResponsesReasoning(settings: AppSettings, mode: ReasoningMode) {
        when (mode) {
            ReasoningMode.AUTO -> Unit
            ReasoningMode.ON -> {
                put("reasoning", JSONObject().put("effort", "medium").put("summary", "auto"))
                put("include", JSONArray().put("reasoning.encrypted_content"))
            }
            ReasoningMode.OFF -> put("reasoning", JSONObject().put("effort", "none"))
        }
    }

    private fun JSONObject.applyResponsesLimit(settings: AppSettings, options: ChatGenerationOptions?) {
        val limit = options?.maxOutputTokens ?: return
        // Responses 拒收低於 16 的 max_output_tokens（pi-ai issue #6265）。
        put("max_output_tokens", maxOf(limit, 16))
    }

    internal data class ResponsesEvent(
        val content: String = "",
        val reasoning: String = "",
        val usage: Long? = null,
        val finished: Boolean = false,
        val finishReason: String? = null,
        val failed: ApiException? = null,
    )

    internal fun parseResponsesEvent(
        event: String,
        data: String,
        settings: AppSettings,
        requestId: String?,
    ): ResponsesEvent {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return ResponsesEvent()
        if (ApiException.isErrorEnvelope(json)) {
            val status = json.optInt("status", json.optJSONObject("error")?.optInt("status") ?: 0)
                .takeIf { it in 400..599 } ?: 200
            throw ApiException.fromBody(status, data, settings.provider, requestId, isStreamError = true,
                language = settings.language)
        }
        return runCatching {
            when (event) {
                "response.output_text.delta" -> ResponsesEvent(content = json.optString("delta"))
                "response.refusal.delta" -> ResponsesEvent(content = json.optString("delta"))
                "response.reasoning_summary_text.delta",
                "response.reasoning_text.delta",
                -> ResponsesEvent(reasoning = json.optString("delta"))
                "response.completed", "response.incomplete" -> {
                    val response = json.optJSONObject("response") ?: json
                    val usage = response.optJSONObject("usage")?.opt("output_tokens") as? Number
                    val status = response.optString("status")
                    val incompleteReason = response.optJSONObject("incomplete_details")?.optString("reason")
                    ResponsesEvent(
                        usage = usage?.toLong(),
                        finished = true,
                        finishReason = if (incompleteReason.isNullOrBlank()) status else "$status.$incompleteReason",
                    )
                }
                "response.failed" -> {
                    val response = json.optJSONObject("response")
                    val message = response?.optJSONObject("error")?.optString("message")
                        ?: response?.optString("error").orEmpty()
                    ResponsesEvent(
                        finished = true,
                        failed = ApiException.fromBody(500, message.ifBlank { data }, settings.provider,
                            requestId, isStreamError = true, language = settings.language),
                    )
                }
                else -> ResponsesEvent()
            }
        }.getOrDefault(ResponsesEvent())
    }

    private fun Response.requestId(): String? = header("x-request-id") ?: header("request-id") ?: header("cf-ray")

    private fun Response.apiError(settings: AppSettings, body: String) =
        ApiException.fromBody(code, body, settings.provider, requestId(), language = settings.language)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** prompt_cache_key 跟 session 派生用同一個種子，跨輪保持快取親和性。 */
internal object ZenCacheKey {
    fun forRequest(firstUserText: String?): String =
        ZenDisguise.headers(firstUserText)["x-opencode-session"].orEmpty()
}
