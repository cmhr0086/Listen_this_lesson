package com.cmhr.listen

import com.cmhr.listen.data.settings.AppearanceSettings
import com.cmhr.listen.ui.theme.DarkModePreference
import com.cmhr.listen.ui.theme.ThemePalette

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.settings.AiProvider
import com.cmhr.listen.data.settings.AiServiceSettings
import com.cmhr.listen.data.settings.AiPromptSettings
import com.cmhr.listen.data.settings.AiGenerationSettings
import com.cmhr.listen.data.stt.AsrPromptAutoConfig
import com.cmhr.listen.data.stt.AsrPromptMode
import com.cmhr.listen.data.settings.ConnectionTestResult
import com.cmhr.listen.data.settings.ServerSettings
import com.cmhr.listen.data.settings.CloudSyncSettings
import com.cmhr.listen.data.settings.ServerConnectionTester
import com.cmhr.listen.data.ai.AiConnectionResult
import com.cmhr.listen.data.ai.AiModelsResult
import com.cmhr.listen.data.ai.AiServiceClient
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.sync.SyncRepository
import com.cmhr.listen.data.sync.SyncProgress
import com.cmhr.listen.data.sync.SyncSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.os.SystemClock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class SettingsUiState(
    val developerMode: Boolean = false,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val server: ServerSettings = ServerSettings(),
    val ai: AiServiceSettings = AiServiceSettings(),
    val aiPrompts: AiPromptSettings = AiPromptSettings(),
    val aiGeneration: AiGenerationSettings = AiGenerationSettings(),
    val globalAsrPromptMode: AsrPromptMode = AsrPromptMode.AUTO,
    val asrPromptAutoConfig: AsrPromptAutoConfig = AsrPromptAutoConfig(),
    val cloudSync: CloudSyncSettings = CloudSyncSettings(),
    val cloudSyncState: CloudSyncRunState = CloudSyncRunState.Idle,
    val cloudSyncProgress: SyncProgress? = null,
    val availableAiModels: List<String> = emptyList(),
    val isLoadingAiModels: Boolean = false,
    val aiModelsError: String? = null,
    val sttCheck: ServiceCheck = ServiceCheck.Unknown,
    val aiCheck: ServiceCheck = ServiceCheck.Unknown,
    val autoNotes: Boolean = true
)

/** The latest connection test of a service, kept on its page instead of a passing snackbar. */
sealed interface ServiceCheck {
    data object Unknown : ServiceCheck
    data object Testing : ServiceCheck
    data class Passed(val at: Long, val latencyMs: Long) : ServiceCheck
    data class Failed(val at: Long, val message: String) : ServiceCheck
}

sealed interface CloudSyncRunState {
    data object Idle : CloudSyncRunState
    data object Syncing : CloudSyncRunState
    data class Success(val summary: SyncSummary) : CloudSyncRunState
    data class Error(val message: String) : CloudSyncRunState
}

data class UiMessage(val text: String)

/** The AI prompts a user can edit, one page each. */
enum class AiPromptKind(val label: String, val usage: String) {
    NOTES("整理成笔记", "用于「笔记」标签和下课后自动整理。课堂原文和重点标记会自动附在后面，这里只写要求。"),
    CORRECT("修正识别错误", "选中文字后「修正」时使用，只改明显的错字和标点。"),
    QUICK("快速回答", "从选中的课堂原文里找出老师的问题并逐条作答。"),
    CLASS_CHAT("课堂问答", "「问答」标签里的提问和追问；只依据冻结的课堂原文回答。"),
    IMAGE("课堂照片", "提问附带黑板或课件照片时追加在提示词后面。"),
    GENERAL("通用对话", "AI 页里不关联课堂的对话。");

    fun read(value: AiPromptSettings): String = when (this) {
        NOTES -> value.organizeNotes
        CORRECT -> value.correctAsr
        QUICK -> value.quickAnswer
        CLASS_CHAT -> value.customConversation
        IMAGE -> value.imageContext
        GENERAL -> value.generalConversation
    }

    fun write(value: AiPromptSettings, text: String): AiPromptSettings = when (this) {
        NOTES -> value.copy(organizeNotes = text)
        CORRECT -> value.copy(correctAsr = text)
        QUICK -> value.copy(quickAnswer = text)
        CLASS_CHAT -> value.copy(customConversation = text)
        IMAGE -> value.copy(imageContext = text)
        GENERAL -> value.copy(generalConversation = text)
    }

    fun isDefault(value: AiPromptSettings) = read(value).trim() == read(AiPromptSettings()).trim()
}

const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
const val DEEPSEEK_DEFAULT_MODEL = "deepseek-v4-flash"
const val OPENAI_BASE_URL = "https://api.openai.com/v1"

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppSettingsRepository(application)
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()
    private val messageFlow = MutableSharedFlow<UiMessage>(extraBufferCapacity = 1)
    val messages = messageFlow.asSharedFlow()
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val connectionTester = ServerConnectionTester(client)
    private val aiClient = AiServiceClient()
    private val syncRepository = SyncRepository(ListenDatabase.get(application), repository)
    private var sttCheckJob: Job? = null
    private var aiCheckJob: Job? = null

    init { viewModelScope.launch { repository.settings.collect { settings ->
        _uiState.update { it.copy(
            developerMode = settings.developerMode,
            appearance = settings.appearance,
            server = settings.server,
            ai = settings.ai,
            aiPrompts = settings.aiPrompts,
            aiGeneration = settings.aiGeneration,
            globalAsrPromptMode = settings.globalAsrPromptMode,
            asrPromptAutoConfig = settings.asrPromptAutoConfig,
            cloudSync = settings.cloudSync,
            autoNotes = settings.autoNotes
        ) }
    } } }

    fun setDeveloperMode(enabled: Boolean) = viewModelScope.launch { repository.setDeveloperMode(enabled) }
    fun setAutoNotes(enabled: Boolean) = viewModelScope.launch { repository.setAutoNotes(enabled) }
    fun setThemePalette(palette: ThemePalette) = viewModelScope.launch { repository.setThemePalette(palette) }
    fun setDarkMode(mode: DarkModePreference) = viewModelScope.launch { repository.setDarkMode(mode) }

    /** Runs a save; on failure reports why and returns false so no test follows. */
    private suspend fun attempt(fallback: String, block: suspend () -> Unit): Boolean =
        runCatching { block() }.fold({ true }, { messageFlow.emit(UiMessage(it.message ?: fallback)); false })

    // 语音识别

    fun saveSttUrl(baseUrl: String) = viewModelScope.launch {
        if (attempt("服务器地址无效。") { repository.saveServer(baseUrl, null) }) checkStt()
    }
    fun saveSttKey(apiKey: String) = viewModelScope.launch {
        if (attempt("API Key 无效。") { repository.saveSttApiKey(apiKey) }) checkStt()
    }
    fun clearApiKey() = viewModelScope.launch {
        repository.clearApiKey()
        _uiState.update { it.copy(sttCheck = ServiceCheck.Unknown) }
        messageFlow.emit(UiMessage("API Key 已清除。"))
    }
    /** Tests once per app run when the page opens, so its status is never stale on arrival. */
    fun checkSttIfUnknown() { if (_uiState.value.sttCheck == ServiceCheck.Unknown && _uiState.value.server.baseUrl.isNotBlank()) checkStt() }
    fun checkStt() {
        sttCheckJob?.cancel()
        sttCheckJob = viewModelScope.launch {
            _uiState.update { it.copy(sttCheck = ServiceCheck.Testing) }
            val settings = repository.settings.first().server
            val key = runCatching { repository.readApiKey().orEmpty() }.getOrDefault("")
            val started = SystemClock.elapsedRealtime()
            val result = connectionTester.verify(settings.baseUrl, key)
            val check = when (result) {
                is ConnectionTestResult.Success -> ServiceCheck.Passed(System.currentTimeMillis(), SystemClock.elapsedRealtime() - started)
                is ConnectionTestResult.Failure -> ServiceCheck.Failed(System.currentTimeMillis(), result.message)
            }
            _uiState.update { it.copy(sttCheck = check) }
        }
    }

    // AI 服务

    fun chooseAiProvider(provider: AiProvider) = viewModelScope.launch {
        val current = _uiState.value.ai
        if (current.provider == provider) return@launch
        val (baseUrl, model) = when {
            provider == AiProvider.DEEPSEEK -> DEEPSEEK_BASE_URL to DEEPSEEK_DEFAULT_MODEL
            current.baseUrl.trimEnd('/') == DEEPSEEK_BASE_URL -> OPENAI_BASE_URL to ""
            else -> current.baseUrl to current.model
        }
        _uiState.update { it.copy(availableAiModels = emptyList()) }
        if (attempt("AI 服务设置无效。") { repository.saveAiEndpoint(provider, baseUrl, model) }) checkAi()
    }
    fun saveAiBaseUrl(baseUrl: String) = viewModelScope.launch {
        val current = _uiState.value.ai
        _uiState.update { it.copy(availableAiModels = emptyList()) }
        if (attempt("AI 服务地址无效。") { repository.saveAiEndpoint(current.provider, baseUrl, current.model) }) checkAi()
    }
    fun saveAiModel(model: String) = viewModelScope.launch {
        if (attempt("请填写 AI 模型名称。") { repository.saveAiModel(model) }) checkAi()
    }
    fun saveAiKey(apiKey: String) = viewModelScope.launch {
        if (attempt("API Key 无效。") { repository.saveAiApiKey(apiKey) }) checkAi()
    }
    fun clearAiApiKey() = viewModelScope.launch {
        repository.clearAiApiKey()
        _uiState.update { it.copy(aiCheck = ServiceCheck.Unknown, availableAiModels = emptyList()) }
        messageFlow.emit(UiMessage("AI API Key 已清除。"))
    }
    fun checkAiIfUnknown() { if (_uiState.value.aiCheck == ServiceCheck.Unknown && _uiState.value.ai.hasApiKey) checkAi() }
    fun checkAi() {
        aiCheckJob?.cancel()
        aiCheckJob = viewModelScope.launch {
            _uiState.update { it.copy(aiCheck = ServiceCheck.Testing) }
            val settings = repository.settings.first().ai
            val key = runCatching { repository.readAiApiKey().orEmpty() }.getOrDefault("")
            val started = SystemClock.elapsedRealtime()
            val check = when (val result = aiClient.testConnection(settings.baseUrl, key)) {
                is AiConnectionResult.Success -> ServiceCheck.Passed(System.currentTimeMillis(), SystemClock.elapsedRealtime() - started)
                is AiConnectionResult.Failure -> ServiceCheck.Failed(System.currentTimeMillis(), result.message)
            }
            _uiState.update { it.copy(aiCheck = check) }
        }
    }
    fun fetchAiModels() = viewModelScope.launch {
        if (_uiState.value.isLoadingAiModels) return@launch
        _uiState.update { it.copy(isLoadingAiModels = true, aiModelsError = null) }
        val settings = repository.settings.first().ai
        val key = runCatching { repository.readAiApiKey().orEmpty() }.getOrDefault("")
        when (val result = aiClient.fetchModels(settings.baseUrl, key)) {
            is AiModelsResult.Success -> _uiState.update { it.copy(availableAiModels = result.models) }
            is AiModelsResult.Failure -> _uiState.update { it.copy(aiModelsError = result.message) }
        }
        _uiState.update { it.copy(isLoadingAiModels = false) }
    }

    // AI 提示词与生成参数（开发者）

    fun saveAiPrompt(kind: AiPromptKind, text: String) = viewModelScope.launch {
        if (attempt("提示词不能为空。") { repository.saveAiPrompts(kind.write(_uiState.value.aiPrompts, text)) }) {
            messageFlow.emit(UiMessage("已保存「${kind.label}」。"))
        }
    }
    fun restoreDefaultAiPrompts() = viewModelScope.launch {
        repository.restoreDefaultAiPrompts()
        messageFlow.emit(UiMessage("AI 提示词已全部恢复默认。"))
    }
    fun saveAiGeneration(value: AiGenerationSettings) = viewModelScope.launch { repository.saveAiGeneration(value) }
    fun restoreDefaultAiGeneration() = viewModelScope.launch { repository.restoreDefaultAiGeneration() }

    // 专业词提示

    fun saveGlobalAsrPromptMode(mode: AsrPromptMode) = viewModelScope.launch { repository.saveGlobalAsrPromptMode(mode) }
    fun saveAsrPromptAutoConfig(value: AsrPromptAutoConfig) = viewModelScope.launch { repository.saveAsrPromptAutoConfig(value) }
    fun restoreDefaultAsrPromptAutoConfig() = viewModelScope.launch { repository.restoreDefaultAsrPromptAutoConfig() }

    // 云同步

    fun saveCloudSyncUrl(baseUrl: String) = viewModelScope.launch {
        if (attempt("云同步设置无效。") { repository.saveCloudSyncServer(baseUrl, null) }) {
            messageFlow.emit(UiMessage("同步服务器地址已保存。"))
        }
    }
    fun saveCloudSyncToken(apiToken: String) = viewModelScope.launch {
        if (attempt("Token 无效。") { repository.saveCloudSyncToken(apiToken) }) {
            messageFlow.emit(UiMessage("Token 已保存。"))
        }
    }
    fun clearCloudSyncApiToken() = viewModelScope.launch {
        repository.clearCloudSyncApiToken()
        _uiState.update { it.copy(cloudSyncState = CloudSyncRunState.Idle) }
        messageFlow.emit(UiMessage("已停用云同步。"))
    }
    fun syncNow() = viewModelScope.launch {
        if (_uiState.value.cloudSyncState is CloudSyncRunState.Syncing) return@launch
        _uiState.update { it.copy(cloudSyncState = CloudSyncRunState.Syncing, cloudSyncProgress = null) }
        val result = runCatching {
            syncRepository.synchronize(repository.settings.first().cloudSync.baseUrl) { progress ->
                _uiState.update { it.copy(cloudSyncProgress = progress) }
            }
        }
        result.fold(
            onSuccess = { summary -> _uiState.update { it.copy(cloudSyncState = CloudSyncRunState.Success(summary)) } },
            onFailure = { error ->
                _uiState.update { it.copy(cloudSyncState = CloudSyncRunState.Error(error.message ?: "云同步失败。")) }
            }
        )
    }
}
