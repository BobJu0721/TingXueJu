package com.aichat.app

import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.ProfileEntity
import com.aichat.app.data.ProfileType
import com.aichat.app.data.WorldEntryEntity
import com.aichat.app.data.WorldSetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun message(id: String, content: String, createdAt: Long) =
        MessageEntity(id, "c", "user", content, createdAt)

    private fun entry(id: String, keyword: String, always: Boolean = false) =
        WorldEntryEntity(id, worldSet.id, id, toJsonStrings(listOf(keyword).filter(String::isNotBlank)), "內容", always)

    private fun profile(name: String, type: ProfileType) =
        ProfileEntity(name, type, name, createdAt = now, updatedAt = now)
}
