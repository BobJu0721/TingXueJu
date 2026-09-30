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

private const val CHAT_SEARCH_ENABLED = false

@Composable
internal fun ChatScreen(viewModel: ChatViewModel, language: AppLanguage, onBack: () -> Unit) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val contexts by viewModel.generationContexts.collectAsStateWithLifecycle()
    val versions by viewModel.messageVersions.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val pendingMutation by viewModel.pendingMutation.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeAssistantMessageId by viewModel.activeAssistantMessageId.collectAsStateWithLifecycle()
    val streaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val summarizing by viewModel.isSummarizingConversation.collectAsStateWithLifecycle()
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
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
    var searchExpanded by remember(selectedId) { mutableStateOf(false) }
    var searchResultsMenu by remember(selectedId) { mutableStateOf(false) }
    var highlightedMessageId by remember(selectedId) { mutableStateOf<String?>(null) }
    val searchActive = CHAT_SEARCH_ENABLED && searchExpanded
    val contextMap = remember(contexts) {
        contexts.associateBy { it.versionId }
    }
    val versionMap = remember(versions) { versions.groupBy { it.messageId } }
    val bottomAnchorIndex = messages.size
    LaunchedEffect(selectedId, CHAT_SEARCH_ENABLED) {
        searchExpanded = false
        searchResultsMenu = false
        highlightedMessageId = null
        viewModel.closeSearch()
    }
    LaunchedEffect(listState, messages.size) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.collect { (_, _, isScrolling) ->
            if (isScrolling && !searchActive) autoFollow = listState.isNearBottom(bottomAnchorIndex)
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
        if (messages.isNotEmpty() && autoFollow && !searchActive) listState.scrollToItem(bottomAnchorIndex)
    }
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && messages.isNotEmpty() && !searchActive) {
            yield()
            listState.scrollToItem(bottomAnchorIndex)
        }
    }
    LaunchedEffect(searchState.revision, searchActive) {
        if (!searchActive) return@LaunchedEffect
        val id = searchState.currentHit?.messageId ?: return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) {
            autoFollow = false
            listState.animateScrollToItem(index)
            highlightedMessageId = id
            delay(1_200)
            if (highlightedMessageId == id) highlightedMessageId = null
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
                        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 68.dp).padding(horizontal = 6.dp, vertical = 6.dp),
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
                                .padding(horizontal = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    renameText = conversation?.title.orEmpty()
                                    renameDialogVisible = conversation != null
                                }
                                .padding(vertical = 2.dp),
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
                            Spacer(Modifier.height(2.dp))
                            Surface(
                                modifier = Modifier.widthIn(max = 240.dp),
                                shape = RoundedCornerShape(99.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                                onClick = viewModel::openModels,
                            ) {
                                Text(
                                    "⚡ " + settings.model,
                                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (CHAT_SEARCH_ENABLED) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        searchExpanded = true
                                        autoFollow = false
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Search, language.pick("搜尋聊天室", "搜索聊天室"), modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
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
                    AnimatedVisibility(searchActive) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = searchState.query,
                                onValueChange = viewModel::setSearchQuery,
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(language.pick("搜尋目前聊天室", "搜索当前聊天室")) },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                singleLine = true,
                                shape = RoundedCornerShape(18.dp),
                            )
                            Box {
                                TextButton(
                                    onClick = { searchResultsMenu = searchState.hits.isNotEmpty() },
                                    enabled = searchState.hits.isNotEmpty(),
                                ) {
                                    Text(if (searchState.hits.isEmpty()) "0 / 0" else "${searchState.currentIndex + 1} / ${searchState.hits.size}")
                                }
                                DropdownMenu(searchActive && searchResultsMenu, { searchResultsMenu = false }) {
                                    LazyColumn(
                                        Modifier.width(300.dp).height(minOf(searchState.hits.size * 72, 360).dp),
                                    ) {
                                        itemsIndexed(searchState.hits, key = { _, hit -> hit.messageId }) { index, hit ->
                                            DropdownMenuItem(
                                                text = { Text(hit.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                                onClick = {
                                                    viewModel.selectSearchResult(index)
                                                    searchResultsMenu = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            IconButton(onClick = viewModel::previousSearchResult, enabled = searchState.hits.isNotEmpty()) {
                                Icon(Icons.Default.KeyboardArrowUp, language.pick("上一筆", "上一条"))
                            }
                            IconButton(onClick = viewModel::nextSearchResult, enabled = searchState.hits.isNotEmpty()) {
                                Icon(Icons.Default.KeyboardArrowDown, language.pick("下一筆", "下一条"))
                            }
                            IconButton(onClick = {
                                searchExpanded = false
                                searchResultsMenu = false
                                viewModel.closeSearch()
                            }) { Icon(Icons.Default.Close, language.pick("關閉搜尋", "关闭搜索")) }
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
                            versions = versionMap[message.id].orEmpty(),
                            generationContext = contextMap[message.currentVersionId],
                            language = language,
                            bubbleOpacity = conversation?.messageBubbleOpacity ?: 1f,
                            characterName = characterName,
                            characterSeed = conversation?.characterId ?: "ai",
                            isGenerating = message.id == activeAssistantMessageId,
                            canContinue = message.role == "assistant" && !message.excluded && message.content.isNotBlank() && message.id == messages.lastOrNull()?.id,
                            highlighted = searchActive && message.id == highlightedMessageId,
                            historyActionsEnabled = !streaming && !summarizing,
                            actionsVisible = actionMessageId == message.id,
                            onToggleActions = {
                                actionMessageId = if (actionMessageId == message.id) null else message.id
                            },
                            onEdit = viewModel::requestEditMessage,
                            onSelectVersion = viewModel::requestSelectVersion,
                            onGenerateAlternative = {
                                actionMessageId = null
                                viewModel.requestGenerateAlternative(it)
                            },
                            onAnswerFrom = viewModel::requestAnswerFrom,
                            onContinue = viewModel::requestContinue,
                            onDelete = viewModel::requestDeleteMessage,
                            onToggleExcluded = viewModel::toggleMessageExcluded,
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
            shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    language.pick("重新命名對話", "重新命名对话"),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                OutlinedTextField(
                    renameText,
                    { renameText = it },
                    Modifier.fillMaxWidth(),
                    placeholder = { Text(language.pick("對話名稱", "对话名称"), fontSize = 14.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameConversation(renameText)
                        renameDialogVisible = false
                    },
                    enabled = renameText.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(language.pick("儲存", "保存"), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogVisible = false }) { Text(language.pick("取消", "取消"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
        )
    }
    pendingMutation?.let { pending ->
        val deleting = pending.kind == MessageMutationKind.DELETE
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingMutation,
            shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    if (deleting) language.pick("刪除這則訊息？", "删除这条消息？")
                    else language.pick("刪除後續訊息？", "删除后续消息？"),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    if (deleting) language.pick(
                        "將刪除這則訊息及其所有版本與生成資料，其他訊息會保留。",
                        "将删除这条消息及其所有版本与生成数据，其他消息会保留。",
                    ) else language.pick(
                        "此操作將刪除這則訊息之後的 ${pending.followingCount} 則訊息，包含其其他版本。是否繼續？",
                        "此操作将删除这条消息之后的 ${pending.followingCount} 条消息，包含其其他版本。是否继续？",
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmPendingMutation,
                    colors = if (deleting) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                ) { Text(if (deleting) language.pick("刪除", "删除") else language.pick("繼續", "继续")) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissPendingMutation) { Text(language.pick("取消", "取消")) } },
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
internal fun MessageBubble(
    message: MessageEntity,
    versions: List<MessageVersionEntity>,
    generationContext: GenerationContextEntity?,
    language: AppLanguage,
    bubbleOpacity: Float,
    characterName: String?,
    characterSeed: String,
    isGenerating: Boolean,
    canContinue: Boolean,
    highlighted: Boolean,
    historyActionsEnabled: Boolean,
    actionsVisible: Boolean,
    onToggleActions: () -> Unit,
    onEdit: (String, String) -> Unit,
    onSelectVersion: (String, String) -> Unit,
    onGenerateAlternative: (String) -> Unit,
    onAnswerFrom: (String) -> Unit,
    onContinue: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleExcluded: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var reasoningExpanded by remember(message.id) { mutableStateOf(false) }
    var worldInfoExpanded by remember(message.id) { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editText by remember(message.id, message.content) { mutableStateOf(message.content) }
    var moreExpanded by remember(message.conversationId, message.id) { mutableStateOf(false) }
    LaunchedEffect(actionsVisible) {
        if (!actionsVisible) moreExpanded = false
    }
    val user = message.role == "user"
    val reasoning = if (user) "" else generationContext?.reasoningContent.orEmpty().trim()
    val worldHits = remember(generationContext) { jsonStrings(generationContext?.activatedWorldEntriesJson.orEmpty()) }
    val currentVersionIndex = versions.indexOfFirst { it.id == message.currentVersionId }.coerceAtLeast(0)
    val currentVersion = versions.getOrNull(currentVersionIndex)
    val canShowActions = message.content.isNotBlank() || reasoning.isNotBlank()
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
                if (message.excluded) {
                    Text(
                        language.pick("已排除", "已排除"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (user) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (reasoning.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (user) Color.White.copy(alpha = 0.14f) else if (dark) Color(0xFF3A3A3C) else Color(0xFFE3E3E8),
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
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = canShowActions, onClick = onToggleActions)
                                            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
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
                if (message.content.isNotBlank()) {
                    SelectionContainer { MarkdownText(message.content) }
                } else if (!user && isGenerating) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
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
                    .then(if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, bubbleShape) else Modifier)
                    // 不要在點擊區外重複套用非等半徑圓角裁切：Compose 會把這種形狀轉成路徑，
                    // 泡泡高於約 8K 像素時，路徑命中測試會錯誤拒絕正文內的觸點，導致點擊失效。
                    // 圓角與尾角由底下 Surface(shape = bubbleShape) 自己裁切，外觀不變。
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
                            .then(if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, bubbleShape) else Modifier)
                            // 同上：外層裁切會擋掉超高泡泡的命中測試，圓角交給 Surface 自己處理。
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
            Column(
                modifier = (if (user) Modifier.fillMaxWidth(.86f) else Modifier.fillMaxWidth().padding(start = 50.dp))
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(message.content)) },
                        enabled = message.content.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.ContentCopy, language.pick("複製", "复制"), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(
                        enabled = historyActionsEnabled && message.content.isNotBlank(),
                        onClick = { editing = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Edit, language.pick("編輯", "编辑"), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(
                        enabled = historyActionsEnabled && !message.excluded && message.content.isNotBlank(),
                        onClick = {
                            if (user) onAnswerFrom(message.id) else onGenerateAlternative(message.id)
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Refresh,
                            if (user) language.pick("重新發送", "重新发送") else language.pick("生成另一版", "生成另一版"),
                            Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Box {
                        IconButton(
                            enabled = historyActionsEnabled,
                            onClick = { moreExpanded = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Default.MoreHoriz, language.pick("更多功能", "更多功能"), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(
                            expanded = moreExpanded && actionsVisible && historyActionsEnabled,
                            onDismissRequest = { moreExpanded = false },
                        ) {
                            if (!user && canContinue) {
                                DropdownMenuItem(
                                    text = { Text(language.pick("續寫", "续写")) },
                                    onClick = { moreExpanded = false; onContinue(message.id) },
                                    enabled = !message.excluded,
                                    leadingIcon = { Icon(Icons.Default.Add, null) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (message.excluded) language.pick("恢復提供給 AI", "恢复提供给 AI") else language.pick("不提供給 AI", "不提供给 AI")) },
                                onClick = { moreExpanded = false; onToggleExcluded(message.id) },
                                leadingIcon = { Icon(if (message.excluded) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(language.pick("刪除訊息", "删除消息"), color = MaterialTheme.colorScheme.error) },
                                onClick = { moreExpanded = false; onDelete(message.id) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            )
                        }
                    }
                }
                if (versions.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            enabled = historyActionsEnabled && currentVersionIndex > 0,
                            onClick = { versions.getOrNull(currentVersionIndex - 1)?.let { onSelectVersion(message.id, it.id) } },
                        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, language.pick("上一版本", "上一版本")) }
                        Text("${currentVersionIndex + 1} / ${versions.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(
                            enabled = historyActionsEnabled && currentVersionIndex < versions.lastIndex,
                            onClick = { versions.getOrNull(currentVersionIndex + 1)?.let { onSelectVersion(message.id, it.id) } },
                        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, language.pick("下一版本", "下一版本")) }
                    }
                }
                if (!user) {
                    versionBadgeLabel(currentVersion, language)?.let {
                        Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    val metrics = remember(message.content, generationContext, language) {
                        messageMetricsLabels(message.content, generationContext, false, language)
                    }
                    Text(
                        metrics.singleLine,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    language.pick("編輯訊息", "编辑消息"),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 12,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    )
                    Text(
                        if (user) language.pick("儲存後可按 ⟳ 重新發送，不會自動要求 AI 回答。", "保存后可按 ⟳ 重新发送，不会自动要求 AI 回答。")
                        else language.pick("儲存為新版本，不會自動要求 AI 回答。", "保存为新版本，不会自动要求 AI 回答。"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEdit(message.id, editText)
                        editing = false
                    },
                    shape = RoundedCornerShape(14.dp),
                ) { Text(language.pick("儲存", "保存"), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(language.pick("取消", "取消"), color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
    }
}

private fun versionBadgeLabel(version: MessageVersionEntity?, language: AppLanguage): String? = when {
    version?.status == MessageVersionStatus.PARTIAL -> language.pick("部分完成", "部分完成")
    version?.status == MessageVersionStatus.INTERRUPTED -> language.pick("生成中斷", "生成中断")
    version?.source == MessageVersionSource.CONTINUATION -> language.pick("本次續寫", "本次续写")
    version?.source == MessageVersionSource.AI_EDIT -> language.pick("人工編輯", "人工编辑")
    else -> null
}
