package com.cmhr.listen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cmhr.listen.data.stt.AsrQueueRuntime
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ListeningControlBus {
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopRequests = requests.asSharedFlow()
    fun requestStop() { requests.tryEmit(Unit) }
}

class ListeningForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "课堂监听", NotificationManager.IMPORTANCE_LOW).apply {
                description = "持续录音与课堂语音识别状态"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent ?: return START_NOT_STICKY
        if (request.action == ACTION_STOP) {
            ListeningControlBus.requestStop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val recordId = request.getLongExtra(EXTRA_RECORD_ID, -1L)
        if (recordId <= 0L) return START_NOT_STICKY
        val notification = buildNotification(
            recordId = recordId,
            courseName = request.getStringExtra(EXTRA_COURSE_NAME).orEmpty(),
            recordName = request.getStringExtra(EXTRA_RECORD_NAME).orEmpty(),
            startedElapsed = request.getLongExtra(EXTRA_STARTED_ELAPSED, SystemClock.elapsedRealtime()),
            recognizing = request.getBooleanExtra(EXTRA_RECOGNIZING, false),
            queueCount = request.getIntExtra(EXTRA_QUEUE_COUNT, 0)
        )
        if (request.action == ACTION_START) {
            acquireWakeLock()
            AsrQueueRuntime.get(applicationContext).kick()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:classroom-listening")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun buildNotification(
        recordId: Long,
        courseName: String,
        recordName: String,
        startedElapsed: Long,
        recognizing: Boolean,
        queueCount: Int
    ): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_RECORD_ID, recordId)
        }
        val stopIntent = Intent(this, ListeningForegroundService::class.java).apply { action = ACTION_STOP }
        val whenWallClock = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(0L)
        val status = if (recognizing) "正在识别" else if (queueCount > 0) "等待识别（$queueCount）" else "等待语音"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(courseName.ifBlank { "课堂监听中" })
            .setContentText("${recordName.ifBlank { "课堂记录" }} · $status")
            .setContentIntent(PendingIntent.getActivity(this, 10, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "停止监听", PendingIntent.getService(this, 11, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setWhen(whenWallClock)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val EXTRA_RECORD_ID = "record_id"
        private const val EXTRA_COURSE_NAME = "course_name"
        private const val EXTRA_RECORD_NAME = "record_name"
        private const val EXTRA_STARTED_ELAPSED = "started_elapsed"
        private const val EXTRA_RECOGNIZING = "recognizing"
        private const val EXTRA_QUEUE_COUNT = "queue_count"
        private const val ACTION_START = "com.cmhr.listen.START_LISTENING_NOTIFICATION"
        private const val ACTION_UPDATE = "com.cmhr.listen.UPDATE_LISTENING_NOTIFICATION"
        private const val ACTION_STOP = "com.cmhr.listen.STOP_LISTENING"
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, state: ListeningUiState) {
            val intent = serviceIntent(context, state).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun update(context: Context, state: ListeningUiState) {
            if (!state.isListening) return
            context.startService(serviceIntent(context, state).setAction(ACTION_UPDATE))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListeningForegroundService::class.java))
        }

        private fun serviceIntent(context: Context, state: ListeningUiState) =
            Intent(context, ListeningForegroundService::class.java).apply {
                putExtra(EXTRA_RECORD_ID, state.activeRecordId ?: -1L)
                putExtra(EXTRA_COURSE_NAME, state.currentCourseName)
                putExtra(EXTRA_RECORD_NAME, state.currentRecordName)
                putExtra(EXTRA_STARTED_ELAPSED, state.listeningStartedAtElapsedRealtimeMs ?: SystemClock.elapsedRealtime())
                putExtra(EXTRA_RECOGNIZING, state.isRecognizing)
                putExtra(EXTRA_QUEUE_COUNT, state.pendingQueueCount)
            }
    }
}
