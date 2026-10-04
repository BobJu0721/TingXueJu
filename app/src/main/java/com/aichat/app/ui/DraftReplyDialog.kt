package com.aichat.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aichat.app.DraftReplyState
import com.aichat.app.data.AppLanguage
import com.aichat.app.pick

@Composable
internal fun DraftReplyDialog(
    state: DraftReplyState,
    language: AppLanguage,
    onInstruction: (String) -> Unit,
    onGenerate: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onAccept: (Boolean) -> Unit,
    onDismissReplacement: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(language.pick("幫我擬回覆", "帮我拟回复")) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.existingInput.isNotEmpty()) {
                    Text(language.pick("目前的輸入草稿（作為參考）", "当前输入草稿（作为参考）"), fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(state.existingInput, Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState())) }
                }
                OutlinedTextField(
                    value = state.instruction,
                    onValueChange = onInstruction,
                    enabled = !state.generating,
                    label = { Text(language.pick("這次想表達的意思（選填）", "这次想表达的意思（选填）")) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                )
                if (state.generating) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(language.pick("正在擬回覆…", "正在拟回复…"))
                    }
                }
                if (state.content.isNotBlank()) {
                    Text(language.pick("草稿預覽", "草稿预览"), fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(state.content, Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) }
                }
                if (state.incomplete) Text(language.pick("尚未完成", "尚未完成"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = if (state.generating) onStop else onGenerate, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.generating) language.pick("停止", "停止")
                        else if (state.content.isBlank()) language.pick("產生草稿", "产生草稿")
                        else language.pick("再擬一版", "再拟一版"))
                }
                TextButton(onClick = { onAccept(false) }, enabled = !state.generating && state.content.isNotBlank() && state.error == null,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(language.pick("填入輸入框", "填入输入框")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(language.pick("取消", "取消")) } },
    )
    state.replacementInput?.let { previous ->
        AlertDialog(
            onDismissRequest = onDismissReplacement,
            title = { Text(language.pick("取代輸入草稿？", "替换输入草稿？")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(language.pick("原本的輸入內容將被代擬草稿取代。", "原本的输入内容将被代拟草稿替换。"))
                Text(previous, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
            } },
            confirmButton = { TextButton(onClick = { onAccept(true) }) { Text(language.pick("取代", "替换")) } },
            dismissButton = { TextButton(onClick = onDismissReplacement) { Text(language.pick("保留原稿", "保留原稿")) } },
        )
    }
}
