package com.aichat.app

import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.ProfileEntity
import com.aichat.app.data.ProfileType
import com.aichat.app.data.WorldEntryEntity
import com.aichat.app.data.WorldSetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerTest {
    private val now = 1_000L
    private val worldSet = WorldSetEntity("world", "測試世界", 2, now, now)

    @Test
    fun worldEntriesUseRecentMessagesAndAlwaysIncludeFlag() {
        val history = listOf(
            message("1", "提到古塔", 1),
            message("2", "換一個話題", 2),
            message("3", "繼續前進", 3),
        )
        val entries = listOf(
            entry("old", "古塔"),
            entry("always", "", always = true),
            entry("current", "前進"),
        )

        assertEquals(
            listOf("always", "current"),
            activateWorldEntries(entries, listOf(worldSet), history).map { it.id },
        )
    }

    // --- activateWorldEntries 邊界與分支 ---

    @Test
    fun disabledEntriesAreNeverActivatedEvenWithKeywordMatch() {
        val history = listOf(message("1", "繼續前進", 1))
        val entries = listOf(
            entry("on", "前進").copy(enabled = true),
            entry("off", "前進").copy(enabled = false),
        )

        assertEquals(listOf("on"), activateWorldEntries(entries, listOf(worldSet), history).map { it.id })
    }

    @Test
    fun entryActivatesWhenAnyOneOfMultipleKeywordsMatches() {
        val history = listOf(message("1", "只提到劍", 1))
        val entry = entry("multi", "弓", "劍", "杖")

        assertEquals(listOf("multi"), activateWorldEntries(listOf(entry), listOf(worldSet), history).map { it.id })
    }

    @Test
    fun keywordMatchingIsCaseInsensitive() {
        val history = listOf(message("1", "I love MAGIC", 1))
        val entry = entry("ci", "magic")

        assertEquals(listOf("ci"), activateWorldEntries(listOf(entry), listOf(worldSet), history).map { it.id })
    }

    @Test
    fun entryWithoutKeywordsAndNotAlwaysIncludedNeverActivates() {
        val history = listOf(message("1", "任何內容", 1))
        val entry = entry("blank", "")

        assertTrue(activateWorldEntries(listOf(entry), listOf(worldSet), history).isEmpty())
    }

    @Test
    fun missingWorldSetFallsBackToDefaultScanDepthTen() {
        // entry.worldSetId 指向一個不在 worldSets 清單裡的設定集時，
        // activateWorldEntries 應退回預設 scanDepth=10。
        val history = (1..3).map { message(it.toString(), "訊息$it", it.toLong()) } +
            message("hit", "目標關鍵詞", 11)
        val orphanEntry = entry("orphan", "目標關鍵詞").copy(worldSetId = "no-such-set")

        // 若 scanDepth 退回 10，則第 11 則（命中訊息）會落在 takeLast(10) 視窗內而命中。
        assertEquals(
            listOf("orphan"),
            activateWorldEntries(listOf(orphanEntry), emptyList(), history).map { it.id },
        )
    }

    @Test
    fun scanDepthClampedToUpperBoundHundred() {
        val deepSet = worldSet.copy(id = "deep", scanDepth = 999)
        // 只在很早的訊息出現關鍵詞，scanDepth 經 coerceIn(1,100) 後為 100，應涵蓋到。
        val history = (1..5).map { message(it.toString(), "閒聊$it", it.toLong()) } +
            message("hit", "遠古關鍵詞", 0)

        val entry = entry("distant", "遠古關鍵詞").copy(worldSetId = "deep")
        assertEquals(
            listOf("distant"),
            activateWorldEntries(listOf(entry), listOf(deepSet), history).map { it.id },
        )
    }

    @Test
    fun entriesAreSortedBySortOrderThenTitle() {
        val history = listOf(message("1", "觸發", 1))
        val entries = listOf(
            entry("b", "觸發").copy(sortOrder = 1, title = "B 條目"),
            entry("a", "觸發").copy(sortOrder = 1, title = "A 條目"),
            entry("z", "觸發").copy(sortOrder = 0, title = "Z 條目"),
        )

        assertEquals(
            listOf("z", "a", "b"),
            activateWorldEntries(entries, listOf(worldSet), history).map { it.id },
        )
    }

    // --- composePrompt 與 World Info 命中視窗的已知 bug ---

    /**
     * 已知 bug（Phase 1 待修）：
     * composePrompt 用「過濾掉已摘要訊息」的 visibleHistory 來決定世界條目命中與否。
     * 因此當某個關鍵詞只出現在已被 summaryThroughAt 蓋掉的舊訊息時，
     * 對應世界條目會命中不到。World Info 命中應掃描完整 history，而非 visibleHistory。
     *
     * 此測試目前預期失敗（紅燈），用來釘住行為；修復後會轉綠。
     */
    @Test
    fun worldEntryShouldStillMatchKeywordLocatedInSummarizedMessages() {
        val conversation = ConversationEntity(
            "c", "chat", now, now,
            summary = "已摘要",
            summaryThroughAt = 1, // 第 1 則以後都算「已摘要過」的範圍起點
        )
        // 關鍵詞只出現在第 1 則（被 summaryThroughAt 過濾掉），後續訊息完全沒提到。
        val history = listOf(
            message("1", "我們去古塔看看", 1),
            message("2", "接下來呢", 2),
        )
        val result = composePrompt(
            conversation,
            history,
            null,
            null,
            listOf(worldSet),
            listOf(entry("tower", "古塔")),
        )

        // 期望：即使關鍵詞在已摘要訊息裡，世界條目仍應被啟動。
        assertEquals(listOf("tower"), result.activatedEntries.map { it.id })
    }

    /**
     * 已知 bug（Phase 1 待修）：
     * 與上一個同類。當對話被裁切（contextStartAt 往後移）後，
     * 落在裁切範圍外的關鍵詞也命中不到對應世界條目。
     *
     * 此測試目前預期失敗（紅燈），用來釘住行為；修復後會轉綠。
     */
    @Test
    fun worldEntryShouldStillMatchKeywordLocatedBeforeContextStartAt() {
        val conversation = ConversationEntity("c", "chat", now, now, contextStartAt = 2)
        val history = listOf(
            message("1", "我們去古塔看看", 1), // 在 contextStartAt 之前，被裁掉
            message("2", "繼續", 2),
        )
        val result = composePrompt(
            conversation,
            history,
            null,
            null,
            listOf(worldSet),
            listOf(entry("tower", "古塔")),
        )

        assertEquals(listOf("tower"), result.activatedEntries.map { it.id })
    }

    @Test
    fun composePromptWithNothingStillEmitsBaseSystemMessage() {
        // 即使沒有任何 profile / world / summary / history，
        // composePrompt 開頭會無條件 append「請自然地延續對話」系統訊息，
        // 所以 messages 不為空，而是剛好一條 system，且無歷史訊息。
        val result = composePrompt(
            ConversationEntity("c", "chat", now, now),
            emptyList(),
            null,
            null,
            emptyList(),
            emptyList(),
        )
        assertEquals(1, result.messages.size)
        assertEquals("system", result.messages.first().role)
        assertTrue(result.messages.first().content.contains("延續對話"))
        assertTrue(result.activatedEntries.isEmpty())
    }

    @Test
    fun profileBlankFieldsAreOmittedFromSystemPrompt() {
        val hero = profile("hero", ProfileType.CHARACTER) // 只給 name，其餘欄位空白
        val result = composePrompt(
            ConversationEntity("c", "chat", now, now),
            listOf(message("1", "嗨", 1)),
            hero,
            null,
            emptyList(),
            emptyList(),
        )

        val system = result.messages.first { it.role == "system" }.content
        assertTrue(system.contains("AI 扮演的角色：hero"))
        assertFalse(system.contains("個性")) // personality 為空，不應出現欄位標題
    }

    @Test
    fun systemMessageAlwaysPrecedesHistoryAndUsesEachMessageRole() {
        val result = composePrompt(
            ConversationEntity("c", "chat", now, now),
            listOf(
                message("u", "問題", 1).copy(role = "user"),
                message("a", "回答", 2).copy(role = "assistant"),
            ),
            profile("hero", ProfileType.CHARACTER),
            null,
            emptyList(),
            emptyList(),
        )

        assertEquals(3, result.messages.size)
        assertEquals("system", result.messages[0].role)
        assertEquals("user", result.messages[1].role)
        assertEquals("問題", result.messages[1].content)
        assertEquals("assistant", result.messages[2].role)
        assertEquals("回答", result.messages[2].content)
    }

    @Test
    fun promptIncludesProfilesWorldAndSummaryButSkipsSummarizedHistory() {
        val conversation = ConversationEntity("c", "chat", now, now, summary = "先前已抵達城門", summaryThroughAt = 1)
        val history = listOf(message("1", "舊訊息", 1), message("2", "打開城門", 2))
        val result = composePrompt(
            conversation,
            history,
            profile("hero", ProfileType.CHARACTER),
            profile("user", ProfileType.PERSONA),
            listOf(worldSet),
            listOf(entry("gate", "城門")),
        )

        assertTrue(result.messages.first().content.contains("AI 扮演的角色：hero"))
        assertTrue(result.messages.first().content.contains("使用者身份 Persona：user"))
        assertTrue(result.messages.first().content.contains("先前已抵達城門"))
        assertTrue(result.messages.first().content.contains("gate"))
        assertFalse(result.messages.any { it.content == "舊訊息" })
        assertEquals("打開城門", result.messages.last().content)
    }

    @Test
    fun promptIncludesWorldOverviewEvenWithoutEntryHits() {
        val result = composePrompt(
            ConversationEntity("c", "chat", now, now),
            listOf(message("1", "沒有關鍵詞", 1)),
            null,
            null,
            listOf(worldSet.copy(overview = "低科技海島王國面臨能源禁忌。")),
            listOf(entry("miss", "不會命中")),
        )

        assertTrue(result.messages.first().content.contains("低科技海島王國面臨能源禁忌。"))
        assertTrue(result.activatedEntries.isEmpty())
    }

    // --- jsonStrings / toJsonStrings 容錯 ---

    @Test
    fun jsonStringsParsesPlainStringArray() {
        assertEquals(listOf("a", "b", "c"), jsonStrings(toJsonStrings(listOf("a", "b", "c"))))
    }

    @Test
    fun jsonStringsReturnsEmptyForMalformedJson() {
        assertTrue(jsonStrings("不是 json").isEmpty())
        assertTrue(jsonStrings("{").isEmpty())
    }

    @Test
    fun jsonStringsTrimsAndDropsBlankEntries() {
        // 前後空白會被 trim；空字串元素會被丟掉。
        assertEquals(listOf("保留"), jsonStrings("""["  保留  ", ""]"""))
    }

    @Test
    fun jsonStringsReturnsEmptyForEmptyOrBlankInput() {
        assertTrue(jsonStrings("").isEmpty())
        assertTrue(jsonStrings("[]").isEmpty())
    }

    @Test
    fun toJsonStringsIsJsonArrayOfRawValues() {
        assertEquals("""["x","y"]""", toJsonStrings(listOf("x", "y")))
    }

    private fun message(id: String, content: String, createdAt: Long) =
        MessageEntity(id, "c", "user", content, createdAt)

    private fun entry(id: String, vararg keywords: String, always: Boolean = false) =
        WorldEntryEntity(id, worldSet.id, id, toJsonStrings(keywords.toList().filter(String::isNotBlank)), "內容", always)

    private fun profile(name: String, type: ProfileType) =
        ProfileEntity(name, type, name, createdAt = now, updatedAt = now)
}
