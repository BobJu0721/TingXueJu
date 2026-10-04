package com.aichat.app

import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.AppLanguage
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.ProfileEntity
import com.aichat.app.data.WorldEntryEntity
import com.aichat.app.data.WorldSetEntity
import com.aichat.app.data.SceneNote
import com.aichat.app.data.isModelVisible
import com.aichat.app.data.promptRole
import com.aichat.app.data.toPromptContent
import com.aichat.app.network.ApiChatMessage
import com.aichat.app.domain.ChatGenerationKind
import com.aichat.app.domain.ChatGenerationRequest
import com.aichat.app.domain.EffectiveHistoryResolver
import com.aichat.app.domain.instruction
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
    language: AppLanguage = AppLanguage.TRADITIONAL_CHINESE,
    request: ChatGenerationRequest? = null,
    sceneNote: SceneNote = SceneNote(),
): PromptResult {
    val target = request?.targetMessageId?.let { id -> history.firstOrNull { it.id == id } }
    val effective = EffectiveHistoryResolver.resolve(
        conversation,
        history,
        request?.kind ?: ChatGenerationKind.NEW_REPLY,
        target,
    )
    val activatedEntries = activateWorldEntries(worldEntries, worldSets, effective.worldHistory)
    val labels = language.promptLabels()
    val systemText = buildString {
        appendLine(labels.continueConversation)
        // 有旁白或手寫角色台詞時，明確告訴模型那些是作者補寫的故事紀錄。
        if (effective.promptHistory.any { it.kind.isAuthored }) {
            appendLine(language.pick(
                "標記為旁白或角色台詞的內容，是作者補寫的故事紀錄；請繼續以原本設定的 AI 角色身份回應。",
                "标记为旁白或角色台词的内容，是作者补写的故事记录；请继续以原本设定的 AI 角色身份回应。",
            ))
        }
        conversation.replyLengthPreference.instruction(language)?.let(::appendLine)
        if (request?.kind == ChatGenerationKind.CONTINUATION) {
            appendLine(language.pick(
                "接續上一則回答，只輸出新增內容，不重述既有文字。",
                "接续上一则回答，只输出新增内容，不重述已有文字。",
            ))
        }
        appendProfile(labels.aiCharacter, character, labels)
        appendProfile(labels.persona, persona, labels)
        val overviews = worldSets.filter { it.overview.isNotBlank() }
        if (overviews.isNotEmpty()) {
            appendLine()
            appendLine("## ${labels.worldOverview}")
            overviews.forEach { set ->
                appendLine("### ${set.name}")
                appendLine(set.overview)
            }
        }
        if (activatedEntries.isNotEmpty()) {
            appendLine()
            appendLine("## ${labels.worldHits}")
            activatedEntries.forEach { entry ->
                appendLine("### ${entry.title}")
                appendLine(entry.content)
            }
        }
        if (effective.includeSummary) {
            appendLine()
            appendLine("## ${labels.olderSummary}")
            appendLine(conversation.summary)
        }
        if (sceneNote.enabled && sceneNote.text.isNotBlank()) {
            appendLine()
            appendLine(language.pick("## 本場劇情提示", "## 本场剧情提示"))
            appendLine(language.pick(
                "以下內容是本次對話的創作指導；尚未發生的劇情不要當成既成事實。",
                "以下内容是本次对话的创作指导；尚未发生的剧情不要当成既成事实。",
            ))
            appendLine()
            append(sceneNote.text)
        }
    }.let { if (sceneNote.enabled && sceneNote.text.isNotBlank()) it.trimStart() else it.trim() }
    return PromptResult(
        messages = buildList {
            if (systemText.isNotBlank()) add(ApiChatMessage("system", systemText))
            effective.promptHistory.forEach { add(ApiChatMessage(it.promptRole(), it.toPromptContent())) }
        },
        activatedEntries = activatedEntries,
    )
}

internal fun StringBuilder.appendProfile(title: String, profile: ProfileEntity?, labels: PromptLabels) {
    if (profile == null) return
    appendLine()
    appendLine("## $title：${profile.name}")
    appendField(labels.summary, profile.summary)
    appendField(labels.personality, profile.personality)
    appendField(labels.background, profile.background)
    appendField(labels.exampleDialogue, profile.exampleDialogue)
    appendField(labels.greetingReference, profile.greeting)
    appendField(labels.extraInstructions, profile.extraInstructions)
}

private fun StringBuilder.appendField(label: String, content: String) {
    if (content.isBlank()) return
    appendLine("### $label")
    appendLine(content)
}
