package com.aichat.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.app.data.*
import com.aichat.app.domain.ChatGenerationKind
import com.aichat.app.domain.ChatGenerationRequest
import com.aichat.app.domain.EffectiveHistoryResolver
import com.aichat.app.network.ApiException
import com.aichat.app.network.validateReasoningMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.IOException
import kotlin.math.max

data class ChatSearchHit(val messageId: String, val snippet: String)

data class ChatSearchState(
    val query: String = "",
    val hits: List<ChatSearchHit> = emptyList(),
    val currentIndex: Int = -1,
    val revision: Long = 0,
) {
    val currentHit: ChatSearchHit? get() = hits.getOrNull(currentIndex)
}

/**
 * AI 代擬的畫面狀態。`null` 表示預覽視窗沒開。
 *
 * 草稿只存在這裡，不會變成聊天訊息，也不影響分支、摘要或世界設定。
 */
data class DraftReplyState(
    val conversationId: String = "",
    val instruction: String = "",
    val existingInput: String = "",
    val content: String = "",
    val generating: Boolean = false,
    /** 停止時已有部分正文，標示尚未完成。 */
    val incomplete: Boolean = false,
    /** 產生當下的歷史修訂號；路線或歷史改變後就不能直接套用。 */
    val revision: Long = 0,
    val branchId: String? = null,
    val replacementInput: String? = null,
    val error: String? = null,
)

enum class MessageMutationKind { SELECT_VERSION, EDIT, ALTERNATIVE, ANSWER_FROM, DELETE }

data class PendingMessageMutation(
    val kind: MessageMutationKind,
    val messageId: String,
    val versionId: String? = null,
    val content: String? = null,
    val expectedRevision: Long? = null,
    /** 手寫角色台詞的署名；改署名也會建立新版本。 */
    val authorName: String? = null,
    val authorCharacterId: String? = null,
)

private sealed interface PendingGenerationStart {
    data object Send : PendingGenerationStart
    data class Existing(val request: ChatGenerationRequest) : PendingGenerationStart
    data object Draft : PendingGenerationStart
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChatViewModel(private val appContainer: AppContainer) : ViewModel() {
    private val conversationRepository = appContainer.conversationRepository
    private val profileRepository = appContainer.profileRepository
    private val worldInfoRepository = appContainer.worldInfoRepository
    private val settingsRepository = appContainer.settingsRepository
    private val secretStore by lazy { appContainer.secretStore }
    private val api by lazy { appContainer.api }
    private val summarizeConversation by lazy { appContainer.summarizeConversationUseCase }
    private val streamConversationUseCase by lazy { appContainer.streamConversationUseCase }

    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val conversations = conversationRepository.observeConversations().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val personas = profileRepository.observeProfiles(ProfileType.PERSONA).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val characters = profileRepository.observeProfiles(ProfileType.CHARACTER).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val worldSets = worldInfoRepository.observeWorldSets().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val worldEntryCounts = worldInfoRepository.observeWorldEntryCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId = _selectedConversationId.asStateFlow()
    /** 目前路線的一致快照：訊息、版本與生成資料來自同一個交易。 */
    val routeSnapshot = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(RouteSnapshot()) else conversationRepository.observeRouteSnapshot(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteSnapshot())
    val messages = routeSnapshot.map { it.messages }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val messageVersions = routeSnapshot.map { it.versions }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val generationContexts = routeSnapshot.map { it.contexts }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeWorldSetIds = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else worldInfoRepository.observeConversationWorldSetIds(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 目前顯示的路線，供介面提示「舊版未保存後續紀錄」。 */
    val activeBranch = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(null) else conversationRepository.observeActiveBranch(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _chatInfoBranch = MutableStateFlow<ConversationBranchEntity?>(null)
    val chatInfoBranch = _chatInfoBranch.asStateFlow()
    private val _isSavingChatInfo = MutableStateFlow(false)
    val isSavingChatInfo = _isSavingChatInfo.asStateFlow()

    private val _scrollToMessageId = MutableStateFlow<String?>(null)
    val scrollToMessageId = _scrollToMessageId.asStateFlow()
    fun consumeScrollToMessage() { _scrollToMessageId.value = null }

    private val _input = MutableStateFlow("")
    val input = _input.asStateFlow()
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming = _isStreaming.asStateFlow()
    private val _activeAssistantMessageId = MutableStateFlow<String?>(null)
    val activeAssistantMessageId = _activeAssistantMessageId.asStateFlow()
    private val _error = MutableStateFlow<UiError?>(null)
    val error = _error.asStateFlow()
    private val _showUnsafeHttpWarning = MutableStateFlow(false)
    val showUnsafeHttpWarning = _showUnsafeHttpWarning.asStateFlow()
    private val _isSummarizingConversation = MutableStateFlow(false)
    val isSummarizingConversation = _isSummarizingConversation.asStateFlow()
    private val _selectedConversation = MutableStateFlow<ConversationEntity?>(null)
    val selectedConversation = _selectedConversation.asStateFlow()
    private val _navigationEvents = MutableSharedFlow<Screen>()
    val navigationEvents = _navigationEvents.asSharedFlow()
    private val _pendingMutation = MutableStateFlow<PendingMessageMutation?>(null)
    val pendingMutation = _pendingMutation.asStateFlow()
    private val _draftReply = MutableStateFlow<DraftReplyState?>(null)
    val draftReply = _draftReply.asStateFlow()
    private var draftJob: Job? = null
    private var draftRunId = 0L
    private val _searchQuery = MutableStateFlow("")
    private val _searchState = MutableStateFlow(ChatSearchState())
    val searchState = _searchState.asStateFlow()

    private var pendingGenerationStart: PendingGenerationStart? = null
    private var streamJob: Job? = null
    private var lastFailedRequest: ChatGenerationRequest? = null

    init {
        viewModelScope.launch { conversationRepository.recoverInterruptedDrafts() }
        viewModelScope.launch {
            combine(_searchQuery.debounce(250), messages) { query, current -> query to current }
                .mapLatest { (query, current) ->
                    withContext(Dispatchers.Default) { findChatMessages(query, current) }
                }
                .collect { (query, hits) ->
                    if (query != _searchQuery.value) return@collect
                    val old = _searchState.value
                    val oldId = old.currentHit?.messageId
                    val nextIndex = hits.indexOfFirst { it.messageId == oldId }.takeIf { it >= 0 }
                        ?: if (hits.isEmpty()) -1 else 0
                    _searchState.value = ChatSearchState(query, hits, nextIndex, old.revision + 1)
                }
        }
    }

    private fun text(traditional: String, simplified: String): String = settings.value.language.pick(traditional, simplified)
    private fun navigate(screen: Screen) { viewModelScope.launch { _navigationEvents.emit(screen) } }
    private suspend fun refreshConversation(id: String? = _selectedConversationId.value) {
        _selectedConversation.value = id?.let { conversationRepository.getActiveConversation(it) }
    }

    fun setInput(value: String) { _input.value = value }

    // ---- AI 幫我擬回覆 ----

    fun openDraftReply() {
        if (historyMutationBlocked()) return
        // 目前的輸入草稿會被帶進視窗顯示，讓使用者確認代擬是參考它寫的。
        val id = _selectedConversationId.value ?: return
        draftRunId++
        _draftReply.value = DraftReplyState(conversationId = id, existingInput = _input.value)
    }

    fun updateDraftInstruction(value: String) {
        if (_draftReply.value?.generating != true) _draftReply.value = _draftReply.value?.copy(instruction = value)
    }

    /** 取消或關閉：丟掉草稿，原輸入框完全不動。 */
    fun closeDraftReply() {
        val wasRunning = draftJob?.isCompleted == false
        draftRunId++
        draftJob?.cancel()
        if (wasRunning) api.cancelActive()
        _draftReply.value = null
        if (pendingGenerationStart == PendingGenerationStart.Draft) dismissUnsafeHttp()
    }

    /** 停止生成：保留已有部分正文並標示尚未完成。 */
    fun stopDraftReply() {
        if (draftJob?.isCompleted != false) return
        draftJob?.cancel()
        api.cancelActive()
        val state = _draftReply.value ?: return
        _draftReply.value = state.copy(generating = false, incomplete = state.content.isNotBlank())
    }

    /** 產生／再擬一版：每次只呼叫一次模型。 */
    fun generateDraftReply() {
        if (historyMutationBlocked()) return
        runWithUnsafeHttpConfirmation(PendingGenerationStart.Draft)
    }

    private fun startDraftReply() {
        val state = _draftReply.value ?: return
        if (historyMutationBlocked() || state.conversationId != _selectedConversationId.value) return
        val conversationId = state.conversationId
        val current = settings.value
        val runId = ++draftRunId
        _draftReply.value = state.copy(content = "", generating = true, incomplete = false, error = null, replacementInput = null)
        draftJob = viewModelScope.launch {
            try {
                val key = secretStore.apiKeyFor(current)
                if (key.isBlank() || current.resolvedBaseUrl.isBlank()) {
                    _draftReply.value = _draftReply.value?.copy(
                        generating = false,
                        error = current.language.pick("請先設定 API Key 與網址。", "请先设置 API Key 与网址。"),
                    )
                    return@launch
                }
                val snapshot = conversationRepository.getGenerationSnapshot(conversationId)
                    ?: throw IOException(text("找不到這段對話。", "找不到这段对话。"))
                currentCoroutineContext().ensureActive()
                if (runId != draftRunId) return@launch
                val revision = snapshot.conversation.historyRevision
                _draftReply.value = _draftReply.value?.copy(revision = revision, branchId = snapshot.branch.id)
                val draftReply = appContainer.draftUserReplyUseCase
                draftReply(
                    conversationId = conversationId,
                    settings = current,
                    key = key,
                    instruction = state.instruction,
                    existingInput = state.existingInput,
                    expectedRevision = revision,
                    sourceBranchId = snapshot.branch.id,
                    onDelta = { token ->
                        currentCoroutineContext().ensureActive()
                        if (runId == draftRunId) _draftReply.value = _draftReply.value?.let { it.copy(content = it.content + token) }
                    },
                )
            } catch (error: CancellationException) {
                if (runId == draftRunId) _draftReply.value = _draftReply.value?.let { it.copy(incomplete = it.content.isNotBlank()) }
                throw error
            } catch (error: Throwable) {
                if (runId == draftRunId) {
                    if (!currentCoroutineContext().isActive) {
                        _draftReply.value = _draftReply.value?.let { it.copy(incomplete = it.content.isNotBlank()) }
                    } else _draftReply.value = _draftReply.value?.copy(
                        error = mapError(error, text("代擬失敗", "代拟失败"), current.language).let { "${it.title}：${it.message}" },
                    )
                }
            } finally {
                if (runId == draftRunId) _draftReply.value = _draftReply.value?.copy(generating = false)
            }
        }
    }

    /**
     * 把草稿填入輸入框。產生之後路線或歷史有改變就不套用，提示重新產生。
     */
    fun dismissDraftReplacement() {
        _draftReply.value = _draftReply.value?.copy(replacementInput = null)
    }

    fun acceptDraftReply(replaceExisting: Boolean = false) {
        val state = _draftReply.value ?: return
        if (state.content.isBlank() || state.error != null || historyMutationBlocked()) return
        val conversationId = _selectedConversationId.value ?: return
        val runId = draftRunId
        viewModelScope.launch {
            val conversation = conversationRepository.getConversation(conversationId)
            if (_draftReply.value != state || runId != draftRunId) return@launch
            if (_selectedConversationId.value != state.conversationId || conversation == null ||
                conversation.id != state.conversationId || conversation.historyRevision != state.revision ||
                conversation.activeBranchId != state.branchId) {
                _draftReply.value = _draftReply.value?.copy(
                    error = text("草稿已過時，對話已經改變，請重新產生。", "草稿已过时，对话已经改变，请重新产生。"),
                )
                return@launch
            }
            if (_input.value.isNotEmpty() && (!replaceExisting || state.replacementInput != _input.value)) {
                _draftReply.value = state.copy(replacementInput = _input.value)
                return@launch
            }
            _input.value = state.content
            draftRunId++
            _draftReply.value = null
        }
    }

    fun clearError() { _error.value = null }
    fun openConversations() { navigate(Screen.CONVERSATIONS) }
    fun openCurrentChat() { navigate(Screen.CHAT) }
    fun openModels() { navigate(Screen.MODELS) }

    fun selectConversation(id: String) {
        if (_isSavingChatInfo.value) return
        if (_selectedConversationId.value != id && _draftReply.value != null) closeDraftReply()
        _selectedConversationId.value = id
        _searchQuery.value = ""
        viewModelScope.launch { refreshConversation(id) }
        navigate(Screen.CHAT)
    }

    fun deleteConversation(conversation: ConversationEntity) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(conversation)
            if (_selectedConversationId.value == conversation.id) _selectedConversationId.value = null
        }
    }

    fun openChatInfo() {
        val id = _selectedConversationId.value ?: return
        viewModelScope.launch {
            refreshConversation(id)
            _chatInfoBranch.value = conversationRepository.getActiveBranch(id)
            if (_selectedConversationId.value == id) _navigationEvents.emit(Screen.CHAT_INFO)
        }
    }

    fun loadChatInfo(conversationId: String) {
        if (_isSavingChatInfo.value) return
        viewModelScope.launch {
            val snapshot = conversationRepository.getGenerationSnapshot(conversationId) ?: return@launch
            if (_selectedConversationId.value == conversationId) {
                _selectedConversation.value = snapshot.conversation
                _chatInfoBranch.value = snapshot.branch
            }
        }
    }

    fun renameConversation(title: String) = updateConversation { conversation ->
        val clean = title.trim()
        if (clean.isBlank() || clean == conversation.title) conversation
        else conversation.copy(title = clean, updatedAt = System.currentTimeMillis())
    }

    fun updateConversationPersona(id: String?) = updateConversation { it.copy(personaId = id) }

    fun updateConversationReasoningMode(mode: ReasoningMode) = updateConversation { it.copy(reasoningMode = mode) }

    fun updateConversationGenerationOptions(
        preference: ReplyLengthPreference,
        maxOutputTokens: Int?,
        field: TokenLimitField,
        onSaved: () -> Unit = {},
    ) {
        require(maxOutputTokens == null || maxOutputTokens > 0)
        val id = _selectedConversationId.value ?: return
        viewModelScope.launch {
            val fresh = conversationRepository.getConversation(id) ?: return@launch
            val updated = fresh.copy(
                replyLengthPreference = preference,
                maxOutputTokens = maxOutputTokens,
                tokenLimitField = field,
            )
            if (updated != fresh) conversationRepository.updateConversation(updated)
            _selectedConversation.value = updated
            onSaved()
        }
    }

    fun saveChatInfo(
        conversationId: String,
        branchId: String,
        note: SceneNote,
        preference: ReplyLengthPreference,
        maxOutputTokens: Int?,
        field: TokenLimitField,
        onSaved: () -> Unit,
    ) {
        if (historyMutationBlocked() || _isSavingChatInfo.value) return
        _isSavingChatInfo.value = true
        viewModelScope.launch {
            try {
                check(_selectedConversationId.value == conversationId &&
                    conversationRepository.saveChatInfo(conversationId, branchId, note, preference, maxOutputTokens, field)) {
                    text("聊天室或分支已變更，未儲存；請保留文字後重新開啟對話資訊。", "聊天室或分支已变更，未保存；请保留文字后重新打开对话信息。")
                }
                refreshConversation(conversationId)
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _error.value = UiError(text("儲存失敗", "保存失败"), error.message.orEmpty(),
                    text("未儲存的文字仍保留在編輯框中。", "未保存的文字仍保留在编辑框中。"))
            } finally {
                _isSavingChatInfo.value = false
            }
        }
    }

    private fun updateConversation(transform: (ConversationEntity) -> ConversationEntity) {
        val current = _selectedConversation.value ?: return
        viewModelScope.launch {
            val fresh = conversationRepository.getConversation(current.id) ?: return@launch
            val updated = transform(fresh)
            if (updated != fresh) conversationRepository.updateConversation(updated)
            _selectedConversation.value = updated
        }
    }

    fun toggleConversationWorldSet(id: String) {
        val conversationId = _selectedConversationId.value ?: return
        viewModelScope.launch {
            val selected = conversationRepository.getConversationWorldSetIds(conversationId).toMutableSet()
            if (!selected.add(id)) selected.remove(id)
            conversationRepository.replaceConversationWorldSets(
                conversationId,
                selected.map { ConversationWorldSetEntity(conversationId, it) },
            )
        }
    }

    fun setConversationBackground(uri: Uri) {
        val conversation = _selectedConversation.value ?: return
        viewModelScope.launch {
            runCatching {
                val targetPath = withContext(Dispatchers.IO) {
                    val directory = File(appContainer.appContext.filesDir, "chat-backgrounds").apply { mkdirs() }
                    val target = File(directory, "${conversation.id}-${java.util.UUID.randomUUID()}.img")
                    appContainer.appContext.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use(input::copyTo)
                    } ?: throw IOException(text("無法讀取背景圖片。", "无法读取背景图片。"))
                    conversation.backgroundImagePath.takeIf(String::isNotBlank)?.let { runCatching { File(it).delete() } }
                    target.absolutePath
                }
                conversation.copy(backgroundImagePath = targetPath)
            }.onSuccess {
                conversationRepository.updateConversation(it)
                _selectedConversation.value = it
            }.onFailure { _error.value = mapError(it, text("背景圖設定失敗", "背景图设置失败"), settings.value.language) }
        }
    }

    fun clearConversationBackground() {
        val conversation = _selectedConversation.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { conversation.backgroundImagePath.takeIf(String::isNotBlank)?.let { runCatching { File(it).delete() } } }
            val updated = conversation.copy(backgroundImagePath = "")
            conversationRepository.updateConversation(updated)
            _selectedConversation.value = updated
        }
    }

    fun updateMessageBubbleOpacity(opacity: Float) = updateConversation {
        it.copy(messageBubbleOpacity = opacity.coerceIn(0.35f, 1f))
    }

    fun send() {
        if (_input.value.isBlank() || historyMutationBlocked()) return
        runWithUnsafeHttpConfirmation(PendingGenerationStart.Send)
    }

    /**
     * 讀取目前路線上的操作目標。
     *
     * 目標一律取自目前路線，不讀 `messages` 的全域 legacy 欄位（那和目前路線選定的版本是兩層
     * 資料，切換路線時不會同步）。讀取期間對話被換掉或生成被鎖住就放棄。
     */
    private suspend fun routeTarget(conversationId: String, messageId: String): MessageEntity? {
        val message = conversationRepository.getRouteMessage(conversationId, messageId) ?: return null
        if (_selectedConversationId.value != conversationId || historyMutationBlocked()) return null
        return message
    }

    fun requestEditMessage(messageId: String, content: String) {
        val clean = content.trim()
        if (clean.isBlank() || historyMutationBlocked()) return
        val conversationId = _selectedConversationId.value ?: return
        viewModelScope.launch {
            // 用目前路線的正文比對，否則畫面與 legacy 不同時會把真正的編輯誤判成「沒有變更」。
            val message = routeTarget(conversationId, messageId) ?: return@launch
            if (message.deleted || message.content == clean) return@launch
            prepareMutation(PendingMessageMutation(MessageMutationKind.EDIT, messageId, content = clean), message)
        }
    }

    fun requestSelectVersion(messageId: String, versionId: String) {
        if (historyMutationBlocked()) return
        val conversationId = _selectedConversationId.value ?: return
        viewModelScope.launch {
            val message = routeTarget(conversationId, messageId) ?: return@launch
            if (message.currentVersionId == versionId) return@launch
            prepareMutation(PendingMessageMutation(MessageMutationKind.SELECT_VERSION, messageId, versionId = versionId), message)
        }
    }

    fun requestGenerateAlternative(messageId: String) {
        if (historyMutationBlocked()) return
        val conversationId = _selectedConversationId.value ?: return
        viewModelScope.launch {
            // 手寫角色台詞不是 AI 回覆，不能被「生成另一版」覆寫。
            val message = routeTarget(conversationId, messageId)
                ?.takeIf { it.canRegenerate && !it.excluded && it.content.isNotBlank() }
                ?: return@launch
            prepareMutation(PendingMessageMutation(MessageMutationKind.ALTERNATIVE, messageId), message)
        }
    }

    /** 旁白或手寫角色台詞的「讓 AI 接話」：以截至這則的有效歷史請聊天室原本的 AI 角色回答。 */
    fun requestAiReplyFrom(messageId: String) {
        if (historyMutationBlocked()) return
        val conversationId = _selectedConversationId.value ?: return
        viewModelScope.launch {
            val message = routeTarget(conversationId, messageId)
                ?.takeIf { it.acceptsAiReply && !it.excluded && it.content.isNotBlank() }
                ?: return@launch
            prepareMutation(PendingMessageMutation(MessageMutationKind.ANSWER_FROM, messageId), message)
        }
    }

    fun requestAnswerFrom(messageId: String) {
        if (historyMutationBlocked()) return
        val conversationId = _selectedConversationId.value ?: return
        viewModelScope.launch {
            // 排除狀態屬於路線，必須讀目前路線的值；私人註記不是可以回應的訊息。
            val message = routeTarget(conversationId, messageId)
                ?.takeIf { it.acceptsAiReply && !it.excluded }
                ?: return@launch
            prepareMutation(PendingMessageMutation(MessageMutationKind.ANSWER_FROM, messageId), message)
        }
    }

    /**
     * 加入作者手寫的訊息（旁白、指定角色台詞、私人註記）。
     * 只加入紀錄，不呼叫 API、不切換路線。
     */
    fun appendAuthoredMessage(
        kind: MessageKind,
        content: String,
        authorName: String? = null,
        authorCharacterId: String? = null,
    ) {
        val clean = content.trim()
        if (clean.isBlank() || historyMutationBlocked()) return
        val conversationId = _selectedConversationId.value ?: return
        viewModelScope.launch {
            val snapshot = conversationRepository.getGenerationSnapshot(conversationId) ?: return@launch
            if (_selectedConversationId.value != conversationId || historyMutationBlocked()) return@launch
            if (conversationRepository.appendAuthoredMessage(conversationId, kind, clean, authorName, authorCharacterId,
                sourceBranchId = snapshot.branch.id, expectedRevision = snapshot.conversation.historyRevision) == null) {
                reportChangedHistory()
                return@launch
            }
            refreshConversation(conversationId)
        }
    }

    /** 編輯手寫訊息；改署名或改正文都建立新版本，舊署名與舊後續完整保留。 */
    fun requestEditAuthoredMessage(messageId: String, content: String, authorName: String?) {
        val clean = content.trim()
        val name = authorName?.trim()
        if (clean.isBlank() || historyMutationBlocked()) return
        val conversationId = _selectedConversationId.value ?: return
        viewModelScope.launch {
            val message = routeTarget(conversationId, messageId) ?: return@launch
            if (message.deleted) return@launch
            if (message.kind == MessageKind.AUTHORED_CHARACTER && name.isNullOrBlank()) return@launch
            if (message.content == clean && message.authorName == name) return@launch
            prepareMutation(
                PendingMessageMutation(
                    MessageMutationKind.EDIT,
                    messageId,
                    content = clean,
                    authorName = name,
                ),
                message,
            )
        }
    }

    fun requestContinue(messageId: String) {
        if (historyMutationBlocked()) return
        viewModelScope.launch {
            val conversationId = _selectedConversationId.value ?: return@launch
            // 尾端的私人註記不算故事發言，跳過它才找得到真正可以續寫的 AI 回覆。
            val last = conversationRepository.getMessages(conversationId).lastOrNull { it.isModelVisible }
            val message = last?.takeIf { it.id == messageId && it.isAiReply } ?: return@launch
            if (message.excluded || message.deleted || message.content.isBlank()) return@launch
            beginExistingGeneration(ChatGenerationKind.CONTINUATION, message.id)
        }
    }

    fun requestDeleteMessage(messageId: String) {
        if (historyMutationBlocked()) return
        val conversationId = _selectedConversationId.value ?: return
        viewModelScope.launch {
            // 不在目前路線的訊息不得進入刪除確認；實際刪除仍依既有跨路線規格。
            val message = routeTarget(conversationId, messageId) ?: return@launch
            prepareMutation(PendingMessageMutation(MessageMutationKind.DELETE, messageId), message)
        }
    }

    fun toggleMessageExcluded(messageId: String) {
        if (historyMutationBlocked()) return
        viewModelScope.launch {
            val conversationId = _selectedConversationId.value ?: return@launch
            val message = conversationRepository.getRouteMessage(conversationId, messageId) ?: return@launch
            conversationRepository.setMessageExcluded(conversationId, messageId, !message.excluded)
            refreshConversation(conversationId)
        }
    }

    /**
     * 只有明確的刪除需要確認。編輯、切換版本、重發與生成另一版都改成建立新路線，
     * 不會刪掉任何既有版本或後續對話，因此不再顯示刪除警告。
     */
    private suspend fun prepareMutation(mutation: PendingMessageMutation, message: MessageEntity) {
        val revision = conversationRepository.getConversation(message.conversationId)?.historyRevision ?: return
        val prepared = mutation.copy(expectedRevision = revision)
        if (mutation.kind == MessageMutationKind.DELETE) _pendingMutation.value = prepared else executeMutation(prepared)
    }

    private fun historyMutationBlocked(): Boolean = _isStreaming.value || _isSummarizingConversation.value ||
        _isSavingChatInfo.value || draftJob?.isCompleted == false

    fun confirmPendingMutation() {
        val pending = _pendingMutation.value ?: return
        _pendingMutation.value = null
        viewModelScope.launch { executeMutation(pending) }
    }

    fun dismissPendingMutation() { _pendingMutation.value = null }

    private suspend fun executeMutation(mutation: PendingMessageMutation) {
        val conversationId = _selectedConversationId.value ?: return
        val conversation = conversationRepository.getConversation(conversationId) ?: return
        if (mutation.expectedRevision != null && conversation.historyRevision != mutation.expectedRevision) {
            reportChangedHistory()
            return
        }
        when (mutation.kind) {
            MessageMutationKind.SELECT_VERSION -> {
                val versionId = mutation.versionId ?: return
                if (!conversationRepository.selectVersion(conversationId, mutation.messageId, versionId, conversation.historyRevision)) {
                    reportChangedHistory()
                    return
                }
                refreshConversation(conversationId)
                // 切換後定位在被切換的訊息，而不是跳到很遠的尾端。
                _scrollToMessageId.value = mutation.messageId
            }
            MessageMutationKind.EDIT -> {
                if (!conversationRepository.addEditedVersion(
                        conversationId,
                        mutation.messageId,
                        mutation.content.orEmpty(),
                        conversation.historyRevision,
                        authorName = mutation.authorName,
                        authorCharacterId = mutation.authorCharacterId,
                    )
                ) {
                    reportChangedHistory()
                    return
                }
                refreshConversation(conversationId)
                _scrollToMessageId.value = mutation.messageId
            }
            MessageMutationKind.ALTERNATIVE -> beginExistingGeneration(ChatGenerationKind.ALTERNATIVE, mutation.messageId)
            MessageMutationKind.ANSWER_FROM -> beginExistingGeneration(ChatGenerationKind.ANSWER_FROM_USER, mutation.messageId)
            MessageMutationKind.DELETE -> {
                conversationRepository.deleteMessage(conversationId, mutation.messageId)
                refreshConversation(conversationId)
            }
        }
    }

    private fun reportChangedHistory() {
        _error.value = UiError(
            text("對話已變更", "对话已变更"),
            text("操作期間訊息歷史發生變更，請重新操作。", "操作期间消息历史发生变化，请重新操作。"),
            text("原有訊息沒有被刪除。", "原有消息没有被删除。"),
        )
    }

    private suspend fun beginExistingGeneration(kind: ChatGenerationKind, messageId: String) {
        val conversationId = _selectedConversationId.value ?: return
        val message = conversationRepository.getRouteMessage(conversationId, messageId) ?: return
        val conversation = conversationRepository.getConversation(conversationId) ?: return
        val request = ChatGenerationRequest(
            kind = kind,
            conversationId = conversationId,
            targetMessageId = message.id,
            baseVersionId = message.currentVersionId,
            expectedRevision = conversation.historyRevision,
        )
        runWithUnsafeHttpConfirmation(PendingGenerationStart.Existing(request))
    }

    fun confirmUnsafeHttp() {
        _showUnsafeHttpWarning.value = false
        val pending = pendingGenerationStart
        pendingGenerationStart = null
        when (pending) {
            PendingGenerationStart.Send -> startNewMessage()
            is PendingGenerationStart.Existing -> startGeneration(pending.request)
            PendingGenerationStart.Draft -> startDraftReply()
            null -> Unit
        }
    }

    fun dismissUnsafeHttp() {
        pendingGenerationStart = null
        _showUnsafeHttpWarning.value = false
    }

    private fun runWithUnsafeHttpConfirmation(start: PendingGenerationStart) {
        if (settings.value.usesUnsafeHttp) {
            pendingGenerationStart = start
            _showUnsafeHttpWarning.value = true
        } else when (start) {
            PendingGenerationStart.Send -> startNewMessage()
            is PendingGenerationStart.Existing -> startGeneration(start.request)
            PendingGenerationStart.Draft -> startDraftReply()
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        api.cancelActive()
    }

    private fun launchGeneration(action: suspend () -> Unit) {
        if (historyMutationBlocked() || streamJob?.isCompleted == false) return
        _isStreaming.value = true
        streamJob = viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                _error.value = mapError(error, text("生成失敗", "生成失败"), settings.value.language)
            } finally {
                _activeAssistantMessageId.value = null
                _isStreaming.value = false
            }
        }
    }

    private fun startNewMessage() {
        val content = _input.value.trim()
        if (content.isBlank()) return
        launchGeneration {
            val conversationId = _selectedConversationId.value ?: run {
                navigate(Screen.NEW_CHAT)
                return@launchGeneration
            }
            validateCurrentReasoningMode()
            conversationRepository.createInitialMessage(conversationId, "user", content) ?: return@launchGeneration
            _input.value = ""
            val conversation = conversationRepository.getConversation(conversationId) ?: return@launchGeneration
            streamConversation(
                ChatGenerationRequest(ChatGenerationKind.NEW_REPLY, conversationId, expectedRevision = conversation.historyRevision),
            )
        }
    }

    private fun startGeneration(request: ChatGenerationRequest) {
        launchGeneration {
            validateCurrentReasoningMode()
            streamConversation(request)
        }
    }

    private suspend fun validateCurrentReasoningMode() {
        val id = _selectedConversationId.value ?: return
        val conversation = conversationRepository.getConversation(id) ?: return
        validateReasoningMode(settings.value, conversation.reasoningMode)
    }

    private suspend fun streamConversation(
        request: ChatGenerationRequest,
        allowAutoSummary: Boolean = true,
        temporarySummary: ConversationEntity? = null,
    ) {
        val source = conversationRepository.getActiveBranch(request.conversationId) ?: return
        val resolvedRequest = if (request.sceneNote == null) request.copy(
            sourceBranchId = source.id, sceneNote = source.sceneNoteValue(),
        ) else request
        val current = settings.value
        val key = secretStore.apiKeyFor(current)
        if (key.isBlank() || current.resolvedBaseUrl.isBlank()) {
            _error.value = UiError(
                current.language.pick("缺少 API 設定", "缺少 API 设置"),
                current.language.pick("請設定 ${current.provider.label} 的 API Key 與網址。", "请设置 ${current.provider.label} 的 API Key 与网址。"),
                current.language.pick("前往設定頁填寫後再試一次。", "前往设置页填写后再试一次。"),
            )
            return
        }
        lastFailedRequest = resolvedRequest
        try {
            val finish = streamConversationUseCase(
                resolvedRequest, current, key,
                onAssistantMessageCreated = { _activeAssistantMessageId.value = it },
                temporarySummary = temporarySummary,
            )
            refreshConversation(request.conversationId)
            lastFailedRequest = null
            if (finish.reachedLengthLimit) {
                _error.value = UiError(
                    current.language.pick("已達輸出上限", "已达到输出上限"),
                    current.language.pick("模型因 Token 上限停止，本次已生成內容仍已保存。", "模型因 Token 上限停止，本次已生成内容仍已保存。"),
                    current.language.pick("可在對話資訊調高最大輸出 Token，或使用續寫。", "可在对话信息调高最大输出 Token，或使用续写。"),
                )
            }
        } catch (error: CancellationException) {
            refreshConversation(request.conversationId)
            throw error
        } catch (error: Throwable) {
            _activeAssistantMessageId.value = null
            currentCoroutineContext().ensureActive()
            // 失敗會回滾候選，歷史修訂號因此往前推；重試必須用最新值，否則會被判成「對話已變更」。
            val revision = conversationRepository.getConversation(request.conversationId)?.historyRevision
            val retryRequest = if (revision != null) resolvedRequest.copy(expectedRevision = revision) else resolvedRequest
            lastFailedRequest = retryRequest
            if (allowAutoSummary && error is ApiException && error.isContextLengthError) {
                try {
                    val conversation = conversationRepository.getActiveConversation(request.conversationId) ?: return
                    val history = conversationRepository.getMessages(request.conversationId)
                    val target = request.targetMessageId?.let { id -> history.firstOrNull { it.id == id } }
                    val allowed = EffectiveHistoryResolver.resolve(conversation, history, request.kind, target).worldHistory
                    val mode = if (target != null && conversation.summaryThroughOrder > target.sortOrder) {
                        ManualSummaryMode.REBUILD_ALL
                    } else {
                        ManualSummaryMode.UN_SUMMARIZED
                    }
                    val summary = summarizeConversation(
                        request.conversationId, current, key, 8, mode,
                        eligibleHistory = allowed,
                        persist = false,
                    ) ?: return
                    streamConversation(retryRequest, allowAutoSummary = false, temporarySummary = summary)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    _error.value = mapError(failure, current.language.pick("自動摘要失敗", "自动摘要失败"), current.language)
                }
            } else {
                _error.value = mapError(error, current.language.pick("生成失敗", "生成失败"), current.language)
            }
        }
    }

    fun manuallySummarizeConversation(mode: ManualSummaryMode, keepRecentMessages: Int) {
        val id = _selectedConversationId.value ?: return
        if (historyMutationBlocked()) return
        val current = settings.value
        _isSummarizingConversation.value = true
        viewModelScope.launch {
            try {
                val key = secretStore.apiKeyFor(current)
                if (key.isBlank() || current.resolvedBaseUrl.isBlank()) {
                    _error.value = UiError(
                        current.language.pick("缺少 API 設定", "缺少 API 设置"),
                        current.language.pick("請設定 ${current.provider.label} 的 API Key 與網址。", "请设置 ${current.provider.label} 的 API Key 与网址。"),
                        current.language.pick("前往設定頁填寫後再試一次。", "前往设置页填写后再试一次。"),
                    )
                    return@launch
                }
                summarizeConversation(id, current, key, keepRecentMessages.coerceIn(1, 100), mode)?.let { _selectedConversation.value = it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _error.value = mapError(error, current.language.pick("手動壓縮失敗", "手动压缩失败"), current.language)
            } finally {
                _isSummarizingConversation.value = false
            }
        }
    }

    fun trimOldestContextAndRetry() {
        val request = lastFailedRequest ?: return
        clearError()
        launchGeneration {
            val conversation = conversationRepository.getConversation(request.conversationId) ?: return@launchGeneration
            val active = conversationRepository.getActiveConversation(request.conversationId) ?: return@launchGeneration
            val branch = conversationRepository.getActiveBranch(request.conversationId) ?: return@launchGeneration
            val all = conversationRepository.getMessages(request.conversationId)
            val target = request.targetMessageId?.let { id -> all.firstOrNull { it.id == id } }
            val history = EffectiveHistoryResolver.resolve(active, all, request.kind, target).worldHistory
            if (history.size <= 2) return@launchGeneration
            val kept = history.drop(history.size / 2).first()
            // 裁切只影響目前路線。
            conversationRepository.updateBranchContextStart(branch.id, kept.sortOrder)
            streamConversation(request.copy(expectedRevision = conversation.historyRevision), allowAutoSummary = false)
        }
    }

    fun setSearchQuery(query: String) {
        _searchState.value = ChatSearchState(query = query, revision = _searchState.value.revision)
        _searchQuery.value = query
    }

    fun closeSearch() {
        _searchQuery.value = ""
        _searchState.value = ChatSearchState(revision = _searchState.value.revision + 1)
    }

    fun nextSearchResult() = moveSearch(1)
    fun previousSearchResult() = moveSearch(-1)
    fun selectSearchResult(index: Int) {
        val state = _searchState.value
        if (index in state.hits.indices) _searchState.value = state.copy(currentIndex = index, revision = state.revision + 1)
    }

    private fun moveSearch(delta: Int) {
        val state = _searchState.value
        if (state.hits.isEmpty()) return
        val next = (max(0, state.currentIndex) + delta).mod(state.hits.size)
        _searchState.value = state.copy(currentIndex = next, revision = state.revision + 1)
    }

}

internal fun findChatMessages(query: String, current: List<MessageEntity>): Pair<String, List<ChatSearchHit>> {
    if (query.isBlank()) return query to emptyList()
    return query to current.mapNotNull { message ->
        val index = message.content.indexOf(query, ignoreCase = true)
        if (index < 0) null else {
            val start = (index - 24).coerceAtLeast(0)
            val end = (index + query.length + 36).coerceAtMost(message.content.length)
            ChatSearchHit(message.id, message.content.substring(start, end).replace('\n', ' '))
        }
    }
}
