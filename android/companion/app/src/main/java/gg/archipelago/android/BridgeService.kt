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
    @Volatile private var activeSession: ArchipelagoSession? = null

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
        if (intent?.action == ACTION_RECONNECT) {
            activeSession?.close()
            activeBridge?.close()
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
        activeSession?.close()
        activeSession = null
        executor.shutdownNow()
        publish("Bridge service stopped")
        super.onDestroy()
    }

    private fun connectionLoop() {
        while (running && !Thread.currentThread().isInterrupted) {
            val bridge = MGBABridgeClient()
            var session: ArchipelagoSession? = null
            activeBridge = bridge
            try {
                publish("Waiting for the custom mGBA core on 127.0.0.1:${BridgeProtocol.PORT}…")
                bridge.connect()
                val (version, platform) = bridge.hello()
                val fusionProfile = MetroidFusionProfile(bridge)
                var fusion: MetroidFusionProfile.RomInfo? = null
                publish("mGBA connected · protocol $version · platform $platform · waiting for patched Metroid Fusion ROM…")

                var nextSessionAttempt = 0L
                var nextRomProbeAt = 0L

                while (running && !Thread.currentThread().isInterrupted) {
                    val now = System.currentTimeMillis()
                    if (now >= nextRomProbeAt) {
                        val detected = fusionProfile.romInfoOrNull()
                        if (detected?.auth != fusion?.auth) {
                            session?.close()
                            if (activeSession === session) activeSession = null
                            session = null
                            fusion = detected
                            nextSessionAttempt = 0L
                            publish(
                                if (detected == null) {
                                    "mGBA connected · waiting for patched Metroid Fusion ROM…"
                                } else {
                                    "mGBA connected · Metroid Fusion APWorld ${MetroidFusionProfile.APWORLD_VERSION} · ${detected.name}"
                                },
                            )
                        }
                        nextRomProbeAt = now + TimeUnit.SECONDS.toMillis(1)
                    }

                    val detectedFusion = fusion
                    val settings = ServerSettings.load(this)
                    if (settings.isConfigured && detectedFusion != null &&
                        (session == null || session.isClosed) &&
                        now >= nextSessionAttempt
                    ) {
                        session?.close()
                        session = ArchipelagoSession(settings, detectedFusion, ::publish)
                        activeSession = session
                        session.connect()
                        nextSessionAttempt = now + TimeUnit.SECONDS.toMillis(5)
                    }
                    if (detectedFusion != null) session?.tick(fusionProfile)
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
                session?.close()
                if (activeSession === session) activeSession = null
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
        const val ACTION_RECONNECT = "gg.archipelago.android.RECONNECT_BRIDGE"

        @Volatile
        var statusText: String = "Bridge service has not started"
            private set
    }
}
