package eu.odran.archipelago

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat

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
                ViewCompat.setAccessibilityHeading(this, true)
            }, matchWrapParams())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (model.isActive) {
                    addView(
                        CompanionUi.statusChip(
                            context,
                            context.getString(R.string.active_chip),
                            CompanionUi.StatusTone.ACTIVE,
                        ),
                        CompanionUi.wrapContentParams(context, 5),
                    )
                }
                addView(
                    CompanionUi.statusChip(
                        context,
                        context.getString(if (model.isHosted) R.string.hosted_chip else R.string.joined_chip),
                    ),
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
                text = when (primary.action) {
                    RoomPrimaryAction.ACTIVATE -> context.getString(
                        if (!model.isActive && model.joinedRoom?.playerSlot == null && model.activePlayerChoices.isNotEmpty()) {
                            R.string.choose_player_activate
                        } else {
                            R.string.make_active
                        },
                    )
                    RoomPrimaryAction.WAKE -> context.getString(R.string.wake_refresh)
                    RoomPrimaryAction.RETRY -> context.getString(R.string.retry_room)
                    RoomPrimaryAction.CHOOSE_PLAYER -> context.getString(R.string.choose_player)
                    RoomPrimaryAction.LAUNCH_SOH -> context.getString(R.string.launch_soh)
                    RoomPrimaryAction.NONE -> context.getString(R.string.currently_active)
                    RoomPrimaryAction.OPEN_PLAYER_FILE -> if (model.nativePlayerFiles.size > 1) {
                        context.getString(R.string.choose_player_file)
                    } else {
                        primary.label
                    }
                    RoomPrimaryAction.WAIT -> context.getString(
                        if (model.status.state == RoomRuntimeState.WAKING) {
                            R.string.waking_room
                        } else {
                            R.string.refreshing_room
                        },
                    )
                }
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
                text = context.getString(if (share.enabled) R.string.share_invite else R.string.share_unavailable)
                isEnabled = share.enabled
                CompanionUi.styleSecondary(this)
                setOnClickListener { callbacks.onShare() }
            }
            val moreButton = Button(context).apply {
                text = context.getString(R.string.more_actions)
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
