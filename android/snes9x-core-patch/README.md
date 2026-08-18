# SNES9x libretro Archipelago bridge

This directory contains the reset-stable SNI memory bridge used by the Android
companion. It is based on `libretro/snes9x` commit
`890b5d445538fe790aa3add3d5702c80f551e0ae`.

Copy `archipelago_bridge.cpp` and `archipelago_bridge.h` into the upstream
`libretro/` directory, then apply
`0001-add-reset-stable-archipelago-bridge.patch` from the SNES9x repository
root.

The core binds TCP `127.0.0.1:43057` only. Requests are accepted and executed
from `retro_run()`, so reads and writes occur on the emulation thread at a frame
boundary. `retro_reset()` increments a generation counter without closing the
listener; the companion observes the new generation and replays the standard
Archipelago SNI detach/attach lifecycle, including a server `Sync` request.

The memory API uses the standard SNI/FX Pak Pro virtual domains rather than
game-specific addresses or cartridge bus mappings:

- `0x000000..0xDFFFFF`: flat ROM file offsets
- `0xE00000..0xEFFFFF`: battery SRAM, mirrored through the cartridge SRAM mask
- `0xF50000..0xF6FFFF`: 128 KiB SNES work RAM

Build the arm64 Android core with NDK r27:

```powershell
& "$env:ANDROID_NDK_HOME/ndk-build.cmd" `
  -C libretro/jni `
  NDK_PROJECT_PATH=. `
  APP_BUILD_SCRIPT=Android.mk `
  NDK_APPLICATION_MK=Application.mk `
  APP_ABI=arm64-v8a
```

Rename the resulting `libretro.so` to
`snes9x_apbridge_v1_libretro_android.so`. The Android linker flags in the patch
produce 16 KiB-aligned ELF load segments.

Copy the core to Downloads and use RetroArch's **Load Core > Install or Restore
a Core** action. The companion prefers this bridge and retains RetroArch
nightly Network Commands as a compatibility fallback.
