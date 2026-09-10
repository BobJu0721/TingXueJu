package com.aichat.app

import com.aichat.app.data.AppLanguage
import com.aichat.app.data.Provider
import com.aichat.app.network.ApiException
import com.aichat.app.network.UnsupportedReasoningModeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMappingTest {
    @Test
    fun distinguishesInvalidKeyFromForbiddenPlan() {
        val invalidKey = mapError(ApiException(401, "Unauthorized"), "失敗", AppLanguage.TRADITIONAL_CHINESE)
        val forbidden = mapError(
            ApiException(403, "Model requires a paid plan"),
            "失敗",
            AppLanguage.TRADITIONAL_CHINESE,
        )

        assertEquals("API Key 無效", invalidKey.title)
        assertEquals("權限或方案限制", forbidden.title)
        assertEquals("API 回報 403：Model requires a paid plan", forbidden.message)
    }

    @Test fun paymentAndCloudflareCodesHaveDistinctSuggestions() {
        val lang = AppLanguage.TRADITIONAL_CHINESE
        val payment = mapError(ApiException(402, "Payment required", Provider.CEREBRAS), "失敗", lang)
        assertEquals("帳務或額度限制", payment.title)
        assertTrue(payment.suggestion.contains("Billing"))
        val expected = mapOf("5035" to "模型需要付費方案", "5016" to "需要同意模型條款",
            "5018" to "帳號無法存取模型", "3041" to "帳號無法存取模型", "3023" to "帳號無法存取模型",
            "3036" to "每日免費額度已用完", "3040" to "供應商容量不足")
        for ((code, title) in expected) {
            val status = if (code in setOf("3036", "3040")) 429 else 403
            val error = ApiException.fromBody(status,
                """{"success":false,"errors":[{"code":$code,"message":"original failure"}]}""",
                Provider.CLOUDFLARE, "trace-123")
            val ui = mapError(error, "失敗", lang)
            assertEquals(title, ui.title)
            assertTrue(ui.message.contains("original failure"))
            assertTrue(ui.message.contains(code))
            assertTrue(ui.message.contains("trace-123"))
            assertEquals(ErrorKind.GENERAL, ui.kind)
            assertFalse(error.isContextLengthError)
        }
        val otherProvider = mapError(ApiException(403, "Forbidden", Provider.GROQ, "5035"), "失敗", lang)
        assertEquals("權限或方案限制", otherProvider.title)
    }

    @Test fun rateQuotaAndUnknown429RemainSeparate() {
        for ((message, expected) in mapOf("Rate limit reached" to "請求過快",
            "insufficient quota" to "可用額度不足", "Try again" to "額度不足或請求過快")) {
            assertEquals(expected, mapError(ApiException(429, message), "失敗", AppLanguage.TRADITIONAL_CHINESE).title)
        }
    }

    @Test fun onlyActualContextOverflowTriggersTrimming() {
        for (message in listOf("maximum context length is 8192 tokens", "context window exceeded", "prompt is too long")) {
            val error = ApiException(400, message)
            assertTrue(message, error.isContextLengthError)
            assertEquals(ErrorKind.CONTEXT_LENGTH, mapError(error, "失敗", AppLanguage.TRADITIONAL_CHINESE).kind)
        }
        assertTrue(ApiException(400, "Too large", internalCode = "context_length_exceeded").isContextLengthError)
        for (message in listOf("Invalid token", "maximum must be positive", "Invalid length", "context field missing",
            "max_completion_tokens must be at most 4096", "reasoning_effort must be one of low, medium, high",
            "include_reasoning cannot be combined with reasoning_format", "enable_thinking is unsupported",
            "reasoning_effort exceeds maximum context length")) {
            val error = ApiException(400, message)
            assertFalse(message, error.isContextLengthError)
            assertTrue(mapError(error, "失敗", AppLanguage.TRADITIONAL_CHINESE).kind != ErrorKind.CONTEXT_LENGTH)
        }
        assertFalse(ApiException(429, "maximum context length").isContextLengthError)
        assertEquals("模型不接受思考參數", mapError(ApiException(400, "reasoning_effort is invalid"),
            "失敗", AppLanguage.TRADITIONAL_CHINESE).title)
    }

    @Test fun localUnsupportedModeErrorIsNotPresentedAsNetworkFailure() {
        val error = mapError(UnsupportedReasoningModeException("saved mode is unsupported"), "失敗", AppLanguage.TRADITIONAL_CHINESE)
        assertEquals("思考設定不受支援", error.title)
        assertEquals(ErrorKind.MODEL_SELECTION, error.kind)
        assertTrue(error.suggestion.contains("模型選擇頁"))
    }

    @Test fun simplifiedChineseErrorsAndStreamErrorsAreReadable() {
        val lang = AppLanguage.SIMPLIFIED_CHINESE
        assertEquals("账务或额度限制", mapError(ApiException(402, "billing"), "失败", lang).title)
        assertEquals("模型需要付费方案", mapError(ApiException(403, "paid", Provider.CLOUDFLARE, "5035"), "失败", lang).title)
        val stream = mapError(ApiException(200, "upstream broke", isStreamError = true), "失败", lang)
        assertEquals("供应商生成失败", stream.title)
        assertTrue(stream.message.contains("串流返回错误（HTTP 200）"))
        assertEquals("供应商生成失败", mapError(ApiException(500, "server error"), "失败", lang).title)
    }

    @Test fun errorParserRetainsDifferentEnvelopesAndMultipleMessages() {
        val standard = ApiException.fromBody(400, """{"error":{"code":"bad_param","message":"invalid","request_id":"nested"}}""")
        assertEquals("bad_param", standard.internalCode)
        assertEquals("nested", standard.requestId)
        assertEquals("invalid", standard.message)
        val multiple = ApiException.fromBody(403, """{"errors":[{"code":5035,"message":"paid"},{"code":99,"message":"extra detail"}],"request_id":"body"}""", requestId = "header")
        assertEquals("paid\nextra detail", multiple.message)
        assertEquals("header", multiple.requestId)
        assertEquals("simple", ApiException.fromBody(500, """{"error":"simple"}""").message)
        assertEquals("plain failure", ApiException.fromBody(500, "plain failure").message)
        assertFalse(ApiException.fromBody(500, "").message.isBlank())
        assertEquals("fallback", ApiException.fromBody(400, """{"error":{"message":null},"message":"fallback"}""").message)
    }
}
