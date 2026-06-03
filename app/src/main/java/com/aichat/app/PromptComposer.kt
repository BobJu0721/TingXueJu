package com.aichat.app

import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.ProfileEntity
import com.aichat.app.data.WorldEntryEntity
import com.aichat.app.data.WorldSetEntity
import com.aichat.app.network.ApiChatMessage
import org.json.JSONArray

data class PromptResult(
    val messages: List<ApiChatMessage>,
    val activatedEntries: List<WorldEntryEntity>,
)

fun jsonStrings(json: String): List<String> = runCatching {
    val array = JSONArray(json)
    buildList {
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}.getOrDefault(emptyList())

fun toJsonStrings(values: List<String>): String = JSONArray(values).toString()

fun activateWorldEntries(
    entries: List<WorldEntryEntity>,
    worldSets: List<WorldSetEntity>,
    history: List<MessageEntity>,
): List<WorldEntryEntity> {
    val setDepths = worldSets.associate { it.id to it.scanDepth.coerceIn(1, 100) }
    return entries
        .asSequence()
        .filter { it.enabled }
        .filter { entry ->
            if (entry.alwaysInclude) return@filter true
            val keywords = jsonStrings(entry.keywordsJson)
            if (keywords.isEmpty()) return@filter false
            val searchable = history.takeLast(setDepths[entry.worldSetId] ?: 10)
                .joinToString("\n") { it.content }
            keywords.any { keyword -> searchable.contains(keyword, ignoreCase = true) }
        }
        .distinctBy { it.id }
        .sortedWith(compareBy<WorldEntryEntity> { it.sortOrder }.thenBy { it.title })
        .toList()
}

fun composePrompt(
    conversation: ConversationEntity,
    history: List<MessageEntity>,
    character: ProfileEntity?,
    persona: ProfileEntity?,
    worldSets: List<WorldSetEntity>,
    worldEntries: List<WorldEntryEntity>,
): PromptResult {
    val visibleHistory = history.filter {
        it.content.isNotBlank() &&
            it.createdAt >= conversation.contextStartAt &&
            it.createdAt > conversation.summaryThroughAt
    }
    val activatedEntries = activateWorldEntries(worldEntries, worldSets, visibleHistory)
    val systemText = buildString {
        appendLine("請自然地延續對話。以下資料是本次對話可使用的背景設定。")
        appendProfile("AI 扮演的角色", character)
        appendProfile("使用者身份 Persona", persona)
        if (activatedEntries.isNotEmpty()) {
            appendLine()
            appendLine("## 本次命中的世界設定")
            activatedEntries.forEach { entry ->
                appendLine("### ${entry.title}")
                appendLine(entry.content)
            }
        }
        if (conversation.summary.isNotBlank()) {
            appendLine()
            appendLine("## 較早對話摘要")
            appendLine(conversation.summary)
        }
    }.trim()
    return PromptResult(
        messages = buildList {
            if (systemText.isNotBlank()) add(ApiChatMessage("system", systemText))
            visibleHistory.forEach { add(ApiChatMessage(it.role, it.content)) }
        },
        activatedEntries = activatedEntries,
    )
}

private fun StringBuilder.appendProfile(title: String, profile: ProfileEntity?) {
    if (profile == null) return
    appendLine()
    appendLine("## $title：${profile.name}")
    appendField("簡介", profile.summary)
    appendField("個性", profile.personality)
    appendField("背景", profile.background)
    appendField("範例對話", profile.exampleDialogue)
    appendField("開場白參考", profile.greeting)
    appendField("額外指示", profile.extraInstructions)
}

private fun StringBuilder.appendField(label: String, content: String) {
    if (content.isBlank()) return
    appendLine("### $label")
    appendLine(content)
}
