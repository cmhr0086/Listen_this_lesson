package com.cmhr.listen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.settings.AiProvider
import com.cmhr.listen.data.settings.AiServiceSettings
import com.cmhr.listen.data.settings.AiPromptSettings
import com.cmhr.listen.data.settings.ConnectionTestResult
import com.cmhr.listen.data.settings.ServerSettings
import com.cmhr.listen.data.settings.ServerConnectionTester
import com.cmhr.listen.data.ai.AiConnectionResult
import com.cmhr.listen.data.ai.AiModelsResult
import com.cmhr.listen.data.ai.AiServiceClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class SettingsUiState(
    val developerMode: Boolean = false,
    val server: ServerSettings = ServerSettings(),
    val ai: AiServiceSettings = AiServiceSettings(),
    val aiPrompts: AiPromptSettings = AiPromptSettings(),
    val availableAiModels: List<String> = emptyList(),
    val isLoadingAiModels: Boolean = false,
    val connectionTestState: ConnectionTestState = ConnectionTestState.Idle,
    val aiConnectionTestState: ConnectionTestState = ConnectionTestState.Idle
)

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
}

data class UiMessage(val text: String)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppSettingsRepository(application)
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()
    private val messageFlow = MutableSharedFlow<UiMessage>(extraBufferCapacity = 1)
    val messages = messageFlow.asSharedFlow()
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val connectionTester = ServerConnectionTester(client)
    private val aiClient = AiServiceClient()

    init { viewModelScope.launch { repository.settings.collect { settings -> _uiState.update { it.copy(developerMode = settings.developerMode, server = settings.server, ai = settings.ai, aiPrompts = settings.aiPrompts) } } } }

    fun setDeveloperMode(enabled: Boolean) = viewModelScope.launch { repository.setDeveloperMode(enabled) }
    fun saveServer(baseUrl: String, apiKey: String?) = viewModelScope.launch {
        val result = runCatching { repository.saveServer(baseUrl, apiKey) }
        messageFlow.emit(UiMessage(result.fold({ "服务器设置已保存。" }, { it.message ?: "服务器地址无效。" })))
    }
    fun clearApiKey() = viewModelScope.launch { repository.clearApiKey(); messageFlow.emit(UiMessage("API Key 已清除。")) }
    fun saveAiService(provider: AiProvider, baseUrl: String, model: String, apiKey: String?) = viewModelScope.launch {
        val result = runCatching { repository.saveAiService(provider, baseUrl, model, apiKey) }
        messageFlow.emit(UiMessage(result.fold({ "AI 服务设置已保存。" }, { it.message ?: "AI 服务设置无效。" })))
    }
    fun clearAiApiKey() = viewModelScope.launch {
        repository.clearAiApiKey()
        messageFlow.emit(UiMessage("AI API Key 已清除。"))
    }
    fun saveAiPrompts(value: AiPromptSettings) = viewModelScope.launch {
        val result = runCatching { repository.saveAiPrompts(value) }
        messageFlow.emit(UiMessage(result.fold({ "AI 提示词已保存。" }, { it.message ?: "AI 提示词无效。" })))
    }
    fun restoreDefaultAiPrompts() = viewModelScope.launch {
        repository.restoreDefaultAiPrompts()
        messageFlow.emit(UiMessage("AI 提示词已恢复默认值。"))
    }
    fun testAiConnection(baseUrl: String, apiKeyInput: String) = viewModelScope.launch {
        _uiState.update { it.copy(aiConnectionTestState = ConnectionTestState.Testing) }
        val key = apiKeyInput.trim().takeIf { it.isNotEmpty() }
            ?: runCatching { repository.readAiApiKey().orEmpty() }.getOrDefault("")
        when (val result = aiClient.testConnection(baseUrl, key)) {
            is AiConnectionResult.Success -> messageFlow.emit(UiMessage("AI 服务连接成功（HTTP ${result.statusCode}）。"))
            is AiConnectionResult.Failure -> messageFlow.emit(UiMessage(result.message))
        }
        _uiState.update { it.copy(aiConnectionTestState = ConnectionTestState.Idle) }
    }
    fun fetchAiModels(baseUrl: String, apiKeyInput: String) = viewModelScope.launch {
        if (_uiState.value.isLoadingAiModels) return@launch
        _uiState.update { it.copy(isLoadingAiModels = true) }
        val key = apiKeyInput.trim().takeIf { it.isNotEmpty() }
            ?: runCatching { repository.readAiApiKey().orEmpty() }.getOrDefault("")
        when (val result = aiClient.fetchModels(baseUrl, key)) {
            is AiModelsResult.Success -> {
                _uiState.update { it.copy(availableAiModels = result.models) }
                messageFlow.emit(UiMessage("已获取 ${result.models.size} 个模型。"))
            }
            is AiModelsResult.Failure -> messageFlow.emit(UiMessage(result.message))
        }
        _uiState.update { it.copy(isLoadingAiModels = false) }
    }
    fun testConnection(baseUrl: String) = viewModelScope.launch {
        _uiState.update { it.copy(connectionTestState = ConnectionTestState.Testing) }
        when (val result = connectionTester.test(baseUrl)) {
            is ConnectionTestResult.Success -> messageFlow.emit(UiMessage("连接成功（HTTP ${result.statusCode}）。"))
            is ConnectionTestResult.Failure -> messageFlow.emit(UiMessage(result.message))
        }
        _uiState.update { it.copy(connectionTestState = ConnectionTestState.Idle) }
    }
}
