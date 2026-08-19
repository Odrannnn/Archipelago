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
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Owns the emulator connection independently of MainActivity. A foreground
 * service is required because Android may suspend ordinary background app
 * threads while RetroArch is in the foreground.
 */
class BridgeService : Service() {
    private enum class EmulatorTransport { MGBA, SNI }

    private data class ActiveEmulator(
        val transport: EmulatorTransport,
        val runtime: PythonGameRuntime,
        val game: DetectedGameInfo,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val dolphinExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var activeBridge: MGBABridgeClient? = null
    @Volatile private var activeSniClient: SniMemoryClient? = null
    @Volatile private var activeDolphinClient: DolphinGdbClient? = null
    @Volatile private var activeSession: RoomSession? = null
    @Volatile private var reconnectRequested = false
    private var lastConsoleServerDetails = ""

    override fun onCreate() {
        super.onCreate()
        restoreActiveRom()
        dolphinTelemetryText = "Dolphin GDB is not connected."
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
            activeSniClient?.close()
            activeDolphinClient?.close()
            activeSession?.close()
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECONNECT) {
            reconnectRequested = true
            activeSession?.close()
            activeBridge?.close()
            activeSniClient?.close()
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
        activeSniClient?.close()
        activeSniClient = null
        activeDolphinClient?.close()
        activeDolphinClient = null
        activeSession?.close()
        activeSession = null
        executor.shutdownNow()
        dolphinExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        statusText = "Bridge service stopped"
        statusDetails = null
        dolphinTelemetryText = "Dolphin GDB service stopped."
        serverStatusText = "⏹️ Archipelago service stopped"
        serverStatusDetails = "Open the companion app to restart the bridge service."
        lastServerState = null
        super.onDestroy()
    }

    private fun connectionLoop() {
        var mgbaBridge: MGBABridgeClient? = null
        var gbaRuntime: PythonGbaRuntime? = null
        var gbaGame: DetectedGameInfo? = null
        var sniClient: SniMemoryClient? = null
        var sniRuntime: PythonSniRuntime? = null
        var sniGame: DetectedGameInfo? = null
        var dolphinClient: DolphinGdbClient? = null
        var dolphinPort: Int? = null
        var dolphinGameId: String? = null
        var dolphinAttempt: Future<Result<DolphinGdbClient>>? = null
        var pendingDolphinClient: DolphinGdbClient? = null
        var pendingDolphinPort: Int? = null

        var activeTransport: EmulatorTransport? = null
        var activeRuntime: PythonGameRuntime? = null
        var activeGame: DetectedGameInfo? = null
        var session: RoomSession? = null
        var sessionSettings: ServerSettings? = null
        var nextMgbaAttempt = 0L
        var nextSniAttempt = 0L
        var nextSnesPromotionAttempt = 0L
        var nextGbaProbe = 0L
        var nextSniProbe = 0L
        var nextSessionAttempt = 0L
        var nextDolphinAttempt = 0L
        var nextDolphinProbe = 0L
        var nextDolphinTelemetry = 0L
        var nextDolphinTelemetryLog = 0L
        var dolphinPeakRequestRate = 0.0
        var dolphinPeakBandwidth = 0.0
        var sniMemoryAttached = false
        var sniResetGeneration: Long? = null
        var serverPaused = false
        var snesPaused = false

        try {
            publish(
                "Waiting for an Archipelago emulator bridge…",
                "SNES games prefer the custom SNES9x bridge on TCP 127.0.0.1:${Snes9xBridgeClient.DEFAULT_PORT}; " +
                    "RetroArch nightly Network Commands remain available as a fallback. Dolphin's generic GDB " +
                    "backend connects to Dolphin on the configured localhost port.",
            )
            while (running && !Thread.currentThread().isInterrupted) {
                val now = System.currentTimeMillis()

                if (reconnectRequested) {
                    reconnectRequested = false
                    serverPaused = false
                    val oldSession = session
                    oldSession?.close()
                    if (activeSession === oldSession) activeSession = null
                    session = null
                    sessionSettings = null
                    nextSessionAttempt = 0L
                }

                while (true) {
                    val rawCommand = ClientConsoleStore.pollCommand() ?: break
                    val commandRuntime = activeRuntime
                    if (commandRuntime == null) {
                        ClientConsoleStore.append("error", "No active game client. Load a supported patched ROM first.")
                        continue
                    }
                    try {
                        val result = commandRuntime.executeCommand(rawCommand)
                        ClientConsoleStore.append(result.console)
                        result.actions.forEach { action ->
                            when (action.optString("action")) {
                                "connect" -> {
                                    val address = action.optString("address")
                                    if (address.isNotBlank()) {
                                        val existing = ServerSettings.load(this)
                                        ServerSettings.save(this, address, existing.password)
                                    }
                                    serverPaused = false
                                    val oldSession = session
                                    oldSession?.close()
                                    if (activeSession === oldSession) activeSession = null
                                    session = null
                                    sessionSettings = null
                                    nextSessionAttempt = 0L
                                }
                                "disconnect" -> {
                                    serverPaused = true
                                    val oldSession = session
                                    oldSession?.close()
                                    if (activeSession === oldSession) activeSession = null
                                    session = null
                                    sessionSettings = null
                                    publishServerState(
                                        RoomConnectionState.DISCONNECTED,
                                        "Disconnected from the client console. Use /connect to reconnect.",
                                    )
                                }
                                "emulator_disconnect" -> {
                                    snesPaused = true
                                    val oldSni = sniClient
                                    if (oldSni != null) sniRuntime?.detach(oldSni)
                                    oldSni?.close()
                                    if (activeSniClient === oldSni) activeSniClient = null
                                    sniClient = null
                                    sniMemoryAttached = false
                                    sniResetGeneration = null
                                    publish("SNES emulator bridge paused from the client console")
                                }
                                "emulator_connect" -> {
                                    snesPaused = false
                                    val oldSni = sniClient
                                    if (oldSni != null) sniRuntime?.detach(oldSni)
                                    oldSni?.close()
                                    if (activeSniClient === oldSni) activeSniClient = null
                                    sniClient = null
                                    sniMemoryAttached = false
                                    sniResetGeneration = null
                                    nextSniAttempt = 0L
                                }
                                "stop" -> {
                                    stopping = true
                                    running = false
                                    mainHandler.post { stopSelf() }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        ClientConsoleStore.append(
                            "error",
                            "Command failed: ${error.message ?: error.javaClass.simpleName}",
                        )
                        Log.w(TAG, "Client console command failed", error)
                    }
                }

                val configuredDolphinPort = DolphinSettings.load(this).gdbPort
                if ((dolphinClient != null && dolphinPort != configuredDolphinPort) ||
                    (pendingDolphinClient != null && pendingDolphinPort != configuredDolphinPort)
                ) {
                    val connected = dolphinClient
                    val pending = pendingDolphinClient
                    connected?.close()
                    pending?.close()
                    dolphinAttempt?.cancel(true)
                    if (activeDolphinClient === connected || activeDolphinClient === pending) {
                        activeDolphinClient = null
                    }
                    dolphinClient = null
                    dolphinPort = null
                    dolphinGameId = null
                    dolphinAttempt = null
                    pendingDolphinClient = null
                    pendingDolphinPort = null
                    nextDolphinAttempt = 0L
                    dolphinTelemetryText = "Dolphin GDB port changed · restart the running game before reconnecting."
                }

                val completedDolphinAttempt = dolphinAttempt
                if (completedDolphinAttempt?.isDone == true) {
                    val candidate = pendingDolphinClient
                    val attemptedPort = pendingDolphinPort
                    dolphinAttempt = null
                    pendingDolphinClient = null
                    pendingDolphinPort = null
                    val result = try {
                        completedDolphinAttempt.get()
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                    result.onSuccess { connected ->
                        try {
                            check(attemptedPort == DolphinSettings.load(this).gdbPort) {
                                "Dolphin GDB port changed during connection"
                            }
                            DolphinMemoryEngineBridge.attach(this, connected)
                            val gameId = connected.gameId()
                            dolphinClient = connected
                            dolphinPort = checkNotNull(attemptedPort)
                            dolphinGameId = gameId
                            activeDolphinClient = connected
                            nextDolphinProbe = now + TimeUnit.SECONDS.toMillis(1)
                            connected.takeTelemetrySnapshot()
                            nextDolphinTelemetry = now + DOLPHIN_TELEMETRY_INTERVAL_MILLIS
                            nextDolphinTelemetryLog = now + DOLPHIN_TELEMETRY_LOG_INTERVAL_MILLIS
                            dolphinPeakRequestRate = 0.0
                            dolphinPeakBandwidth = 0.0
                            dolphinTelemetryText =
                                "Connected · ${gameId.ifBlank { "unknown game" }} · collecting a live performance sample…"
                            if (activeRuntime == null) {
                                publishDolphinReady(gameId, checkNotNull(attemptedPort))
                            }
                            Log.i(TAG, "Dolphin GDB transport connected on port $attemptedPort for $gameId")
                        } catch (error: Exception) {
                            connected.close()
                            if (activeDolphinClient === connected) activeDolphinClient = null
                            nextDolphinAttempt = now + TimeUnit.SECONDS.toMillis(1)
                            dolphinTelemetryText =
                                "Dolphin accepted the debugger but setup failed · restart emulation to retry."
                            Log.w(TAG, "Dolphin GDB setup failed after connection", error)
                        }
                    }.onFailure { error ->
                        candidate?.close()
                        if (activeDolphinClient === candidate) activeDolphinClient = null
                        nextDolphinAttempt = now + TimeUnit.SECONDS.toMillis(1)
                        dolphinTelemetryText = "Waiting for Dolphin GDB on 127.0.0.1:$configuredDolphinPort…"
                        Log.d(TAG, "Dolphin GDB is not ready: ${error.message}")
                    }
                }

                if (dolphinClient == null && dolphinAttempt == null && now >= nextDolphinAttempt) {
                    val candidate = DolphinGdbClient(port = configuredDolphinPort)
                    pendingDolphinClient = candidate
                    pendingDolphinPort = configuredDolphinPort
                    activeDolphinClient = candidate
                    dolphinTelemetryText = "Waiting for Dolphin GDB on 127.0.0.1:$configuredDolphinPort…"
                    dolphinAttempt = dolphinExecutor.submit<Result<DolphinGdbClient>> {
                        runCatching {
                            candidate.connect()
                            candidate
                        }
                    }
                }

                val connectedDolphin = dolphinClient
                if (connectedDolphin != null && now >= nextDolphinProbe) {
                    try {
                        val gameId = connectedDolphin.gameId()
                        if (gameId != dolphinGameId) {
                            dolphinGameId = gameId
                            if (activeRuntime == null) {
                                publishDolphinReady(gameId, checkNotNull(dolphinPort))
                            }
                        }
                        nextDolphinProbe = now + TimeUnit.SECONDS.toMillis(1)
                    } catch (error: Exception) {
                        val lastTelemetry = dolphinTelemetryText
                        connectedDolphin.close()
                        if (activeDolphinClient === connectedDolphin) activeDolphinClient = null
                        dolphinClient = null
                        dolphinPort = null
                        dolphinGameId = null
                        nextDolphinAttempt = now + TimeUnit.SECONDS.toMillis(1)
                        dolphinTelemetryText =
                            "Dolphin GDB disconnected · restart the game to reopen its debugger.\n" +
                                "Last live sample:\n$lastTelemetry"
                        Log.w(TAG, "Dolphin GDB transport disconnected", error)
                    }
                }

                val measuredDolphin = dolphinClient
                val measuredPort = dolphinPort
                if (measuredDolphin != null && measuredPort != null && now >= nextDolphinTelemetry) {
                    val snapshot = measuredDolphin.takeTelemetrySnapshot()
                    val rates = DolphinTelemetryFormatter.rates(snapshot)
                    dolphinPeakRequestRate = maxOf(dolphinPeakRequestRate, rates.requestsPerSecond)
                    dolphinPeakBandwidth = maxOf(dolphinPeakBandwidth, rates.kibibytesPerSecond)
                    dolphinTelemetryText = DolphinTelemetryFormatter.display(
                        snapshot,
                        dolphinGameId.orEmpty(),
                        measuredPort,
                        dolphinPeakRequestRate,
                        dolphinPeakBandwidth,
                    )
                    if (now >= nextDolphinTelemetryLog) {
                        Log.i(
                            TAG,
                            DolphinTelemetryFormatter.logLine(snapshot, dolphinGameId.orEmpty(), measuredPort),
                        )
                        nextDolphinTelemetryLog = now + DOLPHIN_TELEMETRY_LOG_INTERVAL_MILLIS
                    }
                    nextDolphinTelemetry = now + DOLPHIN_TELEMETRY_INTERVAL_MILLIS
                }

                if (mgbaBridge == null && now >= nextMgbaAttempt) {
                    var candidate: MGBABridgeClient? = null
                    try {
                        candidate = MGBABridgeClient()
                        activeBridge = candidate
                        candidate.connect()
                        val (version, platform) = candidate.hello()
                        require(version >= BridgeProtocol.MIN_SUPPORTED_PROTOCOL_VERSION) {
                            "The installed custom mGBA core reports bridge protocol $version; " +
                                "version ${BridgeProtocol.MIN_SUPPORTED_PROTOCOL_VERSION} is required"
                        }
                        val existing = gbaRuntime
                        if (existing != null && existing.acceptsPlatform(platform)) {
                            existing.attachBridge(candidate, platform)
                            existing.emulatorReattached()
                        } else {
                            existing?.close()
                            gbaRuntime = PythonGbaRuntime(this, candidate, platform)
                            gbaGame = null
                        }
                        mgbaBridge = candidate
                        activeBridge = candidate
                        nextGbaProbe = 0L
                        publish("mGBA connected · protocol $version · inspecting patched ROM…")
                    } catch (error: Exception) {
                        candidate?.close()
                        if (activeBridge === candidate) activeBridge = null
                        nextMgbaAttempt = now + TimeUnit.SECONDS.toMillis(1)
                    }
                }

                val connectedMgba = mgbaBridge
                val currentGbaRuntime = gbaRuntime
                if (connectedMgba != null && currentGbaRuntime != null && now >= nextGbaProbe) {
                    try {
                        connectedMgba.ping()
                        gbaGame = if (gbaGame?.let(currentGbaRuntime::validateActive) == true) {
                            gbaGame
                        } else {
                            currentGbaRuntime.probe()
                        }
                        nextGbaProbe = now + TimeUnit.SECONDS.toMillis(1)
                    } catch (error: Exception) {
                        currentGbaRuntime.detachBridge(connectedMgba)
                        connectedMgba.close()
                        if (activeBridge === connectedMgba) activeBridge = null
                        mgbaBridge = null
                        nextMgbaAttempt = now + TimeUnit.SECONDS.toMillis(1)
                        Log.w(TAG, "Local mGBA bridge paused", error)
                    }
                }

                if (!snesPaused && sniClient == null && now >= nextSniAttempt) {
                    var candidate: SniMemoryClient? = null
                    try {
                        val connected = connectPreferredSniClient()
                        candidate = connected.first
                        val status = connected.second
                        activeSniClient = candidate
                        val existing = sniRuntime
                        if (existing == null) {
                            sniRuntime = PythonSniRuntime(this, candidate)
                        } else {
                            existing.attach(candidate)
                        }
                        sniClient = candidate
                        activeSniClient = candidate
                        sniResetGeneration = status.resetGeneration
                        nextSnesPromotionAttempt = now + TimeUnit.SECONDS.toMillis(2)
                        nextSniProbe = 0L
                        publish("${status.description} connected · inspecting SNI-compatible ROM…")
                        Log.i(TAG, "Connected SNES transport: ${status.description}")
                    } catch (error: Exception) {
                        candidate?.close()
                        if (activeSniClient === candidate) activeSniClient = null
                        nextSniAttempt = now + TimeUnit.SECONDS.toMillis(1)
                    }
                }

                if (
                    sniClient is RetroArchNetworkClient &&
                    now >= nextSnesPromotionAttempt
                ) {
                    var promoted: Snes9xBridgeClient? = null
                    try {
                        promoted = Snes9xBridgeClient().apply { connect() }
                        val status = promoted.checkStatus()
                        val fallback = sniClient
                        val runtime = sniRuntime
                        runtime?.emulatorDetached()
                        runtime?.attach(promoted)
                        sniClient = promoted
                        activeSniClient = promoted
                        sniResetGeneration = status.resetGeneration
                        sniMemoryAttached = false
                        nextSniProbe = 0L
                        fallback?.close()
                        publish("${status.description} connected · inspecting SNI-compatible ROM…")
                        Log.i(TAG, "Promoted SNES transport from Network Commands to ${status.description}")
                    } catch (_: Exception) {
                        promoted?.close()
                        nextSnesPromotionAttempt = now + TimeUnit.SECONDS.toMillis(2)
                    }
                }

                val connectedSni = sniClient
                val currentSniRuntime = sniRuntime
                if (connectedSni != null && currentSniRuntime != null && now >= nextSniProbe) {
                    try {
                        val status = connectedSni.checkStatus()
                        val generation = status.resetGeneration
                        if (
                            generation != null && sniResetGeneration != null &&
                            generation != sniResetGeneration
                        ) {
                            currentSniRuntime.emulatorDetached()
                            currentSniRuntime.emulatorReattached()
                            sniMemoryAttached = true
                            Log.i(
                                TAG,
                                "SNES reset generation changed from $sniResetGeneration to $generation; " +
                                    "replayed SNI attach lifecycle",
                            )
                        }
                        sniResetGeneration = generation
                        sniGame = if (sniGame?.let(currentSniRuntime::validateActive) == true) {
                            sniGame
                        } else {
                            currentSniRuntime.probe()
                        }
                        if (sniGame == null) {
                            sniMemoryAttached = false
                        } else if (!sniMemoryAttached) {
                            currentSniRuntime.emulatorReattached()
                            sniMemoryAttached = true
                            Log.i(TAG, "SNES SNI memory validated and attached")
                        }
                        nextSniProbe = now + TimeUnit.SECONDS.toMillis(1)
                    } catch (error: Exception) {
                        currentSniRuntime.detach(connectedSni)
                        sniMemoryAttached = false
                        sniResetGeneration = null
                        connectedSni.close()
                        if (activeSniClient === connectedSni) activeSniClient = null
                        sniClient = null
                        nextSniAttempt = now + TimeUnit.SECONDS.toMillis(1)
                        Log.w(TAG, "SNES memory bridge paused", error)
                    }
                }

                val gbaCandidate = gbaGame?.takeIf { mgbaBridge != null }?.let {
                    ActiveEmulator(EmulatorTransport.MGBA, checkNotNull(gbaRuntime), it)
                }
                val sniCandidate = sniGame?.takeIf { sniClient != null }?.let {
                    ActiveEmulator(EmulatorTransport.SNI, checkNotNull(sniRuntime), it)
                }
                val currentCandidate = when (activeTransport) {
                    EmulatorTransport.MGBA -> gbaCandidate
                    EmulatorTransport.SNI -> sniCandidate
                    null -> null
                }
                val alternateCandidate = when (activeTransport) {
                    EmulatorTransport.MGBA -> sniCandidate
                    EmulatorTransport.SNI -> gbaCandidate
                    null -> sniCandidate ?: gbaCandidate
                }
                val activeTransportUnavailable = when (activeTransport) {
                    EmulatorTransport.MGBA -> mgbaBridge == null
                    EmulatorTransport.SNI -> sniClient == null
                    null -> false
                }
                val desired = currentCandidate ?: alternateCandidate ?: if (
                    activeTransportUnavailable && activeRuntime != null && activeGame != null
                ) {
                    ActiveEmulator(checkNotNull(activeTransport), activeRuntime, activeGame)
                } else {
                    null
                }

                val identityChanged = desired?.let { it.transport to (it.game.game to it.game.auth) } !=
                    activeTransport?.let { transport ->
                        activeGame?.let { game -> transport to (game.game to game.auth) }
                    }
                if (identityChanged) {
                    val oldSession = session
                    oldSession?.close()
                    if (activeSession === oldSession) activeSession = null
                    session = null
                    sessionSettings = null
                    activeTransport = desired?.transport
                    activeRuntime = desired?.runtime
                    activeGame = desired?.game
                    nextSessionAttempt = 0L
                    serverPaused = false

                    activeGameName = desired?.game?.game
                    activePlayerSlot = null
                    activeServerAddress = null
                    rememberActiveRom()
                    if (desired == null) {
                        publishServerWaitingForRom()
                        publish("Emulator bridge ready · waiting for a supported patched ROM…")
                    } else {
                        val emulator = if (desired.transport == EmulatorTransport.SNI) "SNES emulator" else "mGBA"
                        publish("$emulator connected · ${desired.game.game} · live bridge client")
                    }
                }

                val detected = activeGame
                val runtime = activeRuntime
                if (detected != null && runtime != null) {
                    val settings = ServerSettings.load(this)
                    if (session != null && (session!!.isClosed || sessionSettings != settings)) {
                        val oldSession = session
                        oldSession?.close()
                        if (activeSession === oldSession) activeSession = null
                        session = null
                        sessionSettings = null
                    }
                    if (!serverPaused && settings.isConfigured && session == null && now >= nextSessionAttempt) {
                        session = PythonArchipelagoSession(
                            settings,
                            runtime,
                            detected,
                            ::publishServerDetails,
                            ::publishServerState,
                        )
                        sessionSettings = settings
                        activeSession = session
                        session?.connect()
                        nextSessionAttempt = now + TimeUnit.SECONDS.toMillis(5)
                    }

                    val emulatorAvailable = when (activeTransport) {
                        EmulatorTransport.MGBA -> mgbaBridge != null
                        EmulatorTransport.SNI -> sniClient != null
                        null -> false
                    }
                    try {
                        session?.tick(emulatorAvailable)
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

                if (mgbaBridge != null || sniClient != null || session != null) {
                    TimeUnit.MILLISECONDS.sleep(125)
                } else {
                    TimeUnit.MILLISECONDS.sleep(500)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.e(TAG, "Bridge loop stopped after an internal error", error)
            publish(
                "Bridge paused after an internal error",
                Log.getStackTraceString(error),
            )
        } finally {
            val oldSession = session
            runCatching { oldSession?.close() }
            if (activeSession === oldSession) activeSession = null
            runCatching { gbaRuntime?.close() }
            runCatching { sniRuntime?.close() }
            runCatching { mgbaBridge?.close() }
            runCatching { sniClient?.close() }
            runCatching { pendingDolphinClient?.close() }
            dolphinAttempt?.cancel(true)
            runCatching { dolphinClient?.close() }
            if (activeBridge === mgbaBridge) activeBridge = null
            if (activeSniClient === sniClient) activeSniClient = null
            if (activeDolphinClient === dolphinClient || activeDolphinClient === pendingDolphinClient) {
                activeDolphinClient = null
            }
            if (!stopping) dolphinTelemetryText = "Dolphin GDB is not connected."
            running = false
        }
    }

    private fun publishDolphinReady(gameId: String, port: Int) {
        val title = gameId.ifBlank { "game not identified" }
        publish(
            "Dolphin connected · $title · generic memory backend ready",
            "Connected to Dolphin's GDB server on 127.0.0.1:$port. Waiting for a compatible GameCube " +
                "Archipelago client. Dolphin's stock GDB server is unauthenticated and binds to every network interface.",
        )
    }

    private fun connectPreferredSniClient(): Pair<SniMemoryClient, SniTransportStatus> {
        var lastError: Exception? = null
        val factories: List<() -> SniMemoryClient> = listOf(
            { Snes9xBridgeClient().apply { connect() } },
            { RetroArchNetworkClient() },
        )
        factories.forEach { factory ->
            var candidate: SniMemoryClient? = null
            try {
                candidate = factory()
                return candidate to candidate.checkStatus()
            } catch (error: Exception) {
                candidate?.close()
                lastError = error
            }
        }
        throw IllegalStateException("No SNES memory transport is available", lastError)
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
        if (message != lastConsoleServerDetails) {
            lastConsoleServerDetails = message
            ClientConsoleStore.append("status", message)
        }
        updateNotification()
    }

    private fun publishServerWaitingForRom() {
        if (stopping) return
        lastServerState = null
        serverStatusText = "💤 Archipelago waiting for ROM"
        serverStatusDetails =
            "Archipelago will connect after you load a $PATCHED_ROM_DESCRIPTION in RetroArch. " +
                "SNES games use the custom SNES9x Archipelago core when installed."
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
        private const val PATCHED_ROM_DESCRIPTION = "compatible patched Game Boy or SNES ROM"
        private const val DOLPHIN_TELEMETRY_INTERVAL_MILLIS = 2_000L
        private const val DOLPHIN_TELEMETRY_LOG_INTERVAL_MILLIS = 10_000L
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
        var dolphinTelemetryText: String = "Dolphin GDB is not connected."
            private set

        @Volatile
        var serverStatusText: String = "💤 Archipelago waiting for ROM"
            private set

        @Volatile
        var serverStatusDetails: String? =
            "Archipelago will connect after you load a compatible patched Game Boy or SNES ROM in RetroArch."
            private set

    }
}
