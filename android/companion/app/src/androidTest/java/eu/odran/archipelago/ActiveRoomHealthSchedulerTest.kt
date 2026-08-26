package eu.odran.archipelago

import android.Manifest
import android.app.job.JobScheduler
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveRoomHealthSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearRoomAndJob() {
        ActiveRoomHealthScheduler.appEnteredForeground(context)
        context.getSharedPreferences("joined_archipelago_room", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun activeRoomSchedulesNetworkConstrainedHealthJob() {
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE),
        )
        RoomSessionRepository.activate(
            context,
            HostedRoom(
                roomId = "0123456789abcdef0123456789abcdef",
                seedId = "seed",
                creationTime = "",
                lastActivity = "",
                lastPort = 38_281,
                timeoutSeconds = 0,
                trackerId = "",
                players = listOf("Player One (A Link to the Past)"),
            ),
        )

        assertTrue(ActiveRoomHealthScheduler.appEnteredBackground(context))
        assertTrue(
            context.getSystemService(JobScheduler::class.java).allPendingJobs.any { job ->
                job.service.className == ActiveRoomHealthJobService::class.java.name
            },
        )
    }
}
