// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.prestgg.hoparlor

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.InetAddress
import java.util.concurrent.Executors

sealed interface PlayState {
    data object Idle : PlayState
    data class Connecting(val label: String) : PlayState
    data class Playing(val label: String, val host: String, val port: Int) : PlayState
    data class Failed(val message: String) : PlayState
}

/** Shared between the service and the UI (Compose snapshot state is thread-safe to write). */
object Playback {
    var state by mutableStateOf<PlayState>(PlayState.Idle)
}

/**
 * Foreground service that keeps playback alive with the screen off and holds the Wi-Fi locks
 * that stop the radio from entering power-save (the main source of Wi-Fi jitter).
 */
class PlaybackService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var wifiLocks = emptyList<WifiManager.WifiLock>()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, Discovery.DEFAULT_PORT)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: host
                val target = intent.getIntExtra(EXTRA_TARGET, 20)
                val adaptive = intent.getBooleanExtra(EXTRA_ADAPTIVE, true)
                goForeground(label)
                acquireLocks()
                Playback.state = PlayState.Connecting(label)
                worker.execute { connect(host, port, label, target, adaptive) }
            }
        }
        return START_NOT_STICKY
    }

    private fun connect(host: String, port: Int, label: String, target: Int, adaptive: Boolean) {
        NativeEngine.stop()
        val ip = try {
            InetAddress.getByName(host).hostAddress
        } catch (e: Exception) {
            null
        }
        if (ip == null || ip.contains(':')) {
            fail(getString(R.string.error_resolve, host))
            return
        }
        if (NativeEngine.start(ip, port, target, adaptive)) {
            Playback.state = PlayState.Playing(label, ip, port)
        } else {
            fail(getString(R.string.error_audio))
        }
    }

    private fun fail(message: String) {
        NativeEngine.stop()
        Playback.state = PlayState.Failed(message)
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdown() {
        worker.execute { NativeEngine.stop() }
        Playback.state = PlayState.Idle
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        worker.execute { NativeEngine.stop() }
        worker.shutdown()
        releaseLocks()
        if (Playback.state !is PlayState.Failed) Playback.state = PlayState.Idle
        super.onDestroy()
    }

    private fun goForeground(label: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(label)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.disconnect), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun acquireLocks() {
        if (wifiLocks.isNotEmpty()) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val modes = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
            @Suppress("DEPRECATION")
            add(WifiManager.WIFI_MODE_FULL_HIGH_PERF)
        }
        wifiLocks = modes.map { mode ->
            wifi.createWifiLock(mode, "hoparlor:$mode").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hoparlor:playback").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wifiLocks.forEach { if (it.isHeld) it.release() }
        wifiLocks = emptyList()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "io.github.prestgg.hoparlor.START"
        const val ACTION_STOP = "io.github.prestgg.hoparlor.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_LABEL = "label"
        const val EXTRA_TARGET = "target"
        const val EXTRA_ADAPTIVE = "adaptive"

        fun start(ctx: Context, host: String, port: Int, label: String, targetMs: Int, adaptive: Boolean) {
            val i = Intent(ctx, PlaybackService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_TARGET, targetMs)
                .putExtra(EXTRA_ADAPTIVE, adaptive)
            // Set synchronously so UI logic (auto-connect) never fires twice before the
            // service gets to run.
            Playback.state = PlayState.Connecting(label)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, PlaybackService::class.java).setAction(ACTION_STOP))
        }
    }
}
