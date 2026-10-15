package com.aichat.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.*
import com.aichat.app.data.*

@Composable
internal fun ChatMemoryScreen(vm: ChatViewModel, language: AppLanguage, onBack: () -> Unit) {
    val selectedId by vm.selectedConversationId.collectAsStateWithLifecycle()
    val id = rememberSaveable { selectedId.orEmpty() }
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val branch by vm.activeBranch.collectAsStateWithLifecycle()
    val route by vm.routeSnapshot.collectAsStateWithLifecycle()
    val saving by vm.isSavingChatInfo.collectAsStateWithLifecycle()
    val summarizing by vm.isSummarizingConversation.collectAsStateWithLifecycle()
    val busy = modelWorkBusy(vm) || saving
    var mode by rememberSaveable { mutableStateOf(ManualSummaryMode.UN_SUMMARIZED) }
    var keep by rememberSaveable { mutableStateOf(20) }
    var menu by remember { mutableStateOf(false) }
    val conversation = conversations.firstOrNull { it.id == id }
    val currentBranch = branch?.takeIf { it.conversationId == id && id == selectedId }
    val active = if (currentBranch == null) null else conversation?.copy(summary = currentBranch.summary,
        summaryThroughOrder = currentBranch.summaryThroughOrder, contextStartOrder = currentBranch.contextStartOrder,
        summaryThroughAt = 0, contextStartAt = 0)
    val messages = route.messages.takeIf { route.conversationId == id && route.branchId == currentBranch?.id }.orEmpty()
    val plan = active?.let { conversationSummaryPlan(it, messages, keep, mode) }
    ChatSettingsPage(language.pick("對話記憶", "对话记忆"), language, onBack) {
        if (active == null) {
            Text(language.pick("正在載入，或這段對話已不存在。請返回後重試。", "正在加载，或这段对话已不存在。请返回后重试。"))
        } else {
            SettingHeading(language.pick("目前摘要", "当前摘要"))
            if (active.summary.isBlank()) Text(language.pick("目前沒有較早對話摘要。", "目前没有较早对话摘要。"))
            else SelectionContainer {
                Text(active.summary, Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
            }
            Text(language.pick("將較早的對話整理成摘要供 AI 使用，聊天紀錄仍保留。手動壓縮會呼叫目前配置的 AI。", "将较早的对话整理成摘要供 AI 使用，聊天记录仍保留。手动压缩会调用当前配置的 AI。"))
            SettingHeading(language.pick("手動壓縮", "手动压缩"))
            Box {
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(manualSummaryModeLabel(mode, language)) }
                DropdownMenu(menu, { menu = false }) { ManualSummaryMode.entries.forEach { option ->
                    DropdownMenuItem({ Text(manualSummaryModeLabel(option, language)) }, { mode = option; menu = false })
                } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(language.pick("保留最近訊息數", "保留最近消息数"), Modifier.weight(1f))
                TextButton({ keep = (keep - 1).coerceAtLeast(1) }, enabled = keep > 1 && !busy) { Text("-") }
                Text(keep.toString())
                TextButton({ keep = (keep + 1).coerceAtMost(100) }, enabled = keep < 100 && !busy) { Text("+") }
            }
            Text(language.pick("將整理 ${plan?.messagesToSummarize?.size ?: 0} 則較早訊息；保留最近 $keep 則。", "将整理 ${plan?.messagesToSummarize?.size ?: 0} 则较早消息；保留最近 $keep 则。"))
            if (busy) Text(language.pick("生成、摘要或代擬進行中，暫時不能啟動壓縮。", "生成、摘要或代拟进行中，暂时不能启动压缩。"))
            Button(onClick = { vm.manuallySummarizeConversation(mode, keep, id) }, enabled = !busy && plan?.messagesToSummarize?.isNotEmpty() == true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (summarizing) language.pick("壓縮中…", "压缩中…") else language.pick("開始手動壓縮", "开始手动压缩"))
            }
        }
    }
}
