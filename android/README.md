# Android mGBA / RetroArch experiment

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
- `companion/` now authenticates to the room, resolves item and location IDs
  from the server data package, delivers queued items using the ROM's receipt
  counter, reports checked locations, and reports goal completion at credits.
  Room credentials and Internet access remain outside the custom core.

## Next decision

Metroid Fusion is the first supported target: ArchipelagoMine APWorld `v1.22.4`.
Its selected client protocol is documented in `companion/README.md`.

## Installing the custom RetroArch core on Android

Do not configure RetroArch's Core directory to shared storage and run the
library directly from `/sdcard`. Modern Android versions restrict executable
native code in shared writable storage. Package the compiled core as a ZIP,
copy that ZIP to Downloads, and use RetroArch's **Load Core > Install or
Restore a Core** command. RetroArch then extracts the library into its private
core directory. Keep a distinct filename such as
`mgba_apbridge_libretro_android.so` so the stock mGBA core is not overwritten.

For NDK r27 and older, configure CMake with:

```text
-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
```

This is required for Android devices using 16 KiB memory pages.
