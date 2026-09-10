package com.aichat.app.network

import com.aichat.app.data.AppLanguage
import com.aichat.app.data.AppSettings
import com.aichat.app.data.Provider
import com.aichat.app.data.ReasoningMode
import com.aichat.app.pick
import org.json.JSONObject
import java.io.IOException

/** UI and request validation share this policy. Unknown IDs never inherit a family's controls. */
internal enum class ReasoningPolicy {
    OPENROUTER_COMPATIBLE, AGNES, GROQ_QWEN_DEFAULT, GROQ_QWEN_MEDIUM,
    GROQ_GPT_OSS, CEREBRAS_GPT_OSS, CLOUDFLARE_QWEN,
    MODEL_DEFAULT, NON_REASONING, UNKNOWN;

    fun supports(mode: ReasoningMode): Boolean = when (mode) {
        ReasoningMode.AUTO -> true
        ReasoningMode.ON -> this !in setOf(MODEL_DEFAULT, NON_REASONING, UNKNOWN)
        ReasoningMode.OFF -> this in setOf(OPENROUTER_COMPATIBLE, AGNES, GROQ_QWEN_DEFAULT, GROQ_QWEN_MEDIUM)
    }

    fun description(language: AppLanguage): String = when (this) {
        GROQ_QWEN_DEFAULT, GROQ_QWEN_MEDIUM -> language.pick("支援開啟與關閉思考。", "支持开启与关闭思考。")
        GROQ_GPT_OSS, CEREBRAS_GPT_OSS -> language.pick("模型持續使用推理，不支援完全關閉。", "模型持续使用推理，不支持完全关闭。")
        CLOUDFLARE_QWEN -> language.pick("支援要求開啟思考；尚未支援關閉，實際回傳由供應商決定。", "支持请求开启思考；尚未支持关闭，实际返回由供应商决定。")
        MODEL_DEFAULT -> language.pick("此模型支援推理，目前僅支援自動模式，開關控制尚未確認。", "此模型支持推理，目前仅支持自动模式，开关控制尚未确认。")
        NON_REASONING -> language.pick("此模型不支援思考模式，請使用自動。", "此模型不支持思考模式，请使用自动。")
        UNKNOWN -> language.pick("此模型的思考控制尚未適配，目前僅支援自動。", "此模型的思考控制尚未适配，目前仅支持自动。")
        else -> language.pick("思考開關依供應商與模型支援情況生效。", "思考开关依供应商与模型支持情况生效。")
    }

    fun applyTo(payload: JSONObject, mode: ReasoningMode) {
        check(supports(mode))
        if (this == AGNES) {
            payload.put("chat_template_kwargs", JSONObject().put("enable_thinking", mode != ReasoningMode.OFF))
            return
        }
        if (mode == ReasoningMode.AUTO) return
        when (this) {
            OPENROUTER_COMPATIBLE -> payload.put("reasoning", if (mode == ReasoningMode.ON) {
                JSONObject().put("enabled", true).put("exclude", false)
            } else JSONObject().put("effort", "none"))
            GROQ_QWEN_DEFAULT, GROQ_QWEN_MEDIUM -> {
                payload.put("reasoning_effort", if (mode == ReasoningMode.OFF) "none"
                    else if (this == GROQ_QWEN_MEDIUM) "medium" else "default")
                if (mode == ReasoningMode.ON) payload.put("reasoning_format", "parsed")
            }
            GROQ_GPT_OSS -> payload.put("reasoning_effort", "medium").put("include_reasoning", true)
            CEREBRAS_GPT_OSS -> payload.put("reasoning_effort", "medium").put("reasoning_format", "parsed")
            CLOUDFLARE_QWEN -> payload.put("reasoning_effort", "medium")
            else -> Unit
        }
    }
}

internal fun reasoningPolicy(provider: Provider, model: String): ReasoningPolicy = when (provider) {
    Provider.OPENROUTER, Provider.CUSTOM -> ReasoningPolicy.OPENROUTER_COMPATIBLE
    Provider.AGNES -> ReasoningPolicy.AGNES
    Provider.GROQ -> when (model) {
        "qwen/qwen3.8-27b" -> ReasoningPolicy.GROQ_QWEN_MEDIUM
        "qwen/qwen3.6-27b", "qwen/qwen3-32b" -> ReasoningPolicy.GROQ_QWEN_DEFAULT
        "openai/gpt-oss-20b", "openai/gpt-oss-120b" -> ReasoningPolicy.GROQ_GPT_OSS
        "minimaxai/minimax-m2.7", "openai/gpt-oss-safeguard-20b" -> ReasoningPolicy.MODEL_DEFAULT
        "llama-3.3-70b-versatile", "llama-3.1-8b-instant", "meta-llama/llama-4-scout-17b-16e-instruct" -> ReasoningPolicy.NON_REASONING
        else -> ReasoningPolicy.UNKNOWN
    }
    Provider.CEREBRAS -> when (model) {
        "gpt-oss-120b" -> ReasoningPolicy.CEREBRAS_GPT_OSS
        "zai-glm-4.7", "qwen-3.8-27b", "kimi-k2.7-code", "gemma-4-31b" -> ReasoningPolicy.MODEL_DEFAULT
        "llama-3.3-70b", "llama3.1-8b", "qwen-3-235b-a22b-instruct-2507" -> ReasoningPolicy.NON_REASONING
        else -> ReasoningPolicy.UNKNOWN
    }
    Provider.CLOUDFLARE -> when (model) {
        "@cf/qwen/qwen3.8-27b" -> ReasoningPolicy.CLOUDFLARE_QWEN
        "@cf/zai-org/glm-4.7-flash", "@cf/qwen/qwen3-30b-a3b-fp8", "@cf/openai/gpt-oss-120b" -> ReasoningPolicy.MODEL_DEFAULT
        "@cf/meta/llama-3.1-8b-instruct" -> ReasoningPolicy.NON_REASONING
        else -> ReasoningPolicy.UNKNOWN
    }
}

internal fun validateReasoningMode(settings: AppSettings, mode: ReasoningMode) {
    val policy = reasoningPolicy(settings.provider, settings.model)
    if (!policy.supports(mode)) throw UnsupportedReasoningModeException(
        settings.language.pick(
            "${settings.provider.label}／${settings.model} 不支援目前儲存的思考設定。",
            "${settings.provider.label}／${settings.model} 不支持当前保存的思考设置。",
        ) + "\n" + policy.description(settings.language),
    )
}

internal class UnsupportedReasoningModeException(message: String) : IOException(message)

internal fun retiredModelNotice(provider: Provider, model: String, language: AppLanguage): String? {
    val retired = when (provider) {
        Provider.GROQ -> model in setOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen3-32b", "meta-llama/llama-4-scout-17b-16e-instruct")
        Provider.CEREBRAS -> model in setOf("llama-3.3-70b", "llama3.1-8b", "qwen-3-235b-a22b-instruct-2507", "qwen-3-32b")
        else -> false
    }
    return if (retired) language.pick(
        "供應商已公告此模型退役，請重新載入清單並選擇可用模型；原選擇尚未更改。",
        "供应商已公告此模型退役，请重新加载列表并选择可用模型；原选择尚未更改。",
    ) else null
}
