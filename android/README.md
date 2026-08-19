# Android emulator / RetroArch companion

This experiment separates responsibilities deliberately:

```text
Archipelago room <-> Android companion <-> custom mGBA or SNES9x bridge core
```

The mGBA core owns emulator memory and touches it only in `retro_run()`. The
companion will own room credentials, WebSocket reconnects, user interface, and
game-specific Archipelago logic. No memory port is exposed to the LAN.

## Current state

- `mgba-core-patch/` is a source patch for mGBA's libretro core.
- `snes9x-core-patch/` is a reset-stable SNI-domain bridge for SNES9x.
- `companion/` connects to those bridges for GBA/GBC and SNES. RetroArch
  nightly UDP port 55355 remains a fallback for SNES cores without the bridge.
- `companion/` bundles the compatible upstream Castlevania: Circle of the Moon,
  Link's Awakening DX, Mario & Luigi: Superstar Saga, Pokémon Emerald, and
  Super Metroid, and Yu-Gi-Oh! 2006 worlds for generation, ROM patching, and
  live-client execution.
- Link's Awakening DX uses an Android adapter for its custom client protocol,
  carried over the same loopback bridge without RetroArch Network Commands.
- Imported Metroid Fusion `1.22.4` and The Minish Cap `0.3.1` APWorlds register
  their conventional upstream `BizHawkClient` implementations at runtime. The
  companion discovers and executes those handlers generically; ROM identity,
  authentication, checks, item delivery, save restoration, and completion
  behavior remain owned by each APWorld rather than Android game profiles.
- `companion/` can install additional trusted `.apworld` packages into private
  storage for dynamic templates, mixed-game generation, registered patch
  discovery, generic standard GBA/GB/GBC procedure patching, and live execution of
  standard GBA, GB, and GBC `BizHawkClient` implementations through the mGBA bridge.
  Imported Python is executable and unsandboxed; non-standard clients can still
  require explicit compatibility work.
- Oracle of Seasons APWorld `20.1.13` and Oracle of Ages APWorld `1.0.2` are
  compatible community imports. Protocol 6 supplies their flat BizHawk `ROM`
  domain for patched-ROM detection and embedded slot authentication.
- ROM patching discovers every checksum-validated user file declared by an
  APWorld. This supports multi-ROM handlers such as Oracle of Seasons cross-item
  mode without adding game or filename branches to the companion.
- The seed generator keeps named YAML configurations in app-private storage and
  can validate, remember, and reload existing `.yaml` or `.yml` files.
- Player-specific `.apinvite` files can omit the patch for self-connecting games.
  Ship of Harkinian invites retain the selected slot, player name, and game so
  the recipient can resolve the room and use the direct SoH launch action.
- Website-hosted rooms can be removed from the companion without deleting the
  remote room. Local removals persist across refreshes and can be restored from
  the dedicated **Hosted rooms** screen on the app's main page.
- `companion/` now authenticates to the room, resolves item and location IDs
  from the server data package, delivers queued items using the ROM's receipt
  counter, reports checked locations, and reports goal completion at credits.
  Newly delivered remote items also appear in RetroArch's on-screen display
  with the sending player's current alias.
  Room credentials and Internet access remain outside the custom core.

The supported client runtime is documented in `companion/README.md`.

## SNES games through the custom SNES9x core

Install `snes9x_apbridge_v1_libretro_android.so` through RetroArch's **Install
or Restore a Core** action. The companion launches `.sfc` content with that
core and talks to its loopback TCP bridge on port 43057. The bridge handles
flat SNI ROM, SRAM, and WRAM addresses at emulated-frame boundaries and exposes
a reset-generation counter which remains available across `retro_reset()`.
Registered SNI clients keep the desktop client's device lifecycle, ROM
validation, handler registry, item state, and write-buffer behavior. No SNI
process or separate network bridge app is required.

## Installing the custom RetroArch core on Android

Do not configure RetroArch's Core directory to shared storage and run the
library directly from `/sdcard`. Modern Android versions restrict executable
native code in shared writable storage. Copy the uncompressed `.so` to
Downloads, then use RetroArch's **Load Core > Install or Restore a Core**
command to copy it into RetroArch's private core directory. Keep a distinct
filename such as
`mgba_apbridge_v9_libretro_android.so` or
`snes9x_apbridge_v1_libretro_android.so` so a stock core is not overwritten.

For NDK r27 and older, configure CMake with:

```text
-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
```

This is required for Android devices using 16 KiB memory pages.
