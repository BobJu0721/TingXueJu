package com.aichat.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.ConversationWorldSetEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.ProfileType
import com.aichat.app.network.ApiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val appContainer: AppContainer) : ViewModel() {
    private val conversationRepository = appContainer.conversationRepository
    private val profileRepository = appContainer.profileRepository
    private val worldInfoRepository = appContainer.worldInfoRepository
    private val settingsRepository = appContainer.settingsRepository
    private val secretStore = appContainer.secretStore
    private val api = appContainer.api
    private val summarizeConversation = appContainer.summarizeConversationUseCase
    private val streamConversationUseCase = appContainer.streamConversationUseCase

    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val conversations = conversationRepository.observeConversations().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val personas = profileRepository.observeProfiles(ProfileType.PERSONA).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val worldSets = worldInfoRepository.observeWorldSets().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val worldEntryCounts = worldInfoRepository.observeWorldEntryCounts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId = _selectedConversationId.asStateFlow()
    val messages = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else conversationRepository.observeMessages(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val generationContexts = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else conversationRepository.observeGenerationContexts(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeWorldSetIds = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else worldInfoRepository.observeConversationWorldSetIds(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _input = MutableStateFlow("")
    val input = _input.asStateFlow()
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming = _isStreaming.asStateFlow()
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

    private var pendingAction: PendingAction? = null
    private var pendingResendMessageId: String? = null
    private var streamJob: Job? = null

    private fun text(traditional: String, simplified: String): String =
        settings.value.language.pick(traditional, simplified)

    private fun showNotice(message: String) { /* Notices are intentionally disabled. */ }
    private fun navigate(screen: Screen) { viewModelScope.launch { _navigationEvents.emit(screen) } }

    fun setInput(value: String) { _input.value = value }
    fun clearError() { _error.value = null }
    fun openConversations() { navigate(Screen.CONVERSATIONS) }
    fun openCurrentChat() { navigate(Screen.CHAT) }
    fun openModels() { navigate(Screen.MODELS) }

    fun selectConversation(id: String) {
        _selectedConversationId.value = id
        viewModelScope.launch { _selectedConversation.value = conversationRepository.getConversation(id) }
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
        viewModelScope.launch { _selectedConversation.value = conversationRepository.getConversation(id) }
        navigate(Screen.CHAT_INFO)
    }

    fun updateConversationPersona(id: String?) {
        val conversation = _selectedConversation.value ?: return
        viewModelScope.launch {
            val updated = conversation.copy(personaId = id)
            conversationRepository.updateConversation(updated)
            _selectedConversation.value = updated
            showNotice(text("Persona 已更新", "Persona 已更新"))
        }
    }

    fun toggleConversationWorldSet(id: String) {
        val conversationId = _selectedConversationId.value ?: return
        viewModelScope.launch {
            val selected = conversationRepository.getConversationWorldSetIds(conversationId).toMutableSet()
            if (!selected.add(id)) selected.remove(id)
            setConversationWorldSets(conversationId, selected)
        }
    }

    fun saveConversationSummary(summary: String) {
        val conversation = _selectedConversation.value ?: return
        viewModelScope.launch {
            val updated = conversation.copy(summary = summary.trim())
            conversationRepository.updateConversation(updated)
            _selectedConversation.value = updated
            showNotice(text("摘要已儲存", "摘要已保存"))
        }
    }

    fun renameConversation(title: String) {
        val conversation = _selectedConversation.value ?: return
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank() || cleanTitle == conversation.title) return
        viewModelScope.launch {
            val updated = conversation.copy(title = cleanTitle, updatedAt = System.currentTimeMillis())
            conversationRepository.updateConversation(updated)
            _selectedConversation.value = updated
        }
    }

    fun setConversationBackground(uri: Uri) {
        val conversation = _selectedConversation.value ?: return
        viewModelScope.launch {
            runCatching {
                val directory = File(appContainer.appContext.filesDir, "chat-backgrounds")
                directory.mkdirs()
                val target = File(directory, "${conversation.id}-${UUID.randomUUID()}.img")
                appContainer.appContext.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException(text("無法讀取背景圖片。", "无法读取背景图片。"))

                conversation.backgroundImagePath.takeIf(String::isNotBlank)?.let {
                    runCatching { File(it).delete() }
                }
                val updated = conversation.copy(backgroundImagePath = target.absolutePath)
                conversationRepository.updateConversation(updated)
                _selectedConversation.value = updated
            }
                .onSuccess { showNotice(text("背景圖已更新", "背景图已更新")) }
                .onFailure { _error.value = mapError(it, text("背景圖設定失敗", "背景图设置失败"), settings.value.language) }
        }
    }

    fun clearConversationBackground() {
        val conversation = _selectedConversation.value ?: return
        viewModelScope.launch {
            conversation.backgroundImagePath.takeIf(String::isNotBlank)?.let {
                runCatching { File(it).delete() }
            }
            val updated = conversation.copy(backgroundImagePath = "")
            conversationRepository.updateConversation(updated)
            _selectedConversation.value = updated
            showNotice(text("背景圖已移除", "背景图已移除"))
        }
    }

    fun updateMessageBubbleOpacity(opacity: Float) {
        val conversation = _selectedConversation.value ?: return
        val cleanOpacity = opacity.coerceIn(0.35f, 1f)
        if (cleanOpacity == conversation.messageBubbleOpacity) return
        viewModelScope.launch {
            val updated = conversation.copy(messageBubbleOpacity = cleanOpacity)
            conversationRepository.updateConversation(updated)
            _selectedConversation.value = updated
        }
    }

    private suspend fun setConversationWorldSets(conversationId: String, ids: Set<String>) {
        conversationRepository.clearConversationWorldSets(conversationId)
        if (ids.isNotEmpty()) conversationRepository.addConversationWorldSets(ids.map { ConversationWorldSetEntity(conversationId, it) })
    }


    fun send() {
        if (_input.value.isBlank() || _isStreaming.value) return
        runWithUnsafeHttpConfirmation(PendingAction.SEND)
    }
    fun retryLastResponse() {
        if (_selectedConversationId.value == null || _isStreaming.value) return
        runWithUnsafeHttpConfirmation(PendingAction.RETRY)
    }
    fun editMessage(messageId: String, content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank() || _isStreaming.value) return
        viewModelScope.launch {
            val message = conversationRepository.getMessage(messageId) ?: return@launch
            val conversation = conversationRepository.getConversation(message.conversationId) ?: return@launch
            conversationRepository.updateMessage(message.copy(content = trimmed))
            if (message.role == "assistant") conversationRepository.clearReasoningContent(message.id)
            conversationRepository.updateConversation(
                conversation.copy(
                    updatedAt = System.currentTimeMillis(),
                    summary = if (message.createdAt <= conversation.summaryThroughAt) "" else conversation.summary,
                    summaryThroughAt = if (message.createdAt <= conversation.summaryThroughAt) 0 else conversation.summaryThroughAt,
                ),
            )
            showNotice(text("訊息已更新", "消息已更新"))
        }
    }
    fun resendFromMessage(messageId: String) {
        if (_selectedConversationId.value == null || _isStreaming.value) return
        pendingResendMessageId = messageId
        runWithUnsafeHttpConfirmation(PendingAction.RESEND_FROM_MESSAGE)
    }
    fun confirmUnsafeHttp() {
        _showUnsafeHttpWarning.value = false
        val action = pendingAction
        pendingAction = null
        when (action) {
            PendingAction.SEND -> startNewMessage()
            PendingAction.RETRY -> startRetry()
            PendingAction.RESEND_FROM_MESSAGE -> startResendFromMessage(pendingResendMessageId)
            null -> Unit
        }
        pendingResendMessageId = null
    }
    fun dismissUnsafeHttp() { pendingAction = null; pendingResendMessageId = null; _showUnsafeHttpWarning.value = false }
    fun stopStreaming() { api.cancelActive(); streamJob?.cancel(); _isStreaming.value = false; showNotice(text("已停止生成", "已停止生成")) }

    private fun runWithUnsafeHttpConfirmation(action: PendingAction) {
        if (settings.value.usesUnsafeHttp) { pendingAction = action; _showUnsafeHttpWarning.value = true }
        else when (action) {
            PendingAction.SEND -> startNewMessage()
            PendingAction.RETRY -> startRetry()
            PendingAction.RESEND_FROM_MESSAGE -> {
                startResendFromMessage(pendingResendMessageId)
                pendingResendMessageId = null
            }
        }
    }

    private fun startNewMessage() {
        val content = _input.value.trim()
        if (content.isBlank()) return
        _input.value = ""
        streamJob = viewModelScope.launch {
            val conversationId = _selectedConversationId.value ?: run {
                navigate(Screen.NEW_CHAT)
                _input.value = content
                showNotice(text("請先確認 Persona 與世界設定", "请先确认 Persona 与世界设定"))
                return@launch
            }
            val now = System.currentTimeMillis()
            conversationRepository.upsertMessage(MessageEntity(UUID.randomUUID().toString(), conversationId, "user", content, now))
            conversationRepository.getConversation(conversationId)?.let { conversationRepository.updateConversation(it.copy(updatedAt = now)) }
            streamConversation(conversationId)
        }
    }

    private fun startRetry() {
        val conversationId = _selectedConversationId.value ?: return
        streamJob = viewModelScope.launch {
            conversationRepository.getMessages(conversationId).lastOrNull()?.takeIf { it.role == "assistant" }?.let { conversationRepository.deleteMessage(it.id) }
            streamConversation(conversationId)
        }
    }

    private fun startResendFromMessage(messageId: String?) {
        if (messageId == null) return
        streamJob = viewModelScope.launch {
            val message = conversationRepository.getMessage(messageId) ?: return@launch
            val conversation = conversationRepository.getConversation(message.conversationId) ?: return@launch
            if (message.role == "assistant") {
                conversationRepository.deleteMessagesAtOrAfter(message.conversationId, message.createdAt)
            } else {
                conversationRepository.deleteMessagesAfter(message.conversationId, message.createdAt)
            }
            conversationRepository.updateConversation(
                conversation.copy(
                    updatedAt = System.currentTimeMillis(),
                    summary = if (message.createdAt <= conversation.summaryThroughAt) "" else conversation.summary,
                    summaryThroughAt = if (message.createdAt <= conversation.summaryThroughAt) 0 else conversation.summaryThroughAt,
                ),
            )
            showNotice(text("已從這則訊息重新發送", "已从这则消息重新发送"))
            streamConversation(message.conversationId)
        }
    }

    private suspend fun streamConversation(conversationId: String, allowAutoSummary: Boolean = true) {
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
        _isStreaming.value = true
        try {
            streamConversationUseCase(conversationId, current, key)
        } catch (error: Throwable) {
            if (allowAutoSummary && error is ApiException && error.isContextLengthError) {
                runCatching { summarizeConversation(conversationId, current, key, keepRecentMessages = 8, mode = ManualSummaryMode.UN_SUMMARIZED)?.let { _selectedConversation.value = it } }
                    .onSuccess {
                        showNotice(current.language.pick("已摘要較早對話，正在重試", "已摘要较早对话，正在重试"))
                        streamConversation(conversationId, allowAutoSummary = false)
                    }
                    .onFailure { _error.value = mapError(it, current.language.pick("自動摘要失敗", "自动摘要失败"), current.language) }
            } else {
                _error.value = mapError(error, current.language.pick("生成失敗", "生成失败"), current.language)
            }
        } finally {
            _isStreaming.value = false
        }
    }

    fun manuallySummarizeConversation(mode: ManualSummaryMode, keepRecentMessages: Int) {
        val id = _selectedConversationId.value ?: return
        if (_isStreaming.value || _isSummarizingConversation.value) return
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
        viewModelScope.launch {
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
        val id = _selectedConversationId.value ?: return
        clearError()
        streamJob = viewModelScope.launch {
            val conversation = conversationRepository.getConversation(id) ?: return@launch
            val history = conversationRepository.getMessages(id).filter { it.content.isNotBlank() }
            if (history.size <= 2) return@launch
            val kept = history.drop(history.size / 2).first()
            conversationRepository.updateConversation(conversation.copy(contextStartAt = kept.createdAt))
            showNotice("")
            streamConversation(id, allowAutoSummary = false)
        }
    }
}
