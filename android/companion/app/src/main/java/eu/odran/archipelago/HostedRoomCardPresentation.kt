package eu.odran.archipelago

import java.io.File

internal data class HostedInviteChoice(
    val slot: Int,
    val playerName: String,
    val game: String,
    val patch: File?,
)

internal data class HostedRoomCardModel(
    val room: HostedRoom,
    val title: String,
    val details: String,
    val isActive: Boolean,
    val isHosted: Boolean,
    val joinedRoom: JoinedRoom?,
    val activePlayerChoices: List<HostedInviteChoice>,
    val patchlessChoices: List<HostedInviteChoice>,
    val linkedSeedCanShareInvite: Boolean?,
    val sohPlayers: List<SohPlayer>,
    val nativePlayerFiles: List<File>,
    val status: RoomStatusPresentation,
)

internal enum class RoomPrimaryAction {
    WAIT,
    ACTIVATE,
    WAKE,
    RETRY,
    CHOOSE_PLAYER,
    LAUNCH_SOH,
    OPEN_PLAYER_FILE,
    NONE,
}

internal data class RoomPrimaryPresentation(
    val action: RoomPrimaryAction,
    val label: String,
    val enabled: Boolean,
)

internal data class RoomSharePresentation(
    val label: String,
    val enabled: Boolean,
)

internal fun roomPrimaryPresentation(
    isActive: Boolean,
    port: Int,
    runtimeState: RoomRuntimeState,
    playerSelected: Boolean,
    playerChoiceCount: Int,
    sohPlayerCount: Int,
    nativePlayerFileNames: List<String>,
): RoomPrimaryPresentation = when {
    runtimeState == RoomRuntimeState.WAKING -> RoomPrimaryPresentation(
        RoomPrimaryAction.WAIT,
        "Waking room…",
        false,
    )
    runtimeState == RoomRuntimeState.REFRESHING -> RoomPrimaryPresentation(
        RoomPrimaryAction.WAIT,
        "Refreshing…",
        false,
    )
    !isActive -> RoomPrimaryPresentation(
        RoomPrimaryAction.ACTIVATE,
        if (!playerSelected && playerChoiceCount > 0) "Choose player & activate" else "Make active",
        true,
    )
    runtimeState == RoomRuntimeState.UNAVAILABLE ->
        RoomPrimaryPresentation(RoomPrimaryAction.RETRY, "Retry room", true)
    port < 0 -> RoomPrimaryPresentation(RoomPrimaryAction.RETRY, "Retry room", true)
    port == 0 -> RoomPrimaryPresentation(RoomPrimaryAction.WAKE, "Wake & refresh", true)
    !playerSelected && playerChoiceCount > 0 -> RoomPrimaryPresentation(
        RoomPrimaryAction.CHOOSE_PLAYER,
        "Choose player",
        true,
    )
    sohPlayerCount > 0 -> RoomPrimaryPresentation(
        RoomPrimaryAction.LAUNCH_SOH,
        "Launch Ship of Harkinian",
        true,
    )
    nativePlayerFileNames.isNotEmpty() -> RoomPrimaryPresentation(
        RoomPrimaryAction.OPEN_PLAYER_FILE,
        if (nativePlayerFileNames.size == 1) {
            PlayerFileLauncher.actionLabel(nativePlayerFileNames.single())
        } else {
            "Choose player file"
        },
        true,
    )
    else -> RoomPrimaryPresentation(RoomPrimaryAction.NONE, "Currently active", false)
}

internal fun roomSharePresentation(
    linkedSeedCanShareInvite: Boolean?,
    patchlessChoiceCount: Int,
): RoomSharePresentation {
    val enabled = linkedSeedCanShareInvite != false || patchlessChoiceCount > 0
    return RoomSharePresentation(
        label = if (enabled) "Share invite" else "Share unavailable",
        enabled = enabled,
    )
}
