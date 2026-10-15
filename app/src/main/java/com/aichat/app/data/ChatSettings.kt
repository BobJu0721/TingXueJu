package com.aichat.app.data

data class StorySettings(
    val sceneNote: SceneNote = SceneNote(),
    val personaId: String? = null,
    val worldSetIds: Set<String> = emptySet(),
)

data class ReplySettings(
    val preference: ReplyLengthPreference = ReplyLengthPreference.DEFAULT,
    val maxTokens: Int? = null,
    val field: TokenLimitField = TokenLimitField.AUTO,
)

data class ChatSettingsSnapshot(
    val conversationId: String,
    val branchId: String,
    val story: StorySettings,
    val reply: ReplySettings,
)

internal fun ConversationEntity.replySettings() = ReplySettings(replyLengthPreference, maxOutputTokens, tokenLimitField)
