package com.cmhr.listen.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.cmhr.listen.data.stt.AsrPromptAutoConfig
import com.cmhr.listen.data.stt.AsrPromptMode

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

data class ServerSettings(val baseUrl: String = "http://10.0.0.195:8765", val hasApiKey: Boolean = false)
enum class AiProvider { OPENAI_COMPATIBLE, DEEPSEEK }
enum class AiThinkingMode(val displayName: String) {
    DISABLED("关闭"), ENABLED("开启"), SERVICE_DEFAULT("跟随服务")
}
enum class AiReasoningEffort { LOW, HIGH, MAX }
data class AiServiceSettings(
    val provider: AiProvider = AiProvider.OPENAI_COMPATIBLE,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val hasApiKey: Boolean = false
)
data class AiPromptSettings(
    val summary: String = "你是课堂内容整理助手。只依据提供的课堂原文，输出结构清晰的重点摘要；不要虚构原文没有的信息，对不确定内容明确说明。",
    val organizeNotes: String = "你是课堂笔记整理助手。只依据原文，按主题、关键要点和专业术语整理成中文笔记；保留重要限定条件，不得虚构。",
    val correctAsr: String = "你是课堂语音转写校对助手。只修正原文中明显的 ASR 错字、同音词和标点，不改变原意；无法确认处保留原文并标记。只输出建议修正版。",
    val quickAnswer: String = "你是课堂问题快速回答助手。先从原文中识别老师明确提出的问题，再按出现顺序逐条给出简洁答案。只能依据原文和可靠的通用知识；原文不足、说话人或问题不确定时必须明确说明，不得虚构课堂内容。",
    val customConversation: String = "你是课堂内容问答助手。只能依据提供的冻结课堂原文回答；若原文不足以回答，应明确说明，不得虚构。",
    val generalConversation: String = "你是可靠、清晰的中文 AI 助手。准确回答用户问题；不确定时明确说明，不得虚构事实。",
    val imageContext: String = "请结合附加课堂照片中清晰可见的黑板、课件、公式和图表补充判断；无法辨认的内容必须明确说明，不得猜测。"
)
data class AiGenerationSettings(
    val maxTokens: Int = 8_192,
    val fixedTemperature: Float = 0.2f,
    val chatTemperature: Float = 0.7f,
    val deepSeekThinkingMode: AiThinkingMode = AiThinkingMode.DISABLED,
    val reasoningEffort: AiReasoningEffort = AiReasoningEffort.HIGH
) {
    fun validated() = copy(
        maxTokens = maxTokens.coerceIn(512, 32_768),
        fixedTemperature = fixedTemperature.coerceIn(0f, 2f),
        chatTemperature = chatTemperature.coerceIn(0f, 2f)
    )
}
data class AppSettings(
    val developerMode: Boolean = false,
    val server: ServerSettings = ServerSettings(),
    val ai: AiServiceSettings = AiServiceSettings(),
    val aiPrompts: AiPromptSettings = AiPromptSettings(),
    val aiGeneration: AiGenerationSettings = AiGenerationSettings(),
    val globalAsrPromptMode: AsrPromptMode = AsrPromptMode.AUTO,
    val asrPromptAutoConfig: AsrPromptAutoConfig = AsrPromptAutoConfig(),
    val selectedCourseId: Long? = null,
    val selectedRecordId: Long? = null
)

/** DataStore owns ordinary settings; the API key value is AES-GCM encrypted with an Android Keystore key. */
class AppSettingsRepository(private val context: Context) {
    private val sttApiKeyStore = EncryptedSecretStore(STT_KEY_ALIAS)
    private val aiApiKeyStore = EncryptedSecretStore(AI_KEY_ALIAS)

    val settings: Flow<AppSettings> = context.appSettingsDataStore.data.map { preferences ->
        AppSettings(
            developerMode = preferences[DEVELOPER_MODE] ?: false,
            server = ServerSettings(
                baseUrl = preferences[BASE_URL] ?: ServerSettings().baseUrl,
                hasApiKey = preferences[API_KEY] != null
            ),
            ai = AiServiceSettings(
                provider = preferences[AI_PROVIDER]?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
                    ?: AiProvider.OPENAI_COMPATIBLE,
                baseUrl = preferences[AI_BASE_URL] ?: AiServiceSettings().baseUrl,
                model = preferences[AI_MODEL].orEmpty(),
                hasApiKey = preferences[AI_API_KEY] != null
            ),
            aiPrompts = AiPromptSettings(
                summary = preferences[AI_PROMPT_SUMMARY] ?: AiPromptSettings().summary,
                organizeNotes = preferences[AI_PROMPT_NOTES] ?: AiPromptSettings().organizeNotes,
                correctAsr = preferences[AI_PROMPT_CORRECT] ?: AiPromptSettings().correctAsr,
                quickAnswer = preferences[AI_PROMPT_QUICK] ?: AiPromptSettings().quickAnswer,
                customConversation = preferences[AI_PROMPT_CHAT] ?: AiPromptSettings().customConversation,
                generalConversation = preferences[AI_PROMPT_GENERAL_CHAT] ?: AiPromptSettings().generalConversation,
                imageContext = preferences[AI_PROMPT_IMAGE] ?: AiPromptSettings().imageContext
            ),
            aiGeneration = AiGenerationSettings(
                maxTokens = preferences[AI_MAX_TOKENS] ?: AiGenerationSettings().maxTokens,
                fixedTemperature = preferences[AI_FIXED_TEMPERATURE] ?: AiGenerationSettings().fixedTemperature,
                chatTemperature = preferences[AI_CHAT_TEMPERATURE] ?: AiGenerationSettings().chatTemperature,
                deepSeekThinkingMode = preferences[AI_THINKING_MODE]
                    ?.let { runCatching { AiThinkingMode.valueOf(it) }.getOrNull() }
                    ?: AiThinkingMode.DISABLED,
                reasoningEffort = preferences[AI_REASONING_EFFORT]
                    ?.let { runCatching { AiReasoningEffort.valueOf(it) }.getOrNull() }
                    ?: AiReasoningEffort.HIGH
            ).validated(),
            globalAsrPromptMode = preferences[GLOBAL_ASR_PROMPT_MODE]
                ?.let { runCatching { AsrPromptMode.valueOf(it) }.getOrNull() }
                ?: AsrPromptMode.AUTO,
            asrPromptAutoConfig = AsrPromptAutoConfig(
                minAudioDurationMs = preferences[ASR_PROMPT_MIN_AUDIO_MS] ?: AsrPromptAutoConfig().minAudioDurationMs,
                minVoicedDurationMs = preferences[ASR_PROMPT_MIN_VOICED_MS] ?: AsrPromptAutoConfig().minVoicedDurationMs,
                minMeanSpeechProbability = preferences[ASR_PROMPT_MIN_VAD] ?: AsrPromptAutoConfig().minMeanSpeechProbability,
                minSpeechFrameRatio = preferences[ASR_PROMPT_MIN_SPEECH_RATIO] ?: AsrPromptAutoConfig().minSpeechFrameRatio,
                minSnrDb = preferences[ASR_PROMPT_MIN_SNR] ?: AsrPromptAutoConfig().minSnrDb
            ).validated(),
            selectedCourseId = preferences[SELECTED_COURSE],
            selectedRecordId = preferences[SELECTED_RECORD]
        )
    }

    suspend fun setDeveloperMode(enabled: Boolean) = context.appSettingsDataStore.edit { it[DEVELOPER_MODE] = enabled }

    suspend fun saveServer(baseUrl: String, apiKey: String?) {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("http://") || normalized.startsWith("https://")) { "服务器地址必须以 http:// 或 https:// 开头。" }
        context.appSettingsDataStore.edit { preferences ->
            preferences[BASE_URL] = normalized
            if (!apiKey.isNullOrBlank()) preferences[API_KEY] = sttApiKeyStore.encrypt(apiKey.trim())
        }
    }

    suspend fun clearApiKey() = context.appSettingsDataStore.edit { it.remove(API_KEY) }

    suspend fun readApiKey(): String? = context.appSettingsDataStore.data.first()[API_KEY]?.let(sttApiKeyStore::decrypt)

    suspend fun saveAiService(provider: AiProvider, baseUrl: String, model: String, apiKey: String?) {
        val normalized = baseUrl.trim().trimEnd('/')
        val parsed = normalized.toHttpUrlOrNull()
        require(parsed != null && parsed.scheme == "https") { "AI 服务地址必须是有效的 https:// 地址。" }
        require(model.isNotBlank()) { "请填写 AI 模型名称。" }
        context.appSettingsDataStore.edit { preferences ->
            preferences[AI_PROVIDER] = provider.name
            preferences[AI_BASE_URL] = normalized
            preferences[AI_MODEL] = model.trim()
            if (!apiKey.isNullOrBlank()) preferences[AI_API_KEY] = aiApiKeyStore.encrypt(apiKey.trim())
        }
    }

    suspend fun clearAiApiKey() = context.appSettingsDataStore.edit { it.remove(AI_API_KEY) }
    suspend fun readAiApiKey(): String? = context.appSettingsDataStore.data.first()[AI_API_KEY]?.let(aiApiKeyStore::decrypt)

    suspend fun saveAiPrompts(value: AiPromptSettings) {
        require(listOf(value.organizeNotes, value.correctAsr, value.quickAnswer, value.customConversation, value.generalConversation, value.imageContext).all { it.isNotBlank() }) {
            "提示词不能为空。"
        }
        context.appSettingsDataStore.edit { preferences ->
            preferences[AI_PROMPT_SUMMARY] = value.summary.trim()
            preferences[AI_PROMPT_NOTES] = value.organizeNotes.trim()
            preferences[AI_PROMPT_CORRECT] = value.correctAsr.trim()
            preferences[AI_PROMPT_QUICK] = value.quickAnswer.trim()
            preferences[AI_PROMPT_CHAT] = value.customConversation.trim()
            preferences[AI_PROMPT_GENERAL_CHAT] = value.generalConversation.trim()
            preferences[AI_PROMPT_IMAGE] = value.imageContext.trim()
        }
    }

    suspend fun restoreDefaultAiPrompts() = context.appSettingsDataStore.edit { preferences ->
        listOf(AI_PROMPT_SUMMARY, AI_PROMPT_NOTES, AI_PROMPT_CORRECT, AI_PROMPT_QUICK, AI_PROMPT_CHAT, AI_PROMPT_GENERAL_CHAT, AI_PROMPT_IMAGE)
            .forEach(preferences::remove)
    }

    suspend fun saveAiGeneration(value: AiGenerationSettings) {
        val config = value.validated()
        context.appSettingsDataStore.edit { preferences ->
            preferences[AI_MAX_TOKENS] = config.maxTokens
            preferences[AI_FIXED_TEMPERATURE] = config.fixedTemperature
            preferences[AI_CHAT_TEMPERATURE] = config.chatTemperature
            preferences[AI_THINKING_MODE] = config.deepSeekThinkingMode.name
            preferences[AI_REASONING_EFFORT] = config.reasoningEffort.name
        }
    }

    suspend fun restoreDefaultAiGeneration() = context.appSettingsDataStore.edit { preferences ->
        preferences.remove(AI_MAX_TOKENS)
        preferences.remove(AI_FIXED_TEMPERATURE)
        preferences.remove(AI_CHAT_TEMPERATURE)
        preferences.remove(AI_THINKING_MODE)
        preferences.remove(AI_REASONING_EFFORT)
    }

    suspend fun saveGlobalAsrPromptMode(mode: AsrPromptMode) = context.appSettingsDataStore.edit {
        it[GLOBAL_ASR_PROMPT_MODE] = mode.name
    }

    suspend fun saveAsrPromptAutoConfig(value: AsrPromptAutoConfig) {
        val config = value.validated()
        context.appSettingsDataStore.edit { preferences ->
            preferences[ASR_PROMPT_MIN_AUDIO_MS] = config.minAudioDurationMs
            preferences[ASR_PROMPT_MIN_VOICED_MS] = config.minVoicedDurationMs
            preferences[ASR_PROMPT_MIN_VAD] = config.minMeanSpeechProbability
            preferences[ASR_PROMPT_MIN_SPEECH_RATIO] = config.minSpeechFrameRatio
            preferences[ASR_PROMPT_MIN_SNR] = config.minSnrDb
        }
    }

    suspend fun restoreDefaultAsrPromptAutoConfig() = context.appSettingsDataStore.edit { preferences ->
        preferences.remove(ASR_PROMPT_MIN_AUDIO_MS)
        preferences.remove(ASR_PROMPT_MIN_VOICED_MS)
        preferences.remove(ASR_PROMPT_MIN_VAD)
        preferences.remove(ASR_PROMPT_MIN_SPEECH_RATIO)
        preferences.remove(ASR_PROMPT_MIN_SNR)
    }

    suspend fun selectCourse(courseId: Long?) = context.appSettingsDataStore.edit { preferences ->
        if (courseId == null) preferences.remove(SELECTED_COURSE) else preferences[SELECTED_COURSE] = courseId
        preferences.remove(SELECTED_RECORD)
    }

    suspend fun selectRecord(courseId: Long, recordId: Long) = context.appSettingsDataStore.edit { preferences ->
        preferences[SELECTED_COURSE] = courseId
        preferences[SELECTED_RECORD] = recordId
    }

    private companion object {
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val BASE_URL = stringPreferencesKey("server_base_url")
        val API_KEY = stringPreferencesKey("encrypted_api_key")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_API_KEY = stringPreferencesKey("encrypted_ai_api_key")
        val AI_PROMPT_SUMMARY = stringPreferencesKey("ai_prompt_summary")
        val AI_PROMPT_NOTES = stringPreferencesKey("ai_prompt_notes")
        val AI_PROMPT_CORRECT = stringPreferencesKey("ai_prompt_correct")
        val AI_PROMPT_QUICK = stringPreferencesKey("ai_prompt_quick")
        val AI_PROMPT_CHAT = stringPreferencesKey("ai_prompt_chat")
        val AI_PROMPT_GENERAL_CHAT = stringPreferencesKey("ai_prompt_general_chat")
        val AI_PROMPT_IMAGE = stringPreferencesKey("ai_prompt_image")
        val AI_MAX_TOKENS = intPreferencesKey("ai_max_tokens")
        val AI_FIXED_TEMPERATURE = floatPreferencesKey("ai_fixed_temperature")
        val AI_CHAT_TEMPERATURE = floatPreferencesKey("ai_chat_temperature")
        val AI_THINKING_MODE = stringPreferencesKey("ai_thinking_mode")
        val AI_REASONING_EFFORT = stringPreferencesKey("ai_reasoning_effort")
        val GLOBAL_ASR_PROMPT_MODE = stringPreferencesKey("global_asr_prompt_mode")
        val ASR_PROMPT_MIN_AUDIO_MS = longPreferencesKey("asr_prompt_min_audio_ms")
        val ASR_PROMPT_MIN_VOICED_MS = longPreferencesKey("asr_prompt_min_voiced_ms")
        val ASR_PROMPT_MIN_VAD = floatPreferencesKey("asr_prompt_min_vad")
        val ASR_PROMPT_MIN_SPEECH_RATIO = floatPreferencesKey("asr_prompt_min_speech_ratio")
        val ASR_PROMPT_MIN_SNR = floatPreferencesKey("asr_prompt_min_snr")
        val SELECTED_COURSE = longPreferencesKey("selected_course_id")
        val SELECTED_RECORD = longPreferencesKey("selected_record_id")
        const val STT_KEY_ALIAS = "listen_stt_api_key"
        const val AI_KEY_ALIAS = "listen_ai_api_key"
    }
}
