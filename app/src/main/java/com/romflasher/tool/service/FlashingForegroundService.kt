package com.romflasher.tool.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.romflasher.tool.R

/**
 * Keeps the process and CPU awake (PARTIAL_WAKE_LOCK + foreground service) while
 * brick-critical USB flashing, sideloading, EFS, or extraction tasks are running.
 */
class FlashingForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "ROM Flasher Tool"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Operation in progress — keep USB connected"
        ensureChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        acquireWakeLock()
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Active Flashing & Extraction",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps USB flashing and extraction alive in the background"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RomFlasher::ActiveFlashWakeLock").apply {
                setReferenceCounted(false)
                acquire(90 * 60 * 1000L) // max 90 min safety cap
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "rom_flasher_active_ops"
        private const val NOTIF_ID = 4401
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val ACTION_STOP = "com.romflasher.tool.ACTION_STOP_FLASH_SERVICE"

        fun start(context: Context, title: String, text: String = "Operation in progress — keep USB connected") {
            try {
                val intent = Intent(context.applicationContext, FlashingForegroundService::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context.applicationContext, FlashingForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.applicationContext.startService(intent)
            } catch (_: Exception) {
                try {
                    context.applicationContext.stopService(Intent(context.applicationContext, FlashingForegroundService::class.java))
                } catch (_: Exception) {}
            }
        }
    }
}
