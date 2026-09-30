package com.aichat.app.domain

import com.aichat.app.composePrompt
import com.aichat.app.data.*
import org.junit.Assert.*
import org.junit.Test

class ChatGenerationTest {
    @Test fun excludedMessagesAreRemovedFromPromptAndWorldHistory() {
        val conversation = conversation()
        val history = listOf(
            message("1", 1, "保留"),
            message("2", 2, "秘密", excluded = true),
            message("3", 3, "繼續"),
        )
        val result = EffectiveHistoryResolver.resolve(conversation, history)
        assertEquals(listOf("1", "3"), result.promptHistory.map { it.id })
        assertEquals(listOf("1", "3"), result.worldHistory.map { it.id })
    }

    @Test fun alternativeCannotReadTargetFutureOrFutureSummary() {
        val history = listOf(
            message("1", 1, "問題"),
            message("2", 2, "原答案", role = "assistant"),
            message("3", 3, "未來訊息"),
        )
        val conversation = conversation().copy(summary = "包含未來", summaryThroughOrder = 3)
        val request = ChatGenerationRequest(
            ChatGenerationKind.ALTERNATIVE,
            conversation.id,
            targetMessageId = "2",
            baseVersionId = "2-v1",
            expectedRevision = 0,
        )
        val result = composePrompt(conversation, history, null, null, emptyList(), emptyList(), request = request)
        assertFalse(result.messages.any { it.content.contains("原答案") })
        assertFalse(result.messages.any { it.content.contains("未來訊息") })
        assertFalse(result.messages.any { it.content.contains("包含未來") })
        assertEquals("問題", result.messages.last().content)
    }

    @Test fun continuationIncludesBaseAnswerAndInvisibleInstruction() {
        val history = listOf(
            message("1", 1, "問題"),
            message("2", 2, "回答前半", role = "assistant"),
        )
        val request = ChatGenerationRequest(ChatGenerationKind.CONTINUATION, "c", "2", "2-v1", 0)
        val result = composePrompt(conversation(), history, null, null, emptyList(), emptyList(), request = request)
        assertEquals("回答前半", result.messages.last().content)
        assertEquals("system", result.messages.first().role)
        assertTrue(result.messages.first().content.contains("只輸出新增內容"))
    }

    @Test fun oldTargetBeforeTrimRestoresEarlierHistoryForThisRequest() {
        val history = listOf(
            message("1", 1, "必要的前文"),
            message("2", 2, "待重新生成", role = "assistant"),
            message("3", 3, "後續訊息"),
        )
        val request = ChatGenerationRequest(ChatGenerationKind.ALTERNATIVE, "c", "2", "2-v1", 0)
        val result = composePrompt(
            conversation().copy(contextStartOrder = 3), history,
            null, null, emptyList(), emptyList(), request = request,
        )
        assertEquals("必要的前文", result.messages.last().content)
    }

    @Test fun answerFromUserKeepsTargetEvenWhenItWasPreviouslySummarized() {
        val history = listOf(message("1", 1, "舊問題"), message("2", 2, "重新回答這題"))
        val request = ChatGenerationRequest(ChatGenerationKind.ANSWER_FROM_USER, "c", "2", "2-v1", 0)
        val result = composePrompt(
            conversation().copy(summary = "較早摘要", summaryThroughOrder = 2), history,
            null, null, emptyList(), emptyList(), request = request,
        )
        assertTrue(result.messages.any { it.role == "user" && it.content == "重新回答這題" })
    }

    @Test fun lengthPreferenceIsOnlyInjectedWhenSelected() {
        val normal = composePrompt(conversation(), emptyList(), null, null, emptyList(), emptyList())
        val detailed = composePrompt(
            conversation().copy(replyLengthPreference = ReplyLengthPreference.DETAILED),
            emptyList(), null, null, emptyList(), emptyList(),
        )
        assertFalse(normal.messages.first().content.contains("篇幅偏好"))
        assertTrue(detailed.messages.first().content.contains("篇幅偏好"))
    }

    private fun conversation() = ConversationEntity("c", "chat", 1, 1)

    private fun message(id: String, order: Long, content: String, role: String = "user", excluded: Boolean = false) =
        MessageEntity(id, "c", role, content, order, "$id-v1", order, excluded)
}
