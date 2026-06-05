package com.aichat.app

import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSummaryPlanTest {
    @Test
    fun unSummarizedModeKeepsExistingSummaryAndSkipsCoveredMessages() {
        val conversation = conversation(summary = "舊摘要", summaryThroughAt = 3)
        val plan = conversationSummaryPlan(conversation, messages(1..10), keepRecentMessages = 3, mode = ManualSummaryMode.UN_SUMMARIZED)

        assertEquals("舊摘要", plan.existingSummary)
        assertEquals(listOf(4L, 5L, 6L, 7L), plan.messagesToSummarize.map { it.createdAt })
        assertEquals(7L, plan.summaryThroughAt)
    }

    @Test
    fun rebuildAllModeIgnoresExistingSummaryAndUsesAllMessages() {
        val conversation = conversation(summary = "舊摘要", summaryThroughAt = 6)
        val plan = conversationSummaryPlan(conversation, messages(1..10), keepRecentMessages = 4, mode = ManualSummaryMode.REBUILD_ALL)

        assertEquals("", plan.existingSummary)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), plan.messagesToSummarize.map { it.createdAt })
        assertEquals(6L, plan.summaryThroughAt)
    }

    @Test
    fun keepRecentCountIsClampedToOneToOneHundred() {
        val conversation = conversation()
        val keepOne = conversationSummaryPlan(conversation, messages(1..4), keepRecentMessages = 0, mode = ManualSummaryMode.UN_SUMMARIZED)
        val keepHundred = conversationSummaryPlan(conversation, messages(1..101), keepRecentMessages = 999, mode = ManualSummaryMode.UN_SUMMARIZED)

        assertEquals(listOf(1L, 2L, 3L), keepOne.messagesToSummarize.map { it.createdAt })
        assertEquals(listOf(1L), keepHundred.messagesToSummarize.map { it.createdAt })
    }

    private fun conversation(summary: String = "", summaryThroughAt: Long = 0) = ConversationEntity(
        id = "conversation",
        title = "Test",
        createdAt = 0,
        updatedAt = 0,
        summary = summary,
        summaryThroughAt = summaryThroughAt,
    )

    private fun messages(range: IntRange) = range.map { index ->
        MessageEntity(
            id = "message-$index",
            conversationId = "conversation",
            role = if (index % 2 == 0) "assistant" else "user",
            content = "message $index",
            createdAt = index.toLong(),
        )
    }
}
