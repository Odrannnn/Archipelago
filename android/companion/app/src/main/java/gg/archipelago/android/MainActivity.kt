package gg.archipelago.android

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** Starts the persistent bridge service and displays its current status. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var inviteStatus: TextView
    private lateinit var address: EditText
    private lateinit var password: EditText
    private lateinit var joinedRoomContainer: LinearLayout
    private val refreshStatus = object : Runnable {
        override fun run() {
            status.text = BridgeService.statusText +
                "\n\nThe bridge continues running when this screen is closed. Use the notification's Stop action to end it."
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val savedSettings = ServerSettings.load(this)
        status = TextView(this).apply {
            textSize = 18f
            text = "Starting background bridge…"
        }

        address = EditText(this).apply {
            hint = "Server address, e.g. archipelago.gg:45657"
            setSingleLine(true)
            setText(savedSettings.address)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        password = EditText(this).apply {
            hint = "Room password (optional)"
            setSingleLine(true)
            setText(savedSettings.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val save = Button(this).apply {
            text = "Save and connect"
            setOnClickListener {
                ServerSettings.save(this@MainActivity, address.text.toString(), password.text.toString())
                startForegroundService(
                    Intent(this@MainActivity, BridgeService::class.java)
                        .setAction(BridgeService.ACTION_RECONNECT),
                )
                status.text = "Settings saved · reconnecting…"
            }
        }
        val generator = Button(this).apply {
            text = "Offline seed generator"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, GeneratorActivity::class.java))
            }
        }
        val openInvite = Button(this).apply {
            text = "Open multiplayer invite"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    },
                    REQUEST_OPEN_INVITE,
                )
            }
        }
        inviteStatus = TextView(this).apply { textSize = 16f }
        joinedRoomContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(this@MainActivity).apply {
                text = "Archipelago Android Companion"
                textSize = 24f
            })
            addView(address, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(password, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(save, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(generator, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(openInvite, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@MainActivity).apply {
                text = "Imported multiplayer room"
                textSize = 22f
                setPadding(0, 24, 0, 8)
            })
            addView(joinedRoomContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(inviteStatus, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        renderJoinedRoom(JoinedRoomStore.load(this))

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, BridgeService::class.java))
        handleInvite(intent)
    }

    @Deprecated("Uses the platform file picker result API available to android.app.Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_INVITE && resultCode == RESULT_OK && data?.data != null) {
            handleInvite(Intent(Intent.ACTION_VIEW, data.data))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInvite(intent)
    }

    private fun handleInvite(sourceIntent: Intent?) {
        val invite = runCatching { RoomInvite.fromIntent(this, sourceIntent) }
            .onFailure { inviteStatus.text = "Could not open invitation: ${it.message}" }
            .getOrNull() ?: return
        // Prevent an activity recreation from presenting the same invitation twice.
        setIntent(Intent(this, MainActivity::class.java))
        AlertDialog.Builder(this)
            .setTitle("Load shared multiplayer room?")
            .setMessage(
                "The companion will verify room ${invite.roomId.take(10)}… on archipelago.gg, wake its server " +
                    "if necessary, and load its current connection address. No website-session secret is imported.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Load room") { _, _ -> resolveAndLoadRoom(invite.roomId) }
            .show()
    }

    private fun resolveAndLoadRoom(roomId: String) {
        inviteStatus.text = "Resolving shared room on archipelago.gg…"
        thread(name = "shared-room-import") {
            runCatching { ArchipelagoWebHostClient(this).resolvePublicRoom(roomId) }
                .onSuccess { room ->
                    val joined = JoinedRoomStore.save(this, room)
                    runOnUiThread {
                        renderJoinedRoom(joined)
                        if (room.lastPort > 0) {
                            val serverAddress = "archipelago.gg:${room.lastPort}"
                            ServerSettings.save(this, serverAddress, "")
                            address.setText(serverAddress)
                            password.setText("")
                            startForegroundService(
                                Intent(this, BridgeService::class.java)
                                    .setAction(BridgeService.ACTION_RECONNECT),
                            )
                            inviteStatus.text = "Invitation loaded · connecting to $serverAddress"
                        } else {
                            inviteStatus.text = if (room.lastPort < 0) {
                                "The invitation was saved, but archipelago.gg reports a server error."
                            } else {
                                "The invitation was saved, but the room is still starting. Tap Refresh room."
                            }
                        }
                    }
                }
                .onFailure { error -> runOnUiThread {
                    inviteStatus.text = "Could not load invitation: ${error.message ?: error.javaClass.simpleName}"
                } }
        }
    }

    private fun renderJoinedRoom(room: JoinedRoom?) {
        joinedRoomContainer.removeAllViews()
        if (room == null) {
            joinedRoomContainer.addView(TextView(this).apply {
                text = "Open a shared .apinvite file to load another player's room automatically."
            })
            return
        }
        joinedRoomContainer.addView(TextView(this).apply {
            text = buildString {
                append(if (room.port > 0) "archipelago.gg:${room.port}" else "Room saved · no active port yet")
                if (room.players.isNotEmpty()) append("\n${room.players.joinToString()}")
            }
            textSize = 16f
        })
        joinedRoomContainer.addView(Button(this).apply {
            text = "Refresh room and reconnect"
            setOnClickListener { resolveAndLoadRoom(room.roomId) }
        }, matchWrapParams())
        joinedRoomContainer.addView(Button(this).apply {
            text = "Open room and player patches"
            setOnClickListener {
                openWebUrl("${ArchipelagoWebHostClient.BASE_URL}/room/${room.roomId}")
            }
        }, matchWrapParams())
        if (room.trackerId.isNotBlank()) {
            joinedRoomContainer.addView(Button(this).apply {
                text = "Open tracker"
                setOnClickListener {
                    openWebUrl("${ArchipelagoWebHostClient.BASE_URL}/tracker/${room.trackerId}")
                }
            }, matchWrapParams())
        }
    }

    private fun openWebUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun matchWrapParams() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    override fun onStart() {
        super.onStart()
        handler.post(refreshStatus)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshStatus)
        super.onStop()
    }

    companion object {
        private const val REQUEST_OPEN_INVITE = 301
    }
}
