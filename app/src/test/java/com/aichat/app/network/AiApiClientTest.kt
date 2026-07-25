package com.aichat.app.network

import com.aichat.app.data.AppSettings
import com.aichat.app.data.Provider
import com.aichat.app.data.ReasoningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiApiClientTest {
    private val message = listOf(ApiChatMessage("user", "hi"))

    @Test
    fun backgroundPayloadDoesNotAddReasoningControls() {
        val payload = AiApiClient().chatPayload(
            AppSettings(provider = Provider.AGNES),
            message,
            stream = false,
        )

        assertFalse(payload.has("reasoning"))
        assertFalse(payload.has("reasoning_effort"))
        assertFalse(payload.has("reasoning_format"))
        assertFalse(payload.has("chat_template_kwargs"))
    }

    @Test
    fun openRouterAndCustomUseUnifiedReasoning() {
        val client = AiApiClient()
        val openRouterOn = client.chatPayload(
            AppSettings(provider = Provider.OPENROUTER),
            message,
            stream = true,
            reasoningMode = ReasoningMode.ON,
        )
        val customOff = client.chatPayload(
            AppSettings(provider = Provider.CUSTOM, customBaseUrl = "https://example.com/v1"),
            message,
            stream = true,
            reasoningMode = ReasoningMode.OFF,
        )
        val automatic = client.chatPayload(
            AppSettings(provider = Provider.OPENROUTER),
            message,
            stream = true,
            reasoningMode = ReasoningMode.AUTO,
        )

        assertTrue(openRouterOn.getJSONObject("reasoning").getBoolean("enabled"))
        assertFalse(openRouterOn.getJSONObject("reasoning").getBoolean("exclude"))
        assertEquals("none", customOff.getJSONObject("reasoning").getString("effort"))
        assertFalse(customOff.has("chat_template_kwargs"))
        assertFalse(automatic.has("reasoning"))
    }

    @Test
    fun agnesAutoStaysEnabledAndOffDisablesThinking() {
        val client = AiApiClient()
        val automatic = client.chatPayload(
            AppSettings(provider = Provider.AGNES),
            message,
            stream = true,
            reasoningMode = ReasoningMode.AUTO,
        )
        val off = client.chatPayload(
            AppSettings(provider = Provider.AGNES),
            message,
            stream = true,
            reasoningMode = ReasoningMode.OFF,
        )

        assertTrue(automatic.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        assertFalse(off.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
    }

    @Test
    fun groqAndCerebrasUseDocumentedReasoningParameters() {
        val client = AiApiClient()
        val groqQwen = client.chatPayload(
            AppSettings(provider = Provider.GROQ, model = "qwen/qwen3-32b"),
            message,
            stream = true,
            reasoningMode = ReasoningMode.ON,
        )
        val cerebrasGptOss = client.chatPayload(
            AppSettings(provider = Provider.CEREBRAS, model = "gpt-oss-120b"),
            message,
            stream = true,
            reasoningMode = ReasoningMode.ON,
        )
        val cerebrasOff = client.chatPayload(
            AppSettings(provider = Provider.CEREBRAS, model = "zai-glm-4.7"),
            message,
            stream = true,
            reasoningMode = ReasoningMode.OFF,
        )

        assertEquals("parsed", groqQwen.getString("reasoning_format"))
        assertEquals("default", groqQwen.getString("reasoning_effort"))
        assertEquals("parsed", cerebrasGptOss.getString("reasoning_format"))
        assertEquals("medium", cerebrasGptOss.getString("reasoning_effort"))
        assertEquals("none", cerebrasOff.getString("reasoning_effort"))
    }

    @Test
    fun streamDeltaParserReadsContentAndReasoningAliases() {
        val delta = AiApiClient().parseStreamDelta(
            """
            {
              "choices": [{
                "delta": {
                  "content": "hello",
                  "reasoning_content": "thinking"
                }
              }]
            }
            """.trimIndent(),
        )

        assertEquals("hello", delta.content)
        assertEquals("thinking", delta.reasoningContent)
    }

    @Test
    fun streamDeltaParserReadsReasoningDetailsAndIgnoresEncryptedItems() {
        val delta = AiApiClient().parseStreamDelta(
            """
            {
              "choices": [{
                "delta": {
                  "reasoning_details": [
                    {"type": "reasoning.summary", "summary": "summary "},
                    {"type": "reasoning.encrypted", "data": "secret"},
                    {"type": "reasoning.text", "text": "details"}
                  ]
                }
              }]
            }
            """.trimIndent(),
        )

        assertEquals("summary details", delta.reasoningContent)
    }

    @Test
    fun streamDeltaParserReadsProviderSpecificDetailsAndMessageFallback() {
        val providerDelta = AiApiClient().parseStreamDelta(
            """
            {
              "choices": [{
                "delta": {
                  "provider_specific_fields": {
                    "reasoning_details": [
                      {"type": "reasoning.text", "text": "provider thinking"}
                    ]
                  }
                }
              }]
            }
            """.trimIndent(),
        )
        val messageDelta = AiApiClient().parseStreamDelta(
            """
            {
              "choices": [{
                "message": {
                  "reasoning": {"text": "message thinking"}
                }
              }]
            }
            """.trimIndent(),
        )

        assertEquals("provider thinking", providerDelta.reasoningContent)
        assertEquals("message thinking", messageDelta.reasoningContent)
    }

    @Test
    fun streamDeltaParserIgnoresMalformedPayloads() {
        val delta = AiApiClient().parseStreamDelta("not-json")

        assertEquals("", delta.content)
        assertEquals("", delta.reasoningContent)
    }

    @Test
    fun thinkTagParserHandlesTagsSplitAcrossChunks() {
        val parser = ThinkTagStreamParser()
        val lineBreak = 10.toChar().toString()
        val parts = listOf(
            parser.accept(lineBreak + "<th"),
            parser.accept("ink>step"),
            parser.accept(" one</th"),
            parser.accept("ink>" + lineBreak + "Answer"),
            parser.finish(),
        )

        assertEquals("step one", parts.joinToString("") { it.reasoning })
        assertEquals(lineBreak + "Answer", parts.joinToString("") { it.content })
    }
    @Test
    fun thinkTagParserLeavesOrdinaryContentUntouched() {
        val parser = ThinkTagStreamParser()
        val first = parser.accept("Use <think> literally")
        val second = parser.accept(" and keep writing")

        assertEquals("Use <think> literally and keep writing", first.content + second.content)
        assertEquals("", first.reasoning + second.reasoning)
    }

    @Test
    fun completeContentRemovesOnlyLeadingThinkBlock() {
        val client = AiApiClient()
        val lineBreak = 10.toChar().toString()

        assertEquals(
            "Answer",
            with(client) {
                (lineBreak + "<think>work</think>" + lineBreak + "Answer")
                    .withoutLeadingThinkBlock()
            },
        )
        assertEquals(
            "Keep <think>this</think>",
            with(client) { "Keep <think>this</think>".withoutLeadingThinkBlock() },
        )
    }}