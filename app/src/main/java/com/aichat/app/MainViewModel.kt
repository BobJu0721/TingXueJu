package com.aichat.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.app.data.AppDatabase
import com.aichat.app.data.AppLanguage
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.ConversationWorldSetEntity
import com.aichat.app.data.GenerationContextEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.ProfileEntity
import com.aichat.app.data.ProfileType
import com.aichat.app.data.Provider
import com.aichat.app.data.SecretStore
import com.aichat.app.data.SettingsRepository
import com.aichat.app.data.WorldEntryEntity
import com.aichat.app.data.WorldSetEntity
import com.aichat.app.network.AiApiClient
import com.aichat.app.network.ApiChatMessage
import com.aichat.app.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID

enum class Screen {
    CONVERSATIONS, CHAT, SETTINGS, MODELS, CHARACTERS, LIBRARY, PROFILE_EDIT,
    WORLD_SETS, WORLD_SET_EDIT, NEW_CHAT, CHAT_INFO,
}
enum class ErrorKind { GENERAL, CONTEXT_LENGTH }
enum class PendingAction { SEND, RETRY, RESEND_FROM_MESSAGE }
enum class ImportTarget { CHARACTER, PERSONA, WORLD_SET }
enum class ManualSummaryMode { UN_SUMMARIZED, REBUILD_ALL }

data class UiError(
    val title: String,
    val message: String,
    val suggestion: String,
    val kind: ErrorKind = ErrorKind.GENERAL,
)

data class PendingDocumentImport(
    val target: ImportTarget,
    val document: ImportedDocument,
) {
    val estimatedCalls: Int
        get() = ((document.text.length + IMPORT_CHUNK_SIZE - 1) / IMPORT_CHUNK_SIZE).coerceAtLeast(1) + 1
}

data class WorldTemplate(
    val name: String,
    val categories: List<String>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.get(application).chatDao()
    private val settingsRepository = SettingsRepository(application)
    private val secretStore = SecretStore(application)
    private val api = AiApiClient()

    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val conversations = dao.observeConversations().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val characters = dao.observeProfiles(ProfileType.CHARACTER).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val personas = dao.observeProfiles(ProfileType.PERSONA).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val worldSets = dao.observeWorldSets().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val worldEntryCounts = dao.observeWorldEntryCounts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val worldTemplates = DEFAULT_WORLD_TEMPLATES

    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId = _selectedConversationId.asStateFlow()
    val messages = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.observeMessages(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val generationContexts = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.observeGenerationContexts(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeWorldSetIds = _selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.observeConversationWorldSetIds(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _screen = MutableStateFlow(Screen.CONVERSATIONS)
    val screen = _screen.asStateFlow()
    private val _navigationVersion = MutableStateFlow(0)
    val navigationVersion = _navigationVersion.asStateFlow()
    private val _input = MutableStateFlow("")
    val input = _input.asStateFlow()
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming = _isStreaming.asStateFlow()
    private val _error = MutableStateFlow<UiError?>(null)
    val error = _error.asStateFlow()
    private val _showUnsafeHttpWarning = MutableStateFlow(false)
    val showUnsafeHttpWarning = _showUnsafeHttpWarning.asStateFlow()
    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models = _models.asStateFlow()
    private val _isLoadingModels = MutableStateFlow(false)
    val isLoadingModels = _isLoadingModels.asStateFlow()
    private val _isSummarizingConversation = MutableStateFlow(false)
    val isSummarizingConversation = _isSummarizingConversation.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val _editingProfile = MutableStateFlow<ProfileDraft?>(null)
    val editingProfile = _editingProfile.asStateFlow()
    private val _editingWorldSet = MutableStateFlow<WorldSetEntity?>(null)
    val editingWorldSet = _editingWorldSet.asStateFlow()
    val editingWorldEntries = _editingWorldSet.flatMapLatest { worldSet ->
        if (worldSet == null) flowOf(emptyList()) else dao.observeWorldEntries(worldSet.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _pendingImport = MutableStateFlow<PendingDocumentImport?>(null)
    val pendingImport = _pendingImport.asStateFlow()
    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()
    private val _selectedConversation = MutableStateFlow<ConversationEntity?>(null)
    val selectedConversation = _selectedConversation.asStateFlow()
    private val _newChatCharacter = MutableStateFlow<ProfileEntity?>(null)
    val newChatCharacter = _newChatCharacter.asStateFlow()
    private val _newChatPersonaId = MutableStateFlow<String?>(null)
    val newChatPersonaId = _newChatPersonaId.asStateFlow()
    private val _newChatWorldSetIds = MutableStateFlow<Set<String>>(emptySet())
    val newChatWorldSetIds = _newChatWorldSetIds.asStateFlow()
    private val _newChatGreeting = MutableStateFlow("")
    val newChatGreeting = _newChatGreeting.asStateFlow()

    private var pendingAction: PendingAction? = null
    private var pendingResendMessageId: String? = null
    private var streamJob: Job? = null

    private fun text(traditional: String, simplified: String): String =
        settings.value.language.pick(traditional, simplified)

    private fun showScreen(target: Screen) {
        _screen.value = target
        _navigationVersion.value += 1
    }

    fun setInput(value: String) { _input.value = value }
    fun openConversations() { showScreen(Screen.CONVERSATIONS) }
    fun openCharacters() { showScreen(Screen.CHARACTERS) }
    fun openLibrary() { showScreen(Screen.LIBRARY) }
    fun openWorldSets() { showScreen(Screen.WORLD_SETS) }
    fun openSettings() { showScreen(Screen.SETTINGS) }
    fun openRootScreen(screen: Screen) {
        if (screen in listOf(Screen.CONVERSATIONS, Screen.CHARACTERS, Screen.LIBRARY, Screen.SETTINGS)) {
            showScreen(screen)
        }
    }
    fun openCurrentChat() { showScreen(Screen.CHAT) }
    fun openModels() { showScreen(Screen.MODELS); refreshModels() }
    fun clearError() { _error.value = null }
    fun clearNotice() { _notice.value = null }
    private fun showNotice(message: String) { /* Notices are intentionally disabled. */ }

    fun selectConversation(id: String) {
        _selectedConversationId.value = id
        viewModelScope.launch { _selectedConversation.value = dao.getConversation(id) }
        showScreen(Screen.CHAT)
    }

    fun deleteConversation(conversation: ConversationEntity) {
        viewModelScope.launch {
            dao.deleteConversation(conversation)
            if (_selectedConversationId.value == conversation.id) _selectedConversationId.value = null
        }
    }

    fun beginNewChat(characterId: String? = null) {
        viewModelScope.launch {
            _newChatCharacter.value = dao.getProfile(characterId)
            _newChatGreeting.value = _newChatCharacter.value?.greeting.orEmpty()
            _newChatPersonaId.value = null
            _newChatWorldSetIds.value = emptySet()
            showScreen(Screen.NEW_CHAT)
        }
    }

    fun selectNewChatPersona(id: String?) { _newChatPersonaId.value = id }
    fun selectNewChatGreeting(greeting: String) { _newChatGreeting.value = greeting }
    fun toggleNewChatWorldSet(id: String) {
        _newChatWorldSetIds.value = _newChatWorldSetIds.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
    }

    fun createConfiguredConversation() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val character = _newChatCharacter.value
            val conversation = ConversationEntity(
                id = UUID.randomUUID().toString(),
                title = character?.name?.ifBlank { text("新對話", "新对话") } ?: text("新對話", "新对话"),
                createdAt = now,
                updatedAt = now,
                characterId = character?.id,
                personaId = _newChatPersonaId.value,
            )
            dao.upsertConversation(conversation)
            setConversationWorldSets(conversation.id, _newChatWorldSetIds.value)
            if (_newChatGreeting.value.isNotBlank()) {
                dao.upsertMessage(MessageEntity(UUID.randomUUID().toString(), conversation.id, "assistant", _newChatGreeting.value, now + 1))
            }
            _selectedConversationId.value = conversation.id
            _selectedConversation.value = conversation
            showScreen(Screen.CHAT)
        }
    }

    fun openChatInfo() {
        val id = _selectedConversationId.value ?: return
        viewModelScope.launch { _selectedConversation.value = dao.getConversation(id) }
        showScreen(Screen.CHAT_INFO)
    }

    fun updateConversationPersona(id: String?) {
        val conversation = _selectedConversation.value ?: return
        viewModelScope.launch {
            val updated = conversation.copy(personaId = id)
            dao.updateConversation(updated)
            _selectedConversation.value = updated
            showNotice(text("Persona 已更新", "Persona 已更新"))
        }
    }

    fun toggleConversationWorldSet(id: String) {
        val conversationId = _selectedConversationId.value ?: return
        viewModelScope.launch {
            val selected = dao.getConversationWorldSetIds(conversationId).toMutableSet()
            if (!selected.add(id)) selected.remove(id)
            setConversationWorldSets(conversationId, selected)
        }
    }

    fun saveConversationSummary(summary: String) {
        val conversation = _selectedConversation.value ?: return
        viewModelScope.launch {
            val updated = conversation.copy(summary = summary.trim())
            dao.updateConversation(updated)
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
            dao.updateConversation(updated)
            _selectedConversation.value = updated
        }
    }

    fun setConversationBackground(uri: Uri) {
        val conversation = _selectedConversation.value ?: return
        viewModelScope.launch {
            runCatching {
                val directory = File(getApplication<Application>().filesDir, "chat-backgrounds")
                directory.mkdirs()
                val target = File(directory, "${conversation.id}-${UUID.randomUUID()}.img")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException(text("無法讀取背景圖片。", "无法读取背景图片。"))

                conversation.backgroundImagePath.takeIf(String::isNotBlank)?.let {
                    runCatching { File(it).delete() }
                }
                val updated = conversation.copy(backgroundImagePath = target.absolutePath)
                dao.updateConversation(updated)
                _selectedConversation.value = updated
            }
                .onSuccess { showNotice(text("背景圖已更新", "背景图已更新")) }
                .onFailure { _error.value = mapError(it, text("背景圖設定失敗", "背景图设置失败")) }
        }
    }

    fun clearConversationBackground() {
        val conversation = _selectedConversation.value ?: return
        viewModelScope.launch {
            conversation.backgroundImagePath.takeIf(String::isNotBlank)?.let {
                runCatching { File(it).delete() }
            }
            val updated = conversation.copy(backgroundImagePath = "")
            dao.updateConversation(updated)
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
            dao.updateConversation(updated)
            _selectedConversation.value = updated
        }
    }

    private suspend fun setConversationWorldSets(conversationId: String, ids: Set<String>) {
        dao.clearConversationWorldSets(conversationId)
        if (ids.isNotEmpty()) dao.addConversationWorldSets(ids.map { ConversationWorldSetEntity(conversationId, it) })
    }

    fun newProfile(type: ProfileType) {
        _editingProfile.value = ProfileDraft(type = type)
        showScreen(Screen.PROFILE_EDIT)
    }

    fun editProfile(profile: ProfileEntity) {
        _editingProfile.value = profile.toDraft()
        showScreen(Screen.PROFILE_EDIT)
    }

    fun saveProfile(draft: ProfileDraft) {
        if (draft.name.isBlank()) {
            _error.value = UiError(
                text("缺少名稱", "缺少名称"),
                text("請替這份設定填寫名稱。", "请替这份设定填写名称。"),
                text("名稱會顯示在列表與聊天頁。", "名称会显示在列表与聊天页。"),
            )
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = draft.id?.let { dao.getProfile(it) }
            dao.upsertProfile(
                ProfileEntity(
                    id = draft.id ?: UUID.randomUUID().toString(),
                    type = draft.type,
                    name = draft.name.trim(),
                    summary = draft.summary.trim(),
                    personality = draft.personality.trim(),
                    background = draft.background.trim(),
                    exampleDialogue = draft.exampleDialogue.trim(),
                    greeting = draft.greeting.trim(),
                    alternateGreetingsJson = toJsonStrings(draft.alternateGreetings),
                    extraInstructions = draft.extraInstructions.trim(),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
            showNotice(text("設定已儲存", "设置已保存"))
            showScreen(if (draft.type == ProfileType.CHARACTER) Screen.CHARACTERS else Screen.LIBRARY)
        }
    }

    fun deleteProfile(profile: ProfileEntity) { viewModelScope.launch { dao.deleteProfile(profile) } }

    fun newWorldSet() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val worldSet = WorldSetEntity(
                id = UUID.randomUUID().toString(),
                name = text("未命名世界設定集", "未命名世界设定集"),
                scanDepth = 10,
                createdAt = now,
                updatedAt = now,
            )
            dao.upsertWorldSet(worldSet)
            _editingWorldSet.value = worldSet
            showScreen(Screen.WORLD_SET_EDIT)
        }
    }

    fun editWorldSet(worldSet: WorldSetEntity) {
        _editingWorldSet.value = worldSet
        showScreen(Screen.WORLD_SET_EDIT)
    }

    fun saveWorldSet(name: String, overview: String, scanDepth: Int) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = _editingWorldSet.value
            val worldSet = WorldSetEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                scanDepth = scanDepth.coerceIn(1, 100),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                overview = overview.trim(),
            )
            dao.upsertWorldSet(worldSet)
            _editingWorldSet.value = worldSet
            showNotice(text("世界設定集已儲存", "世界设定集已保存"))
        }
    }

    fun updateWorldSetMetadata(name: String, overview: String, scanDepth: Int) {
        val existing = _editingWorldSet.value ?: return
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val cleanOverview = overview.trim()
        val cleanDepth = scanDepth.coerceIn(1, 100)
        if (cleanName == existing.name && cleanOverview == existing.overview && cleanDepth == existing.scanDepth) return
        viewModelScope.launch {
            val updated = existing.copy(
                name = cleanName,
                overview = cleanOverview,
                scanDepth = cleanDepth,
                updatedAt = System.currentTimeMillis(),
            )
            dao.upsertWorldSet(updated)
            _editingWorldSet.value = updated
        }
    }

    fun createWorldTemplate(template: WorldTemplate) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val worldSet = WorldSetEntity(
                id = UUID.randomUUID().toString(),
                name = template.name,
                scanDepth = 10,
                createdAt = now,
                updatedAt = now,
            )
            dao.upsertWorldSet(worldSet)
            template.categories.forEachIndexed { index, category ->
                dao.upsertWorldEntry(
                    WorldEntryEntity(
                        id = UUID.randomUUID().toString(),
                        worldSetId = worldSet.id,
                        title = category,
                        keywordsJson = "[]",
                        content = text("請在這裡填寫$category。", "请在这里填写$category。"),
                        enabled = false,
                        sortOrder = index,
                    ),
                )
            }
            _editingWorldSet.value = worldSet
            showScreen(Screen.WORLD_SET_EDIT)
        }
    }

    fun saveWorldEntry(id: String?, title: String, keywords: String, content: String, always: Boolean, enabled: Boolean) {
        val worldSet = _editingWorldSet.value ?: return
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            dao.upsertWorldEntry(
                WorldEntryEntity(
                    id = id ?: UUID.randomUUID().toString(),
                    worldSetId = worldSet.id,
                    title = title.trim(),
                    keywordsJson = toJsonStrings(keywords.split(',').map(String::trim).filter(String::isNotBlank)),
                    content = content.trim(),
                    alwaysInclude = always,
                    enabled = enabled,
                ),
            )
        }
    }

    fun deleteWorldSet(worldSet: WorldSetEntity) { viewModelScope.launch { dao.deleteWorldSet(worldSet) } }
    fun deleteWorldEntry(entry: WorldEntryEntity) { viewModelScope.launch { dao.deleteWorldEntry(entry) } }

    fun importDocument(uri: Uri, target: ImportTarget) {
        viewModelScope.launch {
            runCatching { readImportedDocument(getApplication(), uri) }
                .onSuccess { document ->
                    val type = target.profileType()
                    val directDraft = if (type != null && document.name.endsWith(".json", true)) {
                        profileDraftFromOwnJson(document.text, type)
                    } else null
                    if (directDraft != null) {
                        _editingProfile.value = directDraft
                        showScreen(Screen.PROFILE_EDIT)
                        showNotice(text("已讀取 JSON，請確認內容", "已读取 JSON，请确认内容"))
                    } else {
                        _pendingImport.value = PendingDocumentImport(target, document)
                    }
                }
                .onFailure { _error.value = mapError(it, text("無法匯入文件", "无法导入文件")) }
        }
    }

    fun dismissPendingImport() { _pendingImport.value = null }

    fun confirmPendingImport() {
        val pending = _pendingImport.value ?: return
        val current = settings.value
        val apiKey = secretStore.get(current.provider)
        if (apiKey.isBlank() || current.resolvedBaseUrl.isBlank()) {
            _pendingImport.value = null
            _error.value = UiError(
                text("缺少 API 設定", "缺少 API 设置"),
                text("AI 整理文件需要目前供應商的 API Key 與網址。", "AI 整理文件需要当前供应商的 API Key 与网址。"),
                text("請先前往設定頁完成 API 設定。", "请先前往设置页完成 API 设置。"),
            )
            return
        }
        _pendingImport.value = null
        _isImporting.value = true
        viewModelScope.launch {
            runCatching {
                when (pending.target) {
                    ImportTarget.CHARACTER, ImportTarget.PERSONA -> {
                        _editingProfile.value = organizeProfile(pending.document.text, pending.target.profileType()!!, current, apiKey)
                        showScreen(Screen.PROFILE_EDIT)
                    }
                    ImportTarget.WORLD_SET -> {
                        val draft = organizeWorldSet(pending.document.text, current, apiKey)
                        saveImportedWorldSet(draft)
                    }
                }
            }.onFailure { _error.value = mapError(it, text("AI 整理失敗", "AI 整理失败")) }
            _isImporting.value = false
        }
    }

    private suspend fun organizeProfile(text: String, type: ProfileType, settings: AppSettings, key: String): ProfileDraft {
        val notes = organizeChunks(text, settings, key, settings.language.organizeProfileInstruction())
        val label = if (type == ProfileType.CHARACTER) {
            settings.language.pick("AI 要扮演的角色", "AI 要扮演的角色")
        } else {
            settings.language.pick("使用者身份 Persona", "用户身份 Persona")
        }
        val reply = api.completeChat(settings, key, listOf(
            ApiChatMessage("system", settings.language.profileJsonInstruction(label)),
            ApiChatMessage("user", notes),
        ))
        return parseAiProfileDraft(reply, type)
    }

    private suspend fun organizeWorldSet(text: String, settings: AppSettings, key: String): WorldSetDraft {
        val notes = organizeChunks(text, settings, key, settings.language.organizeWorldInstruction())
        val reply = api.completeChat(settings, key, listOf(
            ApiChatMessage("system", settings.language.worldJsonInstruction()),
            ApiChatMessage("user", notes),
        ))
        return parseAiWorldSetDraft(reply)
    }

    private suspend fun organizeChunks(text: String, settings: AppSettings, key: String, instruction: String): String {
        val chunks = text.chunked(IMPORT_CHUNK_SIZE)
        if (chunks.size == 1) return text
        return chunks.mapIndexed { index, chunk ->
            api.completeChat(settings, key, listOf(
                ApiChatMessage("system", settings.language.chunkInstruction(instruction, index, chunks.size)),
                ApiChatMessage("user", chunk),
            ))
        }.joinToString("\n\n")
    }

    private suspend fun saveImportedWorldSet(draft: WorldSetDraft) {
        val now = System.currentTimeMillis()
        val worldSet = WorldSetEntity(
            id = UUID.randomUUID().toString(),
            name = draft.name.ifBlank { text("匯入的世界設定", "导入的世界设定") },
            scanDepth = 10,
            createdAt = now,
            updatedAt = now,
            overview = draft.overview.trim(),
        )
        dao.upsertWorldSet(worldSet)
        draft.entries.forEachIndexed { index, entry ->
            dao.upsertWorldEntry(
                WorldEntryEntity(
                    id = UUID.randomUUID().toString(),
                    worldSetId = worldSet.id,
                    title = entry.title,
                    keywordsJson = toJsonStrings(entry.keywords),
                    content = entry.content,
                    alwaysInclude = entry.alwaysInclude,
                    sortOrder = index,
                ),
            )
        }
        _editingWorldSet.value = worldSet
        showScreen(Screen.WORLD_SET_EDIT)
        showNotice(text("AI 已整理世界設定，請確認內容", "AI 已整理世界设定，请确认内容"))
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
            val message = dao.getMessage(messageId) ?: return@launch
            val conversation = dao.getConversation(message.conversationId) ?: return@launch
            dao.updateMessage(message.copy(content = trimmed))
            dao.updateConversation(
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
                beginNewChat()
                _input.value = content
                showNotice(text("請先確認 Persona 與世界設定", "请先确认 Persona 与世界设定"))
                return@launch
            }
            val now = System.currentTimeMillis()
            dao.upsertMessage(MessageEntity(UUID.randomUUID().toString(), conversationId, "user", content, now))
            dao.getConversation(conversationId)?.let { dao.updateConversation(it.copy(updatedAt = now)) }
            streamConversation(conversationId)
        }
    }

    private fun startRetry() {
        val conversationId = _selectedConversationId.value ?: return
        streamJob = viewModelScope.launch {
            dao.getMessages(conversationId).lastOrNull()?.takeIf { it.role == "assistant" }?.let { dao.deleteMessage(it.id) }
            streamConversation(conversationId)
        }
    }

    private fun startResendFromMessage(messageId: String?) {
        if (messageId == null) return
        streamJob = viewModelScope.launch {
            val message = dao.getMessage(messageId) ?: return@launch
            val conversation = dao.getConversation(message.conversationId) ?: return@launch
            if (message.role == "assistant") {
                dao.deleteMessagesAtOrAfter(message.conversationId, message.createdAt)
            } else {
                dao.deleteMessagesAfter(message.conversationId, message.createdAt)
            }
            dao.updateConversation(
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
        val conversation = dao.getConversation(conversationId) ?: return
        val history = dao.getMessages(conversationId)
        val worldIds = dao.getConversationWorldSetIds(conversationId)
        val worldSets = if (worldIds.isEmpty()) emptyList() else dao.getWorldSets(worldIds)
        val entries = if (worldIds.isEmpty()) emptyList() else dao.getWorldEntries(worldIds)
        val prompt = composePrompt(conversation, history, dao.getProfile(conversation.characterId), dao.getProfile(conversation.personaId), worldSets, entries, current.language)
        val assistant = MessageEntity(UUID.randomUUID().toString(), conversationId, "assistant", "", System.currentTimeMillis() + 1)
        dao.upsertMessage(assistant)
        _isStreaming.value = true
        var content = ""
        try {
            api.streamChat(current, key, prompt.messages) { token ->
                content += token
                dao.upsertMessage(assistant.copy(content = content))
            }
            if (content.isBlank()) throw IOException(current.language.pick("API 沒有回傳文字內容。", "API 没有返回文字内容。"))
            dao.upsertGenerationContext(GenerationContextEntity(assistant.id, toJsonStrings(prompt.activatedEntries.map { it.title })))
        } catch (_: CancellationException) {
            if (content.isBlank()) dao.deleteMessage(assistant.id)
        } catch (error: Throwable) {
            dao.deleteMessage(assistant.id)
            if (allowAutoSummary && error is ApiException && error.isContextLengthError) {
                runCatching { summarizeConversation(conversationId, current, key, keepRecentMessages = 8, mode = ManualSummaryMode.UN_SUMMARIZED) }
                    .onSuccess {
                        showNotice(current.language.pick("已摘要較早對話，正在重試", "已摘要较早对话，正在重试"))
                        streamConversation(conversationId, allowAutoSummary = false)
                    }
                    .onFailure { _error.value = mapError(it, current.language.pick("自動摘要失敗", "自动摘要失败")) }
            } else {
                _error.value = mapError(error, current.language.pick("生成失敗", "生成失败"))
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
                summarizeConversation(id, current, key, keepRecentMessages.coerceIn(1, 100), mode)
            } catch (error: Throwable) {
                _error.value = mapError(error, current.language.pick("手動壓縮失敗", "手动压缩失败"))
            } finally {
                _isSummarizingConversation.value = false
            }
        }
    }

    private suspend fun summarizeConversation(
        conversationId: String,
        settings: AppSettings,
        key: String,
        keepRecentMessages: Int,
        mode: ManualSummaryMode,
    ) {
        val conversation = dao.getConversation(conversationId) ?: return
        val history = dao.getMessages(conversationId).filter { it.content.isNotBlank() }
        val plan = conversationSummaryPlan(conversation, history, keepRecentMessages, mode)
        val older = plan.messagesToSummarize
        if (older.isEmpty()) throw IOException(settings.language.pick("目前沒有足夠的較早訊息可以摘要。", "目前没有足够的较早消息可以摘要。"))
        val text = buildString {
            if (plan.existingSummary.isNotBlank()) appendLine("既有摘要：\n${plan.existingSummary}\n")
            older.forEach { appendLine("${it.role}: ${it.content}") }
        }
        val summaries = text.chunked(IMPORT_CHUNK_SIZE).map { chunk ->
            api.completeChat(settings, key, listOf(
                ApiChatMessage("system", settings.language.summarizeSystemInstruction()),
                ApiChatMessage("user", chunk),
            ))
        }
        val summary = if (summaries.size == 1) summaries.single() else api.completeChat(settings, key, listOf(
            ApiChatMessage("system", settings.language.mergeSummaryInstruction()),
            ApiChatMessage("user", summaries.joinToString("\n\n")),
        ))
        val updated = conversation.copy(summary = summary.trim(), summaryThroughAt = plan.summaryThroughAt)
        dao.updateConversation(updated)
        _selectedConversation.value = updated
    }

    fun trimOldestContextAndRetry() {
        val id = _selectedConversationId.value ?: return
        clearError()
        streamJob = viewModelScope.launch {
            val conversation = dao.getConversation(id) ?: return@launch
            val history = dao.getMessages(id).filter { it.content.isNotBlank() }
            if (history.size <= 2) return@launch
            val kept = history.drop(history.size / 2).first()
            dao.updateConversation(conversation.copy(contextStartAt = kept.createdAt))
            showNotice(text("已裁切較早訊息", "已裁切较早消息"))
            streamConversation(id, allowAutoSummary = false)
        }
    }

    fun saveSettings(provider: Provider, baseUrl: String, model: String, apiKey: String, darkTheme: Boolean, language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.save(AppSettings(provider, baseUrl.trim(), model.ifBlank { provider.defaultModel }.trim(), darkTheme, language))
            if (apiKey.isNotBlank()) secretStore.put(provider, apiKey.trim())
            showNotice(language.pick("設定已儲存", "设置已保存"))
            showScreen(Screen.CONVERSATIONS)
        }
    }
    fun currentApiKey(provider: Provider): String = secretStore.get(provider)
    fun refreshModels() {
        if (_isLoadingModels.value) return
        viewModelScope.launch {
            val current = settings.value
            val key = secretStore.get(current.provider)
            if (key.isBlank()) {
                _error.value = UiError(
                    current.language.pick("缺少 API Key", "缺少 API Key"),
                    current.language.pick("請先填入 API Key。", "请先填入 API Key。"),
                    current.language.pick("前往設定頁完成設定。", "前往设置页完成设置。"),
                )
                return@launch
            }
            _isLoadingModels.value = true
            runCatching { api.listModels(current, key) }
                .onSuccess { _models.value = it }
                .onFailure { _error.value = mapError(it, current.language.pick("無法載入模型", "无法载入模型")) }
            _isLoadingModels.value = false
        }
    }
    fun chooseModel(model: String) {
        viewModelScope.launch {
            settingsRepository.save(settings.value.copy(model = model))
            showNotice(settings.value.language.pick("已選擇 $model", "已选择 $model"))
            showScreen(Screen.CHAT)
        }
    }

    private fun mapError(error: Throwable, title: String): UiError = when (error) {
        is ApiException -> when {
            error.isContextLengthError -> UiError(text("上下文過長", "上下文过长"), text("API 回報 ${error.statusCode}：${error.message}", "API 返回 ${error.statusCode}：${error.message}"), text("可裁切舊訊息並重試，或建立新對話。", "可裁切旧消息并重试，或建立新对话。"), ErrorKind.CONTEXT_LENGTH)
            error.statusCode == 401 || error.statusCode == 403 -> UiError(text("API Key 無效", "API Key 无效"), text("API 回報 ${error.statusCode}：${error.message}", "API 返回 ${error.statusCode}：${error.message}"), text("請檢查 Key、模型與供應商設定。", "请检查 Key、模型与供应商设置。"))
            error.statusCode == 429 -> UiError(text("額度不足或請求過快", "额度不足或请求过快"), text("API 回報 429：${error.message}", "API 返回 429：${error.message}"), text("請稍後再試，或切換模型與供應商。", "请稍后再试，或切换模型与供应商。"))
            else -> UiError(title, text("API 回報 ${error.statusCode}：${error.message}", "API 返回 ${error.statusCode}：${error.message}"), text("請檢查模型與供應商設定。", "请检查模型与供应商设置。"))
        }
        is IOException -> UiError(title, error.message ?: text("網路連線失敗。", "网络连接失败。"), text("請檢查網路與 API 設定。", "请检查网络与 API 设置。"))
        else -> UiError(title, error.message ?: text("發生未知錯誤。", "发生未知错误。"), text("請稍後再試。", "请稍后再试。"))
    }
}

private fun ImportTarget.profileType(): ProfileType? = when (this) {
    ImportTarget.CHARACTER -> ProfileType.CHARACTER
    ImportTarget.PERSONA -> ProfileType.PERSONA
    ImportTarget.WORLD_SET -> null
}

private fun ProfileEntity.toDraft() = ProfileDraft(
    id = id,
    type = type,
    name = name,
    summary = summary,
    personality = personality,
    background = background,
    exampleDialogue = exampleDialogue,
    greeting = greeting,
    alternateGreetings = jsonStrings(alternateGreetingsJson),
    extraInstructions = extraInstructions,
)

data class ConversationSummaryPlan(
    val messagesToSummarize: List<MessageEntity>,
    val existingSummary: String,
    val summaryThroughAt: Long,
)

fun conversationSummaryPlan(
    conversation: ConversationEntity,
    messages: List<MessageEntity>,
    keepRecentMessages: Int,
    mode: ManualSummaryMode,
): ConversationSummaryPlan {
    val keepCount = keepRecentMessages.coerceIn(1, 100)
    val nonBlank = messages.filter { it.content.isNotBlank() }.sortedBy { it.createdAt }
    val candidates = when (mode) {
        ManualSummaryMode.UN_SUMMARIZED -> nonBlank.filter { it.createdAt > conversation.summaryThroughAt }
        ManualSummaryMode.REBUILD_ALL -> nonBlank
    }
    val messagesToSummarize = candidates.dropLast(keepCount)
    val summaryThroughAt = messagesToSummarize.lastOrNull()?.createdAt ?: conversation.summaryThroughAt
    val existingSummary = when (mode) {
        ManualSummaryMode.UN_SUMMARIZED -> conversation.summary
        ManualSummaryMode.REBUILD_ALL -> ""
    }
    return ConversationSummaryPlan(messagesToSummarize, existingSummary, summaryThroughAt)
}

private val WORLD_TEMPLATE_CATEGORIES = listOf(
    "時代科技",
    "主要舞台",
    "核心衝突",
    "勢力陣營",
    "力量資源",
    "社會規則/禁忌",
    "角色相關歷史",
)

private val DEFAULT_WORLD_TEMPLATES = listOf(
    WorldTemplate("奇幻世界模板", WORLD_TEMPLATE_CATEGORIES),
    WorldTemplate("科幻世界模板", WORLD_TEMPLATE_CATEGORIES),
    WorldTemplate("現代都市模板", WORLD_TEMPLATE_CATEGORIES),
    WorldTemplate("架空史詩模板", WORLD_TEMPLATE_CATEGORIES),
)
