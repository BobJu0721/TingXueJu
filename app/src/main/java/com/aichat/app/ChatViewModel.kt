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

enum class MessageMutationKind { SELECT_VERSION, EDIT, ALTERNATIVE, ANSWER_FROM, DELETE }

data class PendingMessageMutation(
    val kind: MessageMutationKind,
    val messageId: String,
    val followingCount: Int = 0,
    val versionId: String? = null,
    val content: String? = null,
    val expectedRevision: Long? = null,
)

private sealed interface PendingGenerationStart {
    data object Send : PendingGenerationStart
    data class Existing(val request: ChatGenerationRequest) : PendingGenerationStart
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
    val messages = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else conversationRepository.observeMessages(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val messageVersions = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else conversationRepository.observeMessageVersions(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val generationContexts = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else conversationRepository.observeGenerationContexts(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeWorldSetIds = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else worldInfoRepository.observeConversationWorldSetIds(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        _selectedConversation.value = id?.let { conversationRepository.getConversation(it) }
    }

    fun setInput(value: String) { _input.value = value }
    fun clearError() { _error.value = null }
    fun openConversations() { navigate(Screen.CONVERSATIONS) }
    fun openCurrentChat() { navigate(Screen.CHAT) }
    fun openModels() { navigate(Screen.MODELS) }

    fun selectConversation(id: String) {
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
        viewModelScope.launch { refreshConversation() }
        navigate(Screen.CHAT_INFO)
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

    fun requestEditMessage(messageId: String, content: String) {
        val clean = content.trim()
        if (clean.isBlank() || historyMutationBlocked()) return
        viewModelScope.launch {
            val message = conversationRepository.getMessage(messageId) ?: return@launch
            if (message.content == clean) return@launch
            prepareMutation(PendingMessageMutation(MessageMutationKind.EDIT, messageId, content = clean), message)
        }
    }

    fun requestSelectVersion(messageId: String, versionId: String) {
        if (historyMutationBlocked()) return
        viewModelScope.launch {
            val message = conversationRepository.getMessage(messageId) ?: return@launch
            if (message.currentVersionId == versionId) return@launch
            prepareMutation(PendingMessageMutation(MessageMutationKind.SELECT_VERSION, messageId, versionId = versionId), message)
        }
    }

    fun requestGenerateAlternative(messageId: String) {
        if (historyMutationBlocked()) return
        viewModelScope.launch {
            val message = conversationRepository.getMessage(messageId)?.takeIf { it.role == "assistant" && !it.excluded } ?: return@launch
            prepareMutation(PendingMessageMutation(MessageMutationKind.ALTERNATIVE, messageId), message)
        }
    }

    fun requestAnswerFrom(messageId: String) {
        if (historyMutationBlocked()) return
        viewModelScope.launch {
            val message = conversationRepository.getMessage(messageId)?.takeIf { it.role == "user" && !it.excluded } ?: return@launch
            prepareMutation(PendingMessageMutation(MessageMutationKind.ANSWER_FROM, messageId), message)
        }
    }

    fun requestContinue(messageId: String) {
        if (historyMutationBlocked()) return
        viewModelScope.launch {
            val eligibleLast = conversationRepository.getMessages(_selectedConversationId.value ?: return@launch).lastOrNull()
            val message = eligibleLast?.takeIf { it.id == messageId && it.role == "assistant" } ?: return@launch
            if (message.excluded || message.content.isBlank()) return@launch
            beginExistingGeneration(ChatGenerationKind.CONTINUATION, message)
        }
    }

    fun requestDeleteMessage(messageId: String) {
        if (historyMutationBlocked()) return
        _pendingMutation.value = PendingMessageMutation(MessageMutationKind.DELETE, messageId)
    }

    fun toggleMessageExcluded(messageId: String) {
        if (historyMutationBlocked()) return
        viewModelScope.launch {
            val message = conversationRepository.getMessage(messageId) ?: return@launch
            conversationRepository.setMessageExcluded(message, !message.excluded)
            refreshConversation(message.conversationId)
        }
    }

    private suspend fun prepareMutation(mutation: PendingMessageMutation, message: MessageEntity) {
        val count = conversationRepository.countMessagesAfter(message)
        val revision = conversationRepository.getConversation(message.conversationId)?.historyRevision ?: return
        val prepared = mutation.copy(followingCount = count, expectedRevision = revision)
        if (count > 0) _pendingMutation.value = prepared else executeMutation(prepared)
    }

    private fun historyMutationBlocked(): Boolean = _isStreaming.value || _isSummarizingConversation.value

    fun confirmPendingMutation() {
        val pending = _pendingMutation.value ?: return
        _pendingMutation.value = null
        viewModelScope.launch { executeMutation(pending) }
    }

    fun dismissPendingMutation() { _pendingMutation.value = null }

    private suspend fun executeMutation(mutation: PendingMessageMutation) {
        val message = conversationRepository.getMessage(mutation.messageId) ?: return
        val conversation = conversationRepository.getConversation(message.conversationId) ?: return
        if (mutation.expectedRevision != null && conversation.historyRevision != mutation.expectedRevision) {
            _error.value = UiError(
                text("對話已變更", "对话已变更"),
                text("確認期間訊息歷史發生變更，請重新操作。", "确认期间消息历史发生变化，请重新操作。"),
                text("原有訊息沒有被刪除。", "原有消息没有被删除。"),
            )
            return
        }
        when (mutation.kind) {
            MessageMutationKind.SELECT_VERSION -> {
                val version = mutation.versionId?.let { conversationRepository.getMessageVersion(it) } ?: return
                if (!conversationRepository.selectVersion(message, version, conversation.historyRevision)) {
                    reportChangedHistory()
                    return
                }
                refreshConversation(message.conversationId)
            }
            MessageMutationKind.EDIT -> {
                if (!conversationRepository.addEditedVersion(message, mutation.content.orEmpty(), conversation.historyRevision)) {
                    reportChangedHistory()
                    return
                }
                refreshConversation(message.conversationId)
            }
            MessageMutationKind.ALTERNATIVE -> beginExistingGeneration(ChatGenerationKind.ALTERNATIVE, message)
            MessageMutationKind.ANSWER_FROM -> beginExistingGeneration(ChatGenerationKind.ANSWER_FROM_USER, message)
            MessageMutationKind.DELETE -> {
                conversationRepository.deleteMessage(message)
                refreshConversation(message.conversationId)
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

    private suspend fun beginExistingGeneration(kind: ChatGenerationKind, message: MessageEntity) {
        val conversation = conversationRepository.getConversation(message.conversationId) ?: return
        val request = ChatGenerationRequest(
            kind = kind,
            conversationId = message.conversationId,
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
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        api.cancelActive()
    }

    private fun launchGeneration(action: suspend () -> Unit) {
        if (_isStreaming.value || streamJob?.isCompleted == false) return
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
            conversationRepository.createInitialMessage(conversationId, "user", content)
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
        val current = settings.value
        val key = secretStore.get(current.provider)
        if (key.isBlank() || current.resolvedBaseUrl.isBlank()) {
            _error.value = UiError(
                current.language.pick("缺少 API 設定", "缺少 API 设置"),
                current.language.pick("請設定 ${current.provider.label} 的 API Key 與網址。", "请设置 ${current.provider.label} 的 API Key 与网址。"),
                current.language.pick("前往設定頁填寫後再試一次。", "前往设置页填写后再试一次。"),
            )
            return
        }
        lastFailedRequest = request
        try {
            val finish = streamConversationUseCase(
                request, current, key,
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
            if (allowAutoSummary && error is ApiException && error.isContextLengthError) {
                try {
                    val conversation = conversationRepository.getConversation(request.conversationId) ?: return
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
                    streamConversation(request, allowAutoSummary = false, temporarySummary = summary)
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
        if (_isStreaming.value || _isSummarizingConversation.value) return
        val current = settings.value
        viewModelScope.launch {
            val key = secretStore.get(current.provider)
            if (key.isBlank() || current.resolvedBaseUrl.isBlank()) {
                _error.value = UiError(
                    current.language.pick("缺少 API 設定", "缺少 API 设置"),
                    current.language.pick("請設定 ${current.provider.label} 的 API Key 與網址。", "请设置 ${current.provider.label} 的 API Key 与网址。"),
                    current.language.pick("前往設定頁填寫後再試一次。", "前往设置页填写后再试一次。"),
                )
                return@launch
            }
            _isSummarizingConversation.value = true
            try {
                summarizeConversation(id, current, key, keepRecentMessages.coerceIn(1, 100), mode)?.let { _selectedConversation.value = it }
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
            if (conversation.historyRevision != request.expectedRevision) return@launchGeneration
            val all = conversationRepository.getMessages(request.conversationId)
            val target = request.targetMessageId?.let { id -> all.firstOrNull { it.id == id } }
            val history = EffectiveHistoryResolver.resolve(conversation, all, request.kind, target).worldHistory
            if (history.size <= 2) return@launchGeneration
            val kept = history.drop(history.size / 2).first()
            conversationRepository.updateConversation(
                conversation.copy(contextStartAt = kept.createdAt, contextStartOrder = kept.sortOrder),
            )
            streamConversation(request, allowAutoSummary = false)
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
