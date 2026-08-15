# Android companion: Metroid Fusion vertical slice

This is a small Android application which connects to the custom mGBA libretro
core at `127.0.0.1:43056`. It verifies the protocol handshake and can read,
guard, and write emulated GBA bus addresses through `MGBABridgeClient`.

`BridgeService` owns this connection as a foreground service. It reconnects
when RetroArch or the core restarts and sends a one-second keepalive while the
game is running. `MainActivity` only starts the service and displays status,
so closing or backgrounding the activity does not close the emulator socket.
The persistent notification exposes an explicit Stop action.

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

The remaining companion component is intentionally explicit: an Archipelago
WebSocket room client. It must authenticate with `RomInfo.auth`, map the two
location bitfields through the room data package, send `LocationChecks`, and
call `applyRemoteItemWhileInGame()` before advancing the receipt counter. It
also owns reconnect and DeathLink. This separation keeps room credentials out
of the custom RetroArch core.

The address and item rules were derived from the APWorld's GPL-3.0 client at
the pinned `v1.22.4` source tag. If this companion is distributed together
with a port of further APWorld code or data, review the APWorld's GPL-3.0
license and publish the required corresponding source.

The companion needs Android's `INTERNET` permission because Android protects
loopback TCP behind the same permission. The native core remains bound to
loopback only and receives no Internet-facing configuration or credentials.
