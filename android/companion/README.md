# Android companion: GBA game adapters

The Android application ID and Kotlin namespace are both `eu.odran.archipelago`.

This is a small Android application which connects to the custom mGBA libretro
core at `127.0.0.1:43056`. It verifies the protocol handshake and can read,
guard, and write emulated GBA bus addresses through `MGBABridgeClient`.

`BridgeService` owns this connection as a foreground service. It reconnects
when RetroArch or the core restarts and sends a one-second keepalive while the
game is running. `MainActivity` only starts the service and displays status,
so closing or backgrounding the activity does not close the emulator socket.
The persistent notification exposes an explicit Stop action.

The bridge can become available before RetroArch has loaded any content.
`BridgeService` therefore re-probes the ROM marker once per second while the
core remains connected. It starts a room session when a compatible patched ROM
appears and closes that session if the content is unloaded or replaced.
If RetroArch is backgrounded long enough for the local socket to time out, the
service also closes that room session before retrying. The matching v6 core
adopts the newest queued loopback connection on resume, avoiding a stale client
which could otherwise prevent reconnection after switching apps.

The room-network client adds editable server/password settings and
`ArchipelagoSession`. It supports `ws://` and `wss://`, waits for `RoomInfo`,
authenticates with the ROM's embedded token, requests the detected game's data
package, and uses that adapter's item-handling flags. Binary zlib packets
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
separate SRAM receipt counter remains current. Player-facing item messages are
shown through RetroArch's on-screen display. Only newly
delivered remote/server items produce an OSD message, so reconnect and
older-save reconciliation cannot replay notification history. Sender aliases
come from the room's `players` metadata and follow later alias updates.

The address and item rules were derived from the APWorld's GPL-3.0 client at
the pinned `v1.22.4` source tag. If this companion is distributed together
with a port of further APWorld code or data, review the APWorld's GPL-3.0
license and publish the required corresponding source.

## Game adapters

The live bridge and Archipelago room client use a game-adapter registry. A
`GameAdapter` owns ROM detection, authentication, slot-data parsing, location
state, item application, persistent inventory reconciliation, and completion
detection for one game. `ArchipelagoSession` handles the shared WebSocket
protocol without depending on Metroid Fusion memory types.

The registered adapters are Metroid Fusion APWorld `1.22.4` and The Minish Cap
APWorld `0.3.1`. The latter validates `GBAZELDA`, authenticates with the player
name at ROM `0x600`, verifies the room seed at `0x620`, polls its EWRAM location
flags (including special Goron/Cucco state), and injects received item IDs into
the patched game's guarded item queue. Other console families will also need a
compatible libretro-core implementation of the loopback bridge.

The main screen exposes independent mGBA and Archipelago connection indicators.
The server indicator distinguishes connecting, authenticated, and disconnected
states instead of replacing the emulator bridge status. Long-press either line
to view its latest detailed diagnostic.

The companion needs Android's `INTERNET` permission because Android protects
loopback TCP behind the same permission. The native core remains bound to
loopback only and receives no Internet-facing configuration or credentials.

## Offline generation

The **Offline seed generator** screen embeds Python 3.12, the Archipelago 0.6.8
generation core, Metroid Fusion APWorld 1.22.4, and The Minish Cap APWorld
0.3.1. It can edit/export player YAML and generate one-player, same-game
multiplayer, or mixed-game multiworld seeds without a network connection.
**Add player** asks which supported game the new player will use and appends its
template under a unique name. **Change player game** selects one existing player
and replaces only that player's template, preserving every other YAML document.
Both game templates are development-time assets generated from their bundled
APWorld definitions with Archipelago's standard YAML generator. They expose all
documented common and game-specific options, including weighted choices, ranges,
tricks, and filler distribution where applicable. The Python generation module
contains no game-template text, and the Android picker reads its game list from
the asset registry. Each document's options remain independently editable before
generation. Every player receives a separate `.apmetfus` or `.aptmc` patch
according to their game.

Completed runs are copied into persistent app-private seed history together
with their source YAML, room ZIP, player names, and all player patches. A
history entry can restore its exact generation settings, export the ZIP again,
or select any player's patch for local ROM creation. Deleting an entry removes
its stored files. Uninstalling the app or clearing its app data also removes
the history.

Any stored seed ZIP can also be uploaded directly to `archipelago.gg` with
**Host on archipelago.gg**. The companion creates and starts a room, caches its
current status, and can refresh the full room list owned by its persistent
website session. Room controls, trackers, and online server addresses are
available from the hosted-instances section. **Share multiplayer invite** first
selects a player slot, then opens Android's share sheet with a player-specific
`.apinvite` package. The package contains the public room identifiers and that
player's locally stored `.apmetfus` patch, protected by an integrity hash; it
never contains a website-session credential or base ROM. On a second device,
opening the invite verifies and wakes the public room, loads its current port,
remembers the selected player, and reuses a cached legally supplied clean base
ROM or asks for it when no valid cache exists. The embedded generator applies
the patch locally and prompts the recipient to save the ready-to-run `.gba`.
Older metadata-only invites remain supported, and
**Open multiplayer invite** provides a file-picker fallback when a receiving app
does not open the attachment directly. **Sync website session** is separate and
its secret link must not be shared.

The app applies a selected supported player patch to a user-supplied base ROM,
validates the ROM against the checksums declared by its APWorld, and supports a legacy
512-byte copier header. After the first successful validation it stores a
headerless copy in a per-game private no-backup cache and automatically reuses it
for later local and invite patches. **Forget cached base ROM**, clearing app
data, or uninstalling removes the copy. The app never bundles a ROM.

After a patched `.gba` is saved, the companion offers to launch it directly in
the installed 64-bit RetroArch package. The launch intent selects the custom
`mgba_apbridge_v6_libretro_android.so` core and passes RetroArch's own standard
`retroarch.cfg` path without creating or modifying the file, preserving controller
mappings, overrides, and remaps. Each launch starts a fresh RetroArch task so a
suspended video surface cannot leave the game running with audio but no picture.
When the bridge reports that the selected imported room's game, player slot, and
server are already active, the shortcut instead becomes **Return to RetroArch**
and brings that existing activity forward without recreating its native video
surface. This last-known room identity survives the bridge socket dropping while
RetroArch is backgrounded, as well as companion service and process restarts. A
different room or ROM continues to receive a clean launch.
The game therefore opens with the emulator-memory bridge enabled without requiring
a second content/core selection.

Imported multiplayer rooms are kept in a persistent room library instead of
replacing one another. **Manage imported rooms** lists them, marks the active
room, switches the companion and bridge to another room's current server, and
deletes local room records without affecting hosted rooms or ROM files. Existing
single-room data is migrated automatically. **Open in PopTracker** launches the
PopTracker Android app with the active room's current host and port, selected
player name, saved room password, and the room's game identifier so the
matching tracker pack can load automatically. For player-specific rooms, the
companion also remembers the saved ROM document and retains its Android document
permission, so the active-room section can launch that player's existing ROM in
RetroArch without applying the `.apmetfus` patch again. Selecting a different
player slot does not carry the previous player's ROM shortcut across. **Choose
existing patched ROM** can register a `.gba` created before this shortcut was
available, or replace a reference after its file was moved.

The pinned Python source is under `app/src/main/python`. Refresh it after
updating the adjacent `ArchipelagoMine` checkout with:

```powershell
python scripts/sync_offline_generator.py
```

Chaquopy requires the build-machine Python major/minor version to match the
embedded version. If Python 3.12 is not discoverable through the normal
Windows `py`/`python` commands, build with:

```powershell
.\gradlew.bat app:assembleDebug "-Pchaquopy.buildPython=C:\path\to\python.exe"
```

Only `arm64-v8a` is packaged, matching the custom mGBA core. Local generation
remains fully offline; direct upload and website room management require an
Internet connection.

## Release signing

Release APKs use a permanent self-managed signing key for direct distribution.
The key and its credentials must never be committed. By default, Gradle reads
them from `%USERPROFILE%/.android/eu.odran.archipelago-release.properties`;
another file can be selected with
`-Parchipelago.releaseSigningProperties=C:/secure/path/release.properties`.

The properties file uses this format:

```properties
storeFile=C:/secure/path/eu.odran.archipelago-release.p12
storePassword=replace-with-secret
keyAlias=archipelago-release
keyPassword=replace-with-secret
```

Build the signed APK with `./gradlew app:assembleRelease`. Gradle's signing
validation fails the release build when the file, key, or credentials are not
available. Back up both the keystore and its credentials in at least two secure
locations: losing them permanently prevents updates to existing installations.

The direct-distribution certificate SHA-256 fingerprint is:

```text
24:A2:9C:FB:18:31:14:12:29:40:A4:A5:C6:57:C6:05:54:71:C6:D1:90:97:C1:6B:3C:C8:DA:56:F4:64:27:E4
```

This production certificate intentionally differs from the debug certificate
used by older development APKs. Android requires those installations to be
uninstalled before installing the first production-signed release; subsequent
production releases can update normally.
