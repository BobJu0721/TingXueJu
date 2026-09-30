package com.aichat.app.network

import com.aichat.app.data.Provider
import com.aichat.app.data.AppLanguage
import com.aichat.app.pick
import org.json.JSONObject
import java.io.IOException

class ApiException(
    val statusCode: Int,
    override val message: String,
    val provider: Provider? = null,
    val internalCode: String? = null,
    val requestId: String? = null,
    val isStreamError: Boolean = false,
) : IOException(message) {
    val isReasoningParameterError: Boolean
        get() = statusCode in setOf(400, 422) && listOf(
            "reasoning_effort", "reasoning_format", "include_reasoning", "enable_thinking", "chat_template_kwargs",
        ).any { message.contains(it, ignoreCase = true) }

    val isTokenLimitParameterError: Boolean
        get() = statusCode in setOf(400, 422) && listOf(
            "max_tokens", "max_completion_tokens", "maximum output", "output token",
        ).any { message.contains(it, ignoreCase = true) }

    val isContextLengthError: Boolean
        get() {
            if (statusCode != 400 || isReasoningParameterError) return false
            if (internalCode in setOf("context_length_exceeded", "context_window_exceeded")) return true
            val text = message.lowercase()
            return listOf("maximum context length", "context length exceeded", "context window exceeded",
                "context_length_exceeded", "prompt is too long", "input is too long").any(text::contains) ||
                (text.contains("context") && listOf("exceed", "too long", "too large").any(text::contains))
        }

    companion object {
        internal fun fromBody(
            statusCode: Int,
            body: String,
            provider: Provider? = null,
            requestId: String? = null,
            isStreamError: Boolean = false,
            language: AppLanguage = AppLanguage.TRADITIONAL_CHINESE,
        ): ApiException {
            val json = runCatching { JSONObject(body) }.getOrNull()
            val error = json?.opt("error")
            val errors = json?.optJSONArray("errors")
            val first = (error as? JSONObject) ?: errors?.optJSONObject(0)
            val messages = if (errors != null) (0 until errors.length()).mapNotNull { index ->
                errors.optJSONObject(index)?.text("message")
            } else emptyList()
            val message = first?.text("message")?.takeIf { messages.isEmpty() }
                ?: messages.takeIf { it.isNotEmpty() }?.joinToString("\n")
                ?: (error as? String)?.takeIf { it.isNotBlank() }
                ?: json?.text("message")
                ?: body.takeIf { it.isNotBlank() }?.take(2000)
                ?: language.pick("伺服器沒有提供錯誤細節。", "服务器没有提供错误详情。")
            return ApiException(
                statusCode, message, provider,
                internalCode = first?.text("code") ?: json?.text("code") ?: first?.text("type"),
                requestId = requestId ?: json?.text("request_id") ?: first?.text("request_id"),
                isStreamError = isStreamError,
            )
        }

        internal fun isErrorEnvelope(json: JSONObject): Boolean =
            (!json.isNull("error") && json.opt("error") != false) ||
                (json.optJSONArray("errors")?.length() ?: 0) > 0 || json.opt("success") == false

        private fun JSONObject.text(key: String): String? =
            opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
    }
}
