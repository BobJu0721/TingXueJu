package com.aichat.app

import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.MessageKind
import com.aichat.app.data.isModelVisible
import com.aichat.app.data.promptRole
import com.aichat.app.data.toPromptContent
import com.aichat.app.domain.ChatGenerationKind
import com.aichat.app.domain.EffectiveHistoryResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手動創作訊息的內容資格與格式轉換。
 *
 * 私人註記必須在格式轉換之前就被排除，不是靠「加上請忽略的標籤」；旁白與手寫角色台詞則是
 * 真正的故事內容，要以 `user` 身分加上明確標記送給模型。
 */
class AuthoredMessagePromptTest {

    @Test fun privateNoteIsNeverModelVisible() {
        val note = message("n", "private", kind = MessageKind.PRIVATE_NOTE)
        assertFalse(note.isModelVisible)
        assertFalse(note.copy(excluded = false, deleted = false).isModelVisible)
    }

    @Test fun deletedAndExcludedAndBlankAreNotModelVisible() {
        assertFalse(message("a", "x", deleted = true).isModelVisible)
        assertFalse(message("b", "x", excluded = true).isModelVisible)
        assertFalse(message("c", "   ").isModelVisible)
        assertTrue(message("d", "x").isModelVisible)
    }

    @Test fun authoredContentGetsMarkerAndUserRole() {
        val narration = message("n", "三日後，眾人抵達山門。", kind = MessageKind.NARRATION)
        assertEquals("【旁白】\n三日後，眾人抵達山門。", narration.toPromptContent())
        assertEquals("user", narration.promptRole())

        val line = message("a", "樓上的房間，今晚不能進去。", kind = MessageKind.AUTHORED_CHARACTER, author = "掌櫃")
        assertEquals("【角色台詞：掌櫃】\n樓上的房間，今晚不能進去。", line.toPromptContent())
        assertEquals("user", line.promptRole())

        val chat = message("c", "普通訊息")
        assertEquals("普通訊息", chat.toPromptContent())
        assertEquals("user", chat.promptRole())
    }

    @Test fun onlyChatAssistantCountsAsAiReply() {
        assertTrue(message("1", "x", role = "assistant").isAiReply)
        assertFalse(message("2", "x", role = "assistant", kind = MessageKind.AUTHORED_CHARACTER).isAiReply)
        assertFalse(message("3", "x", role = "assistant", kind = MessageKind.NARRATION).isAiReply)
        assertFalse(message("4", "x", role = "assistant", kind = MessageKind.PRIVATE_NOTE).isAiReply)
        assertFalse(message("5", "x", role = "user").isAiReply)
    }

    @Test fun privateNoteIsExcludedFromPromptAndWorldHistory() {
        val history = listOf(
            message("1", "使用者說的話", order = 1, role = "user"),
            message("2", "AI 回覆", order = 2, role = "assistant"),
            message("3", "我自己看的備忘", order = 3, kind = MessageKind.PRIVATE_NOTE),
        )
        val effective = EffectiveHistoryResolver.resolve(conversation(), history, ChatGenerationKind.NEW_REPLY)
        assertEquals(listOf("1", "2"), effective.promptHistory.map { it.id })
        assertEquals(listOf("1", "2"), effective.worldHistory.map { it.id })
    }

    @Test fun privateNoteDoesNotConsumeSummaryKeepCount() {
        val history = listOf(
            message("1", "第一句", order = 1),
            message("2", "第二句", order = 2),
            message("3", "備忘", order = 3, kind = MessageKind.PRIVATE_NOTE),
            message("4", "第三句", order = 4),
        )
        val effective = EffectiveHistoryResolver.resolve(conversation(), history, ChatGenerationKind.NEW_REPLY)
        // 私人註記不佔有效對話名額，實際送出的仍是三則。
        assertEquals(3, effective.promptHistory.size)
        assertTrue(effective.promptHistory.none { it.kind == MessageKind.PRIVATE_NOTE })
    }

    @Test fun promptMarksAuthoredContentAndExplainsIt() {
        val history = listOf(
            message("1", "我們進城吧", order = 1, role = "user"),
            message("2", "三日後，眾人抵達山門。", order = 2, kind = MessageKind.NARRATION),
            message("3", "掌櫃的台詞", order = 3, kind = MessageKind.AUTHORED_CHARACTER, author = "掌櫃"),
            message("4", "備忘：讓掌櫃與使者是舊識", order = 4, kind = MessageKind.PRIVATE_NOTE),
            message("5", "AI 回覆", order = 5, role = "assistant"),
        )
        val prompt = composePrompt(
            conversation(),
            history,
            character = null,
            persona = null,
            worldSets = emptyList(),
            worldEntries = emptyList(),
        )
        val system = prompt.messages.first { it.role == "system" }.content
        assertTrue("System 要說明標記的意義", system.contains("作者補寫的故事紀錄"))

        val sent = prompt.messages.filter { it.role != "system" }
        assertEquals(listOf("user", "user", "user", "assistant"), sent.map { it.role })
        assertEquals("我們進城吧", sent[0].content)
        assertEquals("【旁白】\n三日後，眾人抵達山門。", sent[1].content)
        assertEquals("【角色台詞：掌櫃】\n掌櫃的台詞", sent[2].content)
        // 私人註記完全沒有出現在送出的內容裡。
        assertTrue(sent.none { it.content.contains("備忘") })
    }

    @Test fun authoredContentAloneDoesNotRequireMarkerExplanation() {
        val history = listOf(message("1", "普通訊息", order = 1, role = "user"))
        val prompt = composePrompt(conversation(), history, null, null, emptyList(), emptyList())
        val system = prompt.messages.first { it.role == "system" }.content
        assertFalse(system.contains("作者補寫的故事紀錄"))
    }

    private fun conversation() = ConversationEntity("c", "chat", 1, 1)

    private fun message(
        id: String,
        content: String,
        order: Long = 1,
        role: String = "user",
        kind: MessageKind = MessageKind.CHAT,
        excluded: Boolean = false,
        deleted: Boolean = false,
        author: String? = null,
    ) = MessageEntity(
        id = id,
        conversationId = "c",
        role = role,
        content = content,
        createdAt = order,
        currentVersionId = "$id-v1",
        sortOrder = order,
        excluded = excluded,
        deleted = deleted,
        kind = kind,
        authorName = author,
    )
}
