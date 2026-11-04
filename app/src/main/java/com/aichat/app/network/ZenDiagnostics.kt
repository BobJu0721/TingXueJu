package com.aichat.app.network

import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Zen 免費通道的診斷摘要日誌。
 *
 * 只記錄：模型、執行環境、HTTP 狀態、工具數量／UTF-8 大小、工具名稱、
 * 以及本文 bytes 與 SHA-256。**不記錄**憑證、提示詞、對話本文或回應內容。
 *
 * 這是診斷用途，不參與請求組裝，也不改變任何送出內容。
 */
internal object ZenDiagnostics {
    private const val TAG = "ZenDiagnostics"

    /** 請求送出前的摘要。工具清單完整記錄；本 App 不做工具裁剪，因此沒有「省略」名單。 */
    fun logRequest(
        model: String,
        apiPath: String,
        tools: org.json.JSONArray?,
        payloadBytes: Int,
        payloadSha256: String,
    ) {
        val toolNames = buildList {
            if (tools != null) for (index in 0 until tools.length()) {
                val tool = tools.optJSONObject(index) ?: continue
                add(tool.optJSONObject("function")?.optString("name")
                    ?: tool.optString("name").ifEmpty { "?" })
            }
        }
        val toolsBytes = tools?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val summary = JSONObject()
            .put("model", model)
            .put("apiPath", apiPath)
            .put("engine", "OkHttp/Conscrypt")
            .put("toolCount", toolNames.size)
            .put("toolsBytes", toolsBytes)
            .put("tools", toolNames.joinToString(","))
            .put("trimmed", false) // 本 App 不裁剪工具；保留欄位供對照
            .put("omittedTools", "")
            .put("payloadBytes", payloadBytes)
            .put("payloadSha256", payloadSha256)
        Log.i(TAG, "request=$summary")
    }

    /** 只抽出上游的 error type（例如 FreeUsageLimitError），不落地錯誤訊息本文。 */
    fun errorTypeOf(body: String): String? = runCatching {
        val json = JSONObject(body)
        (json.optJSONObject("error") ?: json.optJSONObject("error")?.optJSONObject("error"))
            ?.optString("type")
            ?: json.optString("type")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 只用於摘要，不做任何內容落地。 */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 回應摘要；errorShape 只放上游的錯誤 type，不放訊息本文。 */
    fun logResponse(httpStatus: Int, errorType: String?, negotiatedProtocol: String?) {
        val summary = JSONObject()
            .put("httpStatus", httpStatus)
            .put("errorType", errorType ?: "")
            .put("negotiatedProtocol", negotiatedProtocol ?: "")
        Log.i(TAG, "response=$summary")
    }
}
