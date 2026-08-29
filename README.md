# Archipelago Android Companion

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
RetroArch, ArchipelagoMine, or imported APWorlds. Those projects retain
their original authorship, history, and licenses.

This fork adds a self-contained Android workflow for playing GBA, GBC, and SNES multiworlds
with [Archipelago](https://archipelago.gg). It combines an Android companion app
with RetroArch's memory interfaces so games can exchange state with an Archipelago
room without a PC. GBA/GBC uses a custom mGBA bridge; SNES uses nightly Network
Commands by default and offers a custom SNES9x bridge as an optional fallback.

The app bundles fourteen compatible worlds from the pinned Archipelago core.
The nine SNES worlds are A Link to the Past, EarthBound, Final Fantasy Mystic
Quest, Kirby's Dream Land 3, Lufia II Ancient Cave, SMZ3, Super Mario World,
Super Metroid, and Yoshi's Island. The GBA/GBC worlds are Castlevania: Circle
of the Moon, Link's Awakening DX, Mario & Luigi: Superstar Saga, Pokémon
Emerald, and Yu-Gi-Oh! 2006. They can generate, patch, and play without a manual
APWorld import. Other compatible worlds can still be added as trusted
`.apworld` files. Conventional SNES worlds use their upstream `SNIClient`
through a reusable Python-to-emulator path; conventional GBA and GB/GBC worlds
use their upstream `BizHawkClient` through the Python-to-mGBA path. Link's
Awakening DX uses a dedicated Android adapter over the same mGBA bridge.
The rest of this repository remains based on the upstream
[Archipelago project](https://github.com/ArchipelagoMW/Archipelago).

> This is an independent fork and is not an official Archipelago Android
> client. It does not include any commercial ROM.

## What it does

### Play supported emulator multiworlds on Android

- Connects RetroArch's emulated GBA or GBC memory to the companion through a
  loopback-only bridge at `127.0.0.1:43056`.
- Connects nine built-in SNES clients through RetroArch nightly's UDP Network
  Commands interface at `127.0.0.1:55355` by default.
- Retains the custom SNES9x core's frame-boundary SNI memory bridge at TCP
  `127.0.0.1:43057` as an optional mapper-independent fallback. It exposes flat
  ROM, SRAM, and WRAM domains and reports soft resets without an SNI process.
- Uses one shared emulator lifecycle for mGBA and registered SNI clients, with
  deduplicated server resynchronization after memory loss and transport reattach;
  the optional SNES9x bridge also reports explicit reset-generation changes.
- Detects compatible ROMs using built-in or imported bridge clients, then reads
  the authentication data defined by each game.
- Connects to Archipelago rooms over WebSockets, including TLS fallback.
- Provides a desktop-style client console for chat, structured server text,
  upstream client commands, and APWorld-registered commands such as KDL3's
  `/gift`. Android-owned connection and emulator commands are routed through
  the foreground bridge service.
- Reports checked locations and game completion.
- Receives and applies items, upgrades, capacities, keycards, Metroid count,
  and persistent state.
- The dynamically registered upstream Metroid Fusion APWorld client restores received upgrades after an older
  in-game save is loaded; the Android runtime adds no Metroid Fusion-specific recovery logic.
- Displays new item messages through RetroArch's
  on-screen display.
- Reconnects after RetroArch restarts, ROM reloads, app switching, suspension,
  and stale bridge connections.

The bridge runs as an Android foreground service, so split-screen mode is not
required.

### Desktop mode, multi-window, and external displays

Every companion activity is explicitly resizable and uses the live window bounds
rather than the physical display size. Compact windows use a single readable column;
expanded windows center a bounded two-column card layout instead of stretching text
across the monitor. Action groups stack before their labels become cramped, status
chips wrap, and the room library recalculates its viewport when the window height
changes. These transitions happen in place during freeform resizing, split-screen,
orientation changes, and keyboard attachment, preserving the current form and room
state instead of restarting the activity.

System-bar, display-cutout, and IME insets are applied independently for each window.
The client console retains a flexible transcript region, while the authenticated web
view resizes with its window and saves browser navigation across configuration changes
which still require recreation, such as moving between displays with different density.

### Generate and patch seeds without a PC

The companion embeds Python 3.12 and the Archipelago 0.6.8 generation core.
With a built-in or imported compatible game world, on the phone it can:

- create and edit per-player YAML documents with an independent game choice;
- remember named YAML configurations privately, load them back later, or import
  previously exported `.yaml` and `.yml` files, with each player's name and game
  shown in the saved library;
- generate single-player, same-game multiplayer, or mixed-game multiworld seeds offline;
- retain seed history, settings, ZIPs, and player-specific APWorld patches;
- ask for every clean ROM input declared by the selected APWorld and let that
  APWorld validate each file;
- cache successfully used ROM inputs privately for later patches; and
- save a ready-to-run `.gba`, `.gbc`, `.gb`, or `.sfc` for each player.

The on-device game-world manager lists the built-ins and can also import trusted
`.apworld` packages into app-private storage. It validates archive paths, extraction limits, manifest
structure, and compatibility with the embedded Archipelago 0.6.8 core, then
loads the world's full option model for YAML editing and mixed-game generation.
Generated player patches are discovered through Archipelago's patch registry;
standard procedure patches which produce `.gba`, `.gbc`, `.gb`, `.sfc`, or `.smc` files can be applied through
their registered APWorld handler without app-specific patch code. Self-connecting
games which produce no player patch can still generate, save, and host their seed
ZIP. Their player-specific companion invitations carry the selected slot, player
name, and game without a ROM patch payload.

SAF-compatible file managers show an **Archipelago Companion** storage root.
Its read-only **Saved YAMLs** and **Generated seeds** folders allow app-private
YAMLs, seed ZIPs, and player patches to be opened or copied without granting
broad storage permission. Cached base ROMs, room credentials, and private
hosting data are never exposed through this provider.
Hosted `Ship of Harkinian` players are the exception to the generic launch limitation:
the companion can select their slot and launch the Archipelago-enabled SoH Android
port directly because that game owns its server connection and needs no emulator bridge.
APWorlds are
executable Python and are not sandboxed, so only packages from trusted authors
should be installed. Imported worlds with a standard GBA, GB, or GBC `BizHawkClient` can
also provide live item/location synchronization directly. Non-standard clients,
other emulator systems, and unsupported memory domains still require explicit
compatibility work.

Oracle of Seasons APWorld `20.1.13` and Oracle of Ages APWorld `1.0.2` are
supported as trusted community imports. Their conventional GBC clients use the
bridge's system-bus and flat cartridge-ROM domains for live synchronization.

No base ROM is bundled, uploaded, or placed in an invitation.

### Host and share multiplayer rooms

The app has a private, persistent `archipelago.gg` website session. It can
upload generated seed ZIPs, create and start hosted rooms, refresh their
status, open authenticated room controls and the instance list inside the app,
open trackers, and explicitly synchronize its website session with a normal
browser when requested.

Room creation remains in the seed generator, while ongoing room management is
available from the dedicated **Hosted rooms** screen on the app's main page.
Hosted rooms are shown newest first, with room ID providing deterministic order
when creation times match or are unavailable.

Player-specific version 4 `.apinvite` files contain the public room identifiers,
selected player, slot, and game. Games which require a player patch also include
that patch and its SHA-256 integrity hash; patchless games such as Ship of
Harkinian omit it. A recipient can open the invite to wake the room, resolve its
current port, and configure the companion. The app either creates the patched ROM
locally or exposes the patchless game's direct launch action. Invitations never
contain a base ROM, website-session credentials, or private hosting controls.

Imported rooms are stored in a room library. The user can switch active rooms,
refresh their current server ports, retain a separate saved-ROM shortcut for
each player, or remove a local room record without deleting the hosted room.
Website-hosted rooms can likewise be removed from the companion's hosted list
without stopping or deleting them on archipelago.gg. Removed entries stay hidden
across refreshes and can be restored from the **Hosted rooms** screen.

### Launch related Android apps

- Launches saved patched ROMs in 64-bit RetroArch with either the custom mGBA
  bridge core or the bsnes-mercury Performance core while retaining RetroArch's normal
  configuration, controller mappings, overrides, and remaps.
- Opens the PopTracker Android app with the imported room's game identifier,
  active server address, selected player name, and room password.
- Launches the Archipelago-enabled Ship of Harkinian Android port for a selected
  hosted SoH player, passing the server, slot name, and optional password through
  explicit intent extras. SoH connects to the room directly; the mGBA bridge is not used.
- Launches The Minish Cap Android port with a generated `.aptmc` while passing the
  active room host, port, TLS state, slot name, and optional password. The existing
  `.aptmc` ROM-patching and RetroArch route remains available as a separate choice.

## How it is structured

```text
Archipelago room
        ↕ WebSocket
Android companion app
        ├─ TCP 127.0.0.1:43056 ↔ custom mGBA core ↔ GBA/GBC memory
        ├─ UDP 127.0.0.1:55355 ↔ RetroArch Network Commands ↔ SNES memory
        └─ TCP 127.0.0.1:43057 ↔ optional custom SNES9x core ↔ SNI ROM/SRAM/WRAM
```

The custom core performs memory access only on mGBA's emulation thread. It
does not receive room credentials or connect to the Internet. The Android app
owns authentication, room networking, reconnection, and APWorld client
execution. The emulator bridge is bound only to the local device and is never
exposed to the LAN.

## Requirements

- A 64-bit ARM Android device (`arm64-v8a`)
- Android companion package `eu.odran.archipelago`
- 64-bit RetroArch (`com.retroarch.aarch64`)
- The custom mGBA Archipelago bridge core installed in RetroArch
- For SNES games, an Android nightly with **Settings > Network > Network Commands**
  enabled and `bsnes_mercury_performance_libretro_android.so` installed
- Optional: `snes9x_apbridge_v1_libretro_android.so` installed through
  RetroArch's **Install or Restore a Core** action for its mapper-independent bridge
- A legally obtained clean ROM accepted by the selected game world
- For games not built in, a trusted and compatible `.apworld`
- An Archipelago room when playing online

Offline generation and ROM patching do not require an Internet connection.
Hosting, invitations, and live multiworld play do.

The Wind Waker 3.x `.aptww` files are patched with the bundled official
`tanjo3/wwrando` `ap_2.5.1` engine. The companion asks for a North American
`GZLE01` ISO and writes the patched ISO directly through Android's file picker,
so the full disc image is never copied into the app's memory or private cache.
The clean ISO location is remembered through Android's persisted document
permission and can be forgotten from the same base-ROM control as other games.

## Repository layout

- [`android/companion/`](android/companion/) — Android companion application,
  embedded offline generator, hosting client, invitations, and room library
- [`android/mgba-core-patch/`](android/mgba-core-patch/) — loopback bridge source
  and patches for the mGBA libretro core
- [`android/snes9x-core-patch/`](android/snes9x-core-patch/) — reset-stable SNI
  bridge source and patch for the SNES9x libretro core
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
details. The optional custom SNES9x core is built separately from mGBA and
installed through RetroArch's **Load Core > Install or Restore a Core** command.

## Privacy and legal notes

- No commercial ROM is included in this repository or the Android APK.
- The cached base ROM stays in private app storage and can be forgotten from
  the companion.
- The native emulator bridge listens only on Android loopback.
- The secret `archipelago.gg` website-session identifier is never included in
  ordinary invitations.
- Public room identifiers and player patches are shared only when the user
  explicitly creates an invitation.

Super Metroid, Metroid Fusion, The Minish Cap, and Link's Awakening DX are Nintendo properties. Archipelago, mGBA,
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
