package com.aichat.app.network

import com.aichat.app.data.*
import com.aichat.app.domain.ChatGenerationOptions
import com.aichat.app.domain.StreamFinishInfo
import org.junit.Assert.*
import org.junit.Test

class GenerationOptionsTest {
    private val messages = listOf(ApiChatMessage("user", "hi"))

    @Test fun unsetLimitSendsNeitherField() {
        val payload = AiApiClient().chatPayload(AppSettings(), messages, true, ReasoningMode.AUTO,
            ChatGenerationOptions(ReplyLengthPreference.DEFAULT, null, TokenLimitField.AUTO))
        assertFalse(payload.has("max_tokens"))
        assertFalse(payload.has("max_completion_tokens"))
    }

    @Test fun automaticLimitFieldUsesProviderContractAndNeverSendsBoth() {
        val client = AiApiClient()
        val groq = client.chatPayload(
            AppSettings(provider = Provider.GROQ), messages, true, ReasoningMode.AUTO,
            ChatGenerationOptions(ReplyLengthPreference.DEFAULT, 512, TokenLimitField.AUTO),
        )
        val openRouter = client.chatPayload(
            AppSettings(provider = Provider.OPENROUTER), messages, true, ReasoningMode.AUTO,
            ChatGenerationOptions(ReplyLengthPreference.DEFAULT, 256, TokenLimitField.AUTO),
        )
        assertEquals(512, groq.getInt("max_completion_tokens"))
        assertFalse(groq.has("max_tokens"))
        assertEquals(256, openRouter.getInt("max_tokens"))
        assertFalse(openRouter.has("max_completion_tokens"))
    }

    @Test fun explicitFieldOverridesProviderAndFinishReasonIsParsed() {
        val payload = AiApiClient().chatPayload(
            AppSettings(provider = Provider.CEREBRAS), messages, true, ReasoningMode.AUTO,
            ChatGenerationOptions(ReplyLengthPreference.DEFAULT, 99, TokenLimitField.MAX_TOKENS),
        )
        assertEquals(99, payload.getInt("max_tokens"))
        assertFalse(payload.has("max_completion_tokens"))
        val delta = AiApiClient().parseStreamDelta("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")
        assertEquals("length", delta.finishReason)
        assertTrue(StreamFinishInfo(delta.finishReason).reachedLengthLimit)
    }
}
