package com.aichat.app

import com.aichat.app.data.AppLanguage
import com.aichat.app.network.ApiException
import org.junit.Assert.assertEquals
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
}
