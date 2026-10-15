package com.aichat.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.ChatViewModel
import com.aichat.app.pick
import com.aichat.app.data.*

@Composable
internal fun ChatStoryScreen(vm: ChatViewModel, language: AppLanguage, onBack: () -> Unit) {
    val original = rememberSettingsSnapshot(vm, "story")
    val saving by vm.isSavingChatInfo.collectAsStateWithLifecycle()
    val busy = modelWorkBusy(vm)
    val personas by vm.personas.collectAsStateWithLifecycle()
    val sets by vm.worldSets.collectAsStateWithLifecycle()
    val counts by vm.worldEntryCounts.collectAsStateWithLifecycle()
    if (original == null) {
        ChatSettingsPage(language.pick("劇情設定", "剧情设置"), language, onBack) { SettingsUnavailable(vm, language) }
        return
    }
    var text by rememberSaveable(original.conversationId, original.branchId) { mutableStateOf(original.story.sceneNote.text) }
    var enabled by rememberSaveable(original.conversationId, original.branchId) { mutableStateOf(original.story.sceneNote.enabled) }
    var persona by rememberSaveable(original.conversationId, original.branchId) { mutableStateOf(original.story.personaId) }
    var selectedSets by rememberSaveable(original.conversationId, original.branchId,
        stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it.toList() })) { mutableStateOf(original.story.worldSetIds.toList()) }
    val draft = StorySettings(SceneNote(text, enabled), persona, selectedSets.toSet())
    ChatSettingsPage(language.pick("劇情設定", "剧情设置"), language, onBack,
        dirty = draft != original.story, saving = saving, busy = busy,
        onSave = { vm.saveStorySettings(original, draft, onBack) }) {
        SettingHeading(language.pick("本場劇情提示", "本场剧情提示"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(language.pick("啟用本場劇情提示", "启用本场剧情提示"), Modifier.weight(1f))
            Switch(enabled, { enabled = it }, enabled = !saving, modifier = Modifier.semantics {
                contentDescription = language.pick("啟用本場劇情提示", "启用本场剧情提示")
            })
        }
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(max = 260.dp), enabled = !saving,
            label = { Text(language.pick("劇情提示內容", "剧情提示内容")) }, minLines = 5, maxLines = 10,
            placeholder = { Text(language.pick("可填寫目前場景、接下來的劇情方向或敘事要求。", "可填写目前场景、接下来的剧情方向或叙事要求。")) })
        Text(language.pick("僅套用於目前分支。啟用後，每次生成都會附加；建議保持精簡。", "仅应用于当前分支。启用后，每次生成都会附加；建议保持精简。"),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingHeading(language.pick("我的身分（Persona）", "我的身份（Persona）"))
        Text(language.pick("Persona 與世界設定由目前聊天室所有分支共用。", "Persona 与世界设定由当前聊天室所有分支共用。"))
        RadioCard(persona == null, language.pick("不指定 Persona", "不指定 Persona"), null) { if (!saving) persona = null }
        personas.forEach { profile -> RadioCard(persona == profile.id, profile.name, profile.summary.ifBlank { null }) {
            if (!saving) persona = profile.id
        } }
        if (persona != null && personas.none { it.id == persona }) Text(language.pick("所選 Persona 已不存在，請重新選擇。", "所选 Persona 已不存在，请重新选择。"), color = MaterialTheme.colorScheme.error)
        SettingHeading(language.pick("使用的世界設定", "使用的世界设定"))
        sets.forEach { set -> CheckCard(set.id in selectedSets, set.name,
            language.pick("${counts.firstOrNull { it.worldSetId == set.id }?.count ?: 0} 條目", "${counts.firstOrNull { it.worldSetId == set.id }?.count ?: 0} 条目")) {
            if (!saving) selectedSets = selectedSets.toMutableSet().apply { if (!add(set.id)) remove(set.id) }.toList()
        } }
        if (sets.isEmpty()) Text(language.pick("尚無世界設定集。", "尚无世界设定集。"))
        if (selectedSets.any { id -> sets.none { it.id == id } }) {
            Text(language.pick("所選世界設定已不存在。", "所选世界设定已不存在。"), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { selectedSets = selectedSets.filter { id -> sets.any { it.id == id } } }, enabled = !saving) {
                Text(language.pick("移除已刪除的選擇", "移除已删除的选择"))
            }
        }
    }
}
