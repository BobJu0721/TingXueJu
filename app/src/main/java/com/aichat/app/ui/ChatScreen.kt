package com.aichat.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
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
import com.aichat.app.ui.theme.Ios
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.roundToInt
@Composable
internal fun ChatScreen(viewModel: ChatViewModel, language: AppLanguage, onBack: () -> Unit) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val contexts by viewModel.generationContexts.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val darkTheme = isSystemInDarkTheme()
    val conversation by viewModel.selectedConversation.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val characterName = conversation?.characterId?.let { id -> characters.find { it.id == id }?.name }
    val selectedId by viewModel.selectedConversationId.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val chatScope = rememberCoroutineScope()
    var lastOpenedId by remember { mutableStateOf<String?>(null) }
    var autoFollow by remember { mutableStateOf(true) }
    var showScrollToBottom by remember { mutableStateOf(false) }
    var fullChatWidth by remember { mutableIntStateOf(0) }
    var fullChatHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val backgroundWidth = with(density) { fullChatWidth.toDp() }
    val backgroundHeight = with(density) { fullChatHeight.toDp() }
    val imeBottom = WindowInsets.ime.getBottom(density)
    var actionMessageId by remember(selectedId) { mutableStateOf<String?>(null) }
    var renameDialogVisible by remember(selectedId) { mutableStateOf(false) }
    var renameText by remember(selectedId, conversation?.title) { mutableStateOf(conversation?.title.orEmpty()) }
    val contextMap = remember(contexts) {
        contexts.associate { context ->
            context.messageId to (jsonStrings(context.activatedWorldEntriesJson) to context.reasoningContent)
        }
    }
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
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && messages.isNotEmpty()) {
            yield()
            listState.scrollToItem(bottomAnchorIndex)
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).onSizeChanged { size ->
        fullChatWidth = maxOf(fullChatWidth, size.width)
        fullChatHeight = maxOf(fullChatHeight, size.height)
    }) {
        if (fullChatWidth > 0 && fullChatHeight > 0) {
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(backgroundWidth, backgroundHeight)) {
                ChatBackground(
                    conversation?.backgroundImagePath.orEmpty(),
                    darkTheme,
                    fullChatWidth,
                    fullChatHeight,
                )
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                Column {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .padding(start = 6.dp)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) { Back(language, onBack) }
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    renameText = conversation?.title.orEmpty()
                                    renameDialogVisible = conversation != null
                                }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                conversation?.title?.ifBlank { null } ?: language.pick("聽雪居", "听雪居"),
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Surface(
                                shape = RoundedCornerShape(99.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                                onClick = viewModel::openModels,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Bolt, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        settings.model,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        Box(
                            Modifier
                                .padding(end = 6.dp)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .clickable(onClick = viewModel::openChatInfo),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Info, language.pick("對話資訊", "对话信息"), modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Hairline()
                }
            },
            bottomBar = { MessageComposer(viewModel, language) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
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
                            worldHits = contextMap[message.id]?.first.orEmpty(),
                            reasoningContent = contextMap[message.id]?.second.orEmpty(),
                            language = language,
                            bubbleOpacity = conversation?.messageBubbleOpacity ?: 1f,
                            characterName = characterName,
                            characterSeed = conversation?.characterId ?: "ai",
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
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, language.pick("回到底部", "回到底部"))
                    }
                }
            }
        }
    }
    if (renameDialogVisible) {
        AlertDialog(
            onDismissRequest = { renameDialogVisible = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surface,
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
private fun MessageComposer(viewModel: ChatViewModel, language: AppLanguage) {
    val input by viewModel.input.collectAsStateWithLifecycle()
    val streaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(Modifier.navigationBarsPadding().imePadding(), color = iosBarColor()) {
        Column {
            Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    input,
                    viewModel::setInput,
                    Modifier.weight(1f),
                    placeholder = { Text(language.pick("輸入訊息", "输入消息")) },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                )
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (streaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        .clickable {
                            if (streaming) viewModel.stopStreaming()
                            else {
                                viewModel.send()
                                focusManager.clearFocus()
                                keyboard?.hide()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (streaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        if (streaming) language.pick("停止", "停止") else language.pick("送出", "发送"),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    worldHits: List<String>,
    reasoningContent: String,
    language: AppLanguage,
    bubbleOpacity: Float,
    characterName: String?,
    characterSeed: String,
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
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        // iMessage-style: outgoing = systemBlue, incoming = gray fill, tail corner at the speaking side
        val bubbleColor = when {
            user && dark -> Ios.BlueDark
            user -> Ios.BlueLight
            dark -> Ios.FillDark
            else -> Color(0xFFE9E9EB)
        }
        val bubbleShape = if (user) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
                          else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
        val bubbleContent: @Composable () -> Unit = {
            Column(Modifier.padding(14.dp)) {
                if (reasoning.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (user) Color.White.copy(alpha = 0.14f) else if (dark) Color(0xFF3A3A3C) else Color(0xFFE3E3E8),
                        border = if (user) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { reasoningExpanded = !reasoningExpanded }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Psychology, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                                Text("思考過程", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = language.pick("複製思考內容", "复制思考内容"),
                                    modifier = Modifier.size(15.dp).clickable { clipboard.setText(AnnotatedString(reasoning)) },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    if (reasoningExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (reasoningExpanded) {
                                SelectionContainer {
                                    Text(
                                        reasoning,
                                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (message.content.isBlank()) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else SelectionContainer { MarkdownText(message.content) }
                if (worldHits.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        language.pick("世界設定命中 ${worldHits.size} 條", "世界设定命中 ${worldHits.size} 条"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (user) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { worldInfoExpanded = !worldInfoExpanded },
                    )
                    if (worldInfoExpanded) Text(worldHits.joinToString("\n") { "• $it" }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (user) {
            Surface(
                Modifier
                    .fillMaxWidth(0.86f)
                    .clip(bubbleShape)
                    .clickable(enabled = canShowActions, onClick = onToggleActions),
                shape = bubbleShape,
                color = bubbleColor.copy(alpha = bubbleOpacity.coerceIn(0.35f, 1f)),
                contentColor = Color.White,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) { bubbleContent() }
        } else {
            Row(Modifier.fillMaxWidth()) {
                AvatarCircle(characterName ?: "AI", characterSeed, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        characterName ?: "AI",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        Modifier
                            .fillMaxWidth()
                            .clip(bubbleShape)
                            .clickable(enabled = canShowActions, onClick = onToggleActions),
                        shape = bubbleShape,
                        color = bubbleColor.copy(alpha = bubbleOpacity.coerceIn(0.35f, 1f)),
                        contentColor = if (dark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onBackground,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) { bubbleContent() }
                }
            }
        }
        if (canShowActions && actionsVisible) {
            Row(
                modifier = (if (user) Modifier.fillMaxWidth(.86f) else Modifier.padding(start = 50.dp)).padding(top = 4.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }) { Icon(Icons.Default.ContentCopy, language.pick("複製", "复制"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, language.pick("編輯", "编辑"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = { onResend(message.id) }) { Icon(Icons.Default.Refresh, language.pick("重新發送", "重新发送"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surface,
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
