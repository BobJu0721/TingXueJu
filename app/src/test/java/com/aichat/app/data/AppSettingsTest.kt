package com.aichat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun builtInProviderUsesItsBaseUrl() {
        val settings = AppSettings(provider = Provider.GROQ, customBaseUrl = "https://ignored.example/v1/")

        assertEquals("https://api.groq.com/openai/v1", settings.resolvedBaseUrl)
    }

    @Test
    fun cloudflareBuildsAccountSpecificUrls() {
        val settings = AppSettings(
            provider = Provider.CLOUDFLARE,
            cloudflareAccountId = " 0123456789abcdef ",
        )

        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/0123456789abcdef/ai/v1",
            settings.resolvedBaseUrl,
        )
        assertEquals("${settings.resolvedBaseUrl.removeSuffix("/v1")}/models/search?format=openrouter&per_page=100", settings.resolvedModelsUrl)
    }

    @Test
    fun agnesProviderUsesOfficialEndpointAndModel() {
        assertEquals("https://apihub.agnes-ai.com/v1", Provider.AGNES.baseUrl)
        assertEquals("agnes-2.0-flash", Provider.AGNES.defaultModel)
    }

    @Test
    fun customProviderTrimsTrailingSlash() {
        val settings = AppSettings(provider = Provider.CUSTOM, customBaseUrl = "https://example.com/v1/")

        assertEquals("https://example.com/v1", settings.resolvedBaseUrl)
    }

    @Test
    fun customEndpointPresetsRoundTripJson() {
        val presets = listOf(CustomEndpointPreset("one", "Local", "http://127.0.0.1:11434/v1"))

        assertEquals(presets, SettingsRepository.decodeCustomEndpointPresets(SettingsRepository.encodeCustomEndpointPresets(presets)))
    }

    @Test
    fun customEndpointPresetUpsertUpdatesExistingId() {
        val updated = SettingsRepository.upsertCustomEndpointPreset(
            listOf(CustomEndpointPreset("one", "Old", "https://old.example/v1")),
            CustomEndpointPreset("one", "New", "https://new.example/v1"),
        )

        assertEquals(listOf(CustomEndpointPreset("one", "New", "https://new.example/v1")), updated)
    }

    @Test
    fun customEndpointPresetUpsertTrimsTrailingSlash() {
        val updated = SettingsRepository.upsertCustomEndpointPreset(
            emptyList(),
            CustomEndpointPreset("one", "New", "https://new.example/v1/"),
        )

        assertEquals("https://new.example/v1", updated.single().baseUrl)
    }

    @Test
    fun customEndpointPresetRemoveDeletesMatchingId() {
        val presets = listOf(
            CustomEndpointPreset("one", "One", "https://one.example/v1"),
            CustomEndpointPreset("two", "Two", "https://two.example/v1"),
        )

        assertEquals(listOf(presets[1]), SettingsRepository.removeCustomEndpointPreset(presets, "one"))
    }

    @Test
    fun customHttpEndpointIsReportedAsUnsafe() {
        assertTrue(AppSettings(provider = Provider.CUSTOM, customBaseUrl = "http://192.168.1.2:8080/v1").usesUnsafeHttp)
        assertFalse(AppSettings(provider = Provider.CUSTOM, customBaseUrl = "https://example.com/v1").usesUnsafeHttp)
    }

    @Test
    fun conversationDefaultsToAutomaticReasoning() {
        val conversation = ConversationEntity(
            id = "conversation",
            title = "Title",
            createdAt = 1,
            updatedAt = 1,
        )

        assertEquals(ReasoningMode.AUTO, conversation.reasoningMode)
    }
    @Test
    fun unknownProviderFallsBackToOpenRouter() {
        assertEquals(Provider.OPENROUTER, Provider.fromId("missing"))
    }
}
