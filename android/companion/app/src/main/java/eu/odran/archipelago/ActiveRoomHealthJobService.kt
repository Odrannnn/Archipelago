package eu.odran.archipelago

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

internal enum class RoomHealthOutcome {
    HEALTHY,
    SLEEPING,
    FAILURE,
}

internal fun roomHealthNextDelayMillis(
    outcome: RoomHealthOutcome,
    consecutiveFailures: Int = 0,
): Long = when (outcome) {
    RoomHealthOutcome.HEALTHY -> 30L * 60L * 1_000L
    RoomHealthOutcome.SLEEPING -> 6L * 60L * 60L * 1_000L
    RoomHealthOutcome.FAILURE -> {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 4)
        (30L * 60L * 1_000L shl exponent).coerceAtMost(6L * 60L * 60L * 1_000L)
    }
}

internal object RoomHealthStateStore {
    private const val PREFERENCES = "active_room_health"
    private const val LAST_MESSAGE = "last_message"
    private const val LAST_CHECKED_AT = "last_checked_at"
    private const val FAILURES = "failures"

    fun record(context: Context, message: String, failures: Int) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(LAST_MESSAGE, message)
            .putLong(LAST_CHECKED_AT, System.currentTimeMillis())
            .putInt(FAILURES, failures.coerceAtLeast(0))
            .apply()
    }

    fun failures(context: Context): Int =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getInt(FAILURES, 0)

    fun summary(context: Context): String? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val message = preferences.getString(LAST_MESSAGE, null)?.takeIf { it.isNotBlank() } ?: return null
        return "$message · ${formatStatusAge(preferences.getLong(LAST_CHECKED_AT, 0L))}"
    }
}

internal object ActiveRoomHealthScheduler {
    private const val JOB_ID = 0x415048
    private const val INITIAL_DELAY_MILLIS = 15L * 60L * 1_000L

    fun appEnteredForeground(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    fun appEnteredBackground(context: Context): Boolean {
        val room = RoomSessionRepository.activeRoom(context)
        if (room == null || !ArchipelagoWebHostClient.ROOM_ID_PATTERN.matches(room.roomId)) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
            return false
        }
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        return scheduler.getPendingJob(JOB_ID) != null || schedule(context, INITIAL_DELAY_MILLIS)
    }

    fun schedule(context: Context, delayMillis: Long): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, ActiveRoomHealthJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delayMillis.coerceAtLeast(60_000L))
            .setPersisted(true)
            .build()
        return runCatching { scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS }
            .onFailure { Log.e(TAG, "Could not schedule active-room health check", it) }
            .getOrDefault(false)
    }

    private const val TAG = "ActiveRoomHealth"
}

/** Checks only active rooms which were running, so background work never repeatedly wakes sleeping rooms. */
class ActiveRoomHealthJobService : JobService() {
    @Volatile private var runningThread: Thread? = null
    @Volatile private var activeParameters: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        activeParameters = params
        val room = RoomSessionRepository.activeRoom(this)
        if (room == null || !ArchipelagoWebHostClient.ROOM_ID_PATTERN.matches(room.roomId)) {
            activeParameters = null
            return false
        }
        if (room.port <= 0) {
            runningThread = thread(name = "active-room-background-health") {
                RoomHealthStateStore.record(this, getString(R.string.background_room_check_paused), 0)
                finishAndSchedule(params, RoomHealthOutcome.SLEEPING, 0)
            }
            return true
        }
        runningThread = thread(name = "active-room-background-health") {
            val client = ArchipelagoWebHostClient(this)
            runCatching { client.refreshPublicRoom(room.roomId) }
                .onSuccess { refreshed ->
                    client.rememberRoom(refreshed)
                    val refresh = RoomSessionRepository.synchronizeActive(this, refreshed)
                    val current = RoomSessionRepository.activeRoom(this)
                    if (current?.roomId != room.roomId) {
                        finishWithoutReschedule(params)
                        return@onSuccess
                    }
                    val outcome = if (current.port > 0) RoomHealthOutcome.HEALTHY else RoomHealthOutcome.SLEEPING
                    val message = when {
                        current.port > 0 && refresh?.portChanged == true ->
                            getString(R.string.background_room_port_updated, current.port)
                        current.port > 0 -> getString(R.string.background_room_check_succeeded)
                        current.port < 0 -> getString(R.string.background_room_server_error)
                        else -> getString(R.string.background_room_sleeping)
                    }
                    RoomHealthStateStore.record(this, message, 0)
                    if (refresh?.addressChanged == true) {
                        sendBroadcast(Intent(BridgeService.ACTION_ACTIVE_ROOM_ADDRESS_CHANGED).apply {
                            setPackage(packageName)
                        })
                    }
                    finishAndSchedule(params, outcome, 0)
                }
                .onFailure { error ->
                    val failures = RoomHealthStateStore.failures(this) + 1
                    RoomHealthStateStore.record(
                        this,
                        getString(
                            R.string.background_room_check_failed,
                            error.message ?: error.javaClass.simpleName,
                        ),
                        failures,
                    )
                    finishAndSchedule(params, RoomHealthOutcome.FAILURE, failures)
                }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (activeParameters === params) activeParameters = null
        runningThread?.interrupt()
        runningThread = null
        return true
    }

    private fun finishAndSchedule(
        params: JobParameters,
        outcome: RoomHealthOutcome,
        failures: Int,
    ) {
        if (activeParameters !== params) return
        activeParameters = null
        runningThread = null
        jobFinished(params, false)
        ActiveRoomHealthScheduler.schedule(this, roomHealthNextDelayMillis(outcome, failures))
    }

    private fun finishWithoutReschedule(params: JobParameters) {
        if (activeParameters !== params) return
        activeParameters = null
        runningThread = null
        jobFinished(params, false)
    }
}
