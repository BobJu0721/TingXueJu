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
    fun customProviderTrimsTrailingSlash() {
        val settings = AppSettings(provider = Provider.CUSTOM, customBaseUrl = "https://example.com/v1/")

        assertEquals("https://example.com/v1", settings.resolvedBaseUrl)
    }

    @Test
    fun customHttpEndpointIsReportedAsUnsafe() {
        assertTrue(AppSettings(provider = Provider.CUSTOM, customBaseUrl = "http://192.168.1.2:8080/v1").usesUnsafeHttp)
        assertFalse(AppSettings(provider = Provider.CUSTOM, customBaseUrl = "https://example.com/v1").usesUnsafeHttp)
    }

    @Test
    fun unknownProviderFallsBackToOpenRouter() {
        assertEquals(Provider.OPENROUTER, Provider.fromId("missing"))
    }
}

