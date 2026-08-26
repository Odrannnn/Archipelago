package eu.odran.archipelago

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

internal data class MainStartActions(
    val openInvite: () -> Unit,
    val generateSeed: () -> Unit,
    val openRooms: () -> Unit,
)

internal fun mainStartCard(context: Context, actions: MainStartActions): LinearLayout =
    CompanionUi.card(
        context,
        context.getString(R.string.start_something_title),
        context.getString(R.string.start_something_subtitle),
    ).apply {
        addView(Button(context).apply {
            text = context.getString(R.string.open_multiplayer_invite)
            CompanionUi.stylePrimary(this)
            setOnClickListener { actions.openInvite() }
        }, CompanionUi.fullWidth())
        val secondaryActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = context.getString(R.string.generate_seed)
                CompanionUi.styleSecondary(this)
                setOnClickListener { actions.generateSeed() }
            }, CompanionUi.weightedButtonParams(context, 6))
            addView(Button(context).apply {
                text = context.getString(R.string.rooms)
                CompanionUi.styleSecondary(this)
                setOnClickListener { actions.openRooms() }
            }, CompanionUi.weightedButtonParams(context))
        }
        addView(secondaryActions, CompanionUi.insetTop(secondaryActions, context, 6))
    }

internal class MainManualConnectionCard(
    context: Context,
    settings: ServerSettings,
    onConnect: () -> Unit,
    onOpenGameFile: () -> Unit,
) {
    val addressEditor = EditText(context).apply {
        id = View.generateViewId()
        hint = context.getString(R.string.server_address_hint)
        contentDescription = context.getString(R.string.server_address_label)
        setSingleLine(true)
        setText(settings.address)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        textSize = 15f
    }
    val passwordEditor = EditText(context).apply {
        id = View.generateViewId()
        hint = context.getString(R.string.room_password_hint)
        contentDescription = context.getString(R.string.room_password_label)
        setSingleLine(true)
        setText(settings.password)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        textSize = 15f
    }
    val view: LinearLayout

    init {
        val connectButton = Button(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.connect_manually)
            CompanionUi.stylePrimary(this)
            setOnClickListener { onConnect() }
        }
        val openGameButton = Button(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.open_game_file)
            CompanionUi.styleSecondary(this)
            setOnClickListener { onOpenGameFile() }
        }
        addressEditor.nextFocusForwardId = passwordEditor.id
        passwordEditor.nextFocusForwardId = connectButton.id
        connectButton.nextFocusForwardId = openGameButton.id

        val connectionFields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addLabeledEditor(context.getString(R.string.server_address_label), addressEditor)
            addLabeledEditor(context.getString(R.string.room_password_label), passwordEditor)
            addView(connectButton, CompanionUi.insetTop(connectButton, context, 6))
            addView(openGameButton, CompanionUi.insetTop(openGameButton, context, 6))
            addView(TextView(context).apply {
                text = context.getString(R.string.direct_connection_help)
                CompanionUi.styleMuted(this)
                setPadding(0, CompanionUi.dp(context, 8), 0, 0)
            }, CompanionUi.fullWidth())
        }
        view = CompanionUi.card(context, context.getString(R.string.advanced_connection_title)).apply {
            addView(
                CompanionUi.toggleButton(
                    context,
                    context.getString(R.string.advanced_connection_toggle),
                    connectionFields,
                ),
                CompanionUi.fullWidth(),
            )
            addView(connectionFields, CompanionUi.fullWidth())
        }
    }

    private fun LinearLayout.addLabeledEditor(label: String, editor: EditText) {
        addView(TextView(context).apply {
            text = label
            labelFor = editor.id
            CompanionUi.styleMuted(this)
        }, CompanionUi.insetTop(editor, context, if (childCount == 0) 0 else 6))
        addView(editor, CompanionUi.fullWidth())
    }
}
