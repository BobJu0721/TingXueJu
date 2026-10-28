package com.aichat.app.network

import com.aichat.app.data.AppSettings
import com.aichat.app.data.Provider
import com.aichat.app.data.ReasoningMode
import com.aichat.app.mapError
import okhttp3.OkHttpClient
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesApiClientTest {
    private val messages = listOf(ApiChatMessage("user", "hi"))

    private fun client() = ResponsesApiClient(OkHttpClient(), Mutex()) { _, _, url, _ ->
        okhttp3.Request.Builder().url("https://example.com")
    }

    private val settings get() = AppSettings(provider = Provider.ZEN, model = "muse-spark-1.3-contributor-free")

    @Test fun payloadMatchesPiAiResponsesShape() {
        // 推理端點跟官方 v2 CLI 走 /inference/openai/v1（PR #110）；模型列表留在 /inference/v1。
        assertEquals("https://opencode.ai/inference/openai/v1", Provider.ZEN.baseUrl)
        assertEquals("https://opencode.ai/inference/v1/models", settings.resolvedModelsUrl)
        val body = client().responsesPayload(settings, messages, ReasoningMode.ON, null, "hi")
        assertEquals("muse-spark-1.3-contributor-free", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(false, body.getBoolean("store"))
        // input 是陣列；沒有指示訊息時先補一則帶 <env> 的 developer，再放對話。
        val input = body.getJSONArray("input")
        assertEquals(2, input.length())
        assertEquals("developer", input.getJSONObject(0).getString("role"))
        assertTrue(input.getJSONObject(0).getString("content").contains("<env>"))
        assertEquals("user", input.getJSONObject(1).getString("role"))
        val content = input.getJSONObject(1).getJSONArray("content")
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
        // system 走 developer（muse-spark 全系），並在結尾補上 <env>。
        assertEquals("developer", input.getJSONObject(0).getString("role"))
        assertTrue(input.getJSONObject(0).getString("content").startsWith("sys"))
        assertTrue(input.getJSONObject(0).getString("content").contains("<env>"))
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

    @Test fun invertedTimestampHexMatchesUpstreamEncoder() {
        // 上游 identifier.ts 的官方公式：~(now*0x1000+1) 取低 48 bits。
        fun upstream(now: Long): String {
            val current = now.toBigInteger().shiftLeft(12).add(java.math.BigInteger.ONE)
            val mask = java.math.BigInteger.ONE.shiftLeft(48).subtract(java.math.BigInteger.ONE)
            return current.not().and(mask).toString(16).padStart(12, '0')
        }
        for (clock in listOf(0L, 1L, 1_000_000_000_000L, 1_791_884_044_070L, System.currentTimeMillis())) {
            assertEquals(upstream(clock), ZenDisguise.invertedTimestampHex(clock))
            // counter 固定 1，反轉後末三位恆為 ffe。
            assertEquals("ffe", ZenDisguise.invertedTimestampHex(clock).takeLast(3))
        }
    }

    @Test fun sessionCounterRuleAcceptsOneAndRejectsDigest() {
        // #68 實測規則：counter = ~hex & 0xfff，接受當且僅當 0～63。
        assertEquals(1L, ZenDisguise.acceptedCounter(ZenDisguise.invertedTimestampHex()))
        assertTrue(ZenDisguise.isAcceptedSession(ZenDisguise.sessionFor("some conversation")))
        // 舊雜湊前綴：格式對，但 counter 幾乎一定落在外面。
        var rejected = 0
        for (i in 0 until 50) {
            val seed = byteArrayOf(0x73, 0x65, 0x73, 0x00) + "probe-$i".toByteArray()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(seed).take(6).joinToString("") { "%02x".format(it) }
            if (!ZenDisguise.isAcceptedSession("ses_${digest}0ySUUEO3741Ult")) rejected++
        }
        assertTrue("digest sessions should mostly be rejected, rejected=$rejected", rejected >= 45)
    }

    @Test fun sessionIsStablePerConversationAndDistinctAcross() {
        val first = ZenDisguise.sessionFor("conversation A")
        assertEquals(first, ZenDisguise.sessionFor("conversation A"))
        val other = ZenDisguise.sessionFor("conversation B")
        assertTrue(first != other)
        // Base62 尾巴不同，對話不會相撞。
        assertTrue(first.takeLast(14) != other.takeLast(14))
    }

    @Test fun freeTierErrorIsClassifiedBeforeKeyErrors() {
        val freeTier = ApiException.fromBody(403,
            """{"error":{"type":"FreeTierError","message":"free tier can only be used from within OpenCode"}}""",
            Provider.ZEN)
        assertTrue(freeTier.isFreeTierRestricted)
        val mapped = mapError(freeTier, "失敗", com.aichat.app.data.AppLanguage.TRADITIONAL_CHINESE)
        assertEquals("免費通道被拒絕", mapped.title)
        assertTrue(mapped.message.contains("free tier can only be used from within OpenCode"))
        // 一般 403 不受影響。
        val plain = ApiException.fromBody(403, "denied", Provider.ZEN)
        assertTrue(!plain.isFreeTierRestricted)
    }

    @Test fun wireHeadersOmitLegacySyntheticHeaders() {
        // #74：舊合成標頭任一存在即 403，兩者都不送才 200。
        val headers = ZenDisguise.headers("wire regression probe")
        val session = headers["x-opencode-session"].orEmpty()
        assertTrue(session.matches(Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")))
        assertEquals(session, headers["x-opencode-session-id"])
        assertEquals(session, headers["x-session-affinity"])
        assertEquals(session, headers["X-Session-Id"])
        assertFalse(headers.containsKey("x-opencode-request"))
        assertFalse(headers.containsKey("x-opencode-project"))
    }

    @Test fun gateToolsCarryNonEmptyProperties() {
        // #75：空 parameters.properties 會被拒，而且比不廣播更糟；官方工具名要帶真實 schema。
        for (tool in listOf(ZenDisguise.gateTool("bash"), ZenDisguise.responsesGateTool("read"))) {
            val params = tool.optJSONObject("function")?.optJSONObject("parameters")
                ?: tool.optJSONObject("parameters")!!
            assertTrue(params.optJSONObject("properties")!!.keys().asSequence().toList().isNotEmpty())
        }
        // bash 帶 command、read 帶 filePath，兩者都要求 required。
        val bash = ZenDisguise.gateTool("bash").getJSONObject("function").getJSONObject("parameters")
        assertTrue(bash.getJSONObject("properties").has("command"))
        assertEquals("command", bash.getJSONArray("required").getString(0))
        val read = ZenDisguise.gateTool("read").getJSONObject("function").getJSONObject("parameters")
        assertTrue(read.getJSONObject("properties").has("filePath"))
        assertEquals("filePath", read.getJSONArray("required").getString(0))
        // 真實工具的非空 schema 原樣保留。
        val real = JSONObject().put("type", "object").put("properties",
            JSONObject().put("command", JSONObject().put("type", "string")))
        assertTrue(ZenDisguise.gateParameters(real) === real)
        assertEquals(1, ZenDisguise.gateParameters(real).optJSONObject("properties")!!.length())
    }

    @Test fun userAgentUsesTheV2CliShape() {
        // #108 / PR #110：舊的 opencode/1.18.31 (...) 在新門下仍 429。
        val ua = ZenDisguise.headers("ua regression")["User-Agent"].orEmpty()
        assertEquals("opencode/latest/2.2.0/cli", ua)
        // 舊格式是三段帶括號；v2 是 channel/version/client 四段。
        assertFalse(ua.contains("("))
        assertEquals(4, ua.split("/").size)
    }

    @Test fun environmentBlockIsLiteralAndIdempotent() {
        // 實測：同樣內容寫成純文字仍 429，只有字面 <env> 標籤過。
        val block = ZenDisguise.environmentBlock()
        assertTrue(block.startsWith("<env>"))
        assertTrue(block.endsWith("</env>"))
        assertTrue(block.contains("Working directory: ."))
        // 已經有 <env> 就不重複插。
        assertEquals("", ZenDisguise.environmentSuffix("system prompt\n<env>\n  Platform: x\n</env>"))
        assertTrue(ZenDisguise.environmentSuffix("system prompt").contains("<env>"))
        // 完全沒有指示時仍要補。
        assertTrue(ZenDisguise.environmentSuffix("").contains("<env>"))
    }

    @Test fun chatPayloadCarriesEnvironmentBlockForZenOnly() {
        val zen = AiApiClient().chatPayload(
            AppSettings(provider = Provider.ZEN, model = "big-pickle"),
            listOf(ApiChatMessage("system", "you are helpful"), ApiChatMessage("user", "hi")),
            true, ReasoningMode.AUTO,
        )
        val zenMessages = zen.getJSONArray("messages")
        val system = zenMessages.getJSONObject(0).getString("content")
        assertEquals("system", zenMessages.getJSONObject(0).getString("role"))
        assertTrue(system.startsWith("you are helpful"))
        assertTrue(system.contains("<env>"))
        // 沒有 system 時自己補一則在最前面，user 訊息不動。
        val noSystem = AiApiClient().chatPayload(
            AppSettings(provider = Provider.ZEN, model = "big-pickle"),
            listOf(ApiChatMessage("user", "hi")), true, ReasoningMode.AUTO,
        ).getJSONArray("messages")
        assertEquals(2, noSystem.length())
        assertEquals("system", noSystem.getJSONObject(0).getString("role"))
        assertTrue(noSystem.getJSONObject(0).getString("content").contains("<env>"))
        assertEquals("hi", noSystem.getJSONObject(1).getString("content"))
        // 其他供應商完全不插。
        val groq = AiApiClient().chatPayload(
            AppSettings(provider = Provider.GROQ, model = "openai/gpt-oss-120b"),
            listOf(ApiChatMessage("system", "you are helpful"), ApiChatMessage("user", "hi")),
            true, ReasoningMode.AUTO,
        )
        assertFalse(groq.getJSONArray("messages").getJSONObject(0).getString("content").contains("<env>"))
    }

    @Test fun responsesPayloadCarriesEnvironmentOnInstructionMessage() {
        val withSystem = client().responsesPayload(
            settings, listOf(ApiChatMessage("system", "sys"), ApiChatMessage("user", "hi")),
            ReasoningMode.AUTO, null, "hi",
        ).getJSONArray("input")
        assertEquals("developer", withSystem.getJSONObject(0).getString("role"))
        assertTrue(withSystem.getJSONObject(0).getString("content").contains("<env>"))
        // 沒有指示訊息時補一則 developer 在最前面。
        val withoutSystem = client().responsesPayload(settings, messages, ReasoningMode.AUTO, null, "hi")
            .getJSONArray("input")
        assertEquals(2, withoutSystem.length())
        assertEquals("developer", withoutSystem.getJSONObject(0).getString("role"))
        assertTrue(withoutSystem.getJSONObject(0).getString("content").contains("<env>"))
        assertEquals("user", withoutSystem.getJSONObject(1).getString("role"))
    }
}
