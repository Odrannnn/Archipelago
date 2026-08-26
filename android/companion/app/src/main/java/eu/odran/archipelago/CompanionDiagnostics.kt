package eu.odran.archipelago

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/** Creates a support report without room passwords, website cookies, ROM paths, or invite data. */
object CompanionDiagnostics {
    fun report(context: Context): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val snapshot = RoomSessionRepository.snapshot(context)
        val room = snapshot.activeRoom
        val roomStatus = room?.let { roomStatusPresentation(it.port) }
        return buildString {
            appendLine("Archipelago Companion diagnostics")
            appendLine("App: ${packageInfo.versionName} (${PackageInfoCompat.getLongVersionCode(packageInfo)})")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Appearance: ${CompanionThemePreferences.load(context).label}")
            appendLine("Saved rooms: ${snapshot.rooms.size}")
            if (room == null) {
                appendLine("Active room: none")
            } else {
                appendLine("Active room: ${room.roomId}")
                appendLine("Game: ${room.gameName.ifBlank { "unknown" }}")
                appendLine("Player: ${room.playerName ?: "not selected"}")
                appendLine("Slot: ${room.playerSlot ?: "not selected"}")
                appendLine("Room status: ${roomStatus?.label ?: "unknown"}")
                appendLine("Room port: ${room.port.takeIf { it > 0 } ?: "none"}")
                appendLine("Room metadata: ${formatStatusAge(room.updatedAt)}")
            }
            appendLine("Configured server: ${snapshot.serverSettings.address.ifBlank { "none" }}")
            appendLine("Bridge: ${BridgeService.statusText}")
            appendLine("Archipelago connection: ${BridgeService.serverStatusText}")
            BridgeService.statusDetails?.takeIf { it.isNotBlank() }?.let { appendLine("Bridge detail: $it") }
            BridgeService.serverStatusDetails?.takeIf { it.isNotBlank() }?.let {
                appendLine("Connection detail: $it")
            }
        }.trim()
    }
}
