package com.aichat.app

import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun fewerMessagesThanKeepCountSummarizesNothing() {
        // 訊息數等於 keepCount 時，dropLast 後沒有可摘要的訊息。
        val conversation = conversation()
        val plan = conversationSummaryPlan(conversation, messages(1..3), keepRecentMessages = 3, mode = ManualSummaryMode.UN_SUMMARIZED)

        assertTrue(plan.messagesToSummarize.isEmpty())
        // 沒有可摘要訊息時，summaryThroughAt 應維持原本的 conversation 值，不被覆寫成 0 或亂跳。
        assertEquals(0L, plan.summaryThroughAt)
    }

    @Test
    fun unSummarizedModePreservesExistingSummaryThroughAtWhenNothingNewToSummarize() {
        // 已有摘要到第 5 則，但沒有更新的訊息可摘要時，
        // 既有的 summaryThroughAt 必須被保留（不能誤清成 0）。
        val conversation = conversation(summary = "舊摘要", summaryThroughAt = 5)
        val plan = conversationSummaryPlan(conversation, messages(1..5), keepRecentMessages = 3, mode = ManualSummaryMode.UN_SUMMARIZED)

        assertTrue(plan.messagesToSummarize.isEmpty())
        assertEquals("舊摘要", plan.existingSummary)
        assertEquals(5L, plan.summaryThroughAt)
    }

    @Test
    fun blankMessagesAreExcludedBeforePlanning() {
        // content 空白的訊息應先被過濾掉，不計入要摘要或要保留的數量。
        val conversation = conversation()
        val history = listOf(
            message(1, "實內容一"),
            message(2, "   "),
            message(3, ""),
            message(4, "實內容四"),
        )
        val plan = conversationSummaryPlan(conversation, history, keepRecentMessages = 2, mode = ManualSummaryMode.UN_SUMMARIZED)

        // 過濾後剩 2 則有效訊息（1, 4），keep=2 全部保留，無可摘要訊息。
        assertTrue(plan.messagesToSummarize.isEmpty())
    }

    @Test
    fun rebuildAllModeSummarizesNothingWhenMessageCountEqualsKeepCount() {
        val conversation = conversation(summary = "舊摘要", summaryThroughAt = 99)
        val plan = conversationSummaryPlan(conversation, messages(1..3), keepRecentMessages = 3, mode = ManualSummaryMode.REBUILD_ALL)

        assertTrue(plan.messagesToSummarize.isEmpty())
        // 無可摘要訊息時保留原本 summaryThroughAt；existingSummary 則依 REBUILD_ALL 恆為空。
        assertEquals("", plan.existingSummary)
        assertEquals(99L, plan.summaryThroughAt)
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
        message(index, "message $index")
    }

    private fun message(index: Int, content: String) = MessageEntity(
        id = "message-$index",
        conversationId = "conversation",
        role = if (index % 2 == 0) "assistant" else "user",
        content = content,
        createdAt = index.toLong(),
    )
}
