package com.aichat.sandbox.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.data.model.ApiProvider
import com.aichat.sandbox.data.model.ChatSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val openAiApiKey: String = "",
    val anthropicApiKey: String = "",
    val googleApiKey: String = "",
    val openAiBaseUrl: String = ApiProvider.OpenAI.baseUrl,
    val anthropicBaseUrl: String = ApiProvider.Anthropic.baseUrl,
    val googleBaseUrl: String = ApiProvider.Google.baseUrl,
    val openAiBaseUrlError: String? = null,
    val anthropicBaseUrlError: String? = null,
    val googleBaseUrlError: String? = null,
    val defaultModel: String = ChatSettings.Defaults.MODEL,
    val defaultTemperature: Float = ChatSettings.Defaults.TEMPERATURE,
    val defaultTopP: Float = ChatSettings.Defaults.TOP_P,
    val defaultMaxTokens: Int = ChatSettings.Defaults.MAX_TOKENS,
    val defaultPresencePenalty: Float = ChatSettings.Defaults.PRESENCE_PENALTY,
    val defaultFrequencyPenalty: Float = ChatSettings.Defaults.FREQUENCY_PENALTY,
    val darkMode: Boolean = ChatSettings.Defaults.DARK_MODE,
    val customModels: Map<String, List<String>> = emptyMap()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                preferencesManager.defaultModel,
                preferencesManager.defaultTemperature,
                preferencesManager.defaultTopP
            ) { model, temp, topP ->
                _uiState.update {
                    it.copy(
                        defaultModel = model,
                        defaultTemperature = temp,
                        defaultTopP = topP
                    )
                }
            }.collect()
        }
        viewModelScope.launch {
            combine(
                preferencesManager.openAiApiKey,
                preferencesManager.anthropicApiKey,
                preferencesManager.googleApiKey
            ) { openAi, anthropic, google ->
                _uiState.update {
                    it.copy(
                        openAiApiKey = openAi,
                        anthropicApiKey = anthropic,
                        googleApiKey = google
                    )
                }
            }.collect()
        }
        viewModelScope.launch {
            combine(
                preferencesManager.openAiBaseUrl,
                preferencesManager.anthropicBaseUrl,
                preferencesManager.googleBaseUrl
            ) { openAi, anthropic, google ->
                _uiState.update {
                    it.copy(
                        openAiBaseUrl = openAi,
                        anthropicBaseUrl = anthropic,
                        googleBaseUrl = google
                    )
                }
            }.collect()
        }
        viewModelScope.launch {
            preferencesManager.darkMode.collect { darkMode ->
                _uiState.update { it.copy(darkMode = darkMode) }
            }
        }
        viewModelScope.launch {
            combine(
                preferencesManager.defaultMaxTokens,
                preferencesManager.defaultPresencePenalty,
                preferencesManager.defaultFrequencyPenalty
            ) { maxTokens, presPenalty, freqPenalty ->
                _uiState.update {
                    it.copy(
                        defaultMaxTokens = maxTokens,
                        defaultPresencePenalty = presPenalty,
                        defaultFrequencyPenalty = freqPenalty
                    )
                }
            }.collect()
        }
        viewModelScope.launch {
            preferencesManager.customModels.collect { customModels ->
                _uiState.update { it.copy(customModels = customModels) }
            }
        }
    }

    fun setProviderApiKey(providerName: String, key: String) {
        viewModelScope.launch { preferencesManager.setProviderApiKey(providerName, key) }
    }

    fun setProviderBaseUrl(providerName: String, url: String) {
        // Optimistic local update so the field stays editable, plus inline
        // validation; only valid URLs are persisted.
        updateBaseUrlField(providerName, url)
        val error = if (url.isEmpty() || PreferencesManager.isValidApiBaseUrl(url)) null
        else "URL must be HTTPS, valid, and end with /"
        updateBaseUrlError(providerName, error)
        if (PreferencesManager.isValidApiBaseUrl(url)) {
            viewModelScope.launch { preferencesManager.setProviderBaseUrl(providerName, url) }
        }
    }

    private fun updateBaseUrlField(providerName: String, url: String) {
        _uiState.update {
            when (providerName) {
                ApiProvider.Anthropic.name -> it.copy(anthropicBaseUrl = url)
                ApiProvider.Google.name -> it.copy(googleBaseUrl = url)
                else -> it.copy(openAiBaseUrl = url)
            }
        }
    }

    private fun updateBaseUrlError(providerName: String, error: String?) {
        _uiState.update {
            when (providerName) {
                ApiProvider.Anthropic.name -> it.copy(anthropicBaseUrlError = error)
                ApiProvider.Google.name -> it.copy(googleBaseUrlError = error)
                else -> it.copy(openAiBaseUrlError = error)
            }
        }
    }

    fun setDefaultModel(model: String) {
        viewModelScope.launch { preferencesManager.setDefaultModel(model) }
    }

    fun setDefaultTemperature(temp: Float) {
        viewModelScope.launch { preferencesManager.setDefaultTemperature(temp) }
    }

    fun setDefaultTopP(topP: Float) {
        viewModelScope.launch { preferencesManager.setDefaultTopP(topP) }
    }

    fun setDefaultMaxTokens(tokens: Int) {
        viewModelScope.launch { preferencesManager.setDefaultMaxTokens(tokens) }
    }

    fun setDefaultPresencePenalty(penalty: Float) {
        viewModelScope.launch { preferencesManager.setDefaultPresencePenalty(penalty) }
    }

    fun setDefaultFrequencyPenalty(penalty: Float) {
        viewModelScope.launch { preferencesManager.setDefaultFrequencyPenalty(penalty) }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setDarkMode(enabled) }
    }

    // Custom models are stored under the OpenAI slot, which is also the
    // fallback adapter/credentials. Resolution by model id still works for
    // any custom model an existing install saved under another provider.
    fun addCustomModel(model: String) {
        viewModelScope.launch { preferencesManager.addCustomModel(ApiProvider.OpenAI.name, model) }
    }

    fun removeCustomModel(model: String) {
        val provider = uiState.value.customModels.entries
            .firstOrNull { model in it.value }?.key ?: ApiProvider.OpenAI.name
        viewModelScope.launch { preferencesManager.removeCustomModel(provider, model) }
    }

    fun getAllModels(): List<String> {
        val builtIn = ApiProvider.defaults.flatMap { it.models }
        val custom = uiState.value.customModels.values.flatten()
        return builtIn + custom.filter { it !in builtIn }
    }

    fun getCustomModelsFlat(): List<String> {
        return uiState.value.customModels.values.flatten()
    }
}
