package com.aichat.app.network

import com.aichat.app.data.AppSettings
import com.aichat.app.data.Provider
import com.aichat.app.data.ReasoningMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Intercept at the HTTP transport: exercise real request building and SSE consumption, without API keys. */
class ApiTransportTest {
    private val messages = listOf(ApiChatMessage("user", "test"))

    @Test fun groqRequestAndStructuredStreamKeepAnswerAndReasoningSeparate() = runBlocking {
        val settings = AppSettings(provider = Provider.GROQ, model = "openai/gpt-oss-120b")
        val client = AiApiClient(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://api.groq.com/openai/v1/chat/completions", chain.request().url.toString())
            val body = JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            assertTrue(body.getBoolean("include_reasoning"))
            assertFalse(body.has("reasoning_format"))
            reply(chain.request(), 200, sse(
                """{"choices":[{"delta":{"reasoning":"step "}}]}""",
                """{"choices":[{"delta":{"reasoning":"one","content":"answer"}}]}""",
                "[DONE]",
            ))
        }.build())
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        client.streamChat(settings, "test-key", messages, ReasoningMode.ON,
            onToken = { answer.append(it) }, onReasoningToken = { reasoning.append(it) })
        assertEquals("answer", answer.toString())
        assertEquals("step one", reasoning.toString())
    }

    @Test fun rawAndStructuredReasoningDoNotDuplicateEitherArrivalOrder() = runBlocking {
        for (structuredFirst in listOf(true, false)) {
            val raw = listOf(
                """{"choices":[{"delta":{"content":"<th"}}]}""",
                """{"choices":[{"delta":{"content":"ink>step one</th"}}]}""",
                """{"choices":[{"delta":{"content":"ink>answer"}}]}""",
            )
            val structured = """{"choices":[{"delta":{"reasoning_content":"step one"}}]}"""
            val events = (if (structuredFirst) listOf(structured) + raw else raw + structured) + "[DONE]"
            val client = client(200, sse(*events.toTypedArray()))
            val answer = StringBuilder()
            val reasoning = StringBuilder()
            client.streamChat(AppSettings(provider = Provider.CLOUDFLARE, model = "@cf/zai-org/glm-4.7-flash",
                cloudflareAccountId = "test-account"), "test-key", messages, ReasoningMode.AUTO,
                onToken = { answer.append(it) }, onReasoningToken = { reasoning.append(it) })
            assertEquals("answer", answer.toString())
            assertEquals("step one", reasoning.toString())
        }
    }

    @Test fun unsupportedModeIsRejectedBeforeAnyHttpRequest() {
        var calls = 0
        val api = AiApiClient(OkHttpClient.Builder().addInterceptor {
            calls++
            error("Should not make a request")
        }.build())
        assertThrows(UnsupportedReasoningModeException::class.java) {
            runBlocking { api.streamChat(AppSettings(provider = Provider.GROQ), "test-key", messages,
                ReasoningMode.OFF, onToken = {}) }
        }
        assertEquals(0, calls)
    }

    @Test fun paymentErrorIsPreservedWithoutRetry() {
        var calls = 0
        val api = AiApiClient(OkHttpClient.Builder().addInterceptor {
            calls++
            reply(it.request(), 402, """{"error":{"message":"Payment required to access this resource. Visit your billing tab."}}""")
        }.build())
        val error = assertThrows(ApiException::class.java) {
            runBlocking { api.streamChat(AppSettings(provider = Provider.CEREBRAS), "test-key", messages,
                ReasoningMode.ON, onToken = {}) }
        }
        assertEquals(1, calls)
        assertEquals(402, error.statusCode)
        assertEquals(Provider.CEREBRAS, error.provider)
        assertEquals("test-request", error.requestId)
        assertFalse(error.isContextLengthError)
    }

    @Test fun streamFailureAfterTextIsNotSwallowedAsSuccessfulCompletion() {
        val api = client(200, sse(
            """{"choices":[{"delta":{"content":"partial"}}]}""",
            """{"error":{"message":"No capacity","code":3040},"success":false}""",
            "[DONE]",
        ))
        val error = assertThrows(ApiException::class.java) {
            runBlocking { api.streamChat(AppSettings(provider = Provider.CLOUDFLARE, cloudflareAccountId = "test"),
                "test-key", messages, ReasoningMode.AUTO, onToken = {}) }
        }
        assertTrue(error.isStreamError)
        assertEquals(200, error.statusCode) // Transport succeeded; don't fabricate an HTTP status.
        assertEquals("3040", error.internalCode)
        assertEquals("No capacity", error.message)
        assertEquals("test-request", error.requestId)
    }

    @Test fun nonStreamingAndModelListErrorsAlsoPreserveCloudflareDetails() {
        val settings = AppSettings(provider = Provider.CLOUDFLARE, cloudflareAccountId = "test")
        val body = """{"success":false,"errors":[{"code":5035,"message":"Workers Paid required"}]}"""
        for (listModels in listOf(true, false)) {
            val api = client(403, body)
            val error = assertThrows(ApiException::class.java) {
                runBlocking { if (listModels) api.listModels(settings, "test-key") else api.completeChat(settings, "test-key", messages) }
            }
            assertEquals(Provider.CLOUDFLARE, error.provider)
            assertEquals("5035", error.internalCode)
            assertEquals("Workers Paid required", error.message)
        }
    }

    @Test fun cancellationStopsDeliveryAndKeepsCancellationType() {
        val api = client(200, sse(
            """{"choices":[{"delta":{"content":"first"}}]}""",
            """{"choices":[{"delta":{"content":"second"}}]}""",
            "[DONE]",
        ))
        var delivered = 0
        assertThrows(CancellationException::class.java) {
            runBlocking { api.streamChat(AppSettings(provider = Provider.GROQ), "test-key", messages,
                ReasoningMode.AUTO, onToken = { delivered++; throw CancellationException("cancel test") }) }
        }
        assertEquals(1, delivered)
    }

    @Test fun usageOnlyFinalEventReachesCallbackAfterContent() = runBlocking {
        val api = client(200, sse(
            """{"choices":[{"delta":{"content":"answer"}}],"usage":null}""",
            """{"choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":42,"total_tokens":1042}}""",
            "[DONE]",
        ))
        val events = mutableListOf<String>()
        api.streamChat(AppSettings(provider = Provider.GROQ), "test-key", messages, ReasoningMode.AUTO,
            onToken = { events.add(it) }, onUsage = { events.add("usage:$it") })
        assertEquals(listOf("answer", "usage:42"), events)
    }

    private fun client(code: Int, body: String) = AiApiClient(OkHttpClient.Builder().addInterceptor {
        reply(it.request(), code, body)
    }.build())

    private fun reply(request: okhttp3.Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .header("x-request-id", "test-request")
        .body(body.toResponseBody("text/event-stream".toMediaType())).build()

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }
}
