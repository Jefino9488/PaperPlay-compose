package io.max.paperplay.compose

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the process in the foreground while "keep running" is on, so syncing carries on after the
 * person leaves. Playback has its own service. Its notification says what the app is doing.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = build(this, "Paper Play is running", "Starting up", null, null, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION, notification)
        }
        // Not restarted by Android: with the app gone there would be nothing to keep alive.
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "io.max.paperplay.background"
        private const val NOTIFICATION = 4712

        @Volatile
        private var running = false

        fun start(context: Context) {
            if (running) return
            runCatching { context.startForegroundService(Intent(context, KeepAliveService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        /** Redraws the notification with the current activity. Does nothing while the service is off. */
        fun update(context: Context, title: String, text: String, progress: Int?, max: Int?, indeterminate: Boolean) {
            if (!running) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION, build(context, title, text, progress, max, indeterminate))
        }

        private fun build(context: Context, title: String, text: String, progress: Int?, max: Int?, indeterminate: Boolean): Notification {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Running in the background", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.OPEN_EXTRA, "tasks"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_paperplay)
                .setContentTitle(title)
                .setContentText(text.lineSequence().firstOrNull().orEmpty())
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
            if (max != null && progress != null) builder.setProgress(max, progress, false)
            else if (indeterminate) builder.setProgress(0, 0, true)
            return builder.build()
        }
    }
}
