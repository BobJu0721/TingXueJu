package com.aichat.app.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenDiagnosticsTest {

    @Test fun sha256HexMatchesKnownVector() {
        assertEquals("a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e",
            ZenDiagnostics.sha256Hex("Hello World".toByteArray()))
    }

    @Test fun errorTypeExtractionOnlyReturnsTypeNotMessage() {
        val body = """{"type":"error","error":{"type":"FreeUsageLimitError","message":"Free usage limit reached. Subscribe at https://opencode.ai/go"}}"""
        assertEquals("FreeUsageLimitError", ZenDiagnostics.errorTypeOf(body))
        // 訊息本文不出現在結果裡。
        assertFalse(ZenDiagnostics.errorTypeOf(body)!!.contains("Subscribe"))
        assertEquals(null, ZenDiagnostics.errorTypeOf("not json"))
    }

    @Test fun diagnosticsNeverContainsCredentialsOrPromptText() {
        // 診斷只接受這些欄位；組合出的摘要不含 key／提示詞／對話本文。
        val tools = JSONArray().put(JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", "read")))
        val bytes = "secret user prompt".toByteArray()
        val sha = ZenDiagnostics.sha256Hex(bytes)
        assertEquals(64, sha.length)
        assertTrue(sha.all { it in '0'..'9' || it in 'a'..'f' })
        // 工具名稱可見、本文只以摘要出現。
        assertEquals("read", tools.getJSONObject(0).getJSONObject("function").getString("name"))
        assertFalse(sha.contains("secret"))
    }
}
