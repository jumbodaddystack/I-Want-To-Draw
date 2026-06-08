package com.aichat.sandbox.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.aichat.sandbox.data.model.ApiProvider
import com.aichat.sandbox.data.model.ChatSettings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Base URL + API key resolved for a given model's provider. */
data class ProviderCredentials(val baseUrl: String, val apiKey: String)

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    companion object {
        private const val TAG = "PreferencesManager"

        // Legacy single-provider credentials. Kept for backward-compat: the
        // OpenAI slot falls back to these when its per-provider value is unset.
        val API_KEY = stringPreferencesKey("api_key")
        val API_BASE_URL = stringPreferencesKey("api_base_url")
        // Per-provider credentials (key + editable base URL each).
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val ANTHROPIC_API_KEY = stringPreferencesKey("anthropic_api_key")
        val GOOGLE_API_KEY = stringPreferencesKey("google_api_key")
        val OPENAI_BASE_URL = stringPreferencesKey("openai_base_url")
        val ANTHROPIC_BASE_URL = stringPreferencesKey("anthropic_base_url")
        val GOOGLE_BASE_URL = stringPreferencesKey("google_base_url")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val DEFAULT_TEMPERATURE = floatPreferencesKey("default_temperature")
        val DEFAULT_TOP_P = floatPreferencesKey("default_top_p")
        val DEFAULT_MAX_TOKENS = intPreferencesKey("default_max_tokens")
        val DEFAULT_PRESENCE_PENALTY = floatPreferencesKey("default_presence_penalty")
        val DEFAULT_FREQUENCY_PENALTY = floatPreferencesKey("default_frequency_penalty")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val AUTO_GENERATE_TITLES = booleanPreferencesKey("auto_generate_titles")
        val CUSTOM_MODELS = stringPreferencesKey("custom_models")

        private val gson = Gson()

        fun isValidApiBaseUrl(url: String): Boolean {
            return try {
                val uri = URI(url)
                val scheme = uri.scheme?.lowercase()
                val isLocalhost = uri.host?.let {
                    it == "localhost" || it == "127.0.0.1" || it == "10.0.2.2"
                } ?: false
                (scheme == "https" || (scheme == "http" && isLocalhost)) &&
                    uri.host != null &&
                    url.endsWith("/")
            } catch (e: Exception) {
                false
            }
        }
    }

    val apiKey: Flow<String> = dataStore.data.map { it[API_KEY] ?: "" }
    val apiBaseUrl: Flow<String> = dataStore.data.map { it[API_BASE_URL] ?: ChatSettings.Defaults.API_BASE_URL }

    // Per-provider credential flows for the Settings UI. The OpenAI slot
    // falls back to the legacy single-provider values so existing installs
    // keep working without a migration step.
    val openAiApiKey: Flow<String> = dataStore.data.map { it[OPENAI_API_KEY] ?: it[API_KEY] ?: "" }
    val anthropicApiKey: Flow<String> = dataStore.data.map { it[ANTHROPIC_API_KEY] ?: "" }
    val googleApiKey: Flow<String> = dataStore.data.map { it[GOOGLE_API_KEY] ?: "" }
    val openAiBaseUrl: Flow<String> = dataStore.data.map {
        it[OPENAI_BASE_URL] ?: it[API_BASE_URL] ?: ApiProvider.OpenAI.baseUrl
    }
    val anthropicBaseUrl: Flow<String> = dataStore.data.map {
        it[ANTHROPIC_BASE_URL] ?: ApiProvider.Anthropic.baseUrl
    }
    val googleBaseUrl: Flow<String> = dataStore.data.map {
        it[GOOGLE_BASE_URL] ?: ApiProvider.Google.baseUrl
    }
    val defaultModel: Flow<String> = dataStore.data.map { prefs ->
        // Coerce stale persisted defaults (e.g. "gpt-4.1") onto a model
        // that still exists in the registry, plus any user-added custom
        // models. Avoids the selector showing a retired ID after a
        // model-list refresh and avoids hitting a removed model on send.
        val persisted = prefs[DEFAULT_MODEL] ?: return@map ChatSettings.Defaults.MODEL
        val customs = readCustomModelsFlat(prefs)
        if (persisted in ApiProvider.allKnownModels || persisted in customs) persisted
        else ChatSettings.Defaults.MODEL
    }
    val defaultTemperature: Flow<Float> = dataStore.data.map { it[DEFAULT_TEMPERATURE] ?: ChatSettings.Defaults.TEMPERATURE }
    val defaultTopP: Flow<Float> = dataStore.data.map { it[DEFAULT_TOP_P] ?: ChatSettings.Defaults.TOP_P }
    val defaultMaxTokens: Flow<Int> = dataStore.data.map { it[DEFAULT_MAX_TOKENS] ?: ChatSettings.Defaults.MAX_TOKENS }
    val defaultPresencePenalty: Flow<Float> = dataStore.data.map { it[DEFAULT_PRESENCE_PENALTY] ?: ChatSettings.Defaults.PRESENCE_PENALTY }
    val defaultFrequencyPenalty: Flow<Float> = dataStore.data.map { it[DEFAULT_FREQUENCY_PENALTY] ?: ChatSettings.Defaults.FREQUENCY_PENALTY }
    val darkMode: Flow<Boolean> = dataStore.data.map { it[DARK_MODE] ?: ChatSettings.Defaults.DARK_MODE }
    val autoGenerateTitles: Flow<Boolean> = dataStore.data.map { it[AUTO_GENERATE_TITLES] ?: true }

    val customModels: Flow<Map<String, List<String>>> = dataStore.data.map { prefs ->
        val json = prefs[CUSTOM_MODELS] ?: "{}"
        try {
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            gson.fromJson<Map<String, List<String>>>(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun readCustomModelsFlat(prefs: Preferences): Set<String> {
        val json = prefs[CUSTOM_MODELS] ?: return emptySet()
        return try {
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            gson.fromJson<Map<String, List<String>>>(json, type)
                ?.values?.flatten()?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    suspend fun addCustomModel(provider: String, model: String) {
        try {
            dataStore.edit { prefs ->
                val json = prefs[CUSTOM_MODELS] ?: "{}"
                val type = object : TypeToken<MutableMap<String, MutableList<String>>>() {}.type
                val map: MutableMap<String, MutableList<String>> = try {
                    gson.fromJson(json, type) ?: mutableMapOf()
                } catch (e: Exception) {
                    mutableMapOf()
                }
                val list = map.getOrPut(provider) { mutableListOf() }
                if (!list.contains(model)) {
                    list.add(model)
                }
                prefs[CUSTOM_MODELS] = gson.toJson(map)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add custom model", e)
        }
    }

    suspend fun removeCustomModel(provider: String, model: String) {
        try {
            dataStore.edit { prefs ->
                val json = prefs[CUSTOM_MODELS] ?: "{}"
                val type = object : TypeToken<MutableMap<String, MutableList<String>>>() {}.type
                val map: MutableMap<String, MutableList<String>> = try {
                    gson.fromJson(json, type) ?: mutableMapOf()
                } catch (e: Exception) {
                    mutableMapOf()
                }
                map[provider]?.remove(model)
                prefs[CUSTOM_MODELS] = gson.toJson(map)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove custom model", e)
        }
    }

    suspend fun setApiKey(key: String) {
        try {
            dataStore.edit { it[API_KEY] = key }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API key", e)
        }
    }

    suspend fun setApiBaseUrl(url: String): Boolean {
        if (!isValidApiBaseUrl(url)) return false
        return try {
            dataStore.edit { it[API_BASE_URL] = url }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API base URL", e)
            false
        }
    }

    suspend fun setProviderApiKey(providerName: String, key: String) {
        val prefKey = apiKeyPrefFor(providerName)
        try {
            dataStore.edit { it[prefKey] = key }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API key for $providerName", e)
        }
    }

    suspend fun setProviderBaseUrl(providerName: String, url: String): Boolean {
        if (!isValidApiBaseUrl(url)) return false
        val prefKey = baseUrlPrefFor(providerName)
        return try {
            dataStore.edit { it[prefKey] = url }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save base URL for $providerName", e)
            false
        }
    }

    /**
     * Resolves the base URL + API key to use for [modelId] by mapping the
     * model to its provider ([ApiProvider.providerNameFor]) and reading that
     * provider's stored credentials. This is the single source of truth that
     * lets all three providers be used interchangeably from the model picker.
     */
    suspend fun credentialsFor(modelId: String): ProviderCredentials {
        val prefs = dataStore.data.first()
        val provider = ApiProvider.providerNameFor(modelId, readCustomModelsMap(prefs))
        val apiKey = prefs[apiKeyPrefFor(provider)]
            ?: (if (provider == ApiProvider.OpenAI.name) prefs[API_KEY] else null)
            ?: ""
        val baseUrl = prefs[baseUrlPrefFor(provider)]
            ?: (if (provider == ApiProvider.OpenAI.name) prefs[API_BASE_URL] else null)
            ?: ApiProvider.defaultBaseUrlFor(provider)
        return ProviderCredentials(baseUrl = baseUrl, apiKey = apiKey)
    }

    /**
     * True when the provider resolved for [modelId] has a non-blank API key.
     * Lets the chat layer pre-flight a send and guide the user to Settings
     * instead of firing a request that's guaranteed to 401.
     */
    suspend fun hasApiKeyFor(modelId: String): Boolean =
        credentialsFor(modelId).apiKey.isNotBlank()

    private fun apiKeyPrefFor(providerName: String) = when (providerName) {
        ApiProvider.Anthropic.name -> ANTHROPIC_API_KEY
        ApiProvider.Google.name -> GOOGLE_API_KEY
        else -> OPENAI_API_KEY
    }

    private fun baseUrlPrefFor(providerName: String) = when (providerName) {
        ApiProvider.Anthropic.name -> ANTHROPIC_BASE_URL
        ApiProvider.Google.name -> GOOGLE_BASE_URL
        else -> OPENAI_BASE_URL
    }

    private fun readCustomModelsMap(prefs: Preferences): Map<String, List<String>> {
        val json = prefs[CUSTOM_MODELS] ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            gson.fromJson<Map<String, List<String>>>(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun setDefaultModel(model: String) {
        try {
            dataStore.edit { it[DEFAULT_MODEL] = model }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default model", e)
        }
    }

    suspend fun setDefaultTemperature(temp: Float) {
        try {
            dataStore.edit { it[DEFAULT_TEMPERATURE] = temp }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default temperature", e)
        }
    }

    suspend fun setDefaultTopP(topP: Float) {
        try {
            dataStore.edit { it[DEFAULT_TOP_P] = topP }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default top_p", e)
        }
    }

    suspend fun setDefaultMaxTokens(tokens: Int) {
        try {
            dataStore.edit { it[DEFAULT_MAX_TOKENS] = tokens }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default max tokens", e)
        }
    }

    suspend fun setDefaultPresencePenalty(penalty: Float) {
        try {
            dataStore.edit { it[DEFAULT_PRESENCE_PENALTY] = penalty }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default presence penalty", e)
        }
    }

    suspend fun setDefaultFrequencyPenalty(penalty: Float) {
        try {
            dataStore.edit { it[DEFAULT_FREQUENCY_PENALTY] = penalty }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default frequency penalty", e)
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        try {
            dataStore.edit { it[DARK_MODE] = enabled }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save dark mode preference", e)
        }
    }

    suspend fun setAutoGenerateTitles(enabled: Boolean) {
        try {
            dataStore.edit { it[AUTO_GENERATE_TITLES] = enabled }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save auto-generate titles preference", e)
        }
    }
}
