package com.yang136.sshhelper.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

const val DEFAULT_TERMINAL_FONT_SIZE = 14
const val MIN_TERMINAL_FONT_SIZE = 10
const val MAX_TERMINAL_FONT_SIZE = 28

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ThemePreset { OCEAN, EMERALD, AMBER, VIOLET }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themePreset: ThemePreset = ThemePreset.OCEAN,
    val terminalFontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    val extraKeys: List<ExtraKeyId> = DEFAULT_EXTRA_KEYS,
    val aiBaseUrl: String = "https://api.deepseek.com/v1",
    val aiApiKey: String = "",
    val aiModel: String = "deepseek-chat",
    val aiSendContext: Boolean = true,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setThemePreset(preset: ThemePreset)
    suspend fun setTerminalFontSize(size: Int)
    suspend fun setExtraKeys(keys: List<ExtraKeyId>)
    suspend fun setAiBaseUrl(url: String)
    suspend fun setAiApiKey(key: String)
    suspend fun setAiModel(model: String)
    suspend fun setAiSendContext(enabled: Boolean)
}

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

class DataStoreSettingsRepository(context: Context) : SettingsRepository {
    private val dataStore = context.applicationContext.settingsDataStore

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences ->
            AppSettings(
                themeMode = enumValueOrDefault(preferences[THEME_MODE], ThemeMode.SYSTEM),
                themePreset = enumValueOrDefault(preferences[THEME_PRESET], ThemePreset.OCEAN),
                terminalFontSize = sanitizeTerminalFontSize(
                    preferences[TERMINAL_FONT_SIZE] ?: DEFAULT_TERMINAL_FONT_SIZE,
                ),
                extraKeys = decodeExtraKeys(preferences[EXTRA_KEYS]),
                aiBaseUrl = preferences[AI_BASE_URL] ?: "https://api.deepseek.com/v1",
                aiApiKey = preferences[AI_API_KEY].orEmpty(),
                aiModel = preferences[AI_MODEL] ?: "deepseek-chat",
                aiSendContext = preferences[AI_SEND_CONTEXT] ?: true,
            )
        }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    override suspend fun setThemePreset(preset: ThemePreset) {
        dataStore.edit { it[THEME_PRESET] = preset.name }
    }

    override suspend fun setTerminalFontSize(size: Int) {
        dataStore.edit { it[TERMINAL_FONT_SIZE] = sanitizeTerminalFontSize(size) }
    }

    override suspend fun setExtraKeys(keys: List<ExtraKeyId>) {
        dataStore.edit { it[EXTRA_KEYS] = sanitizeExtraKeys(keys).joinToString(",", transform = ExtraKeyId::name) }
    }

    override suspend fun setAiBaseUrl(url: String) {
        dataStore.edit { it[AI_BASE_URL] = url.trim().trimEnd('/') }
    }

    override suspend fun setAiApiKey(key: String) {
        dataStore.edit { it[AI_API_KEY] = key.trim() }
    }

    override suspend fun setAiModel(model: String) {
        dataStore.edit { it[AI_MODEL] = model.trim() }
    }

    override suspend fun setAiSendContext(enabled: Boolean) {
        dataStore.edit { it[AI_SEND_CONTEXT] = enabled }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_PRESET = stringPreferencesKey("theme_preset")
        val TERMINAL_FONT_SIZE = intPreferencesKey("terminal_font_size")
        val EXTRA_KEYS = stringPreferencesKey("extra_keys")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_SEND_CONTEXT = booleanPreferencesKey("ai_send_context")
    }
}

internal fun sanitizeTerminalFontSize(size: Int): Int =
    size.coerceIn(MIN_TERMINAL_FONT_SIZE, MAX_TERMINAL_FONT_SIZE)

internal inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
    value?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: fallback
