package com.aichat.app
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ProfileEntity
import com.aichat.app.data.ProfileType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
sealed interface ProfilesNavigation {
    data object Characters : ProfilesNavigation
    data object Library : ProfilesNavigation
    data object ProfileEdit : ProfilesNavigation
    data class NewChat(val characterId: String) : ProfilesNavigation
}
class ProfilesViewModel(private val appContainer: AppContainer) : ViewModel() {
    private val profileRepository = appContainer.profileRepository
    private val settingsRepository = appContainer.settingsRepository
    private val secretStore = appContainer.secretStore
    private val organizeProfile = appContainer.organizeProfileUseCase
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val characters = profileRepository.observeProfiles(ProfileType.CHARACTER).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val personas = profileRepository.observeProfiles(ProfileType.PERSONA).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _editingProfile = MutableStateFlow<ProfileDraft?>(null)
    val editingProfile = _editingProfile.asStateFlow()
    private val _pendingImport = MutableStateFlow<PendingDocumentImport?>(null)
    val pendingImport = _pendingImport.asStateFlow()
    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()
    private val _error = MutableStateFlow<UiError?>(null)
    val error = _error.asStateFlow()
    private val _navigationEvents = MutableSharedFlow<ProfilesNavigation>()
    val navigationEvents = _navigationEvents.asSharedFlow()
    fun clearError() { _error.value = null }
    fun dismissPendingImport() { _pendingImport.value = null }
    fun newProfile(type: ProfileType) {
        _editingProfile.value = ProfileDraft(type = type)
        navigate(ProfilesNavigation.ProfileEdit)
    }
    fun editProfile(profile: ProfileEntity) {
        _editingProfile.value = profile.toDraft()
        navigate(ProfilesNavigation.ProfileEdit)
    }
    fun closeProfileEditor(type: ProfileType) {
        navigate(if (type == ProfileType.CHARACTER) ProfilesNavigation.Characters else ProfilesNavigation.Library)
    }
    fun startChat(characterId: String) {
        navigate(ProfilesNavigation.NewChat(characterId))
    }
    fun saveProfile(draft: ProfileDraft) {
        if (draft.name.isBlank()) {
            val language = settings.value.language
            _error.value = UiError(
                language.pick("缺少名稱", "缺少名称"),
                language.pick("請替這份設定填寫名稱。", "请替这份设定填写名称。"),
                language.pick("名稱會顯示在列表與聊天頁。", "名称会显示在列表与聊天页。"),
            )
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = draft.id?.let { profileRepository.getProfile(it) }
            profileRepository.upsertProfile(
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
            _navigationEvents.emit(if (draft.type == ProfileType.CHARACTER) ProfilesNavigation.Characters else ProfilesNavigation.Library)
        }
    }
    fun deleteProfile(profile: ProfileEntity) {
        viewModelScope.launch { profileRepository.deleteProfile(profile) }
    }
    fun importDocument(uri: Uri, target: ImportTarget) {
        val type = target.profileType() ?: return
        viewModelScope.launch {
            runCatching { readImportedDocument(appContainer.appContext, uri) }
                .onSuccess { document ->
                    val directDraft = if (document.name.endsWith(".json", true)) profileDraftFromOwnJson(document.text, type) else null
                    if (directDraft != null) {
                        _editingProfile.value = directDraft
                        _navigationEvents.emit(ProfilesNavigation.ProfileEdit)
                    } else {
                        _pendingImport.value = PendingDocumentImport(target, document)
                    }
                }
                .onFailure {
                    val language = settings.value.language
                    _error.value = mapError(it, language.pick("無法匯入文件", "无法导入文件"), language)
                }
        }
    }
    fun confirmPendingImport() {
        val pending = _pendingImport.value ?: return
        val type = pending.target.profileType() ?: return
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
                _editingProfile.value = organizeProfile(pending.document.text, type, current, apiKey)
                _navigationEvents.emit(ProfilesNavigation.ProfileEdit)
            }.onFailure {
                _error.value = mapError(it, current.language.pick("AI 整理失敗", "AI 整理失败"), current.language)
            }
            _isImporting.value = false
        }
    }
    private fun navigate(event: ProfilesNavigation) {
        viewModelScope.launch { _navigationEvents.emit(event) }
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
