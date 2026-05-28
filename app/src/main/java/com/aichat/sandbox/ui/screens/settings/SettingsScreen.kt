package com.aichat.sandbox.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichat.sandbox.data.model.ApiProvider
import com.aichat.sandbox.data.model.ChatSettings
import com.aichat.sandbox.ui.components.ModelSelector
import com.aichat.sandbox.ui.components.SettingsSlider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val allModels = viewModel.getAllModels()
    val customModels = viewModel.getCustomModelsFlat()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 24.dp)
            )

        // API Configuration — one key + base URL per provider. The provider
        // is auto-selected from the chosen model, so all three can be used
        // interchangeably from the model picker.
        Text(
            text = "API Configuration",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        ProviderCredentialsSection(
            providerName = ApiProvider.OpenAI.name,
            apiKey = uiState.openAiApiKey,
            onApiKeyChange = { viewModel.setProviderApiKey(ApiProvider.OpenAI.name, it) },
            baseUrl = uiState.openAiBaseUrl,
            onBaseUrlChange = { viewModel.setProviderBaseUrl(ApiProvider.OpenAI.name, it) },
            baseUrlError = uiState.openAiBaseUrlError,
        )

        Spacer(modifier = Modifier.height(20.dp))

        ProviderCredentialsSection(
            providerName = ApiProvider.Anthropic.name,
            apiKey = uiState.anthropicApiKey,
            onApiKeyChange = { viewModel.setProviderApiKey(ApiProvider.Anthropic.name, it) },
            baseUrl = uiState.anthropicBaseUrl,
            onBaseUrlChange = { viewModel.setProviderBaseUrl(ApiProvider.Anthropic.name, it) },
            baseUrlError = uiState.anthropicBaseUrlError,
        )

        Spacer(modifier = Modifier.height(20.dp))

        ProviderCredentialsSection(
            providerName = ApiProvider.Google.name,
            apiKey = uiState.googleApiKey,
            onApiKeyChange = { viewModel.setProviderApiKey(ApiProvider.Google.name, it) },
            baseUrl = uiState.googleBaseUrl,
            onBaseUrlChange = { viewModel.setProviderBaseUrl(ApiProvider.Google.name, it) },
            baseUrlError = uiState.googleBaseUrlError,
        )

            Spacer(modifier = Modifier.height(24.dp))

        // Default Parameters
        Text(
            text = "Default Parameters",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Model
        ModelSelector(
            label = "Default Model",
            selectedModel = uiState.defaultModel,
            models = allModels,
            onModelSelected = { viewModel.setDefaultModel(it) },
            customModels = customModels,
            onAddCustomModel = { viewModel.addCustomModel(it) },
            onRemoveCustomModel = { viewModel.removeCustomModel(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Temperature
        SettingsSlider(
            label = "Temperature",
            value = uiState.defaultTemperature,
            valueRange = 0f..2f,
            onValueChange = { viewModel.setDefaultTemperature(it) },
            displayFormat = { String.format("%.1f", it) }
        )

        // Top P
        SettingsSlider(
            label = "Top P",
            value = uiState.defaultTopP,
            valueRange = 0f..1f,
            onValueChange = { viewModel.setDefaultTopP(it) },
            displayFormat = { String.format("%.1f", it) }
        )

        // Max Tokens
        SettingsSlider(
            label = "Max Tokens",
            value = uiState.defaultMaxTokens.toFloat(),
            valueRange = 1f..ChatSettings.Defaults.MAX_TOKENS_LIMIT,
            onValueChange = { viewModel.setDefaultMaxTokens(it.toInt()) },
            displayFormat = { it.toInt().toString() }
        )

        // Presence Penalty
        SettingsSlider(
            label = "Presence Penalty",
            value = uiState.defaultPresencePenalty,
            valueRange = -2f..2f,
            onValueChange = { viewModel.setDefaultPresencePenalty(it) },
            displayFormat = { String.format("%.1f", it) }
        )

        // Frequency Penalty
        SettingsSlider(
            label = "Frequency Penalty",
            value = uiState.defaultFrequencyPenalty,
            valueRange = -2f..2f,
            onValueChange = { viewModel.setDefaultFrequencyPenalty(it) },
            displayFormat = { String.format("%.1f", it) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Appearance
        Text(
            text = "Appearance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Dark Mode", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = uiState.darkMode,
                onCheckedChange = { viewModel.setDarkMode(it) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // About
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "AI Chat Sandbox v1.0.0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "An open-source AI chat client supporting multiple API providers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProviderCredentialsSection(
    providerName: String,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    baseUrlError: String?,
) {
    var showApiKey by remember { mutableStateOf(false) }

    Text(
        text = providerName,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text("$providerName API Key") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (showApiKey) VisualTransformation.None
                               else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showApiKey = !showApiKey }) {
                Icon(
                    if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = "Toggle visibility"
                )
            }
        }
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = baseUrl,
        onValueChange = onBaseUrlChange,
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = baseUrlError != null,
        supportingText = baseUrlError?.let { error ->
            { Text(error, color = MaterialTheme.colorScheme.error) }
        }
    )
}
