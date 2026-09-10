package com.aichat.app.network

import com.aichat.app.data.AppLanguage
import com.aichat.app.data.AppSettings
import com.aichat.app.data.Provider
import com.aichat.app.data.ReasoningMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReasoningPolicyTest {
    private data class Case(val provider: Provider, val model: String, val on: String?, val off: Boolean)
    private val cases = listOf(
        Case(Provider.GROQ, "qwen/qwen3.8-27b", """{"reasoning_effort":"medium","reasoning_format":"parsed"}""", true),
        Case(Provider.GROQ, "qwen/qwen3.6-27b", """{"reasoning_effort":"default","reasoning_format":"parsed"}""", true),
        Case(Provider.GROQ, "openai/gpt-oss-120b", """{"reasoning_effort":"medium","include_reasoning":true}""", false),
        Case(Provider.GROQ, "openai/gpt-oss-20b", """{"reasoning_effort":"medium","include_reasoning":true}""", false),
        Case(Provider.CEREBRAS, "gpt-oss-120b", """{"reasoning_effort":"medium","reasoning_format":"parsed"}""", false),
        Case(Provider.CLOUDFLARE, "@cf/qwen/qwen3.8-27b", """{"reasoning_effort":"medium"}""", false),
        Case(Provider.CLOUDFLARE, "@cf/zai-org/glm-4.7-flash", null, false),
        Case(Provider.GROQ, "llama-3.3-70b-versatile", null, false),
        Case(Provider.CEREBRAS, "llama-3.3-70b", null, false),
        Case(Provider.CLOUDFLARE, "@cf/meta/llama-3.1-8b-instruct", null, false),
        Case(Provider.GROQ, "new-qwen-gpt-oss-model", null, false),
        Case(Provider.CEREBRAS, "future-model", null, false),
        Case(Provider.CLOUDFLARE, "future-model", null, false),
    )

    @Test fun modelControlsMatchWireContractsAndUiCapabilities() {
        for (case in cases) {
            val settings = AppSettings(provider = case.provider, model = case.model)
            val policy = reasoningPolicy(case.provider, case.model)
            for (mode in ReasoningMode.entries) {
                val expected = when (mode) {
                    ReasoningMode.AUTO -> "{}"
                    ReasoningMode.ON -> case.on
                    ReasoningMode.OFF -> if (case.off) """{"reasoning_effort":"none"}""" else null
                }
                assertEquals("${case.model}/$mode", expected != null, policy.supports(mode))
                if (expected == null) {
                    assertThrows(UnsupportedReasoningModeException::class.java) { payload(settings, mode) }
                } else {
                    val body = payload(settings, mode)
                    assertEquals(case.model, body.remove("model"))
                    assertEquals(true, body.remove("stream"))
                    body.remove("messages")
                    assertTrue("${case.model}/$mode: $body", JSONObject(expected).similar(body))
                }
            }
        }
    }

    @Test fun allBackgroundCallsOmitControlsIncludingUnsupportedModels() {
        for (case in cases) {
            val body = AiApiClient().chatPayload(AppSettings(provider = case.provider, model = case.model), emptyList(), false)
            assertEquals(setOf("model", "stream", "messages"), body.keys().asSequence().toSet())
        }
    }

    @Test fun customEndpointsAreNotDetectedByUrlOrModelName() {
        for (provider in listOf(Provider.GROQ, Provider.CLOUDFLARE, Provider.CEREBRAS)) {
            val custom = AppSettings(provider = Provider.CUSTOM, customBaseUrl = provider.baseUrl, model = "gpt-oss-120b")
            assertTrue(payload(custom, ReasoningMode.ON).getJSONObject("reasoning").getBoolean("enabled"))
            assertEquals("none", payload(custom, ReasoningMode.OFF).getJSONObject("reasoning").getString("effort"))
        }
    }

    @Test fun modelSwitchRecomputesSupportWithoutChangingSavedMode() {
        val savedMode = ReasoningMode.OFF
        val before = AppSettings(provider = Provider.GROQ, model = "qwen/qwen3.8-27b")
        val after = before.copy(model = "openai/gpt-oss-120b")
        validateReasoningMode(before, savedMode)
        assertThrows(UnsupportedReasoningModeException::class.java) { validateReasoningMode(after, savedMode) }
        validateReasoningMode(after, ReasoningMode.AUTO)
        assertEquals(ReasoningMode.OFF, savedMode)
    }

    @Test fun unsupportedErrorsAndCapabilitiesAreLocalized() {
        val traditional = AppSettings(provider = Provider.CLOUDFLARE, model = "@cf/zai-org/glm-4.7-flash")
        val simplified = traditional.copy(language = AppLanguage.SIMPLIFIED_CHINESE)
        assertTrue(assertThrows(UnsupportedReasoningModeException::class.java) {
            validateReasoningMode(traditional, ReasoningMode.ON)
        }.message!!.contains("不支援"))
        assertTrue(assertThrows(UnsupportedReasoningModeException::class.java) {
            validateReasoningMode(simplified, ReasoningMode.ON)
        }.message!!.contains("不支持"))
    }

    @Test fun defaultsAreCurrentButExistingSelectionsArePreserved() {
        assertEquals("openai/gpt-oss-120b", AppSettings(provider = Provider.GROQ).model)
        assertEquals("gpt-oss-120b", AppSettings(provider = Provider.CEREBRAS).model)
        val old = AppSettings(provider = Provider.GROQ, model = "llama-3.3-70b-versatile")
        assertNotNull(retiredModelNotice(old.provider, old.model, old.language))
        payload(old, ReasoningMode.AUTO)
        assertEquals("llama-3.3-70b-versatile", old.model)
        assertNull(retiredModelNotice(Provider.CUSTOM, old.model, old.language))
        assertNull(retiredModelNotice(Provider.GROQ, "openai/gpt-oss-120b", old.language))
    }

    private fun payload(settings: AppSettings, mode: ReasoningMode) =
        AiApiClient().chatPayload(settings, listOf(ApiChatMessage("user", "test")), true, mode)
}
