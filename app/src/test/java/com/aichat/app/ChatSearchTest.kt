package com.aichat.app

import com.aichat.app.data.MessageEntity
import org.junit.Assert.*
import org.junit.Test

class ChatSearchTest {
    @Test fun searchIsLiteralCaseInsensitiveAndIncludesExcludedMessages() {
        val messages = listOf(
            message("1", "Hello %_ (World)"),
            message("2", "hello again", excluded = true),
            message("3", "沒有命中"),
        )
        assertEquals(listOf("1"), findChatMessages("%_ (", messages).second.map { it.messageId })
        assertEquals(listOf("1", "2"), findChatMessages("HELLO", messages).second.map { it.messageId })
    }

    @Test fun oneMessageWithRepeatedTextStillCountsOnceAndTenThousandRemainOrdered() {
        val messages = (0 until 10_000).map { index ->
            message(index.toString(), if (index % 1000 == 0) "needle needle" else "text $index")
        }
        val hits = findChatMessages("needle", messages).second
        assertEquals(10, hits.size)
        assertEquals("0", hits.first().messageId)
        assertEquals("9000", hits.last().messageId)
    }

    private fun message(id: String, content: String, excluded: Boolean = false) =
        MessageEntity(id, "c", "user", content, id.toLongOrNull() ?: 1, "$id-v1", (id.toLongOrNull() ?: 0) + 1, excluded)
}
