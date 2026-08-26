package eu.odran.archipelago

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/** Legacy joined-room view retained for compatibility; the unified Rooms screen is the main entry point. */
class RoomLibraryActivity : Activity() {
    private lateinit var roomsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roomsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = CompanionUi.screen(this).apply {
            addView(CompanionUi.pageTitle(
                this@RoomLibraryActivity,
                "Joined rooms",
                "Choose which joined multiplayer room the companion should use.",
            ), CompanionUi.fullWidth())
            addView(roomsContainer, CompanionUi.cardParams(this@RoomLibraryActivity))
            addView(Button(this@RoomLibraryActivity).apply {
                text = "Back"
                CompanionUi.styleQuiet(this)
                setOnClickListener { finish() }
            }, CompanionUi.cardParams(this@RoomLibraryActivity, 12))
        }
        val scrollView = CompanionUi.scrollView(this, content)
        SystemBarInsets.apply(window, scrollView)
        setContentView(scrollView)
        renderRooms()
    }

    private fun renderRooms() {
        roomsContainer.removeAllViews()
        val active = JoinedRoomStore.load(this)
        val rooms = JoinedRoomStore.loadAll(this)
        if (rooms.isEmpty()) {
            roomsContainer.addView(TextView(this).apply {
                text = "No rooms saved yet. Open a multiplayer invite from the main screen to add one."
                textSize = 16f
                setPadding(0, 8, 0, 20)
            })
            return
        }

        rooms.forEach { room ->
            roomsContainer.addView(
                roomView(room, room.roomId == active?.roomId),
                CompanionUi.cardParams(this, 10),
            )
        }
    }

    private fun roomView(room: JoinedRoom, isActive: Boolean) = CompanionUi.card(
        this,
        if (isActive) "Active room" else (room.playerName ?: "Imported room"),
    ).apply {
        addView(TextView(this@RoomLibraryActivity).apply {
            text = buildString {
                if (isActive && !room.playerName.isNullOrBlank()) append("${room.playerName} · slot ${room.playerSlot}")
                else if (!room.playerName.isNullOrBlank()) append("Slot ${room.playerSlot}")
                append("\nRoom ${room.roomId.take(12)}…")
                append(if (room.port > 0) "\narchipelago.gg:${room.port}" else "\nNo active server port")
                if (room.players.isNotEmpty()) append("\nPlayers: ${room.players.joinToString()}")
                room.patchedRomName?.let { append("\nSaved ROM: $it") }
                if (room.updatedAt > 0) {
                    append("\nUpdated ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(room.updatedAt))}")
                }
            }
            CompanionUi.styleMuted(this)
        }, matchWrapParams())
        addView(Button(this@RoomLibraryActivity).apply {
            text = if (isActive) "Currently active" else "Switch to this room"
            isEnabled = !isActive
            if (isActive) CompanionUi.styleQuiet(this) else CompanionUi.stylePrimary(this)
            setOnClickListener { activate(room.roomId) }
        }, CompanionUi.insetTop(this, this@RoomLibraryActivity, 8))
        addView(Button(this@RoomLibraryActivity).apply {
            text = "Delete saved room"
            CompanionUi.styleDanger(this)
            setOnClickListener { confirmDelete(room) }
        }, CompanionUi.insetTop(this, this@RoomLibraryActivity, 4))
    }

    private fun activate(roomId: String) {
        val room = JoinedRoomStore.select(this, roomId) ?: return
        setResult(RESULT_OK)
        Toast.makeText(this, "Switched to ${room.playerName ?: "saved room"}", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete(room: JoinedRoom) {
        AlertDialog.Builder(this)
            .setTitle("Delete saved room?")
            .setMessage("This removes the room from the companion. It does not delete your patched ROM or the hosted room on archipelago.gg.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val wasActive = JoinedRoomStore.load(this)?.roomId == room.roomId
                JoinedRoomStore.delete(this, room.roomId)
                setResult(RESULT_OK)
                if (wasActive) finish() else renderRooms()
            }
            .show()
    }

    private fun matchWrapParams() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
