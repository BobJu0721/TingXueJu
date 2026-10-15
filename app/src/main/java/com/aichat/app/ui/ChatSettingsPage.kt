package com.aichat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.ChatViewModel
import com.aichat.app.pick
import com.aichat.app.data.*

private val SnapshotSaver = listSaver<ChatSettingsSnapshot?, Any>(
    save = { if (it == null) emptyList() else listOf(it.conversationId, it.branchId, it.story.sceneNote.text, it.story.sceneNote.enabled,
        it.story.personaId.orEmpty(), ArrayList(it.story.worldSetIds), it.reply.preference.name,
        it.reply.maxTokens?.toString().orEmpty(), it.reply.field.name) },
    restore = {
        if (it.isEmpty()) null else {
            @Suppress("UNCHECKED_CAST")
            ChatSettingsSnapshot(it[0] as String, it[1] as String,
                StorySettings(SceneNote(it[2] as String, it[3] as Boolean), (it[4] as String).ifBlank { null },
                    (it[5] as ArrayList<String>).toSet()),
                ReplySettings(ReplyLengthPreference.valueOf(it[6] as String), (it[7] as String).toIntOrNull(),
                    TokenLimitField.valueOf(it[8] as String)))
        }
    },
)

@Composable
internal fun rememberSettingsSnapshot(vm: ChatViewModel, page: String): ChatSettingsSnapshot? {
    val id by vm.selectedConversationId.collectAsStateWithLifecycle()
    val loaded by vm.settingsSnapshot.collectAsStateWithLifecycle()
    val loadedToken by vm.settingsLoadToken.collectAsStateWithLifecycle()
    val token = remember { java.util.UUID.randomUUID().toString() }
    // The baseline is saveable too: rotation/process restoration must not silently replace a dirty draft's baseline.
    var original by rememberSaveable(page, stateSaver = SnapshotSaver) { mutableStateOf<ChatSettingsSnapshot?>(null) }
    LaunchedEffect(page) { id?.let { vm.loadChatSettings(it, token) } }
    LaunchedEffect(loaded, loadedToken) { if (original == null && loadedToken == token && loaded?.conversationId == id) original = loaded }
    return original
}

@Composable
internal fun ChatSettingsPage(
    title: String, language: AppLanguage, onBack: () -> Unit,
    dirty: Boolean = false, saving: Boolean = false,
    onSave: (() -> Unit)? = null, canSave: Boolean = true, busy: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var discard by rememberSaveable { mutableStateOf(false) }
    val requestBack = { if (!saving) { if (dirty) discard = true else onBack() } }
    BackHandler(dirty || saving) { requestBack() }
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            Column {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 60.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(50.dp)) { Back(language, requestBack) }
                    Text(title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                Hairline()
            }
        },
        bottomBar = {
            if (onSave != null) Column(Modifier.fillMaxWidth().imePadding().padding(16.dp)) {
                if (busy) Text(language.pick("生成、摘要或代擬進行中，完成後才能儲存。", "生成、摘要或代拟进行中，完成后才能保存。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Button(onClick = onSave, enabled = canSave && !saving && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (saving) language.pick("儲存中…", "保存中…") else language.pick("儲存並返回", "保存并返回"))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(language.pick("尚未儲存$title", "尚未保存$title")) },
        text = { Text(language.pick("返回會捨棄本頁變更。", "返回会舍弃本页更改。")) },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text(language.pick("捨棄變更", "舍弃更改")) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(language.pick("繼續編輯", "继续编辑")) } },
    )
}

@Composable
internal fun SettingsUnavailable(vm: ChatViewModel, language: AppLanguage) {
    val error by vm.settingsLoadError.collectAsStateWithLifecycle()
    if (error == null) CircularProgressIndicator() else Text(error!!, color = MaterialTheme.colorScheme.error)
    Text(language.pick("若聊天室已移除，請返回對話列表。", "若聊天室已移除，请返回对话列表。"), fontSize = 13.sp)
}

@Composable
internal fun SettingHeading(text: String) { Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

@Composable
internal fun modelWorkBusy(vm: ChatViewModel): Boolean {
    val streaming by vm.isStreaming.collectAsStateWithLifecycle()
    val summarizing by vm.isSummarizingConversation.collectAsStateWithLifecycle()
    val draft by vm.draftReply.collectAsStateWithLifecycle()
    return streaming || summarizing || draft?.generating == true
}
