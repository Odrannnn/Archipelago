package eu.odran.archipelago

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal data class HostedRoomCardCallbacks(
    val onActivate: () -> Unit,
    val onWakeOrRefresh: (wake: Boolean) -> Unit,
    val onChoosePlayer: () -> Unit,
    val onLaunchSoh: () -> Unit,
    val onOpenPlayerFile: () -> Unit,
    val onShare: () -> Unit,
    val onMore: (View) -> Unit,
)

/** Reusable renderer for one hosted or joined room in the room library. */
internal class HostedRoomCardView(context: Context) : FrameLayout(context) {
    fun bind(model: HostedRoomCardModel, callbacks: HostedRoomCardCallbacks) {
        removeAllViews()
        val primary = roomPrimaryPresentation(
            isActive = model.isActive,
            port = model.room.lastPort,
            runtimeState = model.status.state,
            playerSelected = model.joinedRoom?.playerSlot != null,
            playerChoiceCount = model.activePlayerChoices.size,
            sohPlayerCount = model.sohPlayers.size,
            nativePlayerFileNames = model.nativePlayerFiles.map { it.name },
        )
        val share = roomSharePresentation(
            model.linkedSeedCanShareInvite,
            model.patchlessChoices.size,
        )
        val panel = CompanionUi.panel(context, active = model.isActive).apply {
            addView(TextView(context).apply {
                text = model.title
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (model.isActive) CompanionUi.active else CompanionUi.text)
            }, matchWrapParams())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (model.isActive) {
                    addView(
                        CompanionUi.statusChip(context, "ACTIVE", CompanionUi.StatusTone.ACTIVE),
                        CompanionUi.wrapContentParams(context, 5),
                    )
                }
                addView(
                    CompanionUi.statusChip(context, if (model.isHosted) "HOSTED" else "JOINED"),
                    CompanionUi.wrapContentParams(context, 5),
                )
                addView(
                    CompanionUi.statusChip(context, model.status),
                    CompanionUi.wrapContentParams(context),
                )
            }, CompanionUi.insetTop(View(context), context, 7))
            addView(TextView(context).apply {
                text = model.details
                CompanionUi.styleMuted(this)
            }, CompanionUi.insetTop(View(context), context, 4))
            addView(Button(context).apply {
                text = primary.label
                isEnabled = primary.enabled
                CompanionUi.stylePrimary(this)
                setOnClickListener {
                    when (primary.action) {
                        RoomPrimaryAction.ACTIVATE -> callbacks.onActivate()
                        RoomPrimaryAction.WAKE -> callbacks.onWakeOrRefresh(true)
                        RoomPrimaryAction.RETRY -> callbacks.onWakeOrRefresh(false)
                        RoomPrimaryAction.CHOOSE_PLAYER -> callbacks.onChoosePlayer()
                        RoomPrimaryAction.LAUNCH_SOH -> callbacks.onLaunchSoh()
                        RoomPrimaryAction.OPEN_PLAYER_FILE -> callbacks.onOpenPlayerFile()
                        RoomPrimaryAction.WAIT, RoomPrimaryAction.NONE -> Unit
                    }
                }
            }, CompanionUi.insetTop(View(context), context, 8))
            val shareButton = Button(context).apply {
                text = share.label
                isEnabled = share.enabled
                CompanionUi.styleSecondary(this)
                setOnClickListener { callbacks.onShare() }
            }
            val moreButton = Button(context).apply {
                text = "More"
                CompanionUi.styleQuiet(this)
                setOnClickListener(callbacks.onMore)
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(shareButton, CompanionUi.weightedButtonParams(context, 5))
                addView(moreButton, CompanionUi.weightedButtonParams(context))
            }, CompanionUi.insetTop(View(context), context, 5))
        }
        addView(panel, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun matchWrapParams() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
