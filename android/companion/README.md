# Android companion: emulator Archipelago runtime

For the complete project history, product requirements, repository map,
component architecture, release procedure, and continuation checklist, see
[PROJECT_HANDOFF.md](PROJECT_HANDOFF.md).

The Android application ID and Kotlin namespace are both `eu.odran.archipelago`.

## Dolphin GameCube backend

The companion includes an experimental generic Dolphin backend. It connects to
the dedicated loopback memory service in the Dolphin Archipelago Android fork
on `127.0.0.1:55021` and exposes its memory reads and writes to embedded APWorld
clients under the upstream
`dolphin_memory_engine` Python module name. The compatibility module implements
the desktop package's hook/status, raw and typed big-endian memory access,
pointer-following, and `MemWatch` surface without game-specific addresses in the
transport.

The service starts and stops with emulation, accepts replacement companion
connections after an app or emulator restart, and binds only to loopback. It
transfers binary data directly without pausing the emulated CPU or requiring
any `Dolphin.ini` setting. A world still needs its normal upstream client
bundled or an Android runtime adapter before the companion can join a room for
that game.

The Wind Waker is the GameCube world built into the pinned Archipelago release.
Its upstream client remains authoritative for ROM and slot detection, indexed
item delivery, location checks, DeathLink, chart mappings, goal completion, and
visited-stage tracker data. The Android Dolphin runtime supplies only the shared
transport, room connection, console, and emulator reconnect lifecycle. Load an
ISO randomized with the matching Wind Waker randomizer build in the Dolphin
Archipelago fork; the companion reads the embedded slot name and connects it to
the configured room automatically.

The Dolphin settings card reports two-second transport samples: request rate,
read/write throughput, average and maximum round-trip time, probe traffic, and
failures. It also retains session-wide latency, failures, and peak sampled rate
so a busy interval remains visible after returning from Dolphin. A compact
sample is written to logcat under `ArchipelagoBridge` every ten seconds. These
measurements cover the complete companion-to-Dolphin memory-service round trip;
no memory addresses or values are logged. Unavailable-port messages are rate-limited so a
long wait for Dolphin cannot overwrite the useful connection or failure event in
logcat.

The application connects either to the custom mGBA libretro core at
`127.0.0.1:43056` or the custom SNES9x core at `127.0.0.1:43057`. RetroArch
nightly's UDP Network Commands at `127.0.0.1:55355` remain a fallback. Both SNES
transports run registered upstream SNI client logic without a separate SNI
executable.

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
service also closes that room session before retrying. The matching v9 core
adopts the newest queued loopback connection on resume, avoiding a stale client
which could otherwise prevent reconnection after switching apps.

The room-network client adds editable server/password settings and
`PythonArchipelagoSession`. It supports `ws://` and `wss://`, waits for `RoomInfo`,
authenticates with the ROM's embedded token, requests the detected game's data
package, and uses that APWorld client's item-handling flags. Binary zlib packets
are decoded for large data-package and item messages.
If a cleartext WebSocket handshake is closed by the host, the client retries
the same host and port once with TLS (`wss://`), matching the desktop client's
transport fallback behavior.

Enter the game server as `archipelago.gg:PORT` (for example,
`archipelago.gg:45657`), not as a room-page URL. A pasted numeric path such as
`archipelago.gg/45657` is normalized to the equivalent port form.

## Supported live clients

There is one shared Archipelago context and emulator lifecycle for built-in and
imported games. At startup the Python runtime discovers conventional `client.py`
and `Client.py` modules and registers their standard `BizHawkClient` and
`SNIClient` implementations. The selected client owns ROM detection,
authentication, slot-data handling, location checks, item application,
DeathLink, completion, and any game-specific save reconciliation.

Transport loss, failed memory access, emulator reattachment, and the SNES9x
core's reset-generation changes feed the same generic lifecycle. Recovery
queues one deduplicated Archipelago `Sync`. Stable connections do not emit
periodic `Sync` packets. This layer contains no game-specific memory addresses
and is shared by mGBA and SNI clients.

Kotlin owns the WebSocket and forwards Archipelago packets to that client.
The client console uses the upstream `CommandMeta`/`CommandProcessor` parser
and an Android `ClientCommandProcessor` context. It supports `/help`,
`/received`, `/missing`, `/items`, `/locations`, item/location groups,
`/ready`, room chat, SNI `/slow_mode`, and commands registered by world clients
at runtime, including Kirby's Dream Land 3 `/gift`. `/connect`, `/disconnect`,
`/snes`, `/snes_close`, and `/exit` retain their client meaning while routing
through the Android foreground service. Server `PrintJSON`, client logging,
connection information, and command output share one bounded, color-coded
transcript.

The Android BizHawk compatibility layer translates its reads, guarded reads,
writes, guarded writes, hashes, ROM-domain reads, and on-screen messages into version-6 atomic
transactions handled by the custom mGBA core. The built-in Castlevania: Circle
of the Moon, Mario & Luigi: Superstar Saga, Pokémon Emerald, and Yu-Gi-Oh! 2006
worlds use this path. Imported Metroid Fusion `1.22.4`, The Minish Cap `0.3.1`,
and Wario Land 4 `3.4.0` use the same runtime. Oracle of Seasons `20.1.13` and
Oracle of Ages `1.0.2` are supported community imports using their standard
GBC clients. Other console families still need a compatible libretro-core bridge.

Link's Awakening DX is also bundled. Its custom client protocol is implemented
as an Android adapter using the same version-6 bridge operations against
the Game Boy system bus. It detects the patched `.gbc`, authenticates with the
embedded multiworld key, reports locations and victory, and delivers queued
items without RetroArch's Network Commands interface. Its state machine follows
the desktop client: the live ROM flags and receive index are authoritative, and
the Android layer does not reconstruct checks, rewind saves, or replay cached
server snapshots.

Nine upstream SNI worlds are bundled: A Link to the Past, EarthBound, Final
Fantasy Mystic Quest, Kirby's Dream Land 3, Lufia II Ancient Cave, SMZ3, Super
Mario World, Super Metroid, and Yoshi's Island. `AndroidSNIRuntime` discovers
their unmodified client handlers through the same registry used for compatible
imported clients and preserves each handler's location, item queue, goal,
DeathLink, and slot-data behavior. The Android `SNIContext` ports the desktop
attach/disconnect state transitions, per-cycle ROM validation, handler
selection, command registration, data-storage notifications, player/game name
lookups, error reporting, and buffered-write API. A transport loss clears the
attached ROM/device state but preserves the server's received-item list so a
reset game-side cursor can consume those items again after reattachment.
`Snes9xBridgeClient` is the preferred replacement for SNI's desktop memory
transport. The core exposes mapper-independent FX Pak Pro ROM, SRAM, and WRAM
domains directly and processes requests in `retro_run()`. Its TCP listener
survives `retro_reset()` and PING returns the new reset generation. The older
`RetroArchNetworkClient` LoROM adapter remains as a Network Commands fallback;
the custom core is required for the verified mapper-independent compatibility
shown in the app.

The supported upstream worlds are bundled as Python source and resources inside
the APK. Additional games require a trusted `.apworld` whose conventional client
uses one of the Android bridge families and a compatible cartridge mapping.

APWorlds contain executable code and retain their own licenses. Review an
APWorld's license and publish corresponding source when redistribution requires
it.

The main screen exposes independent emulator and Archipelago connection indicators.
The server indicator distinguishes connecting, authenticated, and disconnected
states instead of replacing the emulator bridge status. Long-press either line
to view its latest detailed diagnostic.

The companion needs Android's `INTERNET` permission because Android protects
loopback TCP behind the same permission. The native core remains bound to
loopback only and receives no Internet-facing configuration or credentials.

## Offline generation

The **Offline seed generator** screen embeds Python 3.12 and the Archipelago
0.6.8 generation core. It can edit/export player YAML and generate one-player,
same-game multiplayer, or mixed-game multiworld seeds without a network
connection using the bundled worlds, or after additional APWorlds are imported.
**Add player** asks which supported game the new player will use and appends its
template under a unique name. **Change player game** selects one existing player
and replaces only that player's template, preserving every other YAML document.
Game templates are generated at runtime from built-in and imported world definitions.
They expose documented common and game-specific options, including weighted
choices, ranges, tricks, and filler distribution where applicable. The Python
generation module contains no game-template text, and the Android picker reads
its game list from the combined world registry. Each document's options remain
independently editable before generation. Every player receives the patch format
registered by their selected APWorld.

The generator can remember named YAML configurations in app-private storage,
ordered newest first. **Load into generator** restores the complete player form;
**Import YAML file** validates and remembers an existing `.yaml` or `.yml` file,
and every library entry identifies each saved player and game. Older entries are
backfilled from their YAML after generator startup. Saved entries can be exported
again or deleted without affecting external copies. Clearing app data removes
this YAML library along with other private data.

The manifest-registered read-only `DocumentsProvider` makes an **Archipelago
Companion** root available to SAF-compatible file managers. **Saved YAMLs**
contains the reusable YAML library; **Generated seeds** contains one folder per
seed with its source YAML, room ZIP, and player patches. Files can be opened or
copied, but external write and delete modes are rejected. The provider resolves
stable document IDs through the stores and canonical seed-history directories;
it does not expose cached base ROMs, website credentials, or room controls.

**Game worlds** shows the bundled games and imports additional trusted `.apworld` files into
`filesDir/offline_generator/worlds`. Installation rejects absolute/traversal
paths, oversized entries and expansions, malformed layouts, duplicate games,
and manifests incompatible with the embedded Archipelago 0.6.8 core. Packages
are extracted rather than imported directly from ZIP so worlds which use
`os.scandir` or adjacent data files continue to work. The Python world registry
then supplies the game list, complete visible option model, player patch suffix,
and output format dynamically. Imported templates use readable scalar defaults
generated from the APWorld's option classes, and can participate in the same
mixed-game YAML and seed flow as other imported games. APWorlds for self-connecting
games may produce only the hostable room ZIP and no player patch; those seeds can be
saved and hosted normally, while ROM creation and player-specific companion invites
remain unavailable. Updating a world consists of
removing it and importing the newer package; after removing an already-loaded
world, fully restart the app before importing another version.

APWorld packages are executable Python code. Archive validation protects the
app's filesystem but cannot sandbox or audit that code, so imports must come
from trusted authors. Worlds may also depend on Python or native modules absent
from the APK; these fail to load with a compatibility diagnostic. Generated
patches are discovered through `AutoPatchRegister`. For registered
`APProcedurePatch` handlers whose result is `.gba`, `.gbc`, `.gb`, `.sfc`, or `.smc`, the companion discovers
the world's checksum-validated `UserFilePath` inputs, stages the selected files,
and invokes the world's normal registered patch method. Other ROM formats remain export-only.

A GBA, GB, or GBC world gains live play when it registers an ordinary
Archipelago `BizHawkClient` and uses connector operations supported by the
Android compatibility layer. Its validation, authentication, location checks,
item delivery, DeathLink, storage messages, and completion logic execute from
the APWorld instead of being translated into Kotlin. Worlds with custom desktop
launchers, extra dependencies, unsupported emulator systems/domains, or other
non-standard client behavior still require compatibility work. The APWorld
manager records Python import failures by package and exposes the full diagnostic
instead of leaving a failed world labelled only as not loaded.

Completed runs are copied into persistent app-private seed history together
with their source YAML, room ZIP, player names, and any player patches. A
history entry can restore its exact generation settings, export the ZIP again,
or, when patches exist, select a player's patch for local ROM creation. Deleting an entry removes
its stored files. Uninstalling the app or clearing its app data also removes
the history.

Any stored seed ZIP can also be uploaded directly to `archipelago.gg` with
**Host on archipelago.gg**. The companion creates and starts a room, caches its
current status, and can refresh the full room list owned by its persistent
website session. **Open website instance list** and **Open room controls** use
an in-app website view which receives that private session cookie, so owner-only
controls work without placing the secret session identifier in a URL. External
links leave the in-app view without receiving its cookie. **Sync website
session** remains the explicit way to grant the normal phone browser the same
access. Trackers, online server addresses, and room-management actions are
available from the dedicated **Hosted rooms** screen on the main page. Room
creation itself remains in the seed generator. **Share multiplayer invite** first
selects a player slot, then opens Android's share sheet with a player-specific
`.apinvite` package. The package contains the public room identifiers and that
player's locally stored APWorld patch, protected by an integrity hash; it
never contains a website-session credential or base ROM. On a second device,
opening the invite verifies and wakes the public room, loads its current port,
remembers the selected player, and reuses cached legally supplied clean ROM
inputs or asks for each missing file. The embedded generator applies the patch
locally and prompts the recipient to save the ready-to-run `.gba`, `.gbc`, `.gb`, `.sfc`, or `.smc`.
Only player-specific version 3 invites are accepted. **Open multiplayer invite**
provides a file-picker fallback when a receiving app
does not open the attachment directly. **Sync website session** is separate and
its secret link must not be shared.

Hosted rooms containing players whose game is `Ship of Harkinian` also show
**Launch Ship of Harkinian**. If the room has multiple SoH players, the companion
first asks which ordered Archipelago slot to use, then asks for the optional room
password. It starts `com.dishii.soh/.MainActivity` with the
`com.dishii.soh.action.CONNECT_ARCHIPELAGO` action and the
`archipelago_address`, `archipelago_slot`, and, when non-empty,
`archipelago_password` extras. The password is never placed in a deep-link URL.
The selected hosted room and player are remembered so the same launch action is
available from the main screen. SoH connects to the Archipelago server directly,
so its active-room view shows direct-launch status and omits the emulator bridge,
ROM-patching, and PopTracker controls.

The app applies a selected supported player patch through its registered APWorld
handler. It discovers all checksum-validated ROM settings declared by that world,
including secondary inputs used by multi-ROM patches. Only after the complete
patch succeeds does it store the exact selected files in a per-game, per-input
private no-backup cache. **Forget cached base ROM**, clearing app data, or
uninstalling removes those copies. The app never bundles a ROM.

After a supported patched ROM is saved, the companion offers to launch it directly in
the installed 64-bit RetroArch package. The launch intent selects either the custom
`mgba_apbridge_v9_libretro_android.so` core or
`snes9x_apbridge_v1_libretro_android.so` and passes RetroArch's own standard
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
matching tracker pack can load automatically. This action is not shown for Ship
of Harkinian, which does not have a compatible PopTracker pack. For player-specific rooms, the
companion also remembers the saved ROM document and retains its Android document
permission, so the active-room section can launch that player's existing ROM in
RetroArch without applying the player patch again. Selecting a different
player slot does not carry the previous player's ROM shortcut across. **Choose
existing patched ROM** can register a `.gb`, `.gbc`, `.gba`, `.sfc`, or `.smc` created before this shortcut was
available, or replace a reference after its file was moved.

The pinned Python source is under `app/src/main/python`. Refresh it after
updating this Archipelago checkout with:

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
