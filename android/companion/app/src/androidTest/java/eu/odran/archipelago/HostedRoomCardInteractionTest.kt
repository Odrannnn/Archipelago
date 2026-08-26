package eu.odran.archipelago

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostedRoomCardInteractionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun inactiveRoomRoutesPrimaryButtonToActivation() {
        val invoked = AtomicReference<String>()
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(HostedRoomCardView(activity).apply {
                    bind(inactiveRoomModel(), callbacks(invoked))
                })
            }

            onView(withText(R.string.choose_player_activate)).check(matches(isDisplayed()))
            scenario.onActivity { it.findButton(R.string.choose_player_activate).performClick() }
            assertEquals("activate", invoked.get())
        }
    }

    @Test
    fun unavailableActiveRoomRoutesPrimaryButtonToRetry() {
        val invoked = AtomicReference<String>()
        launchHost().use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(HostedRoomCardView(activity).apply {
                    bind(unavailableRoomModel(), callbacks(invoked))
                })
            }

            onView(withText(R.string.retry_room)).check(matches(isDisplayed()))
            scenario.onActivity { it.findButton(R.string.retry_room).performClick() }
            assertEquals("refresh:false", invoked.get())
        }
    }

    private fun launchHost(): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_UI_TEST_MODE, true),
    )

    private fun MainActivity.findButton(label: Int): Button {
        val expected = getString(label)
        fun findIn(group: ViewGroup): Button? {
            for (index in 0 until group.childCount) {
                when (val child = group.getChildAt(index)) {
                    is Button -> if (child.text.toString() == expected) return child
                    is ViewGroup -> findIn(child)?.let { return it }
                }
            }
            return null
        }
        return requireNotNull(findIn(findViewById(android.R.id.content))) {
            "Could not find button labelled $expected"
        }
    }

    private fun inactiveRoomModel() = HostedRoomCardModel(
        room = room(port = 38_281),
        title = "Player One",
        details = "A Link to the Past",
        isActive = false,
        isHosted = true,
        joinedRoom = null,
        activePlayerChoices = listOf(HostedInviteChoice(1, "Player One", "A Link to the Past", null)),
        patchlessChoices = emptyList(),
        linkedSeedCanShareInvite = true,
        sohPlayers = emptyList(),
        nativePlayerFiles = emptyList(),
        status = roomStatusPresentation(38_281),
    )

    private fun unavailableRoomModel() = inactiveRoomModel().copy(
        isActive = true,
        joinedRoom = JoinedRoom(
            roomId = ROOM_ID,
            trackerId = "",
            port = 38_281,
            players = listOf("Player One (A Link to the Past)"),
            updatedAt = 1L,
            playerSlot = 1,
            playerName = "Player One",
            gameName = "A Link to the Past",
        ),
        status = roomStatusPresentation(38_281, available = false),
    )

    private fun room(port: Int) = HostedRoom(
        roomId = ROOM_ID,
        seedId = "seed",
        creationTime = "",
        lastActivity = "",
        lastPort = port,
        timeoutSeconds = 0,
        trackerId = "",
        players = listOf("Player One (A Link to the Past)"),
    )

    private fun callbacks(invoked: AtomicReference<String>) = HostedRoomCardCallbacks(
        onActivate = { invoked.set("activate") },
        onWakeOrRefresh = { wake -> invoked.set("refresh:$wake") },
        onChoosePlayer = { invoked.set("choose") },
        onLaunchSoh = { invoked.set("soh") },
        onOpenPlayerFile = { invoked.set("file") },
        onShare = { invoked.set("share") },
        onMore = { invoked.set("more") },
    )

    private companion object {
        const val ROOM_ID = "0123456789abcdef0123456789abcdef"
    }
}
