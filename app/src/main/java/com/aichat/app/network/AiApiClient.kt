package com.aichat.app.network

import com.aichat.app.data.AppSettings
import com.aichat.app.data.ReasoningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ApiChatMessage(
    val role: String,
    val content: String,
)

internal data class StreamDelta(
    val content: String = "",
    val reasoningContent: String = "",
)

class AiApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build(),
) {

    @Volatile
    private var activeCall: Call? = null

    suspend fun listModels(settings: AppSettings, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val request = requestBuilder(settings, apiKey, settings.resolvedModelsUrl).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw response.apiError(settings, body)
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                buildList {
                    for (index in 0 until data.length()) {
                        data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }.sorted()
            }
        }

    suspend fun streamChat(
        settings: AppSettings,
        apiKey: String,
        messages: List<ApiChatMessage>,
        reasoningMode: ReasoningMode,
        onToken: suspend (String) -> Unit,
        onReasoningToken: suspend (String) -> Unit = {},
        onUsage: suspend (Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val payload = chatPayload(settings, messages, stream = true, reasoningMode = reasoningMode)
        val request = requestBuilder(settings, apiKey, "${settings.resolvedBaseUrl}/chat/completions")
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
                var structuredReasoningSeen = false
                var rawReasoningSeen = false

                suspend fun emitText(text: RoutedStreamText) {
                    if (text.content.isNotEmpty()) onToken(text.content)
                    if (!structuredReasoningSeen && text.reasoning.isNotEmpty()) {
                        rawReasoningSeen = true
                        onReasoningToken(text.reasoning)
                    }
                }

                while (!source.exhausted()) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = parseStreamDelta(data, settings, response.requestId())
                    parseCompletionTokens(data)?.let { onUsage(it) }
                    if (delta.reasoningContent.isNotEmpty() && !rawReasoningSeen) {
                        structuredReasoningSeen = true
                        onReasoningToken(delta.reasoningContent)
                    }
                    if (delta.content.isNotEmpty()) {
                        emitText(thinkTags.accept(delta.content))
                    }
                }
                emitText(thinkTags.finish())
            }
        } finally {
            activeCall = null
        }
    }

    suspend fun completeChat(
        settings: AppSettings,
        apiKey: String,
        messages: List<ApiChatMessage>,
    ): String = withContext(Dispatchers.IO) {
        val payload = chatPayload(settings, messages, stream = false)
        val request = requestBuilder(settings, apiKey, "${settings.resolvedBaseUrl}/chat/completions")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw response.apiError(settings, body)
                JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                    .withoutLeadingThinkBlock()
                    .ifBlank { throw IOException("API 沒有回傳文字內容。") }
            }
        } finally {
            activeCall = null
        }
    }

    fun cancelActive() {
        activeCall?.cancel()
        activeCall = null
    }

    private fun requestBuilder(settings: AppSettings, apiKey: String, url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .apply {
                if (settings.provider.name == "OPENROUTER") {
                    header("X-Title", "AI Chat Android")
                }
            }

    internal fun chatPayload(
        settings: AppSettings,
        messages: List<ApiChatMessage>,
        stream: Boolean,
        reasoningMode: ReasoningMode? = null,
    ) = JSONObject()
        .put("model", settings.model)
        .put("stream", stream)
        .put("messages", JSONArray().apply {
            messages.forEach { message ->
                put(JSONObject().put("role", message.role).put("content", message.content))
            }
        })
        .apply { applyReasoning(settings, reasoningMode) }

    private fun JSONObject.applyReasoning(settings: AppSettings, mode: ReasoningMode?) {
        if (mode == null) return
        validateReasoningMode(settings, mode)
        reasoningPolicy(settings.provider, settings.model).applyTo(this, mode)
    }
    internal fun parseStreamDelta(data: String, settings: AppSettings? = null, requestId: String? = null): StreamDelta {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return StreamDelta()
        // Keep provider failures outside the tolerant delta parser: errors must reach the caller.
        if (ApiException.isErrorEnvelope(json)) {
            val status = json.optInt("status", json.optJSONObject("error")?.optInt("status") ?: 0)
                .takeIf { it in 400..599 } ?: 200
            throw ApiException.fromBody(status, data, settings?.provider, requestId, isStreamError = true,
                language = settings?.language ?: com.aichat.app.data.AppLanguage.TRADITIONAL_CHINESE)
        }
        return runCatching {
            val choice = json
                .optJSONArray("choices")
                ?.optJSONObject(0)
            val delta = choice?.optJSONObject("delta")
            val message = choice?.optJSONObject("message")

            StreamDelta(
                content = delta?.opt("content") as? String ?: "",
                reasoningContent = delta.reasoningText().ifBlank { message.reasoningText() },
            )
        }.getOrDefault(StreamDelta())
    }

    internal fun parseCompletionTokens(data: String): Long? {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return null
        val usage = json.optJSONObject("usage")
        val groqUsage = json.optJSONObject("x_groq")?.optJSONObject("usage")
        return listOfNotNull(usage, groqUsage).firstNotNullOfOrNull {
            val value = it.opt("completion_tokens") as? Number
            value?.toDouble()?.takeIf { count -> count.isFinite() && count >= 0 && count < Long.MAX_VALUE.toDouble() && count % 1.0 == 0.0 }?.toLong()
        }
    }

    private fun JSONObject?.reasoningText(includeProviderSpecific: Boolean = true): String {
        if (this == null) return ""
        val direct = firstReasoningValue("reasoning_content", "reasoning", "thinking", "thought")
        if (direct.isNotEmpty()) return direct
        val details = reasoningValue(opt("reasoning_details"))
        if (details.isNotEmpty()) return details
        return if (includeProviderSpecific) {
            optJSONObject("provider_specific_fields").reasoningText(includeProviderSpecific = false)
        } else {
            ""
        }
    }

    private fun JSONObject.firstReasoningValue(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            reasoningValue(opt(key)).takeIf { it.isNotEmpty() }
        }.orEmpty()

    private fun reasoningValue(value: Any?): String = when (value) {
        is String -> value.takeUnless {
            it.isBlank() || it.equals("[REDACTED]", ignoreCase = true)
        }.orEmpty()
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                append(reasoningValue(value.opt(index)))
            }
        }
        is JSONObject -> {
            val type = value.optString("type")
            if (type.contains("encrypted", ignoreCase = true) ||
                type.contains("redacted", ignoreCase = true)
            ) {
                ""
            } else {
                val keys = when (type) {
                    "reasoning.summary" -> arrayOf("summary")
                    "reasoning.text" -> arrayOf("text")
                    else -> arrayOf("text", "summary", "content")
                }
                keys.firstNotNullOfOrNull { key ->
                    reasoningValue(value.opt(key)).takeIf { it.isNotEmpty() }
                }.orEmpty()
            }
        }
        else -> ""
    }

    internal fun String.withoutLeadingThinkBlock(): String {
        val firstText = indexOfFirst { !it.isWhitespace() }
        if (firstText < 0 || !startsWith(THINK_OPEN, firstText)) return this
        val close = indexOf(THINK_CLOSE, firstText + THINK_OPEN.length)
        return if (close < 0) this else substring(close + THINK_CLOSE.length).trimStart()
    }
    private fun Response.requestId(): String? = header("x-request-id") ?: header("request-id") ?: header("cf-ray")

    private fun Response.apiError(settings: AppSettings, body: String) =
        ApiException.fromBody(code, body, settings.provider, requestId(), language = settings.language)

    companion object {
        private const val THINK_OPEN = "<think>"
        private const val THINK_CLOSE = "</think>"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal data class RoutedStreamText(
    val content: String = "",
    val reasoning: String = "",
)

internal class ThinkTagStreamParser {
    private enum class State { UNDECIDED, THINKING, CONTENT }

    private var state = State.UNDECIDED
    private val pending = StringBuilder()

    fun accept(value: String): RoutedStreamText {
        if (value.isEmpty()) return RoutedStreamText()
        if (state == State.CONTENT) return RoutedStreamText(content = value)
        pending.append(value)
        return when (state) {
            State.UNDECIDED -> decide()
            State.THINKING -> drainThinking()
            State.CONTENT -> RoutedStreamText(content = value)
        }
    }

    fun finish(): RoutedStreamText {
        val remaining = pending.toString()
        pending.clear()
        return when (state) {
            State.UNDECIDED -> RoutedStreamText(content = remaining)
            State.THINKING -> RoutedStreamText(reasoning = remaining)
            State.CONTENT -> RoutedStreamText()
        }
    }

    private fun decide(): RoutedStreamText {
        val firstText = pending.indexOfFirst { !it.isWhitespace() }
        if (firstText < 0) return RoutedStreamText()
        val candidate = pending.substring(firstText)
        if (OPEN.startsWith(candidate)) return RoutedStreamText()
        if (!candidate.startsWith(OPEN)) {
            state = State.CONTENT
            return RoutedStreamText(content = takePending())
        }
        state = State.THINKING
        pending.clear()
        pending.append(candidate.removePrefix(OPEN))
        return drainThinking()
    }

    private fun drainThinking(): RoutedStreamText {
        val value = pending.toString()
        val close = value.indexOf(CLOSE)
        if (close >= 0) {
            state = State.CONTENT
            pending.clear()
            return RoutedStreamText(
                reasoning = value.substring(0, close),
                content = value.substring(close + CLOSE.length),
            )
        }

        val keep = longestClosingPrefix(value)
        val reasoning = value.dropLast(keep)
        pending.clear()
        if (keep > 0) pending.append(value.takeLast(keep))
        return RoutedStreamText(reasoning = reasoning)
    }

    private fun takePending(): String = pending.toString().also { pending.clear() }

    private fun longestClosingPrefix(value: String): Int {
        for (length in minOf(value.length, CLOSE.length - 1) downTo 1) {
            if (CLOSE.startsWith(value.takeLast(length))) return length
        }
        return 0
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
