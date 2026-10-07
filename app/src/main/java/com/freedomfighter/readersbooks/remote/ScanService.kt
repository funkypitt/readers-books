package com.freedomfighter.readersbooks.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.freedomfighter.readersbooks.App
import com.freedomfighter.readersbooks.MainActivity
import com.freedomfighter.readersbooks.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Keeps the app alive while a drive is walked: a foreground service with a quiet notification
 * that counts folders and books, and a wake lock so a locked phone does not pause the walk.
 */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as App
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val first = notification(app.remote.progress.value ?: Progress(0, 0))
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, first)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "readersbooks:scan").apply { acquire(60 * 60 * 1000L) }
        val job: Job? = app.remote.runRequested()
        if (job == null) { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            // at most one notification update per second
            while (true) {
                delay(1000)
                app.remote.progress.value?.let { nm.notify(ID, notification(it)) } ?: break
            }
        }
        scope.launch { job.join(); stopSelf() }
        return START_NOT_STICKY
    }

    private fun notification(p: Progress): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_notif).setContentTitle(getString(R.string.notif_scanning)).setContentText(getString(R.string.scanning, p.folders, p.books))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object { private const val CHANNEL = "library_scan"; private const val ID = 7 }
}
