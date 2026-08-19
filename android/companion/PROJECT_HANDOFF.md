# Archipelago Android project handoff

Last updated: 2026-08-19

This document describes the complete Android Archipelago project, the design requirements supplied during development, what was implemented, how the pieces communicate, where the source and artifacts live, and what remains local or unfinished. It is intended to let another developer continue the work without reconstructing the project from chat history.

Machine-specific identifiers, private network addresses, and signing-resource paths are kept out of this public document. The development machine has a separate local-only inventory at `E:\Development\ARCHIPELAGO_ANDROID_LOCAL_RESOURCES.md`.

## 1. Project objective

The project turns an Android device into a self-contained Archipelago environment which can:

- generate and host multiworld seeds;
- import trusted community `.apworld` packages;
- patch supported cartridge games locally without shipping copyrighted ROMs;
- remember seeds, rooms, YAML configurations, and existing patched ROMs;
- launch the correct Android game or emulator;
- connect directly to Archipelago servers;
- run the normal upstream game client logic against Android emulator memory;
- expose a desktop-like client console and diagnostics;
- launch a native Ship of Harkinian Android port for patchless Ocarina of Time sessions;
- launch PopTracker with the selected room and player;
- support Game Boy, Game Boy Color, Game Boy Advance, SNES, Ship of Harkinian, and GameCube workflows through reusable transports rather than per-game memory hacks.

The central deliverable is the **Archipelago Android Companion**. The wider project also contains a PopTracker Android port, a Ship of Harkinian Android/Archipelago fork, custom mGBA and SNES9x libretro cores, and a custom Dolphin Android fork.

## 2. Requirements and instructions that shaped the project

The following requirements were given throughout development and should be treated as the product specification.

### General design requirements

1. Prefer generic, modular implementations over hardcoded per-game patches.
2. Preserve strict 1:1 behavior with the desktop Archipelago client whenever possible.
3. Run an APWorld's ordinary upstream client logic instead of reimplementing its memory behavior in Kotlin.
4. If unusual item or reset behavior also occurs in the desktop client, identify it as intended upstream behavior rather than silently adding Android-only recovery.
5. Remove Android-only item reconstruction or replay when it diverges from upstream. The Oracle recovery customization was explicitly removed for this reason.
6. Make new APWorlds work with as little extra adaptation as their normal transport permits.
7. Commit completed work when moving to a different task.
8. Install debug APKs on the development phone, but publish production-signed release APKs on GitHub.
9. Include the custom SNES9x and mGBA cores with companion releases.
10. Do not bundle commercial ROMs, disc images, or private Archipelago website credentials.

### Companion and hosting requirements

- Support `.apworld` files which do not patch a ROM, beginning with Ship of Harkinian.
- Generate and host patchless rooms without pretending that ROM patching is required.
- Support `.apinvite` for patchless games as well as player-specific patched games.
- Provide manual connection actions for opening a patch and then opening the emulator.
- Keep generated seeds, imported rooms, hosted rooms, and reusable YAMLs in persistent libraries.
- Move hosted-room management out of the generator UI.
- Sort stored data predictably, with the newest entries first where appropriate.
- Preserve generator form values across rotation and activity changes.
- Show the game and player names stored in saved YAML configurations.
- Expose saved YAMLs and generated seed artifacts through Android's Storage Access Framework.
- Handle release-only Python compatibility correctly; a debug-only `tkinter.TclError` workaround was not acceptable.
- Avoid stale hardcoded names such as Metroid Fusion in generic UI and error messages.

### Emulator and live-sync requirements

- Start with Super Metroid through RetroArch nightly, because stable RetroArch versions had broken or unavailable Network Commands behavior.
- Solve reset/reconnect behavior generically rather than replaying hardcoded game state.
- Reach feature parity with desktop SNI behavior and support the full built-in SNES set.
- Prefer a custom SNES9x core when RetroArch Network Commands cannot provide mapper-independent access or reset-stable behavior.
- Add a desktop-style client console for commands, chat, and informational output.
- For GameCube, investigate a generic backend which APWorld clients can use transparently.
- Instrument experimental transports so performance can be measured.
- Abandon the GDB design after testing showed that repeated Android GDB memory requests were not sustainable and configuration could interfere with Dolphin startup.
- Replace GDB with a custom Dolphin fork exposing a fast, loopback-only socket equivalent to the Dolphin Memory Engine surface needed by clients.
- Add all official built-in GameCube worlds through the generic backend. In the pinned Archipelago source, that set currently contains one world: The Wind Waker.

### Ship of Harkinian requirements

- Merge Archipelago-SoH `1.4.2` functionality into the Android Shipwright port.
- Let the game connect directly to the Archipelago server; no emulator-memory bridge is needed.
- Add an explicit Android intent and deep link so the companion can launch an already installed SoH session.
- Prefer explicit intent extras for passwords because URL parameters may appear in history and logs.
- Remove PopTracker, ROM-patching, and emulator-bridge messages from the active-room UI for Ship of Harkinian.

## 3. System architecture

```text
                              archipelago.gg
                                    |
                       room WebSocket / website HTTPS
                                    |
                    +--------------------------------+
                    | Archipelago Android Companion  |
                    | Kotlin UI + foreground service |
                    | Embedded Python 3.12 / AP 0.6.8|
                    +--------------------------------+
                       |       |       |        |
          mGBA bridge  |       |       |        | explicit Android intent
        127.0.0.1:43056 |       |       |        v
                       |       |       |   Ship of Harkinian
                       |       |       |   direct AP connection
                       |       |       |
       SNES9x bridge --+       |       +-- Dolphin memory service
        127.0.0.1:43057         |           127.0.0.1:55021
                               |
                 RetroArch Network Commands fallback
                        UDP 127.0.0.1:55355

The same companion can also launch PopTracker with game, server, slot,
and password extras. PopTracker connects to Archipelago independently.
```

The companion owns the Archipelago WebSocket for emulator-backed games. Kotlin handles Android lifecycle, storage, foreground-service behavior, and network I/O. Embedded Python runs the standard Archipelago world/client code. Each emulator backend presents the memory surface expected by the upstream client.

Ship of Harkinian is deliberately different: the companion only hosts/remembers the room and launches SoH with connection details. SoH owns its own server connection.

## 4. Component inventory

### 4.1 Archipelago Android Companion

| Item | Value |
| --- | --- |
| GitHub | <https://github.com/Odrannnn/Archipelago> |
| Upstream | <https://github.com/ArchipelagoMW/Archipelago> |
| Branch | `main` |
| Current commit | `7ff35513641eb01e2e207e201a72758c71ab6a06` |
| Android subtree | `android/companion` |
| Application ID | `eu.odran.archipelago` |
| Current version | `0.25.0`, version code `76` |
| Latest release | <https://github.com/Odrannnn/Archipelago/releases/tag/android-v0.25.0> |
| Signed APK | <https://github.com/Odrannnn/Archipelago/releases/download/android-v0.25.0/Archipelago-Companion-0.25.0-arm64-v8a-release.apk> |

#### Major features implemented

- Offline Archipelago `0.6.8` generation with embedded Python `3.12`.
- Dynamic player YAML forms generated from world option classes.
- One-player, same-game multiplayer, and mixed-game multiworld generation.
- Trusted `.apworld` import, archive validation, extraction, compatibility diagnostics, updates, and removal.
- Player patch discovery through `AutoPatchRegister` and standard `APProcedurePatch` handlers.
- Local patching for `.gba`, `.gbc`, `.gb`, `.sfc`, and `.smc` outputs.
- Multiple checksum-validated base inputs for worlds which need more than one ROM.
- Private base-ROM caching only after a complete successful patch.
- Seed history with source YAML, room ZIP, players, and patches.
- Saved YAML library with game/player metadata, import, export, restore, sorting, and deletion.
- A read-only Android `DocumentsProvider` exposing saved YAMLs and generated seeds to SAF file managers.
- Direct seed upload and room creation on `archipelago.gg`.
- Persistent hosted-room management, room controls, trackers, and server status.
- Persistent imported-room library with player-specific patched-ROM references.
- Version 3 player-specific `.apinvite` import/export with integrity validation.
- Patchless room/invite handling for self-connecting games.
- Direct RetroArch launch using the correct custom core.
- Direct Ship of Harkinian launch with safe explicit intent extras.
- Direct PopTracker launch with game and room information.
- Foreground emulator bridge service with independent emulator and server state.
- Desktop-style Archipelago client console.
- Rotation-safe and activity-safe generator drafts.
- Release signing with a stable self-managed certificate.

#### Built-in worlds

The current `bundled_worlds.json` contains:

| Platform | Worlds |
| --- | --- |
| SNES | A Link to the Past; EarthBound; Final Fantasy Mystic Quest; Kirby's Dream Land 3; Lufia II Ancient Cave; SMZ3; Super Mario World; Super Metroid; Yoshi's Island |
| GBA | Castlevania: Circle of the Moon; Mario & Luigi: Superstar Saga; Pokémon Emerald; Yu-Gi-Oh! 2006 |
| GBC | Link's Awakening DX |
| GameCube | The Wind Waker |

Community worlds exercised during development include Metroid Fusion `1.22.4`, The Minish Cap `0.3.1`, Wario Land 4 `3.4.0`, Oracle of Seasons `20.1.13`, and Oracle of Ages `1.0.2`. Imported worlds are executable code and are not guaranteed to work merely because they load.

#### Live-client architecture

The runtime has one shared client registry and emulator lifecycle:

- Conventional `client.py`/`Client.py` modules register their ordinary `BizHawkClient` or `SNIClient` handlers.
- The selected handler remains authoritative for validation, authentication, item delivery, location checks, goal state, DeathLink, data storage, and game-specific save reconciliation.
- Transport loss clears attachment state but preserves the server's received-item list, matching desktop client behavior.
- Recovery generates one deduplicated `Sync`; stable connections do not send periodic custom resyncs.
- Kotlin forwards room packets and memory operations but does not contain game addresses.

The main Python entry points are:

- `app/src/main/python/android_client_runtime.py`
- `app/src/main/python/android_bizhawk_runtime.py`
- `app/src/main/python/android_sni_runtime.py`
- `app/src/main/python/android_dolphin_runtime.py`
- `app/src/main/python/offline_generator.py`
- `app/src/main/python/world_compatibility.py`

The Android-facing transport and lifecycle classes are under:

- `app/src/main/java/eu/odran/archipelago/BridgeService.kt`
- `PythonArchipelagoSession.kt`
- `PythonGameRuntime.kt`
- `PythonSniRuntime.kt`
- `PythonDolphinRuntime.kt`
- `MGBABridgeClient.kt`
- `Snes9xBridgeClient.kt`
- `DolphinMemoryClient.kt`
- `DolphinMemoryEngineBridge.kt`
- `RetroArchNetworkClient.kt`

#### Client console

The Android console uses Archipelago's command parser and supports standard output, chat, `/help`, `/received`, `/missing`, `/items`, `/locations`, `/ready`, SNI `/slow_mode`, and commands registered by individual worlds, such as Kirby's Dream Land 3 `/gift`. Connection commands are routed through the Android foreground service. Server `PrintJSON`, logging, and command output share a bounded color-coded transcript.

#### Storage model

Persistent app-private records include:

- seed history;
- saved player YAMLs;
- hosted rooms and website session state;
- imported player rooms;
- selected patched-ROM document references;
- imported APWorld packages;
- checksum-keyed base-ROM cache in no-backup storage.

The SAF root named **Archipelago Companion** exposes only saved YAML and generated seed documents. It does not expose cached ROMs, room controls, or credentials. Clearing app data or uninstalling removes private records.

### 4.2 Ship of Harkinian Archipelago Android

| Item | Value |
| --- | --- |
| GitHub | <https://github.com/Odrannnn/Shipwright-Archipelago-Android> |
| Branch | `main` |
| Current commit | `2c4e37fbb9a7dc3bf9e6c27b5005e31d5472e9f3` |
| Application ID | `com.dishii.soh` |
| Current version | `9.2.3-ap1.4.2-p7`, version code `18` |
| Latest release | <https://github.com/Odrannnn/Shipwright-Archipelago-Android/releases/tag/v9.2.3-ap1.4.2-p7> |
| APK | <https://github.com/Odrannnn/Shipwright-Archipelago-Android/releases/download/v9.2.3-ap1.4.2-p7/soh-v9.2.3-ap1.4.2-p7.apk> |

This repository combines:

- the Android port from <https://github.com/please-be-nice/Shipwright-android>;
- the Archipelago fork from <https://github.com/HarbourMasters/Archipelago-SoH>;
- Harkipellago client work from <https://github.com/jeromkiller/Shipwright_archipellago>.

The merge added the Archipelago client/assets to the Android game, fixed Android permissions and ImGui touch integration, refreshed packaged assets, added Android soft keyboard support, and made release publishing reproducible.

The companion launches it with:

```text
component: com.dishii.soh/.MainActivity
action:    com.dishii.soh.action.CONNECT_ARCHIPELAGO
extras:    archipelago_address
           archipelago_slot
           archipelago_password  (optional)
```

The supported deep link is:

```text
soh://archipelago/connect?address=<encoded-host:port>&slot=<encoded-slot>
```

Explicit extras are preferred when a password is present. New intents also update an already running activity and reconnect with the supplied details.

SoH uses the `oot_soh.apworld` from the Archipelago-SoH release when generating on the server side. It does not receive a ROM patch from the companion and does not use PopTracker or an emulator memory bridge.

### 4.3 PopTracker Android

| Item | Value |
| --- | --- |
| GitHub | <https://github.com/Odrannnn/PopTracker-Android> |
| Upstream | <https://github.com/black-sliver/PopTracker> |
| Branch | `main` |
| Current commit | `6ddf57ce73003e0912c9bd634789d4cfaf00f9bc` |
| Application ID | `io.github.poptracker.android` |
| Version | `0.35.4-android.1` |
| Release | <https://github.com/Odrannnn/PopTracker-Android/releases/tag/v0.35.4-android.1> |
| APK | <https://github.com/Odrannnn/PopTracker-Android/releases/download/v0.35.4-android.1/PopTracker-Android-0.35.4-android.1.apk> |

The port preserves the native C++/SDL2 engine, Lua pack APIs, ZIP reader, JSON layouts, images, saves, and Archipelago networking. Android additions include SAF ZIP import, touch/hold input, pinch-to-zoom, panning, Android dialogs, lifecycle handling, and external launch extras.

The companion supplies `game`, `ap_host`, `ap_slot`, and `ap_password` to `PopTrackerActivity`. Game matching is case-insensitive and ignores punctuation/spaces. Ship of Harkinian intentionally does not offer this action because no compatible tracker pack is available.

### 4.4 Custom SNES9x libretro core

| Item | Value |
| --- | --- |
| Binary release asset | <https://github.com/Odrannnn/Archipelago/releases/download/android-v0.25.0/snes9x_apbridge_v1_libretro_android.so> |
| Listener | TCP `127.0.0.1:43057` |
| Protocol | AP bridge protocol version 1 |
| Source base | <https://github.com/libretro/snes9x> |

The bridge is mapper-independent and exposes the FX Pak Pro/SNI virtual ROM, SRAM, and WRAM domains. Requests execute inside `retro_run()` so emulator memory never crosses threads. The listener survives `retro_reset()`, and `PING` returns a monotonic reset generation so the companion can reproduce the normal SNI detach/attach lifecycle.

**Important repository status:** the modified SNES9x source is currently a local working tree with uncommitted changes and an upstream-only `origin`. It has not yet been published to an Odrannnn GitHub fork. The compiled `.so` is published with companion releases. This is a source-availability and reproducibility gap which should be resolved before broader distribution.

### 4.5 Custom mGBA libretro core

| Item | Value |
| --- | --- |
| Binary release asset | <https://github.com/Odrannnn/Archipelago/releases/download/android-v0.25.0/mgba_apbridge_v9_libretro_android.so> |
| Listener | TCP `127.0.0.1:43056` |
| Protocol | Android BizHawk bridge protocol version 6 |

The core implements atomic reads, guarded reads, writes, guarded writes, hashes, ROM-domain reads, and on-screen notifications used by ordinary BizHawk clients. It is the preferred GBA/GB/GBC transport.

**Important source status:** no mGBA bridge source checkout was found in the current `E:\Development` tree, and no corresponding source repository is linked from the release. Only the compiled core, companion-side client, and historical release copies are currently available. Recovering and publishing the exact core source should be treated as a priority before further public distribution.

### 4.6 Dolphin Archipelago Android fork

| Item | Value |
| --- | --- |
| GitHub fork | <https://github.com/Odrannnn/dolphin> |
| Upstream | <https://github.com/dolphin-emu/dolphin> |
| Development branch | <https://github.com/Odrannnn/dolphin/tree/archipelago-memory-service> |
| Current commit | `87cd6588380ee9a5fd2196a4dee3e6782c73d122` |
| Debug application ID | `eu.odran.dolphin.archipelago.debug` |
| Release application ID | `eu.odran.dolphin.archipelago` |
| Listener | TCP `127.0.0.1:55021` |

The fork adds a binary, loopback-only memory service inside Dolphin Core. It starts and stops with emulation, accepts replacement clients, does not pause the emulated CPU, and supports:

- status and game ID;
- raw reads;
- raw writes;
- ping;
- MEM1 and Wii MEM2 validation;
- transfers up to 1 MiB;
- network-byte-order headers with unchanged raw payload bytes.

The protocol is documented in `docs/ArchipelagoMemoryProtocol.md`. The companion exposes this transport under the upstream Python `dolphin_memory_engine` module name, including hook/status, raw and typed big-endian operations, pointer following, and `MemWatch` behavior.

The Wind Waker adapter calls the upstream `worlds.tww.TWWClient` implementation for slot detection, item delivery, checks, DeathLink, chart mappings, goal state, and tracker information. The generic transport has no TWW addresses.

No Dolphin binary release has been published yet. The development phone currently uses a debug APK built from this branch.

## 5. Implementation history

### Phase 1: Android PopTracker

- Added the Android Gradle/CMake wrapper around upstream PopTracker.
- Preserved the native tracker engine and pack compatibility.
- Added Android storage, dialogs, touch controls, zoom/pan, external launch support, lifecycle handling, documentation, disclaimer, and release.

Relevant commits end at `6ddf57c` in `Odrannnn/PopTracker-Android`.

### Phase 2: Companion mGBA and room workflow

- Added the custom mGBA bridge and room handshake.
- Implemented Metroid Fusion generation/sync, then generalized it into reusable imported BizHawk-client support.
- Added item notifications, ROM detection retries, reconnect handling, seed history, hosting, room sharing, `.apinvite`, imported-room storage, patch application, and tracker integration.
- Added production package naming/signing and multi-game option/template architecture.

This work spans companion commits `0d2f407c` through `bb18d38c` and the `0.9.0` through `0.19.x` releases.

### Phase 3: Bundled GB/GBC/GBA and patchless worlds

- Added built-in core games and the Link's Awakening DX adapter.
- Added modular GB/GBC APWorld support.
- Added patchless APWorld generation/hosting for self-connecting games.
- Added Ship of Harkinian launching.
- Audited LADX and removed custom Oracle recovery behavior to enforce desktop parity.

This work spans `60bdd0d1` through `4a459f76` and the `0.20.x` releases.

### Phase 4: SNES and strict desktop parity

- Added SNI client discovery and the shared emulator lifecycle.
- Began with RetroArch nightly Network Commands.
- Added the reset-stable SNES9x socket core after Network Commands proved insufficient.
- Bundled all nine pinned upstream SNES worlds and displayed them in the UI.
- Added the desktop-style client console and per-world command registration.

The primary companion commits are `64e0b723` and `b77c9301`.

### Phase 5: Storage, hosting, invites, and generator polish

- Fixed headless `tkinter.TclError` handling in production packaging.
- Added generic manual patch/open-emulator actions and patchless invites.
- Added hosted-room management and moved it out of the generator.
- Added predictable sorting.
- Added persistent saved YAML configurations with player/game metadata.
- Added read-only SAF access.
- Preserved generator values across rotation/activity changes.
- Tailored Ship of Harkinian UI to remove irrelevant tracker/patch controls.

This work spans `c6d269e0` through `84f6db5d` and releases `0.23.2` through `0.23.5`.

### Phase 6: GameCube investigation and final socket backend

- Prototyped a generic Dolphin GDB backend, configuration helper, startup fixes, and performance telemetry.
- Measured the bridge under repeated Wind Waker-like traffic.
- Determined the GDB path was not sustainable and could cause Dolphin black-screen/startup problems.
- Removed the final GDB fallback.
- Added a native Dolphin memory service and DME-compatible companion transport.
- Added request/latency/throughput/failure telemetry without logging memory values.
- Bundled The Wind Waker and reused its upstream client logic.

The historical GDB experiments remain visible in commits `60698828` through `786dcdfb`, but the final source at `6f8cfc75` and later does not use GDB. The final companion integration is `7ff35513`; Dolphin implementation commits are `8886998037` and `87cd658838`.

## 6. Development resources and paths

The development root is `E:\Development`. The authoritative local source paths are:

| Local path | Purpose | GitHub equivalent |
| --- | --- | --- |
| `E:\Development\Archipelago AndroidPort\Archipelago-Companion` | Companion fork and Android app | <https://github.com/Odrannnn/Archipelago> |
| `E:\Development\Archipelago AndroidPort` | PopTracker Android fork; also contains nested companion/research directories | <https://github.com/Odrannnn/PopTracker-Android> |
| `E:\Development\Ship of Harkinian Archipelago Android Port` | SoH Android/Archipelago source | <https://github.com/Odrannnn/Shipwright-Archipelago-Android> |
| `E:\Development\Ship of Harkinian Archipelago Android Port Build` | Separate SoH build outputs and Gradle/CMake work trees | No repository; generated files |
| `E:\Development\Dolphin Archipelago Android Port` | Dolphin fork source | <https://github.com/Odrannnn/dolphin/tree/archipelago-memory-service> |
| `E:\Development\Archipelago AndroidPort\snes9x-apbridge` | Modified SNES9x core source | Local only; not yet published |
| `E:\Development\Archipelago AndroidPort\.research` | APWorld/reproduction research | Local only; non-authoritative |
| `E:\Development\Archipelago AndroidPort\.release-staging` | Historical release staging | GitHub release assets are authoritative |

The PopTracker checkout is the outer repository around several intentionally separate nested repositories and generated directories. Do not run `git add -A` from `E:\Development\Archipelago AndroidPort` without reviewing the many untracked nested projects and test directories.

### Companion source map

```text
android/companion/
  README.md                         architecture and build notes
  PROJECT_HANDOFF.md                this document
  app/build.gradle.kts              version, ABI, signing, Chaquopy config
  app/src/main/AndroidManifest.xml  activities, providers, service, file handlers
  app/src/main/assets/              bundled world manifest
  app/src/main/java/.../            Android UI, storage, transport, lifecycle
  app/src/main/python/              pinned AP core, worlds, adapters, generator
  app/src/test/java/                Kotlin unit tests
  app/src/test/python/              Python runtime and compatibility tests
  scripts/sync_offline_generator.py refreshes embedded AP source/worlds
```

## 7. Build, test, install, and release procedures

### 7.1 Companion debug build

Requirements are JDK 17, Android SDK 35, and host Python 3.12 matching Chaquopy.

```powershell
cd 'E:\Development\Archipelago AndroidPort\Archipelago-Companion\android\companion'
.\gradlew.bat app:assembleDebug "-Pchaquopy.buildPython=C:\path\to\python.exe"
```

Output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Install over the existing debug-signed companion:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### 7.2 Companion tests

```powershell
.\gradlew.bat app:testDebugUnitTest app:lintDebug
python -m pytest app\src\test\python
python -m compileall -q app\src\main\python
```

The `0.25.0` validation also generated a real one-player TWW seed and verified the `.archipelago`/`.aptww` output, DME probing, release assembly, signing certificate, and release lint. The latest lint run had zero errors and 319 non-blocking warnings.

### 7.3 Companion release build

Release credentials are intentionally external to Git. Gradle reads a properties file from the user's Android configuration directory unless `-Parchipelago.releaseSigningProperties=...` overrides it.

```powershell
.\gradlew.bat app:assembleRelease app:lintRelease `
  "-Pchaquopy.buildPython=C:\path\to\python.exe"
```

Output:

```text
app\build\outputs\apk\release\app-release.apk
```

The production certificate SHA-256 is:

```text
24:A2:9C:FB:18:31:14:12:29:40:A4:A5:C6:57:C6:05:54:71:C6:D1:90:97:C1:6B:3C:C8:DA:56:F4:64:27:E4
```

Never commit the keystore, properties file, passwords, Archipelago website session, or cached ROMs.

### 7.4 Refreshing the embedded Archipelago source

```powershell
cd 'E:\Development\Archipelago AndroidPort\Archipelago-Companion\android\companion'
python scripts\sync_offline_generator.py
```

Review all resulting changes. The script preserves Android-specific compatibility files and documentation insertions, but upstream updates can still break APIs or APWorld imports.

### 7.5 SoH build

The documented reproducible path uses Linux/WSL2, Docker or Podman, JDK 17, Android SDK 31, NDK `26.0.10792818`, CMake, and the repository's build container:

```bash
git clone --recurse-submodules https://github.com/Odrannnn/Shipwright-Archipelago-Android.git
cd Shipwright-Archipelago-Android/docker
make setup
make build_release
```

The APK is produced under `Android/app/build/outputs/apk/release/`. The local machine also keeps a separate build tree documented in the local-only inventory.

### 7.6 Dolphin debug build

From the Dolphin source root, the Android Gradle project is under `Source/Android`:

```powershell
cd 'E:\Development\Dolphin Archipelago Android Port\Source\Android'
.\gradlew.bat app:assembleDebug
```

Output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

The fork targets Android SDK 36, NDK `29.0.14206865`, JDK 17, and arm64-v8a/x86_64. A production Dolphin signing and release workflow still needs to be defined.

### 7.7 SNES9x core build

The local source includes `libretro/jni/Android.mk`, `Application.mk`, and the bridge source. Build an arm64 libretro core with Android NDK `ndk-build`, then rename the result to:

```text
snes9x_apbridge_v1_libretro_android.so
```

Before relying on this for reproducible releases, commit the local changes to a user-owned fork and document the exact NDK command and source commit.

## 8. Release 0.25.0 integrity information

| Asset | SHA-256 |
| --- | --- |
| `Archipelago-Companion-0.25.0-arm64-v8a-release.apk` | `1AD336E706FF249C9BCE1D015A1996646CCE5CA915963F72E42380156BC9A1C0` |
| `snes9x_apbridge_v1_libretro_android.so` | `FC6F2EAE9CC64D9074C0D325B569A4C390A88589A7864FBA0538EEE1AA0A54F0` |
| `mgba_apbridge_v9_libretro_android.so` | `891272637832CC109A9D2E7505ECF8D32435B545439E1C0160DF51FCF07EAD30` |

The `android-v0.25.0` tag and GitHub release point to companion commit `7ff35513641eb01e2e207e201a72758c71ab6a06`.

## 9. Current limitations and follow-up work

1. **Publish SNES9x source.** The custom core source is local and uncommitted; only the binary is public.
2. **Recover/publish mGBA source.** The exact custom core source is not present in the current development tree.
3. **Publish a Dolphin APK.** The memory-service branch is public, but only a local debug APK exists.
4. **Perform a full TWW live-session test.** Unit/protocol/generation tests pass, but final validation should use a matching randomized TWW image, the custom Dolphin fork, and a real hosted room.
5. **Add future GameCube worlds through adapters.** The transport is generic, but each world still needs its normal upstream client bundled and an adapter when it is not automatically discoverable. The pinned built-in distribution currently has only TWW.
6. **Preserve desktop parity.** Do not add game-specific replay/recovery merely because a one-player seed appears surprising; reproduce the desktop client first.
7. **Keep APWorld trust explicit.** Imported APWorlds execute Python and may require dependencies unavailable in the APK.
8. **Keep ROM handling legal and private.** No release should contain or expose a base ROM, patched ROM, or disc image.
9. **Review lint debt.** There are no current lint errors, but the warning set should be reduced gradually without blocking releases.

## 10. Safe continuation checklist

Before starting work:

1. Read this document and `android/companion/README.md`.
2. Run `git status -sb` in the exact repository being changed; several nested repositories exist.
3. Confirm the current companion, SoH, and Dolphin branch heads against GitHub.
4. Never use the PopTracker outer repository to stage nested companion or core trees.
5. Keep upstream world-client behavior authoritative.
6. Add transport functionality generically and game behavior in upstream-compatible adapters.
7. Add focused Python and Kotlin tests for reconnect/reset behavior.
8. Build and install a debug APK on the phone for interactive testing.
9. Build releases with the permanent production key and verify the certificate before upload.
10. Commit each coherent task separately and include all legally redistributable source for released native cores.

