package com.kintoretimer.app

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 画面を消しても別アプリに切り替えてもタイマーを動かし続けるための常駐サービス。
 * 計時そのものは [TimerController] が行い、ここは通知とCPUの維持だけを担当する。
 */
class TimerService : Service() {
    private lateinit var timer: TimerController
    private lateinit var wakeLock: PowerManager.WakeLock
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        timer = TimerController.get(this)
        timer.service = this
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kintore:timer")
            .apply { setReferenceCounted(false) }
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        when (intent?.action) {
            ACTION_TOGGLE -> timer.engine.toggle()
            ACTION_NEXT -> timer.engine.next()
            ACTION_QUIT -> timer.engine.reset()
        }
        render()
        return START_NOT_STICKY
    }

    /** 動作中にタスクを消されたら続行し、止まっていたら一緒に終わる。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!timer.engine.running) timer.engine.reset()
    }

    override fun onDestroy() {
        if (wakeLock.isHeld) wakeLock.release()
        timer.service = null
        super.onDestroy()
    }

    fun render() {
        if (timer.engine.running) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } else if (wakeLock.isHeld) {
            wakeLock.release()
        }
        notifications.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val e = timer.engine
        val p = e.current
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(!e.finished)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

        if (e.finished || p == null) {
            return b.setContentTitle(getString(R.string.done)).setShowWhen(false).build()
        }

        b.setContentTitle(
            when (p.type) {
                PhaseType.WORK -> p.name
                PhaseType.REST -> getString(R.string.rest)
                PhaseType.PREP -> getString(R.string.prep)
            },
        )
        val next = e.nextWorkPhase()?.let { getString(R.string.next_is, it.name) }
        if (e.running) {
            b.setContentText(next)
                .setWhen(System.currentTimeMillis() + e.remainMs())
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .addAction(0, getString(R.string.pause), action(ACTION_TOGGLE, 1))
                .addAction(0, getString(R.string.next), action(ACTION_NEXT, 2))
        } else {
            b.setContentText(getString(R.string.paused))
                .setShowWhen(false)
                .addAction(0, getString(R.string.resume), action(ACTION_TOGGLE, 1))
                .addAction(0, getString(R.string.next), action(ACTION_NEXT, 2))
                .addAction(0, getString(R.string.quit), action(ACTION_QUIT, 3))
        }
        return b.build()
    }

    private fun action(name: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, TimerService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val CHANNEL = "timer"
        const val NOTIFICATION_ID = 1
        const val WAKE_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000
        const val ACTION_TOGGLE = "com.kintoretimer.app.TOGGLE"
        const val ACTION_NEXT = "com.kintoretimer.app.NEXT"
        const val ACTION_QUIT = "com.kintoretimer.app.QUIT"
    }
}
