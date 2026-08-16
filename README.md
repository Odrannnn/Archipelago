# Archipelago Android Companion for GBA

<p align="center">
  ☕ <a href="https://ko-fi.com/odrannnn">Support this little Android adventure on Ko-fi</a> 💙
</p>

## ⚠️ AI-generated code disclaimer

> **All Android-specific code, emulator-bridge changes, companion features, and
> project documentation introduced by this fork were generated with OpenAI
> Codex under human direction. The generated code may contain defects,
> security issues, or incomplete assumptions and should be independently
> reviewed and tested before production use or redistribution.**

This disclaimer does not apply to the upstream Archipelago project or to
third-party components and source incorporated from Archipelago, mGBA,
RetroArch, ArchipelagoMine, or the bundled APWorlds. Those projects retain
their original authorship, history, and licenses.

This fork adds a self-contained Android workflow for playing GBA multiworlds
with [Archipelago](https://archipelago.gg). It combines an Android companion app
with a custom mGBA libretro core so RetroArch can exchange game state with an
Archipelago room without a PC.

The Android app includes native live adapters for the Metroid Fusion APWorld from
[ArchipelagoMine 1.22.4](https://github.com/StalledStorm/ArchipelagoMine/releases/tag/v1.22.4)
and The Minish Cap APWorld
[v0.3.1](https://github.com/eternalcode0/Archipelago/releases/tag/v0.3.1). It
can also run standard GBA `BizHawkClient` implementations from trusted imported
APWorlds through a reusable Python-to-mGBA compatibility layer; Wario Land 4
APWorld 3.4.0 is the first checked example.
The rest of this repository remains based on the upstream
[Archipelago project](https://github.com/ArchipelagoMW/Archipelago).

> This is an independent fork and is not an official Archipelago Android
> client. It does not include any commercial ROM.

## What it does

### Play supported GBA multiworlds on Android

- Connects RetroArch's emulated GBA memory to the companion through a
  loopback-only bridge at `127.0.0.1:43056`.
- Detects compatible built-in ROMs and imported standard GBA APWorld clients,
  then reads the authentication data defined by each client.
- Connects to Archipelago rooms over WebSockets, including TLS fallback.
- Reports checked locations and game completion.
- Receives and applies items, upgrades, capacities, keycards, Metroid count,
  and persistent state.
- The Metroid Fusion adapter restores received upgrades after an older in-game save is loaded.
- Displays new item messages through RetroArch's
  on-screen display.
- Reconnects after RetroArch restarts, ROM reloads, app switching, suspension,
  and stale bridge connections.

The bridge runs as an Android foreground service, so split-screen mode is not
required.

### Generate and patch seeds without a PC

The companion embeds Python 3.12, the Archipelago 0.6.8 generation components,
and both supported APWorlds. On the phone it can:

- create and edit per-player YAML documents with an independent game choice;
- generate single-player, same-game multiplayer, or mixed-game multiworld seeds offline;
- retain seed history, settings, ZIPs, and player-specific `.apmetfus` or `.aptmc` patches;
- validate the correct legally obtained clean ROM for either game, including ROMs with a
  legacy 512-byte copier header;
- cache the validated base ROM privately for later patches; and
- save a ready-to-run `.gba` for each player.

The on-device APWorld manager can also import trusted `.apworld` packages into
app-private storage. It validates archive paths, extraction limits, manifest
structure, and compatibility with the embedded Archipelago 0.6.8 core, then
loads the world's full option model for YAML editing and mixed-game generation.
Generated player patches are discovered through Archipelago's patch registry;
standard procedure patches which produce `.gba` files can be applied to a
checksum-validated user ROM without app-specific patch code. APWorlds are
executable Python and are not sandboxed, so only packages from trusted authors
should be installed. Imported worlds with a standard GBA `BizHawkClient` can
also provide live item/location synchronization directly. Non-standard clients,
other emulator systems, and unsupported memory domains still require explicit
compatibility work.

No base ROM is bundled, uploaded, or placed in an invitation.

### Host and share multiplayer rooms

The app has a private, persistent `archipelago.gg` website session. It can
upload generated seed ZIPs, create and start hosted rooms, refresh their
status, open authenticated room controls and the instance list inside the app,
open trackers, and explicitly synchronize its website session with a normal
browser when requested.

Player-specific `.apinvite` files contain the public room identifiers, the
selected player and slot, that player's game-specific patch, and a SHA-256
integrity hash. A recipient can open the invite to wake the room, resolve its
current port, configure the companion, patch their own clean ROM locally, and
save the finished game. Invitations never contain a base ROM, website-session
credentials, or private hosting controls.

Imported rooms are stored in a room library. The user can switch active rooms,
refresh their current server ports, retain a separate saved-ROM shortcut for
each player, or remove a local room record without deleting the hosted room.

### Launch related Android apps

- Launches a saved patched ROM in 64-bit RetroArch with the custom mGBA bridge
  core while allowing RetroArch to retain its normal configuration, controller
  mappings, overrides, and remaps.
- Opens the PopTracker Android app with the imported room's game identifier,
  active server address, selected player name, and room password.

## How it is structured

```text
Archipelago room
        ↕ WebSocket
Android companion app
        ↕ 127.0.0.1:43056 only
Custom mGBA libretro core
        ↕ emulated GBA memory
Supported GBA game in RetroArch
```

The custom core performs memory access only on mGBA's emulation thread. It
does not receive room credentials or connect to the Internet. The Android app
owns authentication, room networking, reconnection, and game-specific
logic. The emulator bridge is bound only to the local device and is never
exposed to the LAN.

## Requirements

- A 64-bit ARM Android device (`arm64-v8a`)
- Android companion package `eu.odran.archipelago`
- 64-bit RetroArch (`com.retroarch.aarch64`)
- The custom mGBA Archipelago bridge core installed in RetroArch
- A legally obtained clean ROM for the selected game (US Metroid Fusion or European The Minish Cap)
- An Archipelago room when playing online

Offline generation and ROM patching do not require an Internet connection.
Hosting, invitations, and live multiworld play do.

## Repository layout

- [`android/companion/`](android/companion/) — Android companion application,
  embedded offline generator, hosting client, invitations, and room library
- [`android/mgba-core-patch/`](android/mgba-core-patch/) — loopback bridge source
  and patches for the mGBA libretro core
- [`android/README.md`](android/README.md) — Android architecture and core
  installation overview
- [`android/companion/README.md`](android/companion/README.md) — detailed
  implementation, protocol, generator, and build documentation

## Building the Android companion

The app targets JDK 17 and packages only `arm64-v8a`. From
`android/companion`, build the debug APK with:

```powershell
.\gradlew.bat :app:assembleDebug
```

Chaquopy requires a Python 3.12 build interpreter. If it is not discoverable
automatically, provide it explicitly:

```powershell
.\gradlew.bat :app:assembleDebug "-Pchaquopy.buildPython=C:\path\to\python.exe"
```

See the [companion documentation](android/companion/README.md) for further
details. The custom core is built separately from mGBA and installed through
RetroArch's **Load Core > Install or Restore a Core** command.

## Privacy and legal notes

- No commercial ROM is included in this repository or the Android APK.
- The cached base ROM stays in private app storage and can be forgotten from
  the companion.
- The native emulator bridge listens only on Android loopback.
- The secret `archipelago.gg` website-session identifier is never included in
  ordinary invitations.
- Public room identifiers and player patches are shared only when the user
  explicitly creates an invitation.

Metroid Fusion and The Minish Cap are Nintendo properties. Archipelago, mGBA,
RetroArch, and the APWorlds retain their respective licenses and ownership. Review
the included license files before redistributing binaries or modified source.

## Upstream Archipelago

[Archipelago](https://archipelago.gg) is a framework for multiworld game
randomizers. For the official desktop application, supported-game list,
tutorials, releases, contribution guide, and community resources, use the
upstream project:

- [Official website](https://archipelago.gg)
- [Official repository](https://github.com/ArchipelagoMW/Archipelago)
- [Tutorials](https://archipelago.gg/tutorial/)
- [Official releases](https://github.com/ArchipelagoMW/Archipelago/releases)
- [Contributing guidelines](docs/contributing.md)
- [Code of conduct](docs/code_of_conduct.md)
