package com.aichat.app.network

import org.json.JSONArray
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
 *
 * 2026-10-28 的第三道門（opencode2dsh #108 / PR #110）：匿名推理請求除了形狀，
 * 還要比對「是不是官方 v2 CLI 在發」。實測在本機同時段：Node／curl／JDK 三種
 * TLS 堆疊帶同樣 body 全 429，只有 Bun 原生 fetch 200；再消融後真正的開關是
 * ① v2 形式的 UA、② body 裡要有字面 `<env>…</env>` 區塊（換成純文字同樣 429）。
 * 這裡照搬這兩項；傳輸層能不能過要看 Android 的 Conscrypt 是否同屬放行指紋。
 */
internal object ZenDisguise {
    /**
     * v2 形式：`opencode/<channel>/<version>/<client>`（App.useragent）。
     * 舊的 `opencode/1.18.31 (windows x86_64)` 在新門下仍 429。
     */
    private const val USER_AGENT = "opencode/latest/2.2.0/cli"

    /**
     * `<env>` 區塊裡的 Platform。實測有效的那組是 win32；上游是否驗值未知，
     * 因此沿用已驗證的值，不填 android。
     */
    private const val ENVIRONMENT_PLATFORM = "win32"

    private val ENV_BLOCK = Regex("<env>[\\s\\S]*?</env>")

    /** session 快取上限；同一對話重用一個 ID，上限外逐出最舊。 */
    private const val SESSION_MEMO_LIMIT = 1024
    private val sessionMemo = LinkedHashMap<String, String>()

    /**
     * 跟一次請求綁定的 header 組。session 同一對話只生成一次並快取。
     *
     * 上游實測（opencode2dsh #72/#74）：舊的合成標頭 x-opencode-request /
     * x-opencode-project 任一存在即 403 FreeTierError，兩者都不送才 200。
     * 只保留官方的 session 親和標頭；x-opencode-session-id 與
     * x-opencode-session 同值。
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
        return mapOf(
            "User-Agent" to USER_AGENT,
            "x-opencode-client" to "cli",
            "x-opencode-session" to session,
            "x-opencode-session-id" to session,
            "x-session-affinity" to session,
            "X-Session-Id" to session,
        )
    }

    /** 供 prompt_cache_key 使用：跟 headers 拿同一個快取 session，不能另算。 */
    @Synchronized
    fun sessionFor(firstUserText: String?): String =
        headers(firstUserText)["x-opencode-session"].orEmpty()

    /**
     * body 形狀門用的佔位 tool；配合 tool_choice=none，模型不會真的呼叫。
     *
     * 上游實測（#75）：官方工具名帶空 `parameters.properties` 會被拒，而且比不廣播更糟；
     * 因此這裡直接用 CLI 自己的真實定義（描述、必填欄位都照抄），而非空 stub。
     * 真實工具的非空 schema 不動（見 gateParameters 的保留邏輯）。
     */
    fun gateTool(name: String): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", name)
            .put("description", descriptionOf(name))
            .put("parameters", gateParameters(schemaOf(name))))

    /** Responses 專用：扁平 function tool，沒有 function 包裝層，配合 tool_choice=auto。 */
    fun responsesGateTool(name: String): JSONObject = JSONObject()
        .put("type", "function")
        .put("name", name)
        .put("description", descriptionOf(name))
        .put("parameters", gateParameters(schemaOf(name)))

    /** 空佔位 schema 補一個可選字串欄位；非空的真實 schema 原樣保留。 */
    fun gateParameters(existing: JSONObject? = null): JSONObject {
        val properties = existing?.optJSONObject("properties")
        if (properties != null && properties.keys().asSequence().toList().isNotEmpty()) {
            return existing!!
        }
        val base = JSONObject()
        existing?.keys()?.asSequence()?.toList()?.forEach { key ->
            if (key != "properties") base.put(key, existing.get(key))
        }
        return base
            .put("type", "object")
            .put("properties", JSONObject().put("input", JSONObject()
                .put("type", "string")
                .put("description", "Reserved for the host runtime; do not call this placeholder tool.")))
    }

    /** CLI 自己的 gate 工具定義（#75 從官方請求逐字抄下）。 */
    private fun schemaOf(name: String): JSONObject = when (name) {
        "bash" -> JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("command", JSONObject()
                    .put("type", "string")
                    .put("description", "The command to run.")))
            .put("required", JSONArray().put("command"))
            .put("additionalProperties", false)
        "read" -> JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("filePath", JSONObject()
                    .put("type", "string")
                    .put("description", "Absolute path of the file to read."))
                .put("offset", JSONObject()
                    .put("type", "integer")
                    .put("description", "Line number to start reading from."))
                .put("limit", JSONObject()
                    .put("type", "integer")
                    .put("description", "Maximum number of lines to read.")))
            .put("required", JSONArray().put("filePath"))
            .put("additionalProperties", false)
        else -> JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("input", JSONObject().put("type", "string")))
    }

    private fun descriptionOf(name: String): String = when (name) {
        "bash" -> "Run a shell command in the project workspace."
        "read" -> "Read a file from the filesystem."
        else -> "Reserved for the host runtime; do not call it."
    }

    /**
     * 第三道門（#108 / PR #110）：系統指示裡必須出現字面 `<env>…</env>`。
     *
     * 實測：把同樣的內容寫成「Working directory: .」純文字仍 429，只有 `<env>` 標籤過。
     * 已有 `<env>` 區塊就原樣不動（避免覆蓋呼叫端自己的環境描述）。
     */
    fun environmentBlock(): String =
        "<env>\n  Working directory: .\n  Platform: $ENVIRONMENT_PLATFORM\n</env>"

    /** 缺 `<env>` 才補；`existing` 是該請求目前所有 system/developer 指示的合併文字。 */
    fun environmentSuffix(existing: String): String =
        if (ENV_BLOCK.containsMatchIn(existing)) "" else "\n\n${environmentBlock()}"

    /** chat completions：把 `<env>` 併進既有的 system 訊息，沒有就插一則在最前面。 */
    fun applyEnvironment(messages: JSONArray) {
        val systems = (0 until messages.length())
            .mapNotNull { messages.optJSONObject(it) }
            .filter { it.optString("role") == "system" || it.optString("role") == "developer" }
        val existing = systems.joinToString("\n") { it.optString("content") }
        val suffix = environmentSuffix(existing)
        if (suffix.isEmpty()) return
        val target = systems.firstOrNull { it.optString("role") == "system" }
        if (target != null) {
            target.put("content", target.optString("content") + suffix)
        } else {
            // 只有 developer 或完全沒有 system 時，插一則新的在最前面。
            val inserted = JSONObject().put("role", "system").put("content", environmentBlock())
            val copy = JSONArray()
            copy.put(inserted)
            for (index in 0 until messages.length()) copy.put(messages.get(index))
            for (index in messages.length() - 1 downTo 0) messages.remove(index)
            for (index in 0 until copy.length()) messages.put(copy.get(index))
        }
    }

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
