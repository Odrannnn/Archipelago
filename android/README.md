# Android mGBA / RetroArch companion

This experiment separates responsibilities deliberately:

```text
Archipelago room <-> Android companion <-> 127.0.0.1:43056 <-> custom mGBA libretro core
```

The mGBA core owns emulator memory and touches it only in `retro_run()`. The
companion will own room credentials, WebSocket reconnects, user interface, and
game-specific Archipelago logic. No memory port is exposed to the LAN.

## Current state

- `mgba-core-patch/` is a source patch for mGBA's libretro core.
- `companion/` connects to that bridge and validates a loaded GBA ROM.
- `companion/` now has a Metroid Fusion profile for ArchipelagoMine APWorld
  `v1.22.4`: it recognizes the patched `MFU` ROM identifier, produces the AP
  authentication token, reads checks/area/room/receipt state, detects credits,
  and applies the APWorld's item-side RAM changes only during gameplay.
- `companion/` also has a The Minish Cap profile for APWorld `v0.3.1`, including
  ROM/seed verification, item queue injection, location flags, special Goron
  and Cucco checks, and completion reporting.
- `companion/` can install additional trusted `.apworld` packages into private
  storage for dynamic templates, mixed-game generation, registered patch
  discovery, generic standard GBA procedure patching, and live execution of
  standard GBA `BizHawkClient` implementations through the mGBA bridge.
  Imported Python is executable and unsandboxed; non-standard clients can still
  require explicit compatibility work.
- `companion/` now authenticates to the room, resolves item and location IDs
  from the server data package, delivers queued items using the ROM's receipt
  counter, reports checked locations, and reports goal completion at credits.
  Newly delivered remote items also appear in RetroArch's on-screen display
  with the sending player's current alias.
  Room credentials and Internet access remain outside the custom core.

Both supported client protocols are documented in `companion/README.md`.

## Installing the custom RetroArch core on Android

Do not configure RetroArch's Core directory to shared storage and run the
library directly from `/sdcard`. Modern Android versions restrict executable
native code in shared writable storage. Package the compiled core as a ZIP,
copy that ZIP to Downloads, and use RetroArch's **Load Core > Install or
Restore a Core** command. RetroArch then extracts the library into its private
core directory. Keep a distinct filename such as
`mgba_apbridge_v7_libretro_android.so` so the stock mGBA core is not overwritten.

For NDK r27 and older, configure CMake with:

```text
-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
```

This is required for Android devices using 16 KiB memory pages.
