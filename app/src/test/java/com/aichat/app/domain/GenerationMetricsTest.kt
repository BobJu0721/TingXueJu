package com.aichat.app.domain

import com.aichat.app.data.AppLanguage
import com.aichat.app.data.GenerationContextEntity
import com.aichat.app.ui.messageMetricsLabels
import org.junit.Assert.*
import org.junit.Test

class GenerationMetricsTest {
    @Test fun tokenEstimateHandlesCjkEnglishAndUnicodeWithoutCountingStreamChunks() {
        assertEquals(0L, estimateTokens(""))
        assertEquals(4L, estimateTokens("聽雪居好"))
        assertEquals(2L, estimateTokens("abcdefgh"))
        assertEquals(3L, estimateTokens("你好abcd"))
        assertEquals(1L, estimateTokens("😀"))
        val meter = GenerationMeter(0)
        assertEquals(meter.snapshot("abcdef", "", 1_000_000).tokens,
            meter.snapshot("abc" + "def", "", 1_000_000).tokens)
    }

    @Test fun apiCompletionCountReplacesEstimateAndIsNotSummedOrDoubleCounted() {
        val meter = GenerationMeter(0)
        assertTrue(meter.snapshot("answer", "thinking").estimated)
        meter.recordUsage(100)
        meter.recordUsage(100)
        val metrics = meter.snapshot("answer", "thinking", 2_000_000_000)
        assertEquals(100L, metrics.tokens)
        assertFalse(metrics.estimated)
        assertEquals(2000L, metrics.elapsedMillis)
        assertEquals(50.0, metrics.tokensPerSecond!!, 0.001)
        meter.recordUsage(-1)
        assertEquals(100L, meter.snapshot("", "").tokens)
        meter.recordUsage(0)
        assertEquals(0L, meter.snapshot("", "").tokens)
    }

    @Test fun unknownAndZeroDurationsNeverInventSpeed() {
        assertNull(GenerationMeter(10).snapshot("hi", "", 10).tokensPerSecond)
        assertNull(GenerationMeter(10).snapshot("hi", "", 0).tokensPerSecond)
        val labels = messageMetricsLabels("你好", null, false, AppLanguage.TRADITIONAL_CHINESE)
        assertEquals("約 2 token", labels.count)
        assertNull(labels.speed)
    }

    @Test fun persistedGenerationStatsRestoreAndUserMessagesIgnoreGenerationTime() {
        val context = GenerationContextEntity("id", reasoningContent = "reasoning", outputTokenCount = 100,
            tokenCountEstimated = false, generationElapsedMillis = 2000)
        val labels = messageMetricsLabels("answer", context.copy(), false, AppLanguage.TRADITIONAL_CHINESE)
        assertEquals("100 token", labels.count)
        assertEquals("平均 50.0 token/s", labels.speed)
        assertEquals("100 token · 平均 50.0 token/s", labels.singleLine)
        val user = messageMetricsLabels("你好", context, true, AppLanguage.TRADITIONAL_CHINESE)
        assertEquals("約 2 token", user.count)
        assertNull(user.speed)
    }

    @Test fun partialGenerationsAndEditedMessagesUseEstimatedCounts() {
        val partial = GenerationContextEntity("id", outputTokenCount = 40,
            tokenCountEstimated = true, generationElapsedMillis = 2000)
        val labels = messageMetricsLabels("answer", partial, false, AppLanguage.SIMPLIFIED_CHINESE)
        assertEquals("约 40 token", labels.count)
        assertEquals("平均 约 20.0 token/s", labels.speed)
        val edited = partial.copy(outputTokenCount = null, generationElapsedMillis = null, reasoningContent = "")
        val after = messageMetricsLabels("修改了", edited, false, AppLanguage.TRADITIONAL_CHINESE)
        assertEquals("約 3 token", after.count)
        assertNull(after.speed)
        assertEquals(after.count, after.singleLine)
    }
}
