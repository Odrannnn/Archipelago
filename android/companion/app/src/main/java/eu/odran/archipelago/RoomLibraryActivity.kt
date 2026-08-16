package eu.odran.archipelago

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/** Lists imported rooms and controls which room supplies the active server settings. */
class RoomLibraryActivity : Activity() {
    private lateinit var roomsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roomsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(this@RoomLibraryActivity).apply {
                text = "Imported multiplayer rooms"
                textSize = 24f
            })
            addView(TextView(this@RoomLibraryActivity).apply {
                text = "Choose the room the companion should use. Imported rooms and saved-ROM shortcuts remain here until you delete them."
                textSize = 16f
                setPadding(0, 8, 0, 20)
            })
            addView(roomsContainer, matchWrapParams())
            addView(Button(this@RoomLibraryActivity).apply {
                text = "Back"
                setOnClickListener { finish() }
            }, matchWrapParams())
        }
        val scrollView = ScrollView(this).apply { addView(content) }
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

        rooms.forEachIndexed { index, room ->
            if (index > 0) roomsContainer.addView(View(this).apply {
                setBackgroundColor(0x22000000)
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            roomsContainer.addView(roomView(room, room.roomId == active?.roomId), matchWrapParams())
        }
    }

    private fun roomView(room: JoinedRoom, isActive: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 20, 0, 20)
        addView(TextView(this@RoomLibraryActivity).apply {
            text = buildString {
                if (isActive) append("✓ Active room\n")
                append(room.playerName?.let { "$it · slot ${room.playerSlot}" } ?: "Imported room")
                append("\nRoom ${room.roomId.take(12)}…")
                append(if (room.port > 0) "\narchipelago.gg:${room.port}" else "\nNo active server port")
                if (room.players.isNotEmpty()) append("\nPlayers: ${room.players.joinToString()}")
                room.patchedRomName?.let { append("\nSaved ROM: $it") }
                if (room.updatedAt > 0) {
                    append("\nUpdated ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(room.updatedAt))}")
                }
            }
            textSize = 17f
        }, matchWrapParams())
        addView(Button(this@RoomLibraryActivity).apply {
            text = if (isActive) "Currently active" else "Switch to this room"
            isEnabled = !isActive
            setOnClickListener { activate(room.roomId) }
        }, matchWrapParams())
        addView(Button(this@RoomLibraryActivity).apply {
            text = "Delete saved room"
            setOnClickListener { confirmDelete(room) }
        }, matchWrapParams())
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
