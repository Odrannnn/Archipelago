# Android companion: Metroid Fusion vertical slice

This is a small Android application which connects to the custom mGBA libretro
core at `127.0.0.1:43056`. It verifies the protocol handshake and can read,
guard, and write emulated GBA bus addresses through `MGBABridgeClient`.

`BridgeService` owns this connection as a foreground service. It reconnects
when RetroArch or the core restarts and sends a one-second keepalive while the
game is running. `MainActivity` only starts the service and displays status,
so closing or backgrounding the activity does not close the emulator socket.
The persistent notification exposes an explicit Stop action.

The room-network client adds editable server/password settings and
`ArchipelagoSession`. It supports `ws://` and `wss://`, waits for `RoomInfo`,
authenticates with the ROM's embedded token, requests the Metroid Fusion data
package, and uses the APWorld's `items_handling = 0b011`. Binary zlib packets
are decoded for large data-package and item messages.
If a cleartext WebSocket handshake is closed by the host, the client retries
the same host and port once with TLS (`wss://`), matching the desktop client's
transport fallback behavior.

Enter the game server as `archipelago.gg:PORT` (for example,
`archipelago.gg:45657`), not as a room-page URL. A pasted numeric path such as
`archipelago.gg/45657` is normalized to the equivalent port form.

`MetroidFusionProfile` targets the client in
[`ArchipelagoMine` v1.22.4](https://github.com/StalledStorm/ArchipelagoMine/releases/tag/v1.22.4).
It implements the memory-facing half of that client:

- validates the 20-byte ROM marker at ROM offset `0x7fff00` (`MFU...`) and
  produces the base64 authentication token used by Archipelago;
- converts BizHawk's EWRAM, IWRAM, ROM and System Bus offsets into mGBA GBA
  bus addresses;
- guards all mutable reads/writes with IWRAM `0x0bde == 1`, matching the
  APWorld client so state is never changed in menus, loading, or the title;
- polls minor/major location bitfields, received-item state, map location,
  and credits; and applies every Metroid Fusion item effect defined by the
  APWorld client.

`BridgeService` calls the session's gameplay tick on its one bridge worker.
The tick delivers one queued item at a time, advances the ROM's persistent
receipt counter only after a successful guarded write, reports location bits
as `LocationChecks`, and sends `StatusUpdate` when credits begin. On reconnect,
the server's authoritative item queue and the ROM counter resume at the next
undelivered item. Every three seconds it also reconstructs persistent upgrade,
keycard, Metroid-count, and capacity-maximum state from the full item history;
this restores AP items after loading an older in-game save even though the
separate SRAM receipt counter remains current. DeathLink and player-facing
item messages are shown through RetroArch's on-screen display. Only newly
delivered remote/server items produce an OSD message, so reconnect and
older-save reconciliation cannot replay notification history. Sender aliases
come from the room's `players` metadata and follow later alias updates.

The address and item rules were derived from the APWorld's GPL-3.0 client at
the pinned `v1.22.4` source tag. If this companion is distributed together
with a port of further APWorld code or data, review the APWorld's GPL-3.0
license and publish the required corresponding source.

The companion needs Android's `INTERNET` permission because Android protects
loopback TCP behind the same permission. The native core remains bound to
loopback only and receives no Internet-facing configuration or credentials.
