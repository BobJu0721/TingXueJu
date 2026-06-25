package com.aichat.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.app.data.AppLanguage
import com.aichat.app.data.AppSettings
import com.aichat.app.data.Provider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val models: List<String> = emptyList(),
    val isLoadingModels: Boolean = false,
    val error: UiError? = null,
)

class SettingsViewModel(appContainer: AppContainer) : ViewModel() {
    private val settingsRepository = appContainer.settingsRepository
    private val secretStore = appContainer.secretStore
    private val listModels = appContainer.listModelsUseCase

    private val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _models = MutableStateFlow<List<String>>(emptyList())
    private val _isLoadingModels = MutableStateFlow(false)
    private val _error = MutableStateFlow<UiError?>(null)
    private val _navigationEvents = MutableSharedFlow<Screen>()
    val navigationEvents = _navigationEvents.asSharedFlow()
    val uiState = combine(settings, _models, _isLoadingModels, _error) { settings, models, isLoadingModels, error ->
        SettingsUiState(settings, models, isLoadingModels, error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun clearError() { _error.value = null }

    fun saveSettings(provider: Provider, baseUrl: String, model: String, apiKey: String, darkTheme: Boolean, language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.save(AppSettings(provider, baseUrl.trim(), model.ifBlank { provider.defaultModel }.trim(), darkTheme, language))
            if (apiKey.isNotBlank()) secretStore.put(provider, apiKey.trim())
            _navigationEvents.emit(Screen.CONVERSATIONS)
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
