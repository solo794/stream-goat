package io.github.sherazi5110.cloudstream.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.sherazi5110.cloudstream.MainActivity

/**
 * Keeps the app, the CPU and Wi-Fi awake while a TV plays through the phone's proxy: without it the
 * stream would stop soon after the screen turns off. Shows "Casting to …" with a Stop button.
 */
class CastService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while a TV is playing from this device" })
        }
        val n = build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, n)
        }
        runCatching {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "streamgoat:cast")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        runCatching {
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "streamgoat:cast")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) Cast.end()
        if (Cast.device == null && !Cast.connecting) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else runCatching { getSystemService(NotificationManager::class.java).notify(ID, build()) }
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Casting to ${Cast.device?.name ?: "TV"}")
            .setContentText(Cast.title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop casting", stop)
            .build()
    }

    override fun onDestroy() {
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cast"
        private const val ID = 4202
        private const val ACTION_STOP = "stop_cast"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CastService::class.java)) }
                .onFailure { android.util.Log.w("cast", "Couldn't start the cast service: $it") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CastService::class.java)) }
        }
    }
}
