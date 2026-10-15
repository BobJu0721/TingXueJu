package com.aichat.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.ChatViewModel
import com.aichat.app.pick
import com.aichat.app.data.*

internal fun validMaxTokens(text: String): Boolean = text.isBlank() || (text.all { it in '0'..'9' } && (text.toIntOrNull() ?: 0) > 0)

@Composable
internal fun ChatReplySettingsScreen(vm: ChatViewModel, language: AppLanguage, onBack: () -> Unit) {
    val original = rememberSettingsSnapshot(vm, "reply")
    val saving by vm.isSavingChatInfo.collectAsStateWithLifecycle()
    val busy = modelWorkBusy(vm)
    if (original == null) {
        ChatSettingsPage(language.pick("回覆設定", "回复设置"), language, onBack) { SettingsUnavailable(vm, language) }
        return
    }
    var preference by rememberSaveable(original.conversationId) { mutableStateOf(original.reply.preference) }
    var tokens by rememberSaveable(original.conversationId) { mutableStateOf(original.reply.maxTokens?.toString().orEmpty()) }
    var field by rememberSaveable(original.conversationId) { mutableStateOf(original.reply.field) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val valid = validMaxTokens(tokens)
    val dirty = preference != original.reply.preference || tokens != original.reply.maxTokens?.toString().orEmpty() || field != original.reply.field
    ChatSettingsPage(language.pick("回覆設定", "回复设置"), language, onBack, dirty, saving,
        onSave = { vm.saveReplySettings(original, ReplySettings(preference, tokens.toIntOrNull(), field), onBack) }, canSave = valid, busy = busy) {
        SettingHeading(language.pick("篇幅偏好", "篇幅偏好"))
        SettingsChoice(replyLengthLabel(preference, language), !saving, ReplyLengthPreference.entries.map { it to replyLengthLabel(it, language) }) { preference = it }
        OutlinedTextField(tokens, { tokens = it }, Modifier.fillMaxWidth(), enabled = !saving,
            label = { Text(language.pick("最大輸出 Token（選填）", "最大输出 Token（选填）")) },
            placeholder = { Text(language.pick("空白為不指定", "空白为不指定")) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid,
            supportingText = { if (!valid) Text(language.pick("請輸入正整數，或留白。", "请输入正整数，或留空。")) })
        Text(language.pick("篇幅偏好用來引導回答長短；Token 上限限制輸出量，實際行為依模型與服務支援而定。", "篇幅偏好用来引导回答长短；Token 上限限制输出量，实际行为依模型与服务支持而定。"))
        TextButton(onClick = { advanced = !advanced }) {
            Text(language.pick("進階設定", "进阶设置"))
            Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (advanced) {
            SettingHeading(language.pick("Token 參數欄位", "Token 参数字段"))
            SettingsChoice(tokenFieldLabel(field, language), !saving, TokenLimitField.entries.map { it to tokenFieldLabel(it, language) }) { field = it }
            Text(if (field == TokenLimitField.AUTO) language.pick("由 App 依既有策略決定參數，不保證偵測所有服務。", "由 App 依现有策略决定参数，不保证检测所有服务。")
                else language.pick("強制使用所選欄位傳送上限；請依服務要求選用。", "强制使用所选字段发送上限；请依服务要求选用。"))
        }
    }
}

@Composable
private fun <T> SettingsChoice(label: String, enabled: Boolean, choices: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ menu = true }, Modifier.fillMaxWidth(), enabled = enabled) { Text(label) }
        DropdownMenu(menu, { menu = false }) { choices.forEach { (value, title) ->
            DropdownMenuItem({ Text(title) }, { menu = false; onSelect(value) })
        } }
    }
}

private fun replyLengthLabel(value: ReplyLengthPreference, language: AppLanguage) = when (value) {
    ReplyLengthPreference.DEFAULT -> language.pick("預設", "默认")
    ReplyLengthPreference.SHORT -> language.pick("簡短", "简短")
    ReplyLengthPreference.MEDIUM -> language.pick("適中", "适中")
    ReplyLengthPreference.DETAILED -> language.pick("詳細", "详细")
}
private fun tokenFieldLabel(value: TokenLimitField, language: AppLanguage) = when (value) {
    TokenLimitField.AUTO -> language.pick("自動（建議）", "自动（建议）")
    TokenLimitField.MAX_TOKENS -> "max_tokens"
    TokenLimitField.MAX_COMPLETION_TOKENS -> "max_completion_tokens"
}
