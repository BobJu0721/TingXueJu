package com.aichat.app.ui

import com.aichat.app.data.AppLanguage
import com.aichat.app.data.GenerationContextEntity
import com.aichat.app.domain.estimateTokens
import com.aichat.app.pick
import java.util.Locale

internal data class MessageMetricsLabels(val count: String, val speed: String?) {
    val singleLine: String get() = if (speed == null) count else "$count · $speed"
}

internal fun messageMetricsLabels(
    content: String,
    context: GenerationContextEntity?,
    user: Boolean,
    language: AppLanguage,
): MessageMetricsLabels {
    val generation = context.takeUnless { user }
    val recorded = generation?.outputTokenCount?.takeIf { it >= 0 }
    val count = recorded ?: estimateTokens(generation?.reasoningContent.orEmpty() + content)
    val approximate = recorded == null || generation?.tokenCountEstimated == true
    val prefix = if (approximate) language.pick("約 ", "约 ") else ""
    val elapsed = generation?.generationElapsedMillis?.takeIf { it > 0 }
    val speed = elapsed?.let { String.format(Locale.ROOT, "%.1f", count * 1000.0 / it) }
    return MessageMetricsLabels(
        count = "$prefix$count token",
        speed = speed?.let { language.pick("平均 ", "平均 ") + "$prefix$it token/s" },
    )
}
