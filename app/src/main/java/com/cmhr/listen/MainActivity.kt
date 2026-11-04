package com.cmhr.listen

import com.cmhr.listen.tgui.ListenHostActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Stable entry point shared by the launcher and foreground-service notifications. */
class MainActivity : ListenHostActivity()

object AppNavigationRequests {
    private val requests = Channel<Long>(Channel.BUFFERED)
    val recordRequests = requests.receiveAsFlow()
    fun openRecord(id: Long) { if (id > 0L) requests.trySend(id) }
}
