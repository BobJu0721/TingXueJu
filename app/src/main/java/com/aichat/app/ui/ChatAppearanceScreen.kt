package com.aichat.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.ChatViewModel
import com.aichat.app.pick
import com.aichat.app.data.AppLanguage
import kotlin.math.roundToInt

@Composable
internal fun ChatAppearanceScreen(vm: ChatViewModel, language: AppLanguage, onBack: () -> Unit) {
    val selectedId by vm.selectedConversationId.collectAsStateWithLifecycle()
    val id = rememberSaveable { selectedId.orEmpty() }
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val updating by vm.isSavingAppearance.collectAsStateWithLifecycle()
    val conversation = conversations.firstOrNull { it.id == id && id == selectedId }
    var transparency by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    val savedOpacity by rememberUpdatedState(conversation?.messageBubbleOpacity ?: 1f)
    LaunchedEffect(conversation?.messageBubbleOpacity) {
        if (!dragging && !pending) transparency = 1f - (conversation?.messageBubbleOpacity ?: 1f).coerceIn(0.35f, 1f)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setConversationBackground(uri, id)
    }
    ChatSettingsPage(language.pick("外觀設定", "外观设置"), language, onBack) {
        Text(language.pick("變更會自動儲存。", "更改会自动保存。"))
        if (conversation == null) Text(language.pick("這段對話已不存在或已切換，請返回。", "这段对话已不存在或已切换，请返回。"))
        else {
            SettingHeading(language.pick("背景圖", "背景图"))
            OutlinedButton({ launcher.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth(), enabled = !updating) {
                Icon(Icons.Default.Image, null)
                Spacer(Modifier.width(8.dp))
                Text(language.pick("上傳圖片", "上传图片"))
            }
            if (conversation.backgroundImagePath.isNotBlank()) OutlinedButton({ vm.clearConversationBackground(id) }, Modifier.fillMaxWidth(), enabled = !updating) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                Text(language.pick("移除背景", "移除背景"), color = MaterialTheme.colorScheme.error)
            }
            SettingHeading(language.pick("泡泡・背景透明度", "泡泡・背景透明度"))
            Text("${(transparency * 100).roundToInt()}%")
            Slider(transparency, { dragging = true; transparency = it }, valueRange = 0f..0.65f,
                onValueChangeFinished = {
                    pending = true
                    vm.updateMessageBubbleOpacity(1f - transparency, id) { persisted ->
                        transparency = 1f - (persisted ?: savedOpacity)
                        pending = false
                    }
                    dragging = false
                }, enabled = !pending)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 1f - transparency)) {
                Text(language.pick("訊息泡泡", "消息气泡"), Modifier.padding(16.dp))
            }
            Text(language.pick("越高越透明，最低仍保留可讀性。", "越高越透明，最低仍保留可读性。"))
            if (updating) CircularProgressIndicator()
        }
    }
}
