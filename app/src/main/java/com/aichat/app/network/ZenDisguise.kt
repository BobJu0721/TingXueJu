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
 *
 * session ID 規則（opencode2dsh #62/#65，實測機制 #68）：
 * `ses_` 後 12 位 hex 不是雜湊，是反轉的牆鐘 `~(nowMillis * 0x1000 + 1)`，
 * 上游解回 `counter = ~hex & 0xfff`，只接受 0～63。雜湊前綴約 98.5% 被拒。
 * 同一對話只生成一次，重試與後續輪次重用（上游按 session 分配配額與快取）。
 */
internal object ZenDisguise {
    private const val USER_AGENT = "opencode/1.18.31 (windows x86_64)"

    /** session 快取上限；同一對話重用一個 ID，上限外逐出最舊。 */
    private const val SESSION_MEMO_LIMIT = 1024
    private val sessionMemo = LinkedHashMap<String, String>()

    /**
     * 跟一次請求綁定的 header 組。session 同一對話只生成一次並快取，
     * request 每次全新隨機。
     */
    @Synchronized
    fun headers(firstUserText: String?): Map<String, String> {
        val signal = (firstUserText ?: "").ifBlank { null }
        val session = if (signal == null) {
            newSession(UUID.randomUUID().toString())
        } else {
            sessionMemo[signal] ?: newSession(signal).also { fresh ->
                if (sessionMemo.size >= SESSION_MEMO_LIMIT) {
                    sessionMemo.keys.firstOrNull()?.let { sessionMemo.remove(it) }
                }
                sessionMemo[signal] = fresh
            }
        }
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

    /** 供 prompt_cache_key 使用：跟 headers 拿同一個快取 session，不能另算。 */
    @Synchronized
    fun sessionFor(firstUserText: String?): String =
        headers(firstUserText)["x-opencode-session"].orEmpty()

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

    private fun newSession(seed: String): String {
        val prefixed = byteArrayOf(0x73, 0x65, 0x73, 0x00) + seed.toByteArray()
        val sum = MessageDigest.getInstance("SHA-256").digest(prefixed)
        val timePart = invertedTimestampHex()
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

    /**
     * 上游 identifier.ts 的官方生成公式：`~(nowMillis * 0x1000 + 1)` 取低 48 bits。
     * counter 固定 1，反轉後末三位恆為 `ffe`。後 14 位 Base62 仍由對話雜湊派生，
     * 不同對話不會相撞，快取親和性保留。
     */
    internal fun invertedTimestampHex(nowMillis: Long = System.currentTimeMillis()): String {
        val current = java.math.BigInteger.valueOf(nowMillis).shiftLeft(12).add(java.math.BigInteger.ONE)
        val mask = java.math.BigInteger.ONE.shiftLeft(48).subtract(java.math.BigInteger.ONE)
        val inverted = current.not().and(mask)
        return inverted.toString(16).padStart(12, '0')
    }

    /**
     * 上游實測規則（#68）：`counter = ~hex & 0xfff`，接受當且僅當 0～63。
     * 舊雜湊前綴約 98.5% 落在外面；新公式 counter 固定 1，恆接受。
     */
    internal fun acceptedCounter(hex12: String): Long? {
        val hex = hex12.toBigIntegerOrNull(16) ?: return null
        return hex.inv().and(MASK_12BIT).toLong()
    }

    internal fun isAcceptedSession(session: String): Boolean {
        if (!session.matches(Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$"))) return false
        val counter = acceptedCounter(session.substring(4, 16)) ?: return false
        return counter in 0L..63L
    }

    private val MASK_12BIT = java.math.BigInteger.valueOf(0xfffL)
}
