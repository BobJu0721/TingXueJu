package com.aichat.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val provider = stringPreferencesKey("provider")
        val customBaseUrl = stringPreferencesKey("custom_base_url")
        val model = stringPreferencesKey("model")
        val darkTheme = booleanPreferencesKey("dark_theme")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val provider = Provider.fromId(preferences[Keys.provider])
        AppSettings(
            provider = provider,
            customBaseUrl = preferences[Keys.customBaseUrl].orEmpty(),
            model = preferences[Keys.model] ?: provider.defaultModel,
            darkTheme = preferences[Keys.darkTheme] ?: false,
        )
    }

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { preferences ->
            preferences[Keys.provider] = settings.provider.name
            preferences[Keys.customBaseUrl] = settings.customBaseUrl
            preferences[Keys.model] = settings.model
            preferences[Keys.darkTheme] = settings.darkTheme
        }
    }
}

