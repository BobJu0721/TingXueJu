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
internal fun ChatScreen(viewModel: ChatViewModel, language: AppLanguage) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val contexts by viewModel.generationContexts.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val streaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val conversation by viewModel.selectedConversation.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedConversationId.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val chatScope = rememberCoroutineScope()
    var lastOpenedId by remember { mutableStateOf<String?>(null) }
    var autoFollow by remember { mutableStateOf(true) }
    var showScrollToBottom by remember { mutableStateOf(false) }
    var actionMessageId by remember(selectedId) { mutableStateOf<String?>(null) }
    var renameDialogVisible by remember(selectedId) { mutableStateOf(false) }
    var renameText by remember(selectedId, conversation?.title) { mutableStateOf(conversation?.title.orEmpty()) }
    val contextMap = remember(contexts) { contexts.associateBy { it.messageId } }
    val bottomAnchorIndex = messages.size
    LaunchedEffect(listState, messages.size) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.collect { (_, _, isScrolling) ->
            if (isScrolling) autoFollow = listState.isNearBottom(bottomAnchorIndex)
        }
    }
    LaunchedEffect(listState, messages.size) {
        snapshotFlow { messages.isNotEmpty() && !listState.isNearBottom(bottomAnchorIndex) }
            .distinctUntilChanged()
            .collect { showScrollToBottom = it }
    }
    LaunchedEffect(selectedId, messages.size) {
        if (selectedId != null && selectedId != lastOpenedId && messages.isNotEmpty()) {
            listState.scrollToItem(bottomAnchorIndex)
            autoFollow = true
            lastOpenedId = selectedId
        }
    }
    LaunchedEffect(messages.lastOrNull()?.id, messages.lastOrNull()?.content, autoFollow) {
        if (messages.isNotEmpty() && autoFollow) listState.scrollToItem(bottomAnchorIndex)
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            CompactTopBar(
                title = conversation?.title?.ifBlank { null } ?: language.pick("聽雪居", "听雪居"),
                subtitle = settings.model,
                navigationIcon = { Back(language, viewModel::openConversations) },
                onTitleClick = {
                    renameText = conversation?.title.orEmpty()
                    renameDialogVisible = conversation != null
                },
                actions = {
                    IconButton(onClick = viewModel::openChatInfo) { Icon(Icons.Default.Info, language.pick("對話資訊", "对话信息")) }
                    IconButton(onClick = viewModel::openModels) { Icon(Icons.Default.Tune, language.pick("選擇模型", "选择模型")) }
                },
            )
        },
        bottomBar = { MessageComposer(input, streaming, language, viewModel::setInput, viewModel::send, viewModel::stopStreaming) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ChatBackground(conversation?.backgroundImagePath.orEmpty(), settings.darkTheme)
            if (messages.isEmpty()) EmptyState(language.pick("開始聊天", "开始聊天"), language.pick("輸入訊息，或從角色頁建立帶有開場白的對話。", "输入消息，或从角色页建立带有开场白的对话。"))
            else LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        worldHits = contextMap[message.id]?.let { jsonStrings(it.activatedWorldEntriesJson) }.orEmpty(),
                        reasoningContent = contextMap[message.id]?.reasoningContent.orEmpty(),
                        language = language,
                        bubbleOpacity = conversation?.messageBubbleOpacity ?: 1f,
                        actionsVisible = actionMessageId == message.id,
                        onToggleActions = {
                            actionMessageId = if (actionMessageId == message.id) null else message.id
                        },
                        onEdit = viewModel::editMessage,
                        onResend = {
                            actionMessageId = null
                            viewModel.resendFromMessage(it)
                        },
                    )
                }
                item(key = "chat-bottom-anchor") {
                    Spacer(Modifier.height(1.dp))
                }
            }
            AnimatedVisibility(
                visible = showScrollToBottom,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        if (messages.isNotEmpty()) {
                            actionMessageId = null
                            autoFollow = true
                            chatScope.launch { listState.animateScrollToItem(bottomAnchorIndex) }
                        }
                    },
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, language.pick("回到底部", "回到底部"))
                }
            }
        }
    }
    if (renameDialogVisible) {
        AlertDialog(
            onDismissRequest = { renameDialogVisible = false },
            title = { Text(language.pick("重新命名對話", "重新命名对话")) },
            text = {
                OutlinedTextField(
                    renameText,
                    { renameText = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(language.pick("對話名稱", "对话名称")) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameConversation(renameText)
                        renameDialogVisible = false
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text(language.pick("儲存", "保存")) }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogVisible = false }) { Text(language.pick("取消", "取消")) }
            },
        )
    }
}


@Composable
private fun MessageComposer(input: String, streaming: Boolean, language: AppLanguage, onInput: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(Modifier.imePadding(), shadowElevation = 3.dp) { Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(input, onInput, Modifier.weight(1f), placeholder = { Text(language.pick("輸入訊息", "输入消息")) }, maxLines = 5)
        IconButton(onClick = {
            if (streaming) onStop()
            else {
                onSend()
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }) { Icon(if (streaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (streaming) language.pick("停止", "停止") else language.pick("送出", "发送")) }
    } }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    worldHits: List<String>,
    reasoningContent: String,
    language: AppLanguage,
    bubbleOpacity: Float,
    actionsVisible: Boolean,
    onToggleActions: () -> Unit,
    onEdit: (String, String) -> Unit,
    onResend: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var reasoningExpanded by remember(message.id) { mutableStateOf(false) }
    var worldInfoExpanded by remember(message.id) { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editText by remember(message.id, message.content) { mutableStateOf(message.content) }
    val user = message.role == "user"
    val reasoning = if (user) "" else reasoningContent.trim()
    val canShowActions = message.content.isNotBlank()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(actionsVisible, canShowActions) {
        if (actionsVisible && canShowActions) {
            yield()
            bringIntoViewRequester.bringIntoView()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
    ) {
        val bubbleColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
        Card(
            Modifier
                .fillMaxWidth(if (user) .86f else .96f)
                .clickable(enabled = canShowActions, onClick = onToggleActions),
            colors = CardDefaults.cardColors(containerColor = bubbleColor.copy(alpha = bubbleOpacity.coerceIn(0.35f, 1f))),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (reasoning.isNotBlank()) {
                    TextButton(onClick = { reasoningExpanded = !reasoningExpanded }) {
                        Icon(
                            if (reasoningExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("思考內容")
                    }
                    if (reasoningExpanded) {
                        SelectionContainer {
                            Text(reasoning, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(reasoning)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("複製思考內容")
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                if (message.content.isBlank()) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else SelectionContainer { MarkdownText(message.content) }
                if (worldHits.isNotEmpty()) {
                    TextButton(onClick = { worldInfoExpanded = !worldInfoExpanded }) { Text(language.pick("世界設定命中 ${worldHits.size} 條", "世界设定命中 ${worldHits.size} 条")) }
                    if (worldInfoExpanded) Text(worldHits.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (canShowActions && actionsVisible) {
            Row(Modifier.padding(top = 2.dp)) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }) { Icon(Icons.Default.ContentCopy, language.pick("複製", "复制"), Modifier.size(18.dp)) }
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, language.pick("編輯", "编辑"), Modifier.size(18.dp)) }
                IconButton(onClick = { onResend(message.id) }) { Icon(Icons.Default.Refresh, language.pick("重新發送", "重新发送"), Modifier.size(18.dp)) }
            }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(if (user) language.pick("編輯自己的訊息", "编辑自己的消息") else language.pick("編輯 AI 訊息", "编辑 AI 消息")) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 12,
                    label = { Text(language.pick("訊息內容", "消息内容")) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEdit(message.id, editText)
                    editing = false
                }) { Text(language.pick("儲存", "保存")) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(language.pick("取消", "取消")) } },
        )
    }
}
