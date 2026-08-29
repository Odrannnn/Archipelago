package eu.odran.archipelago

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal object FirstRunGuidePreferences {
    private const val PREFERENCES = "first_run_guide"
    private const val DISMISSED = "dismissed"

    fun shouldShow(context: Context): Boolean =
        !context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(DISMISSED, false) &&
            RoomSessionRepository.rooms(context).isEmpty()

    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DISMISSED, true)
            .apply()
    }
}

internal fun firstRunGuideCard(context: Context, onOpenRooms: () -> Unit): LinearLayout =
    CompanionUi.card(
        context,
        context.getString(R.string.quick_start_title),
        context.getString(R.string.quick_start_subtitle),
    ).apply guide@ {
        addView(TextView(context).apply {
            text = context.getString(R.string.quick_start_steps)
            CompanionUi.styleMuted(this)
            setLineSpacing(0f, 1.2f)
        }, CompanionUi.fullWidth())
        val browse = Button(context).apply {
            text = context.getString(R.string.quick_start_rooms)
            CompanionUi.styleSecondary(this)
            setOnClickListener { onOpenRooms() }
        }
        val dismiss = Button(context).apply {
            text = context.getString(R.string.quick_start_dismiss)
            CompanionUi.styleQuiet(this)
            setOnClickListener {
                FirstRunGuidePreferences.dismiss(context)
                this@guide.visibility = View.GONE
            }
        }
        addView(CompanionUi.actionRow(context).apply {
            addView(browse)
            addView(dismiss)
        }, CompanionUi.insetTop(browse, context, 8))
    }
