package com.aichat.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.app.data.AppLanguage
import com.aichat.app.data.AppSettings
import com.aichat.app.data.CustomEndpointPreset
import com.aichat.app.data.Provider
import com.aichat.app.data.SecretStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val customEndpointPresets: List<CustomEndpointPreset> = emptyList(),
    val models: List<String> = emptyList(),
    val isLoadingModels: Boolean = false,
    val error: UiError? = null,
    val savedKeyIds: Set<String> = emptySet(),
)

class SettingsViewModel(appContainer: AppContainer) : ViewModel() {
    private val settingsRepository = appContainer.settingsRepository
    private val secretStore = appContainer.secretStore
    private val listModels by lazy { appContainer.listModelsUseCase }

    private val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val customEndpointPresets = settingsRepository.customEndpointPresets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _models = MutableStateFlow<List<String>>(emptyList())
    private val _isLoadingModels = MutableStateFlow(false)
    private val _error = MutableStateFlow<UiError?>(null)
    private val _savedKeyIds = MutableStateFlow<Set<String>>(emptySet())
    private val _navigationEvents = MutableSharedFlow<Screen>()
    val navigationEvents = _navigationEvents.asSharedFlow()
    private val endpointConfiguration = combine(settings, customEndpointPresets, _savedKeyIds) { settings, presets, savedKeyIds ->
        Triple(settings, presets, savedKeyIds)
    }
    val uiState = combine(endpointConfiguration, _models, _isLoadingModels, _error) { configuration, models, isLoadingModels, error ->
        SettingsUiState(configuration.first, configuration.second, models, isLoadingModels, error, configuration.third)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    init {
        viewModelScope.launch { _savedKeyIds.value = secretStore.savedKeyIds() }
    }

    fun clearError() { _error.value = null }

    fun openApiSettings() {
        viewModelScope.launch {
            _navigationEvents.emit(Screen.API_SETTINGS)
        }
    }

    fun closeApiSettings() {
        viewModelScope.launch {
            _navigationEvents.emit(Screen.SETTINGS)
        }
    }

    fun saveAppearanceSettings(darkTheme: Boolean, language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.save(settings.value.copy(darkTheme = darkTheme, language = language))
        }
    }

    fun saveBuiltInEndpoint(provider: Provider, apiKey: String, makeActive: Boolean) {
        if (provider == Provider.CUSTOM) return
        viewModelScope.launch {
            val current = settings.value
            if (apiKey.isNotBlank()) {
                secretStore.put(provider, apiKey.trim())
                _savedKeyIds.update { it + SecretStore.providerStorageKey(provider) }
            }
            if (makeActive) {
                settingsRepository.save(current.copy(provider = provider))
                _navigationEvents.emit(Screen.SETTINGS)
            }
        }
    }

    fun saveCustomEndpoint(id: String, name: String, baseUrl: String, apiKey: String, makeActive: Boolean) {
        viewModelScope.launch {
            val preset = CustomEndpointPreset(id, name.trim(), baseUrl.trim())
            settingsRepository.saveCustomEndpointPreset(preset)
            if (apiKey.isNotBlank()) {
                secretStore.putCustomEndpointPreset(id, apiKey.trim())
                _savedKeyIds.update { it + SecretStore.customEndpointStorageKey(id) }
            }
            if (makeActive) {
                val savedKey = if (apiKey.isNotBlank()) apiKey.trim() else secretStore.getCustomEndpointPreset(id)
                if (savedKey.isNotBlank()) secretStore.put(Provider.CUSTOM, savedKey)
                settingsRepository.save(settings.value.copy(provider = Provider.CUSTOM, customBaseUrl = preset.baseUrl.trimEnd('/')))
                _navigationEvents.emit(Screen.SETTINGS)
            }
        }
    }

    fun deleteCustomEndpoint(id: String) {
        viewModelScope.launch {
            val preset = customEndpointPresets.value.firstOrNull { it.id == id }
            settingsRepository.deleteCustomEndpointPreset(id)
            secretStore.removeCustomEndpointPreset(id)
            _savedKeyIds.update { it - SecretStore.customEndpointStorageKey(id) }
            if (settings.value.provider == Provider.CUSTOM && preset?.baseUrl?.trimEnd('/') == settings.value.customBaseUrl.trimEnd('/')) {
                settingsRepository.save(settings.value.copy(provider = Provider.OPENROUTER, customBaseUrl = ""))
            }
        }
    }

    fun refreshModels() {
        if (_isLoadingModels.value) return
        viewModelScope.launch {
            val current = settings.value
            val key = secretStore.get(current.provider)
            if (key.isBlank()) {
                _error.value = UiError(
                    current.language.pick("缺少 API Key", "缺少 API Key"),
                    current.language.pick("請先輸入 API Key。", "请先输入 API Key。"),
                    current.language.pick("前往設定頁完成後再試。", "前往设置页完成后再试。"),
                )
                return@launch
            }
            _isLoadingModels.value = true
            runCatching { listModels(current, key) }
                .onSuccess { _models.value = it }
                .onFailure { _error.value = mapError(it, current.language.pick("無法載入模型", "无法载入模型"), current.language) }
            _isLoadingModels.value = false
        }
    }

    fun chooseModel(model: String) {
        viewModelScope.launch {
            settingsRepository.save(settings.value.copy(model = model))
            _navigationEvents.emit(Screen.CHAT)
        }
    }
}
