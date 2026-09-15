package com.cmhr.listen.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cmhr.listen.ListeningForegroundService
import com.cmhr.listen.MainActivity
import com.cmhr.listen.R

class RecognitionForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "录音补识别", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            OfflineRecognitionRuntime.get(applicationContext).stop(); stopSelf(); return START_NOT_STICKY
        }
        val recordId = intent?.getLongExtra(EXTRA_RECORD_ID, -1L) ?: -1L
        val open = Intent(this, MainActivity::class.java).putExtra(ListeningForegroundService.EXTRA_RECORD_ID, recordId)
        val stop = Intent(this, RecognitionForegroundService::class.java).setAction(ACTION_STOP)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("正在补识别本地录音")
            .setContentText("已完成的识别内容会立即保存")
            .setContentIntent(PendingIntent.getActivity(this, 31, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "停止", PendingIntent.getService(this, 32, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setOnlyAlertOnce(true).build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        OfflineRecognitionRuntime.get(applicationContext).stop()
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "offline_recognition"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_START = "com.cmhr.listen.START_OFFLINE_RECOGNITION"
        private const val ACTION_STOP = "com.cmhr.listen.STOP_OFFLINE_RECOGNITION"
        private const val EXTRA_RECORD_ID = "record_id"
        fun start(context: Context, recordId: Long, recordingId: String) {
            val intent = Intent(context, RecognitionForegroundService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RECORD_ID, recordId).putExtra("recording_id", recordingId)
            ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context) { context.stopService(Intent(context, RecognitionForegroundService::class.java)) }
    }
}
