package com.aichat.app.domain

import com.aichat.app.activateWorldEntries
import com.aichat.app.appendProfile
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ConversationRepository
import com.aichat.app.data.ProfileRepository
import com.aichat.app.data.WorldInfoRepository
import com.aichat.app.data.promptRole
import com.aichat.app.data.sceneNoteValue
import com.aichat.app.data.toPromptContent
import com.aichat.app.network.AiApiClient
import com.aichat.app.network.ApiChatMessage
import com.aichat.app.pick
import com.aichat.app.promptLabels
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/**
 * AI 幫使用者擬回覆。
 *
 * 這是一個獨立的代擬任務，**不能沿用「AI 扮演角色」的生成流程**：它只草擬使用者自己的台詞
 * 或行動，不代替其他角色回應，也不把故事推進到他們回應之後。
 *
 * 只把結果交給呼叫端顯示，不寫入任何聊天資料：不建立 GenerationDraft、不建立 Assistant 占位
 * 訊息、不動分支／摘要／世界設定，也不更新歷史修訂號。
 */
class DraftUserReplyUseCase(
    private val conversationRepository: ConversationRepository,
    private val profileRepository: ProfileRepository,
    private val worldInfoRepository: WorldInfoRepository,
    private val api: AiApiClient,
) {
    suspend operator fun invoke(
        conversationId: String,
        settings: AppSettings,
        key: String,
        instruction: String,
        existingInput: String,
        expectedRevision: Long,
        sourceBranchId: String,
        onDelta: suspend (String) -> Unit,
    ): String {
        val snapshot = conversationRepository.getGenerationSnapshot(conversationId)
            ?: throw IOException(settings.language.pick("找不到這段對話。", "找不到这段对话。"))
        val conversation = snapshot.conversation
        if (conversation.historyRevision != expectedRevision || snapshot.branch.id != sourceBranchId) throw staleDraft(settings)
        val branch = snapshot.branch
        val history = snapshot.messages
        val effective = EffectiveHistoryResolver.resolve(conversation, history, ChatGenerationKind.NEW_REPLY)

        val worldIds = conversationRepository.getConversationWorldSetIds(conversationId)
        val worldSets = if (worldIds.isEmpty()) emptyList() else worldInfoRepository.getWorldSets(worldIds)
        val entries = if (worldIds.isEmpty()) emptyList() else worldInfoRepository.getWorldEntries(worldIds)
        val activated = activateWorldEntries(entries, worldSets, effective.worldHistory)

        val labels = settings.language.promptLabels()
        val system = buildString {
            appendLine(fixedInstruction(settings))
            appendLine(settings.language.pick(
                "以下對話對象設定僅作為背景參考，不是你的扮演任務。",
                "以下对话对象设定仅作为背景参考，不是你的扮演任务。",
            ))
            appendProfile(settings.language.pick("對話對象", "对话对象"), profileRepository.getProfile(conversation.characterId), labels)
            // 沒有 Persona 時只靠現有對話推斷使用者身份，不憑空建立身份設定。
            appendProfile(settings.language.pick("使用者身份", "使用者身份"), profileRepository.getProfile(conversation.personaId), labels)
            if (effective.promptHistory.any { it.kind.isAuthored }) appendLine(settings.language.pick(
                "標記為旁白或角色台詞的內容，是作者補寫的故事紀錄，不是使用者本人說的話。",
                "标记为旁白或角色台词的内容，是作者补写的故事记录，不是使用者本人说的话。",
            ))
            val overviews = worldSets.filter { it.overview.isNotBlank() }
            if (overviews.isNotEmpty()) {
                appendLine()
                appendLine("## ${labels.worldOverview}")
                overviews.forEach { set ->
                    appendLine("### ${set.name}")
                    appendLine(set.overview)
                }
            }
            if (activated.isNotEmpty()) {
                appendLine()
                appendLine("## ${labels.worldHits}")
                activated.forEach { entry ->
                    appendLine("### ${entry.title}")
                    appendLine(entry.content)
                }
            }
            if (effective.includeSummary && conversation.summary.isNotBlank()) {
                appendLine()
                appendLine("## ${labels.olderSummary}")
                appendLine(conversation.summary)
            }
            branch.sceneNoteValue().takeIf { it.enabled && it.text.isNotBlank() }?.let { note ->
                appendLine()
                appendLine(settings.language.pick("## 本場劇情提示", "## 本场剧情提示"))
                appendLine(settings.language.pick(
                    "以下內容是創作指導；尚未發生的劇情不要當成既成事實。",
                    "以下内容是创作指导；尚未发生的剧情不要当成既成事实。",
                ))
                appendLine(note.text)
            }
        }.trim()

        val ask = buildString {
            // 使用者目前的輸入草稿要明確帶進來，讓代擬是「改寫」而不是從零猜。
            if (existingInput.isNotBlank()) {
                appendLine(settings.language.pick("我目前草擬的內容：", "我目前草拟的内容："))
                appendLine(existingInput)
            }
            if (instruction.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                appendLine(settings.language.pick("這次想表達的意思：", "这次想表达的意思："))
                appendLine(instruction)
            }
            if (isNotEmpty()) appendLine()
            appendLine(settings.language.pick("請草擬我接下來要送出的這一則。", "请草拟我接下来要发送的这一则。"))
        }.trim()

        val messages = buildList {
            add(ApiChatMessage("system", system))
            effective.promptHistory.forEach { add(ApiChatMessage(it.promptRole(), it.toPromptContent())) }
            if (ask.isNotBlank()) add(ApiChatMessage("user", ask))
        }

        val collected = StringBuilder()
        api.streamChat(
            settings = settings,
            apiKey = key,
            messages = messages,
            reasoningMode = conversation.reasoningMode,
            options = null,
            onToken = { token ->
                collected.append(token)
                onDelta(token)
            },
        )
        currentCoroutineContext().ensureActive()
        if (collected.isBlank()) throw IOException(settings.language.pick("API 沒有回傳草稿正文。", "API 没有返回草稿正文。"))
        return collected.toString()
    }

    private fun fixedInstruction(settings: AppSettings): String = settings.language.pick(
        "你正在協助使用者撰寫下一則訊息。\n" +
            "請參考使用者身份、目前對話與背景，草擬一則可由使用者直接送出的回覆。\n" +
            "只輸出草稿正文，不輸出分析、說明或標題。\n" +
            "只撰寫使用者的台詞或行動，不代替其他角色回應，也不要把故事推進到他們回應之後。",
        "你正在协助使用者撰写下一则消息。\n" +
            "请参考使用者身份、当前对话与背景，草拟一则可由使用者直接发送的回复。\n" +
            "只输出草稿正文，不输出分析、说明或标题。\n" +
            "只撰写使用者的台词或行动，不代替其他角色回应，也不要把故事推进到他们回应之后。",
    )

    private fun staleDraft(settings: AppSettings) = IOException(settings.language.pick(
        "這份草稿產生後對話已經改變，請重新產生。",
        "这份草稿产生后对话已经改变，请重新产生。",
    ))

}
