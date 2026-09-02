package com.cmhr.listen.data.settings

import android.content.Context
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.cmhr.listen.audio.VadConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vadConfigDataStore by preferencesDataStore(name = "vad_config")

class VadConfigRepository(private val context: Context) {
    val config: Flow<VadConfig> = context.vadConfigDataStore.data.map { preferences ->
        VadConfig(
            threshold = preferences[THRESHOLD] ?: VadConfig.Default.threshold,
            startConfirmMs = preferences[START_CONFIRM_MS] ?: VadConfig.Default.startConfirmMs,
            endSilenceMs = preferences[END_SILENCE_MS] ?: VadConfig.Default.endSilenceMs,
            preRollMs = preferences[PRE_ROLL_MS] ?: VadConfig.Default.preRollMs,
            postRollMs = preferences[POST_ROLL_MS] ?: VadConfig.Default.postRollMs,
            minSegmentMs = preferences[MIN_SEGMENT_MS] ?: VadConfig.Default.minSegmentMs,
            softLimitMs = preferences[SOFT_LIMIT_MS] ?: VadConfig.Default.softLimitMs,
            hardLimitMs = preferences[HARD_LIMIT_MS] ?: VadConfig.Default.hardLimitMs,
            overlapMs = preferences[OVERLAP_MS] ?: VadConfig.Default.overlapMs
        ).validated()
    }

    val presetId: Flow<String> = context.vadConfigDataStore.data.map { preferences ->
        preferences[PRESET_ID] ?: "default"
    }

    suspend fun save(config: VadConfig, presetId: String = "custom") {
        val valid = config.validated()
        context.vadConfigDataStore.edit { preferences ->
            preferences[THRESHOLD] = valid.threshold
            preferences[START_CONFIRM_MS] = valid.startConfirmMs
            preferences[END_SILENCE_MS] = valid.endSilenceMs
            preferences[PRE_ROLL_MS] = valid.preRollMs
            preferences[POST_ROLL_MS] = valid.postRollMs
            preferences[MIN_SEGMENT_MS] = valid.minSegmentMs
            preferences[SOFT_LIMIT_MS] = valid.softLimitMs
            preferences[HARD_LIMIT_MS] = valid.hardLimitMs
            preferences[OVERLAP_MS] = valid.overlapMs
            preferences[PRESET_ID] = presetId
        }
    }

    private companion object {
        val THRESHOLD = floatPreferencesKey("threshold")
        val START_CONFIRM_MS = longPreferencesKey("start_confirm_ms")
        val END_SILENCE_MS = longPreferencesKey("end_silence_ms")
        val PRE_ROLL_MS = longPreferencesKey("pre_roll_ms")
        val POST_ROLL_MS = longPreferencesKey("post_roll_ms")
        val MIN_SEGMENT_MS = longPreferencesKey("min_segment_ms")
        val SOFT_LIMIT_MS = longPreferencesKey("soft_limit_ms")
        val HARD_LIMIT_MS = longPreferencesKey("hard_limit_ms")
        val OVERLAP_MS = longPreferencesKey("overlap_ms")
        val PRESET_ID = stringPreferencesKey("preset_id")
    }
}
