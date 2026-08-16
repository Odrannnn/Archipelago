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
    @Volatile private var activeSession: RoomSession? = null

    override fun onCreate() {
        super.onCreate()
        restoreActiveRom()
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
        var bridge: MGBABridgeClient? = null
        var runtime: PythonGbaRuntime? = null
        var session: RoomSession? = null
        var sessionSettings: ServerSettings? = null
        var activeGame: ImportedGbaRomInfo? = null
        var activeIdentity: Pair<String, String>? = null
        var nextBridgeAttempt = 0L
        var nextSessionAttempt = 0L
        var nextRomProbeAt = 0L
        var nextPingAt = 0L

        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val now = System.currentTimeMillis()

                if (bridge == null && now >= nextBridgeAttempt) {
                    val candidate = MGBABridgeClient()
                    activeBridge = candidate
                    try {
                        publish("Waiting for the custom mGBA core on 127.0.0.1:${BridgeProtocol.PORT}…")
                        candidate.connect()
                        val (version, platform) = candidate.hello()
                        if (version < BridgeProtocol.SAVEDATA_READ_PROTOCOL_VERSION) {
                            throw IllegalStateException(
                                "The installed custom mGBA core reports bridge protocol $version; " +
                                    "supported live clients require protocol ${BridgeProtocol.SAVEDATA_READ_PROTOCOL_VERSION}",
                            )
                        }

                        val existingRuntime = runtime
                        if (existingRuntime != null && existingRuntime.acceptsPlatform(platform)) {
                            existingRuntime.attachBridge(candidate, platform)
                            if (activeIdentity?.first == "Links Awakening DX") {
                                existingRuntime.bridgeReconnected()
                            }
                        } else {
                            val oldSession = session
                            oldSession?.close()
                            if (activeSession === oldSession) activeSession = null
                            session = null
                            sessionSettings = null
                            existingRuntime?.close()
                            runtime = PythonGbaRuntime(this, candidate, platform)
                            activeGame = null
                            activeIdentity = null
                        }

                        bridge = candidate
                        activeBridge = candidate
                        nextRomProbeAt = 0L
                        nextPingAt = now + TimeUnit.SECONDS.toMillis(1)
                        publish(
                            activeGame?.let { "mGBA reconnected · ${it.game} · room session preserved" }
                                ?: "mGBA connected · protocol $version · platform $platform · waiting for $PATCHED_ROM_DESCRIPTION…",
                        )
                    } catch (error: Exception) {
                        candidate.close()
                        if (activeBridge === candidate) activeBridge = null
                        nextBridgeAttempt = now + TimeUnit.SECONDS.toMillis(1)
                        if (running) {
                            Log.w(TAG, "mGBA unavailable; reconnecting local bridge", error)
                            publish("⚠️ mGBA paused or unavailable · Archipelago session retained")
                        }
                    }
                }

                val connectedBridge = bridge
                val activeRuntime = runtime
                if (connectedBridge != null && activeRuntime != null) {
                    try {
                        // Check the transport before probing the ROM. A paused
                        // RetroArch core cannot answer until retro_run resumes.
                        if (now >= nextPingAt) {
                            connectedBridge.ping()
                            nextPingAt = now + TimeUnit.SECONDS.toMillis(1)
                        }

                        if (now >= nextRomProbeAt) {
                            val detected = if (activeGame?.let { activeRuntime.validateActive(it) } == true) {
                                activeGame
                            } else {
                                activeRuntime.probe()
                            }
                            if (!connectedBridge.isConnected) {
                                error("mGBA stopped responding while the ROM was being inspected")
                            }
                            val detectedIdentity = detected?.let { it.game to it.auth }
                            if (detectedIdentity != activeIdentity) {
                                val oldSession = session
                                oldSession?.close()
                                if (activeSession === oldSession) activeSession = null
                                session = null
                                sessionSettings = null
                                activeGame = detected
                                activeIdentity = detectedIdentity
                                detected?.game?.let { detectedGameName ->
                                    if (activeGameName != detectedGameName) {
                                        activeGameName = detectedGameName
                                        activePlayerSlot = null
                                        activeServerAddress = null
                                        rememberActiveRom()
                                    }
                                }
                                nextSessionAttempt = 0L
                                if (detectedIdentity == null) publishServerWaitingForRom()
                                publish(
                                    detected?.let { "mGBA connected · ${it.game} · live bridge client" }
                                        ?: "mGBA connected · waiting for $PATCHED_ROM_DESCRIPTION…",
                                )
                            }
                            nextRomProbeAt = now + TimeUnit.SECONDS.toMillis(1)
                        }
                    } catch (error: Exception) {
                        activeRuntime.detachBridge(connectedBridge)
                        connectedBridge.close()
                        if (activeBridge === connectedBridge) activeBridge = null
                        bridge = null
                        nextBridgeAttempt = now + TimeUnit.SECONDS.toMillis(1)
                        Log.w(TAG, "Local mGBA bridge paused; preserving room session", error)
                        publish("⚠️ mGBA paused or unavailable · Archipelago session retained")
                    }
                }

                val detected = activeGame
                val currentRuntime = runtime
                if (detected != null && currentRuntime != null) {
                    val settings = ServerSettings.load(this)
                    if (session != null && (session!!.isClosed || sessionSettings != settings)) {
                        val oldSession = session
                        oldSession?.close()
                        if (activeSession === oldSession) activeSession = null
                        session = null
                        sessionSettings = null
                    }
                    if (settings.isConfigured && session == null && now >= nextSessionAttempt) {
                        session = PythonArchipelagoSession(
                            this,
                            settings,
                            currentRuntime,
                            detected,
                            ::publishServerDetails,
                            ::publishServerState,
                        )
                        sessionSettings = settings
                        activeSession = session
                        session?.connect()
                        nextSessionAttempt = now + TimeUnit.SECONDS.toMillis(5)
                    }

                    try {
                        session?.tick(bridge != null)
                        val currentBridge = bridge
                        if (currentBridge != null && !currentBridge.isConnected) {
                            currentRuntime.detachBridge(currentBridge)
                            if (activeBridge === currentBridge) activeBridge = null
                            bridge = null
                            nextBridgeAttempt = now + TimeUnit.SECONDS.toMillis(1)
                            publish("⚠️ mGBA paused or unavailable · Archipelago session retained")
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Archipelago session tick failed; reconnecting room", error)
                        val oldSession = session
                        oldSession?.close()
                        if (activeSession === oldSession) activeSession = null
                        session = null
                        sessionSettings = null
                        nextSessionAttempt = now + TimeUnit.SECONDS.toMillis(1)
                    }

                    session?.connectedSlot?.let { connectedSlot ->
                        val address = sessionSettings?.address
                        if (activePlayerSlot != connectedSlot || activeServerAddress != address) {
                            activePlayerSlot = connectedSlot
                            activeServerAddress = address
                            rememberActiveRom()
                        }
                    }
                }

                if (bridge != null || session != null) {
                    TimeUnit.MILLISECONDS.sleep(125)
                } else {
                    TimeUnit.MILLISECONDS.sleep(500)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            val oldSession = session
            oldSession?.close()
            if (activeSession === oldSession) activeSession = null
            runtime?.close()
            bridge?.close()
            if (activeBridge === bridge) activeBridge = null
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
            "Archipelago will connect after you load a $PATCHED_ROM_DESCRIPTION " +
                "in RetroArch using the custom mGBA core."
        updateNotification()
    }

    private fun publishServerState(state: RoomConnectionState, details: String?) {
        if (stopping) return
        val previousState = lastServerState
        lastServerState = state
        serverStatusText = when (state) {
            RoomConnectionState.CONNECTING -> "⏳ Archipelago connecting"
            RoomConnectionState.CONNECTED -> "✅ Archipelago connected"
            RoomConnectionState.DISCONNECTED -> "⚠️ Archipelago not connected"
        }
        serverStatusDetails = details
        updateNotification()
        if (state == RoomConnectionState.CONNECTED &&
            previousState != RoomConnectionState.CONNECTED
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

    private fun restoreActiveRom() {
        val preferences = getSharedPreferences(ACTIVE_ROM_PREFERENCES, MODE_PRIVATE)
        activeGameName = preferences.getString(ACTIVE_ROM_GAME, null)
        activePlayerSlot = preferences.getInt(ACTIVE_ROM_SLOT, 0).takeIf { it > 0 }
        activeServerAddress = preferences.getString(ACTIVE_ROM_SERVER, null)
    }

    private fun rememberActiveRom() {
        getSharedPreferences(ACTIVE_ROM_PREFERENCES, MODE_PRIVATE).edit().apply {
            activeGameName?.let { putString(ACTIVE_ROM_GAME, it) } ?: remove(ACTIVE_ROM_GAME)
            activePlayerSlot?.let { putInt(ACTIVE_ROM_SLOT, it) } ?: remove(ACTIVE_ROM_SLOT)
            activeServerAddress?.let { putString(ACTIVE_ROM_SERVER, it) } ?: remove(ACTIVE_ROM_SERVER)
        }.apply()
    }

    companion object {
        private const val CHANNEL_ID = "emulator_bridge"
        private const val TAG = "ArchipelagoBridge"
        private const val NOTIFICATION_ID = 43056
        private const val ACTION_STOP = "eu.odran.archipelago.STOP_BRIDGE"
        private const val ACTIVE_ROM_PREFERENCES = "last_active_retroarch_rom"
        private const val ACTIVE_ROM_GAME = "game"
        private const val ACTIVE_ROM_SLOT = "slot"
        private const val ACTIVE_ROM_SERVER = "server"
        private const val PATCHED_ROM_DESCRIPTION = "compatible patched GBA or GBC ROM"
        const val ACTION_RECONNECT = "eu.odran.archipelago.RECONNECT_BRIDGE"

        @Volatile
        private var lastServerState: RoomConnectionState? = null

        @Volatile
        var activeGameName: String? = null
            private set

        @Volatile
        var activePlayerSlot: Int? = null
            private set

        @Volatile
        var activeServerAddress: String? = null
            private set

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
            "Archipelago will connect after you load a compatible patched GBA or GBC ROM " +
                "in RetroArch using the custom mGBA core."
            private set

    }
}
