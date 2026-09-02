package com.cmhr.listen

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cmhr.listen.ui.ListenApp
import com.cmhr.listen.ui.theme.ListenTheme
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
        setContent { ListenTheme { ListenApp() } }
        intent.getLongExtra(ListeningForegroundService.EXTRA_RECORD_ID, -1L).let(AppNavigationRequests::openRecord)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getLongExtra(ListeningForegroundService.EXTRA_RECORD_ID, -1L).let(AppNavigationRequests::openRecord)
    }
}
