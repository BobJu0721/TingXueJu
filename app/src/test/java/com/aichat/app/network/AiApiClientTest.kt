package com.aichat.app.network

import com.aichat.app.data.AppSettings
import com.aichat.app.data.Provider
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
}
