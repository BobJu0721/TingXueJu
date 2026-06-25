package com.aichat.app.network

import com.aichat.app.data.AppSettings
import com.aichat.app.data.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiApiClientTest {
    @Test
    fun agnesPayloadEnablesThinkingByDefault() {
        val payload = AiApiClient().chatPayload(
            AppSettings(provider = Provider.AGNES, model = Provider.AGNES.defaultModel),
            listOf(ApiChatMessage("user", "hi")),
            stream = true,
        )

        assertTrue(payload.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
    }

    @Test
    fun nonAgnesPayloadDoesNotAddThinking() {
        val payload = AiApiClient().chatPayload(
            AppSettings(provider = Provider.OPENROUTER),
            listOf(ApiChatMessage("user", "hi")),
            stream = true,
        )

        assertFalse(payload.has("chat_template_kwargs"))
    }

    @Test
    fun streamDeltaParserReadsContentAndReasoningContent() {
        val delta = AiApiClient().parseStreamDelta(
            """
            {
              "choices": [
                {
                  "delta": {
                    "content": "hello",
                    "reasoning_content": "thinking"
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("hello", delta.content)
        assertEquals("thinking", delta.reasoningContent)
    }

    @Test
    fun streamDeltaParserDoesNotTreatReasoningAsContent() {
        val delta = AiApiClient().parseStreamDelta(
            """
            {
              "choices": [
                {
                  "delta": {
                    "reasoning_content": "hidden chain"
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("", delta.content)
        assertEquals("hidden chain", delta.reasoningContent)
    }

    @Test
    fun streamDeltaParserIgnoresMalformedPayloads() {
        val delta = AiApiClient().parseStreamDelta("not-json")

        assertEquals("", delta.content)
        assertEquals("", delta.reasoningContent)
    }
}
