package com.cmhr.listen

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import com.cmhr.listen.ui.theme.DarkModePreference
import com.cmhr.listen.ui.ListenApp
import com.cmhr.listen.ui.theme.ListenTheme
import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.settings.AppearanceSettings
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

object AppNavigationRequests {
    private val requests = Channel<Long>(Channel.BUFFERED)
    val recordRequests = requests.receiveAsFlow()
    fun openRecord(id: Long) { if (id > 0L) requests.trySend(id) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appearance = AppSettingsRepository(applicationContext).settings.map { it.appearance }
        setContent {
            // Initial null keeps the default theme for the first frame instead of flashing another one.
            val current by appearance.collectAsState(initial = null)
            val settings = current ?: AppearanceSettings()
            val dark = when (settings.darkMode) {
                DarkModePreference.SYSTEM -> isSystemInDarkTheme()
                DarkModePreference.LIGHT -> false
                DarkModePreference.DARK -> true
            }
            // System bar icons follow the app's own dark-mode choice, not only the system's.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ListenTheme(palette = settings.palette, darkMode = settings.darkMode) { ListenApp() }
        }
        intent.getLongExtra(ListeningForegroundService.EXTRA_RECORD_ID, -1L).let(AppNavigationRequests::openRecord)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getLongExtra(ListeningForegroundService.EXTRA_RECORD_ID, -1L).let(AppNavigationRequests::openRecord)
    }
}
