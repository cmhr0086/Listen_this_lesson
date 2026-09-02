package com.cmhr.listen.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

data class ServerSettings(val baseUrl: String = "http://10.0.0.195:8765", val hasApiKey: Boolean = false)
enum class AiProvider { OPENAI_COMPATIBLE, DEEPSEEK }
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
    val imageContext: String = "请结合附加课堂照片中清晰可见的黑板、课件、公式和图表补充判断；无法辨认的内容必须明确说明，不得猜测。"
)
data class AppSettings(
    val developerMode: Boolean = false,
    val server: ServerSettings = ServerSettings(),
    val ai: AiServiceSettings = AiServiceSettings(),
    val aiPrompts: AiPromptSettings = AiPromptSettings(),
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
                imageContext = preferences[AI_PROMPT_IMAGE] ?: AiPromptSettings().imageContext
            ),
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
        require(listOf(value.summary, value.organizeNotes, value.correctAsr, value.quickAnswer, value.customConversation, value.imageContext).all { it.isNotBlank() }) {
            "提示词不能为空。"
        }
        context.appSettingsDataStore.edit { preferences ->
            preferences[AI_PROMPT_SUMMARY] = value.summary.trim()
            preferences[AI_PROMPT_NOTES] = value.organizeNotes.trim()
            preferences[AI_PROMPT_CORRECT] = value.correctAsr.trim()
            preferences[AI_PROMPT_QUICK] = value.quickAnswer.trim()
            preferences[AI_PROMPT_CHAT] = value.customConversation.trim()
            preferences[AI_PROMPT_IMAGE] = value.imageContext.trim()
        }
    }

    suspend fun restoreDefaultAiPrompts() = context.appSettingsDataStore.edit { preferences ->
        listOf(AI_PROMPT_SUMMARY, AI_PROMPT_NOTES, AI_PROMPT_CORRECT, AI_PROMPT_QUICK, AI_PROMPT_CHAT, AI_PROMPT_IMAGE)
            .forEach(preferences::remove)
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
        val AI_PROMPT_IMAGE = stringPreferencesKey("ai_prompt_image")
        val SELECTED_COURSE = longPreferencesKey("selected_course_id")
        val SELECTED_RECORD = longPreferencesKey("selected_record_id")
        const val STT_KEY_ALIAS = "listen_stt_api_key"
        const val AI_KEY_ALIAS = "listen_ai_api_key"
    }
}
