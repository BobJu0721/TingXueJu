package com.aichat.app.data

data class BranchTreeRoute(
    val id: String, val createdAt: Long, val sourceBranchId: String?,
    val forkMessageId: String?, val forkVersionId: String?, val legacyIncomplete: Boolean,
)

/** Bounded projection; never reads reasoning or legacy selected message content. */
data class BranchTreeRow(
    val branchId: String, val messageId: String, val versionId: String, val sortOrder: Long,
    val excluded: Boolean, val deleted: Boolean, val role: String, val kind: MessageKind,
    val versionNumber: Int?, val status: MessageVersionStatus?, val source: MessageVersionSource?,
    val snippet: String, val authorName: String?, val valid: Boolean,
)

data class BranchTreeSnapshot(
    val conversationId: String, val title: String, val activeBranchId: String, val revision: Long,
    val routes: List<BranchTreeRoute>, val rows: List<BranchTreeRow>,
)
data class BranchTreeConversation(val id: String, val title: String, val activeBranchId: String, val historyRevision: Long)

data class BranchPreviewRow(
    val messageId: String, val versionId: String, val sortOrder: Long,
    val excluded: Boolean, val deleted: Boolean, val role: String, val kind: MessageKind,
    val authorName: String?, val content: String, val status: MessageVersionStatus?,
)

data class BranchPreviewPage(val rows: List<BranchPreviewRow>, val start: Int, val total: Int)
enum class BranchSwitchResult { SUCCESS, HISTORY_CHANGED, TARGET_MISSING, INCOMPLETE }

data class BranchLocation(val conversationId: String, val branchId: String, val messageId: String?, val requestId: String)
