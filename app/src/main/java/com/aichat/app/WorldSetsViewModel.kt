package com.aichat.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.app.data.AppSettings
import com.aichat.app.data.WorldEntryEntity
import com.aichat.app.data.WorldSetEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface WorldSetsNavigation {
    data object Library : WorldSetsNavigation
    data object WorldSets : WorldSetsNavigation
    data object WorldSetEdit : WorldSetsNavigation
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorldSetsViewModel(private val appContainer: AppContainer) : ViewModel() {
    private val worldInfoRepository = appContainer.worldInfoRepository
    private val settingsRepository = appContainer.settingsRepository
    private val secretStore = appContainer.secretStore
    private val organizeWorldSet = appContainer.organizeWorldSetUseCase
    private val saveImportedWorldSet = appContainer.saveImportedWorldSetUseCase

    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val worldSets = worldInfoRepository.observeWorldSets().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val worldEntryCounts = worldInfoRepository.observeWorldEntryCounts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val worldTemplates = DEFAULT_WORLD_TEMPLATES

    private val _editingWorldSet = MutableStateFlow<WorldSetEntity?>(null)
    val editingWorldSet = _editingWorldSet.asStateFlow()
    val editingWorldEntries = _editingWorldSet.flatMapLatest { worldSet ->
        if (worldSet == null) flowOf(emptyList()) else worldInfoRepository.observeWorldEntries(worldSet.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _pendingImport = MutableStateFlow<PendingDocumentImport?>(null)
    val pendingImport = _pendingImport.asStateFlow()
    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()
    private val _error = MutableStateFlow<UiError?>(null)
    val error = _error.asStateFlow()
    private val _navigationEvents = MutableSharedFlow<WorldSetsNavigation>()
    val navigationEvents = _navigationEvents.asSharedFlow()

    private fun text(traditional: String, simplified: String): String =
        settings.value.language.pick(traditional, simplified)

    fun clearError() { _error.value = null }
    fun dismissPendingImport() { _pendingImport.value = null }

    fun openWorldSets() {
        navigate(WorldSetsNavigation.WorldSets)
    }

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
            worldInfoRepository.upsertWorldSet(worldSet)
            _editingWorldSet.value = worldSet
            _navigationEvents.emit(WorldSetsNavigation.WorldSetEdit)
        }
    }

    fun editWorldSet(worldSet: WorldSetEntity) {
        _editingWorldSet.value = worldSet
        navigate(WorldSetsNavigation.WorldSetEdit)
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
            worldInfoRepository.upsertWorldSet(worldSet)
            _editingWorldSet.value = worldSet
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
            worldInfoRepository.upsertWorldSet(updated)
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
            worldInfoRepository.upsertWorldSet(worldSet)
            template.categories.forEachIndexed { index, category ->
                worldInfoRepository.upsertWorldEntry(
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
            _navigationEvents.emit(WorldSetsNavigation.WorldSetEdit)
        }
    }

    fun saveWorldEntry(id: String?, title: String, keywords: String, content: String, always: Boolean, enabled: Boolean) {
        val worldSet = _editingWorldSet.value ?: return
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            worldInfoRepository.upsertWorldEntry(
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

    fun deleteWorldSet(worldSet: WorldSetEntity) {
        viewModelScope.launch { worldInfoRepository.deleteWorldSet(worldSet) }
    }

    fun deleteWorldEntry(entry: WorldEntryEntity) {
        viewModelScope.launch { worldInfoRepository.deleteWorldEntry(entry) }
    }

    fun importDocument(uri: Uri, target: ImportTarget) {
        if (target != ImportTarget.WORLD_SET) return
        viewModelScope.launch {
            runCatching { readImportedDocument(appContainer.appContext, uri) }
                .onSuccess { document -> _pendingImport.value = PendingDocumentImport(target, document) }
                .onFailure {
                    val language = settings.value.language
                    _error.value = mapError(it, language.pick("無法匯入文件", "无法导入文件"), language)
                }
        }
    }

    fun confirmPendingImport() {
        val pending = _pendingImport.value ?: return
        if (pending.target != ImportTarget.WORLD_SET) return
        val current = settings.value
        val apiKey = secretStore.get(current.provider)
        if (apiKey.isBlank() || current.resolvedBaseUrl.isBlank()) {
            _pendingImport.value = null
            _error.value = UiError(
                current.language.pick("缺少 API 設定", "缺少 API 设置"),
                current.language.pick("AI 整理文件需要目前供應商的 API Key 與網址。", "AI 整理文件需要当前供应商的 API Key 与网址。"),
                current.language.pick("請先前往設定頁完成 API 設定。", "请先前往设置页完成 API 设置。"),
            )
            return
        }
        _pendingImport.value = null
        _isImporting.value = true
        viewModelScope.launch {
            runCatching {
                val draft = organizeWorldSet(pending.document.text, current, apiKey)
                val worldSet = saveImportedWorldSet(draft, current.language.pick("匯入的世界設定", "导入的世界设定"))
                _editingWorldSet.value = worldSet
                _navigationEvents.emit(WorldSetsNavigation.WorldSetEdit)
            }.onFailure {
                _error.value = mapError(it, current.language.pick("AI 整理失敗", "AI 整理失败"), current.language)
            }
            _isImporting.value = false
        }
    }

    private fun navigate(event: WorldSetsNavigation) {
        viewModelScope.launch { _navigationEvents.emit(event) }
    }
}
