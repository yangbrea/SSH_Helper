package com.yang136.sshhelper.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
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

enum class ThemeSource { PRESET, IMAGE }

fun parseThemeSource(storedValue: String?): ThemeSource =
    enumValueOrDefault(storedValue, ThemeSource.PRESET)

enum class ImageThemeVariant(val label: String) {
    IMMERSIVE("沉浸"),
    SOFT("柔和"),
    BRIGHT("明亮"),
}

const val DEFAULT_IMAGE_OVERLAY_STRENGTH = 0.55f
const val MIN_IMAGE_OVERLAY_STRENGTH = 0.35f
const val MAX_IMAGE_OVERLAY_STRENGTH = 0.80f

internal fun coerceImageOverlayStrength(value: Float): Float =
    value.coerceIn(MIN_IMAGE_OVERLAY_STRENGTH, MAX_IMAGE_OVERLAY_STRENGTH)

fun defaultImageOverlayStrength(variant: ImageThemeVariant): Float = when (variant) {
    ImageThemeVariant.IMMERSIVE -> 0.55f
    ImageThemeVariant.SOFT -> 0.65f
    ImageThemeVariant.BRIGHT -> 0.60f
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themePreset: ThemePreset = ThemePreset.OCEAN,
    val themeSource: ThemeSource = ThemeSource.PRESET,
    val imageThemeVariant: ImageThemeVariant = ImageThemeVariant.IMMERSIVE,
    val imageOverlayStrength: Float = DEFAULT_IMAGE_OVERLAY_STRENGTH,
    val terminalFontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    val extraKeys: List<ExtraKeyId> = DEFAULT_EXTRA_KEYS,
    val aiBaseUrl: String = "https://api.deepseek.com/v1",
    val aiApiKey: String = "",
    val aiModel: String = "deepseek-chat",
    val aiSendContext: Boolean = true,
    val aiShowBubble: Boolean = true,
    /**
     * 锁屏（保险库锁定）后，已启动的转发隧道是否保留内存中的重连凭据并继续自动重连。
     * 开启时凭据的生命周期 = 隧道生命周期；关闭时断线后必须回应用解锁才能恢复。
     */
    val forwardReconnectAfterLock: Boolean = true,
)

val AppSettings.effectiveThemeMode: ThemeMode
    get() = if (themeSource == ThemeSource.IMAGE) {
        if (imageThemeVariant == ImageThemeVariant.BRIGHT) ThemeMode.LIGHT else ThemeMode.DARK
    } else {
        themeMode
    }

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setThemePreset(preset: ThemePreset)
    suspend fun setThemeSource(source: ThemeSource)
    suspend fun setImageThemeVariant(variant: ImageThemeVariant)
    suspend fun setImageOverlayStrength(strength: Float)
    suspend fun setTerminalFontSize(size: Int)
    suspend fun setExtraKeys(keys: List<ExtraKeyId>)
    suspend fun setAiBaseUrl(url: String)
    suspend fun setAiApiKey(key: String)
    suspend fun setAiModel(model: String)
    suspend fun setAiSendContext(enabled: Boolean)
    suspend fun setAiShowBubble(enabled: Boolean)
    suspend fun setForwardReconnectAfterLock(enabled: Boolean)
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
                themeSource = enumValueOrDefault(preferences[THEME_SOURCE], ThemeSource.PRESET),
                imageThemeVariant = enumValueOrDefault(preferences[IMAGE_THEME_VARIANT], ImageThemeVariant.IMMERSIVE),
                imageOverlayStrength = coerceImageOverlayStrength(preferences[IMAGE_OVERLAY_STRENGTH] ?: DEFAULT_IMAGE_OVERLAY_STRENGTH),
                terminalFontSize = sanitizeTerminalFontSize(
                    preferences[TERMINAL_FONT_SIZE] ?: DEFAULT_TERMINAL_FONT_SIZE,
                ),
                extraKeys = decodeExtraKeys(preferences[EXTRA_KEYS]),
                aiBaseUrl = preferences[AI_BASE_URL] ?: "https://api.deepseek.com/v1",
                aiApiKey = preferences[AI_API_KEY].orEmpty(),
                aiModel = preferences[AI_MODEL] ?: "deepseek-chat",
                aiSendContext = preferences[AI_SEND_CONTEXT] ?: true,
                aiShowBubble = preferences[AI_SHOW_BUBBLE] ?: true,
                forwardReconnectAfterLock = preferences[FORWARD_RECONNECT_AFTER_LOCK] ?: true,
            )
        }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    override suspend fun setThemePreset(preset: ThemePreset) {
        dataStore.edit { it[THEME_PRESET] = preset.name }
    }

    override suspend fun setThemeSource(source: ThemeSource) {
        dataStore.edit { it[THEME_SOURCE] = source.name }
    }

    override suspend fun setImageThemeVariant(variant: ImageThemeVariant) {
        dataStore.edit { it[IMAGE_THEME_VARIANT] = variant.name }
    }

    override suspend fun setImageOverlayStrength(strength: Float) {
        dataStore.edit { it[IMAGE_OVERLAY_STRENGTH] = coerceImageOverlayStrength(strength) }
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

    override suspend fun setAiShowBubble(enabled: Boolean) {
        dataStore.edit { it[AI_SHOW_BUBBLE] = enabled }
    }

    override suspend fun setForwardReconnectAfterLock(enabled: Boolean) {
        dataStore.edit { it[FORWARD_RECONNECT_AFTER_LOCK] = enabled }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_PRESET = stringPreferencesKey("theme_preset")
        val THEME_SOURCE = stringPreferencesKey("theme_source")
        val IMAGE_THEME_VARIANT = stringPreferencesKey("image_theme_variant")
        val IMAGE_OVERLAY_STRENGTH = floatPreferencesKey("image_overlay_strength")
        val TERMINAL_FONT_SIZE = intPreferencesKey("terminal_font_size")
        val EXTRA_KEYS = stringPreferencesKey("extra_keys")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_SEND_CONTEXT = booleanPreferencesKey("ai_send_context")
        val AI_SHOW_BUBBLE = booleanPreferencesKey("ai_show_bubble")
        val FORWARD_RECONNECT_AFTER_LOCK = booleanPreferencesKey("forward_reconnect_after_lock")
    }
}

internal fun sanitizeTerminalFontSize(size: Int): Int =
    size.coerceIn(MIN_TERMINAL_FONT_SIZE, MAX_TERMINAL_FONT_SIZE)

internal inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
    value?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: fallback
