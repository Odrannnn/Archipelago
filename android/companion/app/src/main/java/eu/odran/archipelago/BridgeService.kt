package eu.odran.archipelago

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns the emulator connection independently of MainActivity. A foreground
 * service is required because Android may suspend ordinary background app
 * threads while RetroArch is in the foreground.
 */
class BridgeService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var activeBridge: MGBABridgeClient? = null
    @Volatile private var activeSession: ArchipelagoSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        publish("Starting Archipelago bridge…")
        publishServerWaitingForRom()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            running = false
            activeBridge?.close()
            activeSession?.close()
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            stopSelf(startId)
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
        stopping = true
        running = false
        activeBridge?.close()
        activeBridge = null
        activeSession?.close()
        activeSession = null
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        statusText = "Bridge service stopped"
        statusDetails = null
        serverStatusText = "⏹️ Archipelago service stopped"
        serverStatusDetails = "Open the companion app to restart the bridge service."
        lastServerState = null
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
                val adapters = GameRegistry.createAdapters(bridge)
                var activeGame: DetectedGame? = null
                publish("mGBA connected · protocol $version · platform $platform · waiting for ${GameRegistry.patchedRomDescription()}…")

                var nextSessionAttempt = 0L
                var nextRomProbeAt = 0L

                while (running && !Thread.currentThread().isInterrupted) {
                    val now = System.currentTimeMillis()
                    if (now >= nextRomProbeAt) {
                        val detected = GameRegistry.detect(adapters)
                        if (detected?.identity != activeGame?.identity) {
                            session?.close()
                            if (activeSession === session) activeSession = null
                            session = null
                            activeGame = detected
                            nextSessionAttempt = 0L
                            if (detected == null) publishServerWaitingForRom()
                            publish(
                                if (detected == null) {
                                    "mGBA connected · waiting for ${GameRegistry.patchedRomDescription()}…"
                                } else {
                                    "mGBA connected · ${detected.adapter.gameName} APWorld ${detected.adapter.apWorldVersion} · ${detected.romInfo.name}"
                                },
                            )
                        }
                        nextRomProbeAt = now + TimeUnit.SECONDS.toMillis(1)
                    }

                    val detectedGame = activeGame
                    val settings = ServerSettings.load(this)
                    if (settings.isConfigured && detectedGame != null &&
                        (session == null || session.isClosed) &&
                        now >= nextSessionAttempt
                    ) {
                        session?.close()
                        session = ArchipelagoSession(
                            settings,
                            detectedGame.adapter,
                            detectedGame.romInfo,
                            ::publishServerDetails,
                            ::publishServerState,
                        )
                        activeSession = session
                        session.connect()
                        nextSessionAttempt = now + TimeUnit.SECONDS.toMillis(5)
                    }
                    if (detectedGame != null) session?.tick()
                    bridge.ping()
                    TimeUnit.SECONDS.sleep(1)
                }
            } catch (error: Exception) {
                if (running) {
                    Log.w(TAG, "mGBA unavailable; reconnecting", error)
                    publish(
                        "⚠️ mGBA not connected",
                        Log.getStackTraceString(error),
                    )
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
                if (running) publishServerWaitingForRom()
            }
        }
    }

    private fun publish(message: String) = publish(message, null)

    private fun publish(message: String, details: String?) {
        if (stopping) return
        statusText = message
        statusDetails = details
        updateNotification()
    }

    private fun publishServerDetails(message: String) {
        if (stopping) return
        serverStatusDetails = message
        updateNotification()
    }

    private fun publishServerWaitingForRom() {
        if (stopping) return
        lastServerState = null
        serverStatusText = "💤 Archipelago waiting for ROM"
        serverStatusDetails =
            "Archipelago will connect after you load a ${GameRegistry.patchedRomDescription()} " +
                "in RetroArch using the custom mGBA core."
        updateNotification()
    }

    private fun publishServerState(state: ArchipelagoSession.ConnectionState, details: String?) {
        if (stopping) return
        val previousState = lastServerState
        lastServerState = state
        serverStatusText = when (state) {
            ArchipelagoSession.ConnectionState.CONNECTING -> "⏳ Archipelago connecting"
            ArchipelagoSession.ConnectionState.CONNECTED -> "✅ Archipelago connected"
            ArchipelagoSession.ConnectionState.DISCONNECTED -> "⚠️ Archipelago not connected"
        }
        serverStatusDetails = details
        updateNotification()
        if (state == ArchipelagoSession.ConnectionState.CONNECTED &&
            previousState != ArchipelagoSession.ConnectionState.CONNECTED
        ) {
            mainHandler.post {
                Toast.makeText(this, "Archipelago connected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateNotification() {
        if (stopping) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
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
            .setContentText("$statusText · $serverStatusText")
            .setStyle(Notification.BigTextStyle().bigText("$statusText\n$serverStatusText"))
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
        private const val TAG = "ArchipelagoBridge"
        private const val NOTIFICATION_ID = 43056
        private const val ACTION_STOP = "eu.odran.archipelago.STOP_BRIDGE"
        const val ACTION_RECONNECT = "eu.odran.archipelago.RECONNECT_BRIDGE"

        @Volatile
        private var lastServerState: ArchipelagoSession.ConnectionState? = null

        @Volatile
        var statusText: String = "Bridge service has not started"
            private set

        @Volatile
        var statusDetails: String? = null
            private set

        @Volatile
        var serverStatusText: String = "💤 Archipelago waiting for ROM"
            private set

        @Volatile
        var serverStatusDetails: String? =
            "Archipelago will connect after you load a compatible patched Metroid Fusion ROM " +
                "in RetroArch using the custom mGBA core."
            private set
    }
}
