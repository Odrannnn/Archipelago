package eu.odran.archipelago

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
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
class ClientConsoleActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private lateinit var connection: TextView
    private lateinit var transcript: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var input: EditText
    private var renderedRevision = -1L

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
                renderedRevision = snapshot.revision
                transcript.text = render(snapshot.entries)
                transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connection = TextView(this).apply { CompanionUi.styleMuted(this) }
        transcript = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(CompanionUi.text)
            setTextIsSelectable(true)
            setPadding(
                CompanionUi.dp(this@ClientConsoleActivity, 12),
                CompanionUi.dp(this@ClientConsoleActivity, 10),
                CompanionUi.dp(this@ClientConsoleActivity, 12),
                CompanionUi.dp(this@ClientConsoleActivity, 10),
            )
            setBackgroundColor(Color.WHITE)
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
            addView(LinearLayout(this@ClientConsoleActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(send, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = CompanionUi.dp(this@ClientConsoleActivity, 8) })
            }, CompanionUi.fullWidth())
            addView(LinearLayout(this@ClientConsoleActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(this@ClientConsoleActivity).apply {
                    text = "Show commands"
                    CompanionUi.styleSecondary(this)
                    setOnClickListener { ClientConsoleStore.submit("/help") }
                }, CompanionUi.weightedButtonParams(this@ClientConsoleActivity, 6))
                addView(Button(this@ClientConsoleActivity).apply {
                    text = "Clear transcript"
                    CompanionUi.styleQuiet(this)
                    setOnClickListener { ClientConsoleStore.clear() }
                }, CompanionUi.weightedButtonParams(this@ClientConsoleActivity))
            }, CompanionUi.insetTop(input, this@ClientConsoleActivity, 6))
        }
        SystemBarInsets.apply(window, root)
        setContentView(root)
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
            input.text.clear()
        }
    }

    private fun render(entries: List<ClientConsoleStore.Entry>): CharSequence {
        val builder = SpannableStringBuilder()
        entries.forEachIndexed { index, entry ->
            val start = builder.length
            builder.append(timeFormat.format(Date(entry.timestamp)))
            builder.append(" ")
            builder.append(when (entry.kind) {
                "input" -> "> "
                "server" -> "# "
                "status" -> "• "
                "error" -> "! "
                else -> "  "
            })
            builder.append(entry.text)
            val color = when (entry.kind) {
                "input" -> CompanionUi.primary
                "server" -> Color.rgb(24, 117, 76)
                "status" -> CompanionUi.textMuted
                "error" -> CompanionUi.danger
                else -> CompanionUi.text
            }
            builder.setSpan(ForegroundColorSpan(color), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (index != entries.lastIndex) builder.append("\n")
        }
        return builder
    }
}
