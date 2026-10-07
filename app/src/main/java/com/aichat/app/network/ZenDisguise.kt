package com.aichat.app.network

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * OpenCode Zen 免費通道的請求偽裝。
 *
 * 匿名通道除了 key 還認兩件事：CLI 形狀的 header 組，以及 body 帶 agent 形狀
 * （stream 加上 bash/read 兩個 function tool）。兩者缺一就 403，跟 key 無關。
 * 偽裝身份是個人單機使用；通道本身會輪換名單、按 IP 限流、地區封鎖。
 */
internal object ZenDisguise {
    private const val USER_AGENT = "opencode/1.18.31 (windows x86_64)"

    /**
     * 跟一次請求綁定的 header 組。session 從對話首句派生（跨輪穩定），
     * request 每次全新隨機。
     */
    fun headers(firstUserText: String?): Map<String, String> {
        val session = sessionId((firstUserText ?: "").ifBlank { UUID.randomUUID().toString() })
        val request = "req_${UUID.randomUUID().toString().replace("-", "").take(32)}"
        return mapOf(
            "User-Agent" to USER_AGENT,
            "x-opencode-client" to "cli",
            "x-opencode-session" to session,
            "x-session-affinity" to session,
            "X-Session-Id" to session,
            "x-opencode-request" to request,
            "x-opencode-project" to "prj_opencode2dsh_default",
        )
    }

    /** body 形狀門用的佔位 tool；配合 tool_choice=none，模型不會真的呼叫。 */
    fun gateTool(name: String): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", name)
            .put("description", "Reserved for the host runtime; do not call it.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))

    /** Responses 專用：扁平 function tool，沒有 function 包裝層，配合 tool_choice=auto。 */
    fun responsesGateTool(name: String): JSONObject = JSONObject()
        .put("type", "function")
        .put("name", name)
        .put("description", "Reserved for the host runtime; do not call it.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))

    private fun sessionId(seed: String): String {
        val prefixed = byteArrayOf(0x73, 0x65, 0x73, 0x00) + seed.toByteArray()
        val sum = MessageDigest.getInstance("SHA-256").digest(prefixed)
        val timePart = sum.take(6).joinToString("") { "%02x".format(it) }
        val randomBig = java.math.BigInteger(1, sum.copyOfRange(6, 16))
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val randomPart = buildString {
            var n = randomBig
            val base = java.math.BigInteger.valueOf(62)
            repeat(14) {
                val qr = n.divideAndRemainder(base)
                append(alphabet[qr[1].toInt()])
                n = qr[0]
            }
        }.reversed()
        return "ses_$timePart$randomPart"
    }
}
