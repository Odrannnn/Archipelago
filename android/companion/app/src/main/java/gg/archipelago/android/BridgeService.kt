package gg.archipelago.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns the emulator connection independently of MainActivity. A foreground
 * service is required because Android may suspend ordinary background app
 * threads while RetroArch is in the foreground.
 */
class BridgeService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    @Volatile private var activeBridge: MGBABridgeClient? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        publish("Starting Archipelago bridge…")
        startForeground(NOTIFICATION_ID, notification(statusText))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            executor.execute(::connectionLoop)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        activeBridge?.close()
        activeBridge = null
        executor.shutdownNow()
        publish("Bridge service stopped")
        super.onDestroy()
    }

    private fun connectionLoop() {
        while (running && !Thread.currentThread().isInterrupted) {
            val bridge = MGBABridgeClient()
            activeBridge = bridge
            try {
                publish("Waiting for the custom mGBA core on 127.0.0.1:${BridgeProtocol.PORT}…")
                bridge.connect()
                val (version, platform) = bridge.hello()
                val sha1 = bridge.romSha1()
                val fusion = MetroidFusionProfile(bridge).romInfoOrNull()
                publish(
                    if (fusion == null) {
                        "mGBA connected · protocol $version · platform $platform · ROM $sha1"
                    } else {
                        "mGBA connected · Metroid Fusion APWorld ${MetroidFusionProfile.APWORLD_VERSION} · ${fusion.name}"
                    },
                )

                while (running && !Thread.currentThread().isInterrupted) {
                    bridge.ping()
                    TimeUnit.SECONDS.sleep(1)
                }
            } catch (error: Exception) {
                if (running) {
                    publish("mGBA unavailable · reconnecting… (${error.message ?: error.javaClass.simpleName})")
                    try {
                        TimeUnit.SECONDS.sleep(1)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            } finally {
                bridge.close()
                if (activeBridge === bridge) activeBridge = null
            }
        }
    }

    private fun publish(message: String) {
        statusText = message
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("Archipelago Companion")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Emulator bridge",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the local Archipelago emulator connection active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "emulator_bridge"
        private const val NOTIFICATION_ID = 43056
        private const val ACTION_STOP = "gg.archipelago.android.STOP_BRIDGE"

        @Volatile
        var statusText: String = "Bridge service has not started"
            private set
    }
}
