package com.aichat.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.relocation.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.*
import com.aichat.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.roundToInt
@Composable
internal fun ConversationsScreen(viewModel: ChatViewModel, newChatViewModel: NewChatViewModel, onRootSelected: (Screen) -> Unit, language: AppLanguage, showBottomBar: Boolean = true) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { CompactTopBar(language.pick("聽雪居", "听雪居")) },
        bottomBar = { if (showBottomBar) RootBottomBar(Screen.CONVERSATIONS, language, onRootSelected) },
        floatingActionButton = { FloatingActionButton(onClick = { newChatViewModel.beginNewChat() }) { Icon(Icons.Default.Add, language.pick("新增對話", "新增对话")) } },
    ) { padding ->
        if (conversations.isEmpty()) EmptyState(language.pick("還沒有對話", "还没有对话"), language.pick("按右下角新增一般對話，或從角色頁開始劇情。", "按右下角新增一般对话，或从角色页开始剧情。"), Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations, key = { it.id }) { conversation ->
                Card(Modifier.fillMaxWidth().clickable { viewModel.selectConversation(conversation.id) }) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(conversation.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { viewModel.deleteConversation(conversation) }) { Icon(Icons.Default.Delete, language.pick("刪除對話", "删除对话")) }
                    }
                }
            }
        }
    }
}
