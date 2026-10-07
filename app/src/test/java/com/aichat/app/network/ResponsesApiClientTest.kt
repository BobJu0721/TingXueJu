package com.aichat.app.network

import com.aichat.app.data.AppSettings
import com.aichat.app.data.Provider
import com.aichat.app.data.ReasoningMode
import okhttp3.OkHttpClient
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesApiClientTest {
    private val settings = AppSettings(provider = Provider.ZEN, model = "muse-spark-1.3-contributor-free")
    private val messages = listOf(ApiChatMessage("user", "hi"))

    private fun client() = ResponsesApiClient(OkHttpClient(), Mutex()) { _, _, url, _ ->
        okhttp3.Request.Builder().url("https://example.com")
    }

    @Test fun payloadMatchesPiAiResponsesShape() {
        val body = client().responsesPayload(settings, messages, ReasoningMode.ON, null, "hi")
        assertEquals("muse-spark-1.3-contributor-free", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(false, body.getBoolean("store"))
        // input 是陣列，每則 content 是 input_text 塊。
        val input = body.getJSONArray("input")
        assertEquals(1, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
        val content = input.getJSONObject(0).getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("hi", content.getJSONObject(0).getString("text"))
        // reasoning 是物件寫法，不是 reasoning_effort 字串。
        assertEquals("medium", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("auto", body.getJSONObject("reasoning").getString("summary"))
        assertFalse(body.has("reasoning_effort"))
        // free-lane 門票：扁平 tools + tool_choice auto。
        val tools = body.getJSONArray("tools")
        assertEquals(setOf("bash", "read"), (0 until tools.length()).mapTo(mutableSetOf()) {
            tools.getJSONObject(it).getString("name")
        })
        assertEquals("auto", body.getString("tool_choice"))
    }

    @Test fun payloadConvertsAssistantHistoryAndSystemRole() {
        val history = listOf(
            ApiChatMessage("system", "sys"),
            ApiChatMessage("user", "hi"),
            ApiChatMessage("assistant", "hello"),
        )
        val body = client().responsesPayload(settings, history, ReasoningMode.AUTO, null, "hi")
        val input = body.getJSONArray("input")
        assertEquals(3, input.length())
        // system 走 developer（muse-spark 全系）。
        assertEquals("developer", input.getJSONObject(0).getString("role"))
        assertEquals("sys", input.getJSONObject(0).getString("content"))
        // user 维持 input_text。
        assertEquals("input_text", input.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
        // assistant 歷史回放成 output_text 的 message 項。
        val replayed = input.getJSONObject(2)
        assertEquals("message", replayed.getString("type"))
        assertEquals("assistant", replayed.getString("role"))
        assertEquals("output_text", replayed.getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("hello", replayed.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun payloadAutoOmitsReasoningAndOffSendsNone() {
        val auto = client().responsesPayload(settings, messages, ReasoningMode.AUTO, null, "hi")
        assertFalse(auto.has("reasoning"))
        val off = client().responsesPayload(settings, messages, ReasoningMode.OFF, null, "hi")
        assertEquals("none", off.getJSONObject("reasoning").getString("effort"))
        assertFalse(off.has("include"))
    }

    @Test fun payloadClampsMaxOutputTokensToSixteen() {
        val options = com.aichat.app.domain.ChatGenerationOptions(
            com.aichat.app.data.ReplyLengthPreference.DEFAULT, 5,
            com.aichat.app.data.TokenLimitField.MAX_TOKENS,
        )
        val body = client().responsesPayload(settings, messages, ReasoningMode.AUTO, options, "hi")
        assertEquals(16, body.getInt("max_output_tokens"))
    }

    @Test fun eventParserReadsTextAndReasoningDeltas() {
        val client = client()
        val text = client.parseResponsesEvent(
            "response.output_text.delta", """{"delta":"hello"}""", settings, null)
        assertEquals("hello", text.content)
        val reasoning = client.parseResponsesEvent(
            "response.reasoning_summary_text.delta", """{"delta":"thinking"}""", settings, null)
        assertEquals("thinking", reasoning.reasoning)
    }

    @Test fun eventParserReadsUsageAndCompletion() {
        val client = client()
        val done = client.parseResponsesEvent(
            "response.completed",
            """{"response":{"status":"completed","usage":{"output_tokens":42}}}""",
            settings, null,
        )
        assertTrue(done.finished)
        assertEquals(42L, done.usage)
        assertEquals("completed", done.finishReason)
        val incomplete = client.parseResponsesEvent(
            "response.incomplete",
            """{"response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}""",
            settings, null,
        )
        assertTrue(incomplete.finished)
        assertEquals("incomplete.max_output_tokens", incomplete.finishReason)
        assertNull(incomplete.usage)
    }

    @Test fun usesResponsesApiRoutesByModelPrefix() {
        val api = AiApiClient()
        assertTrue(api.usesResponsesApi(AppSettings(provider = Provider.ZEN, model = "muse-spark-1.3-contributor-free")))
        assertTrue(api.usesResponsesApi(AppSettings(provider = Provider.ZEN, model = "MUSE-SPARK-9-free")))
        assertFalse(api.usesResponsesApi(AppSettings(provider = Provider.ZEN, model = "big-pickle")))
        assertFalse(api.usesResponsesApi(AppSettings(provider = Provider.GROQ, model = "muse-spark-1.3-contributor-free")))
    }

    @Test fun decodedFixturesMatchR4Capture() {
        // R4 實測回放：reasoning 摘要事件與正文事件。
        val client = client()
        val summary = client.parseResponsesEvent(
            "response.reasoning_summary_text.delta",
            JSONObject().put("delta", "Generating a friendly greeting response without tool calls.").toString(),
            settings, null,
        )
        assertEquals("Generating a friendly greeting response without tool calls.", summary.reasoning)
        assertEquals("", summary.content)
    }
}
