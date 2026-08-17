# Android companion: mGBA Archipelago runtime

The Android application ID and Kotlin namespace are both `eu.odran.archipelago`.

This is a small Android application which connects to the custom mGBA libretro
core at `127.0.0.1:43056`. It verifies the protocol handshake and can read,
guard, and write emulated GBA or Game Boy bus addresses through `MGBABridgeClient`.

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

## Supported mGBA clients

There is one live path for built-in and imported games. At startup the Python
runtime discovers conventional `client.py` and `Client.py` modules, registers
their standard Archipelago `BizHawkClient` implementations, and asks each mGBA
client to validate the loaded ROM. The selected client owns ROM detection,
authentication, slot-data handling, location checks, item application,
DeathLink, completion, and any game-specific save reconciliation.

Kotlin owns the WebSocket and forwards Archipelago packets to that client.
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
items without RetroArch's Network Commands interface.

The five supported upstream worlds are bundled as Python source and resources
inside the APK. Additional games remain unavailable until the user imports a
trusted, compatible `.apworld`.

APWorlds contain executable code and retain their own licenses. Review an
APWorld's license and publish corresponding source when redistribution requires
it.

The main screen exposes independent mGBA and Archipelago connection indicators.
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
`APProcedurePatch` handlers whose result is `.gba`, `.gbc`, or `.gb`, the companion discovers
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
access. Trackers and online server addresses are available from the
hosted-instances section. **Share multiplayer invite** first
selects a player slot, then opens Android's share sheet with a player-specific
`.apinvite` package. The package contains the public room identifiers and that
player's locally stored APWorld patch, protected by an integrity hash; it
never contains a website-session credential or base ROM. On a second device,
opening the invite verifies and wakes the public room, loads its current port,
remembers the selected player, and reuses cached legally supplied clean ROM
inputs or asks for each missing file. The embedded generator applies the patch
locally and prompts the recipient to save the ready-to-run `.gba`, `.gbc`, or `.gb`.
Only player-specific version 3 invites are accepted. **Open multiplayer invite**
provides a file-picker fallback when a receiving app
does not open the attachment directly. **Sync website session** is separate and
its secret link must not be shared.

The app applies a selected supported player patch through its registered APWorld
handler. It discovers all checksum-validated ROM settings declared by that world,
including secondary inputs used by multi-ROM patches. Only after the complete
patch succeeds does it store the exact selected files in a per-game, per-input
private no-backup cache. **Forget cached base ROM**, clearing app data, or
uninstalling removes those copies. The app never bundles a ROM.

After a patched `.gba`, `.gbc`, or `.gb` is saved, the companion offers to launch it directly in
the installed 64-bit RetroArch package. The launch intent selects the custom
`mgba_apbridge_v9_libretro_android.so` core and passes RetroArch's own standard
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
RetroArch without applying the player patch again. Selecting a different
player slot does not carry the previous player's ROM shortcut across. **Choose
existing patched ROM** can register a `.gba` or `.gbc` created before this shortcut was
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
