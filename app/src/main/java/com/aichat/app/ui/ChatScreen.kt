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
    val routeSnapshot by viewModel.routeSnapshot.collectAsStateWithLifecycle()
    // 版本與生成資料取自同一份路線快照，不會和訊息清單分屬不同路線。
    val versions = routeSnapshot.versions
    val contexts = routeSnapshot.contexts
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val pendingMutation by viewModel.pendingMutation.collectAsStateWithLifecycle()
    val activeBranch by viewModel.activeBranch.collectAsStateWithLifecycle()
    val scrollTarget by viewModel.scrollToMessageId.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeAssistantMessageId by viewModel.activeAssistantMessageId.collectAsStateWithLifecycle()
    val streaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val summarizing by viewModel.isSummarizingConversation.collectAsStateWithLifecycle()
    val draftReply by viewModel.draftReply.collectAsStateWithLifecycle()
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
    // 創作入口：旁白、指定角色台詞、私人註記都有獨立編輯視窗，不覆蓋聊天輸入草稿。
    var narrationDraft by remember(selectedId) { mutableStateOf<String?>(null) }
    var noteDraft by remember(selectedId) { mutableStateOf<String?>(null) }
    var authoredDraft by remember(selectedId) { mutableStateOf<AuthoredDraft?>(null) }
    var authoredEditId by remember(selectedId) { mutableStateOf<String?>(null) }
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
    // 切換版本或編輯後定位在被切換的訊息，不要跳到很遠的尾端。
    LaunchedEffect(scrollTarget, messages) {
        val id = scrollTarget ?: return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) {
            autoFollow = false
            listState.animateScrollToItem(index)
        }
        viewModel.consumeScrollToMessage()
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
            bottomBar = {
                MessageComposer(viewModel, language) { kind ->
                    when (kind) {
                        MessageKind.NARRATION -> narrationDraft = ""
                        MessageKind.PRIVATE_NOTE -> noteDraft = ""
                        else -> authoredDraft = AuthoredDraft(messageId = null, name = "", content = "")
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (activeBranch?.legacyIncomplete == true) {
                    Text(
                        language.pick(
                            "舊版未保存後續紀錄：這是升級前建立的版本，只到這一則為止。",
                            "旧版未保存后续记录：这是升级前建立的版本，只到这一则为止。",
                        ),
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (messages.isEmpty()) EmptyState(language.pick("開始聊天", "开始聊天"), language.pick("輸入訊息，或從角色頁建立帶有開場白的對話。", "输入消息，或从角色页建立带有开场白的对话。"))
                else LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        top = if (activeBranch?.legacyIncomplete == true) 46.dp else 10.dp,
                        end = 10.dp,
                        bottom = 26.dp,
                    ),
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
                            canContinue = message.isAiReply && !message.excluded && message.content.isNotBlank() && message.id == messages.lastOrNull { it.isModelVisible }?.id,
                            highlighted = searchActive && message.id == highlightedMessageId,
                            historyActionsEnabled = !streaming && !summarizing && draftReply?.generating != true,
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
                            onAiReplyFrom = {
                                actionMessageId = null
                                viewModel.requestAiReplyFrom(it)
                            },
                            onEditAuthored = { authoredEditId = it },
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
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingMutation,
            shape = RoundedCornerShape(22.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    language.pick("刪除這則訊息？", "删除这条消息？"),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    language.pick(
                        "將刪除這則訊息在所有路線中的全部版本及生成資料。其他訊息會保留。",
                        "将删除这条消息在所有路线中的全部版本及生成数据。其他消息会保留。",
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmPendingMutation,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(language.pick("刪除", "删除")) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissPendingMutation) { Text(language.pick("取消", "取消")) } },
        )
    }
    LaunchedEffect(authoredEditId, messages) {
        val id = authoredEditId ?: return@LaunchedEffect
        authoredEditId = null
        val message = messages.firstOrNull { it.id == id } ?: return@LaunchedEffect
        authoredDraft = AuthoredDraft(id, message.authorName.orEmpty(), message.content)
    }
    narrationDraft?.let { draft ->
        AuthoredEditorDialog(
            title = language.pick("新增旁白", "新增旁白"),
            hint = language.pick("以旁白補寫故事事件，例如：三日後，眾人抵達山門。", "以旁白补写故事事件，例如：三日后，众人抵达山门。"),
            confirmLabel = language.pick("加入紀錄", "加入记录"),
            language = language,
            initialContent = draft,
            onDismiss = { narrationDraft = null },
            onConfirm = { _, text -> narrationDraft = null; viewModel.appendAuthoredMessage(MessageKind.NARRATION, text) },
        )
    }
    noteDraft?.let { draft ->
        AuthoredEditorDialog(
            title = language.pick("新增私人註記", "新增私人注记"),
            hint = language.pick("只有你自己看得到，不會提供給 AI，例如：後面可以考慮讓掌櫃與使者是舊識。", "只有你自己看得到，不会提供给 AI，例如：后面可以考虑让掌柜与使者是旧识。"),
            confirmLabel = language.pick("加入紀錄", "加入记录"),
            language = language,
            initialContent = draft,
            onDismiss = { noteDraft = null },
            onConfirm = { _, text -> noteDraft = null; viewModel.appendAuthoredMessage(MessageKind.PRIVATE_NOTE, text) },
        )
    }
    authoredDraft?.let { draft ->
        AuthoredEditorDialog(
            title = if (draft.messageId == null) language.pick("新增角色台詞", "新增角色台词") else language.pick("編輯角色台詞", "编辑角色台词"),
            hint = language.pick("替某個角色補寫台詞或動作，例如：樓上的房間，今晚不能進去。", "替某个角色补写台词或动作，例如：楼上的房间，今晚不能进去。"),
            confirmLabel = if (draft.messageId == null) language.pick("加入紀錄", "加入记录") else language.pick("儲存", "保存"),
            language = language,
            initialContent = draft.content,
            initialName = draft.name,
            characters = characters,
            onDismiss = { authoredDraft = null },
            onConfirm = { name, text ->
                val editing = draft.messageId
                authoredDraft = null
                if (editing == null) {
                    viewModel.appendAuthoredMessage(
                        MessageKind.AUTHORED_CHARACTER,
                        text,
                        authorName = name,
                        authorCharacterId = characters.firstOrNull { it.name == name }?.id,
                    )
                } else {
                    viewModel.requestEditAuthoredMessage(editing, text, name)
                }
            },
        )
    }
    draftReply?.let { state ->
        DraftReplyDialog(state, language, viewModel::updateDraftInstruction, viewModel::generateDraftReply,
            viewModel::stopDraftReply, viewModel::closeDraftReply, viewModel::acceptDraftReply,
            viewModel::dismissDraftReplacement)
    }
}

/** 手寫訊息的編輯視窗狀態；`messageId == null` 表示新增。 */
private data class AuthoredDraft(val messageId: String?, val name: String, val content: String)

/**
 * 旁白、指定角色台詞與私人註記共用的編輯視窗。
 *
 * 有自己的文字狀態，關閉或取消都不會動到聊天輸入框的草稿。
 */
@Composable
private fun AuthoredEditorDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    language: AppLanguage,
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, content: String) -> Unit,
    initialName: String? = null,
    characters: List<ProfileEntity> = emptyList(),
) {
    var content by remember(title) { mutableStateOf(initialContent) }
    var name by remember(title) { mutableStateOf(initialName.orEmpty()) }
    val needsName = initialName != null
    val canConfirm = content.isNotBlank() && (!needsName || name.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (needsName) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(language.pick("發言者", "发言者")) },
                        shape = RoundedCornerShape(14.dp),
                    )
                    if (characters.isNotEmpty()) {
                        Text(
                            language.pick("從角色庫選擇（會保存當下的名字）", "从角色库选择（会保存当下的名字）"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            characters.forEach { character ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (name == character.name) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    },
                                    modifier = Modifier.clickable { name = character.name },
                                ) {
                                    Text(character.name, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                    placeholder = { Text(hint, fontSize = 13.sp) },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                )
                Text(
                    if (needsName) {
                        language.pick("角色庫改名或刪除都不會改寫既有署名。", "角色库改名或删除都不会改写既有署名。")
                    } else {
                        language.pick("只加入紀錄，不會要求 AI 回答。", "只加入记录，不会要求 AI 回答。")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim(), content.trim()) }, enabled = canConfirm, shape = RoundedCornerShape(14.dp)) {
                Text(confirmLabel, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(language.pick("取消", "取消"), color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}


@Composable
private fun MessageComposer(viewModel: ChatViewModel, language: AppLanguage, onCompose: (MessageKind) -> Unit) {
    val input by viewModel.input.collectAsStateWithLifecycle()
    val streaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var creativeMenu by remember { mutableStateOf(false) }
    Surface(Modifier.navigationBarsPadding().imePadding(), color = iosBarColor()) {
        Column {
            Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // 創作入口收在具名選單裡，不把全部功能攤開在輸入列上。
                Box {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .clickable(enabled = !streaming) { creativeMenu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            language.pick("創作", "创作"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    DropdownMenu(creativeMenu, { creativeMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(language.pick("旁白", "旁白")) },
                            onClick = { creativeMenu = false; onCompose(MessageKind.NARRATION) },
                        )
                        DropdownMenuItem(
                            text = { Text(language.pick("指定角色台詞", "指定角色台词")) },
                            onClick = { creativeMenu = false; onCompose(MessageKind.AUTHORED_CHARACTER) },
                        )
                        DropdownMenuItem(
                            text = { Text(language.pick("私人註記", "私人注记")) },
                            onClick = { creativeMenu = false; onCompose(MessageKind.PRIVATE_NOTE) },
                        )
                        DropdownMenuItem(
                            text = { Text(language.pick("幫我擬回覆", "帮我拟回复")) },
                            onClick = { creativeMenu = false; viewModel.openDraftReply() },
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
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
    onAiReplyFrom: (String) -> Unit,
    onEditAuthored: (String) -> Unit,
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
    val kind = message.kind
    val chatMessage = kind == MessageKind.CHAT
    val privateNote = kind == MessageKind.PRIVATE_NOTE
    // 只有一般聊天訊息套用使用者／AI 的對話樣式；手寫內容與私人註記另有卡片。
    val user = chatMessage && message.role == "user"
    // 手寫內容不會有、也不該有假造的思考內容或生成統計。
    val reasoning = if (chatMessage && !user) generationContext?.reasoningContent.orEmpty().trim() else ""
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
                // 作者手寫的內容與私人註記都標明身份，不與使用者台詞、AI 回覆混淆。
                if (!chatMessage) {
                    Text(
                        when (kind) {
                            MessageKind.NARRATION -> language.pick("旁白", "旁白")
                            MessageKind.AUTHORED_CHARACTER -> language.pick(
                                "${message.authorName.orEmpty()}・手寫",
                                "${message.authorName.orEmpty()}・手写",
                            )
                            else -> language.pick("私人註記・不提供給 AI", "私人注记・不提供给 AI")
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // 使用者明確刪除後留下的結構標記：沒有正文，只是讓其他路線還找得到分岔入口。
                if (message.deleted) {
                    Text(
                        language.pick("訊息已刪除", "消息已删除"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (user) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.error,
                    )
                }
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
        if (!chatMessage) {
            // 旁白、手寫角色台詞與私人註記：中性卡片，不是對話泡泡。
            Surface(
                Modifier
                    .fillMaxWidth()
                    .then(if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier)
                    .clickable(enabled = canShowActions, onClick = onToggleActions),
                shape = RoundedCornerShape(14.dp),
                color = when {
                    privateNote && dark -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    privateNote -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    dark -> Color(0xFF2C2C2E)
                    else -> Color(0xFFEFEFF3)
                },
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = if (privateNote) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
            ) { bubbleContent() }
        } else if (user) {
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
                        onClick = { if (kind == MessageKind.AUTHORED_CHARACTER) onEditAuthored(message.id) else editing = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Edit, language.pick("編輯", "编辑"), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    // 私人註記沒有「重發／讓 AI 接話／續寫」，不能因誤切開關變成模型輸入。
                    if (!privateNote) {
                        IconButton(
                            enabled = historyActionsEnabled && !message.excluded && message.content.isNotBlank(),
                            onClick = {
                                when {
                                    !chatMessage -> onAiReplyFrom(message.id)
                                    user -> onAnswerFrom(message.id)
                                    else -> onGenerateAlternative(message.id)
                                }
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                when {
                                    !chatMessage -> language.pick("讓 AI 接話", "让 AI 接话")
                                    user -> language.pick("重新發送", "重新发送")
                                    else -> language.pick("生成另一版", "生成另一版")
                                },
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
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
                            if (message.isAiReply && canContinue) {
                                DropdownMenuItem(
                                    text = { Text(language.pick("續寫", "续写")) },
                                    onClick = { moreExpanded = false; onContinue(message.id) },
                                    enabled = !message.excluded,
                                    leadingIcon = { Icon(Icons.Default.Add, null) },
                                )
                            }
                            // 私人註記永遠不提供給 AI，因此沒有這個開關。
                            if (!privateNote) {
                                DropdownMenuItem(
                                    text = { Text(if (message.excluded) language.pick("恢復提供給 AI", "恢复提供给 AI") else language.pick("不提供給 AI", "不提供给 AI")) },
                                    onClick = { moreExpanded = false; onToggleExcluded(message.id) },
                                    leadingIcon = { Icon(if (message.excluded) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) },
                                )
                            }
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
                if (message.isAiReply) {
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
