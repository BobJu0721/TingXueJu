package com.aichat.app.domain

import kotlin.math.ceil

/** Rough text-only estimate, not a model tokenizer. CJK code points count as one, others as 1/4. */
internal fun estimateTokens(text: String): Long {
    var quarters = 0L
    var offset = 0
    while (offset < text.length) {
        val point = text.codePointAt(offset)
        quarters += when (Character.UnicodeScript.of(point)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> 4
            else -> 1
        }
        offset += Character.charCount(point)
    }
    return ceil(quarters / 4.0).toLong()
}

internal data class GenerationMetrics(val tokens: Long, val estimated: Boolean, val elapsedMillis: Long) {
    val tokensPerSecond: Double?
        get() = if (elapsedMillis > 0) tokens * 1000.0 / elapsedMillis else null
}

/** Monotonic elapsed time includes the wait for the first token and the rest of the request. */
internal class GenerationMeter(private val startedNanos: Long = System.nanoTime()) {
    private var apiTokens: Long? = null

    fun recordUsage(completionTokens: Long) {
        if (completionTokens >= 0) apiTokens = completionTokens // Usage events are snapshots, not deltas.
    }

    fun snapshot(content: String, reasoning: String, nowNanos: Long = System.nanoTime()): GenerationMetrics =
        GenerationMetrics(
            tokens = apiTokens ?: estimateTokens(reasoning + content),
            estimated = apiTokens == null,
            elapsedMillis = ((nowNanos - startedNanos) / 1_000_000L).coerceAtLeast(0),
        )
}
