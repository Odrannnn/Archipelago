package eu.odran.archipelago

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Interactive transcript for upstream client commands and Archipelago text. */
class ClientConsoleActivity : CompanionActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private lateinit var connection: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var transcriptScroll: ScrollView
    private lateinit var input: EditText
    private var renderedRevision = -1L
    private var renderedEntryIds = emptyList<Long>()
    private var followTailRequested = false

    private val refresh = object : Runnable {
        override fun run() {
            connection.text = buildString {
                append(BridgeService.activeGameName ?: "Waiting for a supported game")
                BridgeService.activePlayerSlot?.let { append(" · slot $it") }
                append("\n")
                append(BridgeService.serverStatusText)
            }
            val snapshot = ClientConsoleStore.snapshot()
            if (snapshot.revision != renderedRevision) {
                val followTail = followTailRequested || isFollowingTail()
                followTailRequested = false
                renderedRevision = snapshot.revision
                render(snapshot.entries)
                if (followTail) {
                    transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connection = TextView(this).apply { CompanionUi.styleMuted(this) }
        transcript = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                CompanionUi.dp(this@ClientConsoleActivity, 10),
                CompanionUi.dp(this@ClientConsoleActivity, 12),
                CompanionUi.dp(this@ClientConsoleActivity, 10),
                CompanionUi.dp(this@ClientConsoleActivity, 12),
            )
            setBackgroundColor(CompanionUi.background)
        }
        transcriptScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(transcript, CompanionUi.fullWidth())
        }
        input = EditText(this).apply {
            hint = "Command or chat message"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendInput()
                    true
                } else false
            }
        }
        val send = Button(this).apply {
            text = "Send"
            CompanionUi.stylePrimary(this)
            setOnClickListener { sendInput() }
        }
        val root = CompanionUi.screen(this).apply {
            addView(
                CompanionUi.pageTitle(
                    this@ClientConsoleActivity,
                    "Client console",
                    "Desktop-style commands, chat, server text, and game-client information.",
                ),
                CompanionUi.fullWidth(),
            )
            addView(connection, CompanionUi.insetTop(connection, this@ClientConsoleActivity, 4))
            addView(
                transcriptScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ).apply {
                    topMargin = CompanionUi.dp(this@ClientConsoleActivity, 12)
                    bottomMargin = CompanionUi.dp(this@ClientConsoleActivity, 8)
                },
            )
            addView(CompanionUi.inlineActionRow(this@ClientConsoleActivity).apply {
                addView(input)
                addView(send)
            }, CompanionUi.fullWidth())
            addView(CompanionUi.actionRow(this@ClientConsoleActivity).apply {
                addView(Button(this@ClientConsoleActivity).apply {
                    text = "Show commands"
                    CompanionUi.styleSecondary(this)
                    setOnClickListener { ClientConsoleStore.submit("/help") }
                })
                addView(Button(this@ClientConsoleActivity).apply {
                    text = "Clear transcript"
                    CompanionUi.styleQuiet(this)
                    setOnClickListener { ClientConsoleStore.clear() }
                })
            }, CompanionUi.insetTop(input, this@ClientConsoleActivity, 6))
        }
        val responsiveRoot = CompanionUi.responsiveHost(this, root)
        SystemBarInsets.apply(window, responsiveRoot)
        setContentView(responsiveRoot)
        startForegroundService(Intent(this, BridgeService::class.java))
        if (ClientConsoleStore.snapshot().entries.isEmpty()) {
            ClientConsoleStore.append("status", "Console ready. Enter /help to list commands.")
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun sendInput() {
        if (ClientConsoleStore.submit(input.text.toString())) {
            followTailRequested = true
            input.text.clear()
        }
    }

    private fun isFollowingTail(): Boolean {
        if (renderedRevision < 0) return true
        val content = transcriptScroll.getChildAt(0) ?: return true
        val remaining = content.height - transcriptScroll.height - transcriptScroll.scrollY
        return remaining <= CompanionUi.dp(this, 72)
    }

    private fun render(entries: List<ClientConsoleStore.Entry>) {
        val appendFrom = renderedEntryIds.size.takeIf { renderedCount ->
            renderedCount > 0 && entries.size >= renderedCount &&
                entries.subList(0, renderedCount).map { it.id } == renderedEntryIds
        }
        if (appendFrom == null) transcript.removeAllViews()
        if (entries.isEmpty()) {
            transcript.addView(TextView(this).apply {
                text = "No console messages yet."
                textSize = 14f
                setTextColor(CompanionUi.textMuted)
                gravity = Gravity.CENTER
                setPadding(0, CompanionUi.dp(this@ClientConsoleActivity, 24), 0, 0)
            }, CompanionUi.fullWidth())
            renderedEntryIds = emptyList()
            return
        }
        entries.drop(appendFrom ?: 0).forEach { entry ->
            transcript.addView(
                consoleEntry(entry),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = CompanionUi.dp(this@ClientConsoleActivity, 12) },
            )
        }
        renderedEntryIds = entries.map { it.id }
    }

    private fun consoleEntry(entry: ClientConsoleStore.Entry): View {
        val style = consoleStyle(entry.kind)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(CompanionUi.flowRow(this@ClientConsoleActivity).apply {
                addView(consoleChip(
                    timeFormat.format(Date(entry.timestamp)),
                    CompanionUi.textMuted,
                    CompanionUi.neutralSoft,
                    CompanionUi.border,
                    monospace = true,
                ))
                addView(consoleChip(style.label, style.accent, style.badgeFill, style.stroke))
            }, CompanionUi.fullWidth())
            addView(TextView(this@ClientConsoleActivity).apply {
                text = entry.text
                textSize = 14f
                setTextColor(style.textColor)
                setTextIsSelectable(true)
                setLineSpacing(CompanionUi.dp(this@ClientConsoleActivity, 2).toFloat(), 1.05f)
                setPadding(
                    CompanionUi.dp(this@ClientConsoleActivity, 12),
                    CompanionUi.dp(this@ClientConsoleActivity, 10),
                    CompanionUi.dp(this@ClientConsoleActivity, 12),
                    CompanionUi.dp(this@ClientConsoleActivity, 10),
                )
                background = CompanionUi.roundedBackground(
                    this@ClientConsoleActivity,
                    style.bubbleFill,
                    style.stroke,
                    12,
                )
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = CompanionUi.dp(this@ClientConsoleActivity, 5) })
        }
    }

    private fun consoleChip(
        value: String,
        foreground: Int,
        fill: Int,
        stroke: Int,
        monospace: Boolean = false,
    ) = TextView(this).apply {
        text = value
        textSize = 10.5f
        setTextColor(foreground)
        setTypeface(if (monospace) Typeface.MONOSPACE else typeface, Typeface.BOLD)
        includeFontPadding = false
        setPadding(
            CompanionUi.dp(this@ClientConsoleActivity, 7),
            CompanionUi.dp(this@ClientConsoleActivity, 5),
            CompanionUi.dp(this@ClientConsoleActivity, 7),
            CompanionUi.dp(this@ClientConsoleActivity, 5),
        )
        background = CompanionUi.roundedBackground(
            this@ClientConsoleActivity,
            fill,
            stroke,
            10,
        )
    }

    private fun consoleStyle(kind: String): ConsoleStyle = when (kind) {
        "input" -> ConsoleStyle(
            "YOU", CompanionUi.primary, CompanionUi.primarySoft,
            CompanionUi.primarySoft, CompanionUi.primaryBorder, CompanionUi.text,
        )
        "server" -> ConsoleStyle(
            "SERVER", CompanionUi.active, CompanionUi.activeSoft,
            CompanionUi.activeBubble, CompanionUi.activeBorder, CompanionUi.text,
        )
        "status" -> ConsoleStyle(
            "STATUS", CompanionUi.textMuted, CompanionUi.neutralSoft,
            CompanionUi.panelSurface, CompanionUi.border, CompanionUi.textMuted,
        )
        "error" -> ConsoleStyle(
            "ERROR", CompanionUi.danger, CompanionUi.errorSoft,
            CompanionUi.errorBubble, CompanionUi.errorBorder, CompanionUi.danger,
        )
        else -> ConsoleStyle(
            "CLIENT", CompanionUi.primary, CompanionUi.primarySoft,
            CompanionUi.surface, CompanionUi.border, CompanionUi.text,
        )
    }

    private data class ConsoleStyle(
        val label: String,
        val accent: Int,
        val badgeFill: Int,
        val bubbleFill: Int,
        val stroke: Int,
        val textColor: Int,
    )
}
