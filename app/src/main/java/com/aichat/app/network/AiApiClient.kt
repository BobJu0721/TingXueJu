package com.aichat.app.network

import com.aichat.app.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ApiChatMessage(
    val role: String,
    val content: String,
)

class AiApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeCall: Call? = null

    suspend fun listModels(settings: AppSettings, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val request = requestBuilder(settings, apiKey, "${settings.resolvedBaseUrl}/models").get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException(response.code, errorMessage(body))
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
        onToken: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("model", settings.model)
            .put("stream", true)
            .put("messages", JSONArray().apply {
                messages.forEach { message ->
                    put(JSONObject().put("role", message.role).put("content", message.content))
                }
            })
        val request = requestBuilder(settings, apiKey, "${settings.resolvedBaseUrl}/chat/completions")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    throw ApiException(response.code, errorMessage(body))
                }
                val source = response.body?.source() ?: throw IOException("伺服器沒有回傳內容")
                while (!source.exhausted()) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val token = runCatching {
                        JSONObject(data)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()
                    }.getOrDefault("")
                    if (token.isNotEmpty()) onToken(token)
                }
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
                if (!response.isSuccessful) throw ApiException(response.code, errorMessage(body))
                JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
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

    private fun chatPayload(
        settings: AppSettings,
        messages: List<ApiChatMessage>,
        stream: Boolean,
    ) = JSONObject()
        .put("model", settings.model)
        .put("stream", stream)
        .put("messages", JSONArray().apply {
            messages.forEach { message ->
                put(JSONObject().put("role", message.role).put("content", message.content))
            }
        })

    private fun errorMessage(body: String): String {
        if (body.isBlank()) return "伺服器沒有提供錯誤細節"
        return runCatching {
            val json = JSONObject(body)
            val error = json.opt("error")
            when (error) {
                is JSONObject -> error.optString("message", body)
                is String -> error
                else -> json.optString("message", body)
            }
        }.getOrDefault(body.take(500))
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class ApiException(
    val statusCode: Int,
    override val message: String,
) : IOException(message) {
    val isContextLengthError: Boolean
        get() = statusCode == 400 && listOf("context", "token", "length", "maximum").any {
            message.contains(it, ignoreCase = true)
        }
}
