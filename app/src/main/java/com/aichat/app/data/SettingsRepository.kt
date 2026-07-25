package com.aichat.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val provider = stringPreferencesKey("provider")
        val customBaseUrl = stringPreferencesKey("custom_base_url")
        val cloudflareAccountId = stringPreferencesKey("cloudflare_account_id")
        val model = stringPreferencesKey("model")
        val darkTheme = booleanPreferencesKey("dark_theme")
        val language = stringPreferencesKey("language")
        val customEndpointPresets = stringPreferencesKey("custom_endpoint_presets")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val provider = Provider.fromId(preferences[Keys.provider])
        AppSettings(
            provider = provider,
            customBaseUrl = preferences[Keys.customBaseUrl].orEmpty(),
            cloudflareAccountId = preferences[Keys.cloudflareAccountId].orEmpty(),
            model = preferences[Keys.model] ?: provider.defaultModel,
            darkTheme = preferences[Keys.darkTheme] ?: false,
            language = AppLanguage.fromId(preferences[Keys.language]),
        )
    }

    val customEndpointPresets: Flow<List<CustomEndpointPreset>> = context.dataStore.data.map { preferences ->
        decodeCustomEndpointPresets(preferences[Keys.customEndpointPresets].orEmpty())
    }

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { preferences ->
            preferences[Keys.provider] = settings.provider.name
            preferences[Keys.customBaseUrl] = settings.customBaseUrl
            preferences[Keys.cloudflareAccountId] = settings.cloudflareAccountId
            preferences[Keys.model] = settings.model
            preferences[Keys.darkTheme] = settings.darkTheme
            preferences[Keys.language] = settings.language.name
        }
    }

    suspend fun saveCustomEndpointPreset(preset: CustomEndpointPreset) {
        context.dataStore.edit { preferences ->
            val presets = decodeCustomEndpointPresets(preferences[Keys.customEndpointPresets].orEmpty())
            val updated = upsertCustomEndpointPreset(presets, preset)
            preferences[Keys.customEndpointPresets] = encodeCustomEndpointPresets(updated)
        }
    }

    suspend fun deleteCustomEndpointPreset(id: String) {
        context.dataStore.edit { preferences ->
            val presets = removeCustomEndpointPreset(
                decodeCustomEndpointPresets(preferences[Keys.customEndpointPresets].orEmpty()),
                id,
            )
            preferences[Keys.customEndpointPresets] = encodeCustomEndpointPresets(presets)
        }
    }

    companion object {
        fun upsertCustomEndpointPreset(presets: List<CustomEndpointPreset>, preset: CustomEndpointPreset): List<CustomEndpointPreset> =
            presets
                .filterNot { it.id == preset.id }
                .plus(preset.copy(name = preset.name.trim(), baseUrl = preset.baseUrl.trimEnd('/')))

        fun removeCustomEndpointPreset(presets: List<CustomEndpointPreset>, id: String): List<CustomEndpointPreset> =
            presets.filterNot { it.id == id }

        fun encodeCustomEndpointPresets(presets: List<CustomEndpointPreset>): String {
            val array = JSONArray()
            presets.forEach {
                array.put(JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", it.baseUrl))
            }
            return array.toString()
        }

        fun decodeCustomEndpointPresets(value: String): List<CustomEndpointPreset> =
            runCatching {
                val array = JSONArray(value.ifBlank { "[]" })
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    CustomEndpointPreset(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        baseUrl = item.optString("baseUrl").trimEnd('/'),
                    )
                }.filter { it.id.isNotBlank() && it.name.isNotBlank() && it.baseUrl.isNotBlank() }
            }.getOrDefault(emptyList())
    }
}
